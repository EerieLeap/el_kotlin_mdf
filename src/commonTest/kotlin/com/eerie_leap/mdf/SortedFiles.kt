package com.eerie_leap.mdf

// Small files of one sorted channel group of a time and a value, for the tests of how their data are stored.

internal val sortedMaster = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
internal val sortedValue = ChannelSpec("v", byteOffset = 8)

/** Records of 12 bytes: the time `i / 2` and the value `10 i`. */
internal fun sortedRecords(count: Int, from: Int = 0): ByteArray {
    val out = ByteWriter()
    for (i in from until from + count) out.f64(i / 2.0).f32(i * 10f)
    return out.toByteArray()
}

internal fun sortedExpected(count: Int) = List(count) { Sample(it / 2.0, it * 10.0, false) }

internal fun sortedGroup(cycleCount: Long) = GroupSpec(0, "g", listOf(sortedMaster, sortedValue), 12, cycleCount = cycleCount)

/** A file of one sorted group whose data is the block [data] makes. */
internal fun sortedFile(cycleCount: Long, finalized: Boolean = true, flags: Int = 0, data: (BlockLayout) -> TestBlock): ByteArray =
    writeFile(
        FileSpec(
            listOf(DataGroupSpec(0, listOf(sortedGroup(cycleCount)), ByteArray(0), dataBlock = data)),
            finalized = finalized,
            unfinalizedFlags = flags,
        ),
    )

/** What a scan of the value channel of the first channel group of [file] gives in one call: the progress and the samples. */
internal fun scanValues(file: MdfFile): Pair<MdfScanProgress, List<Sample>> {
    val request = MdfChannelRequest(file.channelGroups.first().channels[1])
    val sink = CollectingSink()
    val scanner = runSuspending { MdfScanner.create(file, file.groups.first(), listOf(request), sink) }
    return runSuspending { scanner.advance() } to sink.of(request)
}

/** A source that serves [bytes] as far as [visible] says, and counts the reads it was asked. */
internal class CountingGrowingSource(private val bytes: ByteArray, var visible: Int) : MdfSource {
    /** The positions and lengths of the reads, in order. */
    val reads = ArrayList<Pair<Long, Int>>()

    override val size: Long get() = visible.toLong()

    override suspend fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        require(position >= 0 && length >= 0) { "a read of $length bytes at $position" }
        reads.add(position to length)
        if (position >= visible) return 0
        val count = minOf(length.toLong(), visible - position).toInt()
        bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
        return count
    }
}
