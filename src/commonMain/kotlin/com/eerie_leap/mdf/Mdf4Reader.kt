package com.eerie_leap.mdf

// The header block (HD): its links, then its fields.
private const val HD_LINK_DATA_GROUP = 0
private const val HD_LINK_FILE_HISTORY = 1
private const val HD_LINK_COMMENT = 5
private const val HD_START_NS = 0
private const val HD_TIME_ZONE_OFFSET = 8
private const val HD_DAYLIGHT_SAVING_OFFSET = 10
private const val HD_TIME_FLAGS = 12
private const val HD_TIME_CLASS = 13
private const val HD_FLAGS = 14
private const val HD_START_ANGLE = 16
private const val HD_START_DISTANCE = 24
private const val HD_FIELDS_SIZE = 32

// A file history entry (FH).
private const val FH_LINK_NEXT = 0
private const val FH_LINK_COMMENT = 1
private const val FH_TIME_NS = 0
private const val FH_TIME_ZONE_OFFSET = 8
private const val FH_DAYLIGHT_SAVING_OFFSET = 10
private const val FH_TIME_FLAGS = 12
private const val FH_FIELDS_SIZE = 13

/** Reads an MDF 4 file's structure: the identification, header and file history, and the data groups with what is in them. */
public object Mdf4Reader {
    /**
     * Opens the file [source] holds. It reads the blocks that say what the file contains and none of the data, following links by
     * their offsets and checking each block against the file's size and its own length, and its type against what its link names.
     *
     * Fails with an [MdfException] for a file that is no MDF file, one of a version other than 4, one cut short, and one that is
     * damaged: a link outside the file, a block of another type than its link names, a chain of blocks that loops. With
     * [lenient], a block of the file's structure (a comment, a conversion, a source, a data group's rest) that can't be read is left
     * out and noted in [MdfFile.issues] instead, and the rest of the file reads; the identification and header blocks must read.
     */
    public suspend fun open(source: MdfSource, lenient: Boolean = false): MdfFile {
        val reader = BlockReader(source)
        val identification = MdfIdentification.read(reader.bytes(0, MdfIdentification.SIZE))
        val context = LoaderContext(reader, lenient)
        val hd = headerBlock(reader)
        val header = header(hd)
        val history = context.chain(hd.link(HD_LINK_FILE_HISTORY), hd.offset, "FH", "the file history") { block, _ ->
            historyEntry(block, context)
        }
        val groups = StructureLoader(context, ConversionLoader(context)).dataGroups(hd.link(HD_LINK_DATA_GROUP), hd.offset)
        return MdfFile(
            identification = identification,
            header = header,
            history = history,
            comment = context.comment(hd.link(HD_LINK_COMMENT), hd.offset, "the header"),
            groups = groups,
            issues = context.issues,
            source = source,
            blockOffsets = reader.readBlockOffsets(),
        )
    }

    private suspend fun headerBlock(reader: BlockReader): Block {
        if (reader.size < BlockFormat.HEADER_BLOCK_OFFSET + HD_MINIMUM_SIZE) {
            throw MdfException(MdfFailure.Truncated(BlockFormat.HEADER_BLOCK_OFFSET, BlockFormat.HEADER_BLOCK_OFFSET + HD_MINIMUM_SIZE))
        }
        return reader.block(BlockFormat.HEADER_BLOCK_OFFSET, 0, "HD").also { it.requireData(HD_FIELDS_SIZE) }
    }

    private fun header(block: Block): MdfHeader = MdfHeader(
        startNs = block.u64(HD_START_NS),
        timeZoneOffsetMinutes = block.i16(HD_TIME_ZONE_OFFSET),
        daylightSavingOffsetMinutes = block.i16(HD_DAYLIGHT_SAVING_OFFSET),
        timeFlags = block.u8(HD_TIME_FLAGS),
        timeClass = block.u8(HD_TIME_CLASS),
        flags = block.u8(HD_FLAGS),
        startAngleRad = block.f64(HD_START_ANGLE),
        startDistanceM = block.f64(HD_START_DISTANCE),
    )

    private suspend fun historyEntry(block: Block, context: LoaderContext): Pair<MdfFileHistory, Long> {
        block.requireData(FH_FIELDS_SIZE)
        val entry = MdfFileHistory(
            timeNs = block.u64(FH_TIME_NS),
            timeZoneOffsetMinutes = block.i16(FH_TIME_ZONE_OFFSET),
            daylightSavingOffsetMinutes = block.i16(FH_DAYLIGHT_SAVING_OFFSET),
            timeFlags = block.u8(FH_TIME_FLAGS),
            comment = context.comment(block.link(FH_LINK_COMMENT), block.offset, "the file history"),
        )
        return entry to block.link(FH_LINK_NEXT)
    }

    // The header block with its links and fields: 24 + 6 * 8 + 32 bytes.
    private const val HD_MINIMUM_SIZE = BlockFormat.HEADER_SIZE + 6 * BlockFormat.LINK_SIZE + HD_FIELDS_SIZE
}
