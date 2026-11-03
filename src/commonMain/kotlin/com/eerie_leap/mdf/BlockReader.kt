package com.eerie_leap.mdf

/** The layout every MDF 4 block shares: `##XX`, 4 reserved bytes, the block's length, the number of links, then the links. */
internal object BlockFormat {
    const val HEADER_SIZE = 24
    const val LINK_SIZE = 8
    const val TYPE_SIZE = 4
    const val LENGTH_AT = 8
    const val LINK_COUNT_AT = 16

    /** The identification block takes the file's first 64 bytes, and the header block follows it. */
    const val ID_BLOCK_SIZE = 64
    const val HEADER_BLOCK_OFFSET = 64L

    /** Every block begins at a multiple of this, in a file of MDF 4. */
    const val ALIGNMENT = 8L

    /** A link past the end of a file to beyond this (1 TiB) isn't a cut file's: no file is that large. */
    const val MAX_PLAUSIBLE_OFFSET = 1L shl 40

    /** The most one metadata block (names, conversions, comments) may hold; data blocks aren't read as blocks. */
    const val MAX_METADATA_BLOCK_BYTES = 128L * 1024 * 1024
}

private const val PAGE_SIZE = 64 * 1024
private const val PAGE_COUNT = 16
private const val INITIAL_OFFSETS = 256
private val BLOCK_MARK = "##".encodeToByteArray()

internal fun damaged(offset: Long, reason: String): Nothing = throw MdfException(MdfFailure.Damaged(offset, reason))

/** A block read whole: its [type] (`CN` for `##CN`), its [bytes] from the header on, and how many links it has. */
internal class Block(
    val offset: Long,
    val type: String,
    val bytes: ByteArray,
    val linkCount: Int,
    /** The length the block's header claims; more than the bytes held, for a block read in part. */
    val length: Long = bytes.size.toLong(),
) {
    /** Where the block's own fields begin, after the header and the links. */
    val dataStart: Int = BlockFormat.HEADER_SIZE + linkCount * BlockFormat.LINK_SIZE

    val dataSize: Int get() = bytes.size - dataStart

    /** Link [index], 0 (no block) where the block has fewer links, as an older version of the format's blocks do. */
    fun link(index: Int): Long = if (index < linkCount) bytes.u64(BlockFormat.HEADER_SIZE + index * BlockFormat.LINK_SIZE) else 0L

    /** Fails as damaged unless the block's own fields are at least [size] bytes. */
    fun requireData(size: Int) {
        if (dataSize < size) damaged(offset, "block ##$type holds $dataSize bytes of fields, fewer than the $size its fields take")
    }

    fun u8(field: Int): Int = bytes.u8(dataStart + field)

    fun u16(field: Int): Int = bytes.u16(dataStart + field)

    fun i16(field: Int): Int = bytes.i16(dataStart + field)

    fun u32(field: Int): Long = bytes.u32(dataStart + field)

    fun u32AsInt(field: Int): Int = bytes.u32AsInt(dataStart + field)

    fun u64(field: Int): Long = bytes.u64(dataStart + field)

    fun f64(field: Int): Double = bytes.f64(dataStart + field)
}

/** A block's header: where it is, its [type], the [length] it claims (the header and links included) and how many links it has. */
internal class BlockHeader(val offset: Long, val type: String, val length: Long, val linkCount: Int) {
    /** Where the block's contents begin: after the header and the links. */
    val contentOffset: Long = offset + BlockFormat.HEADER_SIZE + linkCount.toLong() * BlockFormat.LINK_SIZE

    val end: Long get() = offset + length
}

/**
 * Reads blocks, checking each against the file's size and its own length and its type against what the link to it names. Small
 * reads go through a few cached pages, since a file's blocks are many and small and a source may be slow.
 */
internal class BlockReader(private val source: MdfSource) {
    private val pageStarts = LongArray(PAGE_COUNT) { -1L }
    private val pageValid = IntArray(PAGE_COUNT)
    private val pages = Array(PAGE_COUNT) { ByteArray(0) }
    private var nextSlot = 0

    private var blockOffsets = LongArray(INITIAL_OFFSETS)
    private var blockCount = 0

    // The file's size as last read: a file only grows, so a size is read again only when a request reaches past the one known.
    private var knownSize = 0L

    val size: Long get() = source.size.also { knownSize = it }

    /** The offset of every block read so far, in order, for finding where the block after another one begins. */
    fun readBlockOffsets(): LongArray = blockOffsets.copyOf(blockCount).also { it.sort() }

    /** [length] bytes at [position]. Fails as [MdfFailure.Truncated] where the file has fewer. */
    suspend fun bytes(position: Long, length: Int): ByteArray {
        val end = position + length
        if (position < 0 || end > sizeReaching(end)) throw MdfException(MdfFailure.Truncated(position, end))
        val result = ByteArray(length)
        if (length >= PAGE_SIZE) {
            if (readFully(position, result, length) < length) throw MdfException(MdfFailure.Truncated(position, end))
        } else {
            copyThroughPages(position, result)
        }
        return result
    }

    /**
     * Up to [length] bytes at [position], read from the source as they are and not through the cached pages: for the headers of data
     * blocks, which are many and far apart. Fewer come back only at the end of the file.
     */
    suspend fun directBytes(position: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        val count = readFully(position, buffer, length)
        return if (count == length) buffer else buffer.copyOf(count)
    }

    /**
     * The block at [target], which a link in the block at [holder] names, and which must be one of the [expected] types (any
     * type if none is given). Fails as damaged for a link outside the file or to something that isn't a block of the type, and as
     * truncated for a block that runs past the file's end.
     */
    suspend fun block(target: Long, holder: Long, vararg expected: String): Block {
        val header = header(target, holder, *expected)
        if (header.length > BlockFormat.MAX_METADATA_BLOCK_BYTES) {
            throw MdfException(MdfFailure.TooLarge(target, header.length, BlockFormat.MAX_METADATA_BLOCK_BYTES))
        }
        if (header.end > sizeReaching(header.end)) throw MdfException(MdfFailure.Truncated(target, header.end))
        return Block(target, header.type, bytes(target, header.length.toInt()), header.linkCount)
    }

    /**
     * The block at [target] (of the [expected] types) with its links and the first [fieldBytes] of its fields only, for a block that
     * ends in data too large to read with it (an attachment). Fails as truncated where the file ends inside what is read.
     */
    suspend fun blockStart(target: Long, holder: Long, fieldBytes: Int, vararg expected: String): Block {
        val header = header(target, holder, *expected)
        val wanted = BlockFormat.HEADER_SIZE + header.linkCount * BlockFormat.LINK_SIZE + fieldBytes
        val length = minOf(header.length, wanted.toLong()).toInt()
        if (target + length > sizeReaching(target + length)) throw MdfException(MdfFailure.Truncated(target, target + length))
        return Block(target, header.type, bytes(target, length), header.linkCount, header.length)
    }

    /**
     * The header of the block at [target], for a block whose contents aren't read whole (data, data lists), with the checks of
     * [block] but for the block running past the file's end, which a data block of a file still arriving does.
     */
    suspend fun header(target: Long, holder: Long, vararg expected: String): BlockHeader {
        checkLink(target, holder)
        return headerFrom(bytes(target, BlockFormat.HEADER_SIZE), target, holder, expected)
    }

    /**
     * The header of the data block at [target], read directly, and the [extra] bytes that follow it (fewer at the end of the file):
     * for a block among many, whose header is all a list of them needs, so that no page of data is read to find it.
     */
    suspend fun dataHeader(target: Long, holder: Long, extra: Int): Pair<BlockHeader, ByteArray> {
        checkLink(target, holder)
        val raw = directBytes(target, BlockFormat.HEADER_SIZE + extra)
        if (raw.size < BlockFormat.HEADER_SIZE) throw MdfException(MdfFailure.Truncated(target, target + BlockFormat.HEADER_SIZE))
        val header = headerFrom(raw, target, holder, emptyArray())
        return header to raw.copyOfRange(BlockFormat.HEADER_SIZE, raw.size)
    }

    private fun checkLink(target: Long, holder: Long) {
        val fileSize = sizeReaching(target + BlockFormat.HEADER_SIZE)
        if (isImpossibleLink(target, fileSize)) damaged(holder, "the block at byte $holder links to byte $target, where no block can be")
        if (target + BlockFormat.HEADER_SIZE > fileSize) {
            throw MdfException(MdfFailure.Truncated(target, target + BlockFormat.HEADER_SIZE))
        }
    }

    private fun headerFrom(raw: ByteArray, target: Long, holder: Long, expected: Array<out String>): BlockHeader {
        val type = typeOf(raw, target)
        if (expected.isNotEmpty() && type !in expected) {
            damaged(target, "the block at byte $holder links to ##${expected.joinToString("/")} but byte $target begins ##$type")
        }
        val length = raw.u64(BlockFormat.LENGTH_AT)
        val linkCount = raw.u64(BlockFormat.LINK_COUNT_AT)
        val linkSpace = (length - BlockFormat.HEADER_SIZE) / BlockFormat.LINK_SIZE
        if (length < BlockFormat.HEADER_SIZE || linkCount < 0 || linkCount > linkSpace) {
            damaged(target, "block ##$type claims a length of $length bytes and $linkCount links")
        }
        if (blockCount == blockOffsets.size) blockOffsets = blockOffsets.copyOf(blockCount * 2)
        blockOffsets[blockCount++] = target
        return BlockHeader(target, type, length, linkCount.toInt())
    }

    // The file's size, read from the source only if [needed] is past the size known (the file may have grown since).
    private fun sizeReaching(needed: Long): Long {
        if (needed > knownSize) knownSize = source.size
        return knownSize
    }

    // A link into the identification block is damage, and so is one past the end of the file where no block could be: a negative or
    // enormous offset, or one that isn't a multiple of 8 as every block's is. Any other link past the end is a cut file's: the
    // block it names is what the file lacks.
    private fun isImpossibleLink(target: Long, fileSize: Long): Boolean = target < BlockFormat.ID_BLOCK_SIZE ||
        (target >= fileSize && (target >= BlockFormat.MAX_PLAUSIBLE_OFFSET || target % BlockFormat.ALIGNMENT != 0L))

    private fun typeOf(header: ByteArray, at: Long): String {
        val marked = header[0] == BLOCK_MARK[0] && header[1] == BLOCK_MARK[1]
        val type = if (marked) header.decodeToString(2, BlockFormat.TYPE_SIZE) else ""
        if (type.length != 2 || !type.all { it.isBlockTypeCharacter() }) damaged(at, "no block begins at byte $at")
        return type
    }

    private fun Char.isBlockTypeCharacter(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private suspend fun copyThroughPages(position: Long, into: ByteArray) {
        var copied = 0
        while (copied < into.size) {
            val at = position + copied
            val pageStart = at - at % PAGE_SIZE
            val inPage = (at - pageStart).toInt()
            val count = minOf(into.size - copied, PAGE_SIZE - inPage)
            val slot = pageHolding(pageStart, inPage + count, position + into.size)
            pages[slot].copyInto(into, copied, inPage, inPage + count)
            copied += count
        }
    }

    // The slot of the page that starts at pageStart and holds at least `needed` bytes, read again where what it holds is short.
    private suspend fun pageHolding(pageStart: Long, needed: Int, requestEnd: Long): Int {
        var slot = pageStarts.indexOf(pageStart)
        if (slot >= 0 && pageValid[slot] >= needed) return slot
        if (slot < 0) {
            slot = nextSlot
            nextSlot = (nextSlot + 1) % PAGE_COUNT
        }
        if (pages[slot].isEmpty()) pages[slot] = ByteArray(PAGE_SIZE)
        val read = readFully(pageStart, pages[slot], PAGE_SIZE)
        pageStarts[slot] = pageStart
        pageValid[slot] = read
        if (read < needed) throw MdfException(MdfFailure.Truncated(pageStart, requestEnd))
        return slot
    }

    /** Reads up to [length] bytes at [position] into [into] at [offset], from the source as they are; fewer only at the end of the file. */
    suspend fun readInto(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        var count = 1
        while (total < length && count > 0) {
            count = source.read(position + total, into, offset + total, length - total)
            if (count > 0) total += count
        }
        return total
    }

    private suspend fun readFully(position: Long, into: ByteArray, length: Int): Int = readInto(position, into, 0, length)
}
