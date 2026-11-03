package com.eerie_leap.mdf

import kotlin.math.pow

private const val BITS_PER_BYTE = 8
private const val HALF_BITS = 16
private const val SINGLE_BITS = 32
private const val DOUBLE_BITS = 64
private const val HALF_MANTISSA_BITS = 10
private const val HALF_EXPONENT_MASK = 0x1F
private const val HALF_MANTISSA_MASK = 0x3FF
private const val HALF_EXPONENT_BIAS = 15
private const val HALF_MANTISSA_SCALE = 1024.0
private const val HALF_SUBNORMAL_EXPONENT = -24
private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_NIBBLE_BITS = 4
private const val HEX_NIBBLE_MASK = 0xF

/**
 * Decodes the values a record holds. A value is [byteCount] bytes at a byte offset, from a bit offset (0 to 7) of its first byte,
 * [bitCount] bits long. Integers are of any bit count up to 64; floats are of 16, 32 or 64 bits on whole bytes. Strings and byte
 * arrays decode to texts for a readout, not to numbers.
 */
public object MdfValues {
    /**
     * An unsigned integer of [bitCount] (1 to 64) bits. For a little-endian field the first bit is the lowest bit of the byte at
     * [byteOffset] counted up by [bitOffset]; for a big-endian field the bytes it spans (to the bit offset and the bit count) are
     * one number, most significant byte first, and [bitOffset] counts up from its lowest bit. A 64-bit value above Long.MAX_VALUE
     * comes back negative: read it as unsigned.
     */
    public fun unsigned(
        bytes: ByteArray,
        byteOffset: Int,
        bitOffset: Int,
        bitCount: Int,
        bigEndian: Boolean = false,
    ): ULong {
        require(bitCount in 1..DOUBLE_BITS) { "A value has 1 to 64 bits, not $bitCount." }
        return bits(bytes, byteOffset, bitOffset, bitCount, bigEndian).toULong()
    }

    /** A two's complement integer of [bitCount] (1 to 64) bits, laid out as [unsigned] is. */
    public fun signed(
        bytes: ByteArray,
        byteOffset: Int,
        bitOffset: Int,
        bitCount: Int,
        bigEndian: Boolean = false,
    ): Long {
        require(bitCount in 1..DOUBLE_BITS) { "A value has 1 to 64 bits, not $bitCount." }
        return signExtend(bits(bytes, byteOffset, bitOffset, bitCount, bigEndian), bitCount)
    }

    /** An IEEE 754 float of [bitCount] (16, 32 or 64) bits at [byteOffset]; NaN for another bit count. */
    public fun float(bytes: ByteArray, byteOffset: Int, bitCount: Int, bigEndian: Boolean = false): Double = when (bitCount) {
        HALF_BITS -> halfToDouble(bits(bytes, byteOffset, 0, HALF_BITS, bigEndian).toInt())
        SINGLE_BITS -> Float.fromBits(bits(bytes, byteOffset, 0, SINGLE_BITS, bigEndian).toInt()).toDouble()
        DOUBLE_BITS -> Double.fromBits(bits(bytes, byteOffset, 0, DOUBLE_BITS, bigEndian))
        else -> Double.NaN
    }

    /** The number a field of a numeric [dataType] holds, as a Double; NaN for a type that isn't a number. */
    public fun number(
        dataType: MdfDataType,
        bytes: ByteArray,
        byteOffset: Int,
        bitOffset: Int,
        bitCount: Int,
    ): Double = when {
        dataType.isFloat -> float(bytes, byteOffset, bitCount, dataType.isBigEndian)
        dataType.isSigned -> signed(bytes, byteOffset, bitOffset, bitCount, dataType.isBigEndian).toDouble()
        dataType.isInteger -> unsigned(bytes, byteOffset, bitOffset, bitCount, dataType.isBigEndian).toDouble()
        else -> Double.NaN
    }

    /**
     * The text of a string field of [byteCount] bytes: Latin-1, UTF-8 or UTF-16 (little or big endian), ending at the first NUL
     * character or the field's end.
     */
    public fun text(dataType: MdfDataType, bytes: ByteArray, byteOffset: Int, byteCount: Int): String {
        val end = byteOffset + byteCount
        return when (dataType) {
            MdfDataType.StringLatin1 -> latin1(bytes, byteOffset, end)
            MdfDataType.StringUtf8 -> bytes.decodeToString(byteOffset, firstNul(bytes, byteOffset, end))
            MdfDataType.StringUtf16LittleEndian -> utf16(bytes, byteOffset, end, bigEndian = false)
            MdfDataType.StringUtf16BigEndian -> utf16(bytes, byteOffset, end, bigEndian = true)
            else -> hex(bytes, byteOffset, byteCount)
        }
    }

    /** A byte array as hexadecimal pairs for a readout: `01 02 ff`. */
    public fun hex(bytes: ByteArray, byteOffset: Int, byteCount: Int): String {
        val text = StringBuilder(byteCount * 3)
        for (i in byteOffset until byteOffset + byteCount) {
            if (text.isNotEmpty()) text.append(' ')
            val value = bytes.u8(i)
            text.append(HEX_DIGITS[value shr HEX_NIBBLE_BITS]).append(HEX_DIGITS[value and HEX_NIBBLE_MASK])
        }
        return text.toString()
    }

    /** Whether bit [bitPosition] of the invalidation bytes that begin at [byteOffset] is set: the value is invalid. */
    public fun isInvalid(bytes: ByteArray, byteOffset: Int, bitPosition: Int): Boolean =
        (bytes.u8(byteOffset + bitPosition / BITS_PER_BYTE) and (1 shl (bitPosition % BITS_PER_BYTE))) != 0

    internal fun signExtend(value: Long, bitCount: Int): Long =
        if (bitCount >= DOUBLE_BITS) value else (value shl (DOUBLE_BITS - bitCount)) shr (DOUBLE_BITS - bitCount)

    // The field's bits as an unsigned number in a Long.
    internal fun bits(
        bytes: ByteArray,
        byteOffset: Int,
        bitOffset: Int,
        bitCount: Int,
        bigEndian: Boolean,
    ): Long {
        val byteSize = (bitOffset + bitCount + BITS_PER_BYTE - 1) / BITS_PER_BYTE
        var result = 0L
        var produced = 0
        var position = bitOffset
        while (produced < bitCount) {
            val inByte = position % BITS_PER_BYTE
            val take = minOf(BITS_PER_BYTE - inByte, bitCount - produced)
            val index = if (bigEndian) byteOffset + byteSize - 1 - position / BITS_PER_BYTE else byteOffset + position / BITS_PER_BYTE
            val piece = (bytes.u8(index) ushr inByte) and ((1 shl take) - 1)
            result = result or (piece.toLong() shl produced)
            produced += take
            position += take
        }
        return result
    }

    internal fun halfToDouble(half: Int): Double {
        val sign = if ((half shr (HALF_BITS - 1)) != 0) -1.0 else 1.0
        val exponent = (half shr HALF_MANTISSA_BITS) and HALF_EXPONENT_MASK
        val mantissa = half and HALF_MANTISSA_MASK
        return when (exponent) {
            0 -> sign * mantissa * 2.0.pow(HALF_SUBNORMAL_EXPONENT)
            HALF_EXPONENT_MASK -> if (mantissa == 0) sign * Double.POSITIVE_INFINITY else Double.NaN
            else -> sign * (1.0 + mantissa / HALF_MANTISSA_SCALE) * 2.0.pow(exponent - HALF_EXPONENT_BIAS)
        }
    }

    private fun firstNul(bytes: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end && bytes[i] != 0.toByte()) i++
        return i
    }

    private fun latin1(bytes: ByteArray, from: Int, end: Int): String {
        val text = StringBuilder()
        var i = from
        while (i < end && bytes[i] != 0.toByte()) text.append(bytes.u8(i++).toChar())
        return text.toString()
    }

    private fun utf16(bytes: ByteArray, from: Int, end: Int, bigEndian: Boolean): String {
        val text = StringBuilder()
        var i = from
        while (i + 1 < end) {
            val unit = if (bigEndian) (bytes.u8(i) shl BITS_PER_BYTE) or bytes.u8(i + 1) else bytes.u16(i)
            if (unit == 0) break
            text.append(unit.toChar())
            i += 2
        }
        return text.toString()
    }
}
