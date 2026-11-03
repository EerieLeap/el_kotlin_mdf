package com.eerie_leap.mdf

// Data list (DL): its first link is the next list, the others are the data blocks; its fields are flags, the count of blocks, and
// the length (or offset) of each, which this reader leaves to the blocks' own lengths.
private const val DL_LINK_NEXT = 0
private const val DL_FIRST_DATA_LINK = 1
private const val DL_COUNT = 4
private const val DL_FIELDS_SIZE = 8

// Header of list (HL): one link, to the first data list; its fields (flags, zip type) are in the blocks the lists name.
private const val HL_LINK_FIRST = 0
private const val HL_FIELDS_SIZE = 8

// Column storage list (LD, MDF 4.2): the next list, the blocks of values, and, if the flags say so, the blocks of invalidation bytes.
private const val LD_LINK_NEXT = 0
private const val LD_FIRST_DATA_LINK = 1
private const val LD_FLAGS = 0
private const val LD_COUNT = 4
private const val LD_FIELDS_SIZE = 8
private const val LD_FLAG_INVALIDATION_BLOCKS = 0x80000000L

private const val MAX_DATA_BLOCKS = 4_000_000
private const val ALIGNMENT = 8L

/** The data blocks a list names, in order, and whether the file ends before the list does (a file still arriving). */
internal class Walk(val leaves: List<DataLeaf>, val isCut: Boolean)

/** The blocks of values and, where the list has them, of invalidation bytes that a column storage list names. */
internal class ColumnWalk(val values: Walk, val invalidation: Walk?)

/**
 * Walks the lists that name a run of data blocks (HL, DL, and MDF 4.2's LD) and gives the blocks in order. A block's length is its
 * own: the lengths and offsets a list repeats are left aside, as a reader of blocks of any length reads them. In an unfinished file
 * (see [UnfinalizedFlag]) the last list of a chain may not name its last blocks yet, and the last block's length is not yet true.
 * One walk is made with one instance.
 */
internal class DataLists(private val file: MdfFile, private val reader: BlockReader) {
    private val leafReader = LeafReader(reader)
    private var isCut = false

    /** The data blocks at [link], named by the block at [holder]: one block, or a list of them (DL), or a header of a list (HL). */
    suspend fun leaves(link: Long, holder: Long, accepts: Set<String>): Walk {
        val found = when (reader.header(link, holder).type) {
            "DL" -> chain(link, holder, accepts)
            "HL" -> headed(link, holder, accepts)
            else -> listOf(leafReader.read(link, holder, accepts, inList = false))
        }
        return Walk(withOpenEnd(found), isCut)
    }

    /** The blocks of the column storage list (LD) at [link] and the lists that follow it. */
    suspend fun columns(link: Long, holder: Long): ColumnWalk {
        val values = ArrayList<DataLeaf>()
        val invalidation = ArrayList<DataLeaf>()
        var hasInvalidation = false
        var next = link
        var from = holder
        val seen = HashSet<Long>()
        while (next != 0L) {
            if (!seen.add(next)) damaged(next, "the chain of column data lists comes back to byte $next")
            val list = blockOrCut(next, from, "LD") ?: break
            list.requireData(LD_FIELDS_SIZE)
            hasInvalidation = addColumns(list, values, invalidation) || hasInvalidation
            if (values.size > MAX_DATA_BLOCKS) throw MdfException(MdfFailure.TooLarge(next, values.size.toLong(), MAX_DATA_BLOCKS.toLong()))
            from = next
            next = list.link(LD_LINK_NEXT)
        }
        return ColumnWalk(Walk(withOpenEnd(values), isCut), if (hasInvalidation) Walk(withOpenEnd(invalidation), isCut) else null)
    }

    private suspend fun addColumns(list: Block, values: MutableList<DataLeaf>, invalidation: MutableList<DataLeaf>): Boolean {
        val count = list.u32AsInt(LD_COUNT)
        val hasInvalidation = (list.u32(LD_FLAGS) and LD_FLAG_INVALIDATION_BLOCKS) != 0L
        val needed = if (hasInvalidation) 2L * count else count.toLong()
        if (count < 0 || LD_FIRST_DATA_LINK + needed > list.linkCount) {
            damaged(list.offset, "a column data list of ${list.linkCount - 1} links says it holds $count blocks")
        }
        for (i in 0 until count) {
            val target = list.link(LD_FIRST_DATA_LINK + i)
            leafOrCut(target, list.offset, DataKinds.VALUES)?.let { values.add(it) }
        }
        if (hasInvalidation) {
            for (i in 0 until count) {
                val target = list.link(LD_FIRST_DATA_LINK + count + i)
                leafOrCut(target, list.offset, DataKinds.INVALIDATION)?.let { invalidation.add(it) }
            }
        }
        return hasInvalidation
    }

    private suspend fun headed(link: Long, holder: Long, accepts: Set<String>): List<DataLeaf> {
        val header = reader.block(link, holder, "HL")
        header.requireData(HL_FIELDS_SIZE)
        val first = header.link(HL_LINK_FIRST)
        return if (first == 0L) emptyList() else chain(first, link, accepts)
    }

    private suspend fun chain(first: Long, holder: Long, accepts: Set<String>): List<DataLeaf> {
        val leaves = ArrayList<DataLeaf>()
        val seen = HashSet<Long>()
        var link = first
        var from = holder
        while (link != 0L) {
            if (!seen.add(link)) damaged(link, "the chain of data lists comes back to byte $link")
            val list = blockOrCut(link, from, "DL") ?: break
            list.requireData(DL_FIELDS_SIZE)
            addList(list, accepts, leaves)
            if (leaves.size > MAX_DATA_BLOCKS) throw MdfException(MdfFailure.TooLarge(link, leaves.size.toLong(), MAX_DATA_BLOCKS.toLong()))
            from = link
            link = list.link(DL_LINK_NEXT)
        }
        return leaves
    }

    // The blocks one list names. The last list of an unfinished file may not have been filled in: its count can't be believed, its
    // links are good up to the first empty one, and the blocks after those in the file are its too.
    private suspend fun addList(list: Block, accepts: Set<String>, into: MutableList<DataLeaf>) {
        val capacity = list.linkCount - DL_FIRST_DATA_LINK
        val isUnfilled = list.link(DL_LINK_NEXT) == 0L && file.isStale(UnfinalizedFlag.LastDataListBlock)
        val count = if (isUnfilled) capacity else countOf(list, capacity)
        val before = into.size
        var index = 0
        var more = true
        while (more && index < count) {
            val target = list.link(DL_FIRST_DATA_LINK + index)
            if (target == 0L &&
                !isUnfilled
            ) {
                damaged(list.offset, "the data list at byte ${list.offset} names no block at its position $index")
            }
            val leaf = if (target == 0L) null else leafOrCut(target, list.offset, accepts)
            if (leaf != null) into.add(leaf)
            more = leaf != null
            index++
        }
        if (isUnfilled && !isCut) addFollowing(list, capacity - (into.size - before), into.drop(before).lastOrNull(), accepts, into)
    }

    private fun countOf(list: Block, capacity: Int): Int {
        val count = list.u32AsInt(DL_COUNT)
        if (count < 0 || count > capacity) damaged(list.offset, "a data list of $capacity links says it holds $count blocks")
        return count
    }

    // The data blocks that follow the blocks [list] names, up to [room] of them: where a writer put the blocks it hadn't linked yet,
    // after the last one it had.
    private suspend fun addFollowing(
        list: Block,
        room: Int,
        last: DataLeaf?,
        accepts: Set<String>,
        into: MutableList<DataLeaf>,
    ) {
        var position = alignUp(if (last == null) list.offset + list.length else endOf(last))
        var left = room
        var more = true
        while (left > 0 && more) {
            val leaf = followingLeaf(position, list.offset, accepts)
            more = leaf != null
            if (leaf != null) {
                into.add(leaf)
                position = alignUp(endOf(leaf))
                left--
            }
        }
    }

    // Where the block of [leaf] ends in the file; as far as a number goes for a block that claims more than that.
    private fun endOf(leaf: DataLeaf): Long {
        val room = Long.MAX_VALUE - leaf.dataStart
        return if (leaf.storedLength > room) Long.MAX_VALUE else leaf.dataStart + leaf.storedLength
    }

    // The data block that begins at [position], null where none does or the file ends before its header does.
    private suspend fun followingLeaf(position: Long, holder: Long, accepts: Set<String>): DataLeaf? {
        if (position > reader.size - BlockFormat.HEADER_SIZE) return null
        val type = reader.directBytes(position, BlockFormat.TYPE_SIZE).decodeToString(2, BlockFormat.TYPE_SIZE)
        return if (type == "DZ" || type in accepts) leafOrCut(position, holder, accepts) else null
    }

    // The block, or null where the file ends inside it or before it: the list ends there, for the file may still be arriving.
    private suspend fun blockOrCut(target: Long, holder: Long, type: String): Block? = try {
        reader.block(target, holder, type)
    } catch (e: MdfException) {
        if (e.failure is MdfFailure.Truncated) null.also { isCut = true } else throw e
    }

    private suspend fun leafOrCut(target: Long, holder: Long, accepts: Set<String>): DataLeaf? = try {
        leafReader.read(target, holder, accepts, inList = true)
    } catch (e: MdfException) {
        if (e.failure is MdfFailure.Truncated) null.also { isCut = true } else throw e
    }

    // The last block's length is not to be believed in an unfinished file that says so: its data reach as far as the next block, or
    // the file. A list cut short has no last block yet.
    private fun withOpenEnd(leaves: List<DataLeaf>): List<DataLeaf> {
        val last = leaves.lastOrNull() ?: return leaves
        if (isCut || last.zip != null || !file.mayHaveStaleLength(last)) return leaves
        val known = reader.readBlockOffsets()
        val next = listOfNotNull(firstBlockAfter(file.blockOffsets, last.offset), firstBlockAfter(known, last.offset)).minOrNull()
        return leaves.dropLast(1) + last.openEnded(next)
    }

    private fun alignUp(position: Long): Long {
        val room = Long.MAX_VALUE - ALIGNMENT
        return if (position > room) Long.MAX_VALUE else (position + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
    }
}

/** The offset of the first block that begins after [offset], null where none does. [blocks] is sorted. */
internal fun firstBlockAfter(blocks: LongArray, offset: Long): Long? {
    var low = 0
    var high = blocks.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (blocks[middle] <= offset) low = middle + 1 else high = middle
    }
    return blocks.getOrNull(low)
}
