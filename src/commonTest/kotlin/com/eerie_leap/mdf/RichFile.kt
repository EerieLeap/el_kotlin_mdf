package com.eerie_leap.mdf

/**
 * A file with a block of every kind the reader takes: lists of zipped blocks under a header, signal data, column storage, arrays, a
 * remote master, a hierarchy, events and attachments. [bytes] writes it; the robustness tests damage it.
 */
internal object RichFile {
    private val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val words = listOf("alpha", "", "gamma delta", "e", "ünï", "zeta".repeat(6))

    // Group 0: arrays, in zipped blocks of lists under a header; each record is the time, a value and an array of three.
    private val arrays = GroupSpec(
        0,
        "arrays",
        listOf(time, ChannelSpec("v", byteOffset = 8), ChannelSpec("arr", byteOffset = 12, array = ArraySpec(listOf(3), 4))),
        24,
        cycleCount = ARRAY_RECORDS.toLong(),
    )

    private fun arrayRecords(): ByteArray {
        val out = ByteWriter()
        for (i in 0 until ARRAY_RECORDS) out.f64(i * 0.25).f32(i.toFloat()).f32(i * 2f).f32(i * 3f).f32(i * 4f)
        return out.toByteArray()
    }

    // Group 1: text in signal data blocks, listed; a record is the time and the offset of its text.
    private fun textsDataGroup(): DataGroupSpec {
        val signal = SignalData.of(words.map { it.encodeToByteArray() })
        val channel = ChannelSpec(
            "s",
            DataTypes.STRING_UTF8,
            64,
            byteOffset = 8,
            type = ChannelTypes.VARIABLE_LENGTH,
            signalDataBlock = { layout ->
                dataListChain(layout, chunks(signal.bytes, SIGNAL_BLOCK).map { plainPart(layout, it, "SD") }, perList = 2, equal = false)
            },
        )
        val records = ByteWriter()
        signal.offsets.forEachIndexed { index, offset -> records.f64(index * 0.1).u64(offset) }
        val group = GroupSpec(0, "texts", listOf(time, channel), 16, cycleCount = words.size.toLong())
        return DataGroupSpec(0, listOf(group), records.toByteArray())
    }

    // Group 2: column storage, values and invalidation bytes in lists of their own.
    private fun columnsDataGroup(): DataGroupSpec {
        val values = ByteWriter()
        for (i in 0 until COLUMN_RECORDS) values.f64(i / 4.0).f32(i * 3f)
        val flags = ByteArray(COLUMN_RECORDS) { if (it % 5 == 0) 1 else 0 }
        val group = GroupSpec(
            0,
            "columns",
            listOf(time, ChannelSpec("c", byteOffset = 8, invalidationBit = 0)),
            12,
            invalidationBytes = 1,
            cycleCount = COLUMN_RECORDS.toLong(),
        )
        return DataGroupSpec(
            0,
            listOf(group),
            ByteArray(0),
            dataBlock = { layout ->
                val dv = chunks(values.toByteArray(), 12 * COLUMN_BLOCK).map { plainPart(layout, it, "DV") }
                // The invalidation block of the second pair is left out: its records are all valid.
                val di = chunks(flags, COLUMN_BLOCK).mapIndexed { index, part -> if (index == 1) null else plainPart(layout, part, "DI") }
                columnList(layout, dv, di, samples = COLUMN_BLOCK.toLong())
            },
        )
    }

    // Group 3: unsorted records with IDs of two bytes, the first group timed by the master of group 0.
    private fun remoteDataGroup(): DataGroupSpec {
        val remote = GroupSpec(5, "remote", listOf(ChannelSpec("r", byteOffset = 0)), 4, cycleCount = 6, remoteMaster = arrays)
        val other = GroupSpec(6, "other", listOf(ChannelSpec("o", byteOffset = 0)), 4, cycleCount = 3)
        val records = ByteWriter()
        for (i in 0 until 9) records.u16(if (i % 3 == 2) 6 else 5).f32(i * 5f)
        return DataGroupSpec(2, listOf(remote, other), records.toByteArray())
    }

    /** The same file as a writer that was cut off leaves it: unfinished, with every flag of what it left stale. */
    fun unfinished(): ByteArray = bytes(finalized = false)

    /** A file of one data group whose last list isn't filled in, with blocks after it that are the group's own and one that is not. */
    fun unfilled(): ByteArray = writeFile(
        FileSpec(
            listOf(
                DataGroupSpec(
                    0,
                    listOf(sortedGroup(0)),
                    ByteArray(0),
                    dataBlock = { layout ->
                        val list = dataList(layout, listOf(plainPart(layout, sortedRecords(5))), capacity = 4, count = 0)
                        plainPart(layout, sortedRecords(5, from = 5))
                        plainPart(layout, sortedRecords(5, from = 10))
                        zippedBlock(layout, ByteArray(UNFILLED_ZIPPED), zlibStored(ByteArray(UNFILLED_ZIPPED)), originalType = "SD")
                        list
                    },
                ),
            ),
            finalized = false,
            unfinalizedFlags = UNFILLED_FLAGS,
        ),
    )

    fun bytes(finalized: Boolean = true): ByteArray {
        val arrayData = arrayRecords()
        val arrayGroup = DataGroupSpec(
            0,
            listOf(arrays),
            ByteArray(0),
            dataBlock = { layout ->
                headerList(layout, dataListChain(layout, chunks(arrayData, 50).map { zippedPart(layout, it) }, perList = 2, equal = false))
            },
        )
        return writeFile(
            FileSpec(
                listOf(arrayGroup, textsDataGroup(), columnsDataGroup(), remoteDataGroup()),
                versionText = "4.20",
                version = 420,
                finalized = finalized,
                unfinalizedFlags = if (finalized) 0 else EVERY_FLAG,
                extras = { build ->
                    val nodes = listOf(
                        HierarchySpec(
                            "All",
                            members = listOf(arrays to arrays.channels[1]),
                            children = listOf(HierarchySpec("Arrays", type = 2, members = listOf(arrays to arrays.channels[2]))),
                        ),
                    )
                    build.header.links[2] = writeHierarchy(build, nodes)
                    val note = AttachmentSpec("note.txt", "attached ".repeat(8).encodeToByteArray(), comment = "a note")
                    val packed = AttachmentSpec("packed.bin", ByteArray(ATTACHMENT_BYTES) { it.toByte() }, isCompressed = true)
                    val (first, blocks) = writeAttachments(build.layout, listOf(note, packed))
                    build.header.links[3] = first
                    val begin = EventSpec("begin", syncBase = 3, syncFactor = 0.5, range = 1, attachments = listOf(note))
                    val end = EventSpec("end", syncBase = 9, syncFactor = 0.5, range = 2, rangeStart = begin, scopes = listOf(arrays))
                    build.header.links[4] = writeEvents(build, listOf(begin, end), blocks)
                },
            ),
        )
    }

    private const val ARRAY_RECORDS = 12
    private const val SIGNAL_BLOCK = 23
    private const val COLUMN_RECORDS = 20
    private const val COLUMN_BLOCK = 8
    private const val ATTACHMENT_BYTES = 300
    private const val UNFILLED_ZIPPED = 40
    private const val UNFILLED_FLAGS = 0x01 or 0x04 or 0x10
    private const val EVERY_FLAG = 0x7F
}
