package com.eerie_leap.mdf

/** Little-endian bytes being written, for the test files and their records. */
internal class ByteWriter {
    private var bytes = ByteArray(64)
    var size = 0
        private set

    fun u8(value: Int): ByteWriter = apply {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun u16(value: Int): ByteWriter = apply {
        u8(value)
        u8(value shr 8)
    }

    fun u32(value: Long): ByteWriter = apply {
        u16(value.toInt())
        u16((value shr 16).toInt())
    }

    fun u64(value: Long): ByteWriter = apply {
        u32(value)
        u32(value shr 32)
    }

    fun f32(value: Float): ByteWriter = u32(value.toRawBits().toLong() and 0xFFFFFFFFL)

    fun f64(value: Double): ByteWriter = u64(value.toRawBits())

    fun bytes(value: ByteArray): ByteWriter = apply {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }

    fun zeros(count: Int): ByteWriter = bytes(ByteArray(count))

    /** [text] as UTF-8 padded with NULs to a multiple of 8 bytes, at least one NUL, as rt_core writes the text of a block. */
    fun paddedText(text: String): ByteWriter {
        val encoded = text.encodeToByteArray()
        return bytes(encoded).zeros(8 - encoded.size % 8)
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }
}

/** A record in a ByteArray: the fields put at their offsets. */
internal class RecordWriter(size: Int) {
    val bytes = ByteArray(size)

    fun u8(at: Int, value: Int): RecordWriter = apply { bytes[at] = value.toByte() }

    fun u16(at: Int, value: Int, bigEndian: Boolean = false): RecordWriter = apply {
        val low = value.toByte()
        val high = (value shr 8).toByte()
        bytes[at] = if (bigEndian) high else low
        bytes[at + 1] = if (bigEndian) low else high
    }

    fun u32(at: Int, value: Long, bigEndian: Boolean = false): RecordWriter = apply {
        for (i in 0 until 4) bytes[at + if (bigEndian) 3 - i else i] = (value shr (8 * i)).toByte()
    }

    fun u64(at: Int, value: Long, bigEndian: Boolean = false): RecordWriter = apply {
        for (i in 0 until 8) bytes[at + if (bigEndian) 7 - i else i] = (value shr (8 * i)).toByte()
    }

    fun f32(at: Int, value: Float, bigEndian: Boolean = false): RecordWriter =
        u32(at, value.toRawBits().toLong() and 0xFFFFFFFFL, bigEndian)

    fun f64(at: Int, value: Double, bigEndian: Boolean = false): RecordWriter = u64(at, value.toRawBits(), bigEndian)

    fun bytes(at: Int, value: ByteArray): RecordWriter = apply { value.copyInto(bytes, at) }
}
