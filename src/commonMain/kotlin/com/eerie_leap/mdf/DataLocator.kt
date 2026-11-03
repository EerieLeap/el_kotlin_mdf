package com.eerie_leap.mdf

/** Finds where a data group's records, or a channel's variable-length values, are and gives them as a [DataStream]. */
internal object DataLocator {
    /**
     * The records of [group] of [file]. Fails with an [MdfException]: [MdfFailure.Unsupported] for a way of storing the data this
     * reader doesn't read, [MdfFailure.TooLarge] for a zipped block it won't inflate, [MdfFailure.Damaged] for a data link that
     * isn't a data block or a list of them.
     */
    suspend fun locate(file: MdfFile, group: MdfDataGroup): DataStream {
        if (group.dataLink == 0L) return EmptyData
        // A reader of its own: the pages another read cached, of the file as it was, may be short of what the file holds now.
        val reader = BlockReader(file.source)
        val lists = DataLists(file, reader)
        // The one place that says how a data group's data is stored: a data block (DT) or a zipped one (DZ), a list of either (DL,
        // alone or under a header, HL), or MDF 4.2's column storage (LD, with DV and DI blocks).
        return if (reader.header(group.dataLink, group.offset).type == "LD") {
            columns(file, group, reader, lists)
        } else {
            val again = suspend { freshLists(file).leaves(group.dataLink, group.offset, DataKinds.RECORDS) }
            streamOf(reader, lists.leaves(group.dataLink, group.offset, DataKinds.RECORDS), again)
        }
    }

    /** The variable-length values a channel keeps in signal data blocks (SD, a zipped one, or a list of them) at [link]. */
    suspend fun locateSignalData(file: MdfFile, link: Long, holder: Long): DataStream {
        val reader = BlockReader(file.source)
        val again = suspend { freshLists(file).leaves(link, holder, DataKinds.SIGNALS) }
        return streamOf(reader, DataLists(file, reader).leaves(link, holder, DataKinds.SIGNALS), again)
    }

    // A walk of its own, with a reader whose pages are of the file as it is now.
    private fun freshLists(file: MdfFile): DataLists = DataLists(file, BlockReader(file.source))

    private suspend fun columns(file: MdfFile, group: MdfDataGroup, reader: BlockReader, lists: DataLists): DataStream {
        val only = group.channelGroups.singleOrNull()
        if (only == null || !group.isSorted) {
            throw MdfException(
                MdfFailure.Unsupported(group.dataLink, "column storage", "it is read for a sorted data group of one channel group only"),
            )
        }
        val walk = lists.columns(group.dataLink, group.offset)
        val dataBytes = only.dataBytes.toInt()
        val invalidationBytes = only.invalidationBytes.toInt()
        if (dataBytes + invalidationBytes == 0) return EmptyData
        val again = suspend { freshLists(file).columns(group.dataLink, group.offset) }
        return ColumnData(
            streamOf(reader, walk.values) { again().values },
            walk.invalidation?.let { found ->
                streamOf(reader, found) { checkNotNull(again().invalidation) { "the invalidation blocks went" } }
            },
            dataBytes,
            invalidationBytes,
        )
    }

    private fun streamOf(reader: BlockReader, walk: Walk, again: (suspend () -> Walk)? = null): DataStream =
        if (walk.leaves.isEmpty() && !walk.isCut) EmptyData else SegmentedData(reader, walk, if (walk.isCut) again else null)
}
