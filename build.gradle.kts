plugins {
    alias(libs.plugins.android.library) apply false
}

tasks.register<Exec>("buildRustRelease") {
    group = "rust"

    val rustDir = layout.projectDirectory.dir("rust").asFile
    val outputDir = layout.projectDirectory.dir("cork/src/main/jniLibs").asFile

    workingDir = rustDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", outputDir.absolutePath,
        "build", "--release"
    )
}

tasks.register("cleanRust") {
    group = "rust"
    doLast {
        delete(layout.projectDirectory.dir("rust/target"))
    }
}
