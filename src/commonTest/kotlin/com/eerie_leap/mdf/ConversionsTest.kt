package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionsTest {
    private fun text(value: String) = ConversionSpec.TextRef(value)

    // A file with one channel group of one float64 channel `v` that has the conversion, and a record for each of the raw values.
    private fun fileOf(spec: ConversionSpec?, raws: List<Double> = listOf(0.0), unit: String? = null): ByteArray {
        val channel = ChannelSpec("v", DataTypes.FLOAT_LE, 64, conversion = spec, unit = unit)
        val records = ByteWriter()
        for (raw in raws) records.u8(1).f64(raw)
        return writeFile(FileSpec(listOf(DataGroupSpec(1, listOf(GroupSpec(1, "g", listOf(channel), 8)), records.toByteArray()))))
    }

    private fun channelOf(file: MdfFile): MdfChannel = file.channelGroups.single().channels.single()

    private fun conversionOf(spec: ConversionSpec): MdfConversion = checkNotNull(channelOf(open(fileOf(spec))).conversion)

    private fun scanned(spec: ConversionSpec, raws: List<Double>, stored: Boolean = false): List<Double> {
        val file = open(fileOf(spec, raws))
        val request = MdfChannelRequest(channelOf(file), stored)
        return scanAll(file, listOf(request)).of(request).map { it.value }
    }

    @Test
    fun aChannelWithNoConversionHasNone() {
        assertNull(channelOf(open(fileOf(null))).conversion)
    }

    @Test
    fun readsTheIdentityConversion() {
        val conversion = assertIs<MdfConversion.Identity>(conversionOf(ConversionSpec.identity()))

        assertEquals(0, conversion.code)
        assertEquals(7.5, conversion.toPhysical(7.5))
        assertEquals(listOf(1.0, -2.0), scanned(ConversionSpec.identity(), listOf(1.0, -2.0)))
    }

    @Test
    fun readsTheLinearConversion() {
        val conversion = assertIs<MdfConversion.Linear>(conversionOf(ConversionSpec.linear(offset = 3.0, factor = 0.5)))

        assertEquals(3.0, conversion.offset)
        assertEquals(0.5, conversion.factor)
        assertEquals(5.0, conversion.toPhysical(4.0))
        assertEquals(listOf(3.0, 5.0, 4.0), scanned(ConversionSpec.linear(3.0, 0.5), listOf(0.0, 4.0, 2.0)))
    }

    @Test
    fun readsTheRationalConversion() {
        val conversion = assertIs<MdfConversion.Rational>(conversionOf(ConversionSpec.rational(1.0, 2.0, 3.0, 0.0, 0.0, 1.0)))

        assertEquals(listOf(1.0, 2.0, 3.0, 0.0, 0.0, 1.0), conversion.parameters)
        assertEquals(11.0, conversion.toPhysical(2.0))
        // (2x + 6) / (x + 1)
        assertEquals(10.0 / 3.0, conversionOf(ConversionSpec.rational(0.0, 2.0, 6.0, 0.0, 1.0, 1.0)).toPhysical(2.0))
    }

    @Test
    fun readsTheAlgebraicConversionAndEvaluatesItsFormula() {
        val conversion = assertIs<MdfConversion.Algebraic>(conversionOf(ConversionSpec.algebraic("sqrt(X) * 2 + 1")))

        assertEquals("sqrt(X) * 2 + 1", conversion.formula)
        assertEquals(7.0, conversion.toPhysical(9.0))
        assertEquals(listOf(1.0, 3.0, 5.0), scanned(ConversionSpec.algebraic("sqrt(X) * 2 + 1"), listOf(0.0, 1.0, 4.0)))
        assertEquals(listOf(0.0, 1.0, 4.0), scanned(ConversionSpec.algebraic("sqrt(X) * 2 + 1"), listOf(0.0, 1.0, 4.0), stored = true))
    }

    @Test
    fun aFormulaTheReaderDoesntEvaluateLeavesTheValuesAsStored() {
        for (formula in listOf("X ^ 2", "X1 * 2", "exp(X)", "X +")) {
            val conversion = assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec.algebraic(formula)), formula)

            assertEquals(3, conversion.code)
            assertTrue(conversion.reason.contains(formula), conversion.reason)
            assertEquals(4.0, conversion.toPhysical(4.0))
            assertEquals(listOf(4.0), scanned(ConversionSpec.algebraic(formula), listOf(4.0)))
        }
    }

    @Test
    fun readsTheInterpolatingTableSortedByItsKeys() {
        val spec = ConversionSpec.table(true, 20.0 to 150.0, 0.0 to 0.0, 10.0 to 100.0)
        val conversion = assertIs<MdfConversion.ValueTable>(conversionOf(spec))

        assertEquals(listOf(0.0 to 0.0, 10.0 to 100.0, 20.0 to 150.0), conversion.entries)
        assertEquals(4, conversion.code)
        assertEquals(50.0, conversion.toPhysical(5.0))
        assertEquals(125.0, conversion.toPhysical(15.0))
        assertEquals(100.0, conversion.toPhysical(10.0))
        // Beyond the ends the end's value stands.
        assertEquals(0.0, conversion.toPhysical(-5.0))
        assertEquals(150.0, conversion.toPhysical(25.0))
        assertTrue(conversion.toPhysical(Double.NaN).isNaN())
    }

    @Test
    fun readsTheTableWithoutInterpolationAsTheNearestKey() {
        val conversion = assertIs<MdfConversion.ValueTable>(
            conversionOf(
                ConversionSpec.table(
                    false,
                    0.0 to 0.0,
                    10.0 to 100.0,
                    20.0 to 150.0,
                ),
            ),
        )

        assertEquals(5, conversion.code)
        assertEquals(0.0, conversion.toPhysical(4.0))
        assertEquals(100.0, conversion.toPhysical(6.0))
        // As near to two keys: the lower.
        assertEquals(0.0, conversion.toPhysical(5.0))
        assertEquals(100.0, conversion.toPhysical(15.0))
        assertEquals(100.0, conversion.toPhysical(14.0))
        assertEquals(150.0, conversion.toPhysical(25.0))
        assertEquals(0.0, conversion.toPhysical(-5.0))
    }

    @Test
    fun aTableOfOneEntryGivesItsValueEverywhere() {
        assertEquals(7.0, conversionOf(ConversionSpec.table(true, 3.0 to 7.0)).toPhysical(100.0))
        assertEquals(7.0, conversionOf(ConversionSpec.table(false, 3.0 to 7.0)).toPhysical(-100.0))
    }

    @Test
    fun readsTheValueRangeToValueConversionWithItsDefault() {
        val spec = ConversionSpec.rangeToValue(-1.0, Triple(0.0, 10.0, 1.0), Triple(10.0, 20.0, 2.0))
        val conversion = assertIs<MdfConversion.RangeToValue>(conversionOf(spec))

        assertEquals(-1.0, conversion.default)
        // A whole number is in a range with its upper bound; the first range that holds wins.
        assertEquals(1.0, conversion.toPhysical(10.0, rawIsInteger = true))
        assertEquals(2.0, conversion.toPhysical(11.0, rawIsInteger = true))
        assertEquals(2.0, conversion.toPhysical(20.0, rawIsInteger = true))
        assertEquals(-1.0, conversion.toPhysical(21.0, rawIsInteger = true))
        assertEquals(-1.0, conversion.toPhysical(-1.0, rawIsInteger = true))
        // A float is in a range without its upper bound.
        assertEquals(2.0, conversion.toPhysical(10.0, rawIsInteger = false))
        assertEquals(1.0, conversion.toPhysical(9.999, rawIsInteger = false))
        assertEquals(-1.0, conversion.toPhysical(20.0, rawIsInteger = false))
    }

    @Test
    fun readsTheValueToTextConversion() {
        val spec = ConversionSpec.valueToText(text("unknown"), 0.0 to text("off"), 1.0 to text("on"))
        val conversion = assertIs<MdfConversion.ValueToText>(conversionOf(spec))

        assertTrue(conversion.producesText)
        assertEquals("off", conversion.toText(0.0))
        assertEquals("on", conversion.toText(1.0))
        assertEquals("unknown", conversion.toText(5.0))
        assertTrue(conversion.toPhysical(0.0).isNaN())
        assertEquals(2, conversion.targets.size)
        assertEquals("unknown", assertIs<MdfConversionTarget.Label>(conversion.default).text)
    }

    @Test
    fun aValueInNoEntryAndNoDefaultStaysAsItIs() {
        val conversion = conversionOf(ConversionSpec.valueToText(null, 0.0 to text("off")))

        assertNull(conversion.toText(5.0))
        assertEquals(5.0, conversion.toPhysical(5.0))
    }

    @Test
    fun aTableEntryCanBeAnotherConversionOfTheValue() {
        val spec = ConversionSpec.valueToText(
            ConversionSpec.ConversionRef(ConversionSpec.linear(0.0, 10.0)),
            0.0 to text("off"),
            2.0 to ConversionSpec.ConversionRef(ConversionSpec.linear(100.0, 2.0)),
        )
        val conversion = conversionOf(spec)

        assertEquals("off", conversion.toText(0.0))
        // Entry 2 is 100 + 2 x, and a value in no entry takes the default, 10 x.
        assertEquals(104.0, conversion.toPhysical(2.0))
        assertEquals(30.0, conversion.toPhysical(3.0))
    }

    @Test
    fun nestedConversionsAreReadOnlyToADepth() {
        fun chain(depth: Int): ConversionSpec = if (depth == 0) {
            ConversionSpec.linear(0.0, 2.0)
        } else {
            ConversionSpec.valueToText(null, 1.0 to ConversionSpec.ConversionRef(chain(depth - 1)))
        }

        // Three levels down the last conversion doubles the value; twelve levels down it's cut off, and leaves the value as stored.
        assertEquals(2.0, conversionOf(chain(3)).toPhysical(1.0))
        assertEquals(1.0, conversionOf(chain(12)).toPhysical(1.0))
    }

    @Test
    fun readsTheValueRangeToTextConversion() {
        val spec = ConversionSpec.rangeToText(text("high"), (0.0 to 10.0) to text("low"), (10.0 to 20.0) to text("mid"))
        val conversion = assertIs<MdfConversion.RangeToText>(conversionOf(spec))

        assertEquals("low", conversion.toText(10.0, rawIsInteger = true))
        assertEquals("mid", conversion.toText(20.0, rawIsInteger = true))
        assertEquals("high", conversion.toText(21.0, rawIsInteger = true))
        assertEquals("mid", conversion.toText(10.0, rawIsInteger = false))
        assertEquals("high", conversion.toText(20.0, rawIsInteger = false))
        assertEquals("high", conversion.toText(-3.0))
    }

    @Test
    fun readsTheTextToValueConversion() {
        val conversion = assertIs<MdfConversion.TextToValue>(
            conversionOf(ConversionSpec.textToValue(-1.0, "red" to 1.0, "green" to 2.0)),
        )

        assertTrue(conversion.takesText)
        assertEquals(listOf("red", "green"), conversion.keys)
        assertEquals(2.0, assertIs<MdfConverted.Value>(conversion.convertText("green")).value)
        assertEquals(-1.0, assertIs<MdfConverted.Value>(conversion.convertText("blue")).value)
    }

    @Test
    fun readsTheTextToTextConversion() {
        val conversion = assertIs<MdfConversion.TextToText>(
            conversionOf(ConversionSpec.textToText(text("?"), "A" to text("Alpha"), "B" to text("Beta"))),
        )

        assertEquals("Alpha", assertIs<MdfConverted.Text>(conversion.convertText("A")).text)
        assertEquals("Beta", assertIs<MdfConverted.Text>(conversion.convertText("B")).text)
        assertEquals("?", assertIs<MdfConverted.Text>(conversion.convertText("C")).text)

        val withoutDefault = assertIs<MdfConversion.TextToText>(conversionOf(ConversionSpec.textToText(null, "A" to text("Alpha"))))
        assertEquals("C", assertIs<MdfConverted.Text>(withoutDefault.convertText("C")).text)
    }

    @Test
    fun readsTheBitFieldToTextConversion() {
        val mode = ConversionSpec(
            7,
            listOf(0.0, 1.0, 2.0),
            listOf(text("off"), text("eco"), text("sport"), text("?")),
            name = "mode",
        )
        val flag = ConversionSpec(7, listOf(0.0, 4.0), listOf(text(""), text("on"), null), name = "flag")
        val conversion = assertIs<MdfConversion.BitfieldToText>(conversionOf(ConversionSpec.bitfield(0x03L to mode, 0x04L to flag)))

        assertEquals(listOf("mode", "flag"), conversion.fields.map { it.name })
        assertEquals(listOf(3L, 4L), conversion.fields.map { it.mask })
        assertEquals("mode=sport|flag=on", conversion.toText(6.0))
        // A field whose text is empty is its name alone.
        assertEquals("mode=eco|flag=", conversion.toText(1.0))
        assertEquals("mode=?|flag=on", conversion.toText(7.0))
        // A value that isn't a whole number has no bits.
        assertEquals("", conversion.toText(1.5))
        assertEquals("", conversion.toText(-1.0))
    }

    @Test
    fun aTypeItDoesntKnowLeavesTheValuesAsStored() {
        val conversion = assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec(99, listOf(1.0))))

        assertEquals(99, conversion.code)
        assertTrue(conversion.reason.contains("99"))
        assertEquals(5.0, conversion.toPhysical(5.0))
        assertEquals(listOf(5.0), scanned(ConversionSpec(99), listOf(5.0)))
    }

    @Test
    fun aBlockWithTooFewValuesIsUnsupportedNotDamaged() {
        assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec(1, listOf(1.0))))
        assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec(2, listOf(1.0, 2.0))))
        assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec(4, listOf(1.0, 2.0, 3.0))))
        assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec(6, listOf(1.0, 2.0))))
        assertIs<MdfConversion.Unsupported>(conversionOf(ConversionSpec(3)))
    }

    @Test
    fun theChannelTakesItsUnitFromTheConversionWhereItHasNone() {
        val spec = ConversionSpec(1, listOf(0.0, 1.0), unit = "km/h")

        assertEquals("km/h", channelOf(open(fileOf(spec))).unit)
        assertEquals("mph", channelOf(open(fileOf(spec, unit = "mph"))).unit)
        assertEquals("km/h", channelOf(open(fileOf(spec))).conversion?.info?.unit)
    }

    @Test
    fun aConversionToTextsLeavesTheStoredNumberToDraw() {
        val spec = ConversionSpec.valueToText(text("?"), 0.0 to text("off"), 1.0 to text("on"))
        val file = open(fileOf(spec, listOf(0.0, 1.0, 1.0, 0.0)))
        val channel = channelOf(file)
        val request = MdfChannelRequest(channel)

        val values = scanAll(file, listOf(request)).of(request).map { it.value }

        assertEquals(listOf(0.0, 1.0, 1.0, 0.0), values)
        assertEquals(listOf("off", "on", "on", "off"), values.map { channel.conversion?.toText(it) })
    }

    @Test
    fun readsTheChannelsValueRangeAndPrecisionAndTheConversionsPhysicalRange() {
        val channel = ChannelSpec("v", DataTypes.FLOAT_LE, 64, valueRange = -5.0..40.0)
        val bytes = writeFile(FileSpec(listOf(DataGroupSpec(1, listOf(GroupSpec(1, "g", listOf(channel), 8)), ByteArray(0)))))

        assertEquals(-5.0..40.0, channelOf(open(bytes)).valueRange)
        assertNull(channelOf(open(fileOf(null))).valueRange)
    }
}
