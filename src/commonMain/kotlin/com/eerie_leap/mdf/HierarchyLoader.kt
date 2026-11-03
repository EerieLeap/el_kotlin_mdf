package com.eerie_leap.mdf

// Channel hierarchy (CH): the next sibling, the first child, the name, the comment, then a (data group, channel group, channel)
// triple of links for each member.
private const val CH_LINK_NEXT = 0
private const val CH_LINK_CHILD = 1
private const val CH_LINK_NAME = 2
private const val CH_LINK_COMMENT = 3
private const val CH_LINK_ELEMENTS = 4
private const val CH_LINKS_PER_ELEMENT = 3
private const val CH_ELEMENT_COUNT = 0
private const val CH_TYPE = 4
private const val CH_FIELDS_SIZE = 8

private const val MAX_HIERARCHY_NODES = 100_000
private const val MAX_HIERARCHY_DEPTH = 64

/** Reads the channel hierarchy (CH) a file groups its channels by. */
internal class HierarchyLoader(private val context: LoaderContext, private val index: FileIndex) {
    private var made = 0

    /** The nodes of the chain of siblings that begins at [first], each with its children. */
    suspend fun load(first: Long, holder: Long): List<MdfHierarchyNode> = siblings(first, holder, 0)

    private suspend fun siblings(first: Long, holder: Long, depth: Int): List<MdfHierarchyNode> {
        if (depth > MAX_HIERARCHY_DEPTH) damaged(holder, "the channel hierarchy nests more than $MAX_HIERARCHY_DEPTH levels deep")
        val room = MAX_HIERARCHY_NODES - made
        return context.chain(first, holder, "CH", "the channel hierarchy", room) { block, _ ->
            made++
            node(block, depth) to block.link(CH_LINK_NEXT)
        }
    }

    private suspend fun node(block: Block, depth: Int): MdfHierarchyNode {
        block.requireData(CH_FIELDS_SIZE)
        val count = block.u32AsInt(CH_ELEMENT_COUNT)
        if (count < 0 || CH_LINK_ELEMENTS + count.toLong() * CH_LINKS_PER_ELEMENT > block.linkCount) {
            damaged(block.offset, "a hierarchy node of ${block.linkCount} links says it names $count channels")
        }
        val name = context.text(block.link(CH_LINK_NAME), block.offset, "the channel hierarchy")
        val members = List(count) { element ->
            val first = CH_LINK_ELEMENTS + element * CH_LINKS_PER_ELEMENT
            val channel = index.channels[block.link(first + 2)]
            val group = index.channelGroups[block.link(first + 1)] ?: channel?.let { index.ownerOf(it.offset) }
            MdfHierarchyMember(group, channel)
        }
        return MdfHierarchyNode(
            offset = block.offset,
            name = name,
            type = MdfHierarchyType.of(block.u8(CH_TYPE)),
            comment = context.comment(block.link(CH_LINK_COMMENT), block.offset, "the channel hierarchy"),
            members = members,
            children = siblings(block.link(CH_LINK_CHILD), block.offset, depth + 1),
        )
    }
}
