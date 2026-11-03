package com.eerie_leap.mdf

/** What a channel holds (`cn_type`). */
public enum class MdfChannelKind(public val code: Int) {
    /** A value in every record. */
    Fixed(0),

    /** A value of variable length, kept apart from the records; the record holds an offset to it (strings, byte arrays, CAN payloads). */
    Vlsd(1),

    /** The channel the others of its group are plotted against (time, angle, distance, an index). */
    Master(2),

    /** A master that is no stored data: the record's number, through the channel's conversion. */
    VirtualMaster(3),

    /** A channel that synchronizes the group with data of another kind. */
    Synchronization(4),

    /** The longest length of a variable-length channel's values. */
    MaxLength(5),

    /** A data channel that is no stored data: the record's number, through the channel's conversion. */
    VirtualData(6),

    /** A type this reader doesn't know. */
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfChannelKind = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** What a master channel counts (`cn_sync_type`). */
public enum class MdfSyncType(public val code: Int) {
    None(0),
    Time(1),
    Angle(2),
    Distance(3),
    Index(4),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfSyncType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** How a channel's value is stored (`cn_data_type`). */
public enum class MdfDataType(public val code: Int) {
    UnsignedIntegerLittleEndian(0),
    UnsignedIntegerBigEndian(1),
    SignedIntegerLittleEndian(2),
    SignedIntegerBigEndian(3),
    FloatLittleEndian(4),
    FloatBigEndian(5),
    StringLatin1(6),
    StringUtf8(7),
    StringUtf16LittleEndian(8),
    StringUtf16BigEndian(9),
    ByteArray(10),
    MimeSample(11),
    MimeStream(12),
    CanOpenDate(13),
    CanOpenTime(14),
    ComplexLittleEndian(15),
    ComplexBigEndian(16),
    Unknown(-1),
    ;

    /** Whether the value is a whole number, signed or not. */
    public val isInteger: Boolean get() = this in INTEGERS

    /** Whether the value is an IEEE 754 float of 16, 32 or 64 bits. */
    public val isFloat: Boolean get() = this == FloatLittleEndian || this == FloatBigEndian

    /** Whether the value can be drawn as a number. */
    public val isNumber: Boolean get() = isInteger || isFloat

    public val isString: Boolean get() = this in STRINGS

    public val isSigned: Boolean get() = this == SignedIntegerLittleEndian || this == SignedIntegerBigEndian

    /** Whether the most significant byte is stored first. */
    public val isBigEndian: Boolean
        get() = this == UnsignedIntegerBigEndian || this == SignedIntegerBigEndian || this == FloatBigEndian ||
            this == StringUtf16BigEndian || this == ComplexBigEndian

    internal companion object {
        private val INTEGERS = setOf(
            UnsignedIntegerLittleEndian,
            UnsignedIntegerBigEndian,
            SignedIntegerLittleEndian,
            SignedIntegerBigEndian,
        )
        private val STRINGS = setOf(StringLatin1, StringUtf8, StringUtf16LittleEndian, StringUtf16BigEndian)

        fun of(code: Int): MdfDataType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * One channel of a channel group: a value in each of the group's records, or the group's master.
 *
 * Where the value is: [byteOffset] bytes into the record's data (after the record ID), from its [bitOffset]th bit, [bitCount] bits
 * long. A channel of a composition (a structure) has its place in the record like any other.
 */
@Suppress("LongParameterList") // A block's fields; the model holds each of them.
public class MdfChannel internal constructor(
    /** Where the channel's block is in the file. */
    public val offset: Long,
    public val name: String,
    /** The unit, from the channel and, where it has none, from its conversion; empty where there is none. */
    public val unit: String,
    public val comment: MdfComment,
    public val kind: MdfChannelKind,
    public val syncType: MdfSyncType,
    public val dataType: MdfDataType,
    public val byteOffset: Int,
    public val bitOffset: Int,
    public val bitCount: Int,
    /** `cn_flags`; the properties below read it. */
    public val flags: Int,
    /** The position of the channel's invalidation bit among the bits after the record's data, or none. */
    public val invalidationBit: Int?,
    public val conversion: MdfConversion?,
    public val source: MdfSourceInformation?,
    /** The channels of a structure, for a channel that is one; none otherwise. */
    public val members: List<MdfChannel>,
    /** The range the stored values are in (`cn_val_range_min` and `cn_val_range_max`), where the channel says one. */
    public val valueRange: ClosedFloatingPointRange<Double>?,
    /** The decimals to show (`cn_precision`), where the channel says it. */
    public val precision: Int?,
    internal val compositionType: String?,
    internal val signalDataLink: Long,
    /** The array the channel's composition is, where it is one: then [members] are its elements, if it could lay them out. */
    public val array: MdfChannelArray? = null,
) {
    /** The variable-length data group this channel's values are kept in, for a channel of that kind in an unsorted group. */
    public var signalDataGroup: MdfChannelGroup? = null
        internal set

    /** The bytes of the record the channel's value spans: its bits, from the bit offset, rounded up to whole bytes. */
    public val byteSpan: Long get() = (bitOffset.toLong() + bitCount + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS

    /** All the channel's values are invalid. */
    public val allValuesInvalid: Boolean get() = (flags and FLAG_ALL_VALUES_INVALID) != 0

    /** Whether the channel has an invalidation bit in the records. A channel without one has valid values unless it says all are not. */
    public val hasInvalidationBit: Boolean get() = invalidationBit != null

    public val isDiscrete: Boolean get() = (flags and FLAG_DISCRETE) != 0

    public val isCalculated: Boolean get() = (flags and FLAG_CALCULATED) != 0

    /** The channel's values aren't stored in the records: they are the record's number, through the channel's conversion. */
    public val isVirtual: Boolean get() = kind == MdfChannelKind.VirtualMaster || kind == MdfChannelKind.VirtualData

    public val isBusEvent: Boolean get() = (flags and FLAG_BUS_EVENT) != 0

    public val isStrictlyMonotonous: Boolean get() = (flags and FLAG_STRICTLY_MONOTONOUS) != 0

    /**
     * Whether this channel's values are numbers a plot can draw: stored in the record, as a number, and not an array (whose
     * elements are the channels to draw).
     */
    public val isNumeric: Boolean
        get() = dataType.isNumber && array == null && (kind == MdfChannelKind.Fixed || kind == MdfChannelKind.Master || isVirtual)

    /** The channel and the channels inside it (the members of a structure, the elements of an array), depth first. */
    internal fun withMembers(): List<MdfChannel> = listOf(this) + members.flatMap { it.withMembers() }

    /** The channel with [members] and [array] set: the elements of an array, made from the channel as the loader found it. */
    internal fun withArray(array: MdfChannelArray, members: List<MdfChannel>): MdfChannel = MdfChannel(
        offset, name, unit, comment, kind, syncType, dataType, byteOffset, bitOffset, bitCount, flags, invalidationBit, conversion, source,
        members, valueRange, precision, compositionType, signalDataLink, array,
    ).also { it.signalDataGroup = signalDataGroup }

    /** One element of the array this channel is, named [name], [byteOffset] bytes into the record, with its invalidation bit. */
    internal fun element(name: String, byteOffset: Int, invalidationBit: Int?): MdfChannel = MdfChannel(
        offset, name, unit, comment, kind, MdfSyncType.None, dataType, byteOffset, bitOffset, bitCount, flags, invalidationBit, conversion,
        source, emptyList(), valueRange, precision, null, 0L, null,
    )

    override fun toString(): String = "MdfChannel($name, $kind, $dataType, byte $byteOffset bit $bitOffset+$bitCount)"

    internal companion object {
        const val FLAG_ALL_VALUES_INVALID = 0x0001
        const val FLAG_INVALIDATION_BIT_VALID = 0x0002
        const val FLAG_PRECISION_VALID = 0x0004
        const val FLAG_RANGE_VALID = 0x0008
        const val FLAG_DISCRETE = 0x0040
        const val FLAG_CALCULATED = 0x0100
        const val FLAG_BUS_EVENT = 0x0400
        const val FLAG_STRICTLY_MONOTONOUS = 0x0800
    }
}
