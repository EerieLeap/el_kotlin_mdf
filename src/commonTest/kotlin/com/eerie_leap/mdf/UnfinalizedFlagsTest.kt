package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an unfinished file of any writer leaves for the reader to recount, flag by flag of `id_unfin_flags` (the lists are in DataListsTest). */
class UnfinalizedFlagsTest {
    private val cycleCounts = 0x01
    private val reductionCounts = 0x02
    private val lastDataBlock = 0x04
    private val reductionBlock = 0x08
    private val vlsdBytes = 0x20
    private val vlsdOffsets = 0x40

    private fun frames(file: MdfFile): List<MdfCanFrame> {
        val group = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        return scanAll(file, emptyList(), listOf(group)).frames
    }

    @Test
    fun takesVariableLengthValuesByTheirOffsetsWhenTheFileSaysTheyAreRight() {
        // Every frame names offset 0: all take the first payload, which only the first of them can.
        val bytes = CanTestFile.build(5, payloadsFirst = true, offsetOf = { 0L })

        val taken = frames(open(bytes))

        assertEquals(listOf(CanTestFile.idOf(0)), taken.map { it.id })
    }

    @Test
    fun recountsTheOffsetsOfVariableLengthValuesWhereTheFileSaysTheyAreStale() {
        // The offsets in the records weren't updated (flag 0x40): the n-th frame takes the n-th payload.
        val flags = cycleCounts or lastDataBlock or vlsdBytes or vlsdOffsets
        val bytes = CanTestFile.build(5, payloadsFirst = true, offsetOf = { 0L }, finalized = false, flags = flags)

        val taken = frames(open(bytes))

        assertEquals(List(5) { CanTestFile.idOf(it) }, taken.map { it.id })
        assertEquals(List(5) { CanTestFile.payloadOf(it).toList() }, taken.map { it.data.toList() })
    }

    @Test
    fun recountsForFramesThatComeBeforeTheirPayloadsToo() {
        val flags = cycleCounts or lastDataBlock or vlsdOffsets
        val bytes = CanTestFile.build(5, payloadsFirst = false, offsetOf = { 999L * it }, finalized = false, flags = flags)

        val taken = frames(open(bytes))

        assertEquals(List(5) { CanTestFile.payloadOf(it).toList() }, taken.map { it.data.toList() })
    }

    @Test
    fun recountsTheValuesOfTextChannelsToo() {
        val values = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH)
        val text = ChannelSpec("s", DataTypes.STRING_UTF8, 64, byteOffset = 8, type = ChannelTypes.VARIABLE_LENGTH, signalData = values)
        val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
        val strings = GroupSpec(1, "strings", listOf(time, text), 16)
        val data = ByteWriter()
        listOf("one", "two", "three").forEach { data.u8(2).u32(it.length.toLong()).bytes(it.encodeToByteArray()) }
        repeat(3) { data.u8(1).f64(it * 0.1).u64(7) }
        val bytes = writeFile(
            FileSpec(
                listOf(DataGroupSpec(1, listOf(values, strings), data.toByteArray(), dataBlockLength = TestBlock.HEADER_SIZE.toLong())),
                finalized = false,
                unfinalizedFlags = cycleCounts or lastDataBlock or vlsdOffsets,
            ),
        )
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.flatMap { it.channels }.first { it.name == "s" })

        val sink = scanAll(file, listOf(request))

        assertEquals(listOf("one", "two", "three"), sink.texts.getValue(request))
    }

    @Test
    fun theByteCountsOfVariableLengthGroupsAreStaleButTheScanCountsThemItself() {
        val flags = cycleCounts or lastDataBlock or vlsdBytes
        val file = open(CanTestFile.build(6, payloadsFirst = true, finalized = false, flags = flags))
        val payloads = file.channelGroups.first { it.isVlsd }

        assertEquals(0L, payloads.vlsdBytes)
        assertEquals(6, frames(file).size)
    }

    @Test
    fun theFlagsAboutSampleReductionsChangeNothingForTheDataTheReaderReads() {
        val flags = cycleCounts or reductionCounts or lastDataBlock or reductionBlock
        val file = open(CanTestFile.build(4, payloadsFirst = true, finalized = false, flags = flags))

        assertEquals(
            setOf(
                UnfinalizedFlag.CycleCounts,
                UnfinalizedFlag.SampleReductionCounts,
                UnfinalizedFlag.LastDataBlockLength,
                UnfinalizedFlag.LastReductionDataBlockLength,
            ),
            file.identification.unfinalized,
        )
        assertEquals(4, frames(file).size)
    }

    @Test
    fun staleCycleCountsLeaveASortedGroupReadToTheEndOfItsData() {
        val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
        val group = GroupSpec(0, "g", listOf(time, ChannelSpec("v", byteOffset = 8)), 12, cycleCount = 3)
        val records = ByteWriter()
        for (i in 0 until 10) records.f64(i.toDouble()).f32(i.toFloat())
        fun scanned(unfinalizedFlags: Int): List<Sample> {
            val bytes = writeFile(
                FileSpec(
                    listOf(DataGroupSpec(0, listOf(group), records.toByteArray())),
                    finalized = unfinalizedFlags == 0,
                    unfinalizedFlags = unfinalizedFlags,
                ),
            )
            val file = open(bytes)
            val request = MdfChannelRequest(file.channelGroups.single().channels[1])
            return scanAll(file, listOf(request)).of(request)
        }

        // A count that can be trusted cuts the data to three records; one the file says is stale doesn't.
        assertEquals(3, scanned(0).size)
        assertEquals(10, scanned(cycleCounts).size)
    }

    @Test
    fun aFinishedFileHasNoStaleFlag() {
        val file = open(CanTestFile.build(2, payloadsFirst = true))

        assertTrue(file.identification.isFinalized)
        assertTrue(file.identification.unfinalized.isEmpty())
        assertNull(file.skipped.firstOrNull())
    }
}
