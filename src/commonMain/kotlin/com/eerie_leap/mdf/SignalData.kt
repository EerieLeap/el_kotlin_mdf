package com.eerie_leap.mdf

private const val WINDOW_BYTES = 1024 * 1024
private const val MAX_VALUE_BYTES = 64L * 1024 * 1024

/** Something the scan has to read from the file before it can take the record it stopped at: the scanner runs it and tries again. */
internal fun interface Fetch {
    suspend fun run()
}

/** What a lookup of a variable-length value found. */
internal enum class Lookup {
    /** The value is there. */
    Found,

    /** The value is in the file but not in memory: [Fetch] it, and ask again. */
    Fetching,

    /** There is no value at that offset, and there won't be. */
    Absent,

    /** The value isn't in the file yet (a file still arriving): try again as it grows. */
    Waiting,
}

/**
 * The variable-length values (strings, byte arrays, CAN payloads) of a channel that keeps them in signal data blocks (SD): each is a
 * 4-byte length and its bytes, found by the offset the channel's records hold into the blocks' data taken as one. A value is looked up
 * in a window of the data kept in memory; a value outside it is fetched with a window at its offset, so a scan that goes forward
 * through the values reads each once and a jump reads one more window.
 */
internal class SignalDataValues(private val stream: DataStream) {
    private var window = ByteArray(0)
    private var windowStart = 0L
    private var windowSize = 0

    // The window reaches the end of the data there was when it was read: a value cut by its end isn't there (yet).
    private var windowIsAtEnd = false
    private var streamIsComplete = false
    private var wanted = 0L

    /** What the last [take] found. */
    var lookup = Lookup.Absent
        private set

    /** The read that brings in the value of the last [take], when it found [Lookup.Fetching]. */
    val fetch: Fetch = Fetch { load(wanted) }

    /** The value at [offset], null where there is none: [lookup] says why. */
    fun take(offset: Long): ByteArray? {
        // An offset of 2^63 or more (a negative one here) is no place in any file.
        if (offset < 0) return null.also { lookup = Lookup.Absent }
        lookup = Lookup.Fetching
        val found = fromWindow(offset)
        if (found == null && lookup == Lookup.Fetching) {
            // Not in the window, or cut by its end. A window that already reaches the end of the data has nothing more to give.
            if (windowIsAtEnd && offset >= windowStart) {
                lookup = if (streamIsComplete) Lookup.Absent else Lookup.Waiting
            } else {
                wanted = offset
            }
        }
        return found
    }

    /** Forget what was found to be missing: the file may have grown since. */
    fun retry() {
        windowIsAtEnd = false
        windowSize = 0
    }

    private fun fromWindow(offset: Long): ByteArray? {
        val at = offset - windowStart
        val length = if (at >= 0 && at <= windowSize - U32_SIZE) window.u32(at.toInt()) else -1L
        return when {
            length < 0 -> null
            length > MAX_VALUE_BYTES -> null.also { lookup = Lookup.Absent }
            at + U32_SIZE + length > windowSize -> null
            else -> window.copyOfRange(at.toInt() + U32_SIZE, at.toInt() + U32_SIZE + length.toInt()).also { lookup = Lookup.Found }
        }
    }

    private suspend fun load(offset: Long) {
        val available = stream.available()
        val declared = stream.declaredLength
        streamIsComplete = declared != null && available >= declared
        windowStart = offset
        windowSize = 0
        windowIsAtEnd = true
        if (offset < 0 || offset > available - U32_SIZE) return
        var size = minOf(WINDOW_BYTES.toLong(), available - offset).toInt()
        fill(offset, size)
        val length = window.u32(0)
        if (length <= MAX_VALUE_BYTES && U32_SIZE + length > size) {
            size = minOf(U32_SIZE + length, available - offset).toInt()
            fill(offset, size)
        }
        windowIsAtEnd = offset + windowSize >= available
    }

    private suspend fun fill(offset: Long, size: Int) {
        if (window.size < size) window = ByteArray(size)
        windowSize = stream.read(offset, window, 0, size)
        if (windowSize < U32_SIZE) windowSize = 0
    }
}
