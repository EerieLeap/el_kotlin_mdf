package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanFirmwareLogTest {
    private val oil = FirmwareLogWriter.ValueSensor("oil_temp", "°C", "Oil temperature: sump", FirmwareLogWriter.Raw("X * 0.1 + 5"))
    private val boost = FirmwareLogWriter.ValueSensor("boost", "bar")

    // Ticks at 100 ms: both sensors every tick, the boost reading invalid at the third.
    private fun log(finished: Boolean = true, ticks: Int = 6): ByteArray = FirmwareLogWriter().apply {
        val o = sensor(oil)
        val b = sensor(boost)
        for (tick in 0 until ticks) {
            val time = tick * 0.1
            value(o, time, 80f + tick, raw = (75f + tick) * 10f)
            if (tick == 2) value(b, time, 0f, invalid = 1) else value(b, time, 1f + tick * 0.5f)
        }
    }.build(finished)

    private fun channel(file: MdfFile, name: String): MdfChannel = file.channelGroups.flatMap { it.channels }.first { it.name == name }

    @Test
    fun scansTheSamplesOfAChannel() {
        val file = open(log())
        val request = MdfChannelRequest(channel(file, "oil_temp"))

        val sink = scanAll(file, listOf(request))

        val samples = sink.of(request)
        assertEquals(6, samples.size)
        assertEquals(List(6) { it * 0.1 }, samples.map { it.time })
        assertEquals(List(6) { 80.0 + it }, samples.map { it.value })
        assertTrue(samples.none { it.isInvalid })
    }

    @Test
    fun marksTheSamplesTheWriterInvalidated() {
        val file = open(log())
        val request = MdfChannelRequest(channel(file, "boost"))

        val samples = scanAll(file, listOf(request)).of(request)

        assertEquals(listOf(false, false, true, false, false, false), samples.map { it.isInvalid })
        assertEquals(0.0, samples[2].value, 0.0)
        assertEquals(2.5, samples[3].value, 0.0)
    }

    @Test
    fun convertsTheRawChannelToTheValueChannel() {
        val file = open(log())
        val value = MdfChannelRequest(channel(file, "oil_temp"))
        val converted = MdfChannelRequest(channel(file, "oil_temp.raw"))
        val stored = MdfChannelRequest(channel(file, "oil_temp.raw"), stored = true)

        val sink = scanAll(file, listOf(value, converted, stored))

        // The firmware's raw channel with the formula X * 0.1 + 5 is the value channel, within float rounding.
        for ((v, c) in sink.of(value).zip(sink.of(converted))) assertEquals(v.value, c.value, 1e-4)
        assertEquals(List(6) { (75.0 + it) * 10.0 }, sink.of(stored).map { it.value })
    }

    @Test
    fun givesTheGroupsMasterConvertedAsTheTime() {
        val file = open(log())
        val request = MdfChannelRequest(channel(file, "boost"))

        val times = scanAll(file, listOf(request)).of(request).map { it.time }

        assertEquals(listOf(0.0, 0.1, 0.2, 0.30000000000000004, 0.4, 0.5), times)
    }

    @Test
    fun readsOnlyTheChannelsAskedFor() {
        val file = open(log())
        val request = MdfChannelRequest(channel(file, "oil_temp"))

        val sink = scanAll(file, listOf(request))

        assertEquals(setOf(request), sink.samples.keys)
    }

    @Test
    fun countsTheRecordsOfEachGroupItself() {
        val file = open(log(finished = false))
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), valueRequests(file), sink) }
        runSuspending { scanner.advance() }

        // The unfinished file says 0 cycles for each group; the scan found them.
        assertEquals(listOf(0L, 0L), file.channelGroups.map { it.cycleCount })
        assertEquals(listOf(6L, 6L), file.channelGroups.map { scanner.cycleCount(it) })
    }

    @Test
    fun scansAFileItsWriterDidntFinish() {
        val finished = scanAll(open(log(finished = true)))
        val unfinished = scanAll(open(log(finished = false)))

        assertEquals(finished.samples.values.map { it.toList() }, unfinished.samples.values.map { it.toList() })
        assertEquals(3, unfinished.samples.size)
    }

    @Test
    fun reportsProgressAndCompleteness() {
        val finishedFile = open(log(finished = true))
        val finished =
            runSuspending { MdfScanner.create(finishedFile, finishedFile.groups.single(), emptyList(), CollectingSink()).advance() }

        assertTrue(finished.isComplete)
        assertNull(finished.problem)
        assertEquals(finished.bytesAvailable, finished.bytesRead)
        assertEquals(finished.bytesDeclared, finished.bytesRead)
        assertEquals(6L * 18 + 6 * 14, finished.bytesRead)

        // An unfinished file has no declared end: it is never complete, however much was read.
        val unfinishedFile = open(log(finished = false))
        val unfinished =
            runSuspending { MdfScanner.create(unfinishedFile, unfinishedFile.groups.single(), emptyList(), CollectingSink()).advance() }

        assertFalse(unfinished.isComplete)
        assertNull(unfinished.bytesDeclared)
        assertEquals(finished.bytesRead, unfinished.bytesRead)
    }

    @Test
    fun readsALogWithTwoByteRecordIds() {
        val bytes = FirmwareLogWriter(recordIdSize = 2).apply {
            val a = sensor(FirmwareLogWriter.ValueSensor("a"))
            val b = sensor(FirmwareLogWriter.ValueSensor("b"))
            for (i in 0 until 4) {
                value(a, i * 0.5, i.toFloat())
                value(b, i * 0.5, (0 - i).toFloat())
            }
        }.build()
        val file = open(bytes)
        val requests = valueRequests(file)

        val sink = scanAll(file, requests)

        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), sink.of(requests[0]).map { it.value })
        assertEquals(listOf(0.0, -1.0, -2.0, -3.0), sink.of(requests[1]).map { it.value })
    }

    @Test
    fun givesNothingForALogWithoutRecords() {
        val file = open(FirmwareLogWriter().apply { sensor(boost) }.build())

        val sink = scanAll(file)

        assertTrue(sink.samples.isEmpty())
    }

    @Test
    fun stopsAtTheLastWholeRecordOfAFileCutAtAnyByte() {
        val whole = log()
        val full = scanAll(open(whole)).samples.mapKeys { it.key.channel.name }
        val total = full.values.sumOf { it.size }
        val headerEnd = whole.size - (6 * 18 + 6 * 14)

        // Cut the file at every byte inside its records: the scan never throws and yields exactly the whole records before the cut.
        for (size in headerEnd..whole.size) {
            val cut = whole.copyOf(size)
            val file = open(cut)
            val sink = scanAll(file)

            val samples = sink.samples.mapKeys { it.key.channel.name }
            for ((name, expected) in full) {
                val got = samples[name].orEmpty()
                assertTrue(got.size <= expected.size, "cut at $size: $name")
                assertEquals(expected.take(got.size), got, "cut at $size: $name")
            }
            val count = samples.values.sumOf { it.size }
            assertTrue(count <= total)
            if (size == whole.size) assertEquals(total, count)
            if (size == headerEnd) assertEquals(0, count)
        }
    }

    @Test
    fun stopsAtTheLastWholeRecordOfAnUnfinishedFileCutAtAnyByte() {
        val whole = log(finished = false)
        val full = scanAll(open(whole)).samples.mapKeys { it.key.channel.name }
        val headerEnd = whole.size - (6 * 18 + 6 * 14)

        for (size in headerEnd..whole.size) {
            val samples = scanAll(open(whole.copyOf(size))).samples.mapKeys { it.key.channel.name }
            for ((name, expected) in full) {
                val got = samples[name].orEmpty()
                assertEquals(expected.take(got.size), got, "cut at $size: $name")
            }
        }
    }

    @Test
    fun aFileScannedInStepsAsItGrowsEqualsOneScan() {
        for (finished in listOf(true, false)) {
            val whole = log(finished)
            val once = scanAll(open(whole)).samples.mapKeys { it.key.channel.name }
            val headerEnd = whole.size - (6 * 18 + 6 * 14)

            val source = GrowingSource(whole, headerEnd)
            val file = runSuspending { Mdf4Reader.open(source) }
            val sink = CollectingSink()
            val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), valueRequests(file), sink) }
            var visible = headerEnd
            while (visible < whole.size) {
                // A step of 7 bytes cuts records in the middle, which the scan keeps for the next call.
                visible = minOf(whole.size, visible + 7)
                source.visible = visible
                runSuspending { scanner.advance() }
            }

            assertEquals(once.mapValues { it.value.toList() }, sink.samples.mapKeys { it.key.channel.name }.mapValues { it.value.toList() })
        }
    }

    @Test
    fun anAdvanceCanBeLimitedToAnAmountOfData() {
        val file = open(log())
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), valueRequests(file), sink) }

        val first = runSuspending { scanner.advance(maxBytes = 1) }

        // At least one record, and the rest left for the next call.
        assertTrue(first.bytesRead in 1 until first.bytesAvailable)
        val last = runSuspending { scanner.advance() }
        assertEquals(last.bytesAvailable, last.bytesRead)
        assertEquals(18, sink.samples.values.sumOf { it.size })
    }
}
