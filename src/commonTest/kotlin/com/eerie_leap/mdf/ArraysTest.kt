package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Arrays (CA) of channels: each element of a fixed-size array is a channel of its own; the others are listed. */
class ArraysTest {
    private val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)

    private fun fileOf(
        array: ArraySpec,
        recordBytes: Int,
        dataType: Int = DataTypes.FLOAT_LE,
        bits: Int = 32,
        invalidationBytes: Int = 0,
        records: ByteArray = ByteArray(0),
        extra: (ChannelSpec) -> ChannelSpec = { it },
        tamper: ((List<TestBlock>) -> Unit)? = null,
    ): ByteArray {
        val channel = extra(
            ChannelSpec(
                "arr",
                dataType,
                bits,
                byteOffset = 8,
                array = array,
                invalidationBit = if (invalidationBytes >
                    0
                ) {
                    0
                } else {
                    null
                },
            ),
        )
        val group = GroupSpec(0, "g", listOf(time, channel), recordBytes, invalidationBytes = invalidationBytes, cycleCount = 1)
        return writeFile(FileSpec(listOf(DataGroupSpec(0, listOf(group), records)), tamper = tamper))
    }

    private fun arrayOf(file: MdfFile): MdfChannel = file.channelGroups.single().channels.single { it.name == "arr" }

    private fun scanElements(file: MdfFile): Map<String, List<Double>> {
        val array = arrayOf(file)
        val requests = array.members.map { MdfChannelRequest(it) }
        val sink = CollectingSink()
        runSuspending { MdfScanner.create(file, file.groups.single(), requests, sink).advance() }
        return requests.associate { it.channel.name to sink.of(it).map { sample -> sample.value } }
    }

    @Test
    fun makesAChannelOfEachElementOfAOneDimensionalArray() {
        val records = ByteWriter().f64(1.0).f32(10f).f32(20f).f32(30f).f32(40f).toByteArray()
        val file = open(fileOf(ArraySpec(listOf(4), 4), recordBytes = 24, records = records))

        val array = arrayOf(file)

        assertEquals(listOf("arr[0]", "arr[1]", "arr[2]", "arr[3]"), array.members.map { it.name })
        assertEquals(listOf(8, 12, 16, 20), array.members.map { it.byteOffset })
        assertEquals(listOf(4L), array.array?.dimensions)
        assertEquals(MdfArrayType.Array, array.array?.type)
        assertNull(array.array?.unreadReason)
        assertFalse(array.isNumeric)
        assertEquals(listOf("t", "arr", "arr[0]", "arr[1]", "arr[2]", "arr[3]"), file.channelGroups.single().allChannels.map { it.name })
        assertEquals(
            mapOf("arr[0]" to listOf(10.0), "arr[1]" to listOf(20.0), "arr[2]" to listOf(30.0), "arr[3]" to listOf(40.0)),
            scanElements(file),
        )
    }

    @Test
    fun makesAChannelOfEachElementOfATwoDimensionalArrayTheLastIndexRunningFastest() {
        val out = ByteWriter().f64(0.0)
        for (i in 0 until 6) out.f32(i * 100f)
        val file = open(fileOf(ArraySpec(listOf(2, 3), 4), recordBytes = 32, records = out.toByteArray()))

        val array = arrayOf(file)

        assertEquals(
            listOf("arr[0][0]", "arr[0][1]", "arr[0][2]", "arr[1][0]", "arr[1][1]", "arr[1][2]"),
            array.members.map { it.name },
        )
        assertEquals(28, array.members.single { it.name == "arr[1][2]" }.byteOffset)
        assertEquals(listOf(100.0), scanElements(file).getValue("arr[0][1]"))
        assertEquals(listOf(500.0), scanElements(file).getValue("arr[1][2]"))
    }

    @Test
    fun spacesTheElementsByTheArraysByteOffsetBaseAndKeepsTheChannelsTypeAndConversion() {
        // Elements of 16 bits, 4 bytes apart; the channel's linear conversion is the elements'.
        val out = ByteWriter().f64(0.0)
        out.u16(1).u16(0).u16(2).u16(0).u16(3).u16(0)
        val converted = { channel: ChannelSpec ->
            ChannelSpec(
                channel.name,
                channel.dataType,
                channel.bitCount,
                channel.byteOffset,
                array = channel.array,
                conversion = ConversionSpec.linear(1.0, 10.0),
                unit = "V",
            )
        }
        val file =
            open(
                fileOf(
                    ArraySpec(listOf(3), 4),
                    recordBytes = 20,
                    dataType = DataTypes.UNSIGNED_LE,
                    bits = 16,
                    records = out.toByteArray(),
                    extra = converted,
                ),
            )

        val array = arrayOf(file)

        assertEquals(listOf(8, 12, 16), array.members.map { it.byteOffset })
        assertTrue(array.members.all { it.dataType == MdfDataType.UnsignedIntegerLittleEndian && it.bitCount == 16 && it.unit == "V" })
        assertEquals(mapOf("arr[0]" to listOf(11.0), "arr[1]" to listOf(21.0), "arr[2]" to listOf(31.0)), scanElements(file))
    }

    @Test
    fun giveseachElementItsOwnInvalidationBitWhereTheArraySaysSo() {
        val records = ByteWriter().f64(0.0).f32(1f).f32(2f).f32(3f).u8(0b010).toByteArray()
        val file =
            open(fileOf(ArraySpec(listOf(3), 4, invalidationBitBase = 1), recordBytes = 20, invalidationBytes = 1, records = records))
        val array = arrayOf(file)
        val requests = array.members.map { MdfChannelRequest(it) }
        val sink = CollectingSink()

        runSuspending { MdfScanner.create(file, file.groups.single(), requests, sink).advance() }

        assertEquals(listOf(0, 1, 2), array.members.map { it.invalidationBit })
        assertEquals(listOf(false, true, false), requests.map { sink.of(it).single().isInvalid })
    }

    @Test
    fun makesAChannelOfEachElementOfAnArrayOfInverseLayoutTheFirstIndexRunningFastest() {
        val out = ByteWriter().f64(0.0)
        for (i in 0 until 6) out.f32(i * 100f)
        val spec = ArraySpec(listOf(2, 3), 4, flags = ArraySpec.Flags.INVERSE_LAYOUT)
        val file = open(fileOf(spec, recordBytes = 32, records = out.toByteArray()))

        val array = arrayOf(file)

        assertTrue(checkNotNull(array.array).hasInverseLayout)
        assertEquals(
            listOf("arr[0][0]", "arr[1][0]", "arr[0][1]", "arr[1][1]", "arr[0][2]", "arr[1][2]"),
            array.members.map { it.name },
        )
        assertEquals(listOf(8, 12, 16, 20, 24, 28), array.members.map { it.byteOffset })
        assertEquals(listOf(100.0), scanElements(file).getValue("arr[1][0]"))
        assertEquals(listOf(200.0), scanElements(file).getValue("arr[0][1]"))
    }

    @Test
    fun givesTheElementsOfAnArrayOfInverseLayoutTheInvalidationBitsOfTheirPlaces() {
        // Six floats, and the invalidation bit of the third place set: that is the element at index (0, 1) when the first runs fastest.
        val out = ByteWriter().f64(0.0)
        for (i in 0 until 6) out.f32(i * 100f)
        val records = out.u8(0b000100).toByteArray()
        val spec = ArraySpec(listOf(2, 3), 4, invalidationBitBase = 1, flags = ArraySpec.Flags.INVERSE_LAYOUT)
        val file = open(fileOf(spec, recordBytes = 32, invalidationBytes = 1, records = records))
        val array = arrayOf(file)
        val requests = array.members.map { MdfChannelRequest(it) }
        val sink = CollectingSink()

        runSuspending { MdfScanner.create(file, file.groups.single(), requests, sink).advance() }

        val invalid = requests.filter { sink.of(it).single().isInvalid }.map { it.channel.name }
        assertEquals(listOf("arr[0][1]"), invalid)
    }

    @Test
    fun listsAnArrayWhoseElementsHaveInvalidationBitsBeyondAnyRecordAndReadsNoneOfThemAsAlwaysValid() {
        val records = ByteWriter().f64(0.0).f32(1f).f32(2f).f32(3f).u8(0).toByteArray()
        val spec = ArraySpec(listOf(3), 4, invalidationBitBase = 1L shl 31)
        val file = open(fileOf(spec, recordBytes = 20, invalidationBytes = 1, records = records))

        val array = arrayOf(file)

        assertTrue(checkNotNull(array.array?.unreadReason).contains("invalidation"), "${array.array}")
        assertTrue(array.members.isEmpty())
    }

    @Test
    fun readsNoAxesOfAnArrayItWillNotRead() {
        val count = (MAX_ARRAY_ELEMENTS + 1).toInt()
        val flags = ArraySpec.Flags.AXIS or ArraySpec.Flags.FIXED_AXIS
        val spec = ArraySpec(listOf(MAX_ARRAY_ELEMENTS + 1), 4, flags = flags, fixedAxes = listOf(List(count) { it.toDouble() }))
        val file = open(fileOf(spec, recordBytes = 20, records = ByteWriter().f64(0.0).f32(1f).f32(2f).f32(3f).toByteArray()))

        val array = checkNotNull(arrayOf(file).array)

        assertTrue(checkNotNull(array.unreadReason).contains("elements"), array.toString())
        assertTrue(array.fixedAxes.isEmpty())
    }

    @Test
    fun readsAMapWithFixedAxesAndKeepsTheAxes() {
        val flags = ArraySpec.Flags.AXIS or ArraySpec.Flags.FIXED_AXIS
        val array = ArraySpec(listOf(2, 3), 4, type = 2, flags = flags, fixedAxes = listOf(listOf(0.0, 1.0), listOf(10.0, 20.0, 30.0)))
        val out = ByteWriter().f64(0.0)
        repeat(6) { out.f32(it.toFloat()) }
        val file = open(fileOf(array, recordBytes = 32, records = out.toByteArray()))

        val channel = arrayOf(file)

        val read = checkNotNull(channel.array) { "no array" }
        assertEquals(MdfArrayType.LookUp, read.type)
        assertTrue(read.hasFixedAxes)
        assertEquals(listOf(listOf(0.0, 1.0), listOf(10.0, 20.0, 30.0)), read.fixedAxes)
        assertEquals(6, channel.members.size)
        assertEquals(listOf(5.0), scanElements(file).getValue("arr[1][2]"))
    }

    @Test
    fun listsAnArrayItDoesNotLayOutAndNeverReadsItsChannelAsAScalar() {
        val cases = mapOf(
            "dynamic size" to ArraySpec(listOf(3), 4, flags = ArraySpec.Flags.DYNAMIC_SIZE),
            "axes from channels" to ArraySpec(listOf(3), 4, flags = ArraySpec.Flags.AXIS),
            "an array of arrays" to ArraySpec(listOf(3), 4, inner = ArraySpec(listOf(2), 4)),
            "an array of structures" to ArraySpec(listOf(3), 4, structure = listOf(ChannelSpec("member", byteOffset = 0))),
            "channels of other groups" to ArraySpec(listOf(3), 4, storage = 1),
            "too many elements" to ArraySpec(listOf(MAX_ARRAY_ELEMENTS + 1), 4),
            "elements no bytes apart" to ArraySpec(listOf(3), 0),
            "no elements" to ArraySpec(listOf(0), 4),
            "no dimensions" to ArraySpec(emptyList(), 4),
        )
        for ((what, spec) in cases) {
            val records = ByteWriter().f64(0.0).f32(1f).f32(2f).f32(3f).toByteArray()
            val file = open(fileOf(spec, recordBytes = 20, records = records))
            val channel = arrayOf(file)

            assertNotNull(checkNotNull(channel.array) { what }.unreadReason, what)
            assertTrue(channel.members.isEmpty(), what)
            assertFalse(channel.isNumeric, what)
            val sink = CollectingSink()
            val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(MdfChannelRequest(channel)), sink) }
            runSuspending { scanner.advance() }

            assertTrue(sink.samples.isEmpty(), what)
            assertIs<MdfFailure.Unsupported>(scanner.rejected.single().failure, what)
        }
    }

    @Test
    fun saysAnArrayItReadsHasNoReasonForNotReadingIt() {
        val file = open(fileOf(ArraySpec(listOf(2), 4), recordBytes = 16))

        assertNull(arrayOf(file).array?.unreadReason)
        val scanner = runSuspending {
            MdfScanner.create(file, file.groups.single(), listOf(MdfChannelRequest(arrayOf(file))), CollectingSink())
        }
        assertEquals("channel arr", scanner.rejected.single().subject)
    }

    @Test
    fun saysAnArrayWhoseBlockIsTooShortOrWhoseSizesAreImpossibleIsDamaged() {
        val shortBlock = fileOf(ArraySpec(listOf(3), 4), recordBytes = 20, tamper = { blocks ->
            blocks.first { it.type == "CA" }.declaredLength = (TestBlock.HEADER_SIZE + 8).toLong()
        })
        val negativeDimension = fileOf(ArraySpec(listOf(-1L), 4), recordBytes = 20)
        val moreDimensionsThanRoom = fileOf(ArraySpec(listOf(3), 4), recordBytes = 20, tamper = { blocks ->
            ByteWriter().u16(5000).toByteArray().copyInto(blocks.first { it.type == "CA" }.data, 2)
        })
        val cutAxes = fileOf(
            ArraySpec(listOf(3), 4, flags = ArraySpec.Flags.AXIS or ArraySpec.Flags.FIXED_AXIS, fixedAxes = listOf(listOf(1.0))),
            recordBytes = 20,
        )

        for ((what, bytes) in mapOf(
            "short" to shortBlock,
            "negative" to negativeDimension,
            "dimensions" to moreDimensionsThanRoom,
            "axes" to cutAxes,
        )) {
            assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(bytes.asMdfSource()) }, what)
        }
    }

    @Test
    fun aLenientReaderListsADamagedArrayAndNeverReadsItsChannelAsAScalar() {
        val bytes = fileOf(ArraySpec(listOf(3), 4), recordBytes = 20, tamper = { blocks ->
            blocks.first { it.type == "CA" }.declaredLength = (TestBlock.HEADER_SIZE + 8).toLong()
        })

        val file = open(bytes, lenient = true)
        val channel = arrayOf(file)

        assertNotNull(channel.array?.unreadReason)
        assertFalse(channel.isNumeric)
        assertTrue(file.issues.isNotEmpty())
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(MdfChannelRequest(channel)), CollectingSink()) }
        assertIs<MdfFailure.Unsupported>(scanner.rejected.single().failure)
    }

    @Test
    fun saysAnArrayOfAHugeNumberOfElementsIsListedNotMadeIntoChannels() {
        // 2^40 by 2^40 elements: the count overflows a Long, and no channels are made.
        val file = open(fileOf(ArraySpec(listOf(1L shl 40, 1L shl 40), 4), recordBytes = 20))

        val array = arrayOf(file).array

        assertEquals(Long.MAX_VALUE, array?.elementCount)
        assertNotNull(array?.unreadReason)
        assertTrue(arrayOf(file).members.isEmpty())
    }

    @Test
    fun anArrayAtAnOffsetBeyondTheRecordIsRejectedWhenAskedFor() {
        val file = open(fileOf(ArraySpec(listOf(4), 8), recordBytes = 24))
        val element = arrayOf(file).members.last()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(MdfChannelRequest(element)), CollectingSink()) }

        assertIs<MdfFailure.Damaged>(scanner.rejected.single().failure)
    }
}
