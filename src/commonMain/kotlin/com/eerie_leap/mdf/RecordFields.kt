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

/**
 * The bytes of a variable-length group's values (strings, CAN payloads), held until the records that name them have asked for them.
 * Each value is handed to every reader that asks for its place (a channel requested twice, a channel and the CAN frames that join it)
 * and let go of after the last. A reader names a value by its offset into the group's data, or, where the file's offsets are stale
 * ([recount]), by its place in the order the values come: the n-th record of the reader takes the n-th value.
 */
internal class VariableLengthStore(private val recount: Boolean = false) {
    private class Entry(val bytes: ByteArray) {
        // The readers (by the low bits of their number) that took the value.
        var takenBy = 0L
    }

    private val entries = LinkedHashMap<Long, Entry>()
    private var storedBytes = 0L
    private var count = 0L
    private val cursors = ArrayList<Long>()

    /** The offset the next value is stored at: the bytes of the values before it, each with its 4-byte length. */
    var nextOffset = 0L
        private set

    /** A reader's number: it is given to [keyFor] and [take]. Every reader registers before the first value is added. */
    fun register(): Int {
        cursors.add(0L)
        return cursors.size - 1
    }

    /** The key under which a reader finds the value its record names at [offset]: the offset, or its next place in order. */
    fun keyFor(reader: Int, offset: Long): Long = if (recount) cursors[reader].also { cursors[reader] = it + 1 } else offset

    /** Stores a value and returns its key: the offset it is at, or its place in order. */
    fun add(value: ByteArray): Long {
        val key = if (recount) count else nextOffset
        entries[key] = Entry(value)
        count++
        nextOffset += U32_SIZE + value.size
        storedBytes += value.size
        // A value nothing ever asks for would pile up: past the limit the oldest go.
        while (storedBytes > MAX_STORED_BYTES && entries.isNotEmpty()) {
            val oldest = entries.entries.iterator().next()
            entries.remove(oldest.key)
            storedBytes -= oldest.value.bytes.size
        }
        return key
    }

    /** The value at [key] for [reader]; null if there is none, or the reader took it already. The last reader to take it lets it go. */
    fun take(reader: Int, key: Long): ByteArray? {
        val entry = entries[key] ?: return null
        val bit = 1L shl (reader and READER_MASK)
        if ((entry.takenBy and bit) != 0L) return null
        entry.takenBy = entry.takenBy or bit
        if (Long.SIZE_BITS >= cursors.size && entry.takenBy == allReaders()) {
            entries.remove(key)
            storedBytes -= entry.bytes.size
        }
        return entry.bytes
    }

    private fun allReaders(): Long = if (cursors.size >= Long.SIZE_BITS) -1L else (1L shl cursors.size) - 1

    private companion object {
        const val MAX_STORED_BYTES = 64L * 1024 * 1024
        const val READER_MASK = Long.SIZE_BITS - 1
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
