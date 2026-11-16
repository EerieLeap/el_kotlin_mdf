package com.eerie_leap.mdf.writer

private const val ALIGNMENT = 8L
private const val BLOCK_HEADER_BYTES = 24
private const val LINK_BYTES = 8
private const val BLOCK_MARK = "##"
private const val RESERVED_BYTES = 4
private const val SPACE = 0x20
private const val REPLACEMENT = 0xFFFD

/** A block to write: its type, links (to blocks that get their address when the set is laid out) and the fields after them. */
internal class OutBlock(val type: String, linkCount: Int, val data: ByteArray) {
    val links = arrayOfNulls<OutBlock>(linkCount)

    /** Where the block begins; known once its set is laid out. */
    var offset = 0L
        internal set

    // A gap (no type) is room kept in the file, of the size of its data.
    val length: Long get() = if (type.isEmpty()) data.size.toLong() else (BLOCK_HEADER_BYTES + LINK_BYTES * links.size + data.size).toLong()

    /** Where the block's fields begin. */
    val fieldsOffset: Long get() = linkOffset(links.size)

    /** Where the block's link number [index] is. */
    fun linkOffset(index: Int): Long = offset + BLOCK_HEADER_BYTES + LINK_BYTES * index
}

/** Blocks laid out one after another from [start], each on a multiple of 8, with their links filled in. */
internal class OutBlockSet(private val start: Long) {
    private val blocks = ArrayList<OutBlock>()

    fun block(type: String, linkCount: Int, data: ByteArray = ByteArray(0)): OutBlock =
        OutBlock(type, linkCount, data).also { blocks.add(it) }

    /** Room of [size] bytes (a multiple of 8) that no block is written in, for blocks written later. */
    fun gap(size: Int): OutBlock = block("", 0, ByteArray(size))

    fun text(text: String): OutBlock = block("TX", 0, LittleEndianBuffer().paddedText(text).toByteArray())

    fun xml(text: String): OutBlock = block("MD", 0, LittleEndianBuffer().paddedText(text).toByteArray())

    /** Where the blocks end, on a multiple of 8, as [layOut] would put them. */
    fun end(): Long = blocks.fold(start) { position, block -> alignedUp(position + block.length) }

    /** The bytes of the blocks, the first on [start]; each block's [OutBlock.offset] is set. */
    fun layOut(): ByteArray {
        var position = start
        for (block in blocks) {
            block.offset = position
            position = alignedUp(position + block.length)
        }
        val out = LittleEndianBuffer(maxOf((position - start).toInt(), 1))
        for (block in blocks) {
            out.zeros((block.offset - start - out.size).toInt())
            if (block.type.isEmpty()) {
                out.bytes(block.data)
                continue
            }
            out.bytes((BLOCK_MARK + block.type).encodeToByteArray()).zeros(RESERVED_BYTES)
            out.u64(block.length).u64(block.links.size.toLong())
            for (link in block.links) out.u64(link?.offset ?: 0L)
            out.bytes(block.data)
        }
        out.zeros((position - start - out.size).toInt())
        return out.toByteArray()
    }

    companion object {
        /** [position] rounded up to a multiple of 8. */
        fun alignedUp(position: Long): Long = (position + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT
    }
}

/** [text] for an XML element: the five characters XML reserves are escaped, and the control characters it forbids are replaced. */
internal fun escapeXmlText(text: String): String {
    val out = StringBuilder()
    for (c in text) {
        when {
            c == '&' -> out.append("&amp;")
            c == '<' -> out.append("&lt;")
            c == '>' -> out.append("&gt;")
            c == '"' -> out.append("&quot;")
            c == '\'' -> out.append("&apos;")
            c.code < SPACE && c != '\t' && c != '\n' && c != '\r' -> out.append(Char(REPLACEMENT))
            else -> out.append(c)
        }
    }
    return out.toString()
}
