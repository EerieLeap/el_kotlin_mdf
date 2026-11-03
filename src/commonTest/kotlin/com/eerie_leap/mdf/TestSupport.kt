package com.eerie_leap.mdf

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs [block] to its end. The tests' sources answer at once, so nothing ever suspends; a test that does fails here instead of
 * hanging.
 */
internal fun <T> runSuspending(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return checkNotNull(result) { "the test suspended" }.getOrThrow()
}

/** The failure of the [MdfException] [block] fails with. */
internal fun failureOf(block: suspend () -> Unit): MdfFailure {
    val failure = runCatching { runSuspending(block) }.exceptionOrNull()
    check(failure is MdfException) { "expected an MdfException but got $failure" }
    return failure.failure
}

internal fun open(bytes: ByteArray, lenient: Boolean = false): MdfFile = runSuspending { Mdf4Reader.open(StrictSource(bytes), lenient) }

/**
 * A source over [bytes] that refuses what the sources of a file system or a content provider refuse: a read at a negative position
 * or of a negative length, one that doesn't fit its buffer. The in-memory source of the library answers 0 to them, which hides a
 * reader that asks.
 */
internal class StrictSource(private val bytes: ByteArray) : MdfSource {
    /** How many reads were asked. */
    var reads = 0
        private set

    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0) { "a read at byte $position" }
        require(length >= 0 && offset >= 0 && offset + length <= into.size) {
            "a read of $length bytes into a buffer of ${into.size} at $offset"
        }
        reads++
        if (position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
        return count
    }
}

/** A source over a file that grows: the bytes of [all], of which [visible] can be read. */
internal class GrowingSource(private val all: ByteArray, var visible: Int) : MdfSource {
    override val size: Long get() = visible.toLong()

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= visible) return 0
        val count = minOf(length.toLong(), visible - position).toInt()
        all.copyInto(into, offset, position.toInt(), position.toInt() + count)
        return count
    }
}
