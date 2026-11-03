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

    /**
     * The file ends before the lists that name the stream's blocks do, so that the stream has fewer blocks than the file declares:
     * [declaredLength] is what the lists named so far hold. A file that grows may fill the lists in.
     */
    val isCut: Boolean get() = false

    /** Reads up to [length] bytes from [position] (of the [available] ones) into [into] at [offset]; the number read. */
    suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int

    /** Fails now, as a read would later, where the stream's data can't be decoded (a zipped block that doesn't inflate). */
    suspend fun verify() {}

    /**
     * Looks again at a file that has grown since the stream last looked: the lists the end of the file cut short, and the end of a
     * block whose length can't be believed. A reader of the stream calls it once each time it comes back for more.
     */
    suspend fun refresh() {}
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
 * A block of [isZeros] has no bytes in the file: its data is [length] zeros, for the records of column storage that have no
 * invalidation block.
 */
internal class DataLeaf(
    val offset: Long,
    val dataStart: Long,
    /** The bytes the block holds once inflated; [OPEN_ENDED] for the last block of an unfinished file, which reaches as far as the file. */
    val length: Long,
    val zip: ZipInfo?,
    /** For an open-ended block, where the next block begins, where one is known. */
    val limit: Long? = null,
    /** The length the block's header claims: [length], unless the block is open-ended and the claim is not to be believed. */
    val declared: Long = length,
    val isZeros: Boolean = false,
) {
    val isOpenEnded: Boolean get() = length == OPEN_ENDED

    /** The bytes the block takes in the file after its header: its data as they are, or a zipped block's zipped ones. */
    val storedLength: Long get() = if (isZeros) 0L else zip?.zippedLength ?: length

    /** The same block, whose length is not to be believed: its data reach to [limit], or the end of the file. */
    fun openEnded(limit: Long?): DataLeaf = DataLeaf(offset, dataStart, OPEN_ENDED, zip, limit, declared, isZeros)

    /** The same block with the length its header claims. */
    fun closed(): DataLeaf = if (isOpenEnded) DataLeaf(offset, dataStart, declared, zip, null, declared, isZeros) else this

    companion object {
        const val OPEN_ENDED = -1L

        /** A block of [length] zeros ([OPEN_ENDED] for as many as are asked for). */
        fun zeros(length: Long): DataLeaf = DataLeaf(OPEN_ENDED, 0L, length, null, isZeros = true)
    }
}

/** How a stream looks again at a file that has grown: it walks on the lists the end of the file cut short, and settles the last block. */
internal interface Revisit {
    /** The walk [previous] was, gone on from where the end of the file cut it. */
    suspend fun resume(previous: Walk): Walk

    /** The last block [last] of a walk that reached the end of its lists, as it is or as one whose length can't be believed. */
    suspend fun settle(last: DataLeaf): DataLeaf
}

/** The length of the zeros that stand for the invalidation of an open-ended block of values: as many as anything asks. */
private const val UNBOUNDED_ZEROS = 1L shl 52

/**
 * The data of the blocks of a [Walk] one after the other as one stream. A zipped block is inflated when a read reaches it, and the
 * last one inflated is kept, so a scan that goes forward inflates each once and holds one. Where the walk was cut short by the end
 * of a file that is still arriving, [refresh] walks on from the list it was cut at when the file has grown.
 */
internal class SegmentedData(private val reader: BlockReader, walk: Walk, private val revisit: Revisit? = null) : DataStream {
    private var leaves: List<DataLeaf> = walk.leaves
    private var resume: Resume? = walk.resume
    private var cut = walk.isCut
    private var walkedAt = walk.size
    private var named = walk.named

    // starts[i] is where leaf i begins in the stream; starts[leaves.size] is the length of the whole, where it is known.
    private var starts = LongArray(0)
    private var isOpenEnded = false

    // Leaves before this index are known to be wholly in the file; a file only grows, so they stay so.
    private var settled = 0

    private var inflatedIndex = -1
    private var inflatedLeaf: DataLeaf? = null
    private var inflated: ByteArray? = null

    init {
        layOut(0)
    }

    /** Puts [replacements] in the place of the stream's blocks, the first [keep] of which they have as they were. */
    fun replace(replacements: List<DataLeaf>, keep: Int) {
        leaves = replacements
        layOut(keep)
    }

    // [keep] leaves at the front are the very ones the stream had before, so what was learned of them is kept.
    private fun layOut(keep: Int) {
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
        settled = minOf(settled, keep)
        // The block inflated last is kept where it is one of those the stream had before.
        if (inflatedIndex !in 0 until minOf(keep, leaves.size) || leaves[inflatedIndex] !== inflatedLeaf) {
            inflatedIndex = -1
            inflatedLeaf = null
            inflated = null
        }
    }

    override val origin: Long get() = leaves.firstOrNull()?.offset ?: 0L

    override val declaredLength: Long? get() = if (isOpenEnded) null else starts[leaves.size]

    override val isCut: Boolean get() = cut

    override suspend fun available(): Long {
        val size = reader.size
        while (settled < leaves.size && isWhollyPresent(leaves[settled], size)) settled++
        return if (settled == leaves.size) starts[leaves.size] else starts[settled] + presentOf(leaves[settled], size)
    }

    override suspend fun refresh() {
        val look = revisit ?: return
        val size = reader.size
        if (size <= walkedAt) return
        walkedAt = size
        if (cut) {
            walkOn(look)
        } else if (isOpenEnded) {
            settleLast(look)
        }
    }

    // The lists were cut short by the end of the file, which has grown: they are walked on from where they were cut.
    private suspend fun walkOn(look: Revisit) {
        val kept = resume?.keep ?: leaves.size
        val found = look.resume(Walk(leaves, cut, resume, walkedAt, named))
        cut = found.isCut
        resume = found.resume
        walkedAt = found.size
        named = found.named
        leaves = found.leaves
        // The blocks the walk had are the same ones, but for the last, which a walk that is no longer cut settles.
        layOut(if (found.isCut) kept else maxOf(0, kept - 1))
    }

    // The last block was taken for one whose length can't be believed: a block that begins at its declared end settles it.
    private suspend fun settleLast(look: Revisit) {
        val last = leaves.lastOrNull() ?: return
        val now = look.settle(last)
        if (now.isOpenEnded == last.isOpenEnded) return
        leaves = leaves.dropLast(1) + now
        layOut(leaves.size - 1)
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
        val count = minOf(length.toLong(), presentOf(leaf, reader.size) - local).toInt()
        return when {
            count <= 0 -> 0
            leaf.isZeros -> count.also { into.fill(0, offset, offset + count) }
            leaf.zip != null -> readInflated(index, local.toInt(), into, offset, count)
            else -> reader.readInto(leaf.dataStart + local, into, offset, count)
        }
    }

    private suspend fun readInflated(
        index: Int,
        local: Int,
        into: ByteArray,
        offset: Int,
        count: Int,
    ): Int {
        val data = inflatedBytes(index) ?: return 0
        data.copyInto(into, offset, local, local + count)
        return count
    }

    // Whether all of the leaf's bytes are in the file now; a block with no length is never wholly there.
    private fun isWhollyPresent(leaf: DataLeaf, size: Long): Boolean = !leaf.isOpenEnded && leaf.storedLength <= size - leaf.dataStart

    // How many bytes of the leaf's data can be read now: a zipped block gives none until all of it is there to inflate.
    private fun presentOf(leaf: DataLeaf, size: Long): Long = when {
        leaf.isZeros -> if (leaf.isOpenEnded) UNBOUNDED_ZEROS else leaf.length
        leaf.zip != null -> if (isWhollyPresent(leaf, size)) leaf.length else 0L
        leaf.isOpenEnded -> maxOf(0L, minOf(leaf.limit ?: Long.MAX_VALUE, size) - leaf.dataStart)
        else -> maxOf(0L, minOf(leaf.length, size - leaf.dataStart))
    }

    private suspend fun inflatedBytes(index: Int): ByteArray? {
        val leaf = leaves[index]
        if (index == inflatedIndex && leaf === inflatedLeaf) return inflated
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
        inflatedLeaf = leaf
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
