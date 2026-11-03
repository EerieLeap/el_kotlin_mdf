package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Column storage (MDF 4.2) whose blocks of invalidation bytes are paired with the blocks of values they belong to: a link that is
 * empty makes its records valid, a list without invalidation blocks gives its records none of those of a later list, and a list the
 * end of a file cuts keeps what it names for when the rest arrives.
 */
class ColumnPairsTest {
    private val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val value = ChannelSpec("v", byteOffset = 8, invalidationBit = 0)

    // The records of column storage: the time `i / 4` and the value `3 i`; and the invalidation byte of each, set for every fifth.
    private fun values(range: IntRange): ByteArray {
        val out = ByteWriter()
        for (i in range) out.f64(i / 4.0).f32(i * 3f)
        return out.toByteArray()
    }

    private fun flags(range: IntRange): ByteArray = ByteArray(range.count()) { if ((range.first + it) % 5 == 0) 1 else 0 }

    private fun expected(count: Int, isInvalid: (Int) -> Boolean) = List(count) { Sample(it / 4.0, it * 3.0, isInvalid(it)) }

    private fun fileOf(count: Int, data: (BlockLayout) -> TestBlock): ByteArray {
        val group = GroupSpec(0, "g", listOf(master, value), 12, invalidationBytes = 1, cycleCount = count.toLong())
        val spec = FileSpec(listOf(DataGroupSpec(0, listOf(group), ByteArray(0), dataBlock = data)), versionText = "4.20", version = 420)
        return writeFile(spec)
    }

    // Two blocks of twenty records, in a list with the blocks of their invalidation bytes [invalidation], an empty link for a null.
    private fun twoBlocks(layout: BlockLayout, invalidation: List<ByteArray?>?): TestBlock {
        val parts = chunks(values(0 until 40), 12 * 20).map { plainPart(layout, it, "DV") }
        return columnList(layout, parts, invalidation?.map { bytes -> bytes?.let { plainPart(layout, it, "DI") } }, samples = 20)
    }

    @Test
    fun anEmptyInvalidationLinkMakesTheRecordsOfItsValuesBlockValid() {
        val bytes = fileOf(40) { layout ->
            twoBlocks(layout, listOf(flags(0 until 20), null))
        }

        val (progress, samples) = scanValues(open(bytes))

        assertEquals(expected(40) { it < 20 && it % 5 == 0 }, samples)
        assertNull(progress.problem)
        assertTrue(progress.isComplete)
    }

    @Test
    fun theInvalidationBlocksOfALaterListAreNotThoseOfTheValuesOfAnEarlierOne() {
        val bytes = fileOf(40) { layout ->
            val later = columnList(
                layout,
                listOf(plainPart(layout, values(20 until 40), "DV")),
                listOf(plainPart(layout, flags(20 until 40), "DI")),
                samples = 20,
            )
            columnList(layout, listOf(plainPart(layout, values(0 until 20), "DV")), null, samples = 20, next = later)
        }

        val (_, samples) = scanValues(open(bytes))

        assertEquals(expected(40) { it >= 20 && it % 5 == 0 }, samples)
    }

    @Test
    fun anOpenEndedValuesBlockWithoutAnInvalidationBlockIsReadToTheNextBlockWithItsRecordsValid() {
        val group = GroupSpec(0, "g", listOf(master, value), 12, invalidationBytes = 1)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(group),
                        ByteArray(0),
                        dataBlock = { layout ->
                            val first = plainPart(layout, values(0 until 5), "DV")
                            // The second block's length is a header's (a writer that was cut off), and no block of invalidation follows.
                            val second = plainPart(layout, values(5 until 10), "DV")
                            second.block.declaredLength = TestBlock.HEADER_SIZE.toLong()
                            columnList(layout, listOf(first, second), listOf(plainPart(layout, flags(0 until 5), "DI"), null), samples = 5)
                        },
                    ),
                ),
                finalized = false,
                unfinalizedFlags = STALE_LENGTH,
                versionText = "4.20",
                version = 420,
            ),
        )

        val (progress, samples) = scanValues(open(bytes))

        assertEquals(expected(10) { it == 0 }, samples)
        assertNull(progress.bytesDeclared)
    }

    @Test
    fun aDownloadThatHasTheHeaderOfTheFirstColumnListButNotItsLinksKeepsItsInvalidationBytesWhenTheListArrives() {
        val bytes = fileOf(40) { layout ->
            twoBlocks(layout, chunks(flags(0 until 40), 20))
        }
        val list = open(bytes).groups.single().dataLink.toInt()
        val source = GrowingSource(bytes, list + BlockFormat.HEADER_SIZE + 3)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        val early = runSuspending { scanner.advance() }
        source.visible = bytes.size
        val done = runSuspending { scanner.advance() }

        assertTrue(early.isCut)
        assertTrue(sink.of(request).isNotEmpty())
        assertEquals(expected(40) { it % 5 == 0 }, sink.of(request))
        assertTrue(done.isComplete)
        assertTrue(!done.isCut)
    }

    @Test
    fun aValuesBlockWhoseInvalidationBlockIsNotThereYetIsLeftOutUntilItIs() {
        val bytes = fileOf(40) { layout ->
            twoBlocks(layout, chunks(flags(0 until 40), 20))
        }
        val source = GrowingSource(bytes, bytes.size - 5)
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        runSuspending { scanner.advance() }
        val firstPass = sink.of(request).toList()
        source.visible = bytes.size
        runSuspending { scanner.advance() }

        assertTrue(firstPass.size < 40, "${firstPass.size}")
        assertEquals(firstPass, expected(firstPass.size) { it % 5 == 0 })
        assertEquals(expected(40) { it % 5 == 0 }, sink.of(request))
    }

    @Test
    fun saysAnInvalidationBlockThatHoldsOtherRecordsThanItsValuesBlockDamagedWhereOthersFollow() {
        val bytes = fileOf(40) { layout ->
            // The first invalidation block holds 15 records of the 20 of its values.
            twoBlocks(layout, chunks(flags(0 until 40), 20).mapIndexed { index, part -> if (index == 0) part.copyOf(15) else part })
        }

        val failure = failureOf { scanValues(open(bytes)) }

        assertTrue(failure is MdfFailure.Damaged && failure.reason.contains("holds 15 records"), "$failure")
    }

    private companion object {
        const val STALE_LENGTH = 0x01 or 0x04
    }
}
