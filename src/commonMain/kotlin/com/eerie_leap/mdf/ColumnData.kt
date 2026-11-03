package com.eerie_leap.mdf

/**
 * The records of a data group that stores its values and its invalidation bytes apart (MDF 4.2's column storage: DV and DI blocks),
 * as the one stream of whole records a scan reads: each record's [dataBytes] of values, then its [invalidationBytes]. Without
 * invalidation blocks every value is valid.
 */
internal class ColumnData(
    private val values: DataStream,
    private val invalidation: DataStream?,
    private val dataBytes: Int,
    private val invalidationBytes: Int,
) : DataStream {
    private val recordBytes = dataBytes + invalidationBytes
    private var valueScratch = ByteArray(0)
    private var invalidationScratch = ByteArray(0)
    private var recordScratch = ByteArray(0)

    override val origin: Long get() = values.origin

    override val declaredLength: Long?
        get() {
            val fromValues = recordsIn(values.declaredLength ?: return null, dataBytes)
            val fromInvalidation = invalidation?.let { recordsIn(it.declaredLength ?: return null, invalidationBytes) } ?: Long.MAX_VALUE
            return bytesOf(minOf(fromValues, fromInvalidation))
        }

    override suspend fun available(): Long = bytesOf(wholeRecords())

    override suspend fun verify() {
        values.verify()
        invalidation?.verify()
    }

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        val first = position / recordBytes
        val skip = (position % recordBytes).toInt()
        val wanted = (skip.toLong() + length + recordBytes - 1) / recordBytes
        val count = minOf(wanted, wholeRecords() - first).toInt()
        if (count <= 0) return 0
        val image = recordImage(first, count)
        val taken = minOf(length, count * recordBytes - skip)
        image.copyInto(into, offset, skip, skip + taken)
        return taken
    }

    // The whole records both streams hold now.
    private suspend fun wholeRecords(): Long {
        val fromValues = recordsIn(values.available(), dataBytes)
        val fromInvalidation = invalidation?.let { recordsIn(it.available(), invalidationBytes) } ?: Long.MAX_VALUE
        return if (fromValues == Long.MAX_VALUE &&
            fromInvalidation == Long.MAX_VALUE
        ) {
            0L
        } else {
            maxOf(0L, minOf(fromValues, fromInvalidation))
        }
    }

    private fun recordsIn(bytes: Long, size: Int): Long = if (size == 0) Long.MAX_VALUE else bytes / size

    // The bytes [records] records take, as many as a number holds at the most.
    private fun bytesOf(records: Long): Long = if (records > Long.MAX_VALUE / recordBytes) Long.MAX_VALUE else records * recordBytes

    // [count] records from record [first], their values and invalidation bytes put together.
    private suspend fun recordImage(first: Long, count: Int): ByteArray {
        val valueBytes = count * dataBytes
        val flagBytes = count * invalidationBytes
        if (valueScratch.size < valueBytes) valueScratch = ByteArray(valueBytes)
        if (invalidationScratch.size < flagBytes) invalidationScratch = ByteArray(flagBytes)
        if (recordScratch.size < count * recordBytes) recordScratch = ByteArray(count * recordBytes)
        if (valueBytes > 0) values.read(first * dataBytes, valueScratch, 0, valueBytes)
        when {
            flagBytes == 0 -> Unit
            invalidation != null -> invalidation.read(first * invalidationBytes, invalidationScratch, 0, flagBytes)
            else -> invalidationScratch.fill(0, 0, flagBytes)
        }
        for (record in 0 until count) {
            valueScratch.copyInto(recordScratch, record * recordBytes, record * dataBytes, (record + 1) * dataBytes)
            invalidationScratch.copyInto(
                recordScratch,
                record * recordBytes + dataBytes,
                record * invalidationBytes,
                (record + 1) * invalidationBytes,
            )
        }
        return recordScratch
    }
}
