package com.eerie_leap.mdf

/** The kind of thing a source information block (`si_type`) names. */
public enum class MdfSourceType(public val code: Int) {
    Other(0),
    Ecu(1),
    Bus(2),
    IoDevice(3),
    Tool(4),
    User(5),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfSourceType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** The bus a bus event is on (`si_bus_type`). */
public enum class MdfBusType(public val code: Int) {
    None(0),
    Other(1),
    Can(2),
    Lin(3),
    Most(4),
    FlexRay(5),
    KLine(6),
    Ethernet(7),
    Usb(8),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfBusType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Where a channel or a channel group's data comes from (an SI block): a name, a path, what kind of source it is. */
public class MdfSourceInformation internal constructor(
    public val name: String,
    public val path: String,
    public val comment: MdfComment,
    public val type: MdfSourceType,
    public val busType: MdfBusType,
    public val flags: Int,
) {
    override fun toString(): String = "MdfSourceInformation($name, $type, $busType)"
}

/**
 * A group of channels that are sampled together: its records each hold a value of every channel. Its [recordId] tells its records
 * from the other groups' in an unsorted data group.
 */
@Suppress("LongParameterList") // A block's fields; the model holds each of them.
public class MdfChannelGroup internal constructor(
    /** Where the group's block is in the file. */
    public val offset: Long,
    public val recordId: Long,
    public val name: String,
    public val comment: MdfComment,
    public val source: MdfSourceInformation?,
    /** The number of records the file says the group has; stale in a file its writer didn't finish (see [MdfIdentification]). */
    public val cycleCount: Long,
    public val flags: Int,
    /** The bytes of a record's data, apart from its record ID and its invalidation bytes. For a variable-length group, 0. */
    public val dataBytes: Long,
    public val invalidationBytes: Long,
    /** For a variable-length group, the total bytes of its values the file says there are (stale in an unfinished file). */
    public val vlsdBytes: Long,
    public val channels: List<MdfChannel>,
) {
    /** The group holds the values of variable-length channels of other groups (strings, byte arrays, CAN payloads), not records. */
    public val isVlsd: Boolean get() = (flags and FLAG_VLSD) != 0

    /** The group holds bus events (CAN frames, LIN frames, ...). */
    public val isBusEvent: Boolean get() = (flags and FLAG_BUS_EVENT) != 0

    /** The group holds bus events in the plain layout of the bus logging standard: one event per record. */
    public val isPlainBusEvent: Boolean get() = (flags and FLAG_PLAIN_BUS_EVENT) != 0

    /** The group's master is a channel of another group. */
    public val hasRemoteMaster: Boolean get() = (flags and FLAG_REMOTE_MASTER) != 0

    /** The channel the others are plotted against: the first master, else the first virtual master, else none. */
    public val master: MdfChannel? =
        channels.firstOrNull { it.kind == MdfChannelKind.Master } ?: channels.firstOrNull { it.kind == MdfChannelKind.VirtualMaster }

    /** The bytes of one of the group's records, apart from its record ID; for a variable-length group, 0. */
    public val recordBytes: Long get() = dataBytes + invalidationBytes

    override fun toString(): String = "MdfChannelGroup(record $recordId, $name, ${channels.size} channels)"

    internal companion object {
        const val FLAG_VLSD = 0x01
        const val FLAG_BUS_EVENT = 0x02
        const val FLAG_PLAIN_BUS_EVENT = 0x04
        const val FLAG_REMOTE_MASTER = 0x08
    }
}

/**
 * A data group: the channel groups whose records are stored together. With a [recordIdSize] of 0 the data is sorted: one channel
 * group, its records one after another; otherwise each record begins with its group's record ID of that many bytes.
 */
public class MdfDataGroup internal constructor(
    /** Where the group's block is in the file. */
    public val offset: Long,
    /** The bytes of the record ID before each record: 0 (a sorted group), 1, 2, 4 or 8. */
    public val recordIdSize: Int,
    public val comment: MdfComment,
    public val channelGroups: List<MdfChannelGroup>,
    internal val dataLink: Long,
) {
    /** Whether the data is sorted: one channel group, no record IDs. */
    public val isSorted: Boolean get() = recordIdSize == 0

    override fun toString(): String = "MdfDataGroup(record id size $recordIdSize, ${channelGroups.size} channel groups)"
}
