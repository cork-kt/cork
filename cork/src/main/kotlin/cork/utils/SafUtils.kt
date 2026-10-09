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

package cork.utils

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import cork.CompressionLevel
import cork.ContainerFormat
import cork.CorkNative
import cork.CorkThreads
import cork.libraryContext
import java.io.IOException
import java.util.ArrayDeque

internal object SafUtils {
    private const val MIME_DIRECTORY = DocumentsContract.Document.MIME_TYPE_DIR
    private const val MIME_BINARY = "application/octet-stream"

    @RequiresApi(Build.VERSION_CODES.N)
    internal fun compressFileToUri(
        inputUri: Uri,
        outputUri: Uri,
        format: ContainerFormat,
        level: CompressionLevel,
        threads: CorkThreads,
    ): String? {
        /*require(!DocumentsContract.isTreeUri(outputUri)) {
            "Archive output must be a document URI, not a tree URI."
        }*/
        /*require(!DocumentsContract.isTreeUri(inputUri)) {
            "A tree URI must use Cork.compress(input = Saf.Tree(Uri), ...)."
        }*/
        require(format != ContainerFormat.Auto) {
            "Auto is only valid for decompression."
        }

        val resolver = libraryContext.contentResolver

        val input = resolver.openFileDescriptor(inputUri, "r")
            ?: return "Unable to open SAF input: $inputUri"

        val output = resolver.openFileDescriptor(outputUri, archiveOutputMode(format))
            ?: return "Unable to open SAF archive output: $outputUri"

        var outputFd: Int = -1
        var inputFd: Int = -1

        try {
            val inputName = queryDisplayName(resolver, inputUri) ?: "input"

            outputFd = output.detachFd()
            output.close()
            inputFd = input.detachFd()
            input.close()

            return CorkNative.compressFdToFd(
                inputFd = inputFd,
                outputFd = outputFd,
                format = format.id,
                level = level.id,
                threads = threads.nativeValue,
                inputName = inputName,
            )
        } catch (error: Throwable) {
            try { output.close() } catch (_: Throwable) {}
            try { input.close() } catch (_: Throwable) {}

            if (outputFd >= 0) {
                try { ParcelFileDescriptor.adoptFd(outputFd).close() } catch (_: Throwable) {}
            }
            if (inputFd >= 0) {
                try { ParcelFileDescriptor.adoptFd(inputFd).close() } catch (_: Throwable) {}
            }

            return error.message
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    internal fun compressTreeToUri(
        treeUri: Uri,
        outputUri: Uri,
        format: ContainerFormat,
        level: CompressionLevel,
        threads: CorkThreads,
    ): String? {
        /*require(!DocumentsContract.isTreeUri(outputUri)) {
            "Archive output must be a document URI, not a tree URI."
        }*/
        /*require(DocumentsContract.isTreeUri(treeUri)) {
            "compressTree requires an ACTION_OPEN_DOCUMENT_TREE URI."
        }*/
        require(format == ContainerFormat.Zip || format == ContainerFormat.SevenZ) {
            "SAF tree input supports ZIP and 7z containers; standalone LZMA accepts one file only."
        }
        val resolver = libraryContext.contentResolver
        val output = resolver.openFileDescriptor(outputUri, "rwt")
            ?: return "Unable to open SAF archive output: $outputUri"

        var outputFd: Int = -1
        lateinit var source: SafTreeInput

        return try {
            source = SafTreeInput(resolver, treeUri, excludedOutputUri = outputUri)
            outputFd = output.detachFd()
            output.close()
            CorkNative.compressTreeToFd(
                outputFd = outputFd,
                format = format.id,
                level = level.id,
                threads = threads.nativeValue,
                source = source,
            )
        } catch (error: Throwable) {
            source.close()
            try { output.close() } catch (_: Throwable) {}
            if (outputFd >= 0) {
                try { ParcelFileDescriptor.adoptFd(outputFd).close() } catch (_: Throwable) {}
            }
            error.message
        } finally {
            source.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    internal fun decompressUriToTree(
        archiveUri: Uri,
        outputTreeUri: Uri,
        threads: CorkThreads,
    ): String? {
        /*require(DocumentsContract.isTreeUri(outputTreeUri)) {
            "Decompression SAF output requires an ACTION_OPEN_DOCUMENT_TREE URI."
        }*/
        /*require(!DocumentsContract.isTreeUri(archiveUri)) {
            "Archive input must be a document URI, not a tree URI."
        }*/

        val resolver = libraryContext.contentResolver

        val archive = resolver.openFileDescriptor(archiveUri, "r")
            ?: return "Unable to open SAF archive input: $archiveUri"

        var archiveFd: Int = -1
        return try {
            val sink = SafTreeOutput(resolver, outputTreeUri)
            archiveFd = archive.detachFd()
            archive.close()
            CorkNative.decompressToTree(
                archiveFd = archiveFd,
                threads = threads.nativeValue,
                sink = sink,
            )
        } catch (error: Throwable) {
            try { archive.close() } catch (_: Throwable) {}
            if (archiveFd >= 0) {
                try { ParcelFileDescriptor.adoptFd(archiveFd).close() } catch (_: Throwable) {}
            }
            return error.message
        }
    }

    private fun archiveOutputMode(format: ContainerFormat): String = when (format) {
        ContainerFormat.Zip, ContainerFormat.SevenZ -> "rwt"
        ContainerFormat.Lzma -> "wt"
        ContainerFormat.Lzma2 -> "wt"
        ContainerFormat.Auto -> error("Auto cannot be used for compression.")
    }

    private fun normalizeRelativePath(path: String, directory: Boolean): String {
        require(path.isNotEmpty()) { "Archive entry name is empty." }
        require(path[0] != '/') { "Archive entry uses an absolute path: $path" }

        var componentStart = 0
        var firstComponent = true
        var needsSlashNormalization = false

        for (index in path.indices) {
            when (path[index]) {
                '\u0000' -> throw IllegalArgumentException("Archive entry contains NUL: $path")
                ':' -> if (firstComponent) {
                    throw IllegalArgumentException("Unsafe archive entry path: $path")
                }

                '\\' -> needsSlashNormalization = true
                '/' -> {
                    validatePathComponent(path, componentStart, index, firstComponent)
                    firstComponent = false
                    componentStart = index + 1
                }
            }
        }

        validatePathComponent(path, componentStart, path.length, firstComponent)

        val normalized = if (needsSlashNormalization) {
            path.replace('\\', '/')
        } else {
            path
        }
        return if (directory && !normalized.endsWith('/')) "$normalized/" else normalized
    }

    private fun validatePathComponent(
        path: String,
        start: Int,
        end: Int,
        firstComponent: Boolean,
    ) {
        require(end > start) { "Unsafe archive entry path: $path" }
        require(!(end - start == 1 && path[start] == '.')) {
            "Unsafe archive entry path: $path"
        }
        require(!(end - start == 2 && path[start] == '.' && path[start + 1] == '.')) {
            "Unsafe archive entry path: $path"
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
        }
        return null
    }

    internal class SafInputEntry(
        @JvmField var path: String,
        @JvmField var fd: Int,
    )

    internal class SafTreeInput(
        private val resolver: ContentResolver,
        treeUri: Uri,
        excludedOutputUri: Uri?
    ) {
        private val excludedOutputAuthority = excludedOutputUri?.authority
        private val excludedOutputDocumentId = excludedOutputUri?.let {
            DocumentsContract.getDocumentId(it)
        }

        private data class DirectoryFrame(
            val parentPath: String,
            val cursor: Cursor,
            val idIndex: Int,
            val nameIndex: Int,
            val mimeIndex: Int,
        )

        private val rootUri = treeUri
        private val frames = ArrayDeque<DirectoryFrame>()
        private val visitedDirectories = HashSet<String>()
        private val reusableEntry = SafInputEntry("", -1)
        private var closed = false

        init {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            visitedDirectories.add(rootDocumentId)
            frames.addLast(openDirectoryCursor(rootDocumentId, ""))
        }

        fun nextEntry(): SafInputEntry? {
            check(!closed) { "SAF input session is closed." }

            while (frames.isNotEmpty()) {
                val frame = frames.last()
                if (!frame.cursor.moveToNext()) {
                    frame.cursor.close()
                    frames.removeLast()
                    continue
                }

                val childId = frame.cursor.getString(frame.idIndex)
                if (
                    childId == excludedOutputDocumentId &&
                    rootUri.authority == excludedOutputAuthority
                ) {
                    // The output archive may be inside the selected input tree.
                    // Never enumerate the document Cork is currently writing to.
                    continue
                }
                val displayName = frame.cursor.getString(frame.nameIndex)
                val directory = frame.cursor.getString(frame.mimeIndex) == MIME_DIRECTORY
                val childPath = if (frame.parentPath.isEmpty()) {
                    displayName
                } else {
                    "${frame.parentPath}/$displayName"
                }

                val normalizedPath = normalizeRelativePath(childPath, directory)

                if (directory) {
                    if (visitedDirectories.add(childId)) {
                        frames.addLast(openDirectoryCursor(childId, childPath))
                    }
                    reusableEntry.path = normalizedPath
                    reusableEntry.fd = -1
                    return reusableEntry
                }

                val childUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, childId)
                val pfd = resolver.openFileDescriptor(childUri, "r")
                    ?: throw IOException("Unable to open SAF file: $childUri")
                val fd = try {
                    pfd.detachFd()
                } catch (error: Throwable) {
                    pfd.close()
                    throw error
                }

                reusableEntry.path = normalizedPath
                reusableEntry.fd = fd
                return reusableEntry
            }

            return null
        }

        private fun openDirectoryCursor(
            documentId: String,
            parentPath: String,
        ): DirectoryFrame {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                rootUri,
                documentId,
            )
            val cursor = resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            ) ?: throw IOException("Unable to enumerate SAF directory: $documentId")

            try {
                val idIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                )
                val nameIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
                val mimeIndex = cursor.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )

                return DirectoryFrame(
                    parentPath = parentPath,
                    cursor = cursor,
                    idIndex = idIndex,
                    nameIndex = nameIndex,
                    mimeIndex = mimeIndex,
                )
            } catch (error: Throwable) {
                cursor.close()
                throw error
            }
        }

        fun close() {
            if (closed) return
            closed = true
            while (frames.isNotEmpty()) {
                frames.removeLast().cursor.close()
            }
        }
    }

    internal class SafTreeOutput(
        private val resolver: ContentResolver,
        treeUri: Uri,
    ) {
        private data class Child(
            val uri: Uri,
            val directory: Boolean,
        )

        private val rootUri = treeUri
        private val directoryUris = HashMap<String, Uri>().apply { put("", treeUri) }
        private val childCache = HashMap<String, MutableMap<String, Child>>()

        fun onEntry(path: String, directory: Boolean): Int {
            val normalized = normalizeRelativePath(path, directory)
            return if (directory) {
                ensureDirectory(normalized.removeSuffix("/"))
                -1
            } else {
                val separator = normalized.lastIndexOf('/')
                val parentPath = if (separator >= 0) normalized.substring(0, separator) else ""
                val name = if (separator >= 0) normalized.substring(separator + 1) else normalized
                val parentUri = ensureDirectory(parentPath)
                val child = findOrCreateChild(parentPath, parentUri, name, directory = false)

                val pfd = resolver.openFileDescriptor(child.uri, "wt")
                    ?: throw IOException("Unable to open SAF output: ${child.uri}")
                try {
                    pfd.detachFd()
                } catch (error: Throwable) {
                    pfd.close()
                    throw error
                }
            }
        }

        private fun ensureDirectory(relativePath: String): Uri {
            if (relativePath.isEmpty()) return rootUri
            directoryUris[relativePath]?.let { return it }

            var currentPath = ""
            var currentUri = rootUri
            for (part in relativePath.split('/')) {
                val nextPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                val cached = directoryUris[nextPath]
                if (cached != null) {
                    currentPath = nextPath
                    currentUri = cached
                    continue
                }

                val child = findOrCreateChild(currentPath, currentUri, part, directory = true)
                require(child.directory) {
                    "SAF destination already contains a file where a directory is required: $nextPath"
                }

                directoryUris[nextPath] = child.uri
                currentPath = nextPath
                currentUri = child.uri
            }

            return currentUri
        }

        private fun findOrCreateChild(
            parentPath: String,
            parentUri: Uri,
            name: String,
            directory: Boolean,
        ): Child {
            val cache = childCache.getOrPut(parentPath) { queryChildren(parentUri) }
            cache[name]?.let { existing ->
                require(existing.directory == directory) {
                    "SAF destination type mismatch for '$name'."
                }
                return existing
            }

            val mime = if (directory) MIME_DIRECTORY else MIME_BINARY
            val createdUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                mime,
                name,
            ) ?: throw IOException("Unable to create SAF document '$name'.")

            return Child(createdUri, directory).also { cache[name] = it }
        }

        private fun queryChildren(parentUri: Uri): MutableMap<String, Child> {
            val result = HashMap<String, Child>()
            val documentId = DocumentsContract.getDocumentId(parentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, documentId)

            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex =
                    cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex)
                    val mime = cursor.getString(mimeIndex)
                    result[name] = Child(
                        uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id),
                        directory = mime == MIME_DIRECTORY,
                    )
                }
            } ?: throw IOException("Unable to query SAF destination directory: $parentUri")

            return result
        }
    }
}

public sealed interface Saf {
    public data class Tree(
        public val uri: Uri,
    ) : Saf
}
