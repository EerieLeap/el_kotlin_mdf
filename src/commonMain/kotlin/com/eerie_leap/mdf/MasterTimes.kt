package com.eerie_leap.mdf

private const val CHUNK_BYTES = 256L * 1024
private const val INITIAL_TIMES = 1024

/**
 * The master values of a channel group whose master channel is in another channel group (`cg_cg_master`): the n-th record of the
 * group is at the n-th value of that master. They come from a scan of the master's own data, which runs on as the group's records
 * need more, and are kept only until the group has used them.
 */
internal class MasterTimes(
    /** For a master that is no stored data, the record's number through its conversion: the time of record n, with no scan. */
    private val virtual: ((Long) -> Double)? = null,
) : MdfSampleSink {
    private var times = DoubleArray(INITIAL_TIMES)
    private var head = 0
    private var size = 0

    // The index, among the master's values, of times[head].
    private var base = 0L
    private var scanner: MdfScanner? = null
    private var wanted = 0L

    // The master's data are all read, or can't be: values past the last one are none.
    private var isExhausted = false

    // The master's data haven't reached the value wanted yet, in a file still arriving.
    private var isWaiting = false

    // The file will not grow: a value that isn't there yet will not be.
    private var isFinal = false

    /** The read that brings in the values up to the one the last [check] asked for. */
    val fetch: Fetch = Fetch { load(wanted) }

    /** Sets the scan of the master's data, which cannot exist before the sink it hands its samples to. */
    fun attach(scan: MdfScanner) {
        scanner = scan
    }

    override fun onSample(request: MdfChannelRequest, time: Double, value: Double, isInvalid: Boolean) {
        if (head + size == times.size) makeRoom()
        times[head + size++] = value
    }

    /** Whether the value of record [index] is there ([Lookup.Found]), has to be read ([Lookup.Fetching]), is not to be had, or not yet. */
    fun check(index: Long): Lookup = when {
        virtual != null || index < base + size -> Lookup.Found
        isExhausted -> Lookup.Absent
        isWaiting -> Lookup.Waiting
        else -> Lookup.Fetching.also { wanted = index }
    }

    /** The value of record [index], NaN where the master has none. [check] has said whether it is there. */
    fun timeAt(index: Long): Double = when {
        virtual != null -> virtual(index)
        index in base until base + size -> times[head + (index - base).toInt()]
        else -> Double.NaN
    }

    /** Lets go of the values the group has used: all those before record [index]. */
    fun discardBefore(index: Long) {
        val drop = (index - base).coerceIn(0L, size.toLong()).toInt()
        head += drop
        size -= drop
        base += drop
    }

    /** The master's file may have grown since a value was waited for, unless it is [isFinal], which will grow no more. */
    fun retry(isFinal: Boolean) {
        isWaiting = false
        this.isFinal = isFinal
    }

    private suspend fun load(index: Long) {
        discardBefore(index)
        val scan = checkNotNull(scanner) { "the master's scan was not attached" }
        var progress: MdfScanProgress
        var moved: Boolean
        do {
            val before = scan.bytesRead
            progress = scan.advance(CHUNK_BYTES, isFinal)
            moved = scan.bytesRead > before
        } while (index >= base + size && moved && progress.problem == null)
        if (index >= base + size) {
            if (progress.isComplete || progress.problem != null || isFinal) isExhausted = true else isWaiting = true
        }
    }

    // The times are in times[head, head + size): make room at the end, by moving them to the front or by growing the array.
    private fun makeRoom() {
        if (head > times.size / 2) {
            times.copyInto(times, 0, head, head + size)
        } else {
            times = times.copyOf(times.size * 2)
            times.copyInto(times, 0, head, head + size)
        }
        head = 0
    }
}
