package com.eerie_leap.mdf

/** What an array (CA) is for (`ca_type`). */
public enum class MdfArrayType(public val code: Int) {
    /** The values of an array, with no axes. */
    Array(0),

    /** The axis of another array: its points. */
    ScalingAxis(1),

    /** A map: values over axes. */
    LookUp(2),

    /** An axis of intervals. */
    IntervalAxis(3),

    /** The result of a classification. */
    ClassificationResult(4),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfArrayType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Where an array's elements are stored (`ca_storage`). */
public enum class MdfArrayStorage(public val code: Int) {
    /** In the record of the channel that has the array, one after the other from its byte offset. */
    ChannelTemplate(0),

    /** In channels of one channel group, the one the array's links name. */
    ChannelGroupTemplate(1),

    /** In channels of a data group, the ones the array's links name. */
    DataGroupTemplate(2),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfArrayStorage = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * The array (CA) that a channel's composition is: its elements, when they are laid out in the channel's record, are channels of
 * their own ([MdfChannel.members], named `oil_temp[3]`, `m[1][2]`). The element at index (i, j, ...) is at the byte offset of the
 * channel plus [byteOffsetBase] times its place in the array, the last index running fastest, or the first for an array of
 * [hasInverseLayout]. An array this reader doesn't lay out into channels is listed with the reason in [unreadReason], and its
 * channel isn't scanned.
 */
@Suppress("LongParameterList") // What a CA block holds, each as the standard names it; the model keeps every one.
public class MdfChannelArray internal constructor(
    public val type: MdfArrayType,
    public val storage: MdfArrayStorage,
    /** The number of elements along each dimension, in the order the block lists them. */
    public val dimensions: List<Long>,
    /** `ca_flags`; the properties below read it. */
    public val flags: Int,
    /** The bytes between one element and the next (`ca_byte_offset_base`). */
    public val byteOffsetBase: Int,
    /** The number of invalidation bits between one element's and the next (`ca_inval_bit_pos_base`). */
    public val invalidationBitBase: Long,
    private val axes: List<DoubleArray>,
    /** Why the elements aren't channels of their own, null where they are. */
    public val unreadReason: String?,
) {
    /** The values of each dimension's axis, for an array whose axes are fixed numbers and which is read; none otherwise. */
    public val fixedAxes: List<List<Double>> get() = axes.map { it.asList() }

    /** The number of elements: the product of the dimensions (capped at [Long.MAX_VALUE]). */
    public val elementCount: Long get() = countOf(dimensions)

    /** The dimensions change at run time, their sizes being values of other channels. */
    public val hasDynamicSize: Boolean get() = (flags and FLAG_DYNAMIC_SIZE) != 0

    /** The array has axes. */
    public val hasAxes: Boolean get() = (flags and FLAG_AXIS) != 0

    /** The axes are numbers stored in the array's block ([fixedAxes]); otherwise they are values of other channels. */
    public val hasFixedAxes: Boolean get() = (flags and FLAG_FIXED_AXIS) != 0

    /** The elements are stored with the first index changing fastest, not the last. */
    public val hasInverseLayout: Boolean get() = (flags and FLAG_INVERSE_LAYOUT) != 0

    override fun toString(): String = "MdfChannelArray($type, ${dimensions.joinToString("x")}${unreadReason?.let {
        ", not read: $it"
    }.orEmpty()})"

    internal companion object {
        /** A composition that couldn't be read, for a channel that must not be read as a plain one. */
        fun unreadable(reason: String): MdfChannelArray =
            MdfChannelArray(MdfArrayType.Unknown, MdfArrayStorage.Unknown, emptyList(), 0, 0, 0L, emptyList(), reason)

        /** The product of [dimensions], as large as a number goes. */
        fun countOf(dimensions: List<Long>): Long = dimensions.fold(1L) { product, size ->
            if (size != 0L && product > Long.MAX_VALUE / size) Long.MAX_VALUE else product * size
        }

        const val FLAG_DYNAMIC_SIZE = 0x01
        const val FLAG_AXIS = 0x10
        const val FLAG_FIXED_AXIS = 0x20
        const val FLAG_INVERSE_LAYOUT = 0x40
    }
}
