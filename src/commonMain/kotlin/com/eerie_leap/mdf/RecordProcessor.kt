package com.eerie_leap.mdf

private const val MAX_VARIABLE_LENGTH_RECORD = 64L * 1024 * 1024
private const val MAX_PENDING_FRAMES = 100_000
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

    /**
     * Takes the whole records in `buffer[0, limit)`, which begins at [position] of the data, and returns how many bytes they were.
     * It stops short of a record the buffer holds only part of (see [needed]), of the end of sorted data, and of a record it can't
     * place (see [problem]), and after the record that takes it to [stopAt] bytes.
     */
    fun process(buffer: ByteArray, limit: Int, position: Long, stopAt: Int): Int {
        needed = 0
        var at = 0
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
        plan.store?.let { store ->
            store.add(buffer.copyOfRange(body, end))
            plan.frameReaders.filter { it.pending.isNotEmpty() }.forEach { resolvePending(it) }
        }
        plan.cycles++
        return end
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

    private fun emit(plan: GroupPlan, record: ByteArray, at: Int) {
        val time = timeOf(plan, record, at)
        for (sample in plan.samples) {
            val raw = sample.field?.read(record, at) ?: plan.cycles.toDouble()
            val value = sample.conversion?.toPhysical(raw, sample.rawIsInteger) ?: raw
            sink.onSample(sample.request, time, value, sample.validity.isInvalid(record, at))
        }
        for (text in plan.texts) emitText(text, record, at, time)
        plan.frames?.let { emitFrame(it, record, at, plan.recordSize, time) }
    }

    private fun timeOf(plan: GroupPlan, record: ByteArray, at: Int): Double {
        val raw = plan.timeField?.read(record, at) ?: plan.cycles.toDouble()
        return plan.timeConversion?.toPhysical(raw, plan.timeIsInteger) ?: raw
    }

    private fun emitText(text: TextPlan, record: ByteArray, at: Int, time: Double) {
        val store = text.store
        val offsetField = text.offsetField
        val value = if (store == null || offsetField == null) {
            MdfValues.text(text.dataType, record, at + text.byteOffset, text.byteCount)
        } else {
            store.take(offsetField.readBits(record, at))?.let { MdfValues.text(text.dataType, it, 0, it.size) }
        }
        if (value != null) sink.onText(text.request, time, value, text.validity.isInvalid(record, at))
    }

    private fun emitFrame(
        frames: FramePlan,
        record: ByteArray,
        at: Int,
        size: Int,
        time: Double,
    ) {
        val offset = frames.layout.dataBytes.readBits(record, at)
        val payload = frames.store.take(offset)
        when {
            payload != null -> sink.onCanFrame(frames.group, frames.layout.frame(record, at, time, payload))
            frames.pending.size < MAX_PENDING_FRAMES -> frames.pending.add(PendingFrame(offset, record.copyOfRange(at, at + size), time))
            else -> frames.unresolved++
        }
    }

    // Frames that came before their payload: the payloads that have arrived since are theirs.
    private fun resolvePending(frames: FramePlan) {
        val iterator = frames.pending.iterator()
        while (iterator.hasNext()) {
            val waiting = iterator.next()
            val payload = frames.store.take(waiting.offset)
            if (payload != null) {
                iterator.remove()
                sink.onCanFrame(frames.group, frames.layout.frame(waiting.record, 0, waiting.time, payload))
            }
        }
    }
}
