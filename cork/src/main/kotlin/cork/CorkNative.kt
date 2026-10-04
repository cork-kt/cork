package cork

internal object CorkNative {
    init {
        System.loadLibrary("cork")
    }

    external fun compress(
        input: String,
        output: String,
        format: Int,
        level: Int,
        threads: Int
    ): String?

    external fun compressToFd(
        input: String,
        outputFd: Int,
        format: Int,
        level: Int,
        threads: Int
    ): String?

    external fun decompress(
        input: String,
        outputDirectory: String,
        threads: Int
    ): String?

    external fun decompressFromFd(
        inputFd: Int,
        outputDirectory: String,
        threads: Int
    ): String?
}
