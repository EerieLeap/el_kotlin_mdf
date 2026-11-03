package com.eerie_leap.mdf

/** Why a file couldn't be read, for a caller to word in its own language. [description] says it in English, for a log. */
public sealed interface MdfFailure {
    /** The failure in English. */
    public val description: String

    /** The file doesn't begin with an MDF identification block. */
    public data object NotMdf : MdfFailure {
        override val description: String = "The file is not an MDF file."
    }

    /** The file is MDF of a version this reader doesn't take, as its identification block writes it: "3.30" for MDF 3. */
    public data class UnsupportedVersion(public val version: String) : MdfFailure {
        override val description: String get() = "MDF version $version isn't supported; only MDF 4 files are read."
    }

    /**
     * A block at byte [offset] runs to byte [end], past the end of the file: the file was cut short. A link to a block that begins
     * past the end of the file is the same (the block it names is what the file lacks), as long as the link could be a block's
     * place.
     */
    public data class Truncated(public val offset: Long, public val end: Long) : MdfFailure {
        override val description: String get() = "The file ends before the block at byte $offset does (it runs to byte $end)."
    }

    /**
     * The file is damaged at byte [offset]: a link that points where no block can be (into the identification block, or past the
     * end of the file to a place that is negative, misaligned or enormous; then [offset] is the block that holds the link), a
     * block of another type than its link names, a block whose length makes no sense, a chain of blocks that loops. [reason] says
     * which, in English.
     */
    public data class Damaged(public val offset: Long, public val reason: String) : MdfFailure {
        override val description: String get() = "The file is damaged at byte $offset: $reason."
    }

    /**
     * Something this reader doesn't take: [what] (a block, a data type, a way of storing the data) at byte [offset], and [reason]
     * why. It says where it is, so a caller can leave out that channel or data group and read the rest of the file.
     */
    public data class Unsupported(public val offset: Long, public val what: String, public val reason: String) : MdfFailure {
        override val description: String get() = "Not read, $what at byte $offset: $reason."
    }

    /** The block at byte [offset] holds [size] bytes, more than the [limit] this reader takes of one block. */
    public data class TooLarge(public val offset: Long, public val size: Long, public val limit: Long) : MdfFailure {
        override val description: String get() = "The block at byte $offset holds $size bytes, more than the $limit this reader takes."
    }
}

/** A file that couldn't be read: [failure] says why, and the message says it in English, for a log. */
public class MdfException(public val failure: MdfFailure) : Exception(failure.description)
