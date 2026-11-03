package com.eerie_leap.mdf

private const val BITS_16 = 16
private const val BITS_32 = 32
private const val BITS_64 = 64

/** A value in a record, read by where it is: the offset into the record's data, the bit offset and the bit count. */
internal class NumberField(
    private val dataType: MdfDataType,
    private val byteOffset: Int,
    private val bitOffset: Int,
    private val bitCount: Int,
) {
    private val shape: Shape = shapeOf()

    private enum class Shape { FloatLittle64, FloatLittle32, UnsignedLittle8, Other }

    /** The value as a Double, from the record data that begins at [at] in [record]. */
    fun read(record: ByteArray, at: Int): Double {
        val position = at + byteOffset
        return when (shape) {
            Shape.FloatLittle64 -> Double.fromBits(record.u64(position))
            Shape.FloatLittle32 -> Float.fromBits(record.u32AsInt(position)).toDouble()
            Shape.UnsignedLittle8 -> record.u8(position).toDouble()
            Shape.Other -> MdfValues.number(dataType, record, position, bitOffset, bitCount)
        }
    }

    /** The field's bits as an unsigned number: an offset into variable-length data, an ID, a flag. */
    fun readBits(record: ByteArray, at: Int): Long = MdfValues.bits(record, at + byteOffset, bitOffset, bitCount, dataType.isBigEndian)

    private fun shapeOf(): Shape = when {
        bitOffset != 0 -> Shape.Other
        dataType == MdfDataType.FloatLittleEndian && bitCount == BITS_64 -> Shape.FloatLittle64
        dataType == MdfDataType.FloatLittleEndian && bitCount == BITS_32 -> Shape.FloatLittle32
        dataType == MdfDataType.UnsignedIntegerLittleEndian && bitCount == Byte.SIZE_BITS -> Shape.UnsignedLittle8
        else -> Shape.Other
    }

    companion object {
        /**
         * The reader of the offset a variable-length [channel] keeps in its records, to its values: an unsigned integer of the
         * channel's bit count, whatever data type the channel gives its values (a string, bytes).
         */
        fun offsetOf(channel: MdfChannel): NumberField =
            NumberField(MdfDataType.UnsignedIntegerLittleEndian, channel.byteOffset, channel.bitOffset, channel.bitCount)

        /** The reader of [channel]'s value, null where it isn't a number this reader decodes. */
        fun of(channel: MdfChannel): NumberField? {
            val type = channel.dataType
            val fits = when {
                type.isFloat -> channel.bitOffset == 0 && channel.bitCount in setOf(BITS_16, BITS_32, BITS_64)
                type.isInteger -> channel.bitCount in 1..BITS_64
                else -> false
            }
            return if (fits) NumberField(type, channel.byteOffset, channel.bitOffset, channel.bitCount) else null
        }
    }
}

/** The bytes of a variable-length group's values (strings, CAN payloads), held until the record that names one asks for it. */
internal class VariableLengthStore {
    private val payloads = LinkedHashMap<Long, ByteArray>()
    private var storedBytes = 0L

    /** The offset the next value is stored at: the bytes of the values before it, each with its 4-byte length. */
    var nextOffset = 0L
        private set

    fun add(value: ByteArray) {
        payloads[nextOffset] = value
        nextOffset += U32_SIZE + value.size
        storedBytes += value.size
        // A value nothing ever asks for would pile up: past the limit the oldest go.
        while (storedBytes > MAX_STORED_BYTES && payloads.isNotEmpty()) {
            val oldest = payloads.entries.iterator().next()
            payloads.remove(oldest.key)
            storedBytes -= oldest.value.size
        }
    }

    /** The value stored at [offset], which is then forgotten; null for none. */
    fun take(offset: Long): ByteArray? = payloads.remove(offset)?.also { storedBytes -= it.size }

    private companion object {
        const val MAX_STORED_BYTES = 64L * 1024 * 1024
    }
}

/** Where a channel's invalidation bit is in a record, and whether the channel is invalid whatever it says. */
internal class Validity(channel: MdfChannel, dataBytes: Long) {
    private val allInvalid: Boolean = channel.allValuesInvalid

    // The byte of the record's data (after the record ID) that holds the bit, -1 for a channel without one.
    private val byteOffset: Int = channel.invalidationBit?.let { dataBytes.toInt() + it / Byte.SIZE_BITS } ?: -1
    private val mask: Int = channel.invalidationBit?.let { 1 shl (it % Byte.SIZE_BITS) } ?: 0

    fun isInvalid(record: ByteArray, at: Int): Boolean = allInvalid || (byteOffset >= 0 && (record.u8(at + byteOffset) and mask) != 0)
}
