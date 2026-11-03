package com.eerie_leap.mdf

private const val NUL: Byte = 0

// The fields of a source information block (SI) after its three links.
private const val SOURCE_LINK_NAME = 0
private const val SOURCE_LINK_PATH = 1
private const val SOURCE_LINK_COMMENT = 2
private const val SOURCE_TYPE = 0
private const val SOURCE_BUS_TYPE = 1
private const val SOURCE_FLAGS = 2
private const val SOURCE_FIELDS_SIZE = 3

/**
 * What the loaders of one file share: the block reader, the texts already read, and the issues found. A reader that is [lenient]
 * leaves out what it can't read of a file (a comment, a source, a conversion, the rest of a chain of blocks) and says so in
 * [issues]; a strict one fails with an [MdfException] on the first block that is cut short or damaged.
 */
internal class LoaderContext(val reader: BlockReader, val lenient: Boolean) {
    val issues = ArrayList<MdfIssue>()

    /** What the file has that the reader doesn't read, whether it is lenient or not: the rest of the file reads. */
    val skipped = ArrayList<MdfIssue>()

    private class RawText(val isXml: Boolean, val text: String)

    private val rawTexts = HashMap<Long, RawText>()
    private val sources = HashMap<Long, MdfSourceInformation>()

    // The blocks of each chained type read so far: a block of a chain belongs to one chain only, so one reached twice is damage, and
    // a structure that names a block over and over can't make the reader read it over and over.
    private val chained = HashMap<String, HashSet<Long>>()

    /** Runs [load]; where it fails and the reader is lenient, notes the failure under [subject] and returns [fallback]. */
    suspend fun <T> tolerate(subject: String, fallback: T, load: suspend () -> T): T = try {
        load()
    } catch (e: MdfException) {
        if (!lenient) throw e
        issues.add(MdfIssue(e.failure, subject))
        fallback
    }

    /** The text of the TX (or MD) block at [link], empty for no link; [holder] is the block that links to it. */
    suspend fun text(link: Long, holder: Long, subject: String): String {
        if (link == 0L) return ""
        return tolerate(subject, "") {
            val raw = rawText(link, holder)
            if (raw.isXml) MdfComment.parse(raw.text).text else raw.text
        }
    }

    /** The comment at [link]: plain text from a TX block (which may hold anything, markup among it), XML read from an MD block. */
    suspend fun comment(link: Long, holder: Long, subject: String): MdfComment {
        if (link == 0L) return MdfComment.EMPTY
        return tolerate(subject, MdfComment.EMPTY) {
            val raw = rawText(link, holder)
            if (raw.isXml) MdfComment.parse(raw.text) else MdfComment.plain(raw.text)
        }
    }

    /** The source information at [link], null for no link. */
    suspend fun source(link: Long, holder: Long, subject: String): MdfSourceInformation? {
        if (link == 0L) return null
        sources[link]?.let { return it }
        return tolerate(subject, null) {
            val block = reader.block(link, holder, "SI")
            block.requireData(SOURCE_FIELDS_SIZE)
            MdfSourceInformation(
                name = text(block.link(SOURCE_LINK_NAME), link, subject),
                path = text(block.link(SOURCE_LINK_PATH), link, subject),
                comment = comment(block.link(SOURCE_LINK_COMMENT), link, subject),
                type = MdfSourceType.of(block.u8(SOURCE_TYPE)),
                busType = MdfBusType.of(block.u8(SOURCE_BUS_TYPE)),
                flags = block.u8(SOURCE_FLAGS),
            ).also { sources[link] = it }
        }
    }

    // The blocks of a chain, each linking to the next: loops and blocks of another type are damage, noted or failed by the context.
    // A chain of more than [limit] blocks is read to that many and the rest noted as skipped. [load] reads a block; the default reads
    // it whole.
    suspend fun <T> chain(
        first: Long,
        holder: Long,
        type: String,
        subject: String,
        limit: Int = Int.MAX_VALUE,
        load: (suspend (Long, Long) -> Block)? = null,
        read: suspend (Block, Int) -> Pair<T, Long>,
    ): List<T> {
        val items = ArrayList<T>()
        val visited = chained.getOrPut(type) { HashSet() }
        val loadBlock: suspend (Long, Long) -> Block = load ?: { target, source -> reader.block(target, source, type) }
        var link = first
        var from = holder
        while (link != 0L && items.size < limit) {
            val current = link
            val holderOfCurrent = from
            val next: Long? = tolerate(subject, null) {
                if (!visited.add(
                        current,
                    )
                ) {
                    damaged(current, "the chain of ##$type blocks comes back to byte $current, which one chain read already")
                }
                val (item, following) = read(loadBlock(current, holderOfCurrent), items.size)
                items.add(item)
                following
            }
            if (next == null) {
                link = 0L
            } else {
                from = current
                link = next
            }
        }
        if (link !=
            0L
        ) {
            skipped.add(MdfIssue(MdfFailure.Unsupported(link, "##$type blocks", "the chain has more than $limit of them"), subject))
        }
        return items
    }

    private suspend fun rawText(link: Long, holder: Long): RawText = rawTexts[link] ?: run {
        val block = reader.block(link, holder, "TX", "MD")
        val bytes = block.bytes
        // The text ends at the first NUL; a block with none (a writer that left it out) ends where the block does.
        val first = (block.dataStart until bytes.size).firstOrNull { bytes[it] == NUL } ?: bytes.size
        RawText(block.type == "MD", bytes.decodeToString(block.dataStart, first)).also { rawTexts[link] = it }
    }
}
