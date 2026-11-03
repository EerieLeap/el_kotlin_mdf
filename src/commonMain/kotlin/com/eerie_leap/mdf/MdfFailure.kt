package com.eerie_leap.mdf

/** Why a file couldn't be read, for a caller to word in its own language. */
public sealed interface MdfFailure {
    /** The file doesn't begin with an MDF identification block. */
    public data object NotMdf : MdfFailure

    /** The file is MDF of a version this reader doesn't take, as its identification block writes it: "3.30" for MDF 3. */
    public data class UnsupportedVersion(public val version: String) : MdfFailure

    /** A block at byte [offset] runs to byte [end], past the end of the file: the file was cut short. */
    public data class Truncated(public val offset: Long, public val end: Long) : MdfFailure
}

/** A file that couldn't be read: [failure] says why, and the message says it in English, for a log. */
public class MdfException(public val failure: MdfFailure) : Exception(failure.toString())
