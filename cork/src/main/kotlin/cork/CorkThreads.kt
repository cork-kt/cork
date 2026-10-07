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

public sealed interface CorkThreads {
    public val nativeValue: Int

    /** chooses a worker count depending on device available. */
    public data object Auto : CorkThreads {
        override val nativeValue: Int = -1
    }

    /** forces the operation to use one worker. */
    public data object Single : CorkThreads {
        override val nativeValue: Int = 1
    }

    /** use an explicit worker count from 1 through [Runtime.getRuntime().availableProcessors()]. */
    public data class Fixed(
        public val count: Int,
    ) : CorkThreads {
        init {
            val availableCores = Runtime.getRuntime().availableProcessors()
            require(count in 1..availableCores) { "Thread count must be between 1 and $availableCores." }
        }

        override val nativeValue: Int = count
    }
}