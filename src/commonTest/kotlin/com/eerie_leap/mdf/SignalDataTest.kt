package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Variable-length values kept in signal data blocks (SD), alone, zipped or listed, for a sorted data group. */
class SignalDataTest {
    private val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)

    private fun textChannel(signal: ((BlockLayout) -> TestBlock)?, dataType: Int = DataTypes.STRING_UTF8) =
        ChannelSpec("s", dataType, 64, byteOffset = 8, type = ChannelTypes.VARIABLE_LENGTH, signalDataBlock = signal)

    // A sorted group of a time and an offset into the signal data for each record.
    private fun fileOf(
        offsets: List<Long>,
        dataType: Int = DataTypes.STRING_UTF8,
        recordIdSize: Int = 0,
        signal: (BlockLayout) -> TestBlock,
    ): ByteArray {
        val group = GroupSpec(
            if (recordIdSize ==
                0
            ) {
                0
            } else {
                1
            },
            "g",
            listOf(master, textChannel(signal, dataType)),
            16,
            cycleCount = offsets.size.toLong(),
        )
        val records = ByteWriter()
        offsets.forEachIndexed { index, offset ->
            if (recordIdSize > 0) records.u8(1)
            records.f64(index * 0.1).u64(offset)
        }
        return writeFile(FileSpec(listOf(DataGroupSpec(recordIdSize, listOf(group), records.toByteArray()))))
    }

    private fun scanned(bytes: ByteArray, stored: Boolean = false): Triple<MdfScanProgress, List<String>, MdfScanner> {
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1], stored)
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
        val progress = runSuspending { scanner.advance() }
        return Triple(progress, sink.texts[request].orEmpty(), scanner)
    }

    private val words = listOf("alpha", "", "gamma delta", "e", "ünï", "zeta".repeat(30))

    private fun encoded(values: List<String>) = SignalData.of(values.map { it.encodeToByteArray() })

    @Test
    fun readsStringsFromASignalDataBlock() {
        val data = encoded(words)
        val bytes = fileOf(data.offsets) { layout -> plainPart(layout, data.bytes, "SD").block }

        val (progress, texts, scanner) = scanned(bytes)

        assertEquals(words, texts)
        assertTrue(progress.isComplete)
        assertNull(progress.problem)
        assertTrue(scanner.rejected.isEmpty())
    }

    @Test
    fun readsBytesFromASignalDataBlock() {
        val values = listOf(byteArrayOf(1, 2, 3), byteArrayOf(), byteArrayOf(-1, 0, 127))
        val data = SignalData.of(values)
        val bytes = fileOf(data.offsets, DataTypes.BYTE_ARRAY) { layout -> plainPart(layout, data.bytes, "SD").block }

        assertEquals(listOf("01 02 03", "", "ff 00 7f"), scanned(bytes).second)
    }

    @Test
    fun readsStringsFromAListOfSignalDataBlocksThatCutTheValues() {
        val data = encoded(words)
        // Pieces of 13 bytes: every value is cut somewhere, the length of one among them.
        val bytes = fileOf(data.offsets) { layout ->
            dataListChain(layout, chunks(data.bytes, 13).map { plainPart(layout, it, "SD") }, perList = 3)
        }

        val (progress, texts) = scanned(bytes)

        assertEquals(words, texts)
        assertTrue(progress.isComplete)
    }

    @Test
    fun readsStringsFromZippedSignalDataBlocksUnderAHeader() {
        val data = encoded(words)
        val bytes = fileOf(data.offsets) { layout ->
            headerList(layout, dataListChain(layout, chunks(data.bytes, 40).map { zippedPart(layout, it, "SD") }, perList = 2))
        }

        assertEquals(words, scanned(bytes).second)
    }

    @Test
    fun readsStringsFromOneZippedSignalDataBlock() {
        val data = encoded(words)
        val bytes = fileOf(data.offsets) { layout -> zippedPart(layout, data.bytes, "SD").block }

        assertEquals(words, scanned(bytes).second)
    }

    @Test
    fun takesValuesByOffsetInAnyOrderAndMoreThanOnce() {
        val data = encoded(words)
        val order = listOf(3, 0, 5, 0, 2, 2, 4, 1, 5)
        val bytes = fileOf(order.map { data.offsets[it] }) { layout -> plainPart(layout, data.bytes, "SD").block }

        assertEquals(order.map { words[it] }, scanned(bytes).second)
    }

    @Test
    fun anOffsetPastTheEndOfTheDataGivesNoValueAndTheOthersStillRead() {
        val data = encoded(words)
        val offsets = listOf(data.offsets[0], data.bytes.size.toLong() + 100, data.offsets[2], data.bytes.size.toLong() - 2)
        val bytes = fileOf(offsets) { layout -> plainPart(layout, data.bytes, "SD").block }

        val (progress, texts) = scanned(bytes)

        assertEquals(listOf(words[0], words[2]), texts)
        assertNull(progress.problem)
        assertTrue(progress.isComplete)
    }

    @Test
    fun anOffsetNoFileCanHaveGivesNoValueAndTheOthersStillRead() {
        val data = encoded(words)
        // Offsets of 2^63 or more (negative here) and ones that overflow when the length of a value is added to them.
        val offsets = listOf(
            data.offsets[0],
            -1L,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            data.offsets[2],
            Long.MAX_VALUE - 1,
            0xFFFFFFFFL,
            data.offsets[4],
        )
        val bytes = fileOf(offsets) { layout -> plainPart(layout, data.bytes, "SD").block }

        val (progress, texts) = scanned(bytes)

        assertEquals(listOf(words[0], words[2], words[4]), texts)
        assertNull(progress.problem)
        assertTrue(progress.isComplete)
    }

    @Test
    fun readsVastSignalDataBlocksThroughItsWindowsInOrderAndOutOfIt() {
        // 40 000 values of about 100 bytes: four megabytes, more than the window the reader keeps of them.
        val values = List(40_000) { "value $it " + "x".repeat(it % 91) }
        val data = encoded(values)
        val order = (0 until 40_000 step 7) + (39_999 downTo 0 step 997) + listOf(5, 39_000, 12)
        val bytes =
            fileOf(
                order.map {
                    data.offsets[it]
                },
            ) { layout -> dataListChain(layout, chunks(data.bytes, 1 shl 20).map { plainPart(layout, it, "SD") }) }

        val (progress, texts) = scanned(bytes)

        assertEquals(order.map { values[it] }, texts)
        assertTrue(progress.isComplete)
    }

    @Test
    fun readsAValueLongerThanItsWindow() {
        val big = "b".repeat(1_500_000)
        val data = encoded(listOf("small", big, "after"))
        val bytes = fileOf(data.offsets) { layout -> plainPart(layout, data.bytes, "SD").block }

        assertEquals(listOf("small", big, "after"), scanned(bytes).second)
    }

    @Test
    fun readsSignalDataInAnUnsortedDataGroupToo() {
        val data = encoded(words)
        val bytes = fileOf(data.offsets, recordIdSize = 1) { layout -> plainPart(layout, data.bytes, "SD").block }

        assertEquals(words, scanned(bytes).second)
    }

    @Test
    fun readsTheSameValuesWhetherStoredOrConverted() {
        val data = encoded(words)
        val bytes = fileOf(data.offsets) { layout -> plainPart(layout, data.bytes, "SD").block }
        val file = open(bytes)
        val channel = file.channelGroups.single().channels[1]
        val plain = MdfChannelRequest(channel)
        val stored = MdfChannelRequest(channel, stored = true)
        val sink = CollectingSink()

        runSuspending { MdfScanner.create(file, file.groups.single(), listOf(plain, stored), sink).advance() }

        assertEquals(words, sink.texts[plain].orEmpty())
        assertEquals(words, sink.texts[stored].orEmpty())
    }

    @Test
    fun waitsForSignalDataThatIsNotAllThereYetAndReadsItWhenItIs() {
        val data = encoded(words)
        val bytes = fileOf(data.offsets) { layout -> plainPart(layout, data.bytes, "SD").block }
        val source = GrowingSource(bytes, bytes.size - 20)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        val waiting = runSuspending { scanner.advance() }
        assertFalse(waiting.isComplete)
        assertNull(waiting.problem)

        source.visible = bytes.size
        val done = runSuspending { scanner.advance() }

        assertTrue(done.isComplete)
        assertEquals(words, sink.texts[request].orEmpty())
    }

    @Test
    fun rejectsOnlyTheChannelWhoseSignalDataItCannotReadAndScansTheRest() {
        val data = encoded(words)
        val other = ChannelSpec("n", byteOffset = 16)
        val text = textChannel({ layout -> zippedPart(layout, data.bytes, "SD", zipType = 5).block })
        val group = GroupSpec(0, "g", listOf(master, text, other), 20, cycleCount = 1)
        val record = ByteWriter().f64(0.5).u64(0).f32(7f).toByteArray()
        val file = open(writeFile(FileSpec(listOf(DataGroupSpec(0, listOf(group), record)))))
        val requests = file.channelGroups.single().channels.drop(1).map { MdfChannelRequest(it) }
        val sink = CollectingSink()

        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), requests, sink) }
        runSuspending { scanner.advance() }

        assertEquals(listOf(7.0), sink.of(requests[1]).map { it.value })
        assertIs<MdfFailure.Unsupported>(scanner.rejected.single { it.subject == "channel s" }.failure)
    }

    @Test
    fun aVariableLengthChannelThatNamesNoValuesIsRejectedNotRead() {
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(GroupSpec(0, "g", listOf(master, textChannel(null)), 16, cycleCount = 1)),
                        ByteWriter().f64(0.0).u64(0).toByteArray(),
                    ),
                ),
            ),
        )
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), CollectingSink()) }

        assertIs<MdfFailure.Unsupported>(scanner.rejected.single().failure)
    }
}
