package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Data in lists of blocks (DL, under an HL or not), of any lengths, plain or zipped, and what an unfinished file leaves of them. */
class DataListsTest {
    private val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val value = ChannelSpec("v", byteOffset = 8)

    // Sorted records of 12 bytes: the time `i / 2` and the value `10 i`.
    private fun records(count: Int): ByteArray {
        val out = ByteWriter()
        for (i in 0 until count) out.f64(i / 2.0).f32(i * 10f)
        return out.toByteArray()
    }

    private fun expected(count: Int) = List(count) { Sample(it / 2.0, it * 10.0, false) }

    private fun group(cycleCount: Long) = GroupSpec(0, "g", listOf(master, value), 12, cycleCount = cycleCount)

    private fun fileOf(cycleCount: Long, data: (BlockLayout) -> TestBlock): ByteArray =
        writeFile(FileSpec(listOf(DataGroupSpec(0, listOf(group(cycleCount)), ByteArray(0), dataBlock = data))))

    private fun scanned(bytes: ByteArray): Pair<MdfScanProgress, List<Sample>> {
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink).advance() }
        return progress to sink.of(request)
    }

    // [isComplete] is false for a file that is not finished: the end of its last block isn't known.
    private fun assertReadsAll(bytes: ByteArray, count: Int, isComplete: Boolean = true) {
        val (progress, samples) = scanned(bytes)
        assertEquals(expected(count), samples, "${samples.size} of $count records")
        assertNull(progress.problem)
        assertEquals(isComplete, progress.isComplete, "$progress")
    }

    @Test
    fun readsAListOfEqualLengthBlocksWhoseRecordsSpanThem() {
        val data = records(50)
        // 100 bytes a block: not a multiple of the record's 12, so most blocks end inside a record.
        val bytes = fileOf(50) { layout -> dataListChain(layout, chunks(data, 100).map { plainPart(layout, it) }) }

        assertReadsAll(bytes, 50)
    }

    @Test
    fun readsAListOfBlocksOfAnyLengths() {
        val data = records(40)
        val sizes = listOf(7, 30, 100, 5, 1, 200, 137)
        val pieces = ArrayList<ByteArray>()
        var at = 0
        var index = 0
        while (at < data.size) {
            val size = minOf(sizes[index++ % sizes.size], data.size - at)
            pieces.add(data.copyOfRange(at, at + size))
            at += size
        }
        val bytes = fileOf(40) { layout -> dataListChain(layout, pieces.map { plainPart(layout, it) }, equal = false) }

        assertReadsAll(bytes, 40)
    }

    @Test
    fun readsAChainOfListsOfFewLinksEach() {
        val data = records(120)
        val bytes = fileOf(120) { layout -> dataListChain(layout, chunks(data, 48).map { plainPart(layout, it) }, perList = 4) }

        assertReadsAll(bytes, 120)
        val unequal =
            fileOf(120) { layout -> dataListChain(layout, chunks(data, 48).map { plainPart(layout, it) }, perList = 3, equal = false) }
        assertReadsAll(unequal, 120)
    }

    @Test
    fun aListMayHaveRoomForMoreBlocksThanItNames() {
        val data = records(30)
        val bytes = fileOf(30) { layout ->
            dataList(layout, chunks(data, 90).map { plainPart(layout, it) }, capacity = 8)
        }

        assertReadsAll(bytes, 30)
    }

    @Test
    fun aListOfNoBlocksHoldsNoData() {
        val bytes = fileOf(0) { layout -> dataList(layout, emptyList(), capacity = 2, count = 0) }

        val (progress, samples) = scanned(bytes)

        assertTrue(samples.isEmpty())
        assertTrue(progress.isComplete)
        assertEquals(0L, progress.bytesRead)
    }

    @Test
    fun readsAListOfZippedBlocks() {
        val data = records(64)
        val bytes = fileOf(64) { layout -> dataListChain(layout, chunks(data, 250).map { zippedPart(layout, it) }, perList = 2) }

        assertReadsAll(bytes, 64)
    }

    @Test
    fun readsAHeaderOfAListOfTransposedZippedBlocks() {
        val data = records(100)
        val bytes = fileOf(100) { layout ->
            // 240 bytes is twenty records; the last block holds what is left, an incomplete record among it.
            val parts = chunks(data + byteArrayOf(1, 2, 3), 240).map {
                zippedPart(layout, it, zipType = ZIP_TRANSPOSED_DEFLATE, columns = 12)
            }
            headerList(layout, dataListChain(layout, parts, perList = 3), ZIP_TRANSPOSED_DEFLATE)
        }

        assertReadsAll(bytes, 100)
    }

    @Test
    fun readsAHeaderOfAListOfDeflatedBlocks() {
        val data = records(33)
        val bytes = fileOf(33) { layout -> headerList(layout, dataListChain(layout, chunks(data, 77).map { zippedPart(layout, it) })) }

        assertReadsAll(bytes, 33)
    }

    @Test
    fun aHeaderOfNoListHoldsNoData() {
        val bytes = fileOf(0) { layout -> layout.block("HL", 1, ByteWriter().u16(1).u8(0).zeros(5).toByteArray()) }

        val (progress, samples) = scanned(bytes)

        assertTrue(samples.isEmpty())
        assertTrue(progress.isComplete)
    }

    @Test
    fun aCycleCountCutsTheDataOfAListToTheRecordsThereAre() {
        // 25 records of data and a count of 20: the rest is padding.
        val data = records(25)
        val bytes = fileOf(20) { layout -> dataListChain(layout, chunks(data, 100).map { plainPart(layout, it) }) }

        assertReadsAll(bytes, 20)
    }

    @Test
    fun aListOfBlocksNotAllThereYetIsReadWhatIsThere() {
        val data = records(40)
        val bytes = fileOf(40) { layout -> dataListChain(layout, chunks(data, 120).map { plainPart(layout, it) }) }
        val source = GrowingSource(bytes, bytes.size - 300)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        val partial = runSuspending { scanner.advance() }
        assertTrue(sink.of(request).size in 1 until 40)
        assertTrue(!partial.isComplete)

        source.visible = bytes.size
        val done = runSuspending { scanner.advance() }
        assertTrue(done.isComplete)
        assertEquals(expected(40), sink.of(request))
    }

    @Test
    fun saysAHeaderOfListThatLinksToAnotherBlockIsDamaged() {
        val bytes = fileOf(0) { layout ->
            layout.block("HL", 1, ByteWriter().u16(1).u8(0).zeros(5).toByteArray()).also {
                it.links[0] =
                    layout.text("x")
            }
        }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("##DL"), failure.reason)
    }

    @Test
    fun saysAListThatLinksToABlockThatIsNoDataIsDamaged() {
        val bytes = fileOf(0) { layout -> dataList(layout, listOf(DataPart(layout.text("not data"), 8))) }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("##TX"), failure.reason)
    }

    @Test
    fun saysAListOfTheWrongKindOfDataIsDamaged() {
        // Signal data (SD) in a list of records.
        val bytes = fileOf(0) { layout -> dataList(layout, listOf(plainPart(layout, ByteArray(12), "SD"))) }

        assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysAListThatCountsMoreBlocksThanItHasLinksIsDamaged() {
        val data = records(4)
        val bytes = fileOf(4) { layout -> dataList(layout, listOf(plainPart(layout, data)), count = 9) }

        assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysAListWithAnEmptyLinkAmongItsBlocksIsDamaged() {
        val data = records(8)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(group(8)),
                        ByteArray(0),
                        dataBlock = { layout -> dataListChain(layout, chunks(data, 48).map { plainPart(layout, it) }) },
                    ),
                ),
                tamper = { blocks -> blocks.first { it.type == "DL" }.links[1] = null },
            ),
        )

        assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysABlockThatClaimsMoreBytesThanTheFileHoldsWithOthersAfterItIsDamaged() {
        val data = records(30)
        val bytes = fileOf(30) { layout ->
            val parts = chunks(data, 120).map { plainPart(layout, it) }
            parts[1].block.declaredLength = HUGE_BLOCK_LENGTH
            dataListChain(layout, parts)
        }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("more than the file holds"), failure.reason)
    }

    @Test
    fun aLastBlockThatClaimsMoreBytesThanTheFileHoldsIsReadToTheEndOfTheFileAndNotComplete() {
        val data = records(30)
        val bytes = fileOf(30) { layout ->
            val parts = chunks(data, 120).map { plainPart(layout, it) }
            parts.last().block.declaredLength = 1L shl TERABYTE_BITS
            dataListChain(layout, parts)
        }

        val (progress, samples) = scanned(bytes)

        assertEquals(expected(30), samples)
        assertNull(progress.problem)
        assertTrue(!progress.isComplete, "$progress")
    }

    @Test
    fun saysBlocksThatClaimMoreBytesTogetherThanANumberHoldsAreDamaged() {
        val data = records(30)
        val bytes = fileOf(30) { layout ->
            val parts = chunks(data, 120).map { plainPart(layout, it) }
            parts.last().block.declaredLength = HUGE_BLOCK_LENGTH
            dataListChain(layout, parts)
        }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("than any file holds"), failure.reason)
    }

    @Test
    fun saysAChainOfListsThatLoopsIsDamagedNotAHang() {
        val data = records(8)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(group(8)),
                        ByteArray(0),
                        dataBlock = { layout -> dataListChain(layout, chunks(data, 48).map { plainPart(layout, it) }, perList = 1) },
                    ),
                ),
                tamper = { blocks ->
                    val lists = blocks.filter { it.type == "DL" }
                    val last = lists.first { it.links[0] == null }
                    last.links[0] = lists.first { it !== last }
                },
            ),
        )

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("comes back"), failure.reason)
    }

    // An unfinished file, as a writer that is cut leaves it: a list not yet filled in, and a data block whose length is a header's.
    private fun unfinished(parts: Int, flags: Int, staleLast: Boolean, unfilled: Boolean): ByteArray {
        val data = records(10 * parts)
        return writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(group(0)),
                        ByteArray(0),
                        dataBlock = { layout ->
                            val blocks = chunks(data, 10 * 12).map { plainPart(layout, it) }
                            val first = dataList(layout, blocks.dropLast(1), capacity = parts - 1)
                            val last = dataList(layout, blocks.takeLast(1), capacity = 4, count = if (unfilled) 0 else 1)
                            first.links[0] = last
                            first
                        },
                    ),
                ),
                finalized = false,
                unfinalizedFlags = flags,
                tamper = { blocks ->
                    val lists = blocks.filter { it.type == "DL" }
                    val data = blocks.filter { it.type == "DT" }
                    if (unfilled) lists.last().links[1] = null
                    if (staleLast) data.last().declaredLength = TestBlock.HEADER_SIZE.toLong()
                },
            ),
        )
    }

    @Test
    fun anUnfinishedFileFindsTheBlocksOfItsLastListThatTheListDoesNotNameYet() {
        // The last list (flag 0x10) names none of its blocks yet and counts none: the block after it in the file is its.
        val bytes = unfinished(parts = 4, flags = 0x01 or 0x10, staleLast = false, unfilled = true)

        assertReadsAll(bytes, 40)
    }

    @Test
    fun anUnfinishedFileTakesTheLastBlockToTheEndOfTheFile() {
        // The last block's length is a header's (flag 0x04): its data go to the end of the file.
        val bytes = unfinished(parts = 4, flags = 0x01 or 0x04, staleLast = true, unfilled = false)

        assertReadsAll(bytes, 40, isComplete = false)
    }

    @Test
    fun anUnfinishedFileWithBothLastListAndLastBlockStaleIsReadToTheEnd() {
        val bytes = unfinished(parts = 3, flags = 0x01 or 0x04 or 0x10, staleLast = true, unfilled = true)

        assertReadsAll(bytes, 30, isComplete = false)
    }

    @Test
    fun anUnfinishedFileCutInsideItsLastBlockIsReadToTheLastWholeRecord() {
        val bytes = unfinished(parts = 4, flags = 0x01 or 0x04, staleLast = true, unfilled = false)
        val cut = bytes.copyOf(bytes.size - 5)

        val (progress, samples) = scanned(cut)

        assertEquals(expected(39), samples)
        assertNull(progress.problem)
    }

    @Test
    fun aListThatIsFilledInIsReadByItsLinksWhateverTheUnfinishedFlagsSay() {
        val bytes = unfinished(parts = 4, flags = 0x01 or 0x10, staleLast = false, unfilled = false)

        assertReadsAll(bytes, 40, isComplete = true)
    }

    private companion object {
        const val TERABYTE_BITS = 40
    }
}
