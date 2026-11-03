package com.eerie_leap.mdf

// Writers of the data blocks the firmware never writes, for the tests: zipped blocks (DZ) of a platform-free kind of zlib stream.

internal const val ZIP_DEFLATE = 0
internal const val ZIP_TRANSPOSED_DEFLATE = 1

/**
 * [data] as a valid zlib stream (RFC 1950) of stored blocks: not compressed, but every reader of zlib takes it, and it needs no
 * platform to make.
 */
internal fun zlibStored(data: ByteArray): ByteArray {
    val out = ByteWriter().u8(0x78).u8(0x01)
    var position = 0
    do {
        val count = minOf(STORED_BLOCK_MAX, data.size - position)
        val isLast = position + count >= data.size
        out.u8(if (isLast) 1 else 0).u16(count).u16(count.inv() and 0xFFFF).bytes(data.copyOfRange(position, position + count))
        position += count
    } while (position < data.size)
    var a = 1L
    var b = 0L
    for (byte in data) {
        a = (a + (byte.toInt() and 0xFF)) % ADLER_MODULUS
        b = (b + a) % ADLER_MODULUS
    }
    val adler = (b shl 16) or a
    return out.u8((adler shr 24).toInt()).u8((adler shr 16).toInt()).u8((adler shr 8).toInt()).u8(adler.toInt()).toByteArray()
}

/** The bytes of records of [columns] bytes, column by column; a last record that is incomplete follows as it was. */
internal fun transpose(data: ByteArray, columns: Int): ByteArray {
    val rows = data.size / columns
    val out = ByteArray(data.size)
    for (row in 0 until rows) {
        for (column in 0 until columns) out[column * rows + row] = data[row * columns + column]
    }
    data.copyInto(out, rows * columns, rows * columns, data.size)
    return out
}

/**
 * A zipped data block (DZ) holding [plain] as [zipped] bytes (the bytes of a zlib or raw DEFLATE stream of the data, transposed
 * first for [ZIP_TRANSPOSED_DEFLATE]), which says it came from a block of type [originalType].
 */
internal fun zippedBlock(
    layout: BlockLayout,
    plain: ByteArray,
    zipped: ByteArray,
    zipType: Int = ZIP_DEFLATE,
    columns: Int = 0,
    originalType: String = "DT",
    links: Int = 0,
): TestBlock {
    val data = ByteWriter()
        .bytes(originalType.encodeToByteArray())
        .u8(zipType)
        .u8(0)
        .u32(columns.toLong())
        .u64(plain.size.toLong())
        .u64(zipped.size.toLong())
        .bytes(zipped)
    return layout.block("DZ", links, data.toByteArray())
}

private const val STORED_BLOCK_MAX = 65535
private const val ADLER_MODULUS = 65521L
