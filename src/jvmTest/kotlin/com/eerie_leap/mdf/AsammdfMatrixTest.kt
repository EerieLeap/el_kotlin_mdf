package com.eerie_leap.mdf

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The matrix of files asammdf 8.8.27 writes (`resources/asammdf/make_matrix_files.py`): MDF 4.00, 4.10 and 4.20, data plain, deflated
 * and transposed and deflated, in lists of blocks; strings in signal data blocks; masters that are angles, distances and indexes;
 * big-endian values; invalid samples; every kind of conversion it can write; arrays, events and attachments; and the column storage
 * that its writer reaches only with three stubs. Each is read sample for sample against what asammdf reads, or, where it can't read
 * its own file, against the signals it was made from.
 */
class AsammdfMatrixTest {
    private fun expected(case: String): Map<String, Any?> = MiniJson.parse(resource("asammdf/expected_$case.json").decodeToString()).obj()

    private fun file(name: String): MdfFile = open(resource("asammdf/$name.mf4"))

    private fun assertClose(want: Double, got: Double, what: String, relative: Double = 1e-6) {
        assertTrue(abs(want - got) <= relative * (1.0 + abs(want)), "$what: expected $want but was $got")
    }

    // The channel group of the file that holds [name], and the channel.
    private fun find(file: MdfFile, group: MdfChannelGroup?, name: String): Pair<MdfChannelGroup, MdfChannel> {
        val groups = if (group != null) listOf(group) else file.channelGroups
        for (candidate in groups) {
            candidate.allChannels.firstOrNull { it.name == name }?.let { return candidate to it }
        }
        error("no channel $name in the file")
    }

    private fun dataGroupOf(file: MdfFile, group: MdfChannelGroup): MdfDataGroup = file.groups.first { data ->
        data.channelGroups.any {
            it ===
                group
        }
    }

    private class Scanned(val converted: List<Sample>, val stored: List<Sample>, val texts: List<String>)

    private fun scan(file: MdfFile, group: MdfChannelGroup, channel: MdfChannel): Scanned {
        val converted = MdfChannelRequest(channel)
        val stored = MdfChannelRequest(channel, stored = true)
        val sink = CollectingSink()
        val requests = if (channel.dataType.isNumber) listOf(converted, stored) else listOf(converted)
        val scanner = runSuspending { MdfScanner.create(file, dataGroupOf(file, group), requests, sink) }
        val progress = runSuspending { scanner.advance() }
        assertNull(progress.problem, "channel ${channel.name}")
        assertTrue(scanner.rejected.isEmpty(), "channel ${channel.name}: ${scanner.rejected}")
        return Scanned(sink.of(converted), sink.of(stored), sink.texts[converted].orEmpty())
    }

    private fun doubles(value: Any?): List<Double> = value.list().map { it.double() }

    // Checks every channel of the expected group against the same channel of the file, which is asked for by name.
    private fun checkGroup(file: MdfFile, group: MdfChannelGroup?, expected: Map<String, Any?>, label: String) {
        for (channel in expected["channels"].list().map { it.obj() }) {
            val name = channel["name"].text()
            val (owner, read) = find(file, group, name)
            if (read === owner.master || (channel["array"] != null)) {
                if (channel["array"] != null) checkArray(file, owner, read, channel, "$label $name")
                continue
            }
            checkChannel(file, owner, read, channel, "$label $name")
        }
    }

    private fun checkChannel(
        file: MdfFile,
        group: MdfChannelGroup,
        read: MdfChannel,
        expected: Map<String, Any?>,
        what: String,
    ) {
        val scanned = scan(file, group, read)
        val times = doubles(expected["times"])
        val converted = expected["converted"].list()
        val conversion = read.conversion
        if (converted.firstOrNull() is String && read.dataType.isString) {
            assertEquals(converted.map { it.text() }, scanned.texts, "$what: texts")
        } else if (converted.firstOrNull() is String && conversion != null) {
            // A table to texts: the scan gives the stored numbers, and the conversion words them as asammdf does.
            assertEquals(converted.map { it.text() }, scanned.stored.map { conversion.toText(it.value) }, "$what: labels")
        } else {
            assertEquals(times.size, scanned.converted.size, "$what: count")
            converted.forEachIndexed { i, want -> assertClose(want.double(), scanned.converted[i].value, "$what[$i]") }
        }
        if (scanned.converted.isNotEmpty()) assertEquals(times.size, scanned.converted.size, "$what: samples")
        if (scanned.converted.isNotEmpty()) {
            times.forEachIndexed { i, want -> assertClose(want, scanned.converted[i].time, "$what: time $i", 1e-9) }
        }
        expected["stored"]?.let { stored -> assertEquals(doubles(stored), scanned.stored.map { it.value }, "$what: stored") }
        expected["invalid"]?.let { invalid ->
            assertEquals(invalid.list().map { it as Boolean }, scanned.converted.map { it.isInvalid }, "$what: invalidation bits")
        }
        if (expected["unit"] != null &&
            expected["unit"].text().isNotEmpty()
        ) {
            assertEquals(expected["unit"].text(), read.unit, "$what: unit")
        }
    }

    private fun checkArray(
        file: MdfFile,
        group: MdfChannelGroup,
        container: MdfChannel,
        expected: Map<String, Any?>,
        what: String,
    ) {
        val array = checkNotNull(container.array) { "$what: no array" }
        assertNull(array.unreadReason, what)
        val samples = expected["array"].obj().values.single().list().map { row -> doubles(row) }
        val elements = container.members
        assertEquals(samples.first().size, elements.size, "$what: elements")
        for ((index, element) in elements.withIndex()) {
            val scanned = scan(file, group, element)
            assertEquals(samples.size, scanned.stored.size, "$what ${element.name}: count")
            samples.forEachIndexed { sample, row ->
                assertEquals(row[index], scanned.stored[sample].value, "$what ${element.name}[$sample]")
            }
        }
    }

    private fun checkFile(name: String, case: String = name, version: Any? = null) {
        val file = file(name)
        val want = expected(case)
        val groups = want["groups"].list().map { it.obj() }

        assertEquals(version ?: want["version"], file.identification.versionText, name)
        assertEquals(groups.size, file.channelGroups.size, "$name: groups")
        assertTrue(file.issues.isEmpty(), "$name: ${file.issues}")
        groups.forEachIndexed { index, group -> checkGroup(file, file.channelGroups[index], group, name) }
    }

    @Test
    fun readsListsOfBlocksInEveryVersionAndEveryWayOfCompressingThem() {
        val files = listOf(
            "lists_4.00_none",
            "lists_4.10_none",
            "lists_4.10_deflate",
            "lists_4.10_transposed",
            "lists_4.20_none",
            "lists_4.20_deflate",
            "lists_4.20_transposed",
        )
        for (name in files) {
            checkFile(name, "lists", version = name.substringAfter("lists_").substringBefore('_'))
        }
    }

    @Test
    fun anAsammdfFileOfManyBlocksIsReadFromItsLists() {
        // The lists really are lists: the data of the first group is in several blocks, listed.
        for (name in listOf("lists_4.10_none", "lists_4.10_deflate")) {
            val file = file(name)
            val data = file.groups.first()

            assertTrue(data.dataLink > 0, name)
            val sink = scanAll(file)
            assertEquals(4, sink.samples.size, name)
        }
    }

    @Test
    fun readsEveryKindOfConversionAsammdfWrites() {
        checkFile("conversions_more")
        val conversions = file("conversions_more").channelGroups.single().channels.associate { it.name to it.conversion }
        assertEquals(
            mapOf("alg" to 3, "interp" to 4, "ranges" to 8, "states" to 7, "rational" to 2),
            conversions.filterKeys { it != "time" }.mapValues { it.value?.code },
        )
    }

    @Test
    fun readsStringsFromSignalDataBlocksPlainAndZipped() {
        checkFile("strings")
        checkFile("strings_zipped", "strings_zipped")
    }

    @Test
    fun readsMastersThatAreAnAngleADistanceOrAnIndex() {
        for ((label, axis) in listOf("angle" to MdfSyncType.Angle, "distance" to MdfSyncType.Distance, "index" to MdfSyncType.Index)) {
            val name = "master_$label"
            checkFile(name)
            assertEquals(axis, file(name).channelGroups.single().masterAxis, name)
        }
    }

    @Test
    fun readsSamplesWithTheirInvalidationBits() {
        checkFile("invalid")
        val scanned = scanAll(file("invalid"))
        assertEquals(60, scanned.samples.values.single().size)
        assertEquals(15, scanned.samples.values.single().count { it.isInvalid })
    }

    @Test
    fun readsSeveralGroupsOfTheirOwnTimes() {
        checkFile("many_groups")
        assertEquals(6, file("many_groups").groups.size)
    }

    @Test
    fun readsTheElementsOfArraysAsChannelsOfTheirOwn() {
        checkFile("arrays")
        val file = file("arrays")
        val grid = file.channelGroups[1].allChannels.first { it.name == "grid" }

        assertEquals(listOf(2L, 3L), grid.array?.dimensions)
        assertEquals(
            listOf("grid[0][0]", "grid[0][1]", "grid[0][2]", "grid[1][0]", "grid[1][1]", "grid[1][2]"),
            grid.members.map {
                it.name
            },
        )
        assertTrue(!grid.isNumeric)
    }

    @Test
    fun readsTheEventsAsammdfWrites() {
        val want = expected("events")["events"].list().map { it.obj() }
        val events = file("events").events

        assertEquals(want.map { it["name"] }, events.map { it.name })
        for ((e, read) in want.zip(events)) {
            assertEquals(e["comment"], read.comment.text, read.name)
            assertEquals(e["type"].int(), read.type.code, read.name)
            assertEquals(e["range"].int(), read.range.code, read.name)
            assertEquals(e["cause"].int(), read.cause.code, read.name)
            assertEquals(MdfSyncType.Time, read.syncType, read.name)
            assertClose(e["base"].double() * e["factor"].double(), read.value, read.name, 1e-12)
        }
    }

    @Test
    fun readsTheAttachmentsAsammdfWritesAndTheirBytes() {
        val want = expected("attachments")["attachments"].list().map { it.obj() }
        val file = file("attachments")

        assertEquals(want.map { it["name"] }, file.attachments.map { it.fileName })
        for ((a, read) in want.zip(file.attachments)) {
            assertEquals(a["mime"], read.mimeType, read.fileName)
            assertEquals(a["comment"], read.comment.text, read.fileName)
            assertTrue(read.isEmbedded, read.fileName)
            assertEquals(a["compressed"] == true, read.isCompressed, read.fileName)
            val bytes = a["bytes"].list().map { it.int().toByte() }.toByteArray()
            assertContentEquals(bytes, runSuspending { file.readAttachment(read) }, read.fileName)
            assertEquals(bytes.size.toLong(), read.originalSize)
            assertNotNull(read.md5)
        }
    }

    @Test
    fun readsColumnStorageAsammdfWritesAgainstTheSignalsItWasMadeFrom() {
        for (name in listOf("columns", "columns_zipped")) {
            val file = file(name)
            val want = expected(name)["groups"].list().single().obj()

            // A group for the time and one for each signal, which says its master is the first's.
            assertEquals(3, file.channelGroups.size, name)
            assertTrue(file.channelGroups.drop(1).all { it.hasRemoteMaster && it.remoteMaster === file.channelGroups.first() }, name)
            assertEquals(MdfSyncType.Time, file.channelGroups[1].masterAxis, name)
            checkGroup(file, null, want, name)
        }
    }

    @Test
    fun readsAnUnfinishedFileOfAnotherWritersLayoutToTheEndOfItsLastBlock() {
        // asammdf's own file with the marks of a writer that was cut: the last data block says its length is a header's.
        val bytes = resource("asammdf/lists_4.10_none.mf4")
        val whole = open(bytes)
        val last = whole.groups.last().dataLink.toInt()
        val patched = bytes.copyOf()
        "UnFinMF ".encodeToByteArray().copyInto(patched, 0)
        patched[60] = (0x01 or 0x04).toByte()
        ByteWriter().u64(TestBlock.HEADER_SIZE.toLong()).toByteArray().copyInto(patched, last + 8)

        val unfinished = open(patched)
        val request = unfinished.channelGroups.last().channels.last().let { MdfChannelRequest(it) }
        val sink = CollectingSink()
        runSuspending { MdfScanner.create(unfinished, unfinished.groups.last(), listOf(request), sink).advance() }

        assertTrue(!unfinished.identification.isFinalized)
        assertEquals(12, sink.of(request).size)
        val wholeRequest = MdfChannelRequest(whole.channelGroups.last().channels.last())
        val wholeSink = CollectingSink()
        runSuspending { MdfScanner.create(whole, whole.groups.last(), listOf(wholeRequest), wholeSink).advance() }
        assertEquals(wholeSink.of(wholeRequest), sink.of(request))
    }
}
