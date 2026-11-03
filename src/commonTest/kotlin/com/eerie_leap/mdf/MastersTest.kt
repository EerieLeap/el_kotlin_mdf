package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Masters that are not the time in the group's own records: in another channel group, an angle, a distance, an index, virtual. */
class MastersTest {
    private val time = ChannelSpec("time", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1, unit = "s")
    private val value = ChannelSpec("v", byteOffset = 0)

    private fun masterRecords(count: Int, scale: Double = 0.25): ByteArray {
        val out = ByteWriter()
        for (i in 0 until count) out.f64(i * scale)
        return out.toByteArray()
    }

    private fun valueRecords(count: Int): ByteArray {
        val out = ByteWriter()
        for (i in 0 until count) out.f32(i * 10f)
        return out.toByteArray()
    }

    private fun scan(file: MdfFile, dataGroup: Int, name: String = "v"): Pair<List<Sample>, MdfScanner> {
        val channel = file.groups[dataGroup].channelGroups.flatMap { it.channels }.first { it.name == name }
        val request = MdfChannelRequest(channel)
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups[dataGroup], listOf(request), sink) }
        runSuspending { scanner.advance() }
        return sink.of(request) to scanner
    }

    private fun remoteFile(
        count: Int,
        master: GroupSpec,
        data: GroupSpec,
        masterCount: Int = count,
        masterBlock: ((BlockLayout) -> TestBlock)? = null,
    ): ByteArray = writeFile(
        FileSpec(
            listOf(
                DataGroupSpec(0, listOf(master), masterRecords(masterCount), dataBlock = masterBlock),
                DataGroupSpec(0, listOf(data), valueRecords(count)),
            ),
        ),
    )

    @Test
    fun readsAGroupWhoseMasterIsInAnotherChannelGroup() {
        val master = GroupSpec(0, "time group", listOf(time), 8, cycleCount = 6)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 6, remoteMaster = master)
        val file = open(remoteFile(6, master, data))

        val (samples, scanner) = scan(file, 1)

        assertEquals(List(6) { Sample(it * 0.25, it * 10.0, false) }, samples)
        assertTrue(scanner.rejected.isEmpty())
        val group = file.groups[1].channelGroups.single()
        assertTrue(group.hasRemoteMaster)
        assertSame(file.groups[0].channelGroups.single(), group.remoteMaster)
        assertEquals("time", group.master?.name)
        assertEquals(MdfSyncType.Time, group.masterAxis)
    }

    @Test
    fun readsTheMastersConversionToo() {
        val milliseconds = ChannelSpec(
            "ms",
            DataTypes.UNSIGNED_LE,
            32,
            type = ChannelTypes.MASTER,
            syncType = 1,
            conversion = ConversionSpec.linear(0.0, 0.001),
        )
        val master = GroupSpec(0, "time group", listOf(milliseconds), 4, cycleCount = 4)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 4, remoteMaster = master)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(0, listOf(master), ByteWriter().u32(100).u32(250).u32(1000).u32(2500).toByteArray()),
                    DataGroupSpec(0, listOf(data), valueRecords(4)),
                ),
            ),
        )

        assertEquals(listOf(0.1, 0.25, 1.0, 2.5), scan(open(bytes), 1).first.map { it.time })
    }

    @Test
    fun readsAMasterThatIsVirtual() {
        val virtual = ChannelSpec(
            "index",
            DataTypes.UNSIGNED_LE,
            0,
            type = ChannelTypes.VIRTUAL_MASTER,
            syncType = 1,
            conversion = ConversionSpec.linear(10.0, 0.5),
        )
        val master = GroupSpec(0, "time group", listOf(virtual), 0, cycleCount = 5)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 5, remoteMaster = master)
        val bytes =
            writeFile(FileSpec(listOf(DataGroupSpec(0, listOf(master), ByteArray(0)), DataGroupSpec(0, listOf(data), valueRecords(5)))))

        assertEquals(listOf(10.0, 10.5, 11.0, 11.5, 12.0), scan(open(bytes), 1).first.map { it.time })
    }

    @Test
    fun readsAMasterKeptInListsOfZippedBlocksAndRecordsOfSeveralGroups() {
        val master = GroupSpec(0, "time group", listOf(time), 8, cycleCount = 200)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 200, remoteMaster = master)
        val bytes = remoteFile(200, master, data) { layout ->
            dataListChain(layout, chunks(masterRecords(200), 300).map { zippedPart(layout, it) }, perList = 2)
        }

        val (samples, _) = scan(open(bytes), 1)

        assertEquals(200, samples.size)
        assertEquals(List(200) { it * 0.25 }, samples.map { it.time })
    }

    @Test
    fun readsGroupsOfAnUnsortedDataGroupAgainstAMasterInAnother() {
        // The master's group is one of two in an unsorted data group, the data group's one of two in another.
        val master = GroupSpec(1, "time group", listOf(time), 8, cycleCount = 3)
        val noise = GroupSpec(2, "noise", listOf(ChannelSpec("n", byteOffset = 0)), 4, cycleCount = 3)
        val data = GroupSpec(1, "data group", listOf(value), 4, cycleCount = 3, remoteMaster = master)
        val other = GroupSpec(2, "other", listOf(ChannelSpec("o", byteOffset = 0)), 4, cycleCount = 3)
        val first = ByteWriter()
        val second = ByteWriter()
        for (i in 0 until 3) {
            first.u8(1).f64(100.0 + i).u8(2).f32(-1f)
            second.u8(2).f32(0f).u8(1).f32(i * 7f)
        }
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(1, listOf(master, noise), first.toByteArray()),
                    DataGroupSpec(1, listOf(data, other), second.toByteArray()),
                ),
            ),
        )

        val (samples, _) = scan(open(bytes), 1)

        assertEquals(listOf(Sample(100.0, 0.0, false), Sample(101.0, 7.0, false), Sample(102.0, 14.0, false)), samples)
    }

    @Test
    fun aMastersGroupCanBeTheMasterOfSeveralGroups() {
        val master = GroupSpec(0, "time group", listOf(time), 8, cycleCount = 4)
        val first = GroupSpec(0, "first", listOf(value), 4, cycleCount = 4, remoteMaster = master)
        val second = GroupSpec(0, "second", listOf(ChannelSpec("w", byteOffset = 0)), 4, cycleCount = 4, remoteMaster = master)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(0, listOf(master), masterRecords(4)),
                    DataGroupSpec(0, listOf(first), valueRecords(4)),
                    DataGroupSpec(0, listOf(second), valueRecords(4)),
                ),
            ),
        )
        val file = open(bytes)

        assertEquals(List(4) { it * 0.25 }, scan(file, 1).first.map { it.time })
        assertEquals(List(4) { it * 0.25 }, scan(file, 2, "w").first.map { it.time })
    }

    @Test
    fun givesNoTimeToRecordsPastTheEndOfAMasterThatIsShorter() {
        val master = GroupSpec(0, "time group", listOf(time), 8, cycleCount = 3)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 5, remoteMaster = master)

        val (samples, _) = scan(open(remoteFile(5, master, data, masterCount = 3)), 1)

        assertEquals(5, samples.size)
        assertContentEquals(doubleArrayOf(0.0, 0.25, 0.5), samples.take(3).map { it.time }.toDoubleArray())
        assertTrue(samples.drop(3).all { it.time.isNaN() })
    }

    @Test
    fun readsAMasterThatIsAnAngleADistanceOrAnIndex() {
        for ((syncType, axis) in listOf(
            2 to MdfSyncType.Angle,
            3 to MdfSyncType.Distance,
            4 to MdfSyncType.Index,
        )) {
            val own = ChannelSpec("axis", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = syncType, byteOffset = 0)
            val group = GroupSpec(0, "g", listOf(own, ChannelSpec("v", byteOffset = 8)), 12, cycleCount = 3)
            val records = ByteWriter().f64(0.5).f32(1f).f64(1.5).f32(2f).f64(3.0).f32(3f).toByteArray()
            val file = open(writeFile(FileSpec(listOf(DataGroupSpec(0, listOf(group), records)))))

            val (samples, _) = scan(file, 0)

            assertEquals(axis, file.channelGroups.single().masterAxis)
            assertEquals(listOf(0.5, 1.5, 3.0), samples.map { it.time }, axis.name)
            assertEquals(listOf(1.0, 2.0, 3.0), samples.map { it.value }, axis.name)
        }
    }

    @Test
    fun aGroupWithoutAMasterHasNoAxis() {
        val file =
            open(
                writeFile(FileSpec(listOf(DataGroupSpec(0, listOf(GroupSpec(0, "g", listOf(value), 4, cycleCount = 1)), valueRecords(1))))),
            )

        assertEquals(MdfSyncType.None, file.channelGroups.single().masterAxis)
        assertNull(file.channelGroups.single().master)
    }

    // A file whose group names, as its master, the first text block: no channel group.
    private fun withMasterNamingNoGroup(): ByteArray {
        val master = GroupSpec(0, "time group", listOf(time), 8, cycleCount = 2)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 2, remoteMaster = master)
        return writeFile(
            FileSpec(
                listOf(DataGroupSpec(0, listOf(master), masterRecords(2)), DataGroupSpec(0, listOf(data), valueRecords(2))),
                tamper = { blocks -> blocks.filter { it.type == "CG" }[1].links[6] = blocks.first { it.type == "TX" } },
            ),
        )
    }

    @Test
    fun saysAMasterGroupThatIsNotInTheFileIsDamaged() {
        val failure = assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(withMasterNamingNoGroup().asMdfSource()) })

        assertTrue(failure.reason.contains("no channel group"), failure.reason)
    }

    @Test
    fun rejectsTheRequestsOfAGroupWhoseMasterIsNoChannelGroupOfTheFile() {
        val file = open(withMasterNamingNoGroup(), lenient = true)

        val (samples, scanner) = scan(file, 1)

        assertNull(file.groups[1].channelGroups.single().remoteMaster)
        assertTrue(file.issues.isNotEmpty())
        assertTrue(samples.isEmpty())
        assertIs<MdfFailure.Unsupported>(scanner.rejected.single().failure)
    }

    @Test
    fun saysAGroupThatClaimsAMasterElsewhereButNamesNoneIsSkippedNotRead() {
        val master = GroupSpec(0, "time group", listOf(time), 8, cycleCount = 2)
        val data = GroupSpec(0, "data group", listOf(value), 4, cycleCount = 2, remoteMaster = master)
        val bytes = writeFile(
            FileSpec(
                listOf(DataGroupSpec(0, listOf(master), masterRecords(2)), DataGroupSpec(0, listOf(data), valueRecords(2))),
                tamper = { blocks -> blocks.filter { it.type == "CG" }[1].links[6] = null },
            ),
        )
        val file = open(bytes)

        val (samples, scanner) = scan(file, 1)

        assertTrue(file.skipped.any { it.subject.contains("data group") })
        assertTrue(samples.isEmpty())
        assertNotNull(scanner.rejected.singleOrNull())
    }

    @Test
    fun aMasterThatHasAMasterElsewhereIsNotFollowedFurther() {
        val first = GroupSpec(0, "first", listOf(time), 8, cycleCount = 2)
        val second = GroupSpec(0, "second", listOf(ChannelSpec("x", byteOffset = 0)), 8, cycleCount = 2, remoteMaster = first)
        val third = GroupSpec(0, "third", listOf(value), 4, cycleCount = 2, remoteMaster = second)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(0, listOf(first), masterRecords(2)),
                    DataGroupSpec(0, listOf(second), masterRecords(2)),
                    DataGroupSpec(0, listOf(third), valueRecords(2)),
                ),
            ),
        )

        val (samples, scanner) = scan(open(bytes), 2)

        assertTrue(samples.isEmpty())
        assertIs<MdfFailure.Unsupported>(scanner.rejected.single().failure)
    }
}
