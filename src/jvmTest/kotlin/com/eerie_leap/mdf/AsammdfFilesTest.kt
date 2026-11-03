package com.eerie_leap.mdf

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files written by asammdf 8.8.27 (`resources/asammdf/make_*.py` make them): another writer's layout than the firmware's, with
 * sorted data groups, every kind of conversion the reader takes, and zipped data blocks (plain and transposed deflate, by
 * Python's zlib). The expected values are asammdf's own reading of the files.
 */
class AsammdfFilesTest {
    private val expected = MiniJson.parse(resource("asammdf/asammdf_expected.json").decodeToString()).obj()

    private fun open(name: String): MdfFile = open(resource("asammdf/$name.mf4"))

    private class Reading(
        val file: MdfFile,
        val sink: CollectingSink,
        val requests: Map<String, Pair<MdfChannelRequest, MdfChannelRequest>>,
    )

    // Scans one channel group of the file: its channels' converted and stored values.
    private fun scan(file: MdfFile, group: Int): Reading {
        val channelGroup = file.groups[group].channelGroups.single()
        val channels = channelGroup.channels.filter { it !== channelGroup.master }
        val requests = channels.associate { it.name to (MdfChannelRequest(it) to MdfChannelRequest(it, stored = true)) }
        val sink = CollectingSink()
        val progress = runSuspending {
            MdfScanner.create(file, file.groups[group], requests.values.flatMap { listOf(it.first, it.second) }, sink).advance()
        }
        assertNull(progress.problem)
        assertTrue(progress.isComplete)
        return Reading(file, sink, requests)
    }

    private fun expectedChannel(name: String, group: Int, channel: String): Map<String, Any?> =
        expected[name].obj()["groups"].list()[group].obj()["channels"].list().map { it.obj() }.first { it["name"] == channel }

    private fun assertClose(expected: Double, actual: Double, what: String, relative: Double = 1e-12) {
        assertTrue(abs(expected - actual) <= relative * (1.0 + abs(expected)), "$what: expected $expected but was $actual")
    }

    @Test
    fun readsTheStructureOfFilesOfEveryVersion() {
        for (version in listOf("4.00", "4.10", "4.20")) {
            val file = open("ver_$version")
            val group = file.groups[0].channelGroups.single()

            assertEquals(version, file.identification.versionText)
            assertEquals("amdf8.8.", file.identification.program.take(8))
            assertEquals(2, file.groups.size, version)
            assertEquals(0, file.groups[0].recordIdSize, version)
            assertTrue(file.groups[0].isSorted, version)
            assertEquals(listOf("time", "sine", "count", "enum", "lin", "rat", "rtab", "tab"), group.channels.map { it.name }, version)
            assertEquals("time", group.master?.name, version)
            assertEquals("s", group.master?.unit, version)
            assertEquals(listOf("s", "V", "n", "", "x", "", "", ""), group.channels.map { it.unit }, version)
            assertEquals(
                listOf(null, null, null, 7, 1, 2, 6, 5),
                group.channels.map { it.conversion?.code },
                version,
            )
            assertEquals(100L, group.cycleCount, version)
            assertTrue(file.issues.isEmpty(), version)
        }
    }

    @Test
    fun readsTheSamplesOfFilesOfEveryVersionLikeAsammdf() {
        for (version in listOf("4.00", "4.10", "4.20")) {
            val reading = scan(open("ver_$version"), 0)

            for (name in listOf("sine", "count", "lin", "rat", "tab")) {
                val want = expectedChannel("ver_4.10", 0, name)
                val (converted, stored) = reading.requests.getValue(name)
                val got = reading.sink.of(converted)
                assertEquals(want["times"].list().map { it.double() }, got.map { it.time }, "$version $name: times")
                // asammdf converts a float32 channel in float32 and the reader in Double: the difference is the float's.
                want["converted"].list().forEachIndexed { i, w -> assertClose(w.double(), got[i].value, "$version $name[$i]", 1e-6) }
                assertEquals(want["stored"].list().map { it.double() }, reading.sink.of(stored).map { it.value }, "$version $name: stored")
            }
        }
    }

    @Test
    fun labelsAnEnumerationByItsStoredNumbers() {
        val reading = scan(open("ver_4.10"), 0)
        val (converted, _) = reading.requests.getValue("enum")
        val conversion = assertIs<MdfConversion.ValueToText>(reading.file.channelGroups[0].channels[3].conversion)

        // The scan gives the stored numbers; the conversion words them as asammdf does.
        val want = expectedChannel("ver_4.10", 0, "enum")["converted"].list().map { it.text() }
        assertEquals(want, reading.sink.of(converted).map { conversion.toText(it.value) })
        assertEquals(setOf("off", "on", "auto"), want.toSet())
    }

    @Test
    fun readsAValueRangeToValueTableByTheStandardsClosedRanges() {
        // Ranges [0, 1] give 10 and [2, 3] give 20, anything else -1. (asammdf's own numeric path for whole numbers answers
        // differently, which is a flaw of its own: it matches only the upper bounds.)
        val reading = scan(open("ver_4.10"), 0)
        val (converted, stored) = reading.requests.getValue("rtab")

        val raw = reading.sink.of(stored).map { it.value }
        val want = raw.map { v ->
            if (v in 0.0..1.0) {
                10.0
            } else if (v in 2.0..3.0) {
                20.0
            } else {
                -1.0
            }
        }
        assertEquals(want, reading.sink.of(converted).map { it.value })
        assertEquals(setOf(0.0, 1.0, 2.0, 3.0, 4.0), raw.toSet())
    }

    @Test
    fun readsTheSecondDataGroupToo() {
        val reading = scan(open("ver_4.10"), 1)
        val (converted, _) = reading.requests.getValue("cosine")
        val want = expectedChannel("ver_4.10", 1, "cosine")

        assertEquals(want["converted"].list().map { it.double() }, reading.sink.of(converted).map { it.value })
        assertEquals(want["times"].list().map { it.double() }, reading.sink.of(converted).map { it.time })
    }

    @Test
    fun readsZippedDataBlocksOfBothZipTypes() {
        for (name in listOf("zipped", "transposed")) {
            val reading = scan(open(name), 0)

            for (channel in listOf("sine", "count")) {
                val want = expectedChannel("zipped", 0, channel)
                val got = reading.sink.of(reading.requests.getValue(channel).first)
                assertEquals(2000, got.size, "$name $channel")
                assertEquals(want["converted"].list().map { it.double() }, got.map { it.value }, "$name $channel")
                assertEquals(want["times"].list().map { it.double() }, got.map { it.time }, "$name $channel")
            }
        }
    }
}
