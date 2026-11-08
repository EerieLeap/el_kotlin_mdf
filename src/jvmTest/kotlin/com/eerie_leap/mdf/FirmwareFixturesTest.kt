package com.eerie_leap.mdf

import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FirmwareFixturesTest : FixtureChecks() {
    override val set = Fixtures
    override val fileCount = 31
    override val minFrames = 300

    @Test
    fun readsAClosedFileCutShortUpToItsLastWholeRecord() {
        // A download in progress: the identifier says MDF, the data block's length and the cycle counts are final and run past the
        // end of the file. asammdf refuses these; the reader takes the data to the end of the file and stops at the last whole record.
        for (name in listOf("values_closed_cut_mid_record.mf4", "values_closed_cut_header_only.mf4")) {
            val entry = Fixtures.entry(name)
            val scan = scan(Fixtures.bytes(name))
            val parent = Fixtures.entry(entry["derived_from"].text())["asammdf"].obj()["groups"].list().map { it.obj() }
            val perRecordId = entry["independent_parse"].obj()["records_per_record_id"].obj()

            assertTrue(scan.file.identification.isFinalized, name)
            assertNull(scan.progress.problem, name)
            assertFalse(scan.progress.isComplete, name)
            for ((expected, group) in parent.zip(scan.file.channelGroups)) {
                val count = perRecordId[group.recordId.toString()]?.int() ?: 0
                assertEquals(count.toLong(), scan.scanner.cycleCount(group), "$name: records of ${group.name}")
                val times = expected["master"].obj()["timestamps"].list().map { it.double() }.take(count)
                for ((e, channel) in expected["channels"].list().map { it.obj() }.zip(group.channels.filter { it !== group.master })) {
                    val got = scan.sink.of(scan.stored.getValue(channel))
                    assertEquals(times, got.map { it.time }, "$name: ${channel.name} times")
                    assertEquals(
                        e["stored"].obj()["samples"].list().map {
                            it.double()
                        }.take(count),
                        got.map { it.value },
                        "$name: ${channel.name}",
                    )
                    assertEquals(
                        e["stored"].obj()["invalid"].list().map {
                            it.int() != 0
                        }.take(count),
                        got.map { it.isInvalid },
                        "$name: ${channel.name}",
                    )
                }
            }
            val bytesAfter = entry["independent_parse"].obj()["bytes_after_the_last_whole_record"].long()
            assertEquals(entry["size"].long() - entry["header_bytes"].long() - bytesAfter, scan.progress.bytesRead, name)
        }
    }

    @Test
    fun saysAFileCutInsideItsHeaderBlocksIsCutShort() {
        val failure = failureOf { Mdf4Reader.open(Fixtures.bytes("values_cut_in_header.mf4").asMdfSource()) }

        assertIs<MdfFailure.Truncated>(failure)
    }

    @Test
    fun readsTheEmptyAndHeaderOnlyFilesAsNoRecords() {
        for (name in listOf("empty.mf4", "values_unclosed_header_only.mf4")) {
            val scan = scan(Fixtures.bytes(name))

            assertTrue(scan.sink.samples.values.all { it.isEmpty() }, name)
            assertEquals(0L, scan.progress.bytesRead, name)
            assertNull(scan.progress.problem, name)
        }
    }

    @Test
    fun readsWhatTheFixturesDescribeInTheirReadme() {
        val conversions = open(Fixtures.bytes("conversions.mf4"))
        val utf8 = conversions.channelGroups.first { it.name == "utf8" }.channels[1]
        assertEquals("°C", utf8.unit)
        assertEquals("Température d'huile: R&D <test> \"quoted\" — café", utf8.comment.text)

        val bare = conversions.channelGroups.first { it.name == "bare" }.channels[1]
        assertEquals("", bare.unit)
        assertEquals("", bare.comment.text)

        // An expression no formula can carry is only a comment on the raw channel.
        val raw = conversions.channelGroups.first { it.name == "noconv" }.channels[2]
        assertNull(raw.conversion)
        assertEquals("expression: x + other_sensor", raw.comment.text)
        assertEquals("expression: x > 0.5", conversions.channelGroups.first { it.name == "ind_expr" }.channels[2].comment.text)

        // The disabled and the not logged sensors have no channel group.
        assertTrue(conversions.channelGroups.none { it.name == "off_sensor" || it.name == "off_logging" })

        val wide = open(Fixtures.bytes("wide_ids.mf4"))
        assertEquals(2, wide.groups.single().recordIdSize)
        assertEquals(129, wide.channelGroups.size)
    }
}
