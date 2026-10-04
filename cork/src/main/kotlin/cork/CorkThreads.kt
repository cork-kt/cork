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