package com.eerie_leap.mdf

private const val FULL_BUFFER_BYTES = 1024 * 1024
private const val MIN_BUFFER_BYTES = 64 * 1024
private const val MAX_BUFFER_BYTES = 80 * 1024 * 1024

/**
 * A channel whose samples a scan hands over. The samples are the channel's converted values (the physical ones) unless [stored]
 * is set, which asks for the values as they are stored: for a channel with a conversion, say, to draw the raw value. A channel with
 * a conversion to texts (an enumeration) gives its stored numbers either way, and [MdfConversion.toText] labels them.
 */
public class MdfChannelRequest(public val channel: MdfChannel, public val stored: Boolean = false) {
    override fun toString(): String = "MdfChannelRequest(${channel.name}${if (stored) ", stored" else ""})"
}

/** Where a scan hands what it reads. */
public interface MdfSampleSink {
    /**
     * A sample of a numeric channel: at [time] (the group's master through its conversion: seconds for a time master; the record's
     * number for a group without one) the channel was [value]. An [isInvalid] sample has no value: the writer set its invalidation
     * bit, and [value] is what it stored in its place.
     */
    public fun onSample(request: MdfChannelRequest, time: Double, value: Double, isInvalid: Boolean)

    /** A value of a string or byte array channel: a byte array as hexadecimal pairs. */
    public fun onText(request: MdfChannelRequest, time: Double, text: String, isInvalid: Boolean) {}

    /** A CAN frame of a group asked for as frames. */
    public fun onCanFrame(group: MdfChannelGroup, frame: MdfCanFrame) {}
}

/**
 * How far a scan has got. [bytesRead] of the data group's data are behind it, of the [bytesAvailable] there are now; the file
 * declares [bytesDeclared] (null for the last data block of a file its writer didn't finish, which has no end yet).
 */
public class MdfScanProgress(
    public val bytesRead: Long,
    public val bytesAvailable: Long,
    public val bytesDeclared: Long?,
    /**
     * The scan has read everything the file declares, and nothing more will come: for a file whose last data block has no end
     * (a null [bytesDeclared]), everything there is once the scan was told the source is final (see [MdfScanner.advance]).
     */
    public val isComplete: Boolean,
    /** Why the records can't be read on, where they can't: the scan has stopped for good. */
    public val problem: MdfFailure?,
    /**
     * The file ends before the lists that name the data's blocks do (a file cut short, or a download that isn't done): some blocks
     * the file names are not there, [bytesDeclared] is what the lists named so far hold, and the scan is not [isComplete] however
     * far it has read. A file that grows may fill the lists in.
     */
    public val isCut: Boolean = false,
) {
    override fun toString(): String = "MdfScanProgress($bytesRead of $bytesAvailable, complete=$isComplete)"
}

/**
 * Reads the records of a data group and hands the samples of the channels asked for to a sink, without holding the data: it reads
 * through the file's source in blocks. [advance] reads what the file holds now and returns; for a file still arriving (a log being
 * downloaded, or one the unit is still writing) call it again as the source grows and it goes on where it stopped, keeping a
 * record it has only part of for the next time.
 *
 * An unsorted data group's records are told apart by their record ID; a group of variable-length values (strings, CAN payloads)
 * has its records read to join them to the records that name them. A file its writer didn't finish has its cycle counts stale
 * and the length of its last data block too: the scan counts the records itself (see [cycleCount]) and takes the data to the end
 * of the file, stopping at the last whole record. A finished file cut short (a download that isn't done) has its data block run
 * past the end of the file, and is read the same way up to the end, though it isn't [MdfScanProgress.isComplete].
 */
public class MdfScanner private constructor(
    private val stream: DataStream,
    private val processor: RecordProcessor,
    private val plans: List<GroupPlan>,
    /** The requests that couldn't be planned, and why; the rest are scanned. */
    public val rejected: List<MdfIssue>,
) {
    private var buffer = ByteArray(0)
    private var position = 0L
    private var stalled = false

    /** The bytes of the data group's data behind the scan. */
    public val bytesRead: Long get() = position

    /** The records of [group] the scan has gone through. */
    public fun cycleCount(group: MdfChannelGroup): Long = plans.firstOrNull { it.group === group }?.cycles ?: 0L

    /** The CAN frames whose payload was never found, among those asked for. */
    public val unresolvedFrames: Long get() = processor.unresolvedFrames()

    /**
     * Reads the records the file holds now, at most about [maxBytes] of them, and returns how far it got. A record only part of
     * which is there is left for the next call. It doesn't fail for data it can't read (a zipped block that doesn't inflate, a
     * record ID no group has, a source that fails): the scan stops there for good, and [MdfScanProgress.problem] says why.
     *
     * A record may wait for data that isn't in the file yet (a value in signal data blocks, a time of a master in another group),
     * and the scan waits with it for the file to grow. Pass [sourceIsFinal] when no more bytes will come (the file is closed, the
     * download is done): a record that waits for what the file doesn't have then goes without it, and the scan goes on, instead of
     * waiting for ever; and a file whose last data block has no end is [MdfScanProgress.isComplete] once everything there is is read.
     */
    public suspend fun advance(maxBytes: Long = Long.MAX_VALUE, sourceIsFinal: Boolean = false): MdfScanProgress {
        stalled = false
        processor.retry(sourceIsFinal)
        val available = try {
            stream.refresh()
            readAvailable(maxBytes)
        } catch (e: MdfException) {
            processor.fail(e.failure)
            position
        }
        return progress(available, sourceIsFinal)
    }

    // Takes the records of the data there is now, up to about maxBytes; the bytes there are.
    private suspend fun readAvailable(maxBytes: Long): Long {
        var consumedHere = 0L
        var available = stream.available()
        var more = !isStopped
        while (more && consumedHere < maxBytes && position < available) {
            fitBuffer(available - position)
            val wanted = minOf(buffer.size.toLong(), available - position).toInt()
            val read = stream.read(position, buffer, 0, wanted)
            val stopAt = minOf(maxBytes - consumedHere, Int.MAX_VALUE.toLong()).toInt()
            val consumed = if (read > 0) takeRecords(read, stopAt) else 0
            consumedHere += consumed
            // A record waiting for data that isn't in the file yet ends the pass; one that had no room in the buffer grows it.
            more = !processor.isWaiting && !isStopped && (consumed > 0 || makeRoom(read))
            available = stream.available()
        }
        return stream.available()
    }

    // Takes the records of the buffer's first [read] bytes, running the reads that a record asks for first and going on in the same
    // buffer; the bytes taken.
    private suspend fun takeRecords(read: Int, stopAt: Int): Int {
        var at = 0
        var fetch: Fetch?
        do {
            at = processor.process(buffer, at, read, position, stopAt)
            fetch = processor.pendingFetch
            processor.pendingFetch = null
            fetch?.run()
        } while (fetch != null)
        position += at
        return at
    }

    private val isStopped: Boolean get() = processor.problem != null || processor.finished

    // A buffer of a megabyte for data that fills it, less for a little, kept for the next call.
    private fun fitBuffer(remaining: Long) {
        val wanted = minOf(FULL_BUFFER_BYTES.toLong(), maxOf(MIN_BUFFER_BYTES.toLong(), remaining)).toInt()
        if (buffer.size < wanted) buffer = ByteArray(wanted)
    }

    // After a pass that took no record: false where the scan must wait for more bytes, true where the buffer was too small for the
    // record and has grown (or the record is beyond what a buffer may be, and the scan has failed).
    private fun makeRoom(read: Int): Boolean {
        if (read < buffer.size) {
            // Everything that is there was in the buffer, and no record in it is whole.
            stalled = true
            return false
        }
        val needed = maxOf(processor.needed, buffer.size + 1)
        if (needed > MAX_BUFFER_BYTES) {
            processor.fail(MdfFailure.TooLarge(stream.origin + position, needed.toLong(), MAX_BUFFER_BYTES.toLong()))
            return false
        }
        buffer = ByteArray(minOf(maxOf(needed, buffer.size * 2), MAX_BUFFER_BYTES))
        return true
    }

    private fun progress(available: Long, sourceIsFinal: Boolean): MdfScanProgress {
        val declared = stream.declaredLength
        val isCut = stream.isCut
        val isAtEnd = position >= (declared ?: available) || stalled || processor.finished
        val reachedEnd = when {
            isCut -> false
            declared != null -> available >= declared && isAtEnd
            else -> sourceIsFinal && isAtEnd
        }
        return MdfScanProgress(
            position,
            available,
            declared,
            isComplete = processor.problem == null && reachedEnd && !processor.isWaiting,
            problem = processor.problem,
            isCut = isCut,
        )
    }

    public companion object {
        /**
         * Starts a scan of the records of [dataGroup] of [file], handing the samples of [channels] (channels of the data group's
         * channel groups) and the frames of [canGroups] (`CAN_DataFrame` groups of it) to [sink]. It locates the data and checks
         * what it is asked for against the layout; a request it can't take is left out and listed in [rejected].
         *
         * Fails with an [MdfException]: [MdfFailure.Unsupported] for a way of storing the data this reader doesn't read, a
         * [MdfFailure.TooLarge] zipped block, a [MdfFailure.Damaged] data group.
         */
        public suspend fun create(
            file: MdfFile,
            dataGroup: MdfDataGroup,
            channels: List<MdfChannelRequest>,
            sink: MdfSampleSink,
            canGroups: List<MdfChannelGroup> = emptyList(),
        ): MdfScanner = build(file, dataGroup, channels, sink, canGroups)

        // [depth] counts the scans of masters that this one is the scan of.
        internal suspend fun build(
            file: MdfFile,
            dataGroup: MdfDataGroup,
            channels: List<MdfChannelRequest>,
            sink: MdfSampleSink,
            canGroups: List<MdfChannelGroup> = emptyList(),
            depth: Int = 0,
        ): MdfScanner {
            val prepared = ScanPreparer(file, dataGroup, depth).prepare(channels, canGroups)
            val planned = ScanPlanner(dataGroup, prepared, file.isStale(UnfinalizedFlag.VlsdOffsets)).plan(channels, canGroups)
            val stream = DataLocator.locate(file, dataGroup)
            // A zipped block that is all there is inflated now, so a damaged one fails here, not in the middle of a scan.
            stream.verify()
            val countsAreTrue = file.identification.isFinalized || UnfinalizedFlag.CycleCounts !in file.identification.unfinalized
            val single = planned.groups.singleOrNull()
            val limit = if (dataGroup.isSorted && countsAreTrue) single?.group?.cycleCount else null
            val processor = RecordProcessor(dataGroup.recordIdSize, planned.groups, sink, limit, stream.origin)
            return MdfScanner(stream, processor, planned.groups, planned.rejected)
        }
    }
}
