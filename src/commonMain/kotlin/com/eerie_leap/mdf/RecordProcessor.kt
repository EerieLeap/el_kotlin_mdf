package com.eerie_leap.mdf

private const val MAX_VARIABLE_LENGTH_RECORD = 64L * 1024 * 1024
private const val ONE_BYTE_IDS = 1
private const val TWO_BYTE_IDS = 2
private const val FOUR_BYTE_IDS = 4
private const val ID_BYTE_LIMIT = 256
private const val ID_WORD_LIMIT = 65_536
private const val PADDING_LIMIT = 8

/**
 * Takes the records in a buffer one by one, as the data group lays them out: after a record ID of [idSize] bytes (none for sorted
 * data) the fixed length record of the channel group the ID names, or a variable-length one, `[length: u32][bytes]`. It hands the
 * samples, texts and frames the plans ask for to the [sink], and keeps the variable-length values frames and texts name.
 *
 * A record that needs something it can't get from the buffer (a value in signal data blocks, a master value of another channel
 * group) is left, and [pendingFetch] says what to read before it is taken.
 */
internal class RecordProcessor(
    private val idSize: Int,
    private val plans: List<GroupPlan>,
    private val sink: MdfSampleSink,
    /** For sorted data of a file whose counts can be trusted: the records there are, the rest of the data being padding. */
    private val recordLimit: Long?,
    private val origin: Long,
) {
    private val byByte: Array<GroupPlan?> = if (idSize == ONE_BYTE_IDS) arrayOfNulls(ID_BYTE_LIMIT) else emptyArray()
    private val byWord: Array<GroupPlan?> = if (idSize == TWO_BYTE_IDS) arrayOfNulls(ID_WORD_LIMIT) else emptyArray()
    private val byWide = HashMap<Long, GroupPlan>()

    init {
        for (plan in plans) {
            val id = plan.group.recordId
            when (idSize) {
                ONE_BYTE_IDS -> if (id in 0 until ID_BYTE_LIMIT) byByte[id.toInt()] = plan
                TWO_BYTE_IDS -> if (id in 0 until ID_WORD_LIMIT) byWord[id.toInt()] = plan
                else -> byWide[id] = plan
            }
        }
    }

    /** Why the records can't be read on, once they can't. */
    var problem: MdfFailure? = null
        private set

    private val isStopped: Boolean get() = problem != null || finished

    /** The sorted records there were to read have all been read. */
    var finished = false
        private set

    /** The bytes the record at the end of the last [process] needed, when it didn't fit in what the buffer held. */
    var needed = 0
        private set

    /** A read the record at the end of the last [process] needs first: the caller runs it, clears this, and calls again. */
    var pendingFetch: Fetch? = null

    /** The record at the end of the last [process] waits for data that isn't in the file yet. */
    var isWaiting = false
        private set

    /**
     * Takes the whole records in `buffer[from, limit)`, the buffer beginning at [position] of the data, and returns the index in the
     * buffer it got to. It stops short of a record the buffer holds only part of (see [needed]), of the end of sorted data, of a
     * record it can't place (see [problem]) and of one that needs a read first (see [pendingFetch]), and after the record that takes
     * it to [stopAt].
     */
    fun process(
        buffer: ByteArray,
        from: Int,
        limit: Int,
        position: Long,
        stopAt: Int,
    ): Int {
        needed = 0
        isWaiting = false
        var at = from
        while (at < limit && at < stopAt && !isStopped) {
            val end = take(buffer, at, limit, position)
            if (end == at) break
            at = end
        }
        return at
    }

    /** Stops the records for good: the caller found a record it can't take. */
    fun fail(failure: MdfFailure) {
        problem = failure
    }

    /** The data a scan waited for may have grown: let every source look again. */
    fun retry() {
        isWaiting = false
        for (plan in plans) {
            plan.remoteTimes?.retry()
            plan.texts.forEach { it.signalData?.retry() }
            plan.frames?.signalData?.retry()
        }
    }

    /** The frames that never found their payload, for the groups of CAN frames. */
    fun unresolvedFrames(): Long = plans.sumOf { plan -> plan.frames?.let { it.unresolved + it.pending.size } ?: 0L }

    // The record at `at`, as far as it goes: the offset after it, or `at` when it isn't whole or can't be placed.
    private fun take(buffer: ByteArray, at: Int, limit: Int, position: Long): Int {
        if (reachedRecordLimit() || hasNothingToRead()) {
            finished = true
            return at
        }
        val data = at + idSize
        if (data > limit) return partial(at, idSize)
        val plan = if (idSize == 0) plans.singleOrNull() else planOf(buffer, at)
        return when {
            plan == null -> unknown(buffer, at, limit, position)
            plan.group.isVlsd -> takeVariable(plan, buffer, Span(at, data, limit))
            else -> takeFixed(plan, buffer, Span(at, data, limit))
        }
    }

    // A data group with no channel group, or sorted data of records of no bytes, holds nothing a record could be read from.
    private fun hasNothingToRead(): Boolean = plans.isEmpty() || (idSize == 0 && plans.first().recordSize == 0)

    private fun reachedRecordLimit(): Boolean {
        val single = plans.singleOrNull() ?: return false
        return idSize == 0 && recordLimit != null && single.cycles >= recordLimit
    }

    private fun planOf(buffer: ByteArray, at: Int): GroupPlan? = when (idSize) {
        ONE_BYTE_IDS -> byByte[buffer.u8(at)]
        TWO_BYTE_IDS -> byWord[buffer.u16(at)]
        else -> byWide[idAt(buffer, at)]
    }

    private fun idAt(buffer: ByteArray, at: Int): Long = when (idSize) {
        ONE_BYTE_IDS -> buffer.u8(at).toLong()
        TWO_BYTE_IDS -> buffer.u16(at).toLong()
        FOUR_BYTE_IDS -> buffer.u32(at)
        else -> buffer.u64(at)
    }

    private fun partial(at: Int, size: Int): Int {
        needed = size
        return at
    }

    // Where a record is in the buffer: its start (at its ID), the start of what follows the ID, and the end of the buffer's data.
    private class Span(val at: Int, val data: Int, val limit: Int)

    private fun takeFixed(plan: GroupPlan, buffer: ByteArray, span: Span): Int {
        val end = span.data + plan.recordSize
        if (end > span.limit) return partial(span.at, end - span.at)
        if (plan.hasOutput && plan.needsLookup && !prepare(plan, buffer, span.data)) return span.at
        if (plan.hasOutput) emit(plan, buffer, span.data)
        plan.cycles++
        return end
    }

    private fun takeVariable(plan: GroupPlan, buffer: ByteArray, span: Span): Int {
        val body = span.data + U32_SIZE
        if (body > span.limit) return partial(span.at, body - span.at)
        val length = buffer.u32(span.data)
        if (length > MAX_VARIABLE_LENGTH_RECORD) {
            problem = MdfFailure.TooLarge(origin, length, MAX_VARIABLE_LENGTH_RECORD)
            return span.at
        }
        val end = body + length.toInt()
        if (end > span.limit) return partial(span.at, end - span.at)
        plan.store?.let { store -> deliverPending(plan, store, store.add(buffer.copyOfRange(body, end))) }
        plan.cycles++
        return end
    }

    // The records that came before the value now stored at [key] get it.
    private fun deliverPending(plan: GroupPlan, store: VariableLengthStore, key: Long) {
        for (text in plan.textReaders) {
            if (text.pending.size == 0) continue
            val waiting = text.pending.remove(key)
            val bytes = if (waiting.isEmpty()) null else store.take(text.reader, key)
            if (bytes != null) waiting.forEach { sendText(text, it.time, bytes, it.isInvalid) }
        }
        for (frames in plan.frameReaders) {
            if (frames.pending.size == 0) continue
            val waiting = frames.pending.remove(key)
            val bytes = if (waiting.isEmpty()) null else store.take(frames.reader, key)
            if (bytes != null) waiting.forEach { sink.onCanFrame(frames.group, frames.layout.frame(it.record, 0, it.time, bytes)) }
        }
    }

    // A record ID that no channel group has: zeros at the end of the data are padding, anything else is damage the records can't be
    // read past, as nothing says how long the record is.
    private fun unknown(buffer: ByteArray, at: Int, limit: Int, position: Long): Int {
        val isPadding = limit - at < PADDING_LIMIT && (at until limit).all { buffer[it] == 0.toByte() }
        if (isPadding) {
            finished = true
        } else {
            problem = MdfFailure.Damaged(
                origin + position + at,
                "the record ID ${idAt(buffer, at)} at byte ${position + at} of the data belongs to no channel group",
            )
        }
        return at
    }

    // Looks up what the record needs from outside the buffer: false when that has to be read (or waited for) before the record is taken.
    private fun prepare(plan: GroupPlan, record: ByteArray, at: Int): Boolean {
        val times = plan.remoteTimes
        var ready = times == null || settle(times.check(plan.cycles), times.fetch)
        for (text in plan.texts) {
            val source = text.signalData
            val offsetField = text.offsetField
            if (ready && source != null && offsetField != null) {
                text.resolved = source.take(offsetField.readBits(record, at))
                ready = settle(source.lookup, source.fetch)
            }
        }
        val frames = plan.frames
        val source = frames?.signalData
        if (ready && frames != null && source != null) {
            frames.resolved = source.take(frames.layout.dataBytes.readBits(record, at))
            ready = settle(source.lookup, source.fetch)
        }
        return ready
    }

    // Whether a lookup lets the record go on: a value to be read stops it for the caller to read it, one that isn't in the file yet
    // stops it to wait.
    private fun settle(lookup: Lookup, fetch: Fetch): Boolean = when (lookup) {
        Lookup.Fetching -> false.also { pendingFetch = fetch }
        Lookup.Waiting -> false.also { isWaiting = true }
        else -> true
    }

    private fun emit(plan: GroupPlan, record: ByteArray, at: Int) {
        val time = timeOf(plan, record, at)
        for (sample in plan.samples) {
            val raw = sample.field?.read(record, at) ?: plan.cycles.toDouble()
            val value = sample.conversion?.toPhysical(raw, sample.rawIsInteger) ?: raw
            sink.onSample(sample.request, time, value, sample.validity.isInvalid(record, at))
        }
        for (text in plan.texts) emitText(text, record, at, time)
        plan.frames?.let { emitFrame(it, record, at, plan.recordSize, time) }
        plan.remoteTimes?.discardBefore(plan.cycles)
    }

    private fun timeOf(plan: GroupPlan, record: ByteArray, at: Int): Double {
        val remote = plan.remoteTimes
        if (remote != null) return remote.timeAt(plan.cycles)
        val raw = plan.timeField?.read(record, at) ?: plan.cycles.toDouble()
        return plan.timeConversion?.toPhysical(raw, plan.timeIsInteger) ?: raw
    }

    private fun emitText(text: TextPlan, record: ByteArray, at: Int, time: Double) {
        val isInvalid = text.validity.isInvalid(record, at)
        val store = text.store
        val offsetField = text.offsetField
        when {
            text.signalData != null -> text.resolved?.let { sendText(text, time, it, isInvalid) }

            store != null && offsetField != null -> {
                val key = store.keyFor(text.reader, offsetField.readBits(record, at))
                val bytes = store.take(text.reader, key)
                if (bytes != null) sendText(text, time, bytes, isInvalid) else text.pending.add(key, PendingText(time, isInvalid))
            }

            else -> sink.onText(text.request, time, MdfValues.text(text.dataType, record, at + text.byteOffset, text.byteCount), isInvalid)
        }
    }

    private fun sendText(text: TextPlan, time: Double, bytes: ByteArray, isInvalid: Boolean) {
        sink.onText(text.request, time, MdfValues.text(text.dataType, bytes, 0, bytes.size), isInvalid)
    }

    private fun emitFrame(
        frames: FramePlan,
        record: ByteArray,
        at: Int,
        size: Int,
        time: Double,
    ) {
        val layout = frames.layout
        val store = frames.store
        when {
            layout.hasInlinePayload -> sink.onCanFrame(frames.group, layout.frame(record, at, time, layout.inlinePayload(record, at)))

            frames.signalData != null -> {
                val payload = frames.resolved
                if (payload != null) sink.onCanFrame(frames.group, layout.frame(record, at, time, payload)) else frames.unresolved++
            }

            store != null -> {
                val key = store.keyFor(frames.reader, layout.dataBytes.readBits(record, at))
                val payload = store.take(frames.reader, key)
                when {
                    payload != null -> sink.onCanFrame(frames.group, layout.frame(record, at, time, payload))
                    !frames.pending.add(key, PendingFrame(record.copyOfRange(at, at + size), time)) -> frames.unresolved++
                }
            }
        }
    }
}
