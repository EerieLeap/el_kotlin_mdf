package com.eerie_leap.mdf

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first of the defects a second review found, each tested to fail before its fix: the headers of data blocks that claim links, a
 * source that is asked for a read that cannot be and one that fails. The others are tested where they belong: column storage whose
 * invalidation blocks are not those of their values in [ColumnPairsTest], arrays stored the other way round and arrays whose axes
 * are read before they are refused in [ArraysTest], a scan that is cut short, lists that name too many blocks or take blocks that
 * are not theirs, and a list walked again and again as a file grows in [DataListWalkTest], and the end of the last block of an
 * unfinished file and a scan that waits for ever on a file that will not grow in [OpenEndsTest].
 */
class SecondReviewFixesTest {
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

    private fun scannerOf(file: MdfFile, sink: CollectingSink = CollectingSink()): MdfScanner {
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        return runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
    }

    private fun samplesOf(file: MdfFile): Pair<MdfScanProgress, List<Sample>> {
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
        return runSuspending { scanner.advance() } to sink.of(request)
    }

    /** A source that serves [bytes] and, once [fails] is set, throws [failure] at every read. */
    private class FailingSource(private val bytes: ByteArray, private val failure: Exception) : MdfSource {
        var fails = false

        override val size: Long get() = bytes.size.toLong()

        override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (fails) throw failure
            if (position >= bytes.size) return 0
            val count = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
            return count
        }
    }

    // ---- 1: the headers of data blocks

    @Test
    fun aDataBlockWhoseLinkCountDoesNotFitAnIntIsDamagedAndNoReadIsAskedBeforeTheFile() {
        // 2^31 links in a block of 2^34 bytes: the count passes the room the length leaves, and as an Int it is negative.
        val bytes = fileOf(5) { layout ->
            layout.block("DT", 0, records(5)).also {
                it.declaredLength = 0x4_0000_0018L
                it.declaredLinkCount = 0x8000_0000L
            }
        }
        val file = open(bytes)

        val failure = failureOf { MdfScanner.create(file, file.groups.single(), emptyList(), CollectingSink()) }

        assertIs<MdfFailure.Damaged>(failure)
    }

    @Test
    fun aDataBlockWithLinksHoldsItsDataAfterThemAndNothingElse() {
        val data = records(5)
        val file = open(fileOf(5) { layout -> layout.block("DT", 2, data) })

        val (progress, samples) = samplesOf(file)

        assertEquals(data.size.toLong(), progress.bytesDeclared)
        assertEquals(expected(5), samples)
    }

    @Test
    fun aZippedBlockWithLinksHasItsFieldsAfterThemAndInflatesFromThere() {
        val data = records(5)
        val file = open(fileOf(5) { layout -> zippedBlock(layout, data, zlibStored(data), links = 3) })

        val (progress, samples) = samplesOf(file)

        assertEquals(expected(5), samples)
        assertEquals(data.size.toLong(), progress.bytesDeclared)
        assertTrue(progress.isComplete)
    }

    @Test
    fun aSourceThatFailsEndsTheScanWithAFailureNotWithItsOwnException() {
        val bytes = fileOf(40) { layout -> dataListChain(layout, chunks(records(40), 120).map { plainPart(layout, it) }) }
        val source = FailingSource(bytes, IllegalStateException("the disk went"))
        val file = runSuspending { Mdf4Reader.open(source) }
        val scanner = scannerOf(file)
        source.fails = true

        val progress = runSuspending { scanner.advance() }

        val problem = assertIs<MdfFailure.NotRead>(progress.problem)
        assertTrue(problem.message.orEmpty().contains("the disk went"), problem.description)
    }

    @Test
    fun aSourceThatFailsWhileOpeningAFileIsAnMdfExceptionToo() {
        val source = FailingSource(fileOf(5) { layout -> layout.block("DT", 0, records(5)) }, IllegalArgumentException("bad place"))
        source.fails = true

        assertIs<MdfFailure.NotRead>(failureOf { Mdf4Reader.open(source) })
    }

    @Test
    fun cancellationFromASourceIsNotAFailureOfTheScan() {
        val bytes = fileOf(5) { layout -> layout.block("DT", 0, records(5)) }
        val source = FailingSource(bytes, CancellationException("stop"))
        val file = runSuspending { Mdf4Reader.open(source) }
        val scanner = scannerOf(file)
        source.fails = true

        assertFailsWith<CancellationException> { runSuspending { scanner.advance() } }
    }

    @Test
    fun theReaderNeverAsksTheSourceForAReadThatCannotBe() {
        val reader = BlockReader(StrictSource(records(5)))

        failureOf { reader.bytes(-8, 4) }
        assertIs<MdfFailure.Damaged>(failureOf { reader.bytes(0, -1) })
        assertNull(runCatching { runSuspending { reader.readInto(0, ByteArray(4), 0, 0) } }.exceptionOrNull())
        assertIs<MdfFailure.Damaged>(failureOf { reader.readInto(-1, ByteArray(4), 0, 4) })
    }
}
