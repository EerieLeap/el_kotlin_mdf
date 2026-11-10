package com.eerie_leap.mdf

import com.eerie_leap.mdf.writer.InMemoryMdfSink
import com.eerie_leap.mdf.writer.Mdf4BusLogWriter
import com.eerie_leap.mdf.writer.MdfSink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val STREAMED_FRAMES = 100_000
private const val BUFFER_LIMIT = 64 * 1024

class Mdf4BusLogWriterTest {
    private fun expectedData(frames: List<LoggedFrame>) = frames.filterIsInstance<LoggedFrame.Data>().map { expectedFrame(it) }

    private fun expectedRemote(frames: List<LoggedFrame>) = frames.filterIsInstance<LoggedFrame.Remote>().map { expectedFrame(it) }

    @Test
    fun readsBackEveryFrameField() {
        val frames = sampleFrames(300)

        val read = readBack(writeBusLog(frames))

        assertEquals(expectedData(frames), read.data)
        assertEquals(expectedRemote(frames), read.remote)
    }

    @Test
    fun readsBackFieldByFieldNotJustEqual() {
        val frames = listOf(
            LoggedFrame.Data(1_500_250, 3, 0x18DAF110, byteArrayOf(1, 2, 3), isExtended = true, isTransmit = true),
            LoggedFrame.Data(2_000_000, 1, 0x123, ByteArray(64) { it.toByte() }, isFd = true, isBitRateSwitch = true, hasErrorState = true),
            LoggedFrame.Remote(3_000_000, 2, 0x7DF, 5),
        )

        val read = readBack(writeBusLog(frames))

        val extended = read.data[0]
        assertEquals(1.50025, extended.time)
        assertEquals(3, extended.bus)
        assertEquals(0x18DAF110L, extended.id)
        assertTrue(extended.isExtended && extended.isTransmit)
        assertFalse(extended.isFd || extended.isBitRateSwitch || extended.hasErrorState || extended.isRemote)
        assertEquals(3, extended.dlc)
        assertContentEquals(byteArrayOf(1, 2, 3), extended.data)
        val fd = read.data[1]
        assertTrue(fd.isFd && fd.isBitRateSwitch && fd.hasErrorState)
        assertFalse(fd.isExtended || fd.isTransmit)
        assertEquals(15, fd.dlc)
        assertEquals(64, fd.dataLength)
        val remote = read.remote.single()
        assertTrue(remote.isRemote)
        assertEquals(0x7DFL, remote.id)
        assertEquals(5, remote.dlc)
        assertEquals(0, remote.data.size)
        assertFalse(remote.isExtended)
    }

    @Test
    fun writesEveryCanFdLengthAndTheExtendedIdLimits() {
        val lengths = (0..8) + listOf(12, 16, 20, 24, 32, 48, 64)
        val frames = lengths.mapIndexed { i, length ->
            LoggedFrame.Data(
                i * 10L,
                0,
                if (i % 2 ==
                    0
                ) {
                    0x1FFFFFFFL
                } else {
                    0x7FFL
                },
                ByteArray(length) { (it + 1).toByte() },
                i % 2 == 0,
                isFd = length > 8,
            )
        } + LoggedFrame.Data(1_000, 0, 0, ByteArray(0)) + LoggedFrame.Remote(1_010, 0, 0x1FFFFFFFL, 15, isExtended = true)

        val read = readBack(writeBusLog(frames))

        assertEquals(expectedData(frames), read.data)
        assertEquals(listOf(9, 10, 11, 12, 13, 14, 15), read.data.filter { it.dataLength > 8 }.map { it.dlc })
        assertEquals(expectedRemote(frames), read.remote)
    }

    @Test
    fun anExtendedIdWithItsTopBitSetIsNotMistakenForAFlag() {
        val frames =
            listOf(LoggedFrame.Data(5, 0, 0x1FFFFFFFL, byteArrayOf(9), isExtended = true), LoggedFrame.Data(6, 0, 0x7FF, byteArrayOf(9)))

        val read = readBack(writeBusLog(frames))

        assertEquals(listOf(true, false), read.data.map { it.isExtended })
        assertEquals(listOf(0x1FFFFFFFL, 0x7FFL), read.data.map { it.id })
    }

    @Test
    fun writesTheHeadTheWayAReaderExpects() {
        val frames = sampleFrames(50)

        val file = open(writeBusLog(frames))

        assertTrue(file.identification.isFinalized)
        assertEquals(410, file.identification.version)
        assertEquals("ELMDF", file.identification.program)
        assertTrue(file.identification.unfinalized.isEmpty())
        assertEquals(TEST_OPTIONS.startNs, file.header.startNs)
        assertEquals(10, file.header.timeClass)
        assertFalse(file.header.isLocalTime)
        val tool = file.history.single()
        assertEquals("el_kotlin_mdf", tool.tool)
        assertEquals("Eerie Leap", tool.vendor)
        assertEquals("9.8.7", tool.version)
        assertEquals("Recorded by the tests", file.comment.text)
        assertEquals(TEST_OPTIONS.properties.toMap(), file.comment.properties)
        assertEquals(listOf("", "CAN_DataFrame", "CAN_RemoteFrame"), file.channelGroups.map { it.name })
        val (payloads, data, remote) = file.channelGroups
        assertTrue(payloads.isVlsd)
        assertTrue(data.isBusEvent && data.isPlainBusEvent && remote.isBusEvent)
        assertEquals(frames.count { it is LoggedFrame.Data }.toLong(), data.cycleCount)
        assertEquals(data.cycleCount, payloads.cycleCount)
        assertEquals(frames.filterIsInstance<LoggedFrame.Data>().sumOf { it.data.size.toLong() }, payloads.vlsdBytes)
        assertEquals(frames.count { it is LoggedFrame.Remote }.toLong(), remote.cycleCount)
        assertEquals(MdfSyncType.Time, data.masterAxis)
        assertTrue(CanFrames.otherBusEventGroups(file).isEmpty())
        assertTrue(file.skipped.isEmpty() && file.issues.isEmpty())
    }

    @Test
    fun writesTheEventsOfAFinishedFile() {
        val sink = InMemoryMdfSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)
        writer.addFrame(1_000, 0, 0x100, byteArrayOf(1))
        writer.addEvent(1_250_000, "gap", "no frames for 2 s on bus 0")
        writer.addEvent(2_000_000, "bus off", null, MdfEventType.AcquisitionInterrupt, MdfEventCause.Error)
        writer.addFrame(2_500_000, 0, 0x101, byteArrayOf(2))
        writer.addEvent(2_500_001, "end <of> \"log\" & more", "stopped by the user", MdfEventType.StopRecordingTrigger, MdfEventCause.User)
        assertEquals(3, writer.eventCount)
        writer.finish()

        val file = open(sink.toByteArray())

        assertEquals(listOf("gap", "bus off", "end <of> \"log\" & more"), file.events.map { it.name })
        assertEquals(listOf("no frames for 2 s on bus 0", "", "stopped by the user"), file.events.map { it.comment.text })
        assertEquals(
            listOf(MdfEventType.Marker, MdfEventType.AcquisitionInterrupt, MdfEventType.StopRecordingTrigger),
            file.events.map { it.type },
        )
        assertEquals(listOf(MdfEventCause.Other, MdfEventCause.Error, MdfEventCause.User), file.events.map { it.cause })
        assertEquals(listOf(MdfEventRange.Point), file.events.map { it.range }.distinct())
        assertEquals(listOf(MdfSyncType.Time), file.events.map { it.syncType }.distinct())
        listOf(1.25, 2.0, 2.500001).zip(file.events.map { it.value }).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-9) }
        assertEquals(2, readBack(file).data.size)
    }

    @Test
    fun aFileWithoutEventsHasNone() {
        assertTrue(open(writeBusLog(sampleFrames(10))).events.isEmpty())
    }

    @Test
    fun anUnfinishedFileIsReadAsFarAsItGoes() {
        val frames = sampleFrames(120)

        val file = open(writeBusLog(frames, finish = false))

        assertFalse(file.identification.isFinalized)
        assertEquals(
            setOf(UnfinalizedFlag.CycleCounts, UnfinalizedFlag.LastDataBlockLength, UnfinalizedFlag.VlsdDataBytes),
            file.identification.unfinalized,
        )
        val read = readBack(file)
        assertEquals(expectedData(frames), read.data)
        assertEquals(expectedRemote(frames), read.remote)
    }

    @Test
    fun anUnfinishedFileCutAtAnyByteHoldsItsWholeRecords() {
        val frames = sampleFrames(70)
        val empty = InMemoryMdfSink().also { Mdf4BusLogWriter.begin(it, TEST_OPTIONS) }
        val dataStart = empty.position.toInt()
        // A frame is there once its last record is: the payload and the frame record of a data frame, the one record of a remote one.
        val ends = ArrayList<Int>()
        var at = dataStart
        for (frame in frames) {
            at += if (frame is LoggedFrame.Data) 1 + 4 + frame.data.size + 1 + 8 + 15 else 1 + 8 + 7
            ends.add(at)
        }
        val whole = writeBusLog(frames, finish = false)
        assertEquals(at, whole.size)

        var previous = 0
        for (cut in dataStart..whole.size) {
            val read = readBack(whole.copyOf(cut))

            val complete = ends.count { it <= cut }
            assertEquals(expectedData(frames.take(complete)), read.data, "cut at $cut")
            assertEquals(expectedRemote(frames.take(complete)), read.remote, "cut at $cut")
            assertTrue(read.data.size + read.remote.size >= previous)
            previous = read.data.size + read.remote.size
        }
        assertEquals(frames.size, previous)
    }

    @Test
    fun aFlushMakesEverythingBeforeItReadable() {
        val sink = InMemoryMdfSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)
        val frames = sampleFrames(200)

        for ((i, frame) in frames.withIndex()) {
            writer.add(frame)
            if (i % 37 == 36) {
                writer.flush()
                val written = frames.take(i + 1)
                val read = readBack(sink.toByteArray())
                assertEquals(expectedData(written), read.data, "after $i")
                assertEquals(expectedRemote(written), read.remote, "after $i")
            }
        }
        writer.finish()

        assertEquals(expectedData(frames), readBack(sink.toByteArray()).data)
    }

    @Test
    fun aFlushBringsTheLengthsAndCountsUpToDateWhereTheyAreWritten() {
        val sink = InMemoryMdfSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)
        val frames = sampleFrames(30)
        frames.forEach { writer.add(it) }
        writer.flush()

        val file = open(sink.toByteArray())

        val (payloads, data, remote) = file.channelGroups
        assertEquals(frames.count { it is LoggedFrame.Data }.toLong(), data.cycleCount)
        assertEquals(data.cycleCount, payloads.cycleCount)
        assertEquals(frames.count { it is LoggedFrame.Remote }.toLong(), remote.cycleCount)
        assertEquals(frames.filterIsInstance<LoggedFrame.Data>().sumOf { it.data.size.toLong() }, payloads.vlsdBytes)
    }

    @Test
    fun aFinishedFileHasNothingMoreToAddAndFinishingAgainChangesNothing() {
        val sink = InMemoryMdfSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)
        writer.addFrame(1, 0, 1, ByteArray(0))
        writer.finish()
        val bytes = sink.toByteArray()

        writer.finish()

        assertTrue(writer.isFinished)
        assertContentEquals(bytes, sink.toByteArray())
        assertFailsWith<IllegalStateException> { writer.addFrame(2, 0, 1, ByteArray(0)) }
        assertFailsWith<IllegalStateException> { writer.addEvent(2, "late") }
        assertFailsWith<IllegalStateException> { writer.flush() }
    }

    @Test
    fun refusesWhatCannotBeWrittenAsAFrame() {
        val sink = InMemoryMdfSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)

        assertFailsWith<IllegalArgumentException> { writer.addFrame(-1, 0, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { writer.addFrame(0, 256, 1, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { writer.addFrame(0, 0, 0x800, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { writer.addFrame(0, 0, 0x2000_0000, ByteArray(0), isExtended = true) }
        assertFailsWith<IllegalArgumentException> { writer.addFrame(0, 0, 1, ByteArray(9)) }
        assertFailsWith<IllegalArgumentException> { writer.addFrame(0, 0, 1, ByteArray(13), isFd = true) }
        assertFailsWith<IllegalArgumentException> { writer.addFrame(0, 0, 1, ByteArray(4), length = 5) }
        assertFailsWith<IllegalArgumentException> { writer.addRemoteFrame(0, 0, 1, 16) }
        assertFailsWith<IllegalArgumentException> { writer.addEvent(-1, "x") }
        assertEquals(0, writer.frameCount)
        // Nothing the writer refused reached the file.
        writer.finish()
        val read = readBack(sink.toByteArray())
        assertTrue(read.data.isEmpty() && read.remote.isEmpty())
    }

    @Test
    fun refusesASinkThatAlreadyHoldsBytes() {
        val sink = InMemoryMdfSink().apply { append(byteArrayOf(1)) }

        assertFailsWith<IllegalArgumentException> { Mdf4BusLogWriter.begin(sink, TEST_OPTIONS) }
    }

    @Test
    fun writesAFrameFromTheFirstBytesOfABufferItReuses() {
        val sink = InMemoryMdfSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)
        val buffer = ByteArray(64) { it.toByte() }

        writer.addFrame(10, 0, 0x55, buffer, length = 3)
        writer.addFrame(20, 0, 0x56, buffer, isFd = true, length = 24)
        writer.finish()

        val read = readBack(sink.toByteArray()).data
        assertContentEquals(byteArrayOf(0, 1, 2), read[0].data)
        assertContentEquals(ByteArray(24) { it.toByte() }, read[1].data)
    }

    @Test
    fun anEmptyLogReadsAsEmpty() {
        val read = readBack(writeBusLog(emptyList()))

        assertTrue(read.data.isEmpty() && read.remote.isEmpty())
    }

    @Test
    fun streamsAHundredThousandFramesThroughASmallBuffer() {
        val sink = CountingSink()
        val writer = Mdf4BusLogWriter.begin(sink, TEST_OPTIONS)
        val data = ByteArray(8)

        for (i in 0 until STREAMED_FRAMES) {
            for (b in data.indices) data[b] = (i + b).toByte()
            if (i % 50 == 49) {
                writer.addRemoteFrame(i * 100L, 1, (i % 0x700).toLong(), i % 9)
            } else {
                writer.addFrame(i * 100L, i % 4, (i % 0x700).toLong(), data, isExtended = false, isTransmit = i % 3 == 0)
            }
            if (i % 20_000 == 19_999) writer.flush()
        }
        writer.finish()

        assertTrue(sink.largestAppend <= BUFFER_LIMIT, "an append of ${sink.largestAppend} bytes")
        assertTrue(sink.overwrites < 100, "${sink.overwrites} overwrites")
        val file = open(sink.bytes())
        assertTrue(file.identification.isFinalized)
        val read = readBack(file)
        val remoteCount = STREAMED_FRAMES / 50
        assertEquals(STREAMED_FRAMES - remoteCount, read.data.size)
        assertEquals(remoteCount, read.remote.size)
        assertEquals(STREAMED_FRAMES.toLong() - remoteCount, writer.frameCount)
        var expectedIndex = 0
        for (frame in read.data) {
            while (expectedIndex % 50 == 49) expectedIndex++
            assertEquals(expectedIndex * 100 / 1_000_000.0, frame.time)
            assertEquals((expectedIndex % 0x700).toLong(), frame.id)
            assertEquals(expectedIndex % 3 == 0, frame.isTransmit)
            assertEquals(expectedIndex % 4, frame.bus)
            assertContentEquals(ByteArray(8) { (expectedIndex + it).toByte() }, frame.data)
            expectedIndex++
        }
        assertEquals(listOf(1), read.remote.map { it.bus }.distinct())
    }

    /** A sink that keeps the file and notes the largest piece appended at once. */
    private class CountingSink : MdfSink {
        private val inner = InMemoryMdfSink()
        var largestAppend = 0
            private set
        var overwrites = 0
            private set

        override val position: Long get() = inner.position

        override fun append(bytes: ByteArray, offset: Int, length: Int) {
            largestAppend = maxOf(largestAppend, length)
            inner.append(bytes, offset, length)
        }

        override fun overwrite(position: Long, bytes: ByteArray) {
            overwrites++
            inner.overwrite(position, bytes)
        }

        override fun flush() = inner.flush()

        fun bytes(): ByteArray = inner.toByteArray()
    }
}
