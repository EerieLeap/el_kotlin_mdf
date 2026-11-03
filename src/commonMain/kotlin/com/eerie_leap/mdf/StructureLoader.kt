package com.eerie_leap.mdf

// Data group (DG).
private const val DG_LINK_NEXT = 0
private const val DG_LINK_CHANNEL_GROUP = 1
private const val DG_LINK_DATA = 2
private const val DG_LINK_COMMENT = 3
private const val DG_RECORD_ID_SIZE = 0
private const val DG_FIELDS_SIZE = 1
private val RECORD_ID_SIZES = setOf(0, 1, 2, 4, 8)

// Channel group (CG).
private const val CG_LINK_NEXT = 0
private const val CG_LINK_CHANNEL = 1
private const val CG_LINK_NAME = 2
private const val CG_LINK_SOURCE = 3
private const val CG_LINK_SAMPLE_REDUCTION = 4
private const val CG_LINK_COMMENT = 5
private const val CG_LINK_MASTER = 6
private const val CG_RECORD_ID = 0
private const val CG_CYCLE_COUNT = 8
private const val CG_FLAGS = 16
private const val CG_DATA_BYTES = 24
private const val CG_INVALIDATION_BYTES = 28
private const val CG_FIELDS_SIZE = 32

// Channel (CN).
private const val CN_LINK_NEXT = 0
private const val CN_LINK_COMPOSITION = 1
private const val CN_LINK_NAME = 2
private const val CN_LINK_SOURCE = 3
private const val CN_LINK_CONVERSION = 4
private const val CN_LINK_DATA = 5
private const val CN_LINK_UNIT = 6
private const val CN_LINK_COMMENT = 7
private const val CN_TYPE = 0
private const val CN_SYNC_TYPE = 1
private const val CN_DATA_TYPE = 2
private const val CN_BIT_OFFSET = 3
private const val CN_BYTE_OFFSET = 4
private const val CN_BIT_COUNT = 8
private const val CN_FLAGS = 12
private const val CN_INVALIDATION_BIT = 16
private const val CN_PRECISION = 20
private const val CN_RANGE_MIN = 24
private const val CN_RANGE_MAX = 32
private const val CN_FIELDS_SIZE = 72

private const val MAX_COMPOSITION_DEPTH = 8

/** Reads the data groups of a file, with their channel groups and channels (DG, CG, CN), and what they link to. */
internal class StructureLoader(private val context: LoaderContext, private val conversions: ConversionLoader) {
    private val arrays = ArrayLoader(context)

    /** The data groups of the chain that begins at [first]; [holder] is the block that links to it. */
    suspend fun dataGroups(first: Long, holder: Long): List<MdfDataGroup> =
        context.chain(first, holder, "DG", "the data groups") { block, index -> dataGroup(block, index) }

    private suspend fun dataGroup(block: Block, index: Int): Pair<MdfDataGroup, Long> {
        block.requireData(DG_FIELDS_SIZE)
        val recordIdSize = block.u8(DG_RECORD_ID_SIZE)
        if (recordIdSize !in RECORD_ID_SIZES) damaged(block.offset, "a data group has records IDs of $recordIdSize bytes")
        val subject = "data group $index"
        val groups = context.chain(block.link(DG_LINK_CHANNEL_GROUP), block.offset, "CG", subject) { cg, _ -> channelGroup(cg) }
        resolveSignalData(groups)
        val comment = context.comment(block.link(DG_LINK_COMMENT), block.offset, subject)
        return MdfDataGroup(block.offset, recordIdSize, comment, groups, block.link(DG_LINK_DATA)) to block.link(DG_LINK_NEXT)
    }

    private suspend fun channelGroup(block: Block): Pair<MdfChannelGroup, Long> {
        block.requireData(CG_FIELDS_SIZE)
        val flags = block.u16(CG_FLAGS)
        val dataBytes = block.u32(CG_DATA_BYTES)
        val invalidationBytes = block.u32(CG_INVALIDATION_BYTES)
        val isVlsd = (flags and MdfChannelGroup.FLAG_VLSD) != 0
        val name = context.text(block.link(CG_LINK_NAME), block.offset, "a channel group")
        val subject = "channel group ${name.ifEmpty { "at byte ${block.offset}" }}"
        val channels = channels(block.link(CG_LINK_CHANNEL), block.offset, subject, 0)
        val hasRemoteMaster = (flags and MdfChannelGroup.FLAG_REMOTE_MASTER) != 0
        val group = MdfChannelGroup(
            offset = block.offset,
            recordId = block.u64(CG_RECORD_ID),
            name = name,
            comment = context.comment(block.link(CG_LINK_COMMENT), block.offset, subject),
            source = context.source(block.link(CG_LINK_SOURCE), block.offset, subject),
            cycleCount = block.u64(CG_CYCLE_COUNT),
            flags = flags,
            // A group of variable-length values has no records of fixed size: its two size fields hold one 64-bit byte count.
            dataBytes = if (isVlsd) 0 else dataBytes,
            invalidationBytes = if (isVlsd) 0 else invalidationBytes,
            vlsdBytes = if (isVlsd) dataBytes or (invalidationBytes shl Int.SIZE_BITS) else 0,
            channels = channels,
            remoteMasterLink = if (hasRemoteMaster) block.link(CG_LINK_MASTER) else 0L,
            hasSampleReductions = block.link(CG_LINK_SAMPLE_REDUCTION) != 0L,
        )
        return group to block.link(CG_LINK_NEXT)
    }

    private suspend fun channels(first: Long, holder: Long, subject: String, depth: Int): List<MdfChannel> {
        if (depth > MAX_COMPOSITION_DEPTH) damaged(holder, "channels nest more than $MAX_COMPOSITION_DEPTH levels deep in $subject")
        return context.chain(first, holder, "CN", subject) { block, _ -> channel(block, subject, depth) }
    }

    private suspend fun channel(block: Block, groupSubject: String, depth: Int): Pair<MdfChannel, Long> {
        block.requireData(CN_FIELDS_SIZE)
        val name = context.text(block.link(CN_LINK_NAME), block.offset, groupSubject)
        val subject = "channel ${name.ifEmpty { "at byte ${block.offset}" }}"
        val kind = MdfChannelKind.of(block.u8(CN_TYPE))
        val flags = block.u32AsInt(CN_FLAGS)
        val conversion = conversions.load(block.link(CN_LINK_CONVERSION), block.offset, subject)
        val composition = composition(block, subject, depth)
        val plain = MdfChannel(
            offset = block.offset,
            name = name,
            unit = context.text(block.link(CN_LINK_UNIT), block.offset, subject).ifEmpty { conversion?.info?.unit.orEmpty() },
            comment = context.comment(block.link(CN_LINK_COMMENT), block.offset, subject),
            kind = kind,
            syncType = MdfSyncType.of(block.u8(CN_SYNC_TYPE)),
            dataType = MdfDataType.of(block.u8(CN_DATA_TYPE)),
            byteOffset = nonNegative(block, CN_BYTE_OFFSET, "byte offset"),
            bitOffset = block.u8(CN_BIT_OFFSET),
            bitCount = nonNegative(block, CN_BIT_COUNT, "bit count"),
            flags = flags,
            invalidationBit = if ((flags and MdfChannel.FLAG_INVALIDATION_BIT_VALID) != 0) invalidationBit(block) else null,
            conversion = conversion,
            source = context.source(block.link(CN_LINK_SOURCE), block.offset, subject),
            members = composition.members,
            valueRange = valueRange(block, flags),
            precision = if ((flags and MdfChannel.FLAG_PRECISION_VALID) != 0) block.u8(CN_PRECISION) else null,
            compositionType = composition.type,
            signalDataLink = signalDataLink(block, kind),
        )
        val channel = when {
            composition.arrayLink != 0L -> arrays.load(composition.arrayLink, block.offset, plain, subject)
            composition.isUnreadable -> plain.withArray(MdfChannelArray.unreadable("its composition can't be read"), emptyList())
            else -> plain
        }
        return channel to block.link(CN_LINK_NEXT)
    }

    // The position of the invalidation bit among the bits after the record's data: a 32-bit number a channel's record can reach.
    private fun invalidationBit(block: Block): Int {
        val bit = block.u32(CN_INVALIDATION_BIT)
        if (bit > Int.MAX_VALUE) damaged(block.offset, "a channel has an invalidation bit at position $bit")
        return bit.toInt()
    }

    private fun nonNegative(block: Block, field: Int, what: String): Int {
        val value = block.u32AsInt(field)
        if (value < 0) damaged(block.offset, "a channel has a $what of ${block.u32(field)}")
        return value
    }

    private fun valueRange(block: Block, flags: Int): ClosedFloatingPointRange<Double>? {
        if ((flags and MdfChannel.FLAG_RANGE_VALID) == 0) return null
        val min = block.f64(CN_RANGE_MIN)
        val max = block.f64(CN_RANGE_MAX)
        return if (min <= max) min..max else null
    }

    // A variable-length channel's values are in a group of its own or in signal data blocks; the link is checked to be one of them.
    private suspend fun signalDataLink(block: Block, kind: MdfChannelKind): Long {
        val link = block.link(CN_LINK_DATA)
        if (link == 0L || kind != MdfChannelKind.Vlsd) return 0L
        return context.tolerate("a variable-length channel", 0L) {
            context.reader.header(link, block.offset, "CG", "SD", "DL", "DZ", "HL")
            link
        }
    }

    private class Composition(val type: String?, val members: List<MdfChannel>, val arrayLink: Long = 0L, val isUnreadable: Boolean = false)

    // The channels of a structure, or the link to the array (CA) the channel is: a channel with a composition that can't be read is
    // not a plain channel, and isn't read as one.
    private suspend fun composition(block: Block, subject: String, depth: Int): Composition {
        val link = block.link(CN_LINK_COMPOSITION)
        if (link == 0L) return Composition(null, emptyList())
        return context.tolerate(subject, Composition(null, emptyList(), isUnreadable = true)) {
            val header = context.reader.header(link, block.offset, "CN", "CA")
            if (header.type == "CN") {
                Composition("CN", channels(link, block.offset, subject, depth + 1))
            } else {
                Composition("CA", emptyList(), arrayLink = link)
            }
        }
    }

    // Matches each variable-length channel's link to the group of variable-length values it names, among its data group's groups.
    private fun resolveSignalData(groups: List<MdfChannelGroup>) {
        val byOffset = groups.filter { it.isVlsd }.associateBy { it.offset }
        if (byOffset.isEmpty()) return
        fun visit(channel: MdfChannel) {
            if (channel.signalDataLink != 0L) channel.signalDataGroup = byOffset[channel.signalDataLink]
            channel.members.forEach(::visit)
        }
        groups.forEach { group -> group.channels.forEach(::visit) }
    }
}
