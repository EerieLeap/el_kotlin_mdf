package com.eerie_leap.mdf

/**
 * Where the reader gets a file's bytes from, so that a file of gigabytes is never held in memory. It is used by one coroutine
 * at a time. A caller implements it over what it has: a file on a disk, a document, a copy that is still being downloaded.
 */
public interface MdfSource {
    /** The number of bytes there are now. For a file still arriving it may grow between calls, and never shrinks. */
    public val size: Long

    /**
     * Reads up to [length] bytes at [position] into [into] from index [offset] and returns how many it read: fewer than
     * [length] only at the end of the file, and 0 at or past it.
     */
    public suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
}

/** A source over [this] file held in memory, for small files and for tests. */
public fun ByteArray.asMdfSource(): MdfSource = ByteArrayMdfSource(this)

private class ByteArrayMdfSource(private val bytes: ByteArray) : MdfSource {
    override val size: Long get() = bytes.size.toLong()

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= bytes.size) return 0
        val count = minOf(length.toLong(), bytes.size - position).toInt()
        bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
        return count
    }
}
