package com.eerie_leap.mdf

/** The header block (HD): when the measurement began, and on which clock. */
@Suppress("LongParameterList") // A block's fields; the model holds each of them.
public class MdfHeader internal constructor(
    /** The start in nanoseconds since 1970-01-01 00:00 UTC (or, where [isLocalTime], on the local clock of the writer). */
    public val startNs: Long,
    /** The time zone's offset to UTC in minutes, valid where [hasTimeOffsets]. */
    public val timeZoneOffsetMinutes: Int,
    /** The daylight saving time's offset in minutes, valid where [hasTimeOffsets]. */
    public val daylightSavingOffsetMinutes: Int,
    public val timeFlags: Int,
    /** How good the clock is: 0 the local clock of a PC, 10 an external time source, 16 an external absolute synchronized one. */
    public val timeClass: Int,
    public val flags: Int,
    public val startAngleRad: Double,
    public val startDistanceM: Double,
) {
    /** The start is local time, not UTC. */
    public val isLocalTime: Boolean get() = (timeFlags and TIME_FLAG_LOCAL) != 0

    /** The time zone's and the daylight saving time's offsets are set. */
    public val hasTimeOffsets: Boolean get() = (timeFlags and TIME_FLAG_OFFSETS_VALID) != 0

    override fun toString(): String = "MdfHeader(startNs=$startNs, timeClass=$timeClass)"

    internal companion object {
        const val TIME_FLAG_LOCAL = 0x01
        const val TIME_FLAG_OFFSETS_VALID = 0x02
    }
}

/** One entry of the file history (FH): which tool made or changed the file, and when. */
public class MdfFileHistory internal constructor(
    public val timeNs: Long,
    public val timeZoneOffsetMinutes: Int,
    public val daylightSavingOffsetMinutes: Int,
    public val timeFlags: Int,
    public val comment: MdfComment,
) {
    /** The tool (`tool_id`) the entry names, or empty. */
    public val tool: String get() = comment.elements["tool_id"].orEmpty()

    /** The tool's vendor (`tool_vendor`), or empty. */
    public val vendor: String get() = comment.elements["tool_vendor"].orEmpty()

    /** The tool's version (`tool_version`), or empty. */
    public val version: String get() = comment.elements["tool_version"].orEmpty()

    /** The user (`user_name`) the entry names, or empty. */
    public val user: String get() = comment.elements["user_name"].orEmpty()

    override fun toString(): String = "MdfFileHistory(tool=$tool, vendor=$vendor, version=$version)"
}

/** Something the reader didn't take of a file, and where: the rest of the file read. */
public class MdfIssue internal constructor(
    public val failure: MdfFailure,
    /** What it is about, in English: `channel oil_temp`, `data group 2`. */
    public val subject: String,
) {
    override fun toString(): String = "$subject: ${failure.description}"
}

/**
 * An MDF 4 file read as far as its structure goes: the identification and header blocks, the file history, and each data group
 * with its channel groups, channels, conversions and sources. The data itself isn't read; a scanner reads it (see [MdfScanner]).
 *
 * A file its writer didn't finish ([MdfIdentification.isFinalized] false) has numbers in these blocks that the writer would
 * have updated: the cycle counts and the length of the last data block.
 */
@Suppress("LongParameterList") // What a file holds, and what the scanners need of it.
public class MdfFile internal constructor(
    public val identification: MdfIdentification,
    public val header: MdfHeader,
    public val history: List<MdfFileHistory>,
    /** The header's comment: for an Eerie Leap log its properties say which unit wrote it. */
    public val comment: MdfComment,
    public val groups: List<MdfDataGroup>,
    /** What the reader left out of the file (read leniently only); empty for a file read in full. */
    public val issues: List<MdfIssue>,
    internal val source: MdfSource,
    // The offset of every block read, sorted: a data block of an unfinished file reaches to the block after it, or the file's end.
    internal val blockOffsets: LongArray,
) {
    /** Every channel group of every data group. */
    public val channelGroups: List<MdfChannelGroup> get() = groups.flatMap { it.channelGroups }

    override fun toString(): String = "MdfFile(${identification.versionText}, ${groups.size} data groups)"
}
