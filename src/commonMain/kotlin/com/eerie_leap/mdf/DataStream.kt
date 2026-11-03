package com.eerie_leap.mdf

/**
 * A data group's records as one stream of bytes, wherever the file keeps them: in a data block, in a zipped one, (later) in a list
 * of blocks. Positions are into the stream, not the file.
 */
internal interface DataStream {
    /** Where the stream begins in the file: the first byte of a data block, the block of a zipped one. For a failure to say where. */
    val origin: Long

    /** The bytes the stream holds now. For the data of a file still arriving it grows. */
    suspend fun available(): Long

    /** The stream's length as the file declares it, null where the file doesn't say (the last data block of an unfinished file). */
    val declaredLength: Long?

    /** Reads up to [length] bytes from [position] (of the [available] ones) into [into] at [offset]; the number read. */
    suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}

// A data block (DT): the file's bytes from [start] to [declaredEnd], or, for the last block of an unfinished file, to the block
// after it or the file's end, as the file has them now.
private class FileData(
    private val source: MdfSource,
    private val start: Long,
    private val declaredEnd: Long?,
    private val nextBlock: Long?,
) : DataStream {
    override val origin: Long get() = start

    override val declaredLength: Long? get() = declaredEnd?.let { it - start }

    override suspend fun available(): Long {
        val end = minOf(declaredEnd ?: nextBlock ?: Long.MAX_VALUE, source.size)
        return maxOf(0L, end - start)
    }

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        val count = minOf(length.toLong(), available() - position).toInt()
        return if (count <= 0) 0 else source.read(start + position, into, offset, count)
    }
}

// A zipped data block (DZ): inflated whole once all of it is in the file.
private class ZippedData(
    private val reader: BlockReader,
    private val blockOffset: Long,
    private val dataOffset: Long,
    private val zippedLength: Int,
    private val originalLength: Int,
    private val transposeColumns: Int,
) : DataStream {
    private var inflated: ByteArray? = null

    override val origin: Long get() = blockOffset

    override val declaredLength: Long get() = originalLength.toLong()

    override suspend fun available(): Long = data()?.size?.toLong() ?: 0L

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        val data = data() ?: return 0
        val count = minOf(length.toLong(), data.size - position).toInt()
        if (count <= 0) return 0
        data.copyInto(into, offset, position.toInt(), position.toInt() + count)
        return count
    }

    private suspend fun data(): ByteArray? =
        inflated ?: if (reader.size < dataOffset + zippedLength) null else load().also { inflated = it }

    private suspend fun load(): ByteArray {
        val zipped = reader.bytes(dataOffset, zippedLength)
        val plain = try {
            inflate(zipped, 0, zipped.size, originalLength)
        } catch (e: IllegalArgumentException) {
            damaged(blockOffset, "the zipped data block doesn't inflate: ${e.message}")
        }
        return if (transposeColumns > 0) untranspose(plain, transposeColumns) else plain
    }
}

// The bytes a transposed block stores column by column, back in records of [columns] bytes; a last record that is incomplete
// follows as it was.
private fun untranspose(data: ByteArray, columns: Int): ByteArray {
    val rows = data.size / columns
    val result = ByteArray(data.size)
    for (column in 0 until columns) {
        for (row in 0 until rows) result[row * columns + column] = data[column * rows + row]
    }
    data.copyInto(result, rows * columns, rows * columns, data.size)
    return result
}

private object ZippedBlock {
    /** The most a zipped block may inflate to, or hold zipped: it is inflated whole. */
    const val MAX_BYTES = 64L * 1024 * 1024
    const val FIELDS_SIZE = 24
    const val ORIGINAL_TYPE = 0
    const val ZIP_TYPE = 2
    const val PARAMETER = 4
    const val ORIGINAL_LENGTH = 8
    const val ZIPPED_LENGTH = 16
    const val ZIP_DEFLATE = 0
    const val ZIP_TRANSPOSED = 1
}

/** Finds where a data group's records are and gives them as a [DataStream]. */
internal object DataLocator {
    /**
     * The records of [group] of [file]. Fails with an [MdfException]: [MdfFailure.Unsupported] for a way of storing the data this
     * reader doesn't read (yet), [MdfFailure.TooLarge] for a zipped block it won't inflate, [MdfFailure.Damaged] for a data link
     * that isn't a data block.
     */
    suspend fun locate(file: MdfFile, group: MdfDataGroup): DataStream {
        if (group.dataLink == 0L) return EmptyData
        // A reader of its own: the pages another read cached, of the file as it was, may be short of what the file holds now.
        val reader = BlockReader(file.source)
        val header = reader.header(group.dataLink, group.offset)
        // The one place that says how a data group's data is stored. A list of data blocks (DL, with its HL header, or the column
        // storage of MDF 4.2: DV, DI, LD) joins here: a stream of the segments the list names, each a plain or a zipped block, that
        // reads across them as one, with its last segment open-ended as `plain` makes it for a file not finished.
        return when (header.type) {
            "DT" -> plain(file, group, header)

            "DZ" -> zipped(reader, header)

            else -> throw MdfException(
                MdfFailure.Unsupported(
                    header.offset,
                    "data of block ##${header.type}",
                    "only data blocks (DT) and zipped ones (DZ) are read",
                ),
            )
        }
    }

    private fun plain(file: MdfFile, group: MdfDataGroup, header: BlockHeader): DataStream {
        val start = header.contentOffset
        val isLastBlock = file.groups.maxOf { it.dataLink } == group.dataLink
        val isStale = !file.identification.isFinalized && (
            UnfinalizedFlag.LastDataBlockLength in file.identification.unfinalized || header.length <= BlockFormat.HEADER_SIZE
            )
        return if (isStale && isLastBlock) {
            FileData(file.source, start, declaredEnd = null, nextBlock = firstBlockAfter(file.blockOffsets, header.offset))
        } else {
            FileData(file.source, start, declaredEnd = header.end, nextBlock = null)
        }
    }

    private suspend fun zipped(reader: BlockReader, header: BlockHeader): DataStream {
        val fields = reader.bytes(header.contentOffset, ZippedBlock.FIELDS_SIZE)
        val originalType = fields.decodeToString(ZippedBlock.ORIGINAL_TYPE, ZippedBlock.ORIGINAL_TYPE + 2)
        if (originalType != "DT") {
            throw MdfException(
                MdfFailure.Unsupported(header.offset, "zipped data of ##$originalType", "only zipped data blocks (DT) are read"),
            )
        }
        val zipType = fields.u8(ZippedBlock.ZIP_TYPE)
        val parameter = fields.u32AsInt(ZippedBlock.PARAMETER)
        val originalLength = fields.u64(ZippedBlock.ORIGINAL_LENGTH)
        val zippedLength = fields.u64(ZippedBlock.ZIPPED_LENGTH)
        val dataOffset = header.contentOffset + ZippedBlock.FIELDS_SIZE
        checkZipped(header, zipType, parameter, originalLength, zippedLength)
        return ZippedData(
            reader,
            header.offset,
            dataOffset,
            zippedLength.toInt(),
            originalLength.toInt(),
            if (zipType == ZippedBlock.ZIP_TRANSPOSED) parameter else 0,
        )
    }

    private fun checkZipped(
        header: BlockHeader,
        zipType: Int,
        parameter: Int,
        originalLength: Long,
        zippedLength: Long,
    ) {
        val tooLarge = maxOf(originalLength, zippedLength)
        if (tooLarge !in 0..ZippedBlock.MAX_BYTES) throw MdfException(MdfFailure.TooLarge(header.offset, tooLarge, ZippedBlock.MAX_BYTES))
        if (zipType != ZippedBlock.ZIP_DEFLATE && zipType != ZippedBlock.ZIP_TRANSPOSED) {
            throw MdfException(
                MdfFailure.Unsupported(header.offset, "zipped data", "zip type $zipType isn't deflate or transposed deflate"),
            )
        }
        if (zipType == ZippedBlock.ZIP_TRANSPOSED &&
            parameter <= 0
        ) {
            damaged(header.offset, "transposed zipped data with $parameter columns")
        }
        if (header.contentOffset + ZippedBlock.FIELDS_SIZE + zippedLength > header.end) {
            damaged(header.offset, "the zipped data block claims $zippedLength bytes of data but holds fewer")
        }
    }

    // The offset of the first block that begins after [offset], null where none does. [blocks] is sorted.
    private fun firstBlockAfter(blocks: LongArray, offset: Long): Long? {
        var low = 0
        var high = blocks.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (blocks[middle] <= offset) low = middle + 1 else high = middle
        }
        return blocks.getOrNull(low)
    }

    private object EmptyData : DataStream {
        override val origin: Long get() = 0L

        override val declaredLength: Long get() = 0L

        override suspend fun available(): Long = 0L

        override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int = 0
    }
}
