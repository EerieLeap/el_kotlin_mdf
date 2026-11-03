package com.eerie_leap.mdf

import kotlin.math.abs
import kotlin.math.floor

/** What a conversion makes of a stored value: a number or a text. */
public sealed interface MdfConverted {
    public class Value(public val value: Double) : MdfConverted {
        override fun toString(): String = "Value($value)"
    }

    public class Text(public val text: String) : MdfConverted {
        override fun toString(): String = "Text($text)"
    }
}

/** What a table conversion gives for an entry: a text, or the result of another conversion of the value. */
public sealed interface MdfConversionTarget {
    public class Label(public val text: String) : MdfConversionTarget

    public class Nested(public val conversion: MdfConversion) : MdfConversionTarget
}

/** What every conversion block (`##CC`) says besides how to convert. */
public class MdfConversionInfo internal constructor(
    public val name: String,
    public val unit: String,
    public val comment: MdfComment,
    /** The decimals to show, where the block says it. */
    public val precision: Int?,
    /** The range of the converted values, where the block says it. */
    public val physicalRange: ClosedFloatingPointRange<Double>?,
)

/**
 * How a channel's stored value becomes its physical value (or a text). A numeric conversion gives a number through [toPhysical];
 * a table conversion to texts gives a text through [toText] (an enumeration's label), and leaves the stored value as the number to
 * draw. One this reader doesn't take is [Unsupported], which leaves the values as stored.
 *
 * Tables that compare a stored value to ranges take it as a whole number ([rawIsInteger], true for a channel of an integer type)
 * or as a float: a range includes its upper bound for the first and leaves it out for the second, as the standard has it.
 */
public sealed class MdfConversion(public val info: MdfConversionInfo) {
    /** The conversion's type, `cc_type`. */
    public abstract val code: Int

    /** Whether the conversion gives a text for some values: a value-to-text table, a range-to-text table, a bit field. */
    public open val producesText: Boolean get() = false

    /** Whether the conversion takes a text, not a number, as its input: a text-to-value or a text-to-text table. */
    public open val takesText: Boolean get() = false

    /** What the stored number [raw] becomes. */
    public abstract fun convert(raw: Double, rawIsInteger: Boolean = true): MdfConverted

    /** What the stored text [text] becomes. A conversion that takes numbers leaves the text as it is. */
    public open fun convertText(text: String): MdfConverted = MdfConverted.Text(text)

    /** The physical value of [raw] as a number, NaN for a value that becomes a text. */
    public open fun toPhysical(raw: Double, rawIsInteger: Boolean = true): Double =
        (convert(raw, rawIsInteger) as? MdfConverted.Value)?.value ?: Double.NaN

    /** The text [raw] becomes, null for a value that becomes a number. */
    public fun toText(raw: Double, rawIsInteger: Boolean = true): String? = (convert(raw, rawIsInteger) as? MdfConverted.Text)?.text

    /** No conversion: the stored value is the physical value. */
    public class Identity internal constructor(info: MdfConversionInfo) : MdfConversion(info) {
        override val code: Int get() = CODE

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(raw)

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double = raw

        internal companion object {
            const val CODE = 0
        }
    }

    /** `factor * raw + offset`. */
    public class Linear internal constructor(info: MdfConversionInfo, public val offset: Double, public val factor: Double) :
        MdfConversion(info) {
        override val code: Int get() = CODE

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(toPhysical(raw, rawIsInteger))

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double = factor * raw + offset

        internal companion object {
            const val CODE = 1
        }
    }

    /** `(p[0] * raw² + p[1] * raw + p[2]) / (p[3] * raw² + p[4] * raw + p[5])`. */
    public class Rational internal constructor(info: MdfConversionInfo, public val parameters: List<Double>) : MdfConversion(info) {
        private val numerator = parameters.take(PARAMETER_COUNT / 2)
        private val denominator = parameters.drop(PARAMETER_COUNT / 2)

        override val code: Int get() = CODE

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(toPhysical(raw, rawIsInteger))

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double =
            (numerator[0] * raw * raw + numerator[1] * raw + numerator[2]) /
                (denominator[0] * raw * raw + denominator[1] * raw + denominator[2])

        internal companion object {
            const val CODE = 2
            const val PARAMETER_COUNT = 6
        }
    }

    /** A formula of the stored value `X`, evaluated in `Double` (see [MdfFormula] for what it takes). */
    public class Algebraic internal constructor(info: MdfConversionInfo, public val formula: String, private val parsed: MdfFormula) :
        MdfConversion(info) {
        override val code: Int get() = CODE

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(toPhysical(raw, rawIsInteger))

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double = parsed.evaluate(raw)

        internal companion object {
            const val CODE = 3
        }
    }

    /**
     * A table of stored values and the physical values they become (`cc_type` 4 and 5), its entries by ascending stored value.
     * With [interpolates] a stored value between two keys becomes the value on the line between theirs, and one beyond the ends
     * the end's value; without it, the value of the nearest key (the lower of two as near).
     */
    public class ValueTable internal constructor(
        info: MdfConversionInfo,
        public val interpolates: Boolean,
        keys: DoubleArray,
        values: DoubleArray,
    ) : MdfConversion(info) {
        private val keys: DoubleArray
        private val values: DoubleArray

        init {
            val order = keys.indices.sortedBy { keys[it] }
            this.keys = DoubleArray(order.size) { keys[order[it]] }
            this.values = DoubleArray(order.size) { values[order[it]] }
        }

        public val entries: List<Pair<Double, Double>> get() = keys.indices.map { keys[it] to values[it] }

        override val code: Int get() = if (interpolates) CODE_INTERPOLATING else CODE_NEAREST

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(toPhysical(raw, rawIsInteger))

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double = when {
            keys.isEmpty() || raw.isNaN() -> raw
            interpolates -> interpolate(raw)
            else -> values[nearest(raw)]
        }

        private fun interpolate(x: Double): Double {
            val above = lowerBound(keys, x)
            if (above == 0) return values[0]
            if (above == keys.size) return values[keys.size - 1]
            val below = above - 1
            val span = keys[above] - keys[below]
            return if (span == 0.0) values[above] else values[below] + (values[above] - values[below]) * (x - keys[below]) / span
        }

        private fun nearest(x: Double): Int {
            val above = minOf(lowerBound(keys, x), keys.size - 1)
            val below = maxOf(above - 1, 0)
            return if (abs(x - keys[above]) >= abs(x - keys[below])) below else above
        }

        internal companion object {
            const val CODE_INTERPOLATING = 4
            const val CODE_NEAREST = 5
        }
    }

    /**
     * A table of ranges of stored values and the physical value each becomes (`cc_type` 6), tried in order, and a [default] for a
     * value in none.
     */
    public class RangeToValue internal constructor(
        info: MdfConversionInfo,
        lowers: DoubleArray,
        uppers: DoubleArray,
        values: DoubleArray,
        public val default: Double,
    ) : MdfConversion(info) {
        private val ranges = ValueRanges(lowers, uppers)
        private val values: DoubleArray = values.copyOf()

        override val code: Int get() = CODE

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(toPhysical(raw, rawIsInteger))

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double {
            val index = ranges.indexOf(raw, rawIsInteger)
            return if (index < 0) default else values[index]
        }

        internal companion object {
            const val CODE = 6
        }
    }

    /** A table of stored values and the text or conversion each becomes (`cc_type` 7), and what a value in none becomes. */
    public class ValueToText internal constructor(
        info: MdfConversionInfo,
        keys: DoubleArray,
        public val targets: List<MdfConversionTarget>,
        public val default: MdfConversionTarget?,
    ) : MdfConversion(info) {
        private val keys: DoubleArray = keys.copyOf()

        override val code: Int get() = CODE

        override val producesText: Boolean get() = true

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted {
            val index = indexOfKey(keys, raw)
            val target = if (index >= 0) targets[index] else default
            return resolve(target, raw, rawIsInteger)
        }

        internal companion object {
            const val CODE = 7
        }
    }

    /** A table of ranges of stored values and the text or conversion each becomes (`cc_type` 8), tried in order, and a default. */
    public class RangeToText internal constructor(
        info: MdfConversionInfo,
        lowers: DoubleArray,
        uppers: DoubleArray,
        public val targets: List<MdfConversionTarget>,
        public val default: MdfConversionTarget?,
    ) : MdfConversion(info) {
        private val ranges = ValueRanges(lowers, uppers)

        override val code: Int get() = CODE

        override val producesText: Boolean get() = true

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted {
            val index = ranges.indexOf(raw, rawIsInteger)
            return resolve(if (index >= 0) targets[index] else default, raw, rawIsInteger)
        }

        internal companion object {
            const val CODE = 8
        }
    }

    /** A table of texts and the numbers they become (`cc_type` 9), and the [default] number for a text in none. */
    public class TextToValue internal constructor(
        info: MdfConversionInfo,
        public val keys: List<String>,
        values: DoubleArray,
        public val default: Double,
    ) : MdfConversion(info) {
        private val values: DoubleArray = values.copyOf()

        override val code: Int get() = CODE

        override val takesText: Boolean get() = true

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(raw)

        override fun convertText(text: String): MdfConverted {
            val index = keys.indexOf(text)
            return MdfConverted.Value(if (index >= 0) values[index] else default)
        }

        internal companion object {
            const val CODE = 9
        }
    }

    /** A table of texts and the texts or conversions they become (`cc_type` 10), and what a text in none becomes. */
    public class TextToText internal constructor(
        info: MdfConversionInfo,
        public val keys: List<String>,
        public val targets: List<MdfConversionTarget>,
        public val default: MdfConversionTarget?,
    ) : MdfConversion(info) {
        override val code: Int get() = CODE

        override val takesText: Boolean get() = true

        override val producesText: Boolean get() = true

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(raw)

        override fun convertText(text: String): MdfConverted {
            val index = keys.indexOf(text)
            val target = if (index >= 0) targets[index] else default
            return when (target) {
                null -> MdfConverted.Text(text)
                is MdfConversionTarget.Label -> MdfConverted.Text(target.text)
                is MdfConversionTarget.Nested -> target.conversion.convertText(text)
            }
        }

        internal companion object {
            const val CODE = 10
        }
    }

    /** One bit field of a [BitfieldToText]: the bits it covers, its name and what its bits become. */
    public class BitField internal constructor(public val mask: Long, public val name: String, public val target: MdfConversionTarget)

    /**
     * A stored value that is a set of bit fields, as the text of each field's value joined with `|` (`cc_type` 11): the fields'
     * masks pick bits of the value, which each field's table turns into a text, prefixed with `name=` where the field has a name.
     */
    public class BitfieldToText internal constructor(info: MdfConversionInfo, public val fields: List<BitField>) : MdfConversion(info) {
        override val code: Int get() = CODE

        override val producesText: Boolean get() = true

        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted {
            val bits = wholeBits(raw) ?: return MdfConverted.Text("")
            val parts = ArrayList<String>()
            for (field in fields) {
                val masked = (field.mask and bits).toULong().toDouble()
                val text = when (val target = field.target) {
                    is MdfConversionTarget.Label -> target.text
                    is MdfConversionTarget.Nested -> target.conversion.convert(masked, rawIsInteger).asText()
                }
                val prefix = if (field.name.isEmpty()) "" else "${field.name}="
                if (text.isNotEmpty()) {
                    parts.add(prefix + text)
                } else if (prefix.isNotEmpty()) {
                    parts.add(prefix)
                }
            }
            return MdfConverted.Text(parts.joinToString("|"))
        }

        // The value as the unsigned 64-bit number it is, or null for one that isn't a whole number in that range.
        private fun wholeBits(raw: Double): Long? {
            val whole = raw >= 0.0 && raw < TWO_POW_64 && raw == floor(raw)
            return if (whole) raw.toULong().toLong() else null
        }

        private fun MdfConverted.asText(): String = when (this) {
            is MdfConverted.Text -> text
            is MdfConverted.Value -> if (value == 0.0) "" else value.toString()
        }

        internal companion object {
            const val CODE = 11
            const val TWO_POW_64 = 18446744073709551616.0
        }
    }

    /**
     * A conversion this reader doesn't take: [code] is its `cc_type`, and [reason] says why. It leaves the values as stored (and
     * a text as it is).
     */
    public class Unsupported internal constructor(info: MdfConversionInfo, override val code: Int, public val reason: String) :
        MdfConversion(info) {
        override fun convert(raw: Double, rawIsInteger: Boolean): MdfConverted = MdfConverted.Value(raw)

        override fun toPhysical(raw: Double, rawIsInteger: Boolean): Double = raw
    }

    internal companion object {
        // The index of the key equal to x, -1 for none (a NaN equals nothing).
        fun indexOfKey(keys: DoubleArray, x: Double): Int {
            for (i in keys.indices) if (keys[i] == x) return i
            return -1
        }

        // The first index whose key is at least x: keys.size where there is none.
        fun lowerBound(keys: DoubleArray, x: Double): Int {
            var low = 0
            var high = keys.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (keys[middle] < x) low = middle + 1 else high = middle
            }
            return low
        }

        // What a table entry's target makes of the stored value: a text, or what the nested conversion makes; a value in no entry,
        // with no default, stays as it is.
        fun resolve(target: MdfConversionTarget?, raw: Double, rawIsInteger: Boolean): MdfConverted = when (target) {
            null -> MdfConverted.Value(raw)
            is MdfConversionTarget.Label -> MdfConverted.Text(target.text)
            is MdfConversionTarget.Nested -> target.conversion.convert(raw, rawIsInteger)
        }
    }
}

// The ranges of a range table: lower <= x <= upper for a whole number, lower <= x < upper for a float; the first that holds wins.
private class ValueRanges(lowers: DoubleArray, uppers: DoubleArray) {
    private val lowers = lowers.copyOf()
    private val uppers = uppers.copyOf()

    fun indexOf(x: Double, isInteger: Boolean): Int {
        for (i in lowers.indices) {
            val inside = if (isInteger) x >= lowers[i] && x <= uppers[i] else x >= lowers[i] && x < uppers[i]
            if (inside) return i
        }
        return -1
    }
}
