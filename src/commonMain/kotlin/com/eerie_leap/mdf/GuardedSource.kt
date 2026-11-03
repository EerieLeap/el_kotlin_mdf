package com.eerie_leap.mdf

import kotlin.coroutines.cancellation.CancellationException

/**
 * The caller's [MdfSource] as the reader may rely on it. A read is never asked for a place that isn't one (a negative position or
 * length, a range outside the buffer), an answer that can't be true (fewer than none, or more than was asked for) is taken as the
 * end of the file, and whatever the source throws but cancellation is a [MdfFailure.NotRead], so that a failing source ends a read
 * or a scan with a failure to report and never lets its own exceptions out of the reader.
 */
internal class GuardedSource private constructor(private val source: MdfSource) : MdfSource {
    override val size: Long
        get() = try {
            source.size.coerceAtLeast(0L)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // A source is the caller's code: whatever it throws fails the read.
            throw failureOf(0L, e)
        }

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (!isPossibleRead(position, into, offset, length)) {
            damaged(position, "the reader asked for $length bytes at byte $position, a read that can't be")
        }
        if (length == 0) return 0
        return try {
            source.read(position, into, offset, length).coerceIn(0, length)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // A source is the caller's code: whatever it throws fails the read.
            throw failureOf(position, e)
        }
    }

    private fun isPossibleRead(position: Long, into: ByteArray, offset: Int, length: Int): Boolean {
        val isPlace = position >= 0 && length >= 0 && offset >= 0
        return isPlace && offset <= into.size - length
    }

    // What to throw for [e], thrown by the source on a read at [position]: cancellation and the reader's own failures as they are.
    private fun failureOf(position: Long, e: Exception): Exception =
        if (e is CancellationException || e is MdfException) e else MdfException(MdfFailure.NotRead(position, e.message), e)

    companion object {
        /** [source] guarded; one that is already is returned as it is. */
        fun of(source: MdfSource): MdfSource = if (source is GuardedSource) source else GuardedSource(source)
    }
}
