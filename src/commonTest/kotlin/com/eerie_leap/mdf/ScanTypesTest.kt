package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanTypesTest {
    private fun record(id: Int, idSize: Int, body: ByteArray): ByteArray {
        val out = ByteWriter()
        for (i in 0 until idSize) out.u8((id.toLong() shr (8 * i)).toInt())
        return out.bytes(body).toByteArray()
    }

    private fun fileOf(
        groups: List<GroupSpec>,
        records: List<ByteArray>,
        recordIdSize: Int = 1,
        finalized: Boolean = true,
        flags: Int = 0,
    ): ByteArray {
        val data = ByteWriter()
        records.forEach { data.bytes(it) }
        val dataGroup = DataGroupSpec(recordIdSize, groups, data.toByteArray())
        return writeFile(FileSpec(listOf(dataGroup), finalized = finalized, unfinalizedFlags = flags))
    }

    private fun scan(bytes: ByteArray, requests: (MdfFile) -> List<MdfChannelRequest>): Pair<MdfFile, CollectingSink> {
        val file = open(bytes)
        return file to scanAll(file, requests(file))
    }

    private fun channel(file: MdfFile, name: String): MdfChannel = file.channelGroups.flatMap { it.channels }.first { it.name == name }

    // The name, data type and bit count of each channel, and its byte offset and bit offset.
    private val numericChannels = listOf(
        Triple("u8", DataTypes.UNSIGNED_LE, 8) to (0 to 0),
        Triple("u16le", DataTypes.UNSIGNED_LE, 16) to (1 to 0),
        Triple("u16be", DataTypes.UNSIGNED_BE, 16) to (3 to 0),
        Triple("u32le", DataTypes.UNSIGNED_LE, 32) to (5 to 0),
        Triple("u32be", DataTypes.UNSIGNED_BE, 32) to (9 to 0),
        Triple("u64le", DataTypes.UNSIGNED_LE, 64) to (13 to 0),
        Triple("u64be", DataTypes.UNSIGNED_BE, 64) to (21 to 0),
        Triple("i8", DataTypes.SIGNED_LE, 8) to (29 to 0),
        Triple("i16le", DataTypes.SIGNED_LE, 16) to (30 to 0),
        Triple("i16be", DataTypes.SIGNED_BE, 16) to (32 to 0),
        Triple("i32le", DataTypes.SIGNED_LE, 32) to (34 to 0),
        Triple("i32be", DataTypes.SIGNED_BE, 32) to (38 to 0),
        Triple("i64le", DataTypes.SIGNED_LE, 64) to (42 to 0),
        Triple("i64be", DataTypes.SIGNED_BE, 64) to (50 to 0),
        Triple("f16le", DataTypes.FLOAT_LE, 16) to (58 to 0),
        Triple("f16be", DataTypes.FLOAT_BE, 16) to (60 to 0),
        Triple("f32le", DataTypes.FLOAT_LE, 32) to (62 to 0),
        Triple("f32be", DataTypes.FLOAT_BE, 32) to (66 to 0),
        Triple("f64le", DataTypes.FLOAT_LE, 64) to (70 to 0),
        Triple("f64be", DataTypes.FLOAT_BE, 64) to (78 to 0),
        Triple("bits12", DataTypes.UNSIGNED_LE, 12) to (86 to 4),
        Triple("bits3s", DataTypes.SIGNED_LE, 3) to (88 to 2),
        Triple("flag", DataTypes.UNSIGNED_LE, 1) to (89 to 7),
        Triple("be12", DataTypes.UNSIGNED_BE, 12) to (90 to 4),
    )

    private val numericValues = mapOf(
        "u8" to 200.0, "u16le" to 51234.0, "u16be" to 4660.0, "u32le" to 3_000_000_000.0, "u32be" to 305_419_896.0,
        "u64le" to 123_456_789_012_345.0, "u64be" to 987_654_321_098_765.0,
        "i8" to -100.0, "i16le" to -30000.0, "i16be" to -12345.0, "i32le" to -2_000_000_000.0, "i32be" to -123_456_789.0,
        "i64le" to -1_234_567_890_123.0, "i64be" to -9_876_543_210_987.0,
        "f16le" to 1.5, "f16be" to -2.5, "f32le" to 3.25, "f32be" to -7.5, "f64le" to 1234.5678, "f64be" to -0.001,
        "bits12" to 0xCDA.toDouble(), "bits3s" to -3.0, "flag" to 1.0, "be12" to 0xABC.toDouble(),
    )

    private fun numericRecord(): ByteArray = RecordWriter(92)
        .u8(0, 200)
        .u16(1, 51234).u16(3, 0x1234, bigEndian = true)
        .u32(5, 3_000_000_000L).u32(9, 0x12345678L, bigEndian = true)
        .u64(13, 123_456_789_012_345L).u64(21, 987_654_321_098_765L, bigEndian = true)
        .u8(29, -100)
        .u16(30, -30000).u16(32, -12345, bigEndian = true)
        .u32(34, -2_000_000_000L).u32(38, -123_456_789L, bigEndian = true)
        .u64(42, -1_234_567_890_123L).u64(50, -9_876_543_210_987L, bigEndian = true)
        .u16(58, 0x3E00).u16(60, 0xC100, bigEndian = true)
        .f32(62, 3.25f).f32(66, -7.5f, bigEndian = true)
        .f64(70, 1234.5678).f64(78, -0.001, bigEndian = true)
        // 12 bits at bit 4 of 0xAB 0xCD: little endian 0xCDA; big endian 0xABC.
        .u8(86, 0xAB).u8(87, 0xCD)
        .u8(88, 0b101 shl 2)
        .u8(89, 0x80)
        .u8(90, 0xAB).u8(91, 0xCD)
        .bytes

    @Test
    fun decodesEveryNumericTypeAtItsOffset() {
        val channels = numericChannels.map { (type, place) ->
            ChannelSpec(type.first, type.second, type.third, byteOffset = place.first, bitOffset = place.second)
        }
        val bytes = fileOf(listOf(GroupSpec(1, "g", channels, 92)), listOf(record(1, 1, numericRecord())))

        val (file, sink) = scan(bytes) { f -> f.channelGroups.single().channels.map { MdfChannelRequest(it, stored = true) } }

        for (request in sink.samples.keys) {
            assertEquals(listOf(numericValues.getValue(request.channel.name)), sink.of(request).map { it.value }, request.channel.name)
        }
        assertEquals(numericValues.size, sink.samples.size)
        assertEquals(24, file.channelGroups.single().channels.size)
    }

    @Test
    fun convertsAnIntegerMasterToSecondsAndReadsVirtualOnes() {
        val ms = ChannelSpec(
            "t",
            DataTypes.UNSIGNED_LE,
            32,
            type = ChannelTypes.MASTER,
            syncType = 1,
            conversion = ConversionSpec.linear(0.0, 0.001),
        )
        val groupA = GroupSpec(1, "a", listOf(ms, ChannelSpec("x", byteOffset = 4)), 8)
        val virtualMaster = ChannelSpec(
            "vt",
            DataTypes.UNSIGNED_LE,
            0,
            type = ChannelTypes.VIRTUAL_MASTER,
            conversion = ConversionSpec.linear(10.0, 0.5),
        )
        val groupB = GroupSpec(2, "b", listOf(virtualMaster, ChannelSpec("y")), 4)
        val groupC = GroupSpec(3, "c", listOf(ChannelSpec("z")), 4)
        val master = ChannelSpec("m", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
        val virtualData =
            ChannelSpec("vd", DataTypes.UNSIGNED_LE, 0, type = ChannelTypes.VIRTUAL_DATA, conversion = ConversionSpec.linear(1.0, 2.0))
        val groupD = GroupSpec(4, "d", listOf(master, virtualData), 8)
        val records = listOf(
            record(1, 1, RecordWriter(8).u32(0, 1000).f32(4, 1.5f).bytes),
            record(2, 1, RecordWriter(4).f32(0, 10f).bytes),
            record(3, 1, RecordWriter(4).f32(0, 20f).bytes),
            record(4, 1, RecordWriter(8).f64(0, 0.25).bytes),
            record(1, 1, RecordWriter(8).u32(0, 2500).f32(4, 2.5f).bytes),
            record(2, 1, RecordWriter(4).f32(0, 11f).bytes),
            record(3, 1, RecordWriter(4).f32(0, 21f).bytes),
            record(4, 1, RecordWriter(8).f64(0, 0.5).bytes),
            record(2, 1, RecordWriter(4).f32(0, 12f).bytes),
        )
        val bytes = fileOf(listOf(groupA, groupB, groupC, groupD), records)

        val (file, sink) = scan(bytes) { f -> listOf("x", "y", "z", "vd").map { MdfChannelRequest(channel(f, it)) } }

        fun samples(name: String) = sink.of(sink.samples.keys.first { it.channel.name == name }).map { it.time to it.value }
        // An integer master through its conversion is seconds.
        assertEquals(listOf(1.0 to 1.5, 2.5 to 2.5), samples("x"))
        // A virtual master is the record's number through its conversion; a group with no master, the number itself.
        assertEquals(listOf(10.0 to 10.0, 10.5 to 11.0, 11.0 to 12.0), samples("y"))
        assertEquals(listOf(0.0 to 20.0, 1.0 to 21.0), samples("z"))
        // A virtual channel's value is the record's number through its conversion.
        assertEquals(listOf(0.25 to 1.0, 0.5 to 3.0), samples("vd"))
        assertTrue(file.channelGroups[1].master?.isVirtual == true)
    }

    @Test
    fun readsInvalidationBitsInAnyByteAndAllInvalidChannels() {
        val channels = listOf(
            ChannelSpec("a", byteOffset = 0, invalidationBit = 0),
            ChannelSpec("b", byteOffset = 4, invalidationBit = 9),
            ChannelSpec("c", byteOffset = 8, flags = ChannelFlags.ALL_INVALID),
            ChannelSpec("d", byteOffset = 12),
        )
        val records = listOf(
            record(1, 1, RecordWriter(18).f32(0, 1f).f32(4, 2f).f32(8, 3f).f32(12, 4f).u8(16, 0b01).u8(17, 0b10).bytes),
            record(1, 1, RecordWriter(18).f32(0, 5f).f32(4, 6f).f32(8, 7f).f32(12, 8f).u8(16, 0b10).u8(17, 0b00).bytes),
        )
        val bytes = fileOf(listOf(GroupSpec(1, "g", channels, 16, invalidationBytes = 2)), records)

        val (_, sink) = scan(bytes) { f -> f.channelGroups.single().channels.map { MdfChannelRequest(it) } }

        fun invalid(name: String) = sink.of(sink.samples.keys.first { it.channel.name == name }).map { it.isInvalid }
        assertEquals(listOf(true, false), invalid("a"))
        assertEquals(listOf(true, false), invalid("b"))
        assertEquals(listOf(true, true), invalid("c"))
        assertEquals(listOf(false, false), invalid("d"))
    }

    @Test
    fun handsOverFixedLengthTextsAndByteArrays() {
        val channels = listOf(
            ChannelSpec("latin", DataTypes.STRING_LATIN1, 64, byteOffset = 0),
            ChannelSpec("utf8", DataTypes.STRING_UTF8, 64, byteOffset = 8),
            ChannelSpec("utf16le", DataTypes.STRING_UTF16_LE, 64, byteOffset = 16),
            ChannelSpec("utf16be", DataTypes.STRING_UTF16_BE, 64, byteOffset = 24),
            ChannelSpec("bytes", DataTypes.BYTE_ARRAY, 32, byteOffset = 32),
        )
        val body = RecordWriter(36)
            .bytes(0, byteArrayOf('C'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte(), 0))
            .bytes(8, "é€".encodeToByteArray())
            .bytes(16, byteArrayOf('H'.code.toByte(), 0, 0xE9.toByte(), 0, 0, 0))
            .bytes(24, byteArrayOf(0, 'H'.code.toByte(), 0, 0xE9.toByte(), 0, 0))
            .bytes(32, byteArrayOf(1, 0xAB.toByte(), 0xFF.toByte(), 0))
            .bytes
        val bytes = fileOf(listOf(GroupSpec(1, "g", channels, 36)), listOf(record(1, 1, body)))

        val (_, sink) = scan(bytes) { f -> f.channelGroups.single().channels.map { MdfChannelRequest(it) } }

        assertEquals(
            mapOf("latin" to "Café", "utf8" to "é€", "utf16le" to "Hé", "utf16be" to "Hé", "bytes" to "01 ab ff 00"),
            sink.texts.mapKeys { it.key.channel.name }.mapValues { it.value.single() },
        )
        assertTrue(sink.samples.isEmpty())
    }

    @Test
    fun handsOverVariableLengthStringsByTheirOffsets() {
        val values = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH)
        val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
        val text = ChannelSpec(
            "s",
            DataTypes.STRING_UTF8,
            64,
            byteOffset = 8,
            type = ChannelTypes.VARIABLE_LENGTH,
            signalData = values,
        )
        val strings = GroupSpec(1, "strings", listOf(time, text), 16)

        fun payload(value: String) =
            record(2, 1, ByteWriter().u32(value.encodeToByteArray().size.toLong()).bytes(value.encodeToByteArray()).toByteArray())
        val records = listOf(
            payload("hello"),
            record(1, 1, RecordWriter(16).f64(0, 0.1).u64(8, 0).bytes),
            payload("wörld!"),
            record(1, 1, RecordWriter(16).f64(0, 0.2).u64(8, 4 + 5).bytes),
        )
        val bytes = fileOf(listOf(values, strings), records)

        val (file, sink) = scan(bytes) { f -> listOf(MdfChannelRequest(channel(f, "s"))) }

        assertEquals(listOf("hello", "wörld!"), sink.texts.values.single())
        assertSame(file.channelGroups[0], channel(file, "s").signalDataGroup)
    }

    private fun assertSame(expected: Any?, actual: Any?) = kotlin.test.assertSame(expected, actual)

    @Test
    fun readsSortedDataWithoutRecordIds() {
        val channels =
            listOf(ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1), ChannelSpec("v", byteOffset = 8))
        val records = (0 until 4).map { RecordWriter(12).f64(0, it * 0.5).f32(8, it * 10f).bytes }
        val group = GroupSpec(0, "g", channels, 12, cycleCount = 3)
        val request = { f: MdfFile -> listOf(MdfChannelRequest(channel(f, "v"))) }

        // The file counts 3 cycles: the fourth record is padding after the data.
        val (_, counted) = scan(fileOf(listOf(group), records, recordIdSize = 0)) { request(it) }
        assertEquals(listOf(0.0, 10.0, 20.0), counted.samples.values.single().map { it.value })

        // A file not finished has the count stale and the scan reads all there is.
        val unfinished = fileOf(listOf(group), records, recordIdSize = 0, finalized = false, flags = 0x01 or 0x04)
        val (_, all) = scan(unfinished) { request(it) }
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0), all.samples.values.single().map { it.value })
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5), all.samples.values.single().map { it.time })
    }

    @Test
    fun readsRecordIdsOfAnyWidth() {
        for (idSize in listOf(1, 2, 4, 8)) {
            val idB = when (idSize) {
                1 -> 200
                2 -> 40_000
                else -> 3_000_000
            }
            val groups = listOf(
                GroupSpec(5, "a", listOf(ChannelSpec("a")), 4),
                GroupSpec(idB.toLong(), "b", listOf(ChannelSpec("b")), 4),
            )
            val records = listOf(
                record(5, idSize, RecordWriter(4).f32(0, 1f).bytes),
                record(idB, idSize, RecordWriter(4).f32(0, 2f).bytes),
                record(5, idSize, RecordWriter(4).f32(0, 3f).bytes),
            )
            val (_, sink) = scan(fileOf(groups, records, recordIdSize = idSize)) { f -> valueRequests(f) }

            assertEquals(
                mapOf("a" to listOf(1.0, 3.0), "b" to listOf(2.0)),
                sink.samples.mapKeys { it.key.channel.name }.mapValues { entry -> entry.value.map { it.value } },
                "ids of $idSize bytes",
            )
        }
    }

    @Test
    fun stopsAtARecordIdNoGroupHasAndSaysWhere() {
        val groups = listOf(GroupSpec(1, "a", listOf(ChannelSpec("a")), 4))
        val records = listOf(
            record(1, 1, RecordWriter(4).f32(0, 1f).bytes),
            record(1, 1, RecordWriter(4).f32(0, 2f).bytes),
            record(9, 1, RecordWriter(4).f32(0, 3f).bytes),
            record(1, 1, RecordWriter(4).f32(0, 4f).bytes),
        )
        val file = open(fileOf(groups, records))
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), valueRequests(file), sink).advance() }

        // The two records before it are read; nothing says how long the one it names is, so the scan stops.
        assertEquals(listOf(1.0, 2.0), sink.samples.values.single().map { it.value })
        val problem = assertIs<MdfFailure.Damaged>(progress.problem)
        assertTrue(problem.reason.contains("record ID 9"), problem.reason)
        assertFalse(progress.isComplete)
    }

    @Test
    fun zerosAfterTheLastRecordArePaddingNotDamage() {
        val groups = listOf(GroupSpec(1, "a", listOf(ChannelSpec("a")), 4))
        val records = listOf(record(1, 1, RecordWriter(4).f32(0, 1f).bytes), ByteArray(5))
        val file = open(fileOf(groups, records))
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), valueRequests(file), sink).advance() }

        assertEquals(listOf(1.0), sink.samples.values.single().map { it.value })
        assertNull(progress.problem)
        assertTrue(progress.isComplete)
    }

    @Test
    fun aDataGroupWithNoChannelGroupHasNothingToRead() {
        for (idSize in listOf(0, 1)) {
            val file = open(fileOf(emptyList(), listOf(ByteArray(300) { 1 }), recordIdSize = idSize))
            val progress = runSuspending { MdfScanner.create(file, file.groups.single(), emptyList(), CollectingSink()).advance() }

            assertNull(progress.problem)
            assertEquals(0L, progress.bytesRead)
        }
    }

    @Test
    fun sortedDataOfRecordsOfNoBytesHasNothingToRead() {
        val group = GroupSpec(0, "g", emptyList(), 0)
        val file = open(fileOf(listOf(group), listOf(ByteArray(300)), recordIdSize = 0))
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), emptyList(), CollectingSink()).advance() }

        assertNull(progress.problem)
        assertEquals(0L, progress.bytesRead)
    }

    @Test
    fun anUnfinishedFilesLastDataBlockOfNoLengthReachesToTheEndWhateverItsFlagsSay() {
        val groups = listOf(GroupSpec(1, "a", listOf(ChannelSpec("a")), 4))
        val records = listOf(record(1, 1, RecordWriter(4).f32(0, 1f).bytes), record(1, 1, RecordWriter(4).f32(0, 2f).bytes))
        val data = ByteWriter().apply { records.forEach { bytes(it) } }.toByteArray()
        // The identifier says the file wasn't finished, no flag says what is stale, and the data block's length covers its header only.
        val dataGroup = DataGroupSpec(1, groups, data, dataBlockLength = TestBlock.HEADER_SIZE.toLong())
        val file = open(writeFile(FileSpec(listOf(dataGroup), finalized = false, unfinalizedFlags = 0)))

        val sink = CollectingSink()
        runSuspending { MdfScanner.create(file, file.groups.single(), valueRequests(file), sink).advance() }

        assertEquals(listOf(1.0, 2.0), sink.samples.values.single().map { it.value })
    }

    @Test
    fun skipsTheRecordsOfGroupsNobodyAskedFor() {
        val values = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH)
        val groups = listOf(
            GroupSpec(1, "a", listOf(ChannelSpec("a")), 4),
            values,
            GroupSpec(3, "b", listOf(ChannelSpec("b")), 4),
        )
        val records = listOf(
            record(1, 1, RecordWriter(4).f32(0, 1f).bytes),
            record(2, 1, ByteWriter().u32(3).bytes(byteArrayOf(7, 8, 9)).toByteArray()),
            record(3, 1, RecordWriter(4).f32(0, 5f).bytes),
            record(2, 1, ByteWriter().u32(0).toByteArray()),
            record(1, 1, RecordWriter(4).f32(0, 2f).bytes),
        )
        val file = open(fileOf(groups, records))
        val request = MdfChannelRequest(channel(file, "a"))
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
        runSuspending { scanner.advance() }

        assertEquals(listOf(1.0, 2.0), sink.of(request).map { it.value })
        assertEquals(listOf(2L, 2L, 1L), file.channelGroups.map { scanner.cycleCount(it) })
    }

    @Test
    fun listsTheRequestsItCantTakeAndScansTheRest() {
        val groups = listOf(
            GroupSpec(
                1,
                "a",
                listOf(
                    ChannelSpec("ok"),
                    ChannelSpec("mime", DataTypes.MIME_SAMPLE, 32, byteOffset = 4),
                    ChannelSpec("outside", byteOffset = 100),
                    ChannelSpec("badInvalidation", byteOffset = 0, invalidationBit = 20),
                ),
                8,
                invalidationBytes = 1,
            ),
        )
        val file = open(fileOf(groups, listOf(record(1, 1, RecordWriter(9).f32(0, 1f).bytes))))
        val other = open(fileOf(groups, emptyList()))
        val requests = listOf("ok", "mime", "outside", "badInvalidation").map { MdfChannelRequest(channel(file, it)) } +
            MdfChannelRequest(channel(other, "ok"))
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), requests, sink) }
        runSuspending { scanner.advance() }

        assertEquals(listOf(requests[0]), sink.samples.keys.toList())
        val reasons = scanner.rejected.associate { it.subject to it.failure }
        assertEquals(setOf("channel mime", "channel outside", "channel badInvalidation", "channel ok"), reasons.keys)
        assertIs<MdfFailure.Unsupported>(reasons.getValue("channel mime"))
        assertIs<MdfFailure.Damaged>(reasons.getValue("channel outside"))
        assertIs<MdfFailure.Damaged>(reasons.getValue("channel badInvalidation"))
    }

    @Test
    fun growsItsBufferForARecordLargerThanIt() {
        val big = 1_500_000
        val groups = listOf(
            GroupSpec(
                1,
                "big",
                listOf(ChannelSpec("first"), ChannelSpec("blob", DataTypes.BYTE_ARRAY, (big - 4) * 8, byteOffset = 4)),
                big,
            ),
            GroupSpec(2, "small", listOf(ChannelSpec("small")), 4),
        )
        val records = listOf(
            record(1, 1, RecordWriter(big).f32(0, 1f).bytes),
            record(2, 1, RecordWriter(4).f32(0, 2f).bytes),
            record(1, 1, RecordWriter(big).f32(0, 3f).bytes),
            record(1, 1, RecordWriter(big).f32(0, 4f).bytes),
        )
        val file = open(fileOf(groups, records))
        val first = MdfChannelRequest(channel(file, "first"))
        val small = MdfChannelRequest(channel(file, "small"))
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(first, small), sink).advance() }

        assertEquals(listOf(1.0, 3.0, 4.0), sink.of(first).map { it.value })
        assertEquals(listOf(2.0), sink.of(small).map { it.value })
        assertTrue(progress.isComplete)
        assertNull(progress.problem)
    }

    @Test
    fun growsItsBufferForAVariableLengthRecordLargerThanIt() {
        val values = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH)
        val text = ChannelSpec("s", DataTypes.BYTE_ARRAY, 64, byteOffset = 0, type = ChannelTypes.VARIABLE_LENGTH, signalData = values)
        val groups = listOf(values, GroupSpec(1, "g", listOf(text), 8))
        val payload = ByteArray(1_300_000) { (it % 251).toByte() }
        val records = listOf(
            record(2, 1, ByteWriter().u32(payload.size.toLong()).bytes(payload).toByteArray()),
            record(1, 1, RecordWriter(8).u64(0, 0).bytes),
        )
        val file = open(fileOf(groups, records))
        val request = MdfChannelRequest(channel(file, "s"))
        val sink = CollectingSink()
        runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink).advance() }

        val text0 = sink.texts.getValue(request).single()
        assertEquals(payload.size * 3 - 1, text0.length)
        assertTrue(text0.startsWith("00 01 02 03"))
    }
}
