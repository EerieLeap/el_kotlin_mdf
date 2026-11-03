package com.eerie_leap.mdf

// Little-endian reads from a block's or a record's bytes: MDF 4 stores its blocks that way.

internal const val LOW_BYTE = 0xFF
internal const val U32_SIZE = 4
internal const val U64_SIZE = 8
private const val THIRD_BYTE = 3
private const val BITS_24 = 24
private const val U32_MASK = 0xFFFFFFFFL

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and LOW_BYTE

internal fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl Byte.SIZE_BITS)

internal fun ByteArray.i16(at: Int): Int = u16(at).toShort().toInt()

internal fun ByteArray.u32(at: Int): Long = u8(at).toLong() or
    (u8(at + 1).toLong() shl Byte.SIZE_BITS) or
    (u8(at + 2).toLong() shl Short.SIZE_BITS) or
    (u8(at + THIRD_BYTE).toLong() shl BITS_24)

/** The 8 bytes at [at] as a Long: an unsigned 64-bit field whose top bit is set reads as negative. */
internal fun ByteArray.u64(at: Int): Long = u32(at) or (u32(at + U32_SIZE) shl Int.SIZE_BITS)

internal fun ByteArray.f64(at: Int): Double = Double.fromBits(u64(at))

/** A 32-bit field as an Int; one above Int.MAX_VALUE reads as negative, which the callers' checks refuse. */
internal fun ByteArray.u32AsInt(at: Int): Int = (u32(at) and U32_MASK).toInt()
