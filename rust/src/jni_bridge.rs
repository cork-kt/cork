/*
 * Copyright 2026 Ishan09811
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

use crate::engine;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::path::Path;
use std::os::unix::prelude::RawFd;

const INPUT_ENTRY_SIG: &str = "Lcork/utils/SafUtils$SafInputEntry;";
const ENTRY_PATH_SIG: &str = "Ljava/lang/String;";

struct SafInputApi {
    next_entry: jni::objects::JMethodID,
    path: Option<jni::objects::JFieldID>,
    fd: Option<jni::objects::JFieldID>,
}

struct SafOutputApi {
    on_entry: jni::objects::JMethodID,
}

fn close_fd(fd: jint) {
    if fd >= 0 {
        unsafe { libc::close(fd as RawFd) };
    }
}

fn get_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|err| format!("invalid Java string: {err}"))
}

fn return_error(env: &mut JNIEnv<'_>, message: String) -> jstring {
    match env.new_string(message) {
        Ok(jstr) => jstr.into_raw(),
        Err(_) => std::ptr::null_mut(), 
    }
}

fn pending_java_exception(env: &mut JNIEnv<'_>) -> bool {
    env.exception_check().unwrap_or(false)
}

fn cache_saf_input_api(env: &mut JNIEnv<'_>, source: &JObject<'_>) -> Result<SafInputApi, String> {
    let class = env
        .get_object_class(source)
        .map_err(|err| format!("unable to resolve SAF input class: {err}"))?;

    let next_entry = env
        .get_method_id(&class, "nextEntry", format!("(){INPUT_ENTRY_SIG}"))
        .map_err(|err| format!("unable to resolve SAF input nextEntry(): {err}"))?;
    
    Ok(SafInputApi {
        next_entry,
        path: None,
        fd: None,
    })
}

fn cache_saf_output_api(env: &mut JNIEnv<'_>, sink: &JObject<'_>) -> Result<SafOutputApi, String> {
    let class = env
        .get_object_class(sink)
        .map_err(|err| format!("unable to resolve SAF output class: {err}"))?;

    let on_entry = env
        .get_method_id(&class, "onEntry", "(Ljava/lang/String;Z)I")
        .map_err(|err| format!("unable to resolve SAF output onEntry(): {err}"))?;

    Ok(SafOutputApi { on_entry })
}

fn next_saf_entry(
    env: &mut JNIEnv<'_>,
    source: &JObject<'_>,
    api: &mut SafInputApi,
) -> Result<Option<engine::SafInputEntry>, String> {
    let value = unsafe {
        env.call_method_unchecked(
            source,
            api.next_entry,
            jni::signature::ReturnType::Object,
            &[],
        )
    }
    .map_err(|err| format!("SAF input callback failed: {err}"))?;

    if pending_java_exception(env) {
        return Err("SAF input callback raised a Java exception".to_owned());
    }

    let entry = value
        .l()
        .map_err(|err| format!("invalid SAF input callback result: {err}"))?;
    if entry.is_null() {
        return Ok(None);
    }

    if api.path.is_none() || api.fd.is_none() {
        let entry_class = env
            .get_object_class(&entry)
            .map_err(|err| format!("unable to resolve SAF entry class: {err}"))?;

        api.path = Some(
            env.get_field_id(&entry_class, "path", ENTRY_PATH_SIG)
                .map_err(|err| format!("unable to resolve SAF entry path field: {err}"))?,
        );
        api.fd = Some(
            env.get_field_id(&entry_class, "fd", "I")
                .map_err(|err| format!("unable to resolve SAF entry fd field: {err}"))?,
        );
    }

    let fd_field = api.fd.expect("SAF fd field must be cached");
    let path_field = api.path.expect("SAF path field must be cached");

    let fd = match {
        env.get_field_unchecked(
            &entry,
            fd_field,
            jni::signature::ReturnType::Primitive(jni::signature::Primitive::Int),
        )
    }
    .and_then(|value| value.i())
    {
        Ok(fd) => fd,
        Err(err) => {
            let _ = env.delete_local_ref(entry);
            return Err(format!("unable to read SAF entry fd: {err}"));
        }
    };

    let path_value = match {
        env.get_field_unchecked(
            &entry,
            path_field,
            jni::signature::ReturnType::Object,
        )
    } {
        Ok(value) => value,
        Err(err) => {
            close_fd(fd);
            let _ = env.delete_local_ref(entry);
            return Err(format!("unable to read SAF entry path: {err}"));
        }
    };
    let path_obj = match path_value.l() {
        Ok(value) => value,
        Err(err) => {
            close_fd(fd);
            let _ = env.delete_local_ref(entry);
            return Err(format!("invalid SAF entry path field: {err}"));
        }
    };
    let path_string = JString::from(path_obj);
    let path = match get_string(env, path_string) {
        Ok(path) => path,
        Err(err) => {
            close_fd(fd);
            let _ = env.delete_local_ref(entry);
            return Err(err);
        }
    };

    let _ = env.delete_local_ref(entry);
    Ok(Some(engine::SafInputEntry {
        path,
        is_directory: fd < 0,
        fd: fd as RawFd,
    }))
}

fn write_saf_entry(
    env: &mut JNIEnv<'_>,
    sink: &JObject<'_>,
    api: &SafOutputApi,
    path: &str,
    directory: bool,
) -> Result<RawFd, String> {
    let path = env
        .new_string(path)
        .map_err(|err| format!("unable to allocate SAF entry path: {err}"))?;
    let path_object = JObject::from(path);

    let args = [
        JValue::Object(&path_object).as_jni(),
        JValue::Bool(directory as u8).as_jni(),
    ];

    let value = unsafe {
        env.call_method_unchecked(
            sink,
            api.on_entry,
            jni::signature::ReturnType::Primitive(jni::signature::Primitive::Int),
            &args,
        )
    }
    .map_err(|err| format!("SAF output callback failed: {err}"))?;

    if pending_java_exception(env) {
        return Err("SAF output callback raised a Java exception".to_owned());
    }

    value
        .i()
        .map(|fd| fd as RawFd)
        .map_err(|err| format!("invalid SAF output callback result: {err}"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_compress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JString<'_>,
    output: JString<'_>,
    format: i32,
    level: i32,
    threads: i32
) -> jstring {
    let input = match get_string(&mut env, input) {
        Ok(value) => value,
        Err(error) => return return_error(&mut env, error),
    };

    let output = match get_string(&mut env, output) {
        Ok(value) => value,
        Err(error) => return return_error(&mut env, error),
    };

    match engine::compress(
        Path::new(&input),
        Path::new(&output),
        format,
        level,
        threads
    ) {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_compressToFd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JString<'_>,
    output_fd: jni::sys::jint,
    format: i32,
    level: i32,
    threads: i32,
) -> jstring {
    let input = match get_string(&mut env, input) {
        Ok(value) => value,
        Err(error) => { 
            close_fd(output_fd);
            return return_error(&mut env, error) 
        }
    };

    match engine::compress_to_fd(
        Path::new(&input),
        output_fd as RawFd,
        format,
        level,
        threads,
    ) {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_compressFdToFd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_fd: jint,
    output_fd: jint,
    format: i32,
    level: i32,
    threads: i32,
    entry_name: JString<'_>,
) -> jstring {
    let entry_name = match get_string(&mut env, entry_name) {
        Ok(value) if !value.is_empty() => value,
        Ok(_) => "input".to_owned(),
        Err(error) => {
            close_fd(input_fd);
            close_fd(output_fd);
            return return_error(&mut env, error);
        }
    };

    match engine::compress_fd_to_fd(
        input_fd as RawFd,
        output_fd as RawFd,
        format,
        level,
        threads,
        &entry_name,
    ) {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_compressTreeToFd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    output_fd: jint,
    format: i32,
    level: i32,
    threads: i32,
    source: JObject<'_>,
) -> jstring {
    let mut api = match cache_saf_input_api(&mut env, &source) {
        Ok(api) => api,
        Err(error) => {
            close_fd(output_fd);
            return return_error(&mut env, error);
        }
    };

    let result = engine::compress_tree_to_fd(
        output_fd as RawFd,
        format,
        level,
        threads,
        || {
            match next_saf_entry(&mut env, &source, &mut api) {
                Ok(Some(entry)) => Ok(Some(entry)),
                Ok(None) => Ok(None),
                Err(error) => Err(crate::error::CorkError::Archive(error)),
            }
        },
    );

    match result {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_decompress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JString<'_>,
    output_directory: JString<'_>,
    threads: i32
) -> jstring {
    let input: String = match get_string(&mut env, input) {
        Ok(value) => value,
        Err(error) => return return_error(&mut env, error),
    };

    let output_directory = match get_string(&mut env, output_directory) {
        Ok(value) => value,
        Err(error) => return return_error(&mut env, error),
    };

    match engine::decompress(
        Path::new(&input),
        Path::new(&output_directory),
        threads
    ) {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_decompressFromFd(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    archive_fd: jni::sys::jint,
    output_directory: JString<'_>,
    threads: i32,
) -> jstring {
    let output_directory = match get_string(&mut env, output_directory) {
        Ok(value) => value,
        Err(error) => { 
            close_fd(archive_fd);
            return return_error(&mut env, error) 
        }
    };

    match engine::decompress_from_fd(
        archive_fd as RawFd,
        Path::new(&output_directory),
        threads,
    ) {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_cork_CorkNative_decompressToTree(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    archive_fd: jint,
    threads: i32,
    sink: JObject<'_>,
) -> jstring {
    let api = match cache_saf_output_api(&mut env, &sink) {
        Ok(api) => api,
        Err(error) => {
            close_fd(archive_fd);
            return return_error(&mut env, error);
        }
    };

    let result = engine::decompress_to_tree(
        archive_fd as RawFd,
        threads,
        |path, directory| write_saf_entry(&mut env, &sink, &api, path, directory)
            .map_err(crate::error::CorkError::Archive),
    );

    match result {
        Ok(_) => std::ptr::null_mut(),
        Err(error) => return_error(&mut env, error.to_string()),
    }
}
