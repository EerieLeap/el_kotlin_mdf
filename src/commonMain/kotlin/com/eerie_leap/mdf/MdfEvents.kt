package com.eerie_leap.mdf

/** What an event (EV) marks (`ev_type`). */
public enum class MdfEventType(public val code: Int) {
    Recording(0),
    RecordingInterrupt(1),
    AcquisitionInterrupt(2),
    StartRecordingTrigger(3),
    StopRecordingTrigger(4),
    Trigger(5),
    Marker(6),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfEventType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Whether an event is a point, or the beginning or end of a range (`ev_range_type`). */
public enum class MdfEventRange(public val code: Int) {
    Point(0),
    RangeBeginning(1),
    RangeEnd(2),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfEventRange = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** What caused an event (`ev_cause`). */
public enum class MdfEventCause(public val code: Int) {
    Other(0),
    Error(1),
    Tool(2),
    Script(3),
    User(4),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfEventCause = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** What an event applies to: a channel group or a channel (or, where the writer named neither, the data group). */
public class MdfEventScope internal constructor(
    public val group: MdfChannelGroup?,
    public val channel: MdfChannel?,
    public val dataGroup: MdfDataGroup?,
)

/**
 * An event (EV): something that happened, at a place on the time axis (or the axis the [syncType] names): a trigger, a marker, the
 * start or end of a recording or of a range. Its place is [syncBase] times [syncFactor] ([value]).
 */
@Suppress("LongParameterList") // A block's fields; the model holds each of them.
public class MdfEvent internal constructor(
    /** Where the event's block is in the file. */
    public val offset: Long,
    public val type: MdfEventType,
    public val syncType: MdfSyncType,
    public val range: MdfEventRange,
    public val cause: MdfEventCause,
    public val flags: Int,
    public val name: String,
    public val comment: MdfComment,
    /** The name of the group of events it belongs to (MDF 4.2), empty where there is none. */
    public val groupName: String,
    /** The index of the file history entry of the tool that made it. */
    public val creatorIndex: Int,
    public val syncBase: Long,
    public val syncFactor: Double,
    internal val parentLink: Long,
    internal val rangeLink: Long,
    internal val scopeLinks: LongArray,
    internal val attachmentLinks: LongArray,
) {
    /** The event's place: seconds for a time axis, radians for an angle, meters for a distance, a record's number for an index. */
    public val value: Double get() = syncBase * syncFactor

    /** The event was made by post-processing, not while recording. */
    public val isPostProcessing: Boolean get() = (flags and FLAG_POST_PROCESSING) != 0

    /** The event this one is under in a hierarchy of events. */
    public var parent: MdfEvent? = null
        internal set

    /** For the end of a range, the event that began it. */
    public var rangeStart: MdfEvent? = null
        internal set

    /** What the event applies to; none for an event about the whole file. */
    public var scopes: List<MdfEventScope> = emptyList()
        internal set

    /** The attachments it refers to. */
    public var attachments: List<MdfAttachment> = emptyList()
        internal set

    override fun toString(): String = "MdfEvent($name, $type, $syncType $value)"

    internal companion object {
        const val FLAG_POST_PROCESSING = 0x01
        const val FLAG_GROUP_NAME = 0x02
    }
}
