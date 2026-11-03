package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val VALUE_RECORD = 14
private const val FRAME_RECORD = 24

class CanFramesTest {
    private val frames = listOf(
        FirmwareLogWriter.Frame(0.010, 0x316, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
        FirmwareLogWriter.Frame(0.020, 0x18FEF100, byteArrayOf(9, 8, 7), isExtended = true, isTransmit = true),
        FirmwareLogWriter.Frame(0.030, 0x123, ByteArray(12) { it.toByte() }, bus = 2, isFd = true, isBitRateSwitch = true),
        FirmwareLogWriter.Frame(0.040, 0x7FF, ByteArray(0)),
    )

    // A value sensor and a CAN sensor whose records alternate, as the ticks write them.
    private fun log(finished: Boolean = true): ByteArray = FirmwareLogWriter().apply {
        val value = sensor(FirmwareLogWriter.ValueSensor("speed", "km/h"))
        val can = can(FirmwareLogWriter.CanSensor("can_in"))
        for ((i, frame) in frames.withIndex()) {
            value(value, i * 0.01, i.toFloat())
            frame(can, frame)
        }
    }.build(finished)

    // A value record is 14 bytes; a frame takes its payload's record (an ID, a length and the bytes) and its own, 24 bytes.
    private val recordBytes = frames.size * (VALUE_RECORD + 1 + 4 + FRAME_RECORD) + frames.sumOf { it.data.size }

    private fun readFrames(bytes: ByteArray): List<MdfCanFrame> {
        val file = open(bytes)
        val frameGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        val read = ArrayList<MdfCanFrame>()
        runSuspending { CanFrames.read(file, file.groups.single(), frameGroup) { read.add(it) } }
        return read
    }

    private fun expected(frame: FirmwareLogWriter.Frame): MdfCanFrame = MdfCanFrame(
        time = frame.time,
        bus = frame.bus,
        id = frame.id,
        isExtended = frame.isExtended,
        dlc = if (frame.data.size <= 8) frame.data.size else 9,
        isTransmit = frame.isTransmit,
        isFd = frame.isFd,
        isBitRateSwitch = frame.isBitRateSwitch,
        hasErrorState = false,
        dataLength = frame.data.size,
        data = frame.data,
    )

    @Test
    fun readsTheFramesOfACanGroupWithTheirPayloads() {
        assertEquals(frames.map { expected(it) }, readFrames(log()))
    }

    @Test
    fun readsTheFramesOfAnUnfinishedFile() {
        assertEquals(frames.map { expected(it) }, readFrames(log(finished = false)))
    }

    @Test
    fun tellsTheCanGroupFromTheOthers() {
        val file = open(log())

        assertEquals(listOf(false, false, true), file.channelGroups.map { CanFrames.isCanDataFrameGroup(it) })
    }

    @Test
    fun ignoresAPayloadWhoseFrameWasCutOff() {
        val whole = log()
        val bytes = whole.copyOf(whole.size - FRAME_RECORD)
        // The last frame's record is gone: its payload (a length and no bytes) is an orphan, which readers ignore.
        assertEquals(frames.dropLast(1).map { expected(it) }, readFrames(bytes))
    }

    @Test
    fun readsTheWholeFramesOfAFileCutAtAnyByte() {
        val whole = log()
        val all = frames.map { expected(it) }
        val first = whole.size - recordBytes

        for (size in first..whole.size) {
            val read = readFrames(whole.copyOf(size))

            assertEquals(all.take(read.size), read, "cut at $size")
            if (size == whole.size) assertEquals(all.size, read.size)
        }
    }

    @Test
    fun readsFramesAsAFileGrows() {
        val whole = log(finished = false)
        val first = whole.size - recordBytes
        val source = GrowingSource(whole, first)
        val file = runSuspending { Mdf4Reader.open(source) }
        val frameGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), emptyList(), sink, listOf(frameGroup)) }

        var visible = first
        while (visible < whole.size) {
            visible = minOf(whole.size, visible + 5)
            source.visible = visible
            runSuspending { scanner.advance() }
        }

        assertEquals(frames.map { expected(it) }, sink.frames)
        assertEquals(0, scanner.unresolvedFrames)
    }

    @Test
    fun countsTheRecordsOfBothGroupsOfACanSensor() {
        val file = open(log(finished = false))
        val sink = CollectingSink()
        val frameGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), emptyList(), sink, listOf(frameGroup)) }
        runSuspending { scanner.advance() }

        // The unfinished file says none; the scan found four values, four payloads and four frames.
        assertEquals(listOf(0L, 0L, 0L), file.channelGroups.map { it.cycleCount })
        assertEquals(listOf(4L, 4L, 4L), file.channelGroups.map { scanner.cycleCount(it) })
    }

    @Test
    fun joinsFramesToTheirPayloadsByOffsetInAnyOrder() {
        // Two frames whose payloads were written after them, in the other order: foreign writers may.
        val layout = foreignCanFile()

        val read = ArrayList<MdfCanFrame>()
        val file = open(layout.bytes)
        val frameGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        runSuspending { CanFrames.read(file, file.groups.single(), frameGroup) { read.add(it) } }

        assertEquals(setOf(0x100L to listOf<Byte>(1, 2, 3), 0x200L to listOf<Byte>(4, 5)), read.map { it.id to it.data.toList() }.toSet())
    }

    @Test
    fun aCanGroupWhoseMembersLieOutsideItsRecordIsNotOneToRead() {
        val bytes = FirmwareLogWriter().apply { can(FirmwareLogWriter.CanSensor("c")) }.build()
        val tampered = writeFileWithMemberOffset(bytes)

        assertFalse(CanFrames.isCanDataFrameGroup(tampered.channelGroups.last()))
    }

    // The same file with the offset of the frame's ID channel moved far out of the record.
    private fun writeFileWithMemberOffset(bytes: ByteArray): MdfFile {
        val copy = bytes.copyOf()
        val idOffset = open(bytes).channelGroups.last().channels[1].members[1].offset.toInt()
        // The channel block's fields begin after its 24-byte header and 8 links; the byte offset is 4 bytes in.
        val at = idOffset + 24 + 8 * 8 + 4
        copy[at + 2] = 0x10
        return open(copy)
    }

    @Test
    fun listsTheOtherBusEventGroupsWithoutReadingThem() {
        val file = open(foreignCanFile(withLin = true).bytes)

        val others = CanFrames.otherBusEventGroups(file)

        assertEquals(listOf("LIN_Frame"), others.map { it.name })
        assertEquals(MdfBusType.Lin, others.single().source?.busType)
        assertFalse(CanFrames.isCanDataFrameGroup(others.single()))
        assertTrue(CanFrames.isCanDataFrameGroup(file.channelGroups.first { it.name == "CAN_DataFrame" }))
    }

    private class Foreign(val bytes: ByteArray)

    // A CAN group in the layout of other writers: members named CAN_DataFrame.X, ID of 32 bits with IDE in bit 31, the payloads
    // of both frames after both frame records. 1-byte record IDs: the frames' group is 1, the payloads' 2, LIN 3.
    private fun foreignCanFile(withLin: Boolean = false): Foreign {
        fun member(
            name: String,
            offset: Int,
            bits: Int,
            bitOffset: Int = 0,
            type: Int = ChannelTypes.FIXED,
            dataType: Int = DataTypes.UNSIGNED_LE,
        ) = ChannelSpec("CAN_DataFrame.$name", dataType, bits, offset, bitOffset, type = type, flags = ChannelFlags.BUS_EVENT)
        val payloads = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH, vlsdBytes = 5)
        val members = listOf(
            member("ID", 8, 32),
            member("DataLength", 12, 8),
            member("DataBytes", 13, 32, type = ChannelTypes.VARIABLE_LENGTH, dataType = DataTypes.BYTE_ARRAY).let {
                ChannelSpec(it.name, it.dataType, it.bitCount, it.byteOffset, 0, type = it.type, flags = it.flags, signalData = payloads)
            },
        )
        val frame = ChannelSpec("CAN_DataFrame", DataTypes.BYTE_ARRAY, 9 * 8, 8, flags = ChannelFlags.BUS_EVENT, members = members)
        val time = ChannelSpec("Timestamp", DataTypes.FLOAT_LE, 64, 0, type = ChannelTypes.MASTER, syncType = 1)
        val frames = GroupSpec(
            1,
            "CAN_DataFrame",
            listOf(time, frame),
            8 + 9,
            flags = GroupFlags.BUS_EVENT or GroupFlags.PLAIN_BUS_EVENT,
            source = SourceSpec("CAN", type = 2, bus = 2),
        )
        val lin = GroupSpec(3, "LIN_Frame", listOf(time), 8, flags = GroupFlags.BUS_EVENT, source = SourceSpec("LIN", type = 2, bus = 3))
        fun frameRecord(timeValue: Double, id: Long, length: Int, offset: Long) =
            ByteWriter().u8(1).f64(timeValue).u32(id or (1L shl 31)).u8(length).u32(offset).toByteArray()
        fun payloadRecord(bytes: ByteArray) = ByteWriter().u8(2).u32(bytes.size.toLong()).bytes(bytes).toByteArray()
        // The second payload comes first: offsets are those of the payload stream in the order of the offsets: 0 and 7.
        val records = frameRecord(0.1, 0x100, 3, 0) + frameRecord(0.2, 0x200, 2, 7) + payloadRecord(byteArrayOf(1, 2, 3)) +
            payloadRecord(byteArrayOf(4, 5))
        val groups = if (withLin) listOf(payloads, frames, lin) else listOf(payloads, frames)
        val bytes = writeFile(FileSpec(listOf(DataGroupSpec(1, groups, records))))
        return Foreign(bytes)
    }
}
