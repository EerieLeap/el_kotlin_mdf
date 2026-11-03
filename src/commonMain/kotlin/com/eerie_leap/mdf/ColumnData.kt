package com.eerie_leap.mdf

/** How column storage looks again at a file that has grown: it walks on the lists the end of the file cut short, and settles the last block. */
internal interface ColumnRevisit {
    /** The walk [previous] was, gone on from where the end of the file cut it. */
    suspend fun resume(previous: ColumnWalk): ColumnWalk

    /** The last block [last] of a walk that reached the end of its lists, as it is or as one whose length can't be believed. */
    suspend fun settle(last: DataLeaf): DataLeaf
}

/**
 * The records of a data group that stores its values and its invalidation bytes apart (MDF 4.2's column storage: DV and DI blocks),
 * as the one stream of whole records a scan reads: each record's [dataBytes] of values, then its [invalidationBytes]. The
 * invalidation bytes of a block of values are those of the block of its pair, and a block of values with none has records that are
 * all valid, so that the two streams (the values, and the invalidation bytes with zeros for the records that have none) go record
 * for record. Without any invalidation block every value is valid.
 */
internal class ColumnData(
    private val reader: BlockReader,
    first: ColumnWalk,
    private val dataBytes: Int,
    private val invalidationBytes: Int,
    private val revisit: ColumnRevisit? = null,
) : DataStream {
    private val recordBytes = dataBytes + invalidationBytes
    private var walk = first
    private var walkedAt = first.size
    private val values = SegmentedData(reader, Walk(emptyList(), false))
    private var invalidation: SegmentedData? = null
    private var valueScratch = ByteArray(0)
    private var invalidationScratch = ByteArray(0)
    private var recordScratch = ByteArray(0)

    init {
        build(0)
    }

    // The streams of the pairs the walk has, the first [keep] of which are the very ones they had before.
    private fun build(keep: Int) {
        values.replace(walk.pairs.map { it.values }, keep)
        if (invalidationBytes == 0 || walk.pairs.none { it.invalidation != null }) {
            invalidation = null
            return
        }
        val stream = invalidation
        val flags = walk.pairs.map { it.invalidation ?: zerosFor(it.values) }
        if (stream == null) {
            invalidation = SegmentedData(reader, Walk(flags, false))
        } else {
            stream.replace(flags, keep)
        }
    }

    // The invalidation bytes of a block of values that has none: a zero for each byte of each of its records (as many as a number holds
    // at the most, for a block that claims more than that).
    private fun zerosFor(block: DataLeaf): DataLeaf = when {
        block.isOpenEnded -> DataLeaf.zeros(DataLeaf.OPEN_ENDED)
        dataBytes == 0 || invalidationBytes == 0 -> DataLeaf.zeros(0L)
        else -> DataLeaf.zeros(minOf(block.length / dataBytes, Long.MAX_VALUE / invalidationBytes) * invalidationBytes)
    }

    override val origin: Long get() = values.origin

    override val isCut: Boolean get() = walk.isCut

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

    override suspend fun refresh() {
        val look = revisit ?: return
        val size = reader.size
        if (size <= walkedAt) return
        walkedAt = size
        if (walk.isCut) {
            val kept = walk.resume?.keep ?: walk.pairs.size
            walk = look.resume(walk)
            // The pairs the walk had are the same ones, but for the last, which a walk that is no longer cut settles.
            build(if (walk.isCut) kept else maxOf(0, kept - 1))
        } else {
            settleLast(look)
        }
    }

    // The last pair was taken for one whose blocks' lengths can't be believed: a block that begins where one ends settles it.
    private suspend fun settleLast(look: ColumnRevisit) {
        val last = walk.pairs.lastOrNull() ?: return
        if (!last.values.isOpenEnded && last.invalidation?.isOpenEnded != true) return
        val now = ColumnPair(look.settle(last.values), last.invalidation?.let { look.settle(it) })
        val isSame = now.values.isOpenEnded == last.values.isOpenEnded && now.invalidation?.isOpenEnded == last.invalidation?.isOpenEnded
        if (isSame) return
        walk = ColumnWalk(walk.pairs.dropLast(1) + now, walk.isCut, walk.resume, walk.size)
        build(walk.pairs.size - 1)
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
        val flags = invalidation
        when {
            flagBytes == 0 -> Unit
            flags != null -> flags.read(first * invalidationBytes, invalidationScratch, 0, flagBytes)
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
