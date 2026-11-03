package com.eerie_leap.mdf

// Channel array (CA): its first link is the composition of its elements (nothing for plain elements); its fields follow.
private const val CA_LINK_COMPOSITION = 0
private const val CA_TYPE = 0
private const val CA_STORAGE = 1
private const val CA_DIMENSIONS = 2
private const val CA_FLAGS = 4
private const val CA_BYTE_OFFSET_BASE = 8
private const val CA_INVALIDATION_BIT_BASE = 12
private const val CA_DIMENSION_SIZES = 16

/** The most elements of one array that become channels of their own. */
internal const val MAX_ARRAY_ELEMENTS = 4096L

/** The most elements of all of a file's arrays that become channels of their own. */
internal const val MAX_ELEMENTS_IN_FILE = 200_000L

/** Reads the array (CA) a channel's composition is and, where its elements lie in the channel's record, makes them channels. */
internal class ArrayLoader(private val context: LoaderContext) {
    private var made = 0L

    /** [template], the channel that has the array at [link] as its composition, as the array: with its elements, if it has them. */
    suspend fun load(link: Long, holder: Long, template: MdfChannel, subject: String): MdfChannel =
        context.tolerate(subject, template.withArray(MdfChannelArray.unreadable("its block can't be read"), emptyList())) {
            val block = context.reader.block(link, holder, "CA")
            block.requireData(CA_DIMENSION_SIZES)
            val array = makeArray(block, dimensionsOf(block), emptyList())
            val reason = reasonNotRead(array, block.link(CA_LINK_COMPOSITION) != 0L, template)
            if (reason != null) {
                template.withArray(withReason(array, reason), emptyList())
            } else {
                // The axes are read of an array that is read, whose sizes are small, and of no other.
                val withAxes = if (array.hasFixedAxes) makeArray(block, array.dimensions, fixedAxes(block, array.dimensions)) else array
                template.withArray(withAxes, elementsOf(template, withAxes))
            }
        }

    private fun makeArray(block: Block, dimensions: List<Long>, axes: List<DoubleArray>) = MdfChannelArray(
        type = MdfArrayType.of(block.u8(CA_TYPE)),
        storage = MdfArrayStorage.of(block.u8(CA_STORAGE)),
        dimensions = dimensions,
        flags = block.u32AsInt(CA_FLAGS),
        byteOffsetBase = block.u32AsInt(CA_BYTE_OFFSET_BASE),
        invalidationBitBase = block.u32(CA_INVALIDATION_BIT_BASE),
        axes = axes,
        unreadReason = null,
    )

    private fun dimensionsOf(block: Block): List<Long> {
        val count = block.u16(CA_DIMENSIONS)
        block.requireData(CA_DIMENSION_SIZES + count * U64_SIZE)
        return List(count) {
            val size = block.u64(CA_DIMENSION_SIZES + it * U64_SIZE)
            if (size < 0) damaged(block.offset, "an array has a dimension of $size elements")
            size
        }
    }

    // The numbers of each fixed axis, after the dimension sizes; as many as the dimension has elements.
    private fun fixedAxes(block: Block, dimensions: List<Long>): List<DoubleArray> {
        var at = CA_DIMENSION_SIZES + dimensions.size * U64_SIZE
        var room = (block.dataSize - at) / U64_SIZE
        return dimensions.map { size ->
            if (size > room) damaged(block.offset, "an array's axes are cut short")
            room -= size.toInt()
            DoubleArray(size.toInt()) { block.f64(at + it * U64_SIZE) }.also { at += size.toInt() * U64_SIZE }
        }
    }

    private fun reasonNotRead(array: MdfChannelArray, hasComposition: Boolean, template: MdfChannel): String? = when {
        array.dimensions.isEmpty() -> "it has no dimensions"
        array.storage != MdfArrayStorage.ChannelTemplate -> "its elements are channels of other groups (storage type ${array.storage.code})"
        array.hasDynamicSize -> "its dimensions change with the values of other channels"
        array.hasAxes && !array.hasFixedAxes -> "its axes are values of other channels"
        hasComposition -> "its elements are arrays or structures themselves"
        else -> sizeReason(array, template)
    }

    private fun sizeReason(array: MdfChannelArray, template: MdfChannel): String? = when {
        array.elementCount == 0L -> "it has no elements"
        array.elementCount > MAX_ARRAY_ELEMENTS -> "it has ${array.elementCount} elements, more than the $MAX_ARRAY_ELEMENTS read"
        made + array.elementCount > MAX_ELEMENTS_IN_FILE -> "the file's arrays have more than $MAX_ELEMENTS_IN_FILE elements"
        array.elementCount > 1 && array.byteOffsetBase <= 0 -> "its elements are ${array.byteOffsetBase} bytes apart"
        lastElementOffset(template, array) > Int.MAX_VALUE -> "its elements lie beyond any record"
        lastInvalidationBit(template, array) > Int.MAX_VALUE -> "its elements' invalidation bits lie beyond any record"
        else -> null
    }

    private fun lastElementOffset(template: MdfChannel, array: MdfChannelArray): Long =
        template.byteOffset + array.byteOffsetBase.toLong() * (array.elementCount - 1)

    // The invalidation bit of the last element, 0 for a channel that has none.
    private fun lastInvalidationBit(template: MdfChannel, array: MdfChannelArray): Long =
        template.invalidationBit?.let { it + array.invalidationBitBase * (array.elementCount - 1) } ?: 0L

    private fun withReason(array: MdfChannelArray, reason: String): MdfChannelArray = MdfChannelArray(
        array.type,
        array.storage,
        array.dimensions,
        array.flags,
        array.byteOffsetBase,
        array.invalidationBitBase,
        emptyList(),
        reason,
    )

    // One channel for each element, named by its indices: the last index runs fastest in the record, or the first in an array of
    // inverse layout.
    private fun elementsOf(template: MdfChannel, array: MdfChannelArray): List<MdfChannel> {
        val count = array.elementCount.toInt()
        made += count
        return List(count) { linear ->
            val indices = indicesOf(linear.toLong(), array)
            val name = template.name + indices.joinToString("") { "[$it]" }
            val offset = template.byteOffset + (array.byteOffsetBase.toLong() * linear).toInt()
            val bit = template.invalidationBit?.let { it + array.invalidationBitBase * linear }
            template.element(name, offset, bit?.toInt())
        }
    }

    // The indices, one for each dimension, of the element at place [linear] among the array's.
    private fun indicesOf(linear: Long, array: MdfChannelArray): LongArray {
        val dimensions = array.dimensions
        val indices = LongArray(dimensions.size)
        var rest = linear
        for (d in if (array.hasInverseLayout) dimensions.indices else dimensions.indices.reversed()) {
            indices[d] = rest % dimensions[d]
            rest /= dimensions[d]
        }
        return indices
    }
}
