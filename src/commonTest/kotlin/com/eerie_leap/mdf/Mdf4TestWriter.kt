package com.eerie_leap.mdf

// A writer of MDF 4 files for the tests, independent of the reader: blocks laid out one after another, 8-byte aligned, with
// their links patched in once every block has its address. The firmware's own layout is in FirmwareLogWriter.

internal object DataTypes {
    const val UNSIGNED_LE = 0
    const val UNSIGNED_BE = 1
    const val SIGNED_LE = 2
    const val SIGNED_BE = 3
    const val FLOAT_LE = 4
    const val FLOAT_BE = 5
    const val STRING_LATIN1 = 6
    const val STRING_UTF8 = 7
    const val STRING_UTF16_LE = 8
    const val STRING_UTF16_BE = 9
    const val BYTE_ARRAY = 10
    const val MIME_SAMPLE = 11
}

internal object ChannelTypes {
    const val FIXED = 0
    const val VARIABLE_LENGTH = 1
    const val MASTER = 2
    const val VIRTUAL_MASTER = 3
    const val VIRTUAL_DATA = 6
}

internal object ChannelFlags {
    const val ALL_INVALID = 0x01
    const val INVALIDATION_BIT_VALID = 0x02
    const val PRECISION_VALID = 0x04
    const val RANGE_VALID = 0x08
    const val BUS_EVENT = 0x400
}

internal object GroupFlags {
    const val VARIABLE_LENGTH = 0x01
    const val BUS_EVENT = 0x02
    const val PLAIN_BUS_EVENT = 0x04
}

/** A block to write: its type, links (to blocks that get their address at layout), and the fields after the links. */
internal class TestBlock(val type: String, linkCount: Int, val data: ByteArray) {
    val links = arrayOfNulls<TestBlock>(linkCount)
    var offset = 0L

    /** A length to write in place of the true one, for the DT block of a file its writer didn't finish. */
    var declaredLength: Long? = null

    /** A link count to write in place of the true one, for a damaged block. */
    var declaredLinkCount: Long? = null

    /** Offsets to write in place of the links' addresses, for a link that points outside the file. */
    val rawLinks = HashMap<Int, Long>()

    val length: Long get() = (HEADER_SIZE + LINK_SIZE * links.size + data.size).toLong()

    companion object {
        const val HEADER_SIZE = 24
        const val LINK_SIZE = 8
    }
}

/**
 * Lays blocks out after an identification block. They go in the order [arrange] finds when it was called, else the order they were
 * made in. [arrange] puts them where rt_core's writer does: each block, then the blocks it links to in a fixed order (a block's
 * `next` chain after all that), depth first.
 */
internal class BlockLayout {
    private val blocks = ArrayList<TestBlock>()

    /** Every block made so far, in the order they were made. */
    val all: List<TestBlock> get() = blocks

    private var arranged: List<TestBlock>? = null
    private val texts = HashMap<String, TestBlock>()

    /** The source information blocks made so far: a source is one block, shared by the groups that name it. */
    val sources = HashMap<SourceSpec, TestBlock>()

    fun add(block: TestBlock): TestBlock = block.also { blocks.add(it) }

    fun block(type: String, links: Int, data: ByteArray): TestBlock = add(TestBlock(type, links, data))

    /** A TX block for [text]; the same text is one block, as rt_core's writer shares them. */
    fun text(text: String): TestBlock = texts.getOrPut(text) { block("TX", 0, ByteWriter().paddedText(text).toByteArray()) }

    fun xml(text: String): TestBlock = block("MD", 0, ByteWriter().paddedText(text).toByteArray())

    /** Orders the blocks as rt_core's writer does, from the header block [root]. */
    fun arrange(root: TestBlock) {
        val order = ArrayList<TestBlock>()
        val done = HashSet<TestBlock>()
        writeTree(root, order, done)
        arranged = order + blocks.filter { it !in done }
    }

    // SerializableBlockBase::WriteToStream: the block with what it links to, then the blocks of its chain with theirs.
    private fun writeTree(block: TestBlock, order: MutableList<TestBlock>, done: MutableSet<TestBlock>) {
        writeBlock(block, order, done)
        var next = nextOf(block)
        while (next != null && next !in done) {
            writeBlock(next, order, done)
            next = nextOf(next)
        }
    }

    private fun writeBlock(block: TestBlock, order: MutableList<TestBlock>, done: MutableSet<TestBlock>) {
        order.add(block)
        done.add(block)
        for (index in childOrder(block)) {
            val child = block.links[index]
            if (child != null && child !in done) writeTree(child, order, done)
        }
    }

    private fun nextOf(block: TestBlock): TestBlock? = if (block.type in CHAINED) block.links[0] else null

    // GetChildren() of rt_core's blocks, as indices of the links: comment, names, source, conversion, composition, data.
    private fun childOrder(block: TestBlock): List<Int> = when (block.type) {
        "HD" -> listOf(5, 1, 2, 3, 4, 0)
        "FH" -> listOf(1)
        "DG" -> listOf(3, 1, 2)
        "CG" -> listOf(5, 2, 3, 4, 1)
        "CN" -> listOf(7, 2, 6, 3, 4, 1, 5)
        "SI", "CC" -> block.links.indices.toList()
        else -> emptyList()
    }

    /** The file: [id] (64 bytes), the blocks, then [tail], which is the records after the last block. */
    fun build(id: ByteArray, tail: ByteArray = ByteArray(0)): ByteArray {
        val placed = arranged ?: blocks
        var position = id.size.toLong()
        for (block in placed) {
            block.offset = position
            position += block.length
            position = (position + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
        }
        val out = ByteWriter().bytes(id)
        for (block in placed) {
            out.zeros((block.offset - out.size).toInt())
            out.bytes(BLOCK_MARK.encodeToByteArray())
            out.bytes(block.type.encodeToByteArray()).zeros(U32_BYTES)
            out.u64(block.declaredLength ?: block.length)
            out.u64(block.declaredLinkCount ?: block.links.size.toLong())
            block.links.forEachIndexed { index, target -> out.u64(block.rawLinks[index] ?: target?.offset ?: 0L) }
            out.bytes(block.data)
        }
        return out.bytes(tail).toByteArray()
    }

    private companion object {
        const val ALIGNMENT = 8
        const val U32_BYTES = 4
        const val BLOCK_MARK = "##"
        val CHAINED = setOf("FH", "DG", "CG", "CN")
    }
}

/** [text] for an XML element: the five characters XML reserves are escaped, and the control characters it forbids are replaced. */
internal fun escapeXml(text: String): String {
    val out = StringBuilder()
    for (c in text) {
        when {
            c == '&' -> out.append("&amp;")
            c == '<' -> out.append("&lt;")
            c == '>' -> out.append("&gt;")
            c == '"' -> out.append("&quot;")
            c == '\'' -> out.append("&apos;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> out.append(0xFFFD.toChar())
            else -> out.append(c)
        }
    }
    return out.toString()
}

/** The 64-byte identification block as rt_core writes it. */
internal fun idBlock(
    finalized: Boolean = true,
    flags: Int = 0,
    versionText: String = "4.10",
    version: Int = 410,
    program: String = "EL",
): ByteArray {
    fun text(value: String) = value.padEnd(8, ' ').encodeToByteArray()
    return ByteWriter()
        .bytes(text(if (finalized) "MDF" else "UnFinMF"))
        .bytes(text(versionText))
        .bytes(text(program))
        .zeros(4)
        .u16(version)
        .zeros(30)
        .u16(flags)
        .u16(0)
        .toByteArray()
}

/** Where a channel's or a group's data comes from (SI). */
internal class SourceSpec(val name: String, val path: String? = null, val type: Int = 3, val bus: Int = 0)

/** A conversion to write (CC): its type, values, and references to texts and other conversions. */
internal class ConversionSpec(
    val type: Int,
    val values: List<Double> = emptyList(),
    val references: List<Ref?> = emptyList(),
    val name: String? = null,
    val unit: String? = null,
    val masks: List<Long>? = null,
) {
    sealed interface Ref

    class TextRef(val text: String) : Ref

    class ConversionRef(val spec: ConversionSpec) : Ref

    companion object {
        fun identity() = ConversionSpec(0)

        fun linear(offset: Double, factor: Double) = ConversionSpec(1, listOf(offset, factor))

        fun rational(vararg p: Double) = ConversionSpec(2, p.toList())

        fun algebraic(formula: String) = ConversionSpec(3, references = listOf(TextRef(formula)))

        fun table(interpolating: Boolean, vararg pairs: Pair<Double, Double>) =
            ConversionSpec(if (interpolating) 4 else 5, pairs.flatMap { listOf(it.first, it.second) })

        fun rangeToValue(default: Double, vararg ranges: Triple<Double, Double, Double>) =
            ConversionSpec(6, ranges.flatMap { listOf(it.first, it.second, it.third) } + default)

        fun valueToText(default: Ref?, vararg entries: Pair<Double, Ref>) =
            ConversionSpec(7, entries.map { it.first }, entries.map { it.second } + default)

        fun rangeToText(default: Ref?, vararg entries: Pair<Pair<Double, Double>, Ref>) =
            ConversionSpec(8, entries.flatMap { listOf(it.first.first, it.first.second) }, entries.map { it.second } + default)

        fun textToValue(default: Double, vararg entries: Pair<String, Double>) =
            ConversionSpec(9, entries.map { it.second } + default, entries.map { TextRef(it.first) })

        fun textToText(default: Ref?, vararg entries: Pair<String, Ref>) =
            ConversionSpec(10, references = entries.flatMap { listOf(TextRef(it.first), it.second) } + default)

        fun bitfield(vararg fields: Pair<Long, ConversionSpec>) =
            ConversionSpec(11, references = fields.map { ConversionRef(it.second) }, masks = fields.map { it.first })
    }
}

/** A channel to write (CN). Its [byteOffset] is into the record's data, after the record ID. */
internal class ChannelSpec(
    val name: String,
    val dataType: Int = DataTypes.FLOAT_LE,
    val bitCount: Int = 32,
    val byteOffset: Int = 0,
    val bitOffset: Int = 0,
    val type: Int = ChannelTypes.FIXED,
    val syncType: Int = 0,
    val flags: Int = 0,
    val invalidationBit: Int? = null,
    val unit: String? = null,
    val comment: String? = null,
    val conversion: ConversionSpec? = null,
    val members: List<ChannelSpec> = emptyList(),
    val source: SourceSpec? = null,
    val signalData: GroupSpec? = null,
    val valueRange: ClosedFloatingPointRange<Double>? = null,
)

/** A channel group to write (CG). */
internal class GroupSpec(
    val recordId: Long,
    val name: String?,
    val channels: List<ChannelSpec>,
    val dataBytes: Int,
    val invalidationBytes: Int = 0,
    val flags: Int = 0,
    val cycleCount: Long = 0,
    val comment: String? = null,
    val source: SourceSpec? = null,
    val vlsdBytes: Long = 0,
    val pathSeparator: Int = 0,
)

/** A data group to write (DG) with the bytes of its records. */
internal class DataGroupSpec(
    val recordIdSize: Int,
    val groups: List<GroupSpec>,
    val records: ByteArray,
    /** The length to write in the data block (DT), 24 plus the records unless given: a stale one for an unfinished file. */
    val dataBlockLength: Long? = null,
    /** The data block's type when it isn't DT, and its contents in place of the records (a DZ block, say). */
    val dataBlock: ((BlockLayout) -> TestBlock)? = null,
)

/** A whole file to write. */
internal class FileSpec(
    val dataGroups: List<DataGroupSpec>,
    val finalized: Boolean = true,
    val unfinalizedFlags: Int = 0,
    val versionText: String = "4.10",
    val version: Int = 410,
    val startNs: Long = 1_700_000_000_000_000_000L,
    val headerComment: String? = "<HDcomment><TX>Test log</TX></HDcomment>",
    val toolId: String = "test_writer",
    val toolVendor: String = "Tests",
    val toolVersion: String = "1.2.3",
    val timeFlags: Int = 0,
    val timeClass: Int = 0,
    val timeZoneMinutes: Int = 0,
    val daylightSavingMinutes: Int = 0,
    /** Tools of further file history entries. */
    val laterTools: List<String> = emptyList(),
    /** Called with the blocks before the file is laid out, to damage them. */
    val tamper: ((List<TestBlock>) -> Unit)? = null,
)

private const val HD_LINKS = 6
private const val HD_DATA_GROUP = 0
private const val HD_FILE_HISTORY = 1
private const val HD_COMMENT = 5

/** The bytes of [spec] as a file. */
internal fun writeFile(spec: FileSpec): ByteArray {
    val layout = BlockLayout()
    val hd = layout.block("HD", HD_LINKS, hdData(spec))
    spec.headerComment?.let { hd.links[HD_COMMENT] = layout.xml(it) }
    hd.links[HD_FILE_HISTORY] = fileHistory(layout, spec)

    var previousDg: TestBlock? = null
    val dataBlocks = ArrayList<Pair<TestBlock, DataGroupSpec>>()
    for (dataGroup in spec.dataGroups) {
        val dg = layout.block("DG", 4, ByteWriter().u8(dataGroup.recordIdSize).zeros(7).toByteArray())
        if (previousDg == null) hd.links[HD_DATA_GROUP] = dg else previousDg.links[0] = dg
        previousDg = dg
        writeGroups(layout, dg, dataGroup)
        dataBlocks.add(dg to dataGroup)
    }
    for ((dg, dataGroup) in dataBlocks) {
        val data = dataGroup.dataBlock?.invoke(layout)
            ?: layout.block("DT", 0, dataGroup.records).also { dt -> dt.declaredLength = dataGroup.dataBlockLength }
        dg.links[2] = data
    }
    layout.arrange(hd)
    spec.tamper?.invoke(layout.all)
    return layout.build(idBlock(spec.finalized, spec.unfinalizedFlags, spec.versionText, spec.version))
}

private fun hdData(spec: FileSpec): ByteArray =
    ByteWriter().u64(spec.startNs).u16(spec.timeZoneMinutes).u16(spec.daylightSavingMinutes).u8(spec.timeFlags).u8(spec.timeClass)
        .u8(0).u8(0).f64(0.0).f64(0.0).toByteArray()

private fun fileHistory(layout: BlockLayout, spec: FileSpec): TestBlock {
    fun entry(tool: String, vendor: String, version: String): TestBlock {
        val fh = layout.block("FH", 2, ByteWriter().u64(spec.startNs).u16(0).u16(0).u8(0).zeros(3).toByteArray())
        fh.links[1] = layout.xml(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FHcomment><TX>created</TX><tool_id>${escapeXml(tool)}</tool_id>" +
                "<tool_vendor>${escapeXml(vendor)}</tool_vendor><tool_version>${escapeXml(version)}</tool_version></FHcomment>",
        )
        return fh
    }
    val first = entry(spec.toolId, spec.toolVendor, spec.toolVersion)
    var previous = first
    for (tool in spec.laterTools) {
        val next = entry(tool, spec.toolVendor, spec.toolVersion)
        previous.links[0] = next
        previous = next
    }
    return first
}

private fun writeGroups(layout: BlockLayout, dg: TestBlock, dataGroup: DataGroupSpec) {
    val blocks = HashMap<GroupSpec, TestBlock>()
    var previous: TestBlock? = null
    for (group in dataGroup.groups) {
        val cg = layout.block("CG", 6, groupData(group))
        blocks[group] = cg
        if (previous == null) dg.links[1] = cg else previous.links[0] = cg
        previous = cg
        group.name?.let { cg.links[2] = layout.text(it) }
        group.comment?.let { cg.links[5] = layout.xml("<CGcomment><TX>${escapeXml(it)}</TX></CGcomment>") }
        group.source?.let { cg.links[3] = writeSource(layout, it) }
        cg.links[1] = writeChannels(layout, group.channels, blocks)
    }
}

private fun groupData(group: GroupSpec): ByteArray {
    val variable = (group.flags and GroupFlags.VARIABLE_LENGTH) != 0
    val dataBytes = if (variable) group.vlsdBytes and 0xFFFFFFFFL else group.dataBytes.toLong()
    val invalidationBytes = if (variable) group.vlsdBytes ushr 32 else group.invalidationBytes.toLong()
    return ByteWriter().u64(
        group.recordId,
    ).u64(group.cycleCount).u16(group.flags).u16(group.pathSeparator).zeros(4).u32(dataBytes).u32(invalidationBytes)
        .toByteArray()
}

private fun writeSource(layout: BlockLayout, source: SourceSpec): TestBlock = layout.sources.getOrPut(source) {
    val si = layout.block("SI", 3, ByteWriter().u8(source.type).u8(source.bus).u8(0).zeros(5).toByteArray())
    si.links[0] = layout.text(source.name)
    source.path?.let { si.links[1] = layout.text(it) }
    si
}

private fun writeChannels(layout: BlockLayout, channels: List<ChannelSpec>, groups: Map<GroupSpec, TestBlock>): TestBlock? {
    var first: TestBlock? = null
    var previous: TestBlock? = null
    for (channel in channels) {
        val cn = layout.block("CN", 8, channelData(channel))
        if (first == null) first = cn else previous?.links?.set(0, cn)
        previous = cn
        cn.links[2] = layout.text(channel.name)
        channel.source?.let { cn.links[3] = writeSource(layout, it) }
        channel.conversion?.let { cn.links[4] = writeConversion(layout, it) }
        channel.unit?.let { cn.links[6] = layout.text(it) }
        channel.comment?.let { cn.links[7] = layout.xml("<CNcomment><TX>${escapeXml(it)}</TX></CNcomment>") }
        channel.signalData?.let { target -> cn.links[5] = groups[target] ?: error("the signal data group comes first") }
        if (channel.members.isNotEmpty()) cn.links[1] = writeChannels(layout, channel.members, groups)
    }
    return first
}

private fun channelData(channel: ChannelSpec): ByteArray {
    var flags = channel.flags
    if (channel.invalidationBit != null) flags = flags or ChannelFlags.INVALIDATION_BIT_VALID
    if (channel.valueRange != null) flags = flags or ChannelFlags.RANGE_VALID
    return ByteWriter().u8(channel.type).u8(channel.syncType).u8(channel.dataType).u8(channel.bitOffset)
        .u32(channel.byteOffset.toLong()).u32(channel.bitCount.toLong()).u32(flags.toLong()).u32((channel.invalidationBit ?: 0).toLong())
        .u8(0).u8(0).u16(0)
        .f64(channel.valueRange?.start ?: 0.0).f64(channel.valueRange?.endInclusive ?: 0.0)
        .f64(0.0).f64(0.0).f64(0.0).f64(0.0)
        .toByteArray()
}

/** The CC block of [spec] and the blocks it links to. */
internal fun writeConversion(layout: BlockLayout, spec: ConversionSpec): TestBlock {
    val data = ByteWriter().u8(spec.type).u8(0).u16(0).u16(spec.references.size).u16(spec.masks?.size ?: spec.values.size).f64(0.0).f64(0.0)
    if (spec.masks != null) spec.masks.forEach { data.u64(it) } else spec.values.forEach { data.f64(it) }
    val cc = layout.block("CC", 4 + spec.references.size, data.toByteArray())
    spec.name?.let { cc.links[0] = layout.text(it) }
    spec.unit?.let { cc.links[1] = layout.text(it) }
    spec.references.forEachIndexed { index, reference ->
        cc.links[4 + index] = when (reference) {
            null -> null
            is ConversionSpec.TextRef -> layout.text(reference.text)
            is ConversionSpec.ConversionRef -> writeConversion(layout, reference.spec)
        }
    }
    return cc
}
