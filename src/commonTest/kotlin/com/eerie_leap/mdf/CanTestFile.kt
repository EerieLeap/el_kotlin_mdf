package com.eerie_leap.mdf

/**
 * A file of CAN frames in the bus logging layout (`CAN_DataFrame` records and a group of variable-length payloads, one unsorted data
 * group), for the tests that need the frames and their payloads in an order or with offsets the firmware's logger doesn't write.
 */
internal object CanTestFile {
    private const val FRAME_BYTES = 15
    private const val TIME_BYTES = 8
    private const val PAYLOAD_BYTES = 8

    /** The ID of frame [index]. */
    fun idOf(index: Int): Long = 0x100L + index

    /** The payload of frame [index]. */
    fun payloadOf(index: Int): ByteArray = ByteArray(PAYLOAD_BYTES) { (index + it).toByte() }

    /**
     * [frames] frames, each with the offset [offsetOf] says it names, and their payloads, which come before the frames
     * ([payloadsFirst]) or after all of them. The file is as the writer left it when [finalized] is false, with [flags]; the data
     * block's length is then a header's alone.
     */
    fun build(
        frames: Int,
        payloadsFirst: Boolean,
        offsetOf: (Int) -> Long = { it * (4L + PAYLOAD_BYTES) },
        finalized: Boolean = true,
        flags: Int = 0,
    ): ByteArray {
        val payloads =
            GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH, cycleCount = if (finalized) frames.toLong() else 0)
        val frame = ChannelSpec(
            "CAN_DataFrame",
            DataTypes.BYTE_ARRAY,
            FRAME_BYTES * Byte.SIZE_BITS,
            byteOffset = TIME_BYTES,
            flags = ChannelFlags.BUS_EVENT,
            members = members(payloads),
        )
        val group = GroupSpec(
            3,
            "can",
            listOf(ChannelSpec("Timestamp", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1), frame),
            TIME_BYTES + FRAME_BYTES,
            flags = GroupFlags.BUS_EVENT or GroupFlags.PLAIN_BUS_EVENT,
            cycleCount = if (finalized) frames.toLong() else 0,
        )
        val data = ByteWriter()
        if (payloadsFirst) writePayloads(data, frames)
        for (i in 0 until frames) {
            data.u8(3).f64(i * 0.001).u8(1).u32(idOf(i)).u8(PAYLOAD_BYTES).u8(PAYLOAD_BYTES).u64(offsetOf(i))
        }
        if (!payloadsFirst) writePayloads(data, frames)
        val spec = DataGroupSpec(
            1,
            listOf(payloads, group),
            data.toByteArray(),
            dataBlockLength = if (finalized) null else TestBlock.HEADER_SIZE.toLong(),
        )
        return writeFile(FileSpec(listOf(spec), finalized = finalized, unfinalizedFlags = flags))
    }

    private fun writePayloads(data: ByteWriter, frames: Int) {
        for (i in 0 until frames) data.u8(2).u32(PAYLOAD_BYTES.toLong()).bytes(payloadOf(i))
    }

    private fun members(payloads: GroupSpec): List<ChannelSpec> {
        fun member(name: String, offset: Int, bits: Int) = ChannelSpec(
            "CAN_DataFrame.$name",
            DataTypes.UNSIGNED_LE,
            bits,
            byteOffset = TIME_BYTES + offset,
            flags = ChannelFlags.BUS_EVENT,
        )
        return listOf(
            member("BusChannel", 0, Byte.SIZE_BITS),
            member("ID", 1, CAN_ID_BITS),
            member("DLC", 5, DLC_BITS),
            member("DataLength", 6, Byte.SIZE_BITS),
            ChannelSpec(
                "CAN_DataFrame.DataBytes",
                DataTypes.BYTE_ARRAY,
                Long.SIZE_BITS,
                byteOffset = TIME_BYTES + 7,
                type = ChannelTypes.VARIABLE_LENGTH,
                flags = ChannelFlags.BUS_EVENT,
                signalData = payloads,
            ),
        )
    }

    private const val CAN_ID_BITS = 29
    private const val DLC_BITS = 4
}
