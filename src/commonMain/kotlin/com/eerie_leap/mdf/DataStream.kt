package com.eerie_leap.mdf

/**
 * A run of data as one stream of bytes, wherever the file keeps it: in a data block, a zipped one, a list of them. Positions are
 * into the stream, not the file. A data group's records are one stream; the variable-length values of a channel are another.
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

    /** Fails now, as a read would later, where the stream's data can't be decoded (a zipped block that doesn't inflate). */
    suspend fun verify() {}
}

/** A stream of nothing, for a data group that has no data. */
internal object EmptyData : DataStream {
    override val origin: Long get() = 0L

    override val declaredLength: Long get() = 0L

    override suspend fun available(): Long = 0L

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int = 0
}

/** The fields of a zipped data block (DZ) after its header. */
internal object ZippedBlock {
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

/** How a zipped block stores its data: [type] (deflate or transposed deflate), [parameter] (the columns), its zipped size. */
internal class ZipInfo(val type: Int, val parameter: Int, val zippedLength: Long) {
    /** The record size the data was transposed by, 0 for plain deflate. */
    val transposeColumns: Int get() = if (type == ZippedBlock.ZIP_TRANSPOSED) parameter else 0
}

/**
 * One block of data in a list (or alone): where its bytes are in the file, how many there are once inflated, and how they are
 * stored. The data of a plain block (DT, DV, DI, SD) is in the file as it is; a zipped one (DZ) is inflated whole when it is read.
 */
internal class DataLeaf(
    val offset: Long,
    val dataStart: Long,
    /** The bytes the block holds once inflated; [OPEN_ENDED] for the last block of an unfinished file, which reaches as far as the file. */
    val length: Long,
    val zip: ZipInfo?,
    /** For an open-ended block, where the next block begins, where one is known. */
    val limit: Long? = null,
) {
    val isOpenEnded: Boolean get() = length == OPEN_ENDED

    /** The bytes the block takes in the file after its header: its data as they are, or a zipped block's zipped ones. */
    val storedLength: Long get() = zip?.zippedLength ?: length

    /** The same block, whose length is not to be believed: its data reach to [limit], or the end of the file. */
    fun openEnded(limit: Long?): DataLeaf = DataLeaf(offset, dataStart, OPEN_ENDED, zip, limit)

    companion object {
        const val OPEN_ENDED = -1L
    }
}

/**
 * The data of the blocks of a [Walk] one after the other as one stream. A zipped block is inflated when a read reaches it, and the
 * last one inflated is kept, so a scan that goes forward inflates each once and holds one. Where the walk was cut short by the end
 * of a file that is still arriving, [again] walks the lists once more when the file has grown.
 */
internal class SegmentedData(private val reader: BlockReader, walk: Walk, private val again: (suspend () -> Walk)? = null) : DataStream {
    private var leaves: List<DataLeaf> = walk.leaves

    // starts[i] is where leaf i begins in the stream; starts[leaves.size] is the length of the whole, where it is known.
    private var starts = LongArray(0)
    private var isOpenEnded = false
    private var isCut = walk.isCut
    private var walkedAt = 0L

    // Leaves before this index are known to be wholly in the file; a file only grows, so they stay so.
    private var settled = 0

    private var inflatedIndex = -1
    private var inflated: ByteArray? = null

    init {
        layOut()
    }

    private fun layOut() {
        val size = reader.size
        starts = LongArray(leaves.size + 1)
        for ((index, leaf) in leaves.withIndex()) {
            if (leaf.isOpenEnded) {
                starts[index + 1] = starts[index]
                continue
            }
            // Only the last block can run past the end of the file (it is being written, or the file was cut); and the data of all
            // the blocks can't add up to more than a number holds.
            if (index < leaves.lastIndex && leaf.storedLength > size - leaf.dataStart) {
                damaged(
                    leaf.offset,
                    "the data block at byte ${leaf.offset} claims ${leaf.storedLength} bytes, more than the file holds after it",
                )
            }
            if (leaf.length > Long.MAX_VALUE - starts[index]) damaged(leaf.offset, "the data blocks claim more bytes than any file holds")
            starts[index + 1] = starts[index] + leaf.length
        }
        isOpenEnded = leaves.lastOrNull()?.isOpenEnded == true
        settled = 0
        inflatedIndex = -1
        inflated = null
    }

    override val origin: Long get() = leaves.firstOrNull()?.offset ?: 0L

    override val declaredLength: Long? get() = if (isOpenEnded || isCut) null else starts[leaves.size]

    override suspend fun available(): Long {
        val size = reader.size
        if (isCut && again != null && size > walkedAt) walkAgain(again, size)
        while (settled < leaves.size && isWhollyPresent(leaves[settled], size)) settled++
        return if (settled == leaves.size) starts[leaves.size] else starts[settled] + presentOf(leaves[settled], size)
    }

    // The lists were cut short by the end of the file, which has grown: what they name now is walked again.
    private suspend fun walkAgain(walk: suspend () -> Walk, size: Long) {
        walkedAt = size
        val found = walk()
        leaves = found.leaves
        isCut = found.isCut
        layOut()
    }

    override suspend fun verify() {
        val first = leaves.firstOrNull() ?: return
        if (first.zip != null && isWhollyPresent(first, reader.size)) inflatedBytes(0)
    }

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        var count = 1
        while (done < length && count > 0) {
            val at = position + done
            val index = leafAt(at)
            count = if (index < 0) 0 else readLeaf(index, at - starts[index], into, offset + done, length - done)
            done += count
        }
        return done
    }

    // The leaf the stream's byte [at] is in: the last one that begins at or before it; -1 for none, and for a place past the end.
    private fun leafAt(at: Long): Int {
        if (at < 0 || leaves.isEmpty()) return -1
        if (!isOpenEnded && at >= starts[leaves.size]) return -1
        var low = 0
        var high = leaves.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= at) low = middle + 1 else high = middle
        }
        return low - 1
    }

    private suspend fun readLeaf(
        index: Int,
        local: Long,
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val leaf = leaves[index]
        if (leaf.zip != null) {
            val data = inflatedBytes(index) ?: return 0
            val count = minOf(length.toLong(), data.size - local).toInt()
            if (count <= 0) return 0
            data.copyInto(into, offset, local.toInt(), local.toInt() + count)
            return count
        }
        val count = minOf(length.toLong(), presentOf(leaf, reader.size) - local).toInt()
        return if (count <= 0) 0 else reader.readInto(leaf.dataStart + local, into, offset, count)
    }

    // Whether all of the leaf's bytes are in the file now; a block with no length is never wholly there.
    private fun isWhollyPresent(leaf: DataLeaf, size: Long): Boolean = !leaf.isOpenEnded && leaf.storedLength <= size - leaf.dataStart

    // How many bytes of the leaf's data can be read now: a zipped block gives none until all of it is there to inflate.
    private fun presentOf(leaf: DataLeaf, size: Long): Long = when {
        leaf.zip != null -> if (isWhollyPresent(leaf, size)) leaf.length else 0L
        leaf.isOpenEnded -> maxOf(0L, minOf(leaf.limit ?: Long.MAX_VALUE, size) - leaf.dataStart)
        else -> maxOf(0L, minOf(leaf.length, size - leaf.dataStart))
    }

    private suspend fun inflatedBytes(index: Int): ByteArray? {
        if (index == inflatedIndex) return inflated
        val leaf = leaves[index]
        val zip = leaf.zip ?: return null
        if (!isWhollyPresent(leaf, reader.size)) return null
        val zipped = reader.bytes(leaf.dataStart, zip.zippedLength.toInt())
        val plain = try {
            inflate(zipped, 0, zipped.size, leaf.length.toInt())
        } catch (e: IllegalArgumentException) {
            damaged(leaf.offset, "the zipped data block doesn't inflate: ${e.message}")
        }
        val bytes = if (zip.transposeColumns > 0) untranspose(plain, zip.transposeColumns) else plain
        inflatedIndex = index
        inflated = bytes
        return bytes
    }
}

// The bytes a transposed block stores column by column, back in records of [columns] bytes; a last record that is incomplete
// follows as it was.
internal fun untranspose(data: ByteArray, columns: Int): ByteArray {
    val rows = data.size / columns
    val result = ByteArray(data.size)
    for (column in 0 until columns) {
        for (row in 0 until rows) result[row * columns + column] = data[column * rows + row]
    }
    data.copyInto(result, rows * columns, rows * columns, data.size)
    return result
}
