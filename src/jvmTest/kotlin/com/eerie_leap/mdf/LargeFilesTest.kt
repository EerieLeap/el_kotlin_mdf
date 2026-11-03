package com.eerie_leap.mdf

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** A source over a file on a disk that notes what was read of it. */
internal class CountingFileSource(private val file: RandomAccessFile) : MdfSource {
    val ranges = ArrayList<LongRange>()
    var bytesRead = 0L
        private set
    private val fileSize = file.length()

    override val size: Long get() = fileSize

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        file.seek(position)
        val count = file.read(into, offset, length).coerceAtLeast(0)
        if (count > 0) {
            ranges.add(position until position + count)
            bytesRead += count
        }
        return count
    }
}

/** Files that aren't small: thousands of channels and many groups open quickly, and a scan reads only the groups it is asked for. */
class LargeFilesTest {
    private fun channels(group: Int, count: Int): List<ChannelSpec> = List(count) { c ->
        ChannelSpec(
            "ch_${group}_$c",
            byteOffset = c * 4,
            unit = "u$c",
            comment = "comment of channel $c in group $group",
            conversion = if (c % 10 == 0) ConversionSpec.linear(1.0, 2.0) else null,
            source = SourceSpec("src$group"),
        )
    }

    private fun withFile(bytes: ByteArray, test: (File) -> Unit) {
        val temp = File.createTempFile("mdf-large", ".mf4")
        try {
            temp.writeBytes(bytes)
            test(temp)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun opensTwoHundredGroupsOfAHundredChannelsQuickly() {
        val groups = (0 until GROUPS).map { g ->
            DataGroupSpec(0, listOf(GroupSpec(0, "group$g", channels(g, CHANNELS), CHANNELS * 4, cycleCount = 1)), ByteArray(CHANNELS * 4))
        }
        withFile(writeFile(FileSpec(groups))) { temp ->
            RandomAccessFile(temp, "r").use { raf ->
                val source = CountingFileSource(raf)
                val start = TimeSource.Monotonic.markNow()

                val file = runSuspending { Mdf4Reader.open(source) }

                val took = start.elapsedNow()
                println(
                    "OPEN ${GROUPS * CHANNELS} channels of a ${temp.length()} byte file: $took, ${source.ranges.size} reads of ${source.bytesRead} bytes",
                )
                assertEquals(GROUPS * CHANNELS, file.channelGroups.sumOf { it.channels.size })
                assertEquals(GROUPS, file.groups.size)
                assertTrue(took.inWholeMilliseconds < MAX_OPEN_MILLISECONDS, "took $took")
                // The reader reads a page at a time of the metadata, not byte by byte.
                assertTrue(source.ranges.size < temp.length() / 4096, "${source.ranges.size} reads")
            }
        }
    }

    @Test
    fun aScanReadsTheDataOfTheGroupsItIsAskedForAndNoOther() {
        val records = RECORDS
        val dataGroups = (0 until DATA_GROUPS).map { g ->
            val data = ByteWriter()
            for (i in 0 until records) data.f64(i * 0.001).f32(i.toFloat()).f32(g.toFloat())
            val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
            val group = GroupSpec(
                0,
                "g$g",
                listOf(master, ChannelSpec("v", byteOffset = 8), ChannelSpec("w", byteOffset = 12)),
                RECORD_BYTES,
                cycleCount = records.toLong(),
            )
            DataGroupSpec(0, listOf(group), data.toByteArray())
        }
        val wanted = 7
        withFile(writeFile(FileSpec(dataGroups))) { temp ->
            RandomAccessFile(temp, "r").use { raf ->
                val source = CountingFileSource(raf)
                val file = runSuspending { Mdf4Reader.open(source) }
                val readByOpen = source.bytesRead

                // The pages the reader keeps of the metadata reach into the data before and after it, a page or two at the most.
                val dataLength = records * RECORD_BYTES.toLong()
                val middles = file.groups.indices.associateWith { middleOf(dataRange(file, it, dataLength)) }
                val request = MdfChannelRequest(file.groups[wanted].channelGroups.single().channels[1])
                val sink = CollectingSink()
                runSuspending { MdfScanner.create(file, file.groups[wanted], listOf(request), sink).advance() }

                println(
                    "SCAN of 1 of $DATA_GROUPS groups of a ${temp.length()} byte file: open read $readByOpen bytes, the scan ${source.bytesRead - readByOpen}",
                )
                assertEquals(List(records) { it.toDouble() }, sink.of(request).map { it.value })
                assertTrue(readByOpen < temp.length() / 4, "opening read $readByOpen of ${temp.length()} bytes")
                for ((index, middle) in middles) {
                    assertEquals(index == wanted, source.ranges.any { middle.overlaps(it) }, "the data of group $index")
                }
                assertTrue(source.bytesRead - readByOpen < records * RECORD_BYTES * 2L)
            }
        }
    }

    // Where the data of the group at [index] is in the file: the first byte after the block's header.
    private fun dataRange(file: MdfFile, index: Int, length: Long): LongRange {
        val start = file.groups[index].dataLink + BlockFormat.HEADER_SIZE
        return start until start + length
    }

    // The data without the pages at its ends, which the pages of the metadata beside it may have taken.
    private fun middleOf(range: LongRange): LongRange = (range.first + DEEP)..(range.last - DEEP)

    private fun LongRange.overlaps(other: LongRange) = first <= other.last && other.first <= last

    private companion object {
        const val GROUPS = 200
        const val CHANNELS = 100
        const val DATA_GROUPS = 12
        const val RECORDS = 65_536
        const val RECORD_BYTES = 16
        const val DEEP = 2L * 64 * 1024
        const val MAX_OPEN_MILLISECONDS = 10_000
    }
}
