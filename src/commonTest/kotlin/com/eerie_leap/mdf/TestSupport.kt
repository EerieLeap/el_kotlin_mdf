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

internal fun open(bytes: ByteArray, lenient: Boolean = false): MdfFile = runSuspending { Mdf4Reader.open(bytes.asMdfSource(), lenient) }

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
