package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataBlocksTest {
    private val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val value = ChannelSpec("v", byteOffset = 8)

    // `count` records of a group of record ID 1 (or none, for sorted data): the time `i / 2` and the value `10 i`.
    private fun records(count: Int, idSize: Int = 1): ByteArray {
        val out = ByteWriter()
        for (i in 0 until count) {
            if (idSize > 0) out.u8(1)
            out.f64(i / 2.0).f32(i * 10f)
        }
        return out.toByteArray()
    }

    private fun group(recordId: Long = 1, cycleCount: Long = 0) =
        GroupSpec(recordId, "g", listOf(master, value), 12, cycleCount = cycleCount)

    private fun scanned(bytes: ByteArray): Pair<MdfScanProgress, List<Sample>> {
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink).advance() }
        return progress to sink.of(request)
    }

    private fun expected(count: Int) = List(count) { Sample(it / 2.0, it * 10.0, false) }

    private fun zipped(
        plain: ByteArray,
        idSize: Int = 1,
        zipType: Int = ZIP_DEFLATE,
        columns: Int = 0,
        originalType: String = "DT",
        cycleCount: Long = 0,
        compress: (ByteArray) -> ByteArray = ::zlibStored,
    ): ByteArray {
        val stored = if (zipType == ZIP_TRANSPOSED_DEFLATE) transpose(plain, columns) else plain
        val dataGroup = DataGroupSpec(
            idSize,
            listOf(group(if (idSize == 0) 0 else 1, cycleCount)),
            plain,
            dataBlock = { layout -> zippedBlock(layout, plain, compress(stored), zipType, columns, originalType) },
        )
        return writeFile(FileSpec(listOf(dataGroup)))
    }

    @Test
    fun readsAZippedDataBlock() {
        val (progress, samples) = scanned(zipped(records(50)))

        assertEquals(expected(50), samples)
        assertTrue(progress.isComplete)
        assertNull(progress.problem)
        assertEquals(50L * 13, progress.bytesRead)
        assertEquals(50L * 13, progress.bytesDeclared)
    }

    @Test
    fun readsAZippedBlockOfRawDeflateWithNoZlibHeader() {
        val raw = { data: ByteArray -> zlibStored(data).let { it.copyOfRange(2, it.size - 4) } }

        assertEquals(expected(20), scanned(zipped(records(20), compress = raw)).second)
    }

    @Test
    fun readsAZippedBlockOfSeveralStoredBlocks() {
        // 6000 records take 78 000 bytes: more than the 65 535 a stored block of deflate holds.
        val (progress, samples) = scanned(zipped(records(6000)))

        assertEquals(expected(6000), samples)
        assertTrue(progress.isComplete)
    }

    @Test
    fun readsATransposedZippedBlockOfSortedData() {
        val plain = records(40, idSize = 0)
        val (progress, samples) = scanned(zipped(plain, idSize = 0, zipType = ZIP_TRANSPOSED_DEFLATE, columns = 12, cycleCount = 40))

        assertEquals(expected(40), samples)
        assertTrue(progress.isComplete)
    }

    @Test
    fun aTransposedBlockEndingInAnIncompleteRecordKeepsThatTail() {
        val plain = records(10, idSize = 0) + byteArrayOf(1, 2, 3, 4, 5)
        val (progress, samples) = scanned(zipped(plain, idSize = 0, zipType = ZIP_TRANSPOSED_DEFLATE, columns = 12, cycleCount = 10))

        assertEquals(expected(10), samples)
        assertNull(progress.problem)
    }

    @Test
    fun aZippedBlockThatIsNotAllThereYetIsReadWhenItIs() {
        val bytes = zipped(records(30))
        val source = GrowingSource(bytes, bytes.size - 10)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        val waiting = runSuspending { scanner.advance() }
        assertEquals(0L, waiting.bytesAvailable)
        assertFalse(waiting.isComplete)

        source.visible = bytes.size
        val done = runSuspending { scanner.advance() }
        assertTrue(done.isComplete)
        assertEquals(expected(30), sink.of(request))
    }

    @Test
    fun saysAZippedBlockThatDoesNotInflateIsDamaged() {
        val bytes = zipped(records(20), compress = { data -> zlibStored(data).also { it[it.size - 1] = 0 } })
        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("doesn't inflate"), failure.reason)
        val rubbish = zipped(records(20), compress = { ByteArray(40) { 0x55 } })
        assertIs<MdfFailure.Damaged>(failureOf { scanned(rubbish) })
        val short = zipped(records(20), compress = { data -> zlibStored(data).copyOf(30) })
        assertIs<MdfFailure.Damaged>(failureOf { scanned(short) })
    }

    @Test
    fun refusesAZippedBlockOfMoreThanItWillInflate() {
        // The block says its data inflate to 100 MiB: the field is at byte 8 of the block's own fields.
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        1,
                        listOf(group()),
                        ByteArray(0),
                        dataBlock = { layout ->
                            zippedBlock(layout, ByteArray(10), zlibStored(ByteArray(10))).also {
                                ByteWriter().u64(100L * 1024 * 1024).toByteArray().copyInto(it.data, 8)
                            }
                        },
                    ),
                ),
            ),
        )

        assertIs<MdfFailure.TooLarge>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysWhichDataItDoesntReadYet() {
        for (originalType in listOf("SD", "RD")) {
            val failure = assertIs<MdfFailure.Unsupported>(failureOf { scanned(zipped(records(2), originalType = originalType)) })
            assertTrue(failure.what.contains(originalType), failure.what)
        }
        assertIs<MdfFailure.Unsupported>(failureOf { scanned(zipped(records(2), zipType = 7)) })

        // A list of data blocks: for the next version of the reader.
        val list = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(1, listOf(group()), ByteArray(0), dataBlock = { layout -> layout.block("DL", 1, ByteArray(24)) }),
                ),
            ),
        )
        val failure = assertIs<MdfFailure.Unsupported>(failureOf { scanned(list) })
        assertTrue(failure.what.contains("##DL"), failure.what)
        assertTrue(failure.reason.isNotEmpty())
    }

    @Test
    fun aDataLinkToAnyOtherBlockIsUnsupportedNotRead() {
        val text = writeFile(
            FileSpec(
                listOf(DataGroupSpec(1, listOf(group()), ByteArray(0), dataBlock = { layout -> layout.text("not data") })),
            ),
        )

        assertIs<MdfFailure.Unsupported>(failureOf { scanned(text) })
    }

    @Test
    fun aDataGroupWithoutDataScansToNothingAndIsComplete() {
        val bytes =
            writeFile(
                FileSpec(listOf(DataGroupSpec(1, listOf(group()), records(3))), tamper = {
                    it.first { b -> b.type == "DG" }.links[2] =
                        null
                }),
            )

        val (progress, samples) = scanned(bytes)

        assertTrue(samples.isEmpty())
        assertTrue(progress.isComplete)
        assertEquals(0L, progress.bytesRead)
    }

    @Test
    fun onlyTheLastDataBlockOfAnUnfinishedFileReachesToTheEndOfTheFile() {
        // Two data groups, the first with its data block closed, the second as a power loss leaves it.
        val first = DataGroupSpec(1, listOf(group(1)), records(3))
        val second = DataGroupSpec(1, listOf(group(1)), records(5), dataBlockLength = TestBlock.HEADER_SIZE.toLong())
        val bytes = writeFile(FileSpec(listOf(first, second), finalized = false, unfinalizedFlags = 0x04))
        val file = open(bytes)

        val counts = file.groups.map { dataGroup ->
            val request = MdfChannelRequest(dataGroup.channelGroups.single().channels[1])
            val sink = CollectingSink()
            runSuspending { MdfScanner.create(file, dataGroup, listOf(request), sink).advance() }
            sink.of(request).size
        }

        assertEquals(listOf(3, 5), counts)
    }

    @Test
    fun readsASortedFileOfSeveralDataGroupsOneAtATime() {
        val groups = (1..3).map { n ->
            DataGroupSpec(0, listOf(GroupSpec(0, "g$n", listOf(master, value), 12, cycleCount = n.toLong())), records(n, idSize = 0))
        }
        val file = open(writeFile(FileSpec(groups)))

        val counts = file.groups.map { dataGroup ->
            val request = MdfChannelRequest(dataGroup.channelGroups.single().channels[1])
            val sink = CollectingSink()
            runSuspending { MdfScanner.create(file, dataGroup, listOf(request), sink).advance() }
            sink.of(request).size
        }

        assertEquals(listOf(1, 2, 3), counts)
    }
}
