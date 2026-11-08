package com.eerie_leap.mdf

import java.util.zip.CRC32
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The logs of rt_core cbff74e (`fixtures/cbff74e/README.md`): the same checks as the first fixtures get, against asammdf's reading of
 * them, and one test for each thing the firmware writes now: the header's properties and time class, the channels' comment properties,
 * sources and limits, the attached configurations, and the event that ends a file.
 */
internal class Cbff74eFixturesTest : FixtureChecks() {
    override val set = Cbff74eFixtures
    override val fileCount = 6
    override val minFrames = 60

    private val values = "values.mf4"
    private val closed get() = opened.filter { it["identifier"] == "MDF     " }

    private fun family(entry: Map<String, Any?>): Map<String, Any?> = set.families()[entry["family"]].obj()

    private fun sensorsOf(entry: Map<String, Any?>): List<Map<String, Any?>> = family(entry)["sensors"].list().map { it.obj() }

    // The generator's own record of the session, with the attached bytes as hex.
    private fun manifestOf(entry: Map<String, Any?>): Map<String, Any?> =
        MiniJson.parse(set.bytes(family(entry)["manifest"].text()).decodeToString()).obj()

    private fun crc(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    @Test
    fun readsTheHeadersPropertiesAndTimeClassOfEveryFile() {
        assertEquals(6, opened.size)
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(set.bytes(name))
            val metadata = family(entry)["metadata"].obj()
            val properties = file.comment.properties

            assertEquals(metadata["unit_uid"].long().toString(), properties["unit_uid"], name)
            assertEquals(metadata["boot_id"].long().toString(), properties["boot_id"], name)
            assertEquals(metadata["session_id"].long().toString(), properties["session_id"], name)
            assertEquals(metadata["start_cause"], properties["start_cause"], name)
            assertEquals(entry["file_index_in_session"].int().toString(), properties["file_index"], name)
            assertEquals(metadata["clock"], properties["clock"], name)
            assertEquals(metadata["sensors_configuration_hash"].long().toString(), properties["sensors_configuration_hash"], name)
            assertEquals(metadata["hd_time_class"].int(), file.header.timeClass, name)
            assertFalse(file.header.isLocalTime, name)
            assertEquals(entry["asammdf"].obj()["header"].obj()["start_time_ns"].long(), file.header.startNs, name)
        }
    }

    @Test
    fun aSetClockPutsTheStartInRealTime() {
        val file = open(set.bytes(values))

        assertEquals("set", file.comment.properties["clock"])
        assertEquals(10, file.header.timeClass)
        // 2026-11-07T10:00:00Z, which the generator asked for
        assertEquals(1_794_045_600_000_000_000L, file.header.startNs)
        assertEquals("smp", file.comment.properties["start_cause"])

        val unitClock = open(set.bytes("can.mf4"))
        assertEquals("uptime", unitClock.comment.properties["clock"])
        assertEquals(0, unitClock.header.timeClass)
        assertEquals("shell", unitClock.comment.properties["start_cause"])
        assertEquals("command", open(set.bytes("nospace.mf4")).comment.properties["start_cause"])
    }

    @Test
    fun readsTheCommentPropertiesOfEveryChannelAndChannelGroup() {
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(set.bytes(name))
            val sensors = sensorsOf(entry).filter { it["has_channel_group"] == true }
            // A copy of another file has a summary of the walk; its properties are those of the file it is a copy of.
            val walkedEntry = if (entry["derived_from"] == null) entry else set.entry(entry["derived_from"].text())
            val walked = walkedEntry["walk"].obj()["data_groups"].list().single().obj()["channel_groups"].list().map { it.obj() }
            val groups = file.channelGroups.filter { !it.isVlsd }

            assertEquals(sensors.map { it["id"] }, groups.map { it.name }, name)
            for ((sensor, group) in sensors.zip(groups)) {
                val at = "$name, ${sensor["id"]}"
                val expected = buildList {
                    add("sensor_type" to sensor["type"].text())
                    if (sensor["name"].text().isNotEmpty()) add("sensor_name" to sensor["name"].text())
                    if (sensor["description"].text().isNotEmpty()) add("sensor_description" to sensor["description"].text())
                }
                if (sensor["type"] == "CANBUS_RAW") {
                    // The raw frames group carries the three on the group; it has no value channel to carry them.
                    assertEquals(expected, group.comment.properties.toList(), at)
                    val cg = walked.first { it["acq_name"] == sensor["id"] }
                    assertEquals(
                        cg["comment_properties"].list().map {
                            it.list()[0] to it.list()[1]
                        },
                        group.comment.properties.toList(),
                        at,
                    )
                    continue
                }
                val value = group.channels.first { it.name == sensor["id"] }
                val rate = sensor["sampling_rate_ms"]?.int()
                val withFacts = expected + listOfNotNull(rate?.let { "sampling_rate_ms" to it.toString() }) +
                    ("log_only_new_data" to sensor["log_only_new_data"].toString())
                assertEquals(withFacts, value.comment.properties.toList(), at)
                val cn = walked.first { it["acq_name"] == sensor["id"] }["channels"].list().map { it.obj() }.first {
                    it["name"] ==
                        sensor["id"]
                }
                assertEquals(cn["comment_properties"].list().map { it.list()[0] to it.list()[1] }, value.comment.properties.toList(), at)

                // The comment's text is still "name: description", for the tools that don't read properties.
                val text = listOf(sensor["name"].text(), sensor["description"].text()).filter { it.isNotEmpty() }.joinToString(": ")
                assertEquals(text, value.comment.text, at)

                // The raw channel keeps its expression comment and no properties.
                group.channels.firstOrNull { it.name == sensor["id"].text() + ".raw" }?.let { raw ->
                    assertTrue(raw.comment.properties.isEmpty(), at)
                }
            }
        }
    }

    @Test
    fun readsTheSourceOfEveryValueChannelAndTheGroupsOwn() {
        var checked = 0
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(set.bytes(name))
            for (sensor in sensorsOf(entry).filter { it["has_channel_group"] == true && it["type"] != "CANBUS_RAW" }) {
                val at = "$name, ${sensor["id"]}"
                val group = file.channelGroups.first { it.name == sensor["id"] }
                val value = group.channels.first { it.name == sensor["id"] }
                val expected = sensor["expected_source"]?.obj()

                if (expected == null) {
                    assertNull(value.source, at)
                } else {
                    assertEquals(expected["name"], value.source?.name, at)
                    assertEquals(expected["source_type"].int(), value.source?.type?.code, at)
                    assertEquals(expected["bus_type"].int(), value.source?.busType?.code, at)
                    checked++
                }
                // What the group keeps as before: the shared acquisition source, on the group.
                assertEquals("Eerie Leap Sensor", group.source?.name, at)
                assertEquals(MdfSourceType.IoDevice, group.source?.type, at)
                assertNull(group.channels.firstOrNull { it.name.endsWith(".raw") }?.source, at)
            }
        }
        assertTrue(checked > 20, "sources checked: $checked")

        // The kinds the firmware names, in one file.
        val byId = open(set.bytes(values)).channelGroups.filter { !it.isVlsd }.associate {
            it.name to
                it.channels.first { c -> c.name == it.name }
        }
        fun source(id: String) = byId.getValue(id).source
        assertEquals("ADC 1", source("oil_pressure")?.name)
        assertEquals(MdfSourceType.IoDevice, source("oil_pressure")?.type)
        assertEquals("DI 0", source("ignition")?.name)
        assertEquals("CAN 1 · 0x316 · RPM", source("rpm")?.name)
        assertEquals(MdfSourceType.Bus, source("rpm")?.type)
        assertEquals(MdfBusType.Can, source("rpm")?.busType)
        assertEquals("virtual", source("engine_power")?.name)
        assertEquals(MdfSourceType.Tool, source("engine_power")?.type)
        assertEquals("script", source("boost")?.name)
        assertEquals("control", source("launch")?.name)
        assertEquals(MdfSourceType.User, source("launch")?.type)
    }

    @Test
    fun readsTheLimitsOfTheChannelsThatHaveThem() {
        val channels = open(set.bytes(values)).channelGroups.filter { !it.isVlsd }.associate {
            it.name to
                it.channels.first { c -> c.name == it.name }
        }

        // An indicator that no script produces: 0 and 1.
        for (id in listOf("ignition", "brake", "launch")) assertEquals(0.0..1.0, channels.getValue(id).limits, id)
        // A linear table with no expression and no script: its lowest and highest value (the table was given unsorted).
        assertEquals(-20.0..140.0, channels.getValue("coolant_temp").limits)
        // None: an expression, a cubic table, a virtual, a CAN or a script sensor, an indicator made by a script.
        for (id in listOf(
            "oil_pressure",
            "fuel_level",
            "engine_power",
            "rpm",
            "boost",
            "shift_light",
        )) {
            assertNull(channels.getValue(id).limits, id)
        }

        for ((id, channel) in channels) {
            assertNull(channel.extendedLimits, id)
            assertNull(channel.valueRange, id)
        }

        // Every file asammdf opens: the reader and asammdf give the same limits, and the raw channels have none.
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(set.bytes(name))
            val expectedGroups = entry["asammdf"].obj()["groups"].list().map { it.obj() }
            for ((expected, group) in expectedGroups.zip(file.channelGroups)) {
                if (!expected.containsKey("channels")) continue
                for (e in expected["channels"].list().map { it.obj() }) {
                    val channel = group.channels.first { it.name == e["name"] }
                    val limits = e["limits"]?.list()?.map { it.double() }
                    assertEquals(limits?.let { it[0]..it[1] }, channel.limits, "$name, ${channel.name}")
                    assertEquals(e["flags"].int() and 0x10 != 0, channel.limits != null, "$name, ${channel.name}")
                }
            }
        }
    }

    @Test
    fun readsEveryAttachmentWithTheBytesItsCommentSaysAndAsammdfGives() {
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(set.bytes(name))
            val manifest = manifestOf(entry)["attachments"].list().map { it.obj() }
            val asammdf = entry["asammdf"].obj()["attachments"].list().map { it.obj() }
            val walked = entry["walk"].obj()["attachments"].list().map { it.obj() }

            assertEquals(
                listOf("adc_config.cbor", "canbus_config.cbor", "sensors_config.cbor", "logging_config.cbor"),
                file.attachments.map {
                    it.fileName
                },
                name,
            )
            assertEquals(manifest.size, file.attachments.size, name)
            for ((i, attachment) in file.attachments.withIndex()) {
                val at = "$name, ${attachment.fileName}"
                val want = manifest[i]

                assertTrue(attachment.isEmbedded, at)
                assertFalse(attachment.isCompressed, at)
                assertEquals("application/cbor", attachment.mimeType, at)
                assertEquals(want["comment_text"], attachment.comment.text, at)
                assertEquals(
                    want["properties"].list().map {
                        it.list()[0].text() to it.list()[1].text()
                    },
                    attachment.comment.properties.toList(),
                    at,
                )
                assertEquals(walked[i]["original_size"].long(), attachment.originalSize, at)
                assertEquals(attachment.originalSize, attachment.embeddedSize, at)

                val bytes = runSuspending { file.readAttachment(attachment) }
                assertEquals(want["size"].int(), bytes.size, at)
                // The comment's crc is the CRC-32 of the stored bytes, which asammdf's extract_attachment() gives back as well.
                assertEquals(attachment.comment.properties["crc"]?.toLong(), crc(bytes), at)
                assertEquals(want["crc32"].long(), crc(bytes), at)
                assertEquals(asammdf[i]["extract_attachment"].obj()["crc32"].long(), crc(bytes), at)
                assertEquals(want["bytes_hex"].text(), bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }, at)
                assertEquals((i + 2).toString(), attachment.comment.properties["configuration_type"], at)
            }

            // The sensors configuration the log was written with is the one its header's hash names.
            val sensors = file.attachments.first { it.fileName == "sensors_config.cbor" }
            assertEquals(sensors.comment.properties["crc"], file.comment.properties["sensors_configuration_hash"], name)
        }
    }

    @Test
    fun readsTheEventThatEndsEachFinalizedFileAndNoneInAnUnclosedOne() {
        for (entry in opened) {
            val name = entry["name"].text()
            val file = open(set.bytes(name))
            val end = family(entry).let { entry["asammdf"].obj()["events"].list() }.map { it.obj() }
            val finalized = entry["identifier"] == "MDF     "

            if (!finalized) {
                assertTrue(file.events.isEmpty(), name)
                continue
            }
            val event = file.events.single()
            val expected = end.single()

            assertEquals(MdfEventType.StopRecordingTrigger, event.type, name)
            assertEquals(MdfSyncType.Time, event.syncType, name)
            assertEquals(MdfEventRange.Point, event.range, name)
            assertEquals(expected["cause"].int(), event.cause.code, name)
            assertEquals(expected["name"], event.name, name)
            assertEquals(expected["sync_base"].long(), event.syncBase, name)
            assertEquals(expected["sync_factor"].double(), event.syncFactor, name)
            assertEquals(expected["scope_count"].int(), event.scopes.size, name)
            assertTrue(event.scopes.isEmpty() && event.attachments.isEmpty() && event.parent == null, name)
            // The time of the last tick in the file, in microseconds after its start.
            assertEquals(entry["end_rel_us"].long() - entry["start_rel_us"].long(), event.syncBase, name)
            assertEquals(entry["end_rel_us"].long() - entry["start_rel_us"].long(), (event.value * 1_000_000).roundToLong(), name)
            assertEquals(MdfComment.parse(expected["comment"].text()).properties, event.comment.properties, name)
            assertEquals(event.name, event.comment.text, name)

            // HD links it, after the data: the walk of the file says where, and padding to 8 bytes lies between.
            val walk = entry["walk"].obj()
            val link = walk["header"].obj()["links"].obj()["ev_first"].long()
            assertEquals(event.offset, link, name)
            val tail = walk["tail"].obj()
            assertEquals(tail["first_block_offset"].long(), event.offset, name)
            assertEquals(0L, event.offset % 8, name)
            assertEquals("##EV", tail["first_block_after_padding"], name)
        }
    }

    @Test
    fun theStopEventsPropertiesSayWhyAndHowMuchWasLost() {
        val stopped = open(set.bytes(values)).events.single()
        assertEquals("Logging stopped", stopped.name)
        assertEquals(MdfEventCause.User, stopped.cause)
        assertEquals(mapOf("stop_reason" to "0", "overruns" to "3", "frames_dropped" to "11"), stopped.comment.properties)

        val noSpace = open(set.bytes("nospace.mf4")).events.single()
        assertEquals("Logging stopped: no space", noSpace.name)
        assertEquals(MdfEventCause.Error, noSpace.cause)
        assertEquals(mapOf("stop_reason" to "2", "overruns" to "2", "frames_dropped" to "0"), noSpace.comment.properties)

        val canStopped = open(set.bytes("can.mf4")).events.single()
        assertEquals("4", canStopped.comment.properties["frames_dropped"])
    }

    @Test
    fun aRotationIsOneSessionOfTwoFilesAndTheFirstEndsWithTheRotatedEvent() {
        val rotation = set.families()["rotation"].obj()["rotation"].obj()
        val first = open(set.bytes(rotation["first_file"].text()))
        val second = open(set.bytes(rotation["second_file"].text()))

        assertEquals(first.comment.properties["session_id"], second.comment.properties["session_id"])
        assertEquals("0", first.comment.properties["file_index"])
        assertEquals("1", second.comment.properties["file_index"])
        assertEquals("trigger", first.comment.properties["start_cause"])
        assertEquals("trigger", second.comment.properties["start_cause"])
        assertEquals("uptime", first.comment.properties["clock"])
        assertEquals(first.comment.properties["boot_id"], second.comment.properties["boot_id"])

        val rotated = first.events.single()
        assertEquals("Next file", rotated.name)
        assertEquals(MdfEventCause.Tool, rotated.cause)
        assertEquals(mapOf("rotated" to "true", "overruns" to "2", "frames_dropped" to "5"), rotated.comment.properties)
        assertNull(rotated.comment.properties["stop_reason"])

        val stopped = second.events.single()
        assertEquals("Logging stopped", stopped.name)
        assertEquals(mapOf("stop_reason" to "0", "overruns" to "1", "frames_dropped" to "2"), stopped.comment.properties)

        // The second file begins at the time the first ended, on the unit's clock.
        assertEquals(first.header.startNs + rotated.syncBase * 1_000, second.header.startNs)
        assertEquals(rotation["first_end_rel_us"].long(), rotated.syncBase)
        // Both carry the same configurations.
        assertEquals(first.attachments.map { it.comment.properties["crc"] }, second.attachments.map { it.comment.properties["crc"] })
    }

    @Test
    fun aFinalizedFilesScanStopsAtTheEndOfItsDataAndNeverReadsThePaddingOrTheEvent() {
        for (entry in closed) {
            val name = entry["name"].text()
            val scan = scan(set.bytes(name))
            val tail = entry["walk"].obj()["tail"].obj()
            val dataEnd = tail["data_block_end"].long()

            assertNull(scan.progress.problem, name)
            assertTrue(scan.progress.isComplete, name)
            assertEquals(dataEnd - entry["header_bytes"].long(), scan.progress.bytesRead, name)
            assertTrue(tail["bytes_after"].long() > 0, name)
            // The records counted are the ones asammdf reads: the padding's zeros and the event are none of them.
            val perId = entry["independent_parse"].obj()["records_per_record_id"].obj()
            val groups = scan.file.channelGroups
            for (group in groups.filter { !it.isVlsd && !it.isBusEvent }) {
                assertEquals(perId.getValue(group.recordId.toString()).long(), scan.scanner.cycleCount(group), "$name, ${group.name}")
            }
        }
    }

    @Test
    fun anUnclosedCopyReadsAsTheSamplesSoFarOfItsFamily() {
        val unclosed = scan(set.bytes("values_unclosed.mf4"))
        val whole = scan(set.bytes(values))
        val entry = set.entry("values_unclosed.mf4")

        assertFalse(unclosed.file.identification.isFinalized)
        assertNull(unclosed.progress.problem)
        assertTrue(unclosed.file.events.isEmpty())
        // Its header has everything the closed one's has but the event.
        assertEquals(open(set.bytes(values)).comment.properties, unclosed.file.comment.properties)
        assertEquals(4, unclosed.file.attachments.size)
        assertEquals(
            runSuspending { whole.file.readAttachment(whole.file.attachments[2]) }.toList(),
            runSuspending { unclosed.file.readAttachment(unclosed.file.attachments[2]) }.toList(),
        )

        for ((group, other) in unclosed.file.channelGroups.zip(whole.file.channelGroups)) {
            if (group.isVlsd) continue
            for ((channel, wholeChannel) in group.channels.zip(other.channels)) {
                if (channel === group.master) continue
                val got = unclosed.sink.of(unclosed.stored.getValue(channel))
                val expected = whole.sink.of(whole.stored.getValue(wholeChannel))
                assertEquals(expected, got, "${group.name}, ${channel.name}")
            }
        }
        // Every record: all of the data is there, and the stream ends at its last whole record.
        assertEquals(entry["size"].long() - entry["header_bytes"].long(), unclosed.progress.bytesRead)
    }

    @Test
    fun theReadersLimitsAndTheCnFlagsAgreeWithEachOther() {
        // asammdf's flags for a value channel: invalidation bit (2), plus limits valid (0x10) where there are limits.
        val entry = set.entry(values)
        val groups = entry["asammdf"].obj()["groups"].list().map { it.obj() }
        val file = open(set.bytes(values))
        for ((expected, group) in groups.zip(file.channelGroups)) {
            for (e in expected["channels"].list().map { it.obj() }) {
                val channel = group.channels.first { it.name == e["name"] }
                assertEquals(e["flags"].int(), channel.flags, channel.name)
            }
        }
        assertContentEquals(
            listOf("ignition", "coolant_temp", "brake", "launch"),
            file.channelGroups.filter { g -> !g.isVlsd && g.channels.first { it.name == g.name }.limits != null }.map { it.name },
        )
    }
}
