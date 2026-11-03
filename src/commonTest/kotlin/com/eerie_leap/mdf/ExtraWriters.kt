package com.eerie_leap.mdf

// More blocks for the tests' writer: lists of data blocks (DL, HL), signal data (SD), column storage (MDF 4.2: LD, DV, DI), arrays (CA),
// the channel hierarchy (CH), events (EV) and attachments (AT). Each is written as the standard lays it out, independent of the reader.

/** A length for a block to claim that no file holds (just under 2^63), for the tests of damaged lengths. */
internal const val HUGE_BLOCK_LENGTH = 0x7FFFFFFFFFFFFFF0L

/** A block of data with the number of bytes it holds once inflated, for a list to name. */
internal class DataPart(val block: TestBlock, val length: Long)

/** Plain data (DT, or the type given: SD, DV, DI). */
internal fun plainPart(layout: BlockLayout, data: ByteArray, type: String = "DT"): DataPart =
    DataPart(layout.block(type, 0, data), data.size.toLong())

/** Zipped data (DZ) that came from a block of [originalType]; stored deflate blocks, or [compress]ed by the caller's own. */
internal fun zippedPart(
    layout: BlockLayout,
    data: ByteArray,
    originalType: String = "DT",
    zipType: Int = ZIP_DEFLATE,
    columns: Int = 0,
    compress: (ByteArray) -> ByteArray = ::zlibStored,
): DataPart {
    val stored = if (zipType == ZIP_TRANSPOSED_DEFLATE) transpose(data, columns) else data
    return DataPart(zippedBlock(layout, data, compress(stored), zipType, columns, originalType), data.size.toLong())
}

/** The [data] cut into pieces of [size] bytes, the last one with what is left. */
internal fun chunks(data: ByteArray, size: Int): List<ByteArray> =
    if (data.isEmpty()) emptyList() else (data.indices step size).map { data.copyOfRange(it, minOf(data.size, it + size)) }

/**
 * A data list (DL) naming [parts], from [first] on in [parts]: each part's length as the list's offsets say it, or, with [equal], the
 * length of the first as the one length (the last may be shorter). [capacity] is the number of data links the block has; links past
 * the parts are empty. [count] is what the block says it holds.
 */
internal fun dataList(
    layout: BlockLayout,
    parts: List<DataPart>,
    next: TestBlock? = null,
    equal: Boolean = true,
    capacity: Int = parts.size,
    count: Int = parts.size,
    startOffset: Long = 0,
): TestBlock {
    val data = ByteWriter().u8(if (equal) 1 else 0).zeros(3).u32(count.toLong())
    if (equal) {
        data.u64(parts.firstOrNull()?.length ?: 0L)
    } else {
        var offset = startOffset
        for (part in parts) {
            data.u64(offset)
            offset += part.length
        }
    }
    val list = layout.block("DL", 1 + capacity, data.toByteArray())
    list.links[0] = next
    parts.forEachIndexed { index, part -> list.links[1 + index] = part.block }
    return list
}

/** The [parts] listed by a chain of data lists of [perList] links each, equal length or not; the first list. */
internal fun dataListChain(layout: BlockLayout, parts: List<DataPart>, perList: Int = parts.size, equal: Boolean = true): TestBlock {
    val groups = parts.chunked(perList.coerceAtLeast(1))
    val sizes = groups.map { group -> group.sumOf { it.length } }
    var next: TestBlock? = null
    for (index in groups.indices.reversed()) {
        next = dataList(layout, groups[index], next, equal, startOffset = sizes.take(index).sum())
    }
    return checkNotNull(next) { "no parts to list" }
}

/** A header of list (HL) over the first list [first] of blocks zipped by [zipType]. */
internal fun headerList(layout: BlockLayout, first: TestBlock, zipType: Int = ZIP_DEFLATE, equal: Boolean = true): TestBlock {
    val header = layout.block("HL", 1, ByteWriter().u16(if (equal) 1 else 0).u8(zipType).zeros(5).toByteArray())
    header.links[0] = first
    return header
}

/** The data of signal data blocks (SD): the [values], each with its length before it, and the offset each begins at. */
internal class SignalData(val bytes: ByteArray, val offsets: List<Long>) {
    companion object {
        fun of(values: List<ByteArray>): SignalData {
            val out = ByteWriter()
            val offsets = ArrayList<Long>()
            for (value in values) {
                offsets.add(out.size.toLong())
                out.u32(value.size.toLong()).bytes(value)
            }
            return SignalData(out.toByteArray(), offsets)
        }
    }
}

/** A list (LD, MDF 4.2) of blocks of values [values] and, if any, of invalidation bytes [invalidation], [samples] records in each. */
internal fun columnList(
    layout: BlockLayout,
    values: List<DataPart>,
    invalidation: List<DataPart>? = null,
    samples: Long = 0,
    next: TestBlock? = null,
    count: Int = values.size,
    equal: Boolean = true,
    /** The bytes of a record's values, to count the records of each block by. */
    recordBytes: Int = 1,
): TestBlock {
    val flags = (if (invalidation != null) INVALIDATION_FLAG else 0L) or (if (equal) EQUAL_FLAG else 0L)
    val data = ByteWriter().u32(flags).u32(count.toLong())
    if (equal) {
        data.u64(samples)
    } else {
        var offset = 0L
        for (part in values) {
            data.u64(offset)
            offset += part.length / recordBytes
        }
    }
    val links = 1 + values.size + (invalidation?.size ?: 0)
    val list = layout.block("LD", links, data.toByteArray())
    list.links[0] = next
    values.forEachIndexed { index, part -> list.links[1 + index] = part.block }
    invalidation?.forEachIndexed { index, part -> list.links[1 + values.size + index] = part.block }
    return list
}

private const val EQUAL_FLAG = 1L
private const val INVALIDATION_FLAG = 0x80000000L

/** An array (CA) as a channel's composition: its [dimensions], and, for plain elements, [byteOffsetBase] bytes between them. */
internal class ArraySpec(
    val dimensions: List<Long>,
    val byteOffsetBase: Int,
    val type: Int = 0,
    val storage: Int = 0,
    val flags: Int = 0,
    val invalidationBitBase: Long = 0,
    val fixedAxes: List<List<Double>> = emptyList(),
    /** What the array holds in place of plain elements: an array again, or a structure's channels. */
    val inner: ArraySpec? = null,
    val structure: List<ChannelSpec> = emptyList(),
) {
    object Flags {
        const val DYNAMIC_SIZE = 0x01
        const val INPUT_QUANTITY = 0x02
        const val OUTPUT_QUANTITY = 0x04
        const val COMPARISON_QUANTITY = 0x08
        const val AXIS = 0x10
        const val FIXED_AXIS = 0x20
    }
}

internal fun writeArray(layout: BlockLayout, array: ArraySpec, groups: Map<GroupSpec, TestBlock>): TestBlock {
    val ndim = array.dimensions.size
    val elements = array.dimensions.fold(1L) { product, size -> product * size }
    val data = ByteWriter().u8(array.type).u8(array.storage).u16(ndim).u32(array.flags.toLong())
        .u32(array.byteOffsetBase.toLong() and 0xFFFFFFFFL).u32(array.invalidationBitBase)
    array.dimensions.forEach { data.u64(it) }
    array.fixedAxes.forEach { axis -> axis.forEach { data.f64(it) } }
    if (array.storage != 0) repeat(elements.toInt()) { data.u64(0) }
    val block = layout.block("CA", arrayLinkCount(array, elements), data.toByteArray())
    val inner = array.inner
    if (inner != null) {
        block.links[0] = writeArray(layout, inner, groups)
    } else if (array.structure.isNotEmpty()) {
        block.links[0] = writeChannels(layout, array.structure, groups)
    }
    return block
}

// The composition, the data links of a template in a data group, then the links the flags add, all empty here.
private fun arrayLinkCount(array: ArraySpec, elements: Long): Int {
    val ndim = array.dimensions.size
    val flags = array.flags
    val hasAxis = (flags and ArraySpec.Flags.AXIS) != 0
    return 1 + (if (array.storage == 2) elements.toInt() else 0) +
        (if ((flags and ArraySpec.Flags.DYNAMIC_SIZE) != 0) 3 * ndim else 0) +
        (if ((flags and ArraySpec.Flags.INPUT_QUANTITY) != 0) 3 * ndim else 0) +
        (if ((flags and ArraySpec.Flags.OUTPUT_QUANTITY) != 0) 3 else 0) +
        (if ((flags and ArraySpec.Flags.COMPARISON_QUANTITY) != 0) 3 else 0) +
        (if (hasAxis) ndim else 0) +
        (if (hasAxis && (flags and ArraySpec.Flags.FIXED_AXIS) == 0) 3 * ndim else 0)
}

/** A node of the channel hierarchy (CH): its [members] are channels of groups, the channel null for the group as a whole. */
internal class HierarchySpec(
    val name: String,
    val type: Int = 0,
    val comment: String? = null,
    val members: List<Pair<GroupSpec, ChannelSpec?>> = emptyList(),
    val children: List<HierarchySpec> = emptyList(),
)

/** The hierarchy blocks of [nodes], siblings chained and children linked; the first. */
internal fun writeHierarchy(build: FileBuild, nodes: List<HierarchySpec>): TestBlock? {
    var first: TestBlock? = null
    var previous: TestBlock? = null
    for (node in nodes) {
        val layout = build.layout
        val block = layout.block(
            "CH",
            CH_FIXED_LINKS + CH_ELEMENT_LINKS * node.members.size,
            ByteWriter().u32(node.members.size.toLong()).u8(node.type).zeros(3).toByteArray(),
        )
        block.links[2] = layout.text(node.name)
        node.comment?.let { block.links[3] = layout.xml("<CHcomment><TX>${escapeXml(it)}</TX></CHcomment>") }
        node.members.forEachIndexed { index, (group, channel) ->
            val at = CH_FIXED_LINKS + CH_ELEMENT_LINKS * index
            block.links[at] = build.dataGroupOf(group)
            block.links[at + 1] = layout.groups.getValue(group)
            block.links[at + 2] = channel?.let { layout.channels.getValue(it) }
        }
        block.links[1] = writeHierarchy(build, node.children)
        if (first == null) first = block else previous?.links?.set(0, block)
        previous = block
    }
    return first
}

private const val CH_FIXED_LINKS = 4
private const val CH_ELEMENT_LINKS = 3

/** An event (EV). [scopes] are channel groups or channels of the file; [parent] and [rangeStart] other events. */
internal class EventSpec(
    val name: String? = null,
    val comment: String? = null,
    val type: Int = 5,
    val syncType: Int = 1,
    val range: Int = 0,
    val cause: Int = 2,
    val flags: Int = 0,
    val syncBase: Long = 1,
    val syncFactor: Double = 0.0,
    val parent: EventSpec? = null,
    val rangeStart: EventSpec? = null,
    val scopes: List<Any> = emptyList(),
    val attachments: List<AttachmentSpec> = emptyList(),
    val groupName: String? = null,
    val creatorIndex: Int = 0,
)

/** The event blocks of [events], chained in order; the first. */
internal fun writeEvents(build: FileBuild, events: List<EventSpec>, attachments: Map<AttachmentSpec, TestBlock>): TestBlock? {
    val layout = build.layout
    val blocks = events.associateWith { event ->
        val links = EV_FIXED_LINKS + event.scopes.size + event.attachments.size + if (event.groupName != null) 1 else 0
        val flags = event.flags or (if (event.groupName != null) EV_FLAG_GROUP_NAME else 0)
        val data = ByteWriter().u8(event.type).u8(event.syncType).u8(event.range).u8(event.cause).u8(flags).zeros(3)
            .u32(event.scopes.size.toLong()).u16(event.attachments.size).u16(event.creatorIndex).u64(event.syncBase).f64(event.syncFactor)
        layout.block("EV", links, data.toByteArray())
    }
    for ((event, block) in blocks) {
        event.name?.let { block.links[3] = layout.text(it) }
        event.comment?.let { block.links[4] = layout.xml("<EVcomment><TX>${escapeXml(it)}</TX></EVcomment>") }
        block.links[1] = event.parent?.let { blocks.getValue(it) }
        block.links[2] = event.rangeStart?.let { blocks.getValue(it) }
        event.scopes.forEachIndexed { index, scope ->
            block.links[EV_FIXED_LINKS + index] = when (scope) {
                is GroupSpec -> layout.groups.getValue(scope)
                is ChannelSpec -> layout.channels.getValue(scope)
                else -> error("a scope is a channel group or a channel")
            }
        }
        event.attachments.forEachIndexed { index, attachment ->
            block.links[EV_FIXED_LINKS + event.scopes.size + index] = attachments.getValue(attachment)
        }
        event.groupName?.let { block.links[block.links.size - 1] = layout.text(it) }
    }
    val ordered = events.map { blocks.getValue(it) }
    ordered.zipWithNext { a, b -> a.links[0] = b }
    return ordered.firstOrNull()
}

private const val EV_FIXED_LINKS = 5
private const val EV_FLAG_GROUP_NAME = 0x02

/** An attachment (AT) of the bytes [data], embedded, deflated (a stored zlib stream) or not; or one that names a file elsewhere. */
internal class AttachmentSpec(
    val fileName: String,
    val data: ByteArray,
    val mimeType: String = "application/octet-stream",
    val comment: String? = null,
    val isEmbedded: Boolean = true,
    val isCompressed: Boolean = false,
    val md5: ByteArray? = ByteArray(MD5_BYTES) { it.toByte() },
    /** The size of the embedded data to write in place of the true one, for a damaged block. */
    val declaredEmbeddedSize: Long? = null,
    val zipType: Int = 0,
    val creatorIndex: Int = 0,
)

/** The attachment blocks of [attachments], chained in order; the first, and each block by its spec. */
internal fun writeAttachments(layout: BlockLayout, attachments: List<AttachmentSpec>): Pair<TestBlock?, Map<AttachmentSpec, TestBlock>> {
    val blocks = attachments.associateWith { attachment ->
        val embedded = if (attachment.isCompressed) zlibStored(attachment.data) else attachment.data
        val flags = (if (attachment.isEmbedded) AT_EMBEDDED else 0) or (if (attachment.isCompressed) AT_COMPRESSED else 0) or
            (if (attachment.md5 != null) AT_MD5_VALID else 0)
        val data = ByteWriter().u16(flags).u16(attachment.creatorIndex).u8(attachment.zipType).u8(0).u16(0)
            .bytes(attachment.md5 ?: ByteArray(MD5_BYTES)).u64(attachment.data.size.toLong())
            .u64(attachment.declaredEmbeddedSize ?: embedded.size.toLong())
        if (attachment.isEmbedded) data.bytes(embedded)
        val block = layout.block("AT", AT_LINKS, data.toByteArray())
        block.links[1] = layout.text(attachment.fileName)
        block.links[2] = layout.text(attachment.mimeType)
        attachment.comment?.let { block.links[3] = layout.xml("<ATcomment><TX>${escapeXml(it)}</TX></ATcomment>") }
        block
    }
    val ordered = attachments.map { blocks.getValue(it) }
    ordered.zipWithNext { a, b -> a.links[0] = b }
    return ordered.firstOrNull() to blocks
}

private const val MD5_BYTES = 16
private const val AT_LINKS = 4
private const val AT_EMBEDDED = 0x01
private const val AT_COMPRESSED = 0x02
private const val AT_MD5_VALID = 0x04
