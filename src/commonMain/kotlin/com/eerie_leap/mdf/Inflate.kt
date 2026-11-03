package com.eerie_leap.mdf

private const val ZLIB_METHOD_MASK = 0x0F
private const val ZLIB_METHOD_DEFLATE = 8
private const val ZLIB_HEADER_CHECK = 31
private const val ZLIB_HEADER_SIZE = 2

/**
 * [length] bytes of [data] from [offset], a zlib stream (RFC 1950, as MDF 4 stores a zipped data block) or, from a writer that left
 * the header out, a raw DEFLATE stream (RFC 1951), inflated to exactly [expectedSize] bytes. Fails with an
 * [IllegalArgumentException] for data that isn't DEFLATE, that ends early, or that doesn't inflate to [expectedSize] bytes.
 */
internal fun inflate(data: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray = if (expectedSize == 0) {
    ByteArray(0)
} else {
    platformInflate(data, offset, length, expectedSize, raw = !hasZlibHeader(data, offset, length))
}

// A zlib stream begins with a method (8, DEFLATE) and a window size, and a check that makes the two bytes a multiple of 31.
private fun hasZlibHeader(data: ByteArray, offset: Int, length: Int): Boolean {
    if (length < ZLIB_HEADER_SIZE) return false
    val first = data.u8(offset)
    val second = data.u8(offset + 1)
    return (first and ZLIB_METHOD_MASK) == ZLIB_METHOD_DEFLATE && ((first shl Byte.SIZE_BITS) + second) % ZLIB_HEADER_CHECK == 0
}

/** The platform's zlib: [raw] says the data is a raw DEFLATE stream, else a zlib one. */
internal expect fun platformInflate(
    data: ByteArray,
    offset: Int,
    length: Int,
    expectedSize: Int,
    raw: Boolean,
): ByteArray
