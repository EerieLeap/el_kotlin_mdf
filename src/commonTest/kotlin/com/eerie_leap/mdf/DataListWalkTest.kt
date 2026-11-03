package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How lists of data blocks are walked: a file cut in its lists says so, a list that names too many blocks or one twice is refused, a
 * list that is not filled in takes only blocks that are its own, and a walk the end of the file cut short goes on from where it was
 * cut, once each time the scan comes back, without inflating a block again.
 */
class DataListWalkTest {
    // ---- a scan cut short says so

    @Test
    fun aFinishedFileCutInItsListsDeclaresWhatItsListsNamedSoFarAndIsCutNotComplete() {
        val data = sortedRecords(40)
        val bytes = sortedFile(40) { layout -> dataListChain(layout, chunks(data, 120).map { plainPart(layout, it) }, perList = 2) }
        val source = GrowingSource(bytes, bytes.size - 300)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        val cut = runSuspending { scanner.advance() }
        source.visible = bytes.size
        val whole = runSuspending { scanner.advance() }

        assertTrue(cut.isCut)
        assertTrue(!cut.isComplete)
        val declared = checkNotNull(cut.bytesDeclared)
        assertTrue(declared in 1 until data.size, "$declared")
        assertTrue(!whole.isCut)
        assertTrue(whole.isComplete)
        assertEquals(data.size.toLong(), whole.bytesDeclared)
        assertEquals(sortedExpected(40), sink.of(request))
    }

    // ---- lists that name too many blocks

    @Test
    fun saysABlockNamedTwiceInOneListIsDamaged() {
        val bytes = sortedFile(3) { layout ->
            val part = plainPart(layout, sortedRecords(3))
            dataList(layout, listOf(part, part, part))
        }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanValues(open(bytes)) })

        assertTrue(failure.reason.contains("named twice"), failure.reason)
    }

    @Test
    fun saysABlockNamedInTwoListsOfAChainIsDamaged() {
        val bytes = sortedFile(3) { layout ->
            val part = plainPart(layout, sortedRecords(3))
            dataList(layout, listOf(part), next = dataList(layout, listOf(part)))
        }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanValues(open(bytes)) })

        assertTrue(failure.reason.contains("named twice"), failure.reason)
    }

    @Test
    fun refusesAListOfMoreBlocksThanAFileMayHaveBeforeReadingAnyOfThem() {
        val bytes = sortedFile(0) { layout ->
            val part = plainPart(layout, sortedRecords(1))
            dataList(layout, List(MAX_DATA_BLOCKS + 1) { part })
        }
        val source = StrictSource(bytes)
        val file = runSuspending { Mdf4Reader.open(source) }
        val before = source.reads

        val failure = failureOf { MdfScanner.create(file, file.groups.single(), emptyList(), CollectingSink()) }

        assertIs<MdfFailure.TooLarge>(failure)
        assertTrue(source.reads - before < FEW_READS, "${source.reads - before} reads")
    }

    // ---- blocks a list that is not filled in takes

    // The records of a second data group, to tell them from the first's: the value `1000 + i`.
    private fun otherRecords(count: Int): ByteArray {
        val out = ByteWriter()
        for (i in 0 until count) out.f64(i / 2.0).f32(1000f + i)
        return out.toByteArray()
    }

    // A file of data groups whose lists are not filled in: each names its first block, and the blocks after are the group's own.
    private fun unfilledFile(vararg datas: ByteArray, order: ((List<TestBlock>) -> List<TestBlock>)? = null): ByteArray = writeFile(
        FileSpec(
            datas.map { data ->
                DataGroupSpec(
                    0,
                    listOf(sortedGroup(0)),
                    ByteArray(0),
                    dataBlock = { layout -> dataList(layout, listOf(plainPart(layout, data)), capacity = 4, count = 0) },
                )
            },
            finalized = false,
            unfinalizedFlags = UNFILLED_FLAGS,
            reorder = order,
        ),
    )

    @Test
    fun anUnfilledListDoesNotTakeTheBlocksOfAnotherDataGroupThatFollowIt() {
        // The lists, then the data of the first group and the data of the second one after it.
        val bytes = unfilledFile(sortedRecords(10), otherRecords(10), order = { all ->
            all.filter { it.type != "DT" } + all.filter { it.type == "DT" }
        })
        val file = open(bytes)

        val (first, second) = file.groups.map { data ->
            val request = MdfChannelRequest(data.channelGroups.single().channels[1])
            val sink = CollectingSink()
            runSuspending { MdfScanner.create(file, data, listOf(request), sink).advance() }
            sink.of(request).map { it.value }
        }

        assertEquals(List(10) { it * 10.0 }, first)
        assertEquals(List(10) { 1000.0 + it }, second)
    }

    @Test
    fun anUnfilledListEndsAtAFollowingBlockThatIsNoBlockOfItsData() {
        // The last block names the length of its header alone: where the next block should begin there are the records, whose first
        // bytes spell "xxDT".
        val out = ByteWriter().f64(Double.fromBits(XXDT_TIME)).f32(0f)
        for (i in 1 until 10) out.f64(i / 2.0).f32(i * 10f)
        val bytes = sortedFile(0, finalized = false, flags = UNFILLED_FLAGS) { layout ->
            val part = plainPart(layout, out.toByteArray())
            part.block.declaredLength = TestBlock.HEADER_SIZE.toLong()
            dataList(layout, listOf(part), capacity = 4, count = 0)
        }

        val (progress, samples) = scanValues(open(bytes))

        assertNull(progress.problem)
        assertEquals(List(10) { it * 10.0 }, samples.map { it.value })
    }

    @Test
    fun anUnfilledListEndsAtAFollowingZippedBlockOfOtherData() {
        val bytes = sortedFile(0, finalized = false, flags = UNFILLED_FLAGS) { layout ->
            val list = dataList(layout, listOf(plainPart(layout, sortedRecords(10))), capacity = 4, count = 0)
            // A block no list names, after the list's: zipped signal data.
            zippedBlock(layout, ByteArray(40), zlibStored(ByteArray(40)), originalType = "SD")
            list
        }

        val (progress, samples) = scanValues(open(bytes))

        assertNull(progress.problem)
        assertEquals(List(10) { it * 10.0 }, samples.map { it.value })
    }

    // ---- a walk cut short goes on from where it was cut

    @Test
    fun aWalkCutShortGoesOnFromItsCutListOnceInAnAdvanceAndKeepsTheBlockItInflated() {
        // Six zipped blocks of 70,000 bytes (not a multiple of a record's 12, and past a page of the reader), each in a list of its own.
        val count = 35_000
        val bytes = sortedFile(count.toLong()) { layout ->
            dataListChain(layout, chunks(sortedRecords(count), 70_000).map { zippedPart(layout, it) }, perList = 1)
        }
        val lists = (0 until bytes.size - 4 step 8).filter { at ->
            bytes[at] == '#'.code.toByte() && bytes[at + 1] == '#'.code.toByte() && bytes[at + 2] == 'D'.code.toByte() &&
                bytes[at + 3] == 'L'.code.toByte()
        }
        assertEquals(6, lists.size)
        // The file ends inside the header of the fifth list.
        val source = CountingGrowingSource(bytes, lists[4] + 10)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
        runSuspending { scanner.advance() }

        repeat(STEPS) { _ ->
            source.visible += 1
            val before = source.reads.size
            runSuspending { scanner.advance() }
            val reads = source.reads.drop(before)
            assertTrue(reads.size <= FEW_READS, "${reads.size} reads in an advance")
            assertTrue(reads.none { it.second >= BIG_READ }, "a block was inflated again: $reads")
        }
        source.visible = bytes.size
        val done = runSuspending { scanner.advance() }

        assertTrue(done.isComplete)
        assertEquals(sortedExpected(count), sink.of(request))
    }

    private companion object {
        const val UNFILLED_FLAGS = 0x01 or 0x04 or 0x10
        const val XXDT_TIME = 0x3FF0_0000_5444_7878L
        const val FEW_READS = 6
        const val BIG_READ = 60_000
        const val STEPS = 5
    }
}
