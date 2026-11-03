package com.eerie_leap.mdf

/** The blocks that hold each kind of data, plain or as what a zipped block (DZ) says it was. */
internal object DataKinds {
    /** The records of a data group (DT). */
    val RECORDS = setOf("DT")

    /** The values of the records of MDF 4.2's column storage (DV). */
    val VALUES = setOf("DV")

    /** The invalidation bytes of the records of column storage (DI). */
    val INVALIDATION = setOf("DI")

    /** The variable-length values of a channel (SD). */
    val SIGNALS = setOf("SD")

    /** Every block that holds data and can sit in a list. */
    val ALL = RECORDS + VALUES + INVALIDATION + SIGNALS
}

/** Reads the headers of the data blocks a list names, one small direct read each, and checks them. */
internal class LeafReader(private val reader: BlockReader) {
    /**
     * The data block at [link] (named by the block at [holder]): one of the [accepts] types, plain or zipped. In a list ([inList])
     * a block of another type is damage; alone it is a way of storing data this reader doesn't read.
     */
    suspend fun read(link: Long, holder: Long, accepts: Set<String>, inList: Boolean): DataLeaf {
        val (header, extra) = reader.dataHeader(link, holder, ZippedBlock.FIELDS_SIZE)
        return when {
            header.type == ZIPPED -> zipped(header, fieldsOf(header, extra), accepts)

            header.type in accepts -> DataLeaf(link, header.contentOffset, header.dataLength, null)

            inList -> damaged(link, "the list of ${accepts.joinToString("/")} data links to ##${header.type} at byte $link")

            else -> throw MdfException(
                MdfFailure.Unsupported(link, "data of block ##${header.type}", "it isn't a data block this reader knows"),
            )
        }
    }

    // The fields of a zipped block follow its links, if it has any: the bytes read with the header are them only if it has none.
    private suspend fun fieldsOf(header: BlockHeader, extra: ByteArray): ByteArray =
        if (header.linkCount == 0) extra else reader.directBytes(header.contentOffset, ZippedBlock.FIELDS_SIZE)

    private fun zipped(header: BlockHeader, fields: ByteArray, accepts: Set<String>): DataLeaf {
        if (fields.size < ZippedBlock.FIELDS_SIZE) throw MdfException(MdfFailure.Truncated(header.offset, header.end))
        val originalType = fields.decodeToString(ZippedBlock.ORIGINAL_TYPE, ZippedBlock.ORIGINAL_TYPE + 2)
        if (originalType !in accepts) {
            val wanted = accepts.joinToString("/")
            throw MdfException(
                MdfFailure.Unsupported(header.offset, "zipped data of ##$originalType", "only zipped $wanted blocks are read here"),
            )
        }
        val zipType = fields.u8(ZippedBlock.ZIP_TYPE)
        val parameter = fields.u32AsInt(ZippedBlock.PARAMETER)
        val originalLength = fields.u64(ZippedBlock.ORIGINAL_LENGTH)
        val zippedLength = fields.u64(ZippedBlock.ZIPPED_LENGTH)
        checkZipped(header, zipType, parameter, originalLength, zippedLength)
        return DataLeaf(
            header.offset,
            header.contentOffset + ZippedBlock.FIELDS_SIZE,
            originalLength,
            ZipInfo(zipType, parameter, zippedLength),
        )
    }

    private fun checkZipped(
        header: BlockHeader,
        zipType: Int,
        parameter: Int,
        originalLength: Long,
        zippedLength: Long,
    ) {
        // Each length on its own: a negative one (a 64-bit number with its top bit set) is as wrong as one above the limit.
        for (length in longArrayOf(originalLength, zippedLength)) {
            if (length !in 0..ZippedBlock.MAX_BYTES) throw MdfException(MdfFailure.TooLarge(header.offset, length, ZippedBlock.MAX_BYTES))
        }
        if (zipType != ZippedBlock.ZIP_DEFLATE && zipType != ZippedBlock.ZIP_TRANSPOSED) {
            throw MdfException(
                MdfFailure.Unsupported(header.offset, "zipped data", "zip type $zipType isn't deflate or transposed deflate"),
            )
        }
        if (zipType == ZippedBlock.ZIP_TRANSPOSED &&
            parameter <= 0
        ) {
            damaged(header.offset, "transposed zipped data with $parameter columns")
        }
        if (header.contentOffset + ZippedBlock.FIELDS_SIZE + zippedLength > header.end) {
            damaged(header.offset, "the zipped data block claims $zippedLength bytes of data but holds fewer")
        }
    }

    private companion object {
        const val ZIPPED = "DZ"
    }
}
