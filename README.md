# Cork

Cork is a High-performance Android-first compression and archive library with a Kotlin API backed by optimized native Rust codes.

## API

```kotlin
import cork.Cork
import cork.CompressionLevel
import cork.ContainerFormat

// ZIP
Cork.compress(
    input = "example/path/to/myFiles",
    output = "example/path/to/myArchive.zip",
    format = ContainerFormat.Zip,
    level = CompressionLevel.Default
)

Cork.decompress(
    input = "example/path/to/myArchive.zip", 
    output = "example/path/to/myFiles"
)

// 7z
Cork.compress(
    input = "example/path/to/myFiles",
    output = "example/path/to/myArchive.7z",
    format = ContainerFormat.SevenZ,
    level = CompressionLevel.Best
)

// Standalone .lzma
Cork.compress(
    input = "example/path/to/myFile.txt",
    output = "example/path/to/myArchive.lzma",
    format = ContainerFormat.Lzma,
    level = CompressionLevel.Balanced
)
```

All calls must be made from a coroutine.

## Building

Requirements:

1. Android Studio with a compatible JDK setup.
2. Android NDK installed.
3. Rust installed.
4. `cargo-ndk`.

Install the Android Rust targets or let `cargo-ndk` manage the NDK target configuration.

Then:

```bash
./gradlew :cork:assembleRelease
```

The root `buildRustRelease` task is wired into `cork`'s gradle packaging task so the Rust codes gets recompiled before the Android library is packaged.