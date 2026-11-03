package com.eerie_leap.mdf

private const val IDENTIFIER_OFFSET = 0
private const val VERSION_TEXT_OFFSET = 8
private const val PROGRAM_OFFSET = 16
private const val VERSION_OFFSET = 28
private const val UNFINALIZED_FLAGS_OFFSET = 60
private const val CUSTOM_UNFINALIZED_FLAGS_OFFSET = 62
private const val TEXT_SIZE = 8
private const val BYTE_MASK = 0xFF

private const val FINALIZED = "MDF"
private const val UNFINALIZED = "UnFinMF"
private const val SUPPORTED_MAJOR = "4"
private val SUPPORTED_VERSIONS = 400..499

/**
 * The identification block that begins every MDF file: what makes the file an MDF file, the version of the format, and,
 * for a file its writer didn't finish (a power loss while logging), what the writer left to update.
 */
public data class MdfIdentification(
    /** The version number: 410 for MDF 4.10. */
    public val version: Int,
    /** The version as the file writes it: "4.10". */
    public val versionText: String,
    /** The program that wrote the file, in up to eight characters: "EL" for an Eerie Leap unit. */
    public val program: String,
    /** False while the identifier is `UnFinMF`: the writer didn't finish the file, and [unfinalized] says what it left. */
    public val isFinalized: Boolean,
    /** The standard's unfinalized flags the file sets; none for a finished file. */
    public val unfinalized: Set<UnfinalizedFlag>,
    /** The writer's own unfinalized flags, which only it knows; 0 for a finished file. */
    public val customUnfinalizedFlags: Int,
) {
    public companion object {
        /** The block's size: the first 64 bytes of the file. */
        public const val SIZE: Int = 64

        /**
         * Reads the block from a file's first bytes, [SIZE] of them at least. Fails with an [MdfException]: [MdfFailure.Truncated]
         * for fewer bytes, [MdfFailure.NotMdf] for bytes that are no MDF identification, [MdfFailure.UnsupportedVersion] for a
         * version other than 4 (MDF 3 among them, whose numbers may be big-endian, so its text decides).
         */
        public fun read(bytes: ByteArray): MdfIdentification {
            if (bytes.size < SIZE) fail(MdfFailure.Truncated(offset = 0, end = SIZE.toLong()))

            val identifier = bytes.text(IDENTIFIER_OFFSET)
            if (identifier != FINALIZED && identifier != UNFINALIZED) fail(MdfFailure.NotMdf)

            val versionText = bytes.text(VERSION_TEXT_OFFSET)
            val version = bytes.u16At(VERSION_OFFSET)
            if (versionText.substringBefore('.') != SUPPORTED_MAJOR || version !in SUPPORTED_VERSIONS) {
                fail(MdfFailure.UnsupportedVersion(versionText))
            }

            return MdfIdentification(
                version = version,
                versionText = versionText,
                program = bytes.text(PROGRAM_OFFSET),
                isFinalized = identifier == FINALIZED,
                unfinalized = UnfinalizedFlag.of(bytes.u16At(UNFINALIZED_FLAGS_OFFSET)),
                customUnfinalizedFlags = bytes.u16At(CUSTOM_UNFINALIZED_FLAGS_OFFSET),
            )
        }

        private fun fail(failure: MdfFailure): Nothing = throw MdfException(failure)
    }
}

/** What a writer that didn't finish a file left to update: the standard's unfinalized flags (`id_unfin_flags`). */
public enum class UnfinalizedFlag(
    /** The flag's bit in `id_unfin_flags`. */
    public val mask: Int,
) {
    /** The cycle counts of the channel groups are stale. */
    CycleCounts(0x01),

    /** The cycle counts of the sample reductions are stale. */
    SampleReductionCounts(0x02),

    /** The length of the last data block (DT) is stale: its data reach to the end of the file. */
    LastDataBlockLength(0x04),

    /** The length of the last reduction data block (RD) is stale. */
    LastReductionDataBlockLength(0x08),

    /** The last data list block (DL) of each list is stale. */
    LastDataListBlock(0x10),

    /** The byte counts of the variable-length (VLSD) channel groups are stale. */
    VlsdDataBytes(0x20),

    /** The offsets of the variable-length channels into their VLSD channel group are stale. */
    VlsdOffsets(0x40),
    ;

    internal companion object {
        /** The flags set in [bits]. */
        fun of(bits: Int): Set<UnfinalizedFlag> = entries.filterTo(mutableSetOf()) { (bits and it.mask) != 0 }
    }
}

// The text of the 8-byte field at [offset], without the spaces (or, from some writers, the NULs) that pad it.
private fun ByteArray.text(offset: Int): String = decodeToString(offset, offset + TEXT_SIZE).trimEnd { it == ' ' || it == Char(0) }

private fun ByteArray.u16At(offset: Int): Int =
    (this[offset].toInt() and BYTE_MASK) or ((this[offset + 1].toInt() and BYTE_MASK) shl Byte.SIZE_BITS)
