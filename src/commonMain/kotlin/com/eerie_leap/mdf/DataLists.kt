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

/** The most data blocks one walk takes: more are refused before they are read. */
internal const val MAX_DATA_BLOCKS = 2_000_000
private const val ALIGNMENT = 8L

/**
 * Where a walk that the end of the file cut short goes on when the file has grown: at the list at [list], which the block at
 * [holder] links to, of which the first [skip] blocks are in the walk already, as are [keep] blocks (or pairs of blocks) in all.
 */
internal class Resume(val list: Long, val holder: Long, val keep: Int, val skip: Int)

/**
 * The data blocks a list names, in order, whether the file ends before the list does (a file still arriving), its size when the walk
 * began, and the offsets of the blocks named so far, which a walk that goes on keeps adding to.
 */
internal class Walk(
    val leaves: List<DataLeaf>,
    val isCut: Boolean,
    val resume: Resume? = null,
    val size: Long = 0L,
    val named: LongSet = LongSet(),
)

/** A block of values of column storage and the block of its invalidation bytes; none for a block whose records are all valid. */
internal class ColumnPair(val values: DataLeaf, val invalidation: DataLeaf?)

/** The blocks of values and invalidation bytes that a column storage list names, a pair for each block of values. */
internal class ColumnWalk(
    val pairs: List<ColumnPair>,
    val isCut: Boolean,
    val resume: Resume?,
    val size: Long,
    val named: LongSet = LongSet(),
)

/**
 * Walks the lists that name a run of data blocks (HL, DL, and MDF 4.2's LD) and gives the blocks in order. A block's length is its
 * own: the lengths and offsets a list repeats are left aside, as a reader of blocks of any length reads them. In an unfinished file
 * (see [UnfinalizedFlag]) the last list of a chain may not name its last blocks yet, and the last block's length is not yet true.
 * One walk is made with one instance, and a block that is named twice in it is damage.
 */
internal class DataLists(private val file: MdfFile, private val reader: BlockReader) {
    private val leafReader = LeafReader(reader)
    private var named = LongSet()
    private val leaves = ArrayList<DataLeaf>()
    private val pairs = ArrayList<ColumnPair>()
    private var isCut = false
    private var resume: Resume? = null
    private var startedAt = 0L
    private var dataBytes = 0
    private var invalidationBytes = 0

    // A list that is not filled in names the blocks after its own as its last, which only a file of one data group can tell.
    private val mayFollow: Boolean by lazy(LazyThreadSafetyMode.NONE) { file.groups.count { it.dataLink != 0L } <= 1 }

    /** The data blocks at [link], named by the block at [holder]: one block, or a list of them (DL), or a header of a list (HL). */
    suspend fun leaves(link: Long, holder: Long, accepts: Set<String>): Walk {
        startedAt = reader.size
        when (reader.header(link, holder).type) {
            "DL" -> chain(link, holder, accepts, 0)
            "HL" -> headed(link, holder, accepts)
            else -> add(leafReader.read(link, holder, accepts, inList = false))
        }
        return walked()
    }

    /** The walk [previous], which the end of the file cut short, gone on from where it was cut. */
    suspend fun resume(previous: Walk, accepts: Set<String>): Walk {
        val point = previous.resume ?: return previous
        startedAt = reader.size
        named = previous.named
        leaves.addAll(previous.leaves.subList(0, point.keep))
        chain(point.list, point.holder, accepts, point.skip)
        return walked()
    }

    /**
     * The last block [leaf] of a walk that reached the end of its lists: as it is, or, where the file is unfinished and its writer
     * didn't update the length of the last block, as a block that reaches to the next block or the end of the file. A length is
     * believed where a block begins just where it says the block ends.
     */
    suspend fun settle(leaf: DataLeaf): DataLeaf {
        val declared = leaf.closed()
        val isStale = declared.zip == null && !declared.isZeros && file.mayHaveStaleLength(declared)
        if (!isStale || reader.blockBeginsAt(alignUp(endOf(declared)))) return declared
        val known = reader.readBlockOffsets()
        val next = listOfNotNull(firstBlockAfter(file.blockOffsets, declared.offset), firstBlockAfter(known, declared.offset)).minOrNull()
        return declared.openEnded(next)
    }

    /** The blocks of the column storage list (LD) at [link] and the lists that follow it, a pair of values and invalidation each. */
    suspend fun columns(link: Long, holder: Long, dataBytes: Int, invalidationBytes: Int): ColumnWalk {
        this.dataBytes = dataBytes
        this.invalidationBytes = invalidationBytes
        startedAt = reader.size
        columnChain(link, holder, 0)
        return walkedColumns()
    }

    /** The column walk [previous], which the end of the file cut short, gone on from where it was cut. */
    suspend fun resumeColumns(previous: ColumnWalk, dataBytes: Int, invalidationBytes: Int): ColumnWalk {
        val point = previous.resume ?: return previous
        this.dataBytes = dataBytes
        this.invalidationBytes = invalidationBytes
        startedAt = reader.size
        named = previous.named
        pairs.addAll(previous.pairs.subList(0, point.keep))
        columnChain(point.list, point.holder, point.skip)
        return walkedColumns()
    }

    private suspend fun walked(): Walk {
        if (!isCut && leaves.isNotEmpty()) leaves[leaves.lastIndex] = settle(leaves.last())
        return Walk(leaves, isCut, resume, startedAt, named)
    }

    private suspend fun walkedColumns(): ColumnWalk {
        val last = pairs.lastOrNull()
        if (!isCut && last != null) pairs[pairs.lastIndex] = ColumnPair(settle(last.values), last.invalidation?.let { settle(it) })
        return ColumnWalk(pairs, isCut, resume, startedAt, named)
    }

    // The walk can go no further for now: the file ends before the list at [list] (or before the block at its position [skip]) does.
    private fun cutAt(list: Long, holder: Long, skip: Int, kept: Int) {
        isCut = true
        resume = Resume(list, holder, kept, skip)
    }

    private fun add(leaf: DataLeaf) {
        if (leaves.size >= MAX_DATA_BLOCKS) throw tooMany(leaf.offset, leaves.size + 1L)
        register(leaf)
        leaves.add(leaf)
    }

    private fun register(leaf: DataLeaf) {
        if (!named.add(leaf.offset)) damaged(leaf.offset, "the data block at byte ${leaf.offset} is named twice")
    }

    private fun tooMany(offset: Long, count: Long) = MdfException(MdfFailure.TooLarge(offset, count, MAX_DATA_BLOCKS.toLong()))

    private suspend fun headed(link: Long, holder: Long, accepts: Set<String>) {
        val header = reader.block(link, holder, "HL")
        header.requireData(HL_FIELDS_SIZE)
        val first = header.link(HL_LINK_FIRST)
        if (first != 0L) chain(first, link, accepts, 0)
    }

    // The lists from [first] on; the first of them from its block at [skip] on, for a walk that goes on.
    private suspend fun chain(first: Long, holder: Long, accepts: Set<String>, skip: Int) {
        val seen = LongSet()
        var link = first
        var from = holder
        var skipped = skip
        while (link != 0L && !isCut) {
            if (!seen.add(link)) damaged(link, "the chain of data lists comes back to byte $link")
            val list = blockOrCut(link, from, "DL")
            if (list == null) {
                cutAt(link, from, skipped, leaves.size)
            } else {
                list.requireData(DL_FIELDS_SIZE)
                addList(list, from, accepts, skipped)
                skipped = 0
                from = link
                link = list.link(DL_LINK_NEXT)
            }
        }
    }

    // The blocks one list names. The last list of an unfinished file may not have been filled in: its count can't be believed, its
    // links are good up to the first empty one, and the blocks after those in the file are its too.
    private suspend fun addList(list: Block, holder: Long, accepts: Set<String>, skip: Int) {
        val capacity = list.linkCount - DL_FIRST_DATA_LINK
        val isUnfilled = isUnfilled(list)
        if (!isUnfilled && leaves.size + countOf(list, capacity).toLong() > MAX_DATA_BLOCKS) {
            throw tooMany(list.offset, leaves.size + countOf(list, capacity).toLong())
        }
        val linked = addLinked(list, holder, accepts, skip)
        if (isUnfilled && !isCut && mayFollow) addFollowing(list, capacity - linked, linked > 0, accepts)
    }

    // Whether [list], the last of its chain, is one its writer didn't get to fill in.
    private fun isUnfilled(list: Block): Boolean = list.link(DL_LINK_NEXT) == 0L && file.isStale(UnfinalizedFlag.LastDataListBlock)

    // The blocks the links of [list] from the one at [skip] name, up to its count, or to its first empty link where it isn't filled
    // in; how many of its links named a block.
    private suspend fun addLinked(list: Block, holder: Long, accepts: Set<String>, skip: Int): Int {
        val isUnfilled = isUnfilled(list)
        val capacity = list.linkCount - DL_FIRST_DATA_LINK
        val count = if (isUnfilled) capacity else countOf(list, capacity)
        var index = skip
        while (index < count && !isCut) {
            val target = list.link(DL_FIRST_DATA_LINK + index)
            if (target == 0L) {
                if (isUnfilled) break
                damaged(list.offset, "the data list at byte ${list.offset} names no block at its position $index")
            }
            val leaf = leafOrCut(target, list.offset, accepts)
            if (leaf == null) {
                cutAt(list.offset, holder, index, leaves.size)
            } else {
                add(leaf)
                index++
            }
        }
        return index
    }

    private fun countOf(list: Block, capacity: Int): Int {
        val count = list.u32AsInt(DL_COUNT)
        if (count < 0 || count > capacity) damaged(list.offset, "a data list of $capacity links says it holds $count blocks")
        return count
    }

    // The data blocks that follow the blocks [list] names, up to [room] of them: where a writer put the blocks it hadn't linked yet,
    // after the last one it had ([hasLast]), or after the list. A block that isn't a block of the data, or can't be read, ends them.
    private suspend fun addFollowing(list: Block, room: Int, hasLast: Boolean, accepts: Set<String>) {
        var position = alignUp(if (hasLast) endOf(leaves.last()) else list.offset + list.length)
        var left = room
        while (left > 0) {
            val leaf = followingLeaf(position, list.offset, accepts) ?: break
            add(leaf)
            position = alignUp(endOf(leaf))
            left--
        }
    }

    // Where the block of [leaf] ends in the file; as far as a number goes for a block that claims more than that.
    private fun endOf(leaf: DataLeaf): Long {
        val room = Long.MAX_VALUE - leaf.dataStart
        return if (leaf.storedLength > room) Long.MAX_VALUE else leaf.dataStart + leaf.storedLength
    }

    // The data block that begins at [position], null where none does, the file ends before its header does, or it is not one of these:
    // whatever follows that can't be read as a block of this data isn't one of the list's, and ends the blocks that are.
    private suspend fun followingLeaf(position: Long, holder: Long, accepts: Set<String>): DataLeaf? {
        if (!reader.blockBeginsAt(position)) return null
        return runCatching { leafReader.read(position, holder, accepts, inList = true) }
            .onFailure { if (it !is MdfException) throw it }
            .getOrNull()
    }

    // The block, or null where the file ends inside it or before it: the list ends there, for the file may still be arriving.
    private suspend fun blockOrCut(target: Long, holder: Long, type: String): Block? = try {
        reader.block(target, holder, type)
    } catch (e: MdfException) {
        if (e.failure is MdfFailure.Truncated) null else throw e
    }

    private suspend fun leafOrCut(target: Long, holder: Long, accepts: Set<String>): DataLeaf? = try {
        leafReader.read(target, holder, accepts, inList = true)
    } catch (e: MdfException) {
        if (e.failure is MdfFailure.Truncated) null else throw e
    }

    // The lists of column storage from [first] on, the first from its block at [skip] on.
    private suspend fun columnChain(first: Long, holder: Long, skip: Int) {
        val seen = LongSet()
        var link = first
        var from = holder
        var skipped = skip
        while (link != 0L && !isCut) {
            if (!seen.add(link)) damaged(link, "the chain of column data lists comes back to byte $link")
            val list = blockOrCut(link, from, "LD")
            if (list == null) {
                cutAt(link, from, skipped, pairs.size)
            } else {
                list.requireData(LD_FIELDS_SIZE)
                addColumns(list, from, skipped)
                skipped = 0
                from = link
                link = list.link(LD_LINK_NEXT)
            }
        }
    }

    // The pairs one list names, from the one at [skip] on. A pair is made when both its blocks are in the file.
    private suspend fun addColumns(list: Block, holder: Long, skip: Int) {
        val count = list.u32AsInt(LD_COUNT)
        val hasInvalidation = (list.u32(LD_FLAGS) and LD_FLAG_INVALIDATION_BLOCKS) != 0L
        val needed = if (hasInvalidation) 2L * count else count.toLong()
        if (count < 0 || LD_FIRST_DATA_LINK + needed > list.linkCount) {
            damaged(list.offset, "a column data list of ${list.linkCount - 1} links says it holds $count blocks")
        }
        if (pairs.size + count.toLong() > MAX_DATA_BLOCKS) throw tooMany(list.offset, pairs.size + count.toLong())
        var index = skip
        while (index < count && !isCut) {
            val pair = pairAt(list, index, if (hasInvalidation) count else 0)
            if (pair == null) {
                cutAt(list.offset, holder, index, pairs.size)
            } else {
                addPair(pair)
                index++
            }
        }
    }

    // The block of values [list] names at [index] and, where the list has invalidation blocks ([flagsAt] is where they begin, 0 if it
    // has none) and the link isn't empty, the block of the same position among them: null where the file ends before one of them.
    private suspend fun pairAt(list: Block, index: Int, flagsAt: Int): ColumnPair? {
        val target = list.link(LD_FIRST_DATA_LINK + index)
        if (target == 0L) damaged(list.offset, "the column data list at byte ${list.offset} names no values block at its position $index")
        val values = leafOrCut(target, list.offset, DataKinds.VALUES)
        val flagsTarget = if (flagsAt > 0) list.link(LD_FIRST_DATA_LINK + flagsAt + index) else 0L
        val invalidation = if (values == null || flagsTarget == 0L) null else leafOrCut(flagsTarget, list.offset, DataKinds.INVALIDATION)
        return if (values == null || (flagsTarget != 0L && invalidation == null)) null else ColumnPair(values, invalidation)
    }

    private fun addPair(pair: ColumnPair) {
        if (pairs.size >= MAX_DATA_BLOCKS) throw tooMany(pair.values.offset, pairs.size + 1L)
        register(pair.values)
        pair.invalidation?.let { register(it) }
        // The pair before this one has others after it, and its blocks' lengths are true: it is checked now. The last pair of a file
        // may be cut or its blocks' lengths stale, and the records both of its blocks hold are what is read of it.
        pairs.lastOrNull()?.let { checkPair(it) }
        pairs.add(pair)
    }

    // The blocks of a pair hold the same number of records, or the invalidation bytes of the pairs after it would be those of other records.
    private fun checkPair(pair: ColumnPair) {
        val invalidation = pair.invalidation ?: return
        if (dataBytes == 0 || invalidationBytes == 0) return
        val valueRecords = pair.values.length / dataBytes
        val flagRecords = invalidation.length / invalidationBytes
        if (valueRecords != flagRecords) {
            damaged(
                invalidation.offset,
                "the invalidation block at byte ${invalidation.offset} holds $flagRecords records but the values block at byte " +
                    "${pair.values.offset} holds $valueRecords",
            )
        }
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
