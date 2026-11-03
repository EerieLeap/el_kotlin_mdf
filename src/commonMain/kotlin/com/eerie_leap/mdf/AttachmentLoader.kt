package com.eerie_leap.mdf

// Attachment (AT): the next attachment, the file name, the MIME type and the comment; then flags, who made it, the checksum, the
// sizes and the data.
private const val AT_LINK_NEXT = 0
private const val AT_LINK_FILE_NAME = 1
private const val AT_LINK_MIME_TYPE = 2
private const val AT_LINK_COMMENT = 3
private const val AT_FLAGS = 0
private const val AT_CREATOR = 2
private const val AT_ZIP_TYPE = 4
private const val AT_MD5 = 8
private const val AT_MD5_SIZE = 16
private const val AT_ORIGINAL_SIZE = 24
private const val AT_EMBEDDED_SIZE = 32
private const val AT_FIELDS_SIZE = 40
private const val HEX_DIGITS = "0123456789abcdef"
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0xF

private const val MAX_ATTACHMENTS = 10_000

/** Reads the attachments (AT) of a file: not their data, which [MdfFile.readAttachment] reads when asked. */
internal class AttachmentLoader(private val context: LoaderContext) {
    /** The attachments of the chain that begins at [first]. */
    suspend fun load(first: Long, holder: Long): List<MdfAttachment> = context.chain(
        first,
        holder,
        "AT",
        "the attachments",
        MAX_ATTACHMENTS,
        load = { link, from -> context.reader.blockStart(link, from, AT_FIELDS_SIZE, "AT") },
    ) { block, _ -> attachment(block) to block.link(AT_LINK_NEXT) }

    private suspend fun attachment(block: Block): MdfAttachment {
        block.requireData(AT_FIELDS_SIZE)
        val flags = block.u16(AT_FLAGS)
        val embeddedSize = block.u64(AT_EMBEDDED_SIZE)
        val dataStart = block.offset + block.dataStart + AT_FIELDS_SIZE
        val isEmbedded = (flags and MdfAttachment.FLAG_EMBEDDED) != 0
        if (isEmbedded && (embeddedSize < 0 || dataStart + embeddedSize > block.offset + block.length)) {
            damaged(block.offset, "the $embeddedSize bytes of an attachment's data run past its block")
        }
        val subject = "an attachment"
        return MdfAttachment(
            offset = block.offset,
            fileName = context.text(block.link(AT_LINK_FILE_NAME), block.offset, subject),
            mimeType = context.text(block.link(AT_LINK_MIME_TYPE), block.offset, subject),
            comment = context.comment(block.link(AT_LINK_COMMENT), block.offset, subject),
            flags = flags,
            creatorIndex = block.u16(AT_CREATOR),
            zipType = block.u8(AT_ZIP_TYPE),
            md5 = if ((flags and MdfAttachment.FLAG_MD5_VALID) != 0) hex(block.bytes, block.dataStart + AT_MD5) else null,
            originalSize = block.u64(AT_ORIGINAL_SIZE),
            embeddedSize = embeddedSize,
            dataOffset = dataStart,
        )
    }

    private fun hex(bytes: ByteArray, from: Int): String {
        val text = StringBuilder(AT_MD5_SIZE * 2)
        for (i in from until from + AT_MD5_SIZE) {
            text.append(HEX_DIGITS[bytes.u8(i) shr NIBBLE_BITS]).append(HEX_DIGITS[bytes.u8(i) and NIBBLE_MASK])
        }
        return text.toString()
    }
}
