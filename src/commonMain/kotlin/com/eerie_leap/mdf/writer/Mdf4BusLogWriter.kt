package com.eerie_leap.mdf.writer

import com.eerie_leap.mdf.MdfEventCause
import com.eerie_leap.mdf.MdfEventType

/** What a log says of itself and of the tool that wrote it. */
public class Mdf4BusLogOptions(
    /** When the recording starts: nanoseconds since 1970-01-01 00:00:00 UTC, the header's `hd_start_time_ns`. Frame times are from here. */
    public val startNs: Long,
    /** The `<common_properties>` of the header's comment: key/value pairs the log carries about its source. */
    public val properties: List<Pair<String, String>> = emptyList(),
    /** The text of the header's comment. */
    public val description: String = "CAN bus log",
    /** The tool, as the file history names it. */
    public val toolId: String = "el_kotlin_mdf",
    public val toolVendor: String = "Eerie Leap",
    public val toolVersion: String = "1.0",
    /** The program in the identification block: printable ASCII, up to eight characters (a longer text is cut). */
    public val programId: String = "ELMDF",
    /** The header's time class (`hd_time_class`): 0 for a local PC's clock, 10 for an external source. */
    public val timeClass: Int = 0,
)

/**
 * Writes an MDF 4.10 log of CAN bus traffic to an [MdfSink] as the frames come: the ASAM bus logging layout of rt_core's logger, a
 * `CAN_DataFrame` group whose payloads are variable-length data and a `CAN_RemoteFrame` group. Times are microseconds from the
 * recording's start (the header's start time), written as float64 seconds.
 *
 * While it writes, the file is as a unit leaves one it did not close: its identifier is `UnFinMF` with the unfinalized flags set
 * for the cycle counts, the length of the data block and the byte counts of the payloads. [Mdf4Reader][com.eerie_leap.mdf.Mdf4Reader]
 * and other tools read it as far as it goes, to the last whole record. [flush] writes what is held and brings the lengths and the
 * counts up to date, so a file read after it holds every record and event added before it; [finish] does the same and makes the
 * file a finished one. Only a small buffer is held in memory (64 KiB of records).
 *
 * **Events.** Room for 64 KiB of events (about 300 of them, with their names and comments) is kept between the head and the data,
 * and [flush] writes the events added since the last one there, in place, so a crash loses none that a flush covered. Events that
 * do not fit are held in memory until [finish], which appends them after the data.
 *
 * **Failure.** When the sink throws, the writer fails: the exception is rethrown, [isFailed] is true, and every later call throws an
 * [IllegalStateException] (a buffer is never appended twice). What the sink holds is what an unfinished log holds, as far as the
 * last [flush] that returned.
 *
 * **Threads.** A writer is for one caller at a time: the caller serializes its calls, and a call made while another runs fails with an
 * [IllegalStateException] where that is noticed (it is a check, not a lock).
 */
public class Mdf4BusLogWriter private constructor(
    private val sink: MdfSink,
    private val options: Mdf4BusLogOptions,
    private val head: BusLogHead,
) {
    private val records = LittleEndianBuffer(BUFFER_BYTES)
    private val pending = ArrayList<PendingEvent>()
    private var eventsWritten = 0
    private var regionCursor = head.eventRegion.start
    private var lastEventLink = head.eventRegion.link
    private var isBusy = false
    private var failure: Exception? = null
    private var payloadBytes = 0L
    private var dataBytes = 0L
    private var patched = Counters(-1, -1, -1, -1)

    /** Data frames added. */
    public var frameCount: Long = 0
        private set

    /** Remote frames added. */
    public var remoteFrameCount: Long = 0
        private set

    /** Events added. */
    public val eventCount: Int get() = eventsWritten + pending.size

    /** The time of the latest frame added, in microseconds, or 0. */
    public var lastTimeMicros: Long = 0
        private set

    /** The bytes of the file so far, not counting what is held in the buffer: the sink's position. */
    public val bytesWritten: Long get() = sink.position

    /** The sink threw: the writer takes no more calls. */
    public val isFailed: Boolean get() = failure != null

    /** [finish] was called: nothing more can be added. */
    public var isFinished: Boolean = false
        private set

    private class PendingEvent(
        val timeMicros: Long,
        val name: String,
        val comment: String?,
        val type: MdfEventType,
        val cause: MdfEventCause,
    )

    private data class Counters(val frames: Long, val remote: Long, val payload: Long, val data: Long)

    init {
        sink.append(head.bytes)
        sink.flush()
    }

    // Every public call goes through this: one at a time, and none after the writer failed.
    private inline fun <T> operation(block: () -> T): T {
        check(!isBusy) { "the writer is used from two places at once: it is for one caller at a time" }
        failure?.let { throw IllegalStateException("the log failed: ${it.message}", it) }
        isBusy = true
        try {
            return block()
        } finally {
            isBusy = false
        }
    }

    // What touches the sink goes through this: its exception fails the writer.
    @Suppress("TooGenericExceptionCaught") // Whatever the sink throws fails the writer, and is rethrown as it was.
    private inline fun <T> toSink(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        failure = e
        throw e
    }

    /**
     * Adds a CAN frame: [timeMicros] from the start, on [bus] (0 to 255, as the file numbers it: the Eerie Leap firmware writes its
     * CAN interface + 1, and 0 means the bus is not known, which tools such as asammdf read as any bus), with [id] (11 bits, or 29
     * where [isExtended]) and the first [length] bytes of [data]. A frame longer than 8 bytes is a CAN FD frame and [isFd] must say
     * so; its length is one of 12, 16, 20, 24, 32, 48 and 64. [isTransmit] is a frame the logger sent, [isBitRateSwitch] and
     * [hasErrorState] the FD flags BRS and ESI.
     */
    @Suppress("LongParameterList") // A frame's fields; each is one of the bus logging standard's.
    public fun addFrame(
        timeMicros: Long,
        bus: Int,
        id: Long,
        data: ByteArray,
        isExtended: Boolean = false,
        isTransmit: Boolean = false,
        isFd: Boolean = false,
        isBitRateSwitch: Boolean = false,
        hasErrorState: Boolean = false,
        length: Int = data.size,
    ): Unit = operation {
        checkOpen()
        requireFrame(timeMicros, bus, id, isExtended)
        require(length in 0..data.size) { "a frame of $length bytes from data of ${data.size}" }
        val dlc = dlcOf(length, isFd)
        makeRoom()
        records.u8(RECORD_ID_PAYLOAD).u32(length.toLong()).bytes(data, 0, length)
        records.u8(RECORD_ID_DATA_FRAME).f64(secondsOf(timeMicros)).u8(bus).u32(idWithExtension(id, isExtended))
        records.u8(
            dlc or flag(isTransmit, BIT_DIR) or flag(isFd, BIT_EDL) or flag(isBitRateSwitch, BIT_BRS) or flag(hasErrorState, BIT_ESI),
        )
        records.u8(length).u64(payloadBytes + frameCount * PAYLOAD_LENGTH_BYTES)
        payloadBytes += length
        frameCount++
        dataBytes += RECORD_ID_BYTES + PAYLOAD_LENGTH_BYTES + length + RECORD_ID_BYTES + TIME_BYTES + DATA_FRAME_BYTES
        lastTimeMicros = timeMicros
    }

    /**
     * Adds a remote frame (a request for the data of [id]) with the length [dlc] (0 to 15) it asks for. It has no payload.
     */
    public fun addRemoteFrame(
        timeMicros: Long,
        bus: Int,
        id: Long,
        dlc: Int,
        isExtended: Boolean = false,
        isTransmit: Boolean = false,
    ): Unit = operation {
        checkOpen()
        requireFrame(timeMicros, bus, id, isExtended)
        require(dlc in 0..MAX_DLC) { "a data length code of $dlc" }
        makeRoom()
        records.u8(RECORD_ID_REMOTE_FRAME).f64(secondsOf(timeMicros)).u8(bus).u32(idWithExtension(id, isExtended))
        records.u8(dlc or flag(isTransmit, BIT_DIR)).u8(minOf(dlc, MAX_CLASSIC_LENGTH))
        remoteFrameCount++
        dataBytes += RECORD_ID_BYTES + TIME_BYTES + REMOTE_FRAME_BYTES
        lastTimeMicros = timeMicros
    }

    /**
     * Adds an event at [timeMicros]: a gap, a mark, a change of the bus state, the end of the recording. Its [name] and [comment] are
     * what a reader shows. They are written to the file by the next [flush], or by [finish].
     */
    public fun addEvent(
        timeMicros: Long,
        name: String,
        comment: String? = null,
        type: MdfEventType = MdfEventType.Marker,
        cause: MdfEventCause = MdfEventCause.Other,
    ): Unit = operation {
        checkOpen()
        require(timeMicros >= 0) { "an event at $timeMicros µs" }
        require(type != MdfEventType.Unknown && cause != MdfEventCause.Unknown) { "an event of an unknown type or cause" }
        pending.add(PendingEvent(timeMicros, name, comment, type, cause))
    }

    /**
     * Hands the records held to the sink, writes the events added since the last flush, brings the data block's length, the cycle
     * counts and the payloads' byte count up to date and flushes the sink: the file now holds every record and event added so far.
     */
    public fun flush(): Unit = operation {
        checkOpen()
        writeRecords()
        toSink { sink.flush() }
        writeRegionEvents()
        patchCounts()
        toSink { sink.flush() }
    }

    /**
     * Ends the log: writes what is held, appends the events that did not fit the room kept for them, and makes the file a finished
     * one (the identifier `MDF`, no unfinalized flags), in that order, flushing the sink between the steps so that the identifier is
     * the last to change. Nothing can be added afterwards. Calling it again does nothing.
     */
    public fun finish(): Unit = operation {
        if (isFinished) return@operation
        writeRecords()
        writeRegionEvents()
        val tail = appendTailEvents()
        toSink { sink.flush() }
        patchCounts()
        tail?.let { overwrite(lastEventLink, it) }
        toSink { sink.flush() }
        toSink { sink.overwrite(0, identificationBlock(options.programId, isFinished = true)) }
        toSink { sink.flush() }
        isFinished = true
    }

    private fun checkOpen() = check(!isFinished) { "the log is finished" }

    private fun requireFrame(timeMicros: Long, bus: Int, id: Long, isExtended: Boolean) {
        require(timeMicros >= 0) { "a frame at $timeMicros µs" }
        require(bus in 0..MAX_BUS) { "bus $bus is not a bus number from 0 to $MAX_BUS" }
        require(id >= 0 && id <= if (isExtended) MAX_EXTENDED_ID else MAX_STANDARD_ID) {
            "id 0x${id.toString(HEX_RADIX)} is not ${if (isExtended) "an extended" else "a standard"} identifier"
        }
    }

    private fun makeRoom() {
        if (records.size + MAX_RECORDS_BYTES > BUFFER_BYTES) writeRecords()
    }

    private fun writeRecords() {
        if (records.size == 0) return
        toSink { sink.append(records.array, 0, records.size) }
        records.clear()
    }

    // The length of the data block and the counts are written where they are, for a reader that does not read the flags; the flags
    // still say they are stale, as they are once more records follow.
    private fun patchCounts() {
        val now = Counters(frameCount, remoteFrameCount, payloadBytes, dataBytes)
        if (now == patched) return
        if (now.data != patched.data) {
            overwrite(head.dataBlockOffset + DATA_BLOCK_LENGTH_AT, DATA_BLOCK_HEADER_BYTES + dataBytes)
        }
        if (now.frames != patched.frames || now.payload != patched.payload) {
            overwrite(head.dataFrames.cycleCount, frameCount)
            overwrite(head.payloads.cycleCount, frameCount)
            // The byte count of a group of payloads is a 64-bit number in the two 32-bit fields of data and invalidation bytes.
            overwrite(head.payloads.dataBytes, payloadBytes)
        }
        if (now.remote != patched.remote) overwrite(head.remoteFrames.cycleCount, remoteFrameCount)
        patched = now
    }

    private fun overwrite(position: Long, value: Long) {
        toSink { sink.overwrite(position, LittleEndianBuffer().u64(value).toByteArray()) }
    }

    // The events that fit the room kept for them go there, in place, and the chain's last link is made to name the first.
    private fun writeRegionEvents() {
        var cursor = regionCursor
        var count = 0
        while (count < pending.size) {
            val size = eventSize(pending[count])
            if (cursor + size > head.eventRegion.end) break
            cursor += size
            count++
        }
        if (count == 0) return
        val set = OutBlockSet(regionCursor)
        val blocks = chain(set, pending.subList(0, count))
        val bytes = set.layOut()
        toSink { sink.overwrite(regionCursor, bytes) }
        overwrite(lastEventLink, blocks.first().offset)
        lastEventLink = blocks.last().linkOffset(EV_LINK_NEXT)
        regionCursor = cursor
        eventsWritten += count
        pending.subList(0, count).clear()
    }

    // The events that did not fit go after the data block, on a multiple of 8. Returns where the first is, to link to once the data is
    // safe, or null where there are none.
    private fun appendTailEvents(): Long? {
        if (pending.isEmpty()) return null
        val padding = OutBlockSet.alignedUp(sink.position) - sink.position
        if (padding > 0) toSink { sink.append(ByteArray(padding.toInt())) }
        val set = OutBlockSet(sink.position)
        val blocks = chain(set, pending)
        val bytes = set.layOut()
        toSink { sink.append(bytes) }
        eventsWritten += pending.size
        pending.clear()
        return blocks.first().offset
    }

    private fun chain(set: OutBlockSet, events: List<PendingEvent>): List<OutBlock> {
        val blocks = events.map { eventBlock(set, it) }
        for (i in 0 until blocks.size - 1) blocks[i].links[EV_LINK_NEXT] = blocks[i + 1]
        return blocks
    }

    private fun eventSize(event: PendingEvent): Long = OutBlockSet(0).also { eventBlock(it, event) }.end()

    private fun eventBlock(set: OutBlockSet, event: PendingEvent): OutBlock {
        val data = LittleEndianBuffer().u8(event.type.code).u8(SYNC_TIME).u8(RANGE_POINT).u8(event.cause.code).u8(0).zeros(RESERVED_FLAGS)
            .u32(0).u16(0).u16(0).u64(event.timeMicros).f64(MICROSECOND)
        val block = set.block("EV", EV_LINKS, data.toByteArray())
        block.links[EV_LINK_NAME] = set.text(event.name)
        event.comment?.let { block.links[EV_LINK_COMMENT] = set.xml("<EVcomment><TX>${escapeXmlText(it)}</TX></EVcomment>") }
        return block
    }

    private fun secondsOf(timeMicros: Long): Double = timeMicros / MICROSECONDS_PER_SECOND

    private fun idWithExtension(id: Long, isExtended: Boolean): Long = if (isExtended) id or IDE_FLAG else id

    private fun flag(set: Boolean, bit: Int): Int = if (set) 1 shl bit else 0

    private fun dlcOf(length: Int, isFd: Boolean): Int {
        require(length <= MAX_CLASSIC_LENGTH || isFd) { "a classic CAN frame of $length bytes" }
        require(length <= MAX_CLASSIC_LENGTH || length in FD_LENGTHS) { "$length bytes is not a CAN FD frame length" }
        return if (length <= MAX_CLASSIC_LENGTH) length else FD_LENGTHS.indexOf(length) + MAX_CLASSIC_LENGTH + 1
    }

    public companion object {
        /**
         * Starts a log in [sink]: writes the head (the identification, the header, the file history, the channel groups) and the data
         * block's header. The sink should be empty.
         */
        public fun begin(sink: MdfSink, options: Mdf4BusLogOptions): Mdf4BusLogWriter {
            require(sink.position == 0L) { "the sink already holds ${sink.position} bytes" }
            require(options.timeClass in 0..UByte.MAX_VALUE.toInt()) { "time class ${options.timeClass}" }
            require(isAscii(options.programId)) { "the program id ${options.programId} is not printable ASCII" }
            return Mdf4BusLogWriter(sink, options, busLogHead(options))
        }

        /** The lengths of a CAN FD frame's data, by the data length codes 9 to 15. */
        private val FD_LENGTHS = intArrayOf(12, 16, 20, 24, 32, 48, 64)

        private const val BUFFER_BYTES = 64 * 1024

        // The most a frame adds: its payload's record and its own, with a payload of 64 bytes.
        private const val MAX_RECORDS_BYTES = 128
        private const val RECORD_ID_BYTES = 1
        private const val DATA_BLOCK_HEADER_BYTES = 24
        private const val DATA_BLOCK_LENGTH_AT = 8L
        private const val MAX_BUS = 255
        private const val MAX_STANDARD_ID = 0x7FFL
        private const val MAX_EXTENDED_ID = 0x1FFFFFFFL
        private const val IDE_FLAG = 1L shl 31
        private const val HEX_RADIX = 16
        private const val MAX_DLC = 15
        private const val MAX_CLASSIC_LENGTH = 8
        private const val BIT_DIR = 4
        private const val BIT_EDL = 5
        private const val BIT_BRS = 6
        private const val BIT_ESI = 7
        private const val MICROSECONDS_PER_SECOND = 1_000_000.0
        private const val MICROSECOND = 1e-6
        private const val SYNC_TIME = 1
        private const val RANGE_POINT = 0
        private const val RESERVED_FLAGS = 3
        private const val EV_LINKS = 5
        private const val EV_LINK_NEXT = 0
        private const val EV_LINK_NAME = 3
        private const val EV_LINK_COMMENT = 4
    }
}
