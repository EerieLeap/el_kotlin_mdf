@file:OptIn(ExperimentalForeignApi::class)

package com.eerie_leap.mdf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.zlib.ZLIB_VERSION
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** Window bits for a zlib stream (a header and a trailer), and negative for a raw one, as zlib's inflateInit2 takes them. */
private const val ZLIB_WINDOW_BITS = 15
private const val RAW_WINDOW_BITS = -15

// Modelled on the Gefera app's Deflate.decompress, which inflates on iOS the same way.
internal actual fun platformInflate(
    data: ByteArray,
    offset: Int,
    length: Int,
    expectedSize: Int,
    raw: Boolean,
): ByteArray = memScoped {
    val stream = alloc<z_stream>()
    stream.zalloc = null
    stream.zfree = null
    stream.opaque = null
    stream.next_in = null
    stream.avail_in = 0u
    val windowBits = if (raw) RAW_WINDOW_BITS else ZLIB_WINDOW_BITS
    val initResult = inflateInit2_(stream.ptr, windowBits, ZLIB_VERSION, sizeOf<z_stream>().toInt())
    check(initResult == Z_OK) { "inflateInit2 failed: $initResult" }
    try {
        val output = ByteArray(expectedSize)
        // addressOf needs an element, so an empty input or output is pinned as a byte that the stream's counts don't include.
        val input = if (length == 0) ByteArray(1) else data
        val target = if (expectedSize == 0) ByteArray(1) else output
        input.usePinned { pinnedInput ->
            target.usePinned { pinnedOutput ->
                stream.next_in = pinnedInput.addressOf(if (length == 0) 0 else offset).reinterpret<UByteVar>()
                stream.avail_in = length.convert()
                stream.next_out = pinnedOutput.addressOf(0).reinterpret<UByteVar>()
                stream.avail_out = expectedSize.convert()
                val status = inflate(stream.ptr, Z_NO_FLUSH)
                val produced = expectedSize - stream.avail_out.toInt()
                when {
                    status == Z_STREAM_END -> require(produced == expectedSize) {
                        "DEFLATE data inflates to $produced bytes, not the $expectedSize expected"
                    }

                    status == Z_OK || status == Z_BUF_ERROR -> throw IllegalArgumentException(
                        "DEFLATE data ends early or inflates to more than the $expectedSize bytes expected",
                    )

                    else -> throw IllegalArgumentException("Invalid DEFLATE data: $status")
                }
            }
        }
        output
    } finally {
        inflateEnd(stream.ptr)
    }
}
