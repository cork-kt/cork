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

package cork

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import cork.utils.Saf
import cork.utils.SafUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

public object Cork {
    public const val VERSION: String = "1.0.0-alpha1"

    /**
     * compresses [input] file/directory into a selected [ContainerFormat] to output file.
     *
     * @return [CorkResult].
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

    /**
     * compresses [input] file/directory into a selected [ContainerFormat] to [output] SAF uri file.
     *
     * @return [CorkResult].
     */
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

    /**
     * compresses [input] SAF uri file into a selected [ContainerFormat] to [output] SAF uri file.
     *
     * @return [CorkResult].
     */
    @RequiresApi(Build.VERSION_CODES.N)
    public suspend fun compress(
        input: Uri,
        output: Uri,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        require(format != ContainerFormat.Auto) {
            "Auto is only valid for decompression."
        }

        val result = SafUtils.compressFileToUri(
            input,
            output,
            format,
            level,
            threads
        )

        return@withContext CorkResult(result == null, result)
    }

    /**
     * compresses [input] SAF uri directory into a selected [ContainerFormat] to [output] SAF uri file.
     *
     * @return [CorkResult].
     */
    @RequiresApi(Build.VERSION_CODES.N)
    public suspend fun compress(
        input: Saf.Tree,
        output: Uri,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        require(format != ContainerFormat.Auto) {
            "Auto is only valid for decompression."
        }

        val result = SafUtils.compressTreeToUri(
            input.uri,
            output,
            format,
            level,
            threads
        )

        return@withContext CorkResult(result == null, result)
    }

    /**
     * compresses [input] file/directory into a selected [ContainerFormat] to output file.
     *
     * @return [CorkResult].
     */
    public suspend fun compress(
        input: File,
        output: File,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = compress(input.path, output.path, format, level, threads)

    /**
     * compresses [input] file/directory into a selected [ContainerFormat] to output file.
     *
     * @return [CorkResult].
     */
    public suspend fun compress(
        input: Path,
        output: Path,
        format: ContainerFormat,
        level: CompressionLevel = CompressionLevel.Default,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = compress(input.toString(), output.toString(), format, level, threads)

    /**
     * Extracts [archive] to [outputDirectory]. The container format is detected from its magic bytes.
     *
     * @return [CorkResult].
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

    /**
     * Extracts SAF uri [archive] to [outputDirectory]. The container format is detected from its magic bytes.
     *
     * @return [CorkResult].
     */
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

    /**
     * Extracts SAF uri [archive] to SAF uri [outputDirectory]. The container format is detected from its magic bytes.
     *
     * @return [CorkResult].
     */
    @RequiresApi(Build.VERSION_CODES.N)
    public suspend fun decompress(
        archive: Uri,
        outputDirectory: Uri,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = withContext(Dispatchers.IO) {
        val result = SafUtils.decompressUriToTree(
            archiveUri = archive,
            outputTreeUri = outputDirectory,
            threads = threads
        )

        return@withContext CorkResult(result == null, result)
    }

    /**
     * Extracts [archive] to [outputDirectory]. The container format is detected from its magic bytes.
     *
     * @return [CorkResult].
     */
    public suspend fun decompress(
        archive: File,
        outputDirectory: File,
        threads: CorkThreads = CorkThreads.Auto
    ): CorkResult = decompress(archive.path, outputDirectory.path, threads)

    /**
     * Extracts [archive] to [outputDirectory]. The container format is detected from its magic bytes.
     *
     * @return [CorkResult].
     */
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
