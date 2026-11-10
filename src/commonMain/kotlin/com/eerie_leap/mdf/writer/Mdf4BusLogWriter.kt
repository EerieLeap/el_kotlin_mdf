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
    /** The program in the identification block, in up to eight characters. */
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
 * counts up to date, so a file read after it holds every record added before it; [finish] does the same, appends the events and
 * makes the file a finished one. Only a small buffer is held in memory (64 KiB of records), and the events until [finish]: they come
 * after the data, which grows to the end of the file.
 *
 * One thread at a time may use a writer.
 */
public class Mdf4BusLogWriter private constructor(
    private val sink: MdfSink,
    private val options: Mdf4BusLogOptions,
    private val head: BusLogHead,
) {
    private val records = LittleEndianBuffer(BUFFER_BYTES)
    private val events = ArrayList<PendingEvent>()
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
    public val eventCount: Int get() = events.size

    /** The time of the latest frame added, in microseconds, or 0. */
    public var lastTimeMicros: Long = 0
        private set

    /** The bytes of the file so far, not counting what is held in the buffer: the sink's position. */
    public val bytesWritten: Long get() = sink.position

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

    /**
     * Adds a CAN frame: [timeMicros] from the start, on [bus] (as the caller numbers its buses, 0 to 255), with [id] (11 bits, or 29
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
    ) {
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
    ) {
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
     * what a reader shows. Events are written when the file is finished.
     */
    public fun addEvent(
        timeMicros: Long,
        name: String,
        comment: String? = null,
        type: MdfEventType = MdfEventType.Marker,
        cause: MdfEventCause = MdfEventCause.Other,
    ) {
        checkOpen()
        require(timeMicros >= 0) { "an event at $timeMicros µs" }
        require(type != MdfEventType.Unknown && cause != MdfEventCause.Unknown) { "an event of an unknown type or cause" }
        events.add(PendingEvent(timeMicros, name, comment, type, cause))
    }

    /**
     * Hands the records held to the sink, brings the data block's length, the cycle counts and the payloads' byte count up to date and
     * flushes the sink: the file now holds every record added so far.
     */
    public fun flush() {
        checkOpen()
        writeRecords()
        patchCounts()
        sink.flush()
    }

    /**
     * Ends the log: flushes, appends the events, makes the file finished (the identifier `MDF`, no unfinalized flags). Nothing can be
     * added afterwards. Calling it again does nothing.
     */
    public fun finish() {
        if (isFinished) return
        writeRecords()
        patchCounts()
        writeEvents()
        sink.overwrite(0, identificationBlock(options.programId, isFinished = true))
        sink.flush()
        isFinished = true
    }

    private fun checkOpen() = check(!isFinished) { "the log is finished" }

    private fun requireFrame(timeMicros: Long, bus: Int, id: Long, isExtended: Boolean) {
        require(timeMicros >= 0) { "a frame at $timeMicros µs" }
        require(bus in 0..MAX_BUS) { "bus $bus" }
        require(id >= 0 && id <= if (isExtended) MAX_EXTENDED_ID else MAX_STANDARD_ID) {
            "id 0x${id.toString(HEX_RADIX)} is not ${if (isExtended) "an extended" else "a standard"} identifier"
        }
    }

    private fun makeRoom() {
        if (records.size + MAX_RECORDS_BYTES > BUFFER_BYTES) writeRecords()
    }

    private fun writeRecords() {
        if (records.size == 0) return
        sink.append(records.array, 0, records.size)
        records.clear()
    }

    // The length of the data block and the counts are written where they are, for a reader that does not read the flags; the flags
    // still say they are stale, as they are once more records follow.
    private fun patchCounts() {
        val now = Counters(frameCount, remoteFrameCount, payloadBytes, dataBytes)
        if (now == patched) return
        if (now.data != patched.data) {
            sink.overwrite(
                head.dataBlockOffset + DATA_BLOCK_LENGTH_AT,
                LittleEndianBuffer().u64(DATA_BLOCK_HEADER_BYTES + dataBytes).toByteArray(),
            )
        }
        if (now.frames != patched.frames || now.payload != patched.payload) {
            sink.overwrite(head.dataFrames.cycleCount, LittleEndianBuffer().u64(frameCount).toByteArray())
            sink.overwrite(head.payloads.cycleCount, LittleEndianBuffer().u64(frameCount).toByteArray())
            // The byte count of a group of payloads is a 64-bit number in the two 32-bit fields of data and invalidation bytes.
            sink.overwrite(head.payloads.dataBytes, LittleEndianBuffer().u64(payloadBytes).toByteArray())
        }
        if (now.remote != patched.remote) {
            sink.overwrite(head.remoteFrames.cycleCount, LittleEndianBuffer().u64(remoteFrameCount).toByteArray())
        }
        patched = now
    }

    // The events go after the data block, on a multiple of 8, and the header block links to the first.
    private fun writeEvents() {
        if (events.isEmpty()) return
        val padding = OutBlockSet.alignedUp(sink.position) - sink.position
        if (padding > 0) sink.append(ByteArray(padding.toInt()))
        val set = OutBlockSet(sink.position)
        var previous: OutBlock? = null
        var first: OutBlock? = null
        for (event in events) {
            val block = eventBlock(set, event)
            if (first == null) first = block else previous?.links?.set(0, block)
            previous = block
        }
        val bytes = set.layOut()
        sink.append(bytes)
        sink.overwrite(head.eventsLink, LittleEndianBuffer().u64(checkNotNull(first).offset).toByteArray())
    }

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
        private const val EV_LINK_NAME = 3
        private const val EV_LINK_COMMENT = 4
    }
}
