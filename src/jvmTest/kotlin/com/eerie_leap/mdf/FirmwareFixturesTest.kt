package com.eerie_leap.mdf

import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Logs written by the firmware's own logger (rt_core's `Mdf4LoggerSensorReading` on native_sim, see `fixtures/README.md`), read by
 * this library and checked against asammdf's values, sample for sample: the stored values with their invalidation bits, the
 * converted values, the times, the CAN frames, and the structure around them.
 */
internal object Fixtures {
    private val json: Map<String, Any?> by lazy { MiniJson.parse(bytes("fixtures.json").decodeToString()).obj() }

    val files: List<Map<String, Any?>> by lazy { json["files"].list().map { it.obj() } }

    fun families(): Map<String, Any?> = json["families"].obj()

    fun entry(name: String): Map<String, Any?> = files.first { it["name"] == name }

    fun bytes(name: String): ByteArray = resource("fixtures/$name")
}

/** A file of the test resources. */
internal fun resource(path: String): ByteArray {
    val stream = checkNotNull(Fixtures::class.java.classLoader.getResourceAsStream(path)) { "no test resource $path" }
    return stream.use { it.readBytes() }
}

class FirmwareFixturesTest {
    private val files = Fixtures.files
    private val opened = files.filter { it["asammdf"].obj()["opens"] == true }

    @Test
    fun everyFixtureIsWhatItsRecordSays() {
        assertEquals(31, files.size)
        for (entry in files) {
            val bytes = Fixtures.bytes(entry["name"].text())
            val crc = CRC32().also { it.update(bytes) }.value

            assertEquals(entry["size"].int(), bytes.size, "${entry["name"]}: size")
            assertEquals(entry["crc32_decimal"].long(), crc, "${entry["name"]}: CRC-32")
        }
    }

    @Test
    fun readsTheIdentificationHeaderAndHistoryOfEveryFileAsammdfOpens() {
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(Fixtures.bytes(name))
            val asammdf = entry["asammdf"].obj()
            val header = asammdf["header"].obj()
            val metadata = Fixtures.families()[entry["family"]].obj()["metadata"].obj()

            assertEquals("4.10", file.identification.versionText, name)
            assertEquals("EL", file.identification.program, name)
            assertEquals(entry["identifier"] == "MDF     ", file.identification.isFinalized, name)
            assertEquals(entry["unfinalized_standard_flags"].int(), file.identification.unfinalized.sumOf { it.mask }, name)
            assertEquals(header["start_time_ns"].long(), file.header.startNs, name)
            assertEquals(0, file.header.timeClass, name)
            assertFalse(file.header.isLocalTime, name)

            val expectedProperties = header["common_properties"].obj().map { it.key to it.value.text() }
            assertEquals(expectedProperties, file.comment.properties.toList(), "$name: header properties")
            assertEquals("Eerie Leap sensor log", file.comment.text, name)

            val history = file.history.single()
            assertEquals("rt_daq", history.tool, name)
            assertEquals("Eerie Leap", history.vendor, name)
            assertEquals(metadata["tool_version"].text(), history.version, name)
            assertTrue(file.issues.isEmpty(), name)
        }
    }

    @Test
    fun readsTheChannelGroupsAndChannelsOfEveryFileAsammdfOpens() {
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(Fixtures.bytes(name))
            val expectedGroups = entry["asammdf"].obj()["groups"].list().map { it.obj() }
            val isClosed = entry["identifier"] == "MDF     "

            assertEquals(1, file.groups.size, name)
            assertEquals(expectedGroups.size, file.channelGroups.size, name)
            for ((expected, group) in expectedGroups.zip(file.channelGroups)) {
                val where = "$name, group ${expected["index"]}"
                assertEquals((expected["name"] as String?).orEmpty(), group.name, where)
                assertEquals(expected["record_id"].long(), group.recordId, where)
                assertEquals(expected["flags"].int(), group.flags, where)
                assertEquals(expected["is_vlsd"], group.isVlsd, where)
                assertEquals(expected["is_bus_event"], group.isBusEvent, where)
                if (isClosed) assertEquals(expected["cycles"].long(), group.cycleCount, where)
                if (group.isVlsd) {
                    if (isClosed) assertEquals(expected["data_bytes"].long(), group.vlsdBytes, where)
                } else {
                    assertEquals(expected["data_bytes"].long(), group.dataBytes, where)
                    assertEquals(expected["invalidation_bytes"].long(), group.invalidationBytes, where)
                }
                checkSource(where, expected["source"], group.source)
                assertEquals(MdfComment.parse((expected["comment"] as String?).orEmpty()).text, group.comment.text, where)
                checkChannels(where, expected, group)
            }
        }
    }

    private fun checkSource(where: String, expected: Any?, source: MdfSourceInformation?) {
        if (expected == null) {
            assertNull(source, where)
        } else {
            val e = expected.obj()
            assertEquals(e["name"], source?.name, where)
            assertEquals((e["path"] as String?).orEmpty(), source?.path, where)
            assertEquals(e["source_type"].int(), source?.type?.code, where)
            assertEquals(e["bus_type"].int(), source?.busType?.code, where)
        }
    }

    private fun checkChannels(where: String, expected: Map<String, Any?>, group: MdfChannelGroup) {
        if (group.isVlsd || group.isBusEvent) return
        val master = expected["master"].obj()
        assertEquals(master["name"], group.master?.name, where)
        assertEquals(master["unit"], group.master?.unit, where)
        assertEquals(MdfChannelKind.Master, group.master?.kind, where)
        assertEquals(MdfSyncType.Time, group.master?.syncType, where)
        assertEquals(MdfDataType.FloatLittleEndian, group.master?.dataType, where)
        assertEquals(64, group.master?.bitCount, where)

        val expectedChannels = expected["channels"].list().map { it.obj() }
        val channels = group.channels.filter { it !== group.master }
        assertEquals(expectedChannels.map { it["name"] }, channels.map { it.name }, where)
        for ((e, channel) in expectedChannels.zip(channels)) {
            val at = "$where, channel ${channel.name}"
            assertEquals((e["unit"] as String?).orEmpty(), channel.unit, at)
            assertEquals(MdfComment.parse((e["comment"] as String?).orEmpty()).text, channel.comment.text, at)
            assertEquals(e["data_type"].int(), channel.dataType.code, at)
            assertEquals(e["bit_count"].int(), channel.bitCount, at)
            assertEquals(e["has_invalidation_bit"], channel.hasInvalidationBit, at)
            assertEquals(if (e["has_invalidation_bit"] == true) e["invalidation_bit_position"].int() else null, channel.invalidationBit, at)
            val conversion = e["conversion"]
            if (conversion == null) {
                assertNull(channel.conversion, at)
            } else {
                assertEquals(conversion.obj()["type"].int(), channel.conversion?.code, at)
                assertEquals(conversion.obj()["formula"], assertIs<MdfConversion.Algebraic>(channel.conversion).formula, at)
            }
        }
    }

    // Every channel as requests for its stored and its converted values, and every CAN frame group: one scan of the file.
    private class Scan(val file: MdfFile, val sink: CollectingSink, val scanner: MdfScanner, val progress: MdfScanProgress) {
        lateinit var stored: Map<MdfChannel, MdfChannelRequest>
        lateinit var converted: Map<MdfChannel, MdfChannelRequest>
    }

    private fun scan(bytes: ByteArray): Scan {
        val file = open(bytes)
        val channels = file.channelGroups.flatMap { group -> group.channels.filter { it.isNumeric && it !== group.master } }
        val stored = channels.associateWith { MdfChannelRequest(it, stored = true) }
        val converted = channels.associateWith { MdfChannelRequest(it, stored = false) }
        val frameGroups = file.channelGroups.filter { CanFrames.isCanDataFrameGroup(it) }
        val sink = CollectingSink()
        val scanner = runSuspending {
            MdfScanner.create(file, file.groups.single(), stored.values.toList() + converted.values.toList(), sink, frameGroups)
        }
        val progress = runSuspending { scanner.advance() }
        return Scan(file, sink, scanner, progress).also {
            it.stored = stored
            it.converted = converted
        }
    }

    @Test
    fun scansTheSamplesOfEveryFileAsammdfOpensLikeAsammdf() {
        for (entry in opened) {
            val name = entry["name"].text()
            val scan = scan(Fixtures.bytes(name))
            val expectedGroups = entry["asammdf"].obj()["groups"].list().map { it.obj() }
            val isClosed = entry["identifier"] == "MDF     "

            assertNull(scan.progress.problem, name)
            for ((expected, group) in expectedGroups.zip(scan.file.channelGroups)) {
                if (!group.isVlsd) {
                    assertEquals(
                        expected["cycles"].long(),
                        scan.scanner.cycleCount(group),
                        "$name: records of ${group.name}",
                    )
                }
                if (group.isVlsd && isClosed) assertEquals(expected["cycles"].long(), scan.scanner.cycleCount(group), name)
                if (expected.containsKey("channels")) compareSamples(name, expected, group, scan)
            }
            // Every byte up to the last whole record was read, and what follows is the part of a record the file was cut in.
            val parse = entry["independent_parse"].obj()
            val dataBytes = entry["size"].long() - entry["header_bytes"].long()
            assertEquals(dataBytes - parse["bytes_after_the_last_whole_record"].long(), scan.progress.bytesRead, name)
        }
    }

    private fun compareSamples(name: String, expected: Map<String, Any?>, group: MdfChannelGroup, scan: Scan) {
        val times = expected["master"].obj()["timestamps"].list().map { it.double() }
        val channels = group.channels.filter { it !== group.master }
        for ((e, channel) in expected["channels"].list().map { it.obj() }.zip(channels)) {
            val at = "$name, ${channel.name}"
            val stored = e["stored"].obj()
            val got = scan.sink.of(scan.stored.getValue(channel))
            assertEquals(times.size, got.size, "$at: samples")
            assertEquals(times, got.map { it.time }, "$at: times")
            assertEquals(stored["samples"].list().map { it.double() }, got.map { it.value }, "$at: stored values")
            assertEquals(stored["invalid"].list().map { it.int() != 0 }, got.map { it.isInvalid }, "$at: invalidation bits")

            val convertedGot = scan.sink.of(scan.converted.getValue(channel))
            val converted = (e["converted"] ?: e["stored"]).obj()
            assertEquals(converted["invalid"].list().map { it.int() != 0 }, convertedGot.map { it.isInvalid }, "$at: invalid, converted")
            val expectedValues = converted["samples"].list().map { it.double() }
            for ((i, value) in expectedValues.withIndex()) {
                val actual = convertedGot[i].value
                assertTrue(
                    abs(actual - value) <= TOLERANCE * (1.0 + abs(value)),
                    "$at: converted value $i, expected $value but was $actual",
                )
            }
        }
    }

    @Test
    fun readsTheCanFramesOfEveryFileAsammdfOpensLikeAsammdf() {
        var framesChecked = 0
        for (entry in opened) {
            val name = entry["name"].text()
            val scan = scan(Fixtures.bytes(name))
            val expectedGroups = entry["asammdf"].obj()["groups"].list().map { it.obj() }
            for ((expected, group) in expectedGroups.zip(scan.file.channelGroups)) {
                val expectedFrames = (expected["frames"] as List<*>?)?.map { it.obj() } ?: continue
                val frames = scan.sink.framesOf[group].orEmpty()
                assertEquals(expectedFrames.size, frames.size, "$name: frames of ${group.name}")
                for ((i, e) in expectedFrames.withIndex()) {
                    val at = "$name, ${group.name}, frame $i"
                    val frame = frames[i]
                    assertEquals(e["t"].double(), frame.time, at)
                    assertEquals(e["bus_channel"].int(), frame.bus, at)
                    assertEquals(e["id"].long(), frame.id, at)
                    assertEquals(e["extended"], frame.isExtended, at)
                    assertEquals(e["dlc"].int(), frame.dlc, at)
                    assertEquals(e["transmit"], frame.isTransmit, at)
                    assertEquals(e["fd"], frame.isFd, at)
                    assertEquals(e["brs"], frame.isBitRateSwitch, at)
                    assertEquals(e["esi"], frame.hasErrorState, at)
                    assertEquals(e["data_length"].int(), frame.dataLength, at)
                    assertEquals(e["data"].text(), frame.data.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }, at)
                    framesChecked++
                }
            }
        }
        assertTrue(framesChecked > 300, "frames checked: $framesChecked")
    }

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

    private companion object {
        // asammdf converts in float32 (numexpr on a float32 array); the reader in Double from the float32 raw value.
        const val TOLERANCE = 1e-5
    }
}
