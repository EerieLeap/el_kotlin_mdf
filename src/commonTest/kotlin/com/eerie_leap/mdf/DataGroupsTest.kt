package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One file of several data groups, sorted and not, with record IDs of every width and data that is plain, in lists of blocks that cut
 * the records anywhere, or zipped: each group reads to the values it was written with, and none takes another's.
 */
class DataGroupsTest {
    private val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)

    // What each channel is to give, by its name, in the order it was written.
    private val expected = LinkedHashMap<String, MutableList<Sample>>()

    // A channel group of an unsorted data group: its record ID, the factor of its values and whether its records end in an invalidation
    // byte (the even ones are invalid).
    private class Kind(val name: String, val id: Long, val scale: Float, val hasInvalidation: Boolean = false)

    private class Written(val bytes: ByteArray, val groups: List<GroupSpec>)

    private fun expect(name: String, n: Int, scale: Float, isInvalid: Boolean = false) {
        expected.getOrPut(name) { ArrayList() }.add(Sample(n / 4.0, (n * scale).toDouble(), isInvalid))
    }

    private fun sortedGroup(name: String, count: Int) =
        GroupSpec(0, name, listOf(time, ChannelSpec(name, byteOffset = 8)), 12, cycleCount = count.toLong())

    private fun unsortedGroup(kind: Kind, count: Int) = GroupSpec(
        kind.id,
        kind.name,
        listOf(time, ChannelSpec(kind.name, byteOffset = 8, invalidationBit = if (kind.hasInvalidation) 0 else null)),
        12,
        invalidationBytes = if (kind.hasInvalidation) 1 else 0,
        cycleCount = count.toLong(),
    )

    // Sorted records: the time and the value.
    private fun sortedRecords(name: String, count: Int, scale: Float): ByteArray {
        val out = ByteWriter()
        for (n in 0 until count) {
            out.f64(n / 4.0).f32(n * scale)
            expect(name, n, scale)
        }
        return out.toByteArray()
    }

    // [total] unsorted records of the groups [a] and [b], each starting with its group's ID in [idSize] bytes; [isB] says which are b's.
    private fun unsortedRecords(
        idSize: Int,
        a: Kind,
        b: Kind,
        total: Int,
        isB: (Int) -> Boolean,
    ): Written {
        val out = ByteWriter()
        val counts = intArrayOf(0, 0)
        for (i in 0 until total) {
            val which = if (isB(i)) 1 else 0
            val kind = if (which == 1) b else a
            val n = counts[which]++
            for (byte in 0 until idSize) out.u8((kind.id shr (Byte.SIZE_BITS * byte)).toInt() and BYTE_MASK)
            out.f64(n / 4.0).f32(n * kind.scale)
            val isInvalid = if (kind.hasInvalidation) n % 2 == 0 else null
            if (isInvalid != null) out.u8(if (isInvalid) 1 else 0)
            expect(kind.name, n, kind.scale, isInvalid == true)
        }
        return Written(out.toByteArray(), listOf(unsortedGroup(a, counts[0]), unsortedGroup(b, counts[1])))
    }

    // Sorted, one plain block.
    private fun plainDataGroup() = DataGroupSpec(0, listOf(sortedGroup("plain", PLAIN)), sortedRecords("plain", PLAIN, 1f))

    // Sorted, transposed and zipped blocks of twenty records and a bit, in lists of three under a header.
    private fun transposedDataGroup(): DataGroupSpec {
        val data = sortedRecords("transposed", TRANSPOSED, 2f)
        return DataGroupSpec(
            0,
            listOf(sortedGroup("transposed", TRANSPOSED)),
            ByteArray(0),
            dataBlock = { layout ->
                val parts = chunks(data, RECORD_BYTES * TRANSPOSED_BLOCK_RECORDS + 5).map {
                    zippedPart(layout, it, zipType = ZIP_TRANSPOSED_DEFLATE, columns = RECORD_BYTES)
                }
                headerList(layout, dataListChain(layout, parts, perList = 3), ZIP_TRANSPOSED_DEFLATE)
            },
        )
    }

    // Unsorted, IDs of 2 bytes: three records of the first group for one of the second, in blocks of 37 bytes that cut the IDs apart.
    private fun twoByteDataGroup(): DataGroupSpec {
        val written = unsortedRecords(2, Kind("two_a", TWO_BYTE_ID_A, 3f), Kind("two_b", TWO_BYTE_ID_B, -1f), TWO_BYTE) { it % 4 == 3 }
        return DataGroupSpec(
            2,
            written.groups,
            ByteArray(0),
            dataBlock = { layout ->
                dataListChain(layout, chunks(written.bytes, ODD_BLOCK).map { plainPart(layout, it) }, perList = 4, equal = false)
            },
        )
    }

    // Unsorted, IDs of 8 bytes that reach past 32 bits, the first group with invalidation bytes, in zipped blocks under a header.
    private fun eightByteDataGroup(): DataGroupSpec {
        val first = Kind("eight_a", EIGHT_BYTE_ID_A, 7f, hasInvalidation = true)
        val written = unsortedRecords(8, first, Kind("eight_b", EIGHT_BYTE_ID_B, 5f), EIGHT_BYTE) { it % 3 == 0 }
        return DataGroupSpec(
            8,
            written.groups,
            ByteArray(0),
            dataBlock = { layout ->
                val parts = chunks(written.bytes, ZIPPED_BLOCK).map { zippedPart(layout, it) }
                headerList(layout, dataListChain(layout, parts, perList = 3, equal = false))
            },
        )
    }

    // Unsorted, IDs of 4 bytes with the top bit set, one plain block.
    private fun fourByteDataGroup(): DataGroupSpec {
        val written =
            unsortedRecords(4, Kind("four_a", FOUR_BYTE_ID_A, 9f), Kind("four_b", FOUR_BYTE_ID_B, 0.5f), FOUR_BYTE) { it % 5 == 4 }
        return DataGroupSpec(4, written.groups, written.bytes)
    }

    private fun fileOf(): ByteArray = writeFile(
        FileSpec(listOf(plainDataGroup(), transposedDataGroup(), twoByteDataGroup(), eightByteDataGroup(), fourByteDataGroup())),
    )

    @Test
    fun readsEveryDataGroupOfOneFileWhateverItsRecordIdsAndItsBlocks() {
        val file = open(fileOf())

        assertTrue(file.issues.isEmpty(), "${file.issues}")
        assertEquals(listOf(0, 0, 2, 8, 4), file.groups.map { it.recordIdSize })
        val got = LinkedHashMap<String, List<Sample>>()
        for (data in file.groups) {
            val requests = data.channelGroups.flatMap { group ->
                group.channels.filter { it.isNumeric && it !== group.master }.map { MdfChannelRequest(it) }
            }
            val sink = CollectingSink()
            val progress = runSuspending { MdfScanner.create(file, data, requests, sink).advance() }

            assertNull(progress.problem, "${data.channelGroups.map { it.name }}")
            assertTrue(progress.isComplete, "${data.channelGroups.map { it.name }}")
            for (request in requests) got[request.channel.name] = sink.of(request)
        }

        assertEquals(expected.keys.sorted(), got.keys.sorted())
        for ((name, want) in expected) assertEquals(want, got.getValue(name), name)
        assertEquals(listOf(PLAIN, TRANSPOSED), listOf("plain", "transposed").map { got.getValue(it).size })
    }

    @Test
    fun readsOneGroupOfAnUnsortedDataGroupWithoutTheOthersRecordsGettingInTheWay() {
        val file = open(fileOf())
        val data = file.groups[EIGHT_BYTE_GROUP]
        val only = data.channelGroups.first { it.name == "eight_b" }
        val request = MdfChannelRequest(only.channels.first { it.name == "eight_b" })
        val sink = CollectingSink()

        val progress = runSuspending { MdfScanner.create(file, data, listOf(request), sink).advance() }

        assertNull(progress.problem)
        assertTrue(progress.isComplete)
        assertEquals(expected.getValue("eight_b"), sink.of(request))
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val RECORD_BYTES = 12
        const val PLAIN = 20
        const val TRANSPOSED = 70
        const val TRANSPOSED_BLOCK_RECORDS = 20
        const val TWO_BYTE = 40
        const val EIGHT_BYTE = 36
        const val FOUR_BYTE = 25
        const val EIGHT_BYTE_GROUP = 3
        const val ODD_BLOCK = 37
        const val ZIPPED_BLOCK = 53
        const val TWO_BYTE_ID_A = 300L
        const val TWO_BYTE_ID_B = 7L
        const val EIGHT_BYTE_ID_A = 0x0102030405L
        const val EIGHT_BYTE_ID_B = 2L
        const val FOUR_BYTE_ID_A = 0xDEADBEEFL
        const val FOUR_BYTE_ID_B = 1L
    }
}
