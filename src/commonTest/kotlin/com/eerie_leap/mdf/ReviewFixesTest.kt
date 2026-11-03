package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * What the review of the reader's first version found, each as a test that failed on it: variable-length values shared by several
 * readers, a channel with an array as a scalar, impossible lengths, structures that name one another, a quadratic wait for values,
 * long formulas, texts before their values, impossible bit positions, CAN payloads in the record, a master that can't be read, plain
 * comments with markup, and a comment of ampersands.
 */
class ReviewFixesTest {
    private val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)

    private fun record(id: Int, body: ByteArray): ByteArray = ByteWriter().u8(id).bytes(body).toByteArray()

    private fun canLog(frames: Int): MdfFile {
        val bytes = FirmwareLogWriter().apply {
            val c = can(FirmwareLogWriter.CanSensor("c", "frames"))
            for (i in 0 until frames) frame(c, FirmwareLogWriter.Frame(i * 0.1, 0x100L + i, ByteArray(i + 1) { it.toByte() }))
        }.build()
        return open(bytes)
    }

    // 1.
    @Test
    fun aVariableLengthChannelAndTheCanFramesThatJoinItBothGetTheirPayloads() {
        val file = canLog(5)
        val canGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        val dataBytes = canGroup.channels.flatMap { it.members }.first { it.name.endsWith("DataBytes") }

        val sink = scanAll(file, listOf(MdfChannelRequest(dataBytes)), listOf(canGroup))

        assertEquals(5, sink.frames.size)
        assertEquals(5, sink.texts.values.sumOf { it.size })
        assertEquals(listOf(1, 2, 3, 4, 5), sink.frames.map { it.data.size })
    }

    @Test
    fun theSameVariableLengthChannelRequestedTwiceGivesBothTheirValues() {
        val file = canLog(4)
        val canGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        val dataBytes = canGroup.channels.flatMap { it.members }.first { it.name.endsWith("DataBytes") }
        val converted = MdfChannelRequest(dataBytes)
        val stored = MdfChannelRequest(dataBytes, stored = true)

        val sink = scanAll(file, listOf(converted, stored))

        assertEquals(4, sink.texts.getValue(converted).size)
        assertEquals(sink.texts.getValue(converted), sink.texts.getValue(stored))
    }

    // 2.
    @Test
    fun aChannelWhoseCompositionIsAnArrayIsNeverScannedAsAScalar() {
        var array: TestBlock? = null
        val arr = ChannelSpec("arr", byteOffset = 8)
        val group = GroupSpec(1, "g", listOf(time, arr), 20)
        val records = ByteWriter().u8(1).f64(0.5).f32(1f).f32(2f).f32(3f).toByteArray()
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        1,
                        listOf(group),
                        records,
                        dataBlock = { layout ->
                            array = layout.block("CA", 1, ByteWriter().u8(0).u8(0).u16(1).u32(0).u32(4).u32(0).u64(3).toByteArray())
                            layout.block("DT", 0, records)
                        },
                    ),
                ),
                tamper = { blocks -> blocks.filter { it.type == "CN" }[1].links[1] = array },
            ),
        )
        val file = open(bytes)
        val channel = file.channelGroups.single().channels[1]

        val sink = scanAll(file, listOf(MdfChannelRequest(channel)))

        assertFalse(channel.isNumeric)
        assertTrue(sink.samples.isEmpty())
    }

    // 3.
    @Test
    fun saysZippedLengthsThatAreNegativeAreTooLargeNotACrash() {
        val group = GroupSpec(1, "g", listOf(time, ChannelSpec("v", byteOffset = 8)), 12)
        for (field in listOf(8, 16)) {
            val bytes = writeFile(
                FileSpec(
                    listOf(
                        DataGroupSpec(
                            1,
                            listOf(group),
                            ByteArray(0),
                            dataBlock = { layout ->
                                zippedBlock(layout, ByteArray(10), zlibStored(ByteArray(10))).also {
                                    ByteWriter().u64(-1L).toByteArray().copyInto(it.data, field)
                                }
                            },
                        ),
                    ),
                ),
            )
            val file = open(bytes)
            val request = MdfChannelRequest(file.channelGroups.single().channels[1])

            val failure = failureOf { MdfScanner.create(file, file.groups.single(), listOf(request), CollectingSink()) }

            assertIs<MdfFailure.TooLarge>(failure, "field at $field")
        }
    }

    // 4.
    private fun count(channels: List<MdfChannel>): Long = channels.sumOf { 1L + count(it.members) }

    @Test
    fun channelsThatAllComposeTheSameChannelAreDamagedNotReadOverAndOver() {
        val channels = List(4) { ChannelSpec("c$it", byteOffset = 0) }
        val bytes = writeFile(
            FileSpec(
                listOf(DataGroupSpec(1, listOf(GroupSpec(1, "g", channels, 4)), ByteArray(0))),
                tamper = { blocks ->
                    val cns = blocks.filter { it.type == "CN" }
                    for (cn in cns) cn.links[1] = cns[0]
                },
            ),
        )

        val lenient = runSuspending { Mdf4Reader.open(bytes.asMdfSource(), lenient = true) }

        assertTrue(count(lenient.channelGroups.single().channels) < 100)
        assertTrue(lenient.issues.isNotEmpty())
        assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(bytes.asMdfSource()) })
    }

    private fun level(k: Int, n: Int): List<ChannelSpec> =
        List(n) { i -> ChannelSpec("L${k}_$i", byteOffset = 0, members = if (i == 0 && k < 8) level(k + 1, n) else emptyList()) }

    @Test
    fun aStructureOfStructuresThatShareTheirMembersIsDamagedNotAnExplosion() {
        val group = GroupSpec(1, "g", level(0, 4), 4)
        val bytes = writeFile(
            FileSpec(
                listOf(DataGroupSpec(1, listOf(group), ByteArray(0))),
                tamper = { blocks ->
                    val cns = blocks.filter { it.type == "CN" }

                    fun nameOf(b: TestBlock) = checkNotNull(b.links[2]).data.decodeToString().trimEnd(Char(0))
                    val byName = cns.associateBy { nameOf(it) }
                    for (cn in cns) {
                        val depth = nameOf(cn).substringBefore('_').drop(1).toInt()
                        if (depth < 8) cn.links[1] = byName["L${depth + 1}_0"]
                    }
                },
            ),
        )

        assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(bytes.asMdfSource()) })
    }

    // 5.
    @Test
    fun framesThatCameBeforeTheirPayloadsAreFoundByTheirOffsetsNotByALookThroughAllOfThem() {
        val frames = 80_000
        val bytes = CanTestFile.build(frames, payloadsFirst = false)
        val file = open(bytes)
        val canGroup = file.channelGroups.first { CanFrames.isCanDataFrameGroup(it) }
        val start = TimeSource.Monotonic.markNow()

        val sink = scanAll(file, emptyList(), listOf(canGroup))

        assertEquals(frames, sink.frames.size)
        assertEquals(List(frames) { CanTestFile.idOf(it) }, sink.frames.map { it.id })
        assertTrue(start.elapsedNow().inWholeMilliseconds < MAX_MILLISECONDS, "took ${start.elapsedNow()}")
    }

    // 6.
    @Test
    fun aLongChainOfOperatorsIsRejectedNotAStackOverflow() {
        val parsed = MdfFormula.parse("X" + "+X".repeat(200_000))

        assertIs<MdfFormula.Rejected>(parsed)
    }

    // 7.
    @Test
    fun aTextWhosePayloadComesAfterTheRecordThatNamesItIsStillRead() {
        val values = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH)
        val text = ChannelSpec("s", DataTypes.STRING_UTF8, 64, byteOffset = 8, type = ChannelTypes.VARIABLE_LENGTH, signalData = values)
        val strings = GroupSpec(1, "strings", listOf(time, text), 16)

        fun payload(value: String) =
            record(2, ByteWriter().u32(value.encodeToByteArray().size.toLong()).bytes(value.encodeToByteArray()).toByteArray())
        val data = ByteWriter()
        listOf(
            record(1, RecordWriter(16).f64(0, 0.1).u64(8, 0).bytes),
            record(1, RecordWriter(16).f64(0, 0.2).u64(8, 4L + 5).bytes),
            payload("hello"),
            payload("world"),
        ).forEach { data.bytes(it) }
        val file = open(writeFile(FileSpec(listOf(DataGroupSpec(1, listOf(values, strings), data.toByteArray())))))
        val channel = file.channelGroups.flatMap { it.channels }.first { it.name == "s" }

        val sink = scanAll(file, listOf(MdfChannelRequest(channel)))

        assertEquals(listOf("hello", "world"), sink.texts.values.flatten())
    }

    // 8.
    @Test
    fun anInvalidationBitBeyondAnyRecordIsDamagedNotAnIndexOutOfBounds() {
        val value = ChannelSpec("v", byteOffset = 8, invalidationBit = -1)
        val group = GroupSpec(1, "g", listOf(time, value), 15)
        val data = ByteWriter()
        repeat(64) { data.bytes(ByteWriter().u8(1).f64(it.toDouble()).f32(1f).u8(0).u8(0).u8(0).toByteArray()) }
        val bytes = writeFile(FileSpec(listOf(DataGroupSpec(1, listOf(group), data.toByteArray()))))

        val failure = failureOf {
            val file = Mdf4Reader.open(bytes.asMdfSource())
            val request = MdfChannelRequest(file.channelGroups.single().channels[1])
            MdfScanner.create(file, file.groups.single(), listOf(request), CollectingSink()).advance()
        }

        assertIs<MdfFailure.Damaged>(failure)
    }

    // 9.
    @Test
    fun canFramesWhosePayloadIsABytesFieldOfTheRecordAreRead() {
        fun member(name: String, offset: Int, bits: Int) =
            ChannelSpec("CAN_DataFrame.$name", DataTypes.UNSIGNED_LE, bits, byteOffset = 8 + offset, flags = ChannelFlags.BUS_EVENT)
        val members = listOf(
            member("ID", 0, 29),
            member("DataLength", 4, 8),
            ChannelSpec(
                "CAN_DataFrame.DataBytes",
                DataTypes.BYTE_ARRAY,
                64,
                byteOffset = 8 + 5,
                flags = ChannelFlags.BUS_EVENT,
            ),
        )
        val frame =
            ChannelSpec(
                "CAN_DataFrame",
                DataTypes.BYTE_ARRAY,
                13 * Byte.SIZE_BITS,
                byteOffset = 8,
                flags = ChannelFlags.BUS_EVENT,
                members = members,
            )
        val group = GroupSpec(
            1,
            "can",
            listOf(time, frame),
            8 + 13,
            flags = GroupFlags.BUS_EVENT or GroupFlags.PLAIN_BUS_EVENT,
        )
        val data = ByteWriter()
        data.u8(1).f64(0.5).u32(0x123).u8(3).bytes(byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2))
        data.u8(1).f64(0.6).u32(0x456).u8(8).bytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val file = open(writeFile(FileSpec(listOf(DataGroupSpec(1, listOf(group), data.toByteArray())))))
        val canGroup = file.channelGroups.single()

        assertTrue(CanFrames.isCanDataFrameGroup(canGroup))
        val sink = scanAll(file, emptyList(), listOf(canGroup))

        assertEquals(listOf(0x123L, 0x456L), sink.frames.map { it.id })
        assertContentEquals(byteArrayOf(9, 8, 7), sink.frames[0].data)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), sink.frames[1].data)
    }

    // 10.
    private fun scanned(group: GroupSpec, record: ByteArray): Pair<CollectingSink, MdfScanner> {
        val file = open(writeFile(FileSpec(listOf(DataGroupSpec(1, listOf(group), record)))))
        val request = MdfChannelRequest(file.channelGroups.single().channels.last())
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
        runSuspending { scanner.advance() }
        return sink to scanner
    }

    @Test
    fun aMasterThatCantBeReadGivesNoTimeToTheSamplesOfItsGroup() {
        val value = ChannelSpec("v", byteOffset = 8)
        val body = ByteWriter().f64(0.5).f32(1f).toByteArray()
        val conversion = ConversionSpec.linear(0.0, 0.001)
        val cases = mapOf(
            "a master outside its record" to
                ChannelSpec(
                    "t",
                    DataTypes.FLOAT_LE,
                    64,
                    byteOffset = 100,
                    type = ChannelTypes.MASTER,
                    syncType = 1,
                    conversion = conversion,
                ),
            "a master that is text" to
                ChannelSpec("t", DataTypes.STRING_UTF8, 64, type = ChannelTypes.MASTER, syncType = 1, conversion = conversion),
        )
        for ((what, master) in cases) {
            val (sink, scanner) = scanned(GroupSpec(1, "g", listOf(master, value), 12), record(1, body))

            assertTrue(sink.samples.isEmpty(), what)
            assertTrue(scanner.rejected.isNotEmpty(), what)
        }
    }

    @Test
    fun aGroupThatSaysItsMasterIsElsewhereButNamesNoneGivesNoTimeEither() {
        val value = ChannelSpec("v", byteOffset = 8)
        val group = GroupSpec(1, "g", listOf(time, value), 12, flags = 0x08)

        val (sink, scanner) = scanned(group, record(1, ByteWriter().f64(0.5).f32(1f).toByteArray()))

        assertTrue(sink.samples.isEmpty())
        assertTrue(scanner.rejected.isNotEmpty())
    }

    // 11.
    @Test
    fun aPlainTextCommentWithMarkupInItIsKeptAsItIs() {
        val channel = ChannelSpec("temperature", byteOffset = 8, unit = "a unit of some length to hold the comment")
        val group = GroupSpec(1, "g", listOf(time, channel), 12)
        val bytes = writeFile(
            FileSpec(
                listOf(DataGroupSpec(1, listOf(group), ByteArray(0))),
                tamper = { blocks ->
                    val cn = blocks.filter { it.type == "CN" }[1]
                    val text = checkNotNull(cn.links[6])
                    val markup = "<b>Oil</b> temperature".encodeToByteArray()
                    text.data.fill(0)
                    markup.copyInto(text.data)
                    cn.links[7] = text
                },
            ),
        )

        val comment = open(bytes).channelGroups.single().channels[1].comment

        assertEquals("<b>Oil</b> temperature", comment.text)
    }

    // 12.
    @Test
    fun aCommentOfNothingButAmpersandsIsReadInTimeProportionalToItsLength() {
        val text = "<CNcomment><TX>" + "&".repeat(1_500_000) + "</TX></CNcomment>"
        val start = TimeSource.Monotonic.markNow()

        val comment = MdfComment.parse(text)

        assertEquals(1_500_000, comment.text.length)
        assertTrue(start.elapsedNow().inWholeMilliseconds < MAX_MILLISECONDS, "took ${start.elapsedNow()}")
    }

    private companion object {
        const val MAX_MILLISECONDS = 3000
    }
}
