package com.eerie_leap.mdf

/**
 * A file attached to the measurement (AT): its name and kind, and, if it is [isEmbedded], its bytes inside this file, read with
 * [MdfFile.readAttachment] (an embedded DBC file, a unit's configuration). One that isn't embedded names a file elsewhere.
 */
@Suppress("LongParameterList") // A block's fields; the model holds each of them.
public class MdfAttachment internal constructor(
    /** Where the attachment's block is in the file. */
    public val offset: Long,
    public val fileName: String,
    public val mimeType: String,
    public val comment: MdfComment,
    public val flags: Int,
    /** The index of the file history entry of the tool that attached it. */
    public val creatorIndex: Int,
    /** How the embedded data are compressed, 0 for deflate (`at_zip_type`; set from MDF 4.3). */
    public val zipType: Int,
    /** The MD5 checksum of the original file as 32 hexadecimal digits, where the attachment has one. */
    public val md5: String?,
    /** The size of the attached file. */
    public val originalSize: Long,
    /** The size of the embedded data: smaller than [originalSize] where they are compressed. */
    public val embeddedSize: Long,
    /** Where the embedded data begin in the file. */
    internal val dataOffset: Long,
) {
    public val isEmbedded: Boolean get() = (flags and FLAG_EMBEDDED) != 0

    /** The embedded data are deflated. */
    public val isCompressed: Boolean get() = (flags and FLAG_COMPRESSED) != 0

    /** The embedded data are compressed in a way beyond deflate (MDF 4.3), which this reader doesn't inflate. */
    public val isCompressedOtherwise: Boolean get() = (flags and FLAG_COMPRESSED_OTHERWISE) != 0

    override fun toString(): String =
        "MdfAttachment($fileName, $mimeType, ${if (isEmbedded) "embedded $embeddedSize bytes" else "external"})"

    internal companion object {
        const val FLAG_EMBEDDED = 0x01
        const val FLAG_COMPRESSED = 0x02
        const val FLAG_MD5_VALID = 0x04
        const val FLAG_COMPRESSED_OTHERWISE = 0x08
    }
}
