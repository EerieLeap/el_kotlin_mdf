package com.eerie_leap.mdf

/** One channel's samples to hand over: what to read, whether it is invalid, and what to convert it with. */
internal class SamplePlan(
    val request: MdfChannelRequest,
    /** Where the value is; null for a virtual channel, whose value is the record's number. */
    val field: NumberField?,
    val validity: Validity,
    /** The conversion to apply, null for the stored value or for a conversion to texts, which leaves the number to draw. */
    val conversion: MdfConversion?,
    val rawIsInteger: Boolean,
)

/** The most records that wait for a value of a variable-length group that hasn't come yet, in all the plans of a scan. */
internal const val MAX_PENDING_RECORDS = 100_000

/** The records that came before the variable-length value they name, by the key of the value they wait for. */
internal class Pending<T> {
    private val waiting = HashMap<Long, ArrayList<T>>()

    var size = 0
        private set

    fun add(key: Long, item: T): Boolean {
        if (size >= MAX_PENDING_RECORDS) return false
        waiting.getOrPut(key) { ArrayList() }.add(item)
        size++
        return true
    }

    /** The records waiting for the value at [key], now no longer waiting. */
    fun remove(key: Long): List<T> {
        val items = waiting.remove(key) ?: return emptyList()
        size -= items.size
        return items
    }
}

/** A text that waited for its value. */
internal class PendingText(val time: Double, val isInvalid: Boolean)

/** One string or byte array channel's values to hand over: in the record, in a variable-length group, or in signal data blocks. */
@Suppress("LongParameterList") // A plan's fields.
internal class TextPlan(
    val request: MdfChannelRequest,
    val dataType: MdfDataType,
    val byteOffset: Int,
    val byteCount: Int,
    val validity: Validity,
    /** For a variable-length channel, where the offset into the values is and the values are; else null. */
    val offsetField: NumberField?,
    val store: VariableLengthStore?,
    /** For a variable-length channel whose values are in signal data blocks: where they are read from; else null. */
    val signalData: SignalDataValues? = null,
) {
    /** This plan's number among the readers of [store]. */
    val reader: Int = store?.register() ?: 0

    /** The value of signal data found for the record being taken, null where there is none. */
    var resolved: ByteArray? = null

    val pending = Pending<PendingText>()
}

/** A frame that waited for its payload. */
internal class PendingFrame(val record: ByteArray, val time: Double)

/** The CAN frames of a group to hand over, and the frames whose payload hasn't come yet. */
internal class FramePlan(
    val group: MdfChannelGroup,
    val layout: CanFrameLayout,
    val store: VariableLengthStore?,
    val signalData: SignalDataValues? = null,
) {
    /** This plan's number among the readers of [store]. */
    val reader: Int = store?.register() ?: 0

    val pending = Pending<PendingFrame>()
    var unresolved = 0L

    /** The payload of signal data found for the record being taken, null where there is none. */
    var resolved: ByteArray? = null
}

/** What the scan does with the records of one channel group. */
internal class GroupPlan(val group: MdfChannelGroup) {
    /** The bytes of a record after its ID; for a variable-length group, 0. */
    val recordSize: Int = group.recordBytes.toInt()

    var timeField: NumberField? = null
    var timeConversion: MdfConversion? = null
    var timeIsInteger = true

    /** For a group whose master is in another channel group: the master's values, read by a scan of their own. */
    var remoteTimes: MasterTimes? = null

    /** Why the group's time can't be read, so that nothing of the group can be placed on it; null where it can. */
    var timeFailure: MdfFailure? = null

    val samples = ArrayList<SamplePlan>()
    val texts = ArrayList<TextPlan>()
    var frames: FramePlan? = null

    /** For a variable-length group some channel reads values of: where they are kept as they go by. */
    var store: VariableLengthStore? = null

    /** The text plans to hand the pending texts to when a value of this variable-length group arrives. */
    val textReaders = ArrayList<TextPlan>()

    /** The frame plans to hand the pending frames to when a value of this variable-length group arrives. */
    val frameReaders = ArrayList<FramePlan>()

    /** The records of the group seen so far. */
    var cycles = 0L

    val hasOutput: Boolean get() = samples.isNotEmpty() || texts.isNotEmpty() || frames != null

    /** Whether a record of the group needs something read from the file before it can be taken (signal data, a remote master). */
    val needsLookup: Boolean
        get() = remoteTimes != null || texts.any { it.signalData != null } || frames?.signalData != null
}
