package com.eerie_leap.mdf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BlockReaderTest {
    // 300 KB where each byte says where it is, so any misplaced copy shows.
    private val data = ByteArray(300_000) { (it * 31 + it / 256).toByte() }

    private class CountingSource(private val bytes: ByteArray) : MdfSource {
        var reads = 0

        override val size: Long get() = bytes.size.toLong()

        override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (position >= bytes.size) return 0
            // At most 5000 bytes a call, as a slow source gives, to make the reader ask again.
            val count = minOf(length, 5000, bytes.size - position.toInt())
            bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
            return count
        }
    }

    @Test
    fun readsBytesAcrossThePageBoundaries() {
        val reader = BlockReader(data.asMdfSource())
        val random = Random(3)

        repeat(500) {
            val position = random.nextInt(0, data.size - 1)
            val length = random.nextInt(1, minOf(70_000, data.size - position) + 1)

            assertContentEquals(
                data.copyOfRange(position, position + length),
                runSuspending {
                    reader.bytes(position.toLong(), length)
                },
                "$position+$length",
            )
        }
    }

    @Test
    fun readsASmallRangeAgainFromItsPage() {
        val source = CountingSource(data)
        val reader = BlockReader(source)

        runSuspending { reader.bytes(100, 24) }
        val filled = source.reads
        runSuspending {
            reader.bytes(200, 24)
            reader.bytes(65_000, 24)
            reader.bytes(100, 24)
        }

        // Everything after the first range was in the page it filled.
        assertEquals(filled, source.reads)
        runSuspending { reader.bytes(70_000, 24) }
        assertTrue(source.reads > filled)
    }

    @Test
    fun keepsOnReadingWhereASourceGivesLessThanItWasAsked() {
        val reader = BlockReader(CountingSource(data))

        assertContentEquals(data.copyOfRange(10, 200_010), runSuspending { reader.bytes(10, 200_000) })
        assertContentEquals(data.copyOfRange(60_000, 70_000), runSuspending { reader.bytes(60_000, 10_000) })
    }

    @Test
    fun saysWhereTheFileEndsWhenAReadReachesPastIt() {
        val reader = BlockReader(data.asMdfSource())

        val failure = failureOf { reader.bytes(data.size - 10L, 20) }

        assertEquals(MdfFailure.Truncated(data.size - 10L, data.size + 10L), failure)
        assertIs<MdfFailure.Truncated>(failureOf { reader.bytes(-1, 4) })
    }

    @Test
    fun readsWhatAFileGainsAfterAnEarlierShortRead() {
        val source = GrowingSource(data, 100_000)
        val reader = BlockReader(source)

        // The page that holds the end of what there is now is cached short.
        assertContentEquals(data.copyOfRange(99_000, 99_900), runSuspending { reader.bytes(99_000, 900) })
        assertIs<MdfFailure.Truncated>(failureOf { reader.bytes(99_900, 400) })

        source.visible = 200_000
        assertContentEquals(data.copyOfRange(99_900, 100_300), runSuspending { reader.bytes(99_900, 400) })
        assertContentEquals(data.copyOfRange(99_000, 99_900), runSuspending { reader.bytes(99_000, 900) })
    }

    @Test
    fun remembersTheOffsetsOfTheBlocksItReadsSorted() {
        var blocks: List<TestBlock> = emptyList()
        val spec = FileSpec(
            listOf(DataGroupSpec(1, listOf(GroupSpec(1, "g", listOf(ChannelSpec("v", unit = "V", comment = "c")), 4)), ByteArray(0))),
            tamper = { blocks = it },
        )
        val file = open(writeFile(spec))

        val offsets = file.blockOffsets

        assertContentEquals(offsets.sortedArray(), offsets)
        assertEquals(64L, offsets.first())
        // Every block of the file's structure was read; the data block was not.
        val read = offsets.toSet()
        assertTrue(blocks.filter { it.type != "DT" }.all { it.offset in read })
        assertFalse(blocks.first { it.type == "DT" }.offset in read)
    }
}
