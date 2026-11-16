package com.eerie_leap.mdf.writer

private const val BYTE_MASK = 0xFF
private const val U16_MASK = 0xFFFF
private const val U32_MASK = 0xFFFFFFFFL
private const val DEFAULT_CAPACITY = 64
private const val TEXT_ALIGNMENT = 8

/** Little-endian bytes being put together: a block's fields, a batch of records. */
internal class LittleEndianBuffer(capacity: Int = DEFAULT_CAPACITY) {
    var array = ByteArray(capacity)
        private set
    var size = 0
        private set

    fun u8(value: Int): LittleEndianBuffer = apply {
        reserve(1)
        array[size++] = value.toByte()
    }

    fun u16(value: Int): LittleEndianBuffer = apply {
        u8(value and BYTE_MASK)
        u8((value shr Byte.SIZE_BITS) and BYTE_MASK)
    }

    fun u32(value: Long): LittleEndianBuffer = apply {
        val bits = value and U32_MASK
        u16(bits.toInt() and U16_MASK)
        u16((bits shr Short.SIZE_BITS).toInt() and U16_MASK)
    }

    fun u64(value: Long): LittleEndianBuffer = apply {
        u32(value and U32_MASK)
        u32((value shr Int.SIZE_BITS) and U32_MASK)
    }

    fun f64(value: Double): LittleEndianBuffer = u64(value.toRawBits())

    fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size - offset): LittleEndianBuffer = apply {
        reserve(length)
        value.copyInto(array, size, offset, offset + length)
        size += length
    }

    fun zeros(count: Int): LittleEndianBuffer = apply {
        reserve(count)
        array.fill(0, size, size + count)
        size += count
    }

    /** [text] as UTF-8 and at least one NUL, padded with NULs to a multiple of 8 bytes, as a block's text is written. */
    fun paddedText(text: String): LittleEndianBuffer {
        val encoded = text.encodeToByteArray()
        return bytes(encoded).zeros(TEXT_ALIGNMENT - encoded.size % TEXT_ALIGNMENT)
    }

    fun clear() {
        size = 0
    }

    fun toByteArray(): ByteArray = array.copyOf(size)

    private fun reserve(extra: Int) {
        if (size + extra > array.size) array = array.copyOf(maxOf(array.size * 2, size + extra))
    }
}
