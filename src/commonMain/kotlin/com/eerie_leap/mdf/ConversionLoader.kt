package com.eerie_leap.mdf

// The links of a conversion block (CC), before its references.
private const val LINK_NAME = 0
private const val LINK_UNIT = 1
private const val LINK_COMMENT = 2
private const val FIXED_LINKS = 4

// Its fields after the links.
private const val TYPE = 0
private const val PRECISION = 1
private const val FLAGS = 2
private const val VALUE_COUNT = 6
private const val PHYSICAL_MIN = 8
private const val PHYSICAL_MAX = 16
private const val VALUES = 24
private const val VALUE_SIZE = 8

private const val FLAG_PRECISION_VALID = 0x01
private const val FLAG_RANGE_VALID = 0x02

private const val LINEAR_VALUES = 2
private const val PAIR = 2
private const val TRIPLET = 3
private const val TYPE_RANGE_TO_TEXT_PAIRS = 2
private const val MAX_NESTING = 8

/** Reads conversion blocks (CC) into [MdfConversion]s; the nested conversions of a table are read too, to a depth. */
internal class ConversionLoader(private val context: LoaderContext) {
    private val cache = HashMap<Long, MdfConversion>()

    /** The conversion at [link], null for no link. A reader that is lenient takes one it can't read as none. */
    suspend fun load(link: Long, holder: Long, subject: String): MdfConversion? {
        if (link == 0L) return null
        return context.tolerate(subject, null) { conversion(link, holder, subject, 0) }
    }

    private suspend fun conversion(link: Long, holder: Long, subject: String, depth: Int): MdfConversion {
        cache[link]?.let { return it }
        val block = context.reader.block(link, holder, "CC")
        block.requireData(VALUES)
        val info = info(block, subject)
        val type = block.u8(TYPE)
        val result = if (depth > MAX_NESTING) {
            MdfConversion.Unsupported(info, type, "conversions nest more than $MAX_NESTING levels deep")
        } else {
            block.requireData(VALUES + block.u16(VALUE_COUNT) * VALUE_SIZE)
            build(Reading(block, info, type, subject, depth))
        }
        return result.also { cache[link] = it }
    }

    private suspend fun info(block: Block, subject: String): MdfConversionInfo {
        val flags = block.u16(FLAGS)
        val min = block.f64(PHYSICAL_MIN)
        val max = block.f64(PHYSICAL_MAX)
        return MdfConversionInfo(
            name = context.text(block.link(LINK_NAME), block.offset, subject),
            unit = context.text(block.link(LINK_UNIT), block.offset, subject),
            comment = context.comment(block.link(LINK_COMMENT), block.offset, subject),
            precision = if ((flags and FLAG_PRECISION_VALID) != 0) block.u8(PRECISION) else null,
            physicalRange = if ((flags and FLAG_RANGE_VALID) != 0 && min <= max) min..max else null,
        )
    }

    // A conversion block being read: its fields, and the references to the texts and conversions it names.
    private class Reading(
        val block: Block,
        val info: MdfConversionInfo,
        val type: Int,
        val subject: String,
        val depth: Int,
    ) {
        val valueCount: Int = block.u16(VALUE_COUNT)
        val referenceCount: Int = block.linkCount - FIXED_LINKS

        fun value(index: Int): Double = block.f64(VALUES + index * VALUE_SIZE)

        fun values(from: Int, count: Int, step: Int = 1, offset: Int = 0): DoubleArray = DoubleArray(count) {
            value(from + it * step + offset)
        }

        fun reference(index: Int): Long = block.link(FIXED_LINKS + index)

        fun unsupported(reason: String): MdfConversion = MdfConversion.Unsupported(info, type, reason)
    }

    private suspend fun build(r: Reading): MdfConversion = when (r.type) {
        MdfConversion.Identity.CODE -> MdfConversion.Identity(r.info)
        MdfConversion.Linear.CODE -> linear(r)
        MdfConversion.Rational.CODE -> rational(r)
        MdfConversion.Algebraic.CODE -> algebraic(r)
        MdfConversion.ValueTable.CODE_INTERPOLATING, MdfConversion.ValueTable.CODE_NEAREST -> valueTable(r)
        MdfConversion.RangeToValue.CODE -> rangeToValue(r)
        MdfConversion.ValueToText.CODE -> valueToText(r)
        MdfConversion.RangeToText.CODE -> rangeToText(r)
        MdfConversion.TextToValue.CODE -> textToValue(r)
        MdfConversion.TextToText.CODE -> textToText(r)
        MdfConversion.BitfieldToText.CODE -> bitfieldToText(r)
        else -> r.unsupported("conversion type ${r.type} isn't one this reader knows")
    }

    private fun linear(r: Reading): MdfConversion = if (r.valueCount < LINEAR_VALUES) {
        r.unsupported("a linear conversion needs $LINEAR_VALUES values, the block has ${r.valueCount}")
    } else {
        MdfConversion.Linear(r.info, offset = r.value(0), factor = r.value(1))
    }

    private fun rational(r: Reading): MdfConversion = if (r.valueCount < MdfConversion.Rational.PARAMETER_COUNT) {
        r.unsupported("a rational conversion needs ${MdfConversion.Rational.PARAMETER_COUNT} values, the block has ${r.valueCount}")
    } else {
        MdfConversion.Rational(r.info, r.values(0, MdfConversion.Rational.PARAMETER_COUNT).toList())
    }

    private suspend fun algebraic(r: Reading): MdfConversion {
        if (r.referenceCount < 1 || r.reference(0) == 0L) return r.unsupported("the conversion names no formula")
        val formula = context.text(r.reference(0), r.block.offset, r.subject)
        return when (val parsed = MdfFormula.parse(formula)) {
            is MdfFormula.Parsed -> MdfConversion.Algebraic(r.info, formula, parsed.formula)
            is MdfFormula.Rejected -> r.unsupported("the formula \"$formula\" isn't one this reader evaluates: ${parsed.reason}")
        }
    }

    private fun valueTable(r: Reading): MdfConversion {
        val entries = r.valueCount / PAIR
        return if (entries == 0 || r.valueCount % PAIR != 0) {
            r.unsupported("a table of keys and values holds ${r.valueCount} values, not pairs")
        } else {
            MdfConversion.ValueTable(
                r.info,
                interpolates = r.type == MdfConversion.ValueTable.CODE_INTERPOLATING,
                keys = r.values(0, entries, step = PAIR),
                values = r.values(0, entries, step = PAIR, offset = 1),
            )
        }
    }

    private fun rangeToValue(r: Reading): MdfConversion {
        val entries = (r.valueCount - 1) / TRIPLET
        return if (r.valueCount < 1 || (r.valueCount - 1) % TRIPLET != 0) {
            r.unsupported("a table of ranges holds ${r.valueCount} values, not ranges with values and a default")
        } else {
            MdfConversion.RangeToValue(
                r.info,
                lowers = r.values(0, entries, step = TRIPLET),
                uppers = r.values(0, entries, step = TRIPLET, offset = 1),
                values = r.values(0, entries, step = TRIPLET, offset = 2),
                default = r.value(entries * TRIPLET),
            )
        }
    }

    private suspend fun valueToText(r: Reading): MdfConversion {
        val entries = r.valueCount
        if (r.referenceCount < entries) return r.unsupported("a table of $entries values names ${r.referenceCount} texts")
        return MdfConversion.ValueToText(r.info, r.values(0, entries), targets(r, entries), defaultTarget(r, entries))
    }

    private suspend fun rangeToText(r: Reading): MdfConversion {
        val entries = r.valueCount / TYPE_RANGE_TO_TEXT_PAIRS
        if (r.valueCount % PAIR != 0 || r.referenceCount < entries) {
            return r.unsupported("a table of ${r.valueCount} values names ${r.referenceCount} texts")
        }
        return MdfConversion.RangeToText(
            r.info,
            lowers = r.values(0, entries, step = PAIR),
            uppers = r.values(0, entries, step = PAIR, offset = 1),
            targets = targets(r, entries),
            default = defaultTarget(r, entries),
        )
    }

    private suspend fun textToValue(r: Reading): MdfConversion {
        val entries = r.referenceCount
        if (entries < 0 || r.valueCount < entries) return r.unsupported("a table of $entries texts holds ${r.valueCount} values")
        val keys = List(entries) { context.text(r.reference(it), r.block.offset, r.subject) }
        val default = if (r.valueCount > entries) r.value(entries) else Double.NaN
        return MdfConversion.TextToValue(r.info, keys, r.values(0, entries), default)
    }

    private suspend fun textToText(r: Reading): MdfConversion {
        val entries = (r.referenceCount - 1) / PAIR
        if (r.referenceCount < 1) return r.unsupported("a table of texts names no default")
        val keys = List(entries) { context.text(r.reference(it * PAIR), r.block.offset, r.subject) }
        val targets = List(entries) { target(r, r.reference(it * PAIR + 1)) ?: MdfConversionTarget.Label("") }
        return MdfConversion.TextToText(r.info, keys, targets, target(r, r.reference(entries * PAIR)))
    }

    private suspend fun bitfieldToText(r: Reading): MdfConversion {
        val entries = r.referenceCount
        if (entries < 0 || r.valueCount < entries) return r.unsupported("a bit field table of $entries fields holds ${r.valueCount} masks")
        val fields = List(entries) {
            val target = target(r, r.reference(it)) ?: MdfConversionTarget.Label("")
            val name = (target as? MdfConversionTarget.Nested)?.conversion?.info?.name.orEmpty()
            val mask = r.block.u64(VALUES + it * VALUE_SIZE)
            MdfConversion.BitField(mask, name, target)
        }
        return MdfConversion.BitfieldToText(r.info, fields)
    }

    private suspend fun targets(r: Reading, count: Int): List<MdfConversionTarget> =
        List(count) { target(r, r.reference(it)) ?: MdfConversionTarget.Label("") }

    private suspend fun defaultTarget(r: Reading, entries: Int): MdfConversionTarget? =
        if (r.referenceCount > entries) target(r, r.reference(entries)) else null

    // A reference of a table: the text it names, or a conversion of the value; none for no link.
    private suspend fun target(r: Reading, link: Long): MdfConversionTarget? {
        if (link == 0L) return null
        val header = context.reader.header(link, r.block.offset, "TX", "MD", "CC")
        return if (header.type == "CC") {
            MdfConversionTarget.Nested(conversion(link, r.block.offset, r.subject, r.depth + 1))
        } else {
            MdfConversionTarget.Label(context.text(link, r.block.offset, r.subject))
        }
    }
}
