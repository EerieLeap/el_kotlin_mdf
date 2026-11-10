package com.eerie_leap.mdf.writer

// The blocks that come before the records of a CAN bus log, in the layout rt_core's logger writes (one unsorted data group of
// 1-byte record IDs: the payloads, the data frames, the remote frames) and the ASAM bus logging standard describes.

internal const val ID_BLOCK_BYTES = 64
internal const val RECORD_ID_PAYLOAD = 1
internal const val RECORD_ID_DATA_FRAME = 2
internal const val RECORD_ID_REMOTE_FRAME = 3

/** The bytes of a record's time, a float64 of seconds. */
internal const val TIME_BYTES = 8

/** The bytes of a data frame's fields after the time: bus, ID with IDE, flags, data length and the offset of the payload. */
internal const val DATA_FRAME_BYTES = 15

/** The bytes of a remote frame's fields after the time: bus, ID with IDE, flags and the length asked for. */
internal const val REMOTE_FRAME_BYTES = 7

/** The bytes of a payload's length in the group of payloads. */
internal const val PAYLOAD_LENGTH_BYTES = 4

private const val VERSION_TEXT = "4.10"
private const val VERSION = 410
private const val FINISHED_TEXT = "MDF"
private const val UNFINISHED_TEXT = "UnFinMF"
private const val ID_TEXT_BYTES = 8
private const val ID_PADDING = 4
private const val ID_RESERVED = 30

/** The cycle counts, the length of the last data block and the byte counts of the payloads are stale. */
internal const val UNFINALIZED_FLAGS = 0x01 or 0x04 or 0x20

private const val HD_LINKS = 6
private const val HD_LINK_DATA_GROUP = 0
private const val HD_LINK_FILE_HISTORY = 1
internal const val HD_LINK_EVENTS = 4
private const val HD_LINK_COMMENT = 5
private const val FH_LINKS = 2
private const val DG_LINKS = 4
private const val CG_LINKS = 6
private const val CN_LINKS = 8
private const val SI_LINKS = 3
private const val LINK_NEXT = 0
private const val FH_LINK_COMMENT = 1
private const val DG_LINK_GROUPS = 1
private const val DG_LINK_DATA = 2
private const val CG_LINK_CHANNELS = 1
private const val CG_LINK_NAME = 2
private const val CG_LINK_SOURCE = 3
private const val CN_LINK_COMPOSITION = 1
private const val CN_LINK_NAME = 2
private const val CN_LINK_DATA = 5
private const val CN_LINK_UNIT = 6
private const val SI_LINK_NAME = 0
private const val SI_LINK_PATH = 1

private const val CG_FLAG_VLSD = 0x01
private const val CG_FLAG_BUS_EVENT = 0x02
private const val CG_FLAG_PLAIN_BUS_EVENT = 0x04
private const val CN_FLAG_BUS_EVENT = 0x400
private const val CN_TYPE_VLSD = 1
private const val CN_TYPE_MASTER = 2
private const val CN_SYNC_TIME = 1
private const val DATA_TYPE_UNSIGNED = 0
private const val DATA_TYPE_FLOAT = 4
private const val DATA_TYPE_BYTES = 10
private const val SOURCE_TYPE_BUS = 2
private const val BUS_TYPE_CAN = 2
private const val PATH_SEPARATOR = '.'.code
private const val BITS_8 = 8
private const val BITS_64 = 64
private const val CAN_ID_BITS = 29
private const val IDE_BYTE = 4
private const val IDE_BIT = 7
private const val DLC_BITS = 4
private const val FLAGS_AT = 5
private const val BIT_DIR = 4
private const val BIT_EDL = 5
private const val BIT_BRS = 6
private const val BIT_ESI = 7
private const val DATA_LENGTH_AT = 6
private const val DATA_BYTES_AT = 7
private const val CN_FIELDS_PADDING = 48
private const val SI_RESERVED = 5
private const val DG_RESERVED = 7
private const val FH_RESERVED = 3
private const val HD_FIELDS_PADDING = 16
private const val CG_RESERVED = 4

/** Where in the file the head's blocks that a writer patches are. */
internal class BusLogHead(
    val bytes: ByteArray,
    /** Where the data block (DT) begins: the last of [bytes], whose records follow. */
    val dataBlockOffset: Long,
    /** The place in the header block (HD) of the link to the first event. */
    val eventsLink: Long,
    val payloads: GroupPlace,
    val dataFrames: GroupPlace,
    val remoteFrames: GroupPlace,
)

/** Where the cycle count and the data byte count of a channel group are written. */
internal class GroupPlace(val cycleCount: Long, val dataBytes: Long)

/** The identification block (ID) of a file the writer is still writing, or of one it finished. */
internal fun identificationBlock(programId: String, isFinished: Boolean): ByteArray {
    fun text(value: String) = value.padEnd(ID_TEXT_BYTES, ' ').take(ID_TEXT_BYTES).encodeToByteArray()
    return LittleEndianBuffer(ID_BLOCK_BYTES)
        .bytes(text(if (isFinished) FINISHED_TEXT else UNFINISHED_TEXT))
        .bytes(text(VERSION_TEXT))
        .bytes(text(programId))
        .zeros(ID_PADDING)
        .u16(VERSION)
        .zeros(ID_RESERVED)
        .u16(if (isFinished) 0 else UNFINALIZED_FLAGS)
        .u16(0)
        .toByteArray()
}

/** Makes the blocks of the head for [options]: the header, the file history, the three channel groups with their channels. */
internal fun busLogHead(options: Mdf4BusLogOptions): BusLogHead {
    val set = OutBlockSet(ID_BLOCK_BYTES.toLong())
    val hd = set.block("HD", HD_LINKS, headerFields(options))
    hd.links[HD_LINK_COMMENT] = set.xml(headerComment(options))
    val fh = set.block("FH", FH_LINKS, fileHistoryFields(options))
    fh.links[FH_LINK_COMMENT] = set.xml(fileHistoryComment(options))
    hd.links[HD_LINK_FILE_HISTORY] = fh

    val dg = set.block("DG", DG_LINKS, LittleEndianBuffer().u8(1).zeros(DG_RESERVED).toByteArray())
    hd.links[HD_LINK_DATA_GROUP] = dg
    val source = busSource(set)

    val payloads = set.block("CG", CG_LINKS, groupFields(RECORD_ID_PAYLOAD, CG_FLAG_VLSD, 0))
    val dataFrames = set.block(
        "CG",
        CG_LINKS,
        groupFields(RECORD_ID_DATA_FRAME, CG_FLAG_BUS_EVENT or CG_FLAG_PLAIN_BUS_EVENT, TIME_BYTES + DATA_FRAME_BYTES),
    )
    val remoteFrames = set.block(
        "CG",
        CG_LINKS,
        groupFields(RECORD_ID_REMOTE_FRAME, CG_FLAG_BUS_EVENT or CG_FLAG_PLAIN_BUS_EVENT, TIME_BYTES + REMOTE_FRAME_BYTES),
    )
    dg.links[DG_LINK_GROUPS] = payloads
    payloads.links[LINK_NEXT] = dataFrames
    dataFrames.links[LINK_NEXT] = remoteFrames
    dataFrames.links[CG_LINK_NAME] = set.text("CAN_DataFrame")
    dataFrames.links[CG_LINK_SOURCE] = source
    dataFrames.links[CG_LINK_CHANNELS] = dataFrameChannels(set, payloads)
    remoteFrames.links[CG_LINK_NAME] = set.text("CAN_RemoteFrame")
    remoteFrames.links[CG_LINK_SOURCE] = source
    remoteFrames.links[CG_LINK_CHANNELS] = remoteFrameChannels(set)

    // The data block, with the header of a block alone: its length is that of the header until the records are counted.
    val dataBlock = set.block("DT", 0)
    dg.links[DG_LINK_DATA] = dataBlock
    val body = set.layOut()
    return BusLogHead(
        bytes = LittleEndianBuffer(
            ID_BLOCK_BYTES + body.size,
        ).bytes(identificationBlock(options.programId, false)).bytes(body).toByteArray(),
        dataBlockOffset = dataBlock.offset,
        eventsLink = hd.linkOffset(HD_LINK_EVENTS),
        payloads = placeOf(payloads),
        dataFrames = placeOf(dataFrames),
        remoteFrames = placeOf(remoteFrames),
    )
}

private fun placeOf(group: OutBlock) = GroupPlace(
    cycleCount = group.fieldsOffset + Long.SIZE_BYTES,
    dataBytes =
    group.fieldsOffset + GROUP_DATA_BYTES_AT,
)

// The group's fields: the record ID, the cycle count, the flags, the separator, 4 reserved, then the data and invalidation bytes.
private const val GROUP_DATA_BYTES_AT = 24L

private fun groupFields(recordId: Int, flags: Int, dataBytes: Int): ByteArray {
    val separator = if (flags and CG_FLAG_BUS_EVENT != 0) PATH_SEPARATOR else 0
    return LittleEndianBuffer().u64(recordId.toLong()).u64(0).u16(flags).u16(separator).zeros(CG_RESERVED).u32(dataBytes.toLong()).u32(0)
        .toByteArray()
}

private fun headerFields(options: Mdf4BusLogOptions): ByteArray =
    LittleEndianBuffer().u64(options.startNs).u16(0).u16(0).u8(0).u8(options.timeClass).u8(0).u8(0).zeros(HD_FIELDS_PADDING).toByteArray()

private fun fileHistoryFields(options: Mdf4BusLogOptions): ByteArray =
    LittleEndianBuffer().u64(options.startNs).u16(0).u16(0).u8(0).zeros(FH_RESERVED).toByteArray()

private fun headerComment(options: Mdf4BusLogOptions): String {
    val entries = options.properties.joinToString("") { (name, value) -> "<e name=\"${escapeXmlText(name)}\">${escapeXmlText(value)}</e>" }
    val properties = if (entries.isEmpty()) "" else "<common_properties>$entries</common_properties>"
    return "<HDcomment><TX>${escapeXmlText(options.description)}</TX>$properties</HDcomment>"
}

private fun fileHistoryComment(options: Mdf4BusLogOptions): String =
    "<FHcomment><TX>created</TX><tool_id>${escapeXmlText(options.toolId)}</tool_id>" +
        "<tool_vendor>${escapeXmlText(
            options.toolVendor,
        )}</tool_vendor><tool_version>${escapeXmlText(options.toolVersion)}</tool_version>" +
        "</FHcomment>"

private fun busSource(set: OutBlockSet): OutBlock {
    val si = set.block("SI", SI_LINKS, LittleEndianBuffer().u8(SOURCE_TYPE_BUS).u8(BUS_TYPE_CAN).u8(0).zeros(SI_RESERVED).toByteArray())
    si.links[SI_LINK_NAME] = set.text("CAN")
    si.links[SI_LINK_PATH] = set.text("CAN")
    return si
}

/** A channel's place in the record: [byteOffset] from the record's data, after the record ID. */
private class Member(val name: String, val byteOffset: Int, val bitOffset: Int, val bits: Int)

private fun frameMember(name: String, offset: Int, bitOffset: Int, bits: Int) = Member(name, TIME_BYTES + offset, bitOffset, bits)

private val COMMON_MEMBERS = listOf(
    frameMember("BusChannel", 0, 0, BITS_8),
    frameMember("ID", 1, 0, CAN_ID_BITS),
    frameMember("IDE", IDE_BYTE, IDE_BIT, 1),
    frameMember("DLC", FLAGS_AT, 0, DLC_BITS),
    frameMember("Dir", FLAGS_AT, BIT_DIR, 1),
)

private val DATA_FRAME_MEMBERS = COMMON_MEMBERS + listOf(
    frameMember("EDL", FLAGS_AT, BIT_EDL, 1),
    frameMember("BRS", FLAGS_AT, BIT_BRS, 1),
    frameMember("ESI", FLAGS_AT, BIT_ESI, 1),
    frameMember("DataLength", DATA_LENGTH_AT, 0, BITS_8),
)

private val REMOTE_FRAME_MEMBERS = COMMON_MEMBERS + frameMember("DataLength", DATA_LENGTH_AT, 0, BITS_8)

private fun channel(
    set: OutBlockSet,
    place: Member,
    type: Int,
    dataType: Int,
    flags: Int,
    syncType: Int = 0,
): OutBlock {
    val data = LittleEndianBuffer().u8(type).u8(syncType).u8(dataType).u8(place.bitOffset).u32(place.byteOffset.toLong())
        .u32(place.bits.toLong()).u32(flags.toLong()).u32(0).u8(0).u8(0).u16(0).zeros(CN_FIELDS_PADDING).toByteArray()
    return set.block("CN", CN_LINKS, data).also { it.links[CN_LINK_NAME] = set.text(place.name) }
}

private fun timestamp(set: OutBlockSet): OutBlock =
    channel(set, Member("Timestamp", 0, 0, BITS_64), CN_TYPE_MASTER, DATA_TYPE_FLOAT, 0, CN_SYNC_TIME).also {
        it.links[CN_LINK_UNIT] = set.text("s")
    }

private fun members(set: OutBlockSet, frame: String, list: List<Member>): List<OutBlock> =
    list.map { channel(set, Member("$frame.${it.name}", it.byteOffset, it.bitOffset, it.bits), 0, DATA_TYPE_UNSIGNED, CN_FLAG_BUS_EVENT) }

private fun chain(blocks: List<OutBlock>): OutBlock? {
    for (i in 0 until blocks.size - 1) blocks[i].links[LINK_NEXT] = blocks[i + 1]
    return blocks.firstOrNull()
}

private fun dataFrameChannels(set: OutBlockSet, payloads: OutBlock): OutBlock? {
    val timestamp = timestamp(set)
    val frame =
        channel(set, Member("CAN_DataFrame", TIME_BYTES, 0, DATA_FRAME_BYTES * Byte.SIZE_BITS), 0, DATA_TYPE_BYTES, CN_FLAG_BUS_EVENT)
    val bytes = channel(
        set,
        Member("CAN_DataFrame.DataBytes", TIME_BYTES + DATA_BYTES_AT, 0, BITS_64),
        CN_TYPE_VLSD,
        DATA_TYPE_BYTES,
        CN_FLAG_BUS_EVENT,
    )
    bytes.links[CN_LINK_DATA] = payloads
    frame.links[CN_LINK_COMPOSITION] = chain(members(set, "CAN_DataFrame", DATA_FRAME_MEMBERS) + bytes)
    timestamp.links[LINK_NEXT] = frame
    return timestamp
}

private fun remoteFrameChannels(set: OutBlockSet): OutBlock? {
    val timestamp = timestamp(set)
    val frame =
        channel(set, Member("CAN_RemoteFrame", TIME_BYTES, 0, REMOTE_FRAME_BYTES * Byte.SIZE_BITS), 0, DATA_TYPE_BYTES, CN_FLAG_BUS_EVENT)
    frame.links[CN_LINK_COMPOSITION] = chain(members(set, "CAN_RemoteFrame", REMOTE_FRAME_MEMBERS))
    timestamp.links[LINK_NEXT] = frame
    return timestamp
}
