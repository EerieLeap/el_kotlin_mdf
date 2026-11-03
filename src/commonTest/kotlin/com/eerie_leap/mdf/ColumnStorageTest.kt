package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MDF 4.2's column storage: the records' values in DV blocks and their invalidation bytes in DI blocks, listed by LD blocks. */
class ColumnStorageTest {
    private val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val value = ChannelSpec("v", byteOffset = 8, invalidationBit = 0)

    // The values of record i: the time `i / 4` and the value `3 i`; and its invalidation byte, set (bit 0) for every fifth.
    private fun values(range: IntRange): ByteArray {
        val out = ByteWriter()
        for (i in range) out.f64(i / 4.0).f32(i * 3f)
        return out.toByteArray()
    }

    private fun flags(range: IntRange): ByteArray = ByteArray(range.count()) { if ((range.first + it) % 5 == 0) 1 else 0 }

    private fun expected(count: Int) = List(count) { Sample(it / 4.0, it * 3.0, it % 5 == 0) }

    private fun fileOf(count: Int, version: String = "4.20", data: (BlockLayout) -> TestBlock): ByteArray {
        val group = GroupSpec(0, "g", listOf(master, value), 12, invalidationBytes = 1, cycleCount = count.toLong())
        val spec = FileSpec(
            listOf(DataGroupSpec(0, listOf(group), ByteArray(0), dataBlock = data)),
            versionText = version,
            version = version.replace(".", "").toInt(),
        )
        return writeFile(spec)
    }

    private fun scanned(bytes: ByteArray): Pair<MdfScanProgress, List<Sample>> {
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink).advance() }
        return progress to sink.of(request)
    }

    @Test
    fun readsValuesAndInvalidationBytesStoredApart() {
        val bytes = fileOf(40) { layout ->
            val dv = chunks(values(0 until 40), 12 * 16).map { plainPart(layout, it, "DV") }
            val di = chunks(flags(0 until 40), 16).map { plainPart(layout, it, "DI") }
            columnList(layout, dv, di, samples = 16)
        }

        val (progress, samples) = scanned(bytes)

        assertEquals(expected(40), samples)
        assertTrue(progress.isComplete)
        assertNull(progress.problem)
    }

    @Test
    fun readsColumnsInBlocksOfUnequalNumbersOfRecords() {
        val bytes = fileOf(30) { layout ->
            // Each value block and the invalidation block with its records' bytes: 1, 7, 3, 10, 9 records.
            var from = 0
            val pairs = listOf(1, 7, 3, 10, 9).map { count ->
                val range = from until from + count
                from += count
                plainPart(layout, values(range), "DV") to plainPart(layout, flags(range), "DI")
            }
            columnList(layout, pairs.map { it.first }, pairs.map { it.second }, equal = false, recordBytes = 12)
        }

        assertEquals(expected(30), scanned(bytes).second)
    }

    @Test
    fun readsZippedColumnsAndAChainOfLists() {
        val bytes = fileOf(64) { layout ->
            val dv = chunks(values(0 until 64), 12 * 20).map { zippedPart(layout, it, "DV") }
            val di = chunks(flags(0 until 64), 20).map { zippedPart(layout, it, "DI") }
            val second = columnList(layout, dv.drop(2), di.drop(2), samples = 20)
            columnList(layout, dv.take(2), di.take(2), samples = 20, next = second)
        }

        assertEquals(expected(64), scanned(bytes).second)
    }

    @Test
    fun readsTransposedZippedColumns() {
        val bytes = fileOf(25) { layout ->
            val dv = listOf(zippedPart(layout, values(0 until 25), "DV", zipType = ZIP_TRANSPOSED_DEFLATE, columns = 12))
            val di = listOf(zippedPart(layout, flags(0 until 25), "DI", zipType = ZIP_TRANSPOSED_DEFLATE, columns = 1))
            columnList(layout, dv, di)
        }

        assertEquals(expected(25), scanned(bytes).second)
    }

    @Test
    fun valuesWithoutInvalidationBlocksAreAllValid() {
        val bytes = fileOf(12) { layout -> columnList(layout, listOf(plainPart(layout, values(0 until 12), "DV"))) }

        val (progress, samples) = scanned(bytes)

        assertEquals(List(12) { Sample(it / 4.0, it * 3.0, false) }, samples)
        assertTrue(progress.isComplete)
    }

    @Test
    fun readsOnlyTheRecordsBothColumnsHold() {
        // Twenty values and fifteen invalidation bytes: the last five records aren't whole.
        val bytes = fileOf(20) { layout ->
            columnList(layout, listOf(plainPart(layout, values(0 until 20), "DV")), listOf(plainPart(layout, flags(0 until 15), "DI")))
        }

        assertEquals(expected(15), scanned(bytes).second)
    }

    @Test
    fun readsAColumnListOfNoBlocksAsNoData() {
        val bytes = fileOf(0) { layout -> columnList(layout, emptyList()) }

        val (progress, samples) = scanned(bytes)

        assertTrue(samples.isEmpty())
        assertTrue(progress.isComplete)
    }

    @Test
    fun readsMdf43AndMdf42FilesTheSameWay() {
        for (version in listOf("4.20", "4.30")) {
            val bytes = fileOf(10, version) { layout ->
                columnList(layout, listOf(plainPart(layout, values(0 until 10), "DV")), listOf(plainPart(layout, flags(0 until 10), "DI")))
            }

            assertEquals(expected(10), scanned(bytes).second, version)
        }
    }

    @Test
    fun refusesColumnStorageOfAnUnsortedDataGroup() {
        val group = GroupSpec(1, "g", listOf(master, value), 12, invalidationBytes = 1)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        1,
                        listOf(group),
                        ByteArray(0),
                        dataBlock = { layout -> columnList(layout, listOf(plainPart(layout, values(0 until 3), "DV"))) },
                    ),
                ),
            ),
        )

        assertIs<MdfFailure.Unsupported>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysAColumnListThatCountsMoreBlocksThanItHasLinksIsDamaged() {
        val bytes = fileOf(5) { layout -> columnList(layout, listOf(plainPart(layout, values(0 until 5), "DV")), count = 7) }

        assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysAColumnListOfTheWrongKindOfBlockIsDamaged() {
        // A DT in a list of values, which holds DV.
        val bytes = fileOf(5) { layout -> columnList(layout, listOf(plainPart(layout, values(0 until 5), "DT"))) }

        assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })
    }

    @Test
    fun saysAValueBlockThatClaimsMoreBytesThanTheFileHoldsWithOthersAfterItIsDamagedNotAnOutOfMemory() {
        val bytes = fileOf(40) { layout ->
            val dv = chunks(values(0 until 40), 12 * 16).map { plainPart(layout, it, "DV") }
            val di = chunks(flags(0 until 40), 16).map { plainPart(layout, it, "DI") }
            dv[1].block.declaredLength = HUGE_BLOCK_LENGTH
            columnList(layout, dv, di, samples = 16)
        }

        val failure = assertIs<MdfFailure.Damaged>(failureOf { scanned(bytes) })

        assertTrue(failure.reason.contains("more than the file holds"), failure.reason)
    }

    @Test
    fun anUnfinishedFileTakesItsLastColumnBlocksToTheEndOfTheFile() {
        val group = GroupSpec(0, "g", listOf(master, value), 12, invalidationBytes = 1)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(group),
                        ByteArray(0),
                        dataBlock = { layout ->
                            columnList(
                                layout,
                                listOf(plainPart(layout, values(0 until 24), "DV")),
                                listOf(plainPart(layout, flags(0 until 24), "DI")),
                            )
                        },
                    ),
                ),
                finalized = false,
                unfinalizedFlags = 0x01 or 0x04,
                versionText = "4.20",
                version = 420,
                tamper = { blocks ->
                    blocks.filter { it.type == "DV" || it.type == "DI" }.forEach { it.declaredLength = TestBlock.HEADER_SIZE.toLong() }
                },
            ),
        )

        assertEquals(expected(24), scanned(bytes).second)
    }
}
