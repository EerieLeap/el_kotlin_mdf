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

/** One string or byte array channel's values to hand over: in the record, or in a variable-length group. */
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
)

/** A frame that waited for its payload. */
internal class PendingFrame(val offset: Long, val record: ByteArray, val time: Double)

/** The CAN frames of a group to hand over, and the frames whose payload hasn't come yet. */
internal class FramePlan(val group: MdfChannelGroup, val layout: CanFrameLayout, val store: VariableLengthStore) {
    val pending = ArrayList<PendingFrame>()
    var unresolved = 0L
}

/** What the scan does with the records of one channel group. */
internal class GroupPlan(val group: MdfChannelGroup) {
    /** The bytes of a record after its ID; for a variable-length group, 0. */
    val recordSize: Int = group.recordBytes.toInt()

    var timeField: NumberField? = null
    var timeConversion: MdfConversion? = null
    var timeIsInteger = true
    val samples = ArrayList<SamplePlan>()
    val texts = ArrayList<TextPlan>()
    var frames: FramePlan? = null

    /** For a variable-length group some channel reads values of: where they are kept as they go by. */
    var store: VariableLengthStore? = null

    /** The frame plans to hand the pending frames to when a value of this variable-length group arrives. */
    val frameReaders = ArrayList<FramePlan>()

    /** The records of the group seen so far. */
    var cycles = 0L

    val hasOutput: Boolean get() = samples.isNotEmpty() || texts.isNotEmpty() || frames != null
}
