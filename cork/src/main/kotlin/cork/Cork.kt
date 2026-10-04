package cork

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

public object Cork {
    public const val VERSION: String = "0.1.0"

    /**
     * compresses input file/directory into a selected ContainerFormat archive to output file.
     *
     * @return CorkResult.
     */
    public suspend fun compress(
        input: String,
        output: String,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        require(format != ContainerFormat.Auto) {
            "Auto is only valid for decompression."
        }

        val result = CorkNative.compress(
            input = input,
            output = output,
            format = format.id,
            level = level.id,
            threads = threads.nativeValue
        )

        return@withContext CorkResult(result == null, result)
    }

    public suspend fun compress(
        input: String,
        output: Uri,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        require(format != ContainerFormat.Auto) {
            "Auto is only valid for decompression."
        }

        val parcel = libraryContext.contentResolver.openFileDescriptor(output, "w")
        val outputFd = parcel?.detachFd() ?: return@withContext CorkResult(false, "Unable to open file: $output")
        parcel.close()

        val result = CorkNative.compressToFd(
            input = input,
            outputFd = outputFd,
            format = format.id,
            level = level.id,
            threads = threads.nativeValue
        )

        return@withContext CorkResult(result == null, result)
    }

    public suspend fun compress(
        input: File,
        output: File,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = compress(input.path, output.path, format, level, threads)

    public suspend fun compress(
        input: Path,
        output: Path,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = compress(input.toString(), output.toString(), format, level, threads)

    /**
     * Extracts an archive. The container format is detected from its magic bytes.
     *
     * @return CorkResult.
     */
    public suspend fun decompress(
        archive: String,
        outputDirectory: String,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        val result = CorkNative.decompress(
            input = archive,
            outputDirectory = outputDirectory,
            threads = threads.nativeValue
        )

        return@withContext CorkResult(result == null, result)
    }

    public suspend fun decompress(
        archive: Uri,
        outputDirectory: String,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        val parcel = libraryContext.contentResolver.openFileDescriptor(archive, "r")
        val archiveFd = parcel?.detachFd() ?: return@withContext CorkResult(false, "Unable to open file: $archive")
        parcel.close()

        val result = CorkNative.decompressFromFd(
            inputFd = archiveFd,
            outputDirectory = outputDirectory,
            threads = threads.nativeValue
        )

        return@withContext CorkResult(result == null, result)
    }

    public suspend fun decompress(
        archive: File,
        outputDirectory: File,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = decompress(archive.path, outputDirectory.path, threads)

    public suspend fun decompress(
        archive: Path,
        outputDirectory: Path,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = decompress(archive.toString(), outputDirectory.toString(), threads)
}

public enum class ContainerFormat(
    internal val id: Int,
) {
    /**
     * ZIP container. Cork uses Deflate for created entries.
     */
    Zip(1),

    /**
     * 7z container. Cork uses the backend's default LZMA2 configuration.
     */
    SevenZ(2),

    /**
     * Standalone .lzma file format.
     *
     * This accepts a single input file and writes one .lzma stream.
     */
    Lzma(3),

    /**
     * Reserved for the standalone LZMA2 stream API planned for a later release.
     */
    Lzma2(4),

    /**
     * Auto-detection is supported for decompression only.
     */
    Auto(0),
}

public enum class CompressionLevel(
    internal val id: Int,
) {
    Fast(1),
    Balanced(5),
    Default(6),
    Best(9),
}
