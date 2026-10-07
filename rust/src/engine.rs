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

use crate::error::CorkError;
use lzma_rust2::{LzmaOptions, LzmaReader, LzmaWriter};
use std::fs::{self, File};
use std::io::{self, BufReader, BufWriter, Read, Write, Seek};
use std::os::fd::{FromRawFd, RawFd};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

pub const FORMAT_AUTO: i32 = 0;
pub const FORMAT_ZIP: i32 = 1;
pub const FORMAT_7Z: i32 = 2;
pub const FORMAT_LZMA: i32 = 3;
pub const FORMAT_LZMA2: i32 = 4;

fn owned_file_from_fd(fd: RawFd) -> Result<File, CorkError> {
    if fd < 0 {
        return Err(CorkError::Io(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("invalid file descriptor: {fd}"),
        )));
    }

    Ok(unsafe { File::from_raw_fd(fd) })
}

fn rewind_required(file: &mut File) -> Result<(), CorkError> {
    use std::io::Seek;
    file.seek(io::SeekFrom::Start(0))?;
    Ok(())
}

fn reset_output(file: &mut File) -> Result<(), CorkError> {
    use std::io::Seek;
    file.set_len(0)?;
    file.seek(io::SeekFrom::Start(0))?;
    Ok(())
}

fn ensure_input_output_differ(input: &Path, output: &File) -> Result<(), CorkError> {
    let input_file = File::open(input)?;
    let input_metadata = input_file.metadata()?;
    let output_metadata = output.metadata()?;

    if input_metadata.dev() == output_metadata.dev()
        && input_metadata.ino() == output_metadata.ino()
    {
        return Err(CorkError::InvalidFormat(
            "input and output refer to the same file".to_owned(),
        ));
    }

    Ok(())
}

pub struct SafInputEntry {
    pub path: String,
    pub is_directory: bool,
    pub fd: RawFd,
}

pub fn compress(
    input: &Path,
    output: &Path,
    format: i32,
    level: i32,
    requested_threads: i32
) -> Result<u64, CorkError> {
    if !input.exists() {
        return Err(CorkError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("input does not exist: {}", input.display()),
        )));
    }

    if input == output {
        return Err(CorkError::InvalidFormat(
            "input and output paths must differ".to_owned(),
        ));
    }

    if let Some(parent) = output.parent() {
        fs::create_dir_all(parent)?;
    }

    match format {
        FORMAT_ZIP => compress_zip(input, output, level),
        FORMAT_7Z => compress_7z(input, output, level, resolve_threads(requested_threads)),
        FORMAT_LZMA => compress_lzma(input, output, level),
        FORMAT_LZMA2 => Err(CorkError::Unsupported(
            "standalone LZMA2 is reserved for the streaming codec API".to_owned(),
        )),
        FORMAT_AUTO => Err(CorkError::InvalidFormat(
            "Auto cannot be used when compressing".to_owned(),
        )),
        other => Err(CorkError::InvalidFormat(format!(
            "unknown container format id: {other}"
        ))),
    }
}

pub fn compress_to_fd(
    input: &Path,
    output_fd: RawFd,
    format: i32,
    level: i32,
    requested_threads: i32,
) -> Result<u64, CorkError> {
    let mut output = owned_file_from_fd(output_fd)?;

    if !input.exists() {
        return Err(CorkError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("input does not exist: {}", input.display()),
        )));
    }

    ensure_input_output_differ(input, &output)?;
    output.set_len(0)?;
    use std::io::Seek;
    output.seek(io::SeekFrom::Start(0))?;

    match format {
        FORMAT_ZIP => compress_zip_to_writer(input, output, level),
        FORMAT_7Z => compress_7z_to_writer(
            input,
            output,
            level,
            resolve_threads(requested_threads),
        ),
        FORMAT_LZMA => compress_lzma_to_writer(input, output, level),
        FORMAT_LZMA2 => Err(CorkError::Unsupported(
            "standalone LZMA2 is reserved for the streaming codec API".to_owned(),
        )),
        FORMAT_AUTO => Err(CorkError::InvalidFormat(
            "Auto cannot be used when compressing".to_owned(),
        )),
        other => Err(CorkError::InvalidFormat(format!(
            "unknown container format id: {other}"
        ))),
    }
}

pub fn compress_fd_to_fd(
    input_fd: RawFd,
    output_fd: RawFd,
    format: i32,
    level: i32,
    requested_threads: i32,
    entry_name: &str,
) -> Result<u64, CorkError> {
    if input_fd < 0 || output_fd < 0 {
        if input_fd >= 0 {
            unsafe { libc::close(input_fd) };
        }
        if output_fd >= 0 && output_fd != input_fd {
            unsafe { libc::close(output_fd) };
        }
        return Err(CorkError::Io(io::Error::new(
            io::ErrorKind::InvalidInput,
            "invalid SAF file descriptor",
        )));
    }

    if input_fd == output_fd {
        unsafe { libc::close(input_fd) };
        return Err(CorkError::InvalidFormat(
            "SAF input and output descriptors must differ".to_owned(),
        ));
    }

    let input = owned_file_from_fd(input_fd)?;
    let mut output = owned_file_from_fd(output_fd)?;

    match format {
        FORMAT_ZIP => {
            reset_output(&mut output)?;
            compress_zip_from_reader(input, output, level, Some(entry_name))
        }
        FORMAT_7Z => {
            reset_output(&mut output)?;
            compress_7z_from_reader(
                input,
                output,
                level,
                resolve_threads(requested_threads),
                Some(entry_name),
            )
        }
        FORMAT_LZMA => compress_lzma_reader_to_writer(input, output, None, level),
        FORMAT_LZMA2 => Err(CorkError::Unsupported(
            "standalone LZMA2 is reserved for the streaming codec API".to_owned(),
        )),
        FORMAT_AUTO => Err(CorkError::InvalidFormat(
            "Auto cannot be used when compressing".to_owned(),
        )),
        other => Err(CorkError::InvalidFormat(format!(
            "unknown container format id: {other}"
        ))),
    }
}

pub fn compress_tree_to_fd<F>(
    output_fd: RawFd,
    format: i32,
    level: i32,
    requested_threads: i32,
    mut next_entry: F,
) -> Result<u64, CorkError>
where
    F: FnMut() -> Result<Option<SafInputEntry>, CorkError>,
{
    let mut output = owned_file_from_fd(output_fd)?;
    reset_output(&mut output)?;

    match format {
        FORMAT_ZIP => {
            let mut archive = ZipWriter::new(BufWriter::new(output));
            let options = zip_options(level);

            loop {
                let Some(entry) = next_entry()? else { break };
                let source = if entry.is_directory {
                    None
                } else {
                    Some(owned_file_from_fd(entry.fd)?)
                };
                let safe_name = match validate_entry_name(&entry.path) {
                    Ok(name) => name,
                    Err(error) => {
                        drop(source);
                        return Err(error);
                    }
                };

                if entry.is_directory {
                    archive.add_directory(format!("{safe_name}/"), options)?;
                } else {
                    let mut source = BufReader::new(source.expect("file entry must have a source"));
                    archive.start_file(safe_name, options)?;
                    io::copy(&mut source, &mut archive)?;
                }
            }

            let target = archive.finish()?;
            let mut target = target.into_inner().map_err(|err| CorkError::Io(err.into_error()))?;
            target.flush()?;
            Ok(target.metadata()?.len())
        }
        FORMAT_7Z => {
            use sevenz_rust2::{encoder_options::Lzma2Options, ArchiveEntry, ArchiveWriter};

            let mut writer = ArchiveWriter::new(output)?;
            let options = Lzma2Options::from_level_mt(
                level.clamp(0, 9) as u32,
                resolve_threads(requested_threads),
                8 * 1024 * 1024,
            );
            writer.set_content_methods(vec![options.into()]);

            loop {
                let Some(entry) = next_entry()? else { break };
                let source = if entry.is_directory {
                    None
                } else {
                    Some(owned_file_from_fd(entry.fd)?)
                };
                let safe_name = match validate_entry_name(&entry.path) {
                    Ok(name) => name,
                    Err(error) => {
                        drop(source);
                        return Err(error);
                    }
                };

                if entry.is_directory {
                    writer.push_archive_entry(
                        ArchiveEntry::new_directory(&safe_name),
                        None::<File>,
                    )?;
                } else {
                    writer.push_archive_entry(
                        ArchiveEntry::new_file(&safe_name),
                        Some(source.expect("file entry must have a source")),
                    )?;
                }
            }

            let mut target = writer.finish()?;
            Ok(target.stream_position()?)
        }
        FORMAT_LZMA | FORMAT_LZMA2 => Err(CorkError::Unsupported(
            "SAF tree input is only valid for ZIP and 7z; standalone LZMA is a single-file stream".to_owned(),
        )),
        FORMAT_AUTO => Err(CorkError::InvalidFormat(
            "Auto cannot be used when compressing".to_owned(),
        )),
        other => Err(CorkError::InvalidFormat(format!(
            "unknown container format id: {other}"
        ))),
    }
}

pub fn decompress(
    input: &Path, 
    output_dir: &Path,
    requested_threads: i32
) -> Result<u64, CorkError> {
    if !input.is_file() {
        return Err(CorkError::Io(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("archive is not a file: {}", input.display()),
        )));
    }

    fs::create_dir_all(output_dir)?;

    match detect_format(input)? {
        FORMAT_ZIP => decompress_zip(input, output_dir),
        FORMAT_7Z => decompress_7z(input, output_dir, resolve_threads(requested_threads)),
        FORMAT_LZMA => decompress_lzma(input, output_dir),
        other => Err(CorkError::Unsupported(format!(
            "automatic decompression does not recognize format id {other}"
        ))),
    }
}

pub fn decompress_from_fd(
    archive_fd: RawFd,
    output_dir: &Path,
    requested_threads: i32,
) -> Result<u64, CorkError> {
    let mut archive = owned_file_from_fd(archive_fd)?;
    fs::create_dir_all(output_dir)?;

    use std::io::Seek;
    archive.seek(io::SeekFrom::Start(0))?;

    let format = detect_format_reader(&mut archive, None)?;
    match format {
        FORMAT_ZIP => decompress_zip_from_reader(archive, output_dir),
        FORMAT_7Z => decompress_7z_from_reader(
            archive,
            output_dir,
            resolve_threads(requested_threads),
        ),
        FORMAT_LZMA => decompress_lzma_from_reader(archive, output_dir, None),
        other => Err(CorkError::Unsupported(format!(
            "decompression does not recognize format id {other}"
        ))),
    }
}

pub fn decompress_to_tree<F>(
    archive_fd: RawFd,
    requested_threads: i32,
    mut on_entry: F,
) -> Result<u64, CorkError>
where
    F: FnMut(&str, bool) -> Result<RawFd, CorkError>,
{
    let mut archive = owned_file_from_fd(archive_fd)?;
    rewind_required(&mut archive)?;
    let format = detect_format_reader(&mut archive, None)?;
    rewind_required(&mut archive)?;
    match format {
        FORMAT_ZIP => decompress_zip_to_tree(archive, &mut on_entry),
        FORMAT_7Z => decompress_7z_to_tree(archive, resolve_threads(requested_threads), &mut on_entry),
        FORMAT_LZMA => decompress_lzma_to_tree(archive, &mut on_entry),
        other => Err(CorkError::Unsupported(format!(
            "decompression: unable to recognize format id {other}"
        ))),
    }
}

fn detect_format(path: &Path) -> Result<i32, CorkError> {
    let mut file = File::open(path)?;
    detect_format_reader(&mut file, path.extension().and_then(|ext| ext.to_str()))
}

fn detect_format_reader<R: Read + io::Seek>(reader: &mut R, extension_hint: Option<&str>) -> Result<i32, CorkError> {
    let position = reader.stream_position()?;
    let mut magic = [0u8; 8];
    let read = reader.read(&mut magic)?;
    reader.seek(io::SeekFrom::Start(position))?;

    if read >= 4
        && (magic[..4] == *b"PK\x03\x04"
            || magic[..4] == *b"PK\x05\x06"
            || magic[..4] == *b"PK\x07\x08")
    {
        return Ok(FORMAT_ZIP);
    }

    if read >= 6 && magic[..6] == [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C] {
        return Ok(FORMAT_7Z);
    }

    if extension_hint.is_some_and(|ext| ext.eq_ignore_ascii_case("lzma")) {
        return Ok(FORMAT_LZMA);
    }

    Err(CorkError::Unsupported("unknown archive signature".to_owned()))
}

fn compress_lzma(input: &Path, output: &Path, level: i32) -> Result<u64, CorkError> {
    let target = File::create(output)?;
    let written = compress_lzma_to_writer(input, target, level)?;
    Ok(written)
}

fn compress_lzma_to_writer<W: Write>(input: &Path, target: W, level: i32) -> Result<u64, CorkError> {
    if !input.is_file() {
        return Err(CorkError::Unsupported(
            "standalone .lzma compression accepts a single file".to_owned(),
        ));
    }

    let input_size = input.metadata()?.len();
    let source = File::open(input)?;
    let options = LzmaOptions::with_preset(level.clamp(0, 9) as u32);
    let counting = CountingWriter::new(target);
    let mut writer = LzmaWriter::new_use_header(
        BufWriter::new(counting),
        &options,
        Some(input_size),
    )?;

    let mut source = BufReader::new(source);
    io::copy(&mut source, &mut writer)?;
    let mut counting = writer.finish()?;
    counting.flush()?;
    Ok(counting.get_ref().bytes_written())
}

fn compress_lzma_reader_to_writer<R: Read, W: Write>(
    source: R,
    target: W,
    input_size: Option<u64>,
    level: i32,
) -> Result<u64, CorkError> {
    let options = LzmaOptions::with_preset(level.clamp(0, 9) as u32);
    let counting = CountingWriter::new(target);
    let mut writer = LzmaWriter::new_use_header(
        BufWriter::new(counting),
        &options,
        input_size,
    )?;

    let mut source = BufReader::new(source);
    io::copy(&mut source, &mut writer)?;
    let mut target = writer.finish()?;
    target.flush()?;
    Ok(target.get_ref().bytes_written())
}

fn decompress_lzma(input: &Path, output_dir: &Path) -> Result<u64, CorkError> {
    let name = input
        .file_stem()
        .ok_or_else(|| CorkError::InvalidFormat("invalid .lzma filename".to_owned()))?;

    let source = File::open(input)?;
    decompress_lzma_from_reader(source, output_dir, Some(name))
}

fn decompress_lzma_from_reader<R: Read>(
    source: R,
    output_dir: &Path,
    output_name: Option<&std::ffi::OsStr>,
) -> Result<u64, CorkError> {
    let output = output_dir.join(output_name.unwrap_or_else(|| std::ffi::OsStr::new("output")));
    let mut reader = LzmaReader::new_mem_limit(BufReader::new(source), 1024 * 1024, None)?;
    let target = File::create(&output)?;
    let mut target = BufWriter::new(target);

    io::copy(&mut reader, &mut target)?;
    target.flush()?;

    Ok(output.metadata()?.len())
}

fn decompress_lzma_to_tree<F>(
    source: File,
    on_entry: &mut F,
) -> Result<u64, CorkError>
where
    F: FnMut(&str, bool) -> Result<RawFd, CorkError>,
{
    let output_name = "output";
    let fd = on_entry(output_name, false)?;
    let mut target = owned_file_from_fd(fd)?;
    let mut reader = LzmaReader::new_mem_limit(BufReader::new(source), 1024 * 1024, None)?;
    let copied = io::copy(&mut reader, &mut target)?;
    target.flush()?;
    Ok(copied)
}

fn compress_7z(
    input: &Path,
    output: &Path,
    level: i32,
    threads: u32,
) -> Result<u64, CorkError> {
    let target = File::create(output)?;
    compress_7z_to_writer(input, target, level, threads)?;
    Ok(output.metadata()?.len())
}

fn compress_7z_to_writer<W: Write + io::Seek>(
    input: &Path,
    target: W,
    level: i32,
    threads: u32,
) -> Result<u64, CorkError> {
    use sevenz_rust2::{encoder_options::Lzma2Options, ArchiveWriter};

    let mut writer = ArchiveWriter::new(target)?;
    let options = Lzma2Options::from_level_mt(
        level.clamp(0, 9) as u32,
        threads,
        8 * 1024 * 1024,
    );

    writer.set_content_methods(vec![options.into()]);
    writer.push_source_path(input, |_| true)?;
    let mut target = writer.finish()?;
    Ok(target.stream_position()?)
}

fn compress_7z_from_reader<R: Read, W: Write + io::Seek>(
    source: R,
    target: W,
    level: i32,
    threads: u32,
    entry_name: Option<&str>,
) -> Result<u64, CorkError> {
    use sevenz_rust2::{encoder_options::Lzma2Options, ArchiveEntry, ArchiveWriter};

    let mut writer = ArchiveWriter::new(target)?;
    let options = Lzma2Options::from_level_mt(level.clamp(0, 9) as u32, threads, 8 * 1024 * 1024);
    writer.set_content_methods(vec![options.into()]);
    let entry_name = validate_entry_name(entry_name.unwrap_or("input"))?;
    writer.push_archive_entry(ArchiveEntry::new_file(&entry_name), Some(source))?;
    let mut target = writer.finish()?;
    Ok(target.stream_position()?)
}

fn decompress_7z(
    input: &Path,
    output_dir: &Path,
    threads: u32,
) -> Result<u64, CorkError> {
    let source = File::open(input)?;
    decompress_7z_from_reader(source, output_dir, threads)
}

fn decompress_7z_from_reader<R: Read + io::Seek>(
    source: R,
    output_dir: &Path,
    threads: u32,
) -> Result<u64, CorkError> {
    use sevenz_rust2::{default_entry_extract_fn, ArchiveReader, Password};

    let mut reader = ArchiveReader::new(source, Password::empty())?;
    reader.set_thread_count(threads);
    reader.for_each_entries(|entry, stream| {
        let normalized = validate_entry_name(entry.name()).map_err(|err| sevenz_rust2::Error::Other(err.to_string().into()))?;
        let destination = output_dir.join(&normalized);
        default_entry_extract_fn(entry, stream, &destination)
    })?;

    directory_size(output_dir)
}

fn decompress_7z_to_tree<F>(
    source: File,
    threads: u32,
    on_entry: &mut F,
) -> Result<u64, CorkError>
where
    F: FnMut(&str, bool) -> Result<RawFd, CorkError>,
{
    use sevenz_rust2::{ArchiveReader, Password};

    let mut reader = ArchiveReader::new(source, Password::empty())?;
    reader.set_thread_count(threads);

    let mut total = 0u64;
    reader.for_each_entries(|entry, stream| {
        let normalized = validate_entry_name(entry.name())
            .map_err(|err| sevenz_rust2::Error::Other(err.to_string().into()))?;
        let directory = entry.is_directory();
        let fd = on_entry(&normalized, directory)
            .map_err(|err| sevenz_rust2::Error::Other(err.to_string().into()))?;

        if directory {
            return Ok(true);
        }

        if fd < 0 {
            return Err(sevenz_rust2::Error::Other(
                "SAF sink did not return an output fd for 7z file entry".into(),
            ));
        }

        let mut output = owned_file_from_fd(fd)
            .map_err(|err| sevenz_rust2::Error::Other(err.to_string().into()))?;
        total = total.saturating_add(
            io::copy(stream, &mut output)
                .map_err(|err| sevenz_rust2::Error::Other(err.to_string().into()))?,
        );
        output.flush()
            .map_err(|err| sevenz_rust2::Error::Other(err.to_string().into()))?;
        Ok(true)
    })?;

    Ok(total)
}

fn zip_options(level: i32) -> SimpleFileOptions {
    SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .compression_level(Some(level.clamp(0, 9) as i64))
}

fn compress_zip(input: &Path, output: &Path, level: i32) -> Result<u64, CorkError> {
    let target = File::create(output)?;
    compress_zip_to_writer(input, target, level)?;
    Ok(output.metadata()?.len())
}

fn compress_zip_to_writer<W: Write + io::Seek>(input: &Path, target: W, level: i32) -> Result<u64, CorkError> {
    let mut archive = ZipWriter::new(BufWriter::new(target));

    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Deflated)
        .compression_level(Some(level.clamp(0, 9) as i64));

    if input.is_file() {
        let name = input
            .file_name()
            .ok_or_else(|| CorkError::InvalidFormat("input file has no filename".to_owned()))?;

        archive.start_file(name.to_string_lossy(), options)?;
        let mut source = BufReader::new(File::open(input)?);
        io::copy(&mut source, &mut archive)?;
    } else if input.is_dir() {
        add_directory_to_zip(&mut archive, input, input, options)?;
    } else {
        return Err(CorkError::Unsupported(
            "input must be a regular file or directory".to_owned(),
        ));
    }

    let mut target = archive.finish()?;
    target.flush()?;
    Ok(target.stream_position().unwrap_or(0))
}

fn compress_zip_from_reader<R: Read, W: Write + io::Seek>(
    mut input: R,
    target: W,
    level: i32,
    entry_name: Option<&str>,
) -> Result<u64, CorkError> {
    let mut archive = ZipWriter::new(BufWriter::new(target));
    let entry_name = validate_entry_name(entry_name.unwrap_or("input"))?;
    archive.start_file(entry_name, zip_options(level))?;
    io::copy(&mut input, &mut archive)?;
    let target = archive.finish()?;
    let mut target = target.into_inner().map_err(|err| CorkError::Io(err.into_error()))?;
    target.flush()?;
    Ok(target.stream_position()?)
}

fn add_directory_to_zip<W: Write + io::Seek>(
    archive: &mut ZipWriter<BufWriter<W>>,
    root: &Path,
    current: &Path,
    options: SimpleFileOptions,
) -> Result<(), CorkError> {
    for entry in fs::read_dir(current)? {
        let entry = entry?;
        let path = entry.path();
        let relative = path
            .strip_prefix(root)
            .map_err(|err| CorkError::Archive(err.to_string()))?;
        let name = normalize_zip_path(relative);

        if path.is_dir() {
            archive.add_directory(format!("{name}/"), options)?;
            add_directory_to_zip(archive, root, &path, options)?;
        } else if path.is_file() {
            archive.start_file(name, options)?;
            let mut source = BufReader::new(File::open(&path)?);
            io::copy(&mut source, archive)?;
        }
    }

    Ok(())
}

fn decompress_zip(input: &Path, output_dir: &Path) -> Result<u64, CorkError> {
    let source = File::open(input)?;
    decompress_zip_from_reader(source, output_dir)
}

fn decompress_zip_from_reader<R: Read + io::Seek>(source: R, output_dir: &Path) -> Result<u64, CorkError> {
    let mut archive = ZipArchive::new(BufReader::new(source))?;
    
    archive.extract(output_dir)?;
    directory_size(output_dir)
}

fn decompress_zip_to_tree<F>(
    source: File,
    on_entry: &mut F,
) -> Result<u64, CorkError>
where
    F: FnMut(&str, bool) -> Result<RawFd, CorkError>,
{
    let mut archive = ZipArchive::new(BufReader::new(source))?;
    let mut total = 0u64;

    for index in 0..archive.len() {
        let mut file = archive.by_index(index)?;
        let name = file
            .enclosed_name()
            .ok_or_else(|| CorkError::Archive(format!("unsafe ZIP entry path: {}", file.name())))?;
        let name = name.to_string_lossy().into_owned();
        let directory = file.is_dir();
        let fd = on_entry(&name, directory)?;

        if directory {
            continue;
        }

        if fd < 0 {
            return Err(CorkError::Archive(format!(
                "SAF sink did not return an output fd for ZIP entry: {name}"
            )));
        }

        let mut output = owned_file_from_fd(fd)?;
        total = total.saturating_add(io::copy(&mut file, &mut output)?);
        output.flush()?;
    }

    Ok(total)
}

fn normalize_zip_path(path: &Path) -> String {
    path.components()
        .filter_map(|component| match component {
            std::path::Component::Normal(value) => Some(value.to_string_lossy().into_owned()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("/")
}

fn validate_entry_name(name: &str) -> Result<String, CorkError> {
    let normalized = name.replace('\\', "/");
    let without_trailing_slash = normalized.strip_suffix('/').unwrap_or(&normalized);
    if without_trailing_slash.is_empty()
        || without_trailing_slash.starts_with('/')
        || without_trailing_slash.contains('\0')
        || without_trailing_slash
            .split('/')
            .any(|part| part.is_empty() || part == "." || part == "..")
        || without_trailing_slash
            .split('/')
            .next()
            .is_some_and(|part| part.contains(':'))
    {
        return Err(CorkError::Archive(format!(
            "unsafe archive entry path: {name}"
        )));
    }
    Ok(without_trailing_slash.to_owned())
}

fn directory_size(path: &Path) -> Result<u64, CorkError> {
    let mut total = 0u64;

    if path.is_file() {
        return Ok(path.metadata()?.len());
    }

    for entry in fs::read_dir(path)? {
        let entry = entry?;
        let child: PathBuf = entry.path();

        if child.is_dir() {
            total = total.saturating_add(directory_size(&child)?);
        } else if child.is_file() {
            total = total.saturating_add(child.metadata()?.len());
        }
    }

    Ok(total)
}

fn resolve_threads(requested: i32) -> u32 {
    if requested >= 1 {
        return requested as u32;
    }

    std::thread::available_parallelism()
        .map(|count| count.get().clamp(1, 8) as u32)
        .unwrap_or(1)
}

struct CountingWriter<W> {
    inner: W,
    bytes: u64,
}

impl<W: Write> CountingWriter<W> {
    fn new(inner: W) -> Self {
        Self { inner, bytes: 0 }
    }

    fn bytes_written(&self) -> u64 {
        self.bytes
    }
}

impl<W: Write> Write for CountingWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let written = self.inner.write(buf)?;
        self.bytes = self.bytes.saturating_add(written as u64);
        Ok(written)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}
