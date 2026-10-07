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

package cork.demo

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cork.CompressionLevel
import cork.ContainerFormat
import cork.Cork
import cork.CorkThreads
import cork.utils.Saf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = colorResource(id = R.color.cork_background)
                ) {
                    CorkDemoScreen()
                }
            }
        }
    }
}

@Composable
fun CorkDemoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val workDir = remember(context) { File(context.getExternalFilesDir(null)!!, "cork-demo") }
    val sampleDir = remember(workDir) { File(workDir, "sample") }
    val archivesDir = remember(workDir) { File(workDir, "archives") }
    val extractedDir = remember(workDir) { File(workDir, "extracted") }

    val formatOptions = remember { listOf("ZIP", "7z", "LZMA") }
    val levelOptions = remember { listOf("Fast", "Balanced", "Default", "Best") }
    val threadOptions = remember { listOf("Auto", "1", "2", "4", "8") }

    var selectedFormatIndex by remember { mutableIntStateOf(0) }
    var selectedLevelIndex by remember { mutableIntStateOf(0) }
    var selectedThreadIndex by remember { mutableIntStateOf(0) }

    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Ready") }
    var detailText by remember { mutableStateOf("Preparing sample workspace…") }

    fun selectedFormat(): ContainerFormat = when (selectedFormatIndex) {
        0 -> ContainerFormat.Zip
        1 -> ContainerFormat.SevenZ
        else -> ContainerFormat.Lzma
    }

    fun selectedLevel(): CompressionLevel = when (selectedLevelIndex) {
        0 -> CompressionLevel.Fast
        1 -> CompressionLevel.Balanced
        2 -> CompressionLevel.Default
        else -> CompressionLevel.Best
    }

    fun selectedThreads(): CorkThreads = when (selectedThreadIndex) {
        0 -> CorkThreads.Auto
        else -> CorkThreads.Fixed(selectedThreadIndex)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            prepareSampleDataIfNeeded(sampleDir)
        }
        statusText = "Ready"
        detailText = "Sample: ${humanBytes(sampleDirSize(sampleDir))} · Cork ${Cork.VERSION}"
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val format = selectedFormat()
            val level = selectedLevel()
            val threads = selectedThreads()
            val extension = when (format) {
                ContainerFormat.Zip -> "zip"
                ContainerFormat.SevenZ -> "7z"
                else -> error("Unsupported demo format")
            }

            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)

            val output = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocumentUri,
                "application/octet-stream",
                "cork-demo.$extension"
            ) ?: return@let

            isBusy = true
            statusText = "Compressing…"
            detailText = "${formatLabel(format)} · ${levelLabel(level)} · ${threadsLabel(threads)} · "

            scope.launch(Dispatchers.Main) {
                val start = System.nanoTime()
                val result = Cork.compress(
                    input = Saf.Tree(uri),
                    output = output,
                    format = format,
                    level = level,
                    threads = threads
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                isBusy = false
                if (result.isSuccessful) {
                    statusText = "Compression complete"
                    detailText = "${formatLabel(format)} archive from " +
                            "$elapsedMs ms"
                } else {
                    statusText = "Operation failed"
                    detailText = result.error ?: "Unknown"
                }
            }
        }
    }

    val extractArchivePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val format = selectedFormat()
            val level = selectedLevel()
            val threads = selectedThreads()

            isBusy = true
            statusText = "Decompressing…"
            detailText = "${formatLabel(format)} · ${levelLabel(level)} · ${threadsLabel(threads)} · "

            scope.launch(Dispatchers.Main) {
                val start = System.nanoTime()
                val result = Cork.decompress(
                    archive = uri,
                    outputDirectory = extractedDir.path,
                    threads = threads
                )
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                isBusy = false
                if (result.isSuccessful) {
                    statusText = "Decompression complete"
                    detailText = "${formatLabel(format)} archive from " +
                            "$elapsedMs ms"
                } else {
                    statusText = "Operation failed"
                    detailText = result.error ?: "Unknown"
                }
            }
        }
    }

    fun compressSample() {
        val format = selectedFormat()
        val level = selectedLevel()
        val threads = selectedThreads()
        val extension = when (format) {
            ContainerFormat.Zip -> "zip"
            ContainerFormat.SevenZ -> "7z"
            ContainerFormat.Lzma -> "lzma"
            else -> error("Unsupported demo format")
        }
        val output = File(archivesDir.apply { mkdirs() }, "cork-demo.$extension")
        val input = if (format == ContainerFormat.Lzma) File(sampleDir, "data.txt") else sampleDir

        isBusy = true
        statusText = "Compressing…"
        detailText = "${formatLabel(format)} · ${levelLabel(level)} · ${threadsLabel(threads)} · " +
                "input: ${if (format == ContainerFormat.Lzma) "data.txt" else "sample/"}"

        scope.launch(Dispatchers.Main) {
            val before = sampleDirSize(input)
            val start = System.nanoTime()
            val result = Cork.compress(
                input,
                output,
                format,
                level,
                threads
            )
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val resultData = ResultData(before, elapsedMs, output)
            isBusy = false
            if (result.isSuccessful) {
                statusText = "Compression complete"
                detailText = "${formatLabel(format)} archive from " +
                        "${humanBytes(resultData.inputBytes)} input · ${resultData.elapsedMs} ms\n${resultData.file}"
            } else {
                statusText = "Operation failed"
                detailText = result.error ?: "Unknown"
            }
        }
    }

    fun extractLatest() {
        val archive = archivesDir.listFiles()?.maxByOrNull { it.lastModified() }
        if (archive == null) {
            statusText = "Nothing to extract"
            detailText = "Compress the sample first."
            return
        }

        val destination = File(extractedDir, archive.nameWithoutExtension).apply {
            deleteRecursively()
            mkdirs()
        }

        isBusy = true
        statusText = "Extracting…"
        detailText = archive.name

        scope.launch(Dispatchers.Main) {
            val start = System.nanoTime()
            val result = Cork.decompress(archive, destination, selectedThreads())
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val resultData = ResultData(archive.length(), elapsedMs, destination)
            isBusy = false
            if (result.isSuccessful) {
                statusText = "Extraction complete"
                detailText = "result -> ${humanBytes(resultData.inputBytes)} " +
                        "written · ${resultData.elapsedMs} ms\n${resultData.file}"
            } else {
                statusText = "Operation failed"
                detailText = result.error ?: "Unknown"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Text(
            text = "CORK DEMO",
            fontSize = 12.sp,
            color = colorResource(id = R.color.cork_primary),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Native compression for Kotlin",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.cork_text),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "This app runs the same public Cork API shipped by the library. The heavy archive work happens in Rust.",
            fontSize = 15.sp,
            color = colorResource(id = R.color.cork_muted),
            modifier = Modifier.padding(bottom = 20.dp)
        )

        DemoCard(
            title = "OPERATION",
            body = "Configure a native compression run.",
            modifier = Modifier.padding(bottom = 12.dp)
        )

        DropdownSelector(
            caption = "Format",
            items = formatOptions,
            selectedIndex = selectedFormatIndex,
            enabled = !isBusy,
            onItemSelected = { selectedFormatIndex = it }
        )

        DropdownSelector(
            caption = "Compression level",
            items = levelOptions,
            selectedIndex = selectedLevelIndex,
            enabled = !isBusy,
            onItemSelected = { selectedLevelIndex = it }
        )

        DropdownSelector(
            caption = "Workers",
            items = threadOptions,
            selectedIndex = selectedThreadIndex,
            enabled = !isBusy,
            onItemSelected = { selectedThreadIndex = it }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { compressSample() },
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.cork_primary),
                    contentColor = colorResource(id = R.color.cork_primary_text)
                )
            ) {
                Text(text = "Compress sample")
            }

            Button(
                onClick = { extractLatest() },
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.cork_primary),
                    contentColor = colorResource(id = R.color.cork_primary_text)
                )
            ) {
                Text(text = "Extract latest")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { dirPickerLauncher.launch(null) },
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.cork_primary),
                    contentColor = colorResource(id = R.color.cork_primary_text)
                )
            ) {
                Text(text = "Compress (SAF)")
            }

            Button(
                onClick = { extractArchivePickerLauncher.launch(arrayOf("*/*")) },
                enabled = !isBusy,
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.cork_primary),
                    contentColor = colorResource(id = R.color.cork_primary_text)
                )
            ) {
                Text(text = "Extract (SAF)")
            }
        }

        DemoCard(
            title = "RESULT",
            body = "The demo stores temporary archives under the app cache directory.",
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Text(
            text = statusText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.cork_text),
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Text(
            text = detailText,
            fontSize = 14.sp,
            color = colorResource(id = R.color.cork_muted),
            modifier = Modifier.padding(bottom = 18.dp)
        )
    }
}

@Composable
fun DropdownSelector(
    caption: String,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onItemSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(
                color = colorResource(id = R.color.cork_surface_alt),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled) { expanded = true }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = caption.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.cork_muted)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = items.getOrElse(selectedIndex) { "" },
                    fontSize = 16.sp,
                    color = colorResource(id = if (enabled) R.color.cork_text else R.color.cork_muted)
                )

                Text(
                    text = "▼",
                    fontSize = 12.sp,
                    color = colorResource(id = R.color.cork_muted)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(colorResource(id = R.color.cork_surface))
            ) {
                items.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item,
                                color = colorResource(id = R.color.cork_text)
                            )
                        },
                        onClick = {
                            onItemSelected(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DemoCard(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = colorResource(id = R.color.cork_surface),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = colorResource(id = R.color.cork_primary)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            color = colorResource(id = R.color.cork_muted)
        )
    }
}

private fun prepareSampleDataIfNeeded(sampleDir: File) {
    if (File(sampleDir, "README.txt").exists()) return
    sampleDir.mkdirs()
    File(sampleDir, "README.txt").writeText(
        "Cork demo dataset\n\nThis directory is generated by the demo app.\n"
    )
    val repeated = buildString {
        repeat(12_000) {
            append("Cork is an Android-first compression library backed by Rust. ")
            append("The quick brown fox jumps over the lazy dog.\n")
        }
    }
    File(sampleDir, "data.txt").writeText(repeated)
    File(sampleDir, "nested").mkdirs()
    File(sampleDir, "nested/metadata.json").writeText(
        "{\n  \"format\": \"demo\",\n  \"native\": true,\n  \"reusable\": true\n}\n"
    )
}

private fun sampleDirSize(dir: File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.2f MiB".format(bytes / 1024f / 1024f)
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun formatLabel(format: ContainerFormat): String = when (format) {
    ContainerFormat.SevenZ -> "7z"
    ContainerFormat.Lzma -> "LZMA"
    else -> "ZIP"
}

private fun levelLabel(level: CompressionLevel): String = when (level) {
    CompressionLevel.Fast -> "fast"
    CompressionLevel.Balanced -> "balanced"
    CompressionLevel.Default -> "default"
    CompressionLevel.Best -> "best"
}

private fun threadsLabel(threads: CorkThreads): String = when (threads) {
    CorkThreads.Auto -> "auto workers"
    CorkThreads.Single -> "1 worker"
    is CorkThreads.Fixed -> "${threads.count} workers"
}

private data class ResultData(
    val inputBytes: Long,
    val elapsedMs: Long,
    val file: File,
)