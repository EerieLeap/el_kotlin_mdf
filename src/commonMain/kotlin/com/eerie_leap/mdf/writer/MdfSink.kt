package com.eerie_leap.mdf.writer

/**
 * Where a writer puts a file's bytes: a file on a disk, a document, a buffer. A writer appends the file as it goes and goes back
 * only to patch what it could not know yet (the lengths and the counts), so a recording of gigabytes is never held in memory.
 * It is used by one thread at a time.
 */
public interface MdfSink {
    /** The number of bytes appended so far: where the next [append] lands. */
    public val position: Long

    /** Appends [length] bytes of [bytes] from [offset] at [position]. */
    public fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)

    /** Replaces the bytes already appended at [position] with [bytes]; the file does not grow. */
    public fun overwrite(position: Long, bytes: ByteArray)

    /** Makes what was appended and overwritten so far reach the medium: a file read now holds it. */
    public fun flush()
}

/** A sink that keeps the file in memory, for small files and tests: [toByteArray] is the file so far. */
public class InMemoryMdfSink : MdfSink {
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /** How many times [flush] was called. */
    public var flushes: Int = 0
        private set

    override val position: Long get() = size.toLong()

    override fun append(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "$length bytes from $offset of ${bytes.size}" }
        if (size + length > this.bytes.size) this.bytes = this.bytes.copyOf(maxOf(this.bytes.size * 2, size + length))
        bytes.copyInto(this.bytes, size, offset, offset + length)
        size += length
    }

    override fun overwrite(position: Long, bytes: ByteArray) {
        require(position >= 0 && position + bytes.size <= size) { "${bytes.size} bytes at $position of $size" }
        bytes.copyInto(this.bytes, position.toInt())
    }

    override fun flush() {
        flushes++
    }

    /** A copy of the file so far. */
    public fun toByteArray(): ByteArray = bytes.copyOf(size)

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
