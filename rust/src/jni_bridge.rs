use crate::engine;
use jni::objects::{JClass, JString};
use jni::sys::{jstring};
use jni::JNIEnv;
use std::path::Path;
use std::os::unix::prelude::RawFd;

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
        Err(error) => return return_error(&mut env, error),
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
        Err(error) => return return_error(&mut env, error),
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
