package com.eerie_leap.mdf

import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal actual fun platformInflate(
    data: ByteArray,
    offset: Int,
    length: Int,
    expectedSize: Int,
    raw: Boolean,
): ByteArray {
    val inflater = Inflater(raw)
    try {
        inflater.setInput(data, offset, length)
        val output = ByteArray(expectedSize)
        var filled = 0
        while (filled < expectedSize) {
            val count = inflateInto(inflater, output, filled, expectedSize - filled)
            filled += count
            if (count == 0) explainStop(inflater, filled, expectedSize)
        }
        // The stream may still hold its end marker: one more call reads it, and finds out whether more data follows.
        val more = inflateInto(inflater, ByteArray(1), 0, 1)
        require(more == 0) { "DEFLATE data inflates to more than the $expectedSize bytes expected" }
        require(inflater.finished()) { "DEFLATE data ends early, after its $expectedSize bytes" }
        return output
    } finally {
        inflater.end()
    }
}

private fun inflateInto(inflater: Inflater, into: ByteArray, offset: Int, length: Int): Int = try {
    inflater.inflate(into, offset, length)
} catch (e: DataFormatException) {
    throw IllegalArgumentException("Invalid DEFLATE data: ${e.message}", e)
}

// The inflater gave no bytes before the output was full: it says why.
private fun explainStop(inflater: Inflater, filled: Int, expectedSize: Int): Nothing = when {
    inflater.needsDictionary() -> throw IllegalArgumentException("DEFLATE data needs a dictionary")
    inflater.finished() -> throw IllegalArgumentException("DEFLATE data inflates to $filled bytes, not the $expectedSize expected")
    inflater.needsInput() -> throw IllegalArgumentException("DEFLATE data ends early, at $filled of $expectedSize bytes")
    else -> error("DEFLATE made no progress")
}
