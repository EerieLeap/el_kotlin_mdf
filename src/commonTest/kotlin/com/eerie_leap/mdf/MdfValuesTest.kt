package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdfValuesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun readsUnsignedIntegersOfWholeBytesInBothByteOrders() {
        val data = bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        assertEquals(0x01UL, MdfValues.unsigned(data, 0, 0, 8))
        assertEquals(0x0201UL, MdfValues.unsigned(data, 0, 0, 16))
        assertEquals(0x0102UL, MdfValues.unsigned(data, 0, 0, 16, bigEndian = true))
        assertEquals(0x04030201UL, MdfValues.unsigned(data, 0, 0, 32))
        assertEquals(0x01020304UL, MdfValues.unsigned(data, 0, 0, 32, bigEndian = true))
        assertEquals(0x0807060504030201UL, MdfValues.unsigned(data, 0, 0, 64))
        assertEquals(0x0102030405060708UL, MdfValues.unsigned(data, 0, 0, 64, bigEndian = true))
        assertEquals(0x050403UL, MdfValues.unsigned(data, 2, 0, 24))
        assertEquals(0x030405UL, MdfValues.unsigned(data, 2, 0, 24, bigEndian = true))
    }

    @Test
    fun readsBitFieldsFromTheirBitOffset() {
        val data = bytes(0xAB, 0xCD)

        // Little endian: the bits count up from the lowest bit of the first byte, as the number 0xCDAB.
        assertEquals(0xCDAUL, MdfValues.unsigned(data, 0, 4, 12))
        assertEquals(0xBUL, MdfValues.unsigned(data, 0, 0, 4))
        assertEquals(0xAUL, MdfValues.unsigned(data, 0, 4, 4))
        assertEquals(1UL, MdfValues.unsigned(data, 0, 0, 1))
        assertEquals(0UL, MdfValues.unsigned(data, 0, 2, 1))
        // Big endian: the bytes the offset and the count reach are one number, most significant first, 0xABCD for twelve bits from
        // bit 4, and the offset counts up from its lowest bit; four bits from bit 4 reach one byte only.
        assertEquals(0xABCUL, MdfValues.unsigned(data, 0, 4, 12, bigEndian = true))
        assertEquals(0xBUL, MdfValues.unsigned(data, 0, 0, 4, bigEndian = true))
        assertEquals(0xAUL, MdfValues.unsigned(data, 0, 4, 4, bigEndian = true))
        assertEquals(0xBCDUL, MdfValues.unsigned(data, 0, 0, 12, bigEndian = true))
    }

    @Test
    fun readsAValueOf64BitsThatStartsMidByte() {
        val data = bytes(0xF1, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01)

        // The 72 bits are the number 0x01EFCDAB89674523F1; the field is bits 4 to 67.
        assertEquals(0x1EFCDAB89674523FUL, MdfValues.unsigned(data, 0, 4, 64))
    }

    @Test
    fun readsSignedIntegersByTheirBitCount() {
        assertEquals(-1L, MdfValues.signed(bytes(0xFF, 0x0F), 0, 0, 12))
        assertEquals(-128L, MdfValues.signed(bytes(0x80), 0, 0, 8))
        assertEquals(127L, MdfValues.signed(bytes(0x7F), 0, 0, 8))
        assertEquals(-2L, MdfValues.signed(bytes(0xFE, 0xFF), 0, 0, 16))
        assertEquals(-2L, MdfValues.signed(bytes(0xFF, 0xFE), 0, 0, 16, bigEndian = true))
        assertEquals(-1L, MdfValues.signed(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), 0, 0, 64))
        assertEquals(Long.MIN_VALUE, MdfValues.signed(bytes(0, 0, 0, 0, 0, 0, 0, 0x80), 0, 0, 64))
        // A signed 4-bit field at bit 4: 0b1010 is -6.
        assertEquals(-6L, MdfValues.signed(bytes(0xA5), 0, 4, 4))
        assertEquals(-1L, MdfValues.signed(bytes(0x01), 0, 0, 1))
    }

    @Test
    fun anUnsigned64BitValueAboveTheLongRangeIsStillUnsigned() {
        val data = ByteArray(8) { 0xFF.toByte() }

        assertEquals(ULong.MAX_VALUE, MdfValues.unsigned(data, 0, 0, 64))
        assertEquals(1.8446744073709552E19, MdfValues.number(MdfDataType.UnsignedIntegerLittleEndian, data, 0, 0, 64))
    }

    @Test
    fun refusesABitCountOutsideOneTo64() {
        assertFailsWith<IllegalArgumentException> { MdfValues.unsigned(ByteArray(16), 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MdfValues.signed(ByteArray(16), 0, 0, 65) }
    }

    @Test
    fun readsHalfPrecisionFloats() {
        fun half(bits: Int, bigEndian: Boolean = false) =
            MdfValues.float(if (bigEndian) bytes(bits shr 8, bits and 0xFF) else bytes(bits and 0xFF, bits shr 8), 0, 16, bigEndian)

        assertEquals(1.0, half(0x3C00))
        assertEquals(-2.0, half(0xC000))
        assertEquals(0.5, half(0x3800))
        assertEquals(65504.0, half(0x7BFF))
        assertEquals(0.0, half(0x0000))
        assertEquals(-0.0, half(0x8000))
        assertEquals(1.0 / 16777216.0, half(0x0001))
        assertEquals(Double.POSITIVE_INFINITY, half(0x7C00))
        assertEquals(Double.NEGATIVE_INFINITY, half(0xFC00))
        assertTrue(half(0x7E00).isNaN())
        assertEquals(1.0, half(0x3C00, bigEndian = true))
        assertEquals(1.0009765625, half(0x3C01))
    }

    @Test
    fun readsSingleAndDoublePrecisionFloatsInBothByteOrders() {
        val single = ByteWriter().f32(1.5f).toByteArray()
        assertEquals(1.5, MdfValues.float(single, 0, 32))
        assertEquals(1.5, MdfValues.float(single.reversedArray(), 0, 32, bigEndian = true))

        val double = ByteWriter().f64(-123.456).toByteArray()
        assertEquals(-123.456, MdfValues.float(double, 0, 64))
        assertEquals(-123.456, MdfValues.float(double.reversedArray(), 0, 64, bigEndian = true))
        assertTrue(MdfValues.float(double, 0, 24).isNaN())
    }

    @Test
    fun readsNumbersByTheirDataType() {
        val data = ByteWriter().f32(2.5f).u32(0xFFFFFFFEL).toByteArray()

        assertEquals(2.5, MdfValues.number(MdfDataType.FloatLittleEndian, data, 0, 0, 32))
        assertEquals(4294967294.0, MdfValues.number(MdfDataType.UnsignedIntegerLittleEndian, data, 4, 0, 32))
        assertEquals(-2.0, MdfValues.number(MdfDataType.SignedIntegerLittleEndian, data, 4, 0, 32))
        assertTrue(MdfValues.number(MdfDataType.StringUtf8, data, 0, 0, 32).isNaN())
    }

    @Test
    fun decodesStringsOfEveryEncodingUpToTheirNul() {
        val latin1 = bytes('C'.code, 'a'.code, 'f'.code, 0xE9, 0, 'x'.code)
        assertEquals("Café", MdfValues.text(MdfDataType.StringLatin1, latin1, 0, latin1.size))

        val utf8 = "é€😀".encodeToByteArray() + byteArrayOf(0, 'x'.code.toByte())
        assertEquals("é€😀", MdfValues.text(MdfDataType.StringUtf8, utf8, 0, utf8.size))

        val utf16le = bytes('H'.code, 0, 0xE9, 0, 0, 0, 'x'.code, 0)
        assertEquals("Hé", MdfValues.text(MdfDataType.StringUtf16LittleEndian, utf16le, 0, utf16le.size))

        val utf16be = bytes(0, 'H'.code, 0, 0xE9, 0, 0, 0, 'x'.code)
        assertEquals("Hé", MdfValues.text(MdfDataType.StringUtf16BigEndian, utf16be, 0, utf16be.size))
    }

    @Test
    fun aStringWithoutANulEndsWhereItsFieldDoes() {
        val data = "abcdef".encodeToByteArray()

        assertEquals("bcd", MdfValues.text(MdfDataType.StringUtf8, data, 1, 3))
        assertEquals("bcd", MdfValues.text(MdfDataType.StringLatin1, data, 1, 3))
        assertEquals("", MdfValues.text(MdfDataType.StringUtf8, data, 2, 0))
    }

    @Test
    fun showsByteArraysAsHexadecimalPairs() {
        val data = bytes(0x01, 0xAB, 0xFF, 0x00)

        assertEquals("01 ab ff 00", MdfValues.hex(data, 0, 4))
        assertEquals("ab ff", MdfValues.hex(data, 1, 2))
        assertEquals("", MdfValues.hex(data, 0, 0))
        assertEquals("01 ab", MdfValues.text(MdfDataType.ByteArray, data, 0, 2))
    }

    @Test
    fun readsInvalidationBitsFromTheBytesAfterTheData() {
        val invalidation = bytes(0b0000_0101, 0b1000_0000)

        assertTrue(MdfValues.isInvalid(invalidation, 0, 0))
        assertFalse(MdfValues.isInvalid(invalidation, 0, 1))
        assertTrue(MdfValues.isInvalid(invalidation, 0, 2))
        assertFalse(MdfValues.isInvalid(invalidation, 0, 7))
        assertTrue(MdfValues.isInvalid(invalidation, 0, 15))
        assertFalse(MdfValues.isInvalid(invalidation, 0, 8))
    }
}
