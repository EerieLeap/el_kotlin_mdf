package com.eerie_leap.mdf

import com.eerie_leap.mdf.writer.InMemoryMdfSink
import com.eerie_leap.mdf.writer.Mdf4BusLogOptions
import com.eerie_leap.mdf.writer.Mdf4BusLogWriter

/** A frame for the bus log tests to write and to expect back. */
internal sealed interface LoggedFrame {
    val timeMicros: Long

    class Data(
        override val timeMicros: Long,
        val bus: Int,
        val id: Long,
        val data: ByteArray,
        val isExtended: Boolean = false,
        val isTransmit: Boolean = false,
        val isFd: Boolean = false,
        val isBitRateSwitch: Boolean = false,
        val hasErrorState: Boolean = false,
    ) : LoggedFrame

    class Remote(
        override val timeMicros: Long,
        val bus: Int,
        val id: Long,
        val dlc: Int,
        val isExtended: Boolean = false,
        val isTransmit: Boolean = false,
    ) : LoggedFrame
}

internal fun Mdf4BusLogWriter.add(frame: LoggedFrame) {
    when (frame) {
        is LoggedFrame.Data -> addFrame(
            frame.timeMicros,
            frame.bus,
            frame.id,
            frame.data,
            frame.isExtended,
            frame.isTransmit,
            frame.isFd,
            frame.isBitRateSwitch,
            frame.hasErrorState,
        )

        is LoggedFrame.Remote -> addRemoteFrame(frame.timeMicros, frame.bus, frame.id, frame.dlc, frame.isExtended, frame.isTransmit)
    }
}

private val FD_LENGTHS = intArrayOf(12, 16, 20, 24, 32, 48, 64)

/** The data length code a frame of [length] bytes carries, worked out apart from the writer. */
internal fun expectedDlc(length: Int): Int = if (length <= 8) length else 9 + FD_LENGTHS.indexOf(length)

/** What the reader should give back for [frame]. */
internal fun expectedFrame(frame: LoggedFrame): MdfCanFrame = when (frame) {
    is LoggedFrame.Data -> MdfCanFrame(
        time = frame.timeMicros / 1_000_000.0,
        bus = frame.bus,
        id = frame.id,
        isExtended = frame.isExtended,
        dlc = expectedDlc(frame.data.size),
        isTransmit = frame.isTransmit,
        isFd = frame.isFd,
        isBitRateSwitch = frame.isBitRateSwitch,
        hasErrorState = frame.hasErrorState,
        dataLength = frame.data.size,
        data = frame.data,
    )

    is LoggedFrame.Remote -> MdfCanFrame(
        time = frame.timeMicros / 1_000_000.0,
        bus = frame.bus,
        id = frame.id,
        isExtended = frame.isExtended,
        dlc = frame.dlc,
        isTransmit = frame.isTransmit,
        isFd = false,
        isBitRateSwitch = false,
        hasErrorState = false,
        dataLength = 0,
        data = ByteArray(0),
        isRemote = true,
    )
}

/** The frames of the data group and of the remote group of a bus log, in the order they are in the file. */
internal class ReadBack(val data: List<MdfCanFrame>, val remote: List<MdfCanFrame>)

internal fun readBack(file: MdfFile): ReadBack {
    val dataGroup = file.channelGroups.firstOrNull { it.name == "CAN_DataFrame" }
    val remoteGroup = file.channelGroups.firstOrNull { it.name == "CAN_RemoteFrame" }
    val sink = scanAll(file, emptyList(), listOfNotNull(dataGroup, remoteGroup))
    return ReadBack(sink.framesOf[dataGroup].orEmpty(), sink.framesOf[remoteGroup].orEmpty())
}

internal fun readBack(bytes: ByteArray): ReadBack = readBack(open(bytes))

internal val TEST_OPTIONS = Mdf4BusLogOptions(
    startNs = 1_760_000_000_123_456_789L,
    properties = listOf("device_name" to "el_pm100", "unit_uid" to "305419896", "note" to "a < b & \"c\""),
    description = "Recorded by the tests",
    toolVersion = "9.8.7",
    timeClass = 10,
)

/** A frame for every combination the tests need, [count] of them, at increasing times. */
internal fun sampleFrames(count: Int): List<LoggedFrame> {
    val fdLengths = intArrayOf(12, 16, 20, 24, 32, 48, 64, 0, 5, 8)
    return List(count) { i ->
        val time = 1_000L + i * 250L
        when (i % 5) {
            0 -> LoggedFrame.Data(time, 0, 0x100L + i % 0x600, ByteArray(i % 9) { (i + it).toByte() })

            1 -> LoggedFrame.Data(time, 1, 0x18FEF100L + i, ByteArray(8) { (i * 3 + it).toByte() }, isExtended = true, isTransmit = true)

            2 -> LoggedFrame.Data(
                time,
                2,
                0x7FF,
                ByteArray(fdLengths[(i / 5) % fdLengths.size]) { (it xor i).toByte() },
                isFd = true,
                isBitRateSwitch = i % 2 == 0,
                hasErrorState = i % 4 == 0,
            )

            3 -> LoggedFrame.Remote(time, 0, 0x200L + i % 0x100, i % 16, isExtended = i % 2 == 0, isTransmit = i % 3 == 0)

            else -> LoggedFrame.Data(time, 255, 0x1FFFFFFFL - i, ByteArray(i % 5), isExtended = true)
        }
    }
}

/** A log of [frames] written to a new sink and, with [finish], finished. */
internal fun writeBusLog(frames: List<LoggedFrame>, finish: Boolean = true, options: Mdf4BusLogOptions = TEST_OPTIONS): ByteArray {
    val sink = InMemoryMdfSink()
    val writer = Mdf4BusLogWriter.begin(sink, options)
    frames.forEach { writer.add(it) }
    if (finish) writer.finish() else writer.flush()
    return sink.toByteArray()
}
