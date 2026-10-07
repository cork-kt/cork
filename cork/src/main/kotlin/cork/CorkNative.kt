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

import cork.utils.SafUtils

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

    external fun compressFdToFd(
        inputFd: Int,
        outputFd: Int,
        format: Int,
        level: Int,
        threads: Int,
        inputName: String,
    ): String?

    external fun compressTreeToFd(
        outputFd: Int,
        format: Int,
        level: Int,
        threads: Int,
        source: SafUtils.SafTreeInput,
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

    external fun decompressToTree(
        archiveFd: Int,
        threads: Int,
        sink: SafUtils.SafTreeOutput,
    ): String?
}
