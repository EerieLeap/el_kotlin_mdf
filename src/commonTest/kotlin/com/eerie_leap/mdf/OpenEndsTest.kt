package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the last data block of an unfinished file ends when its writer didn't say (a block that begins where the block should end
 * says it, and the block is open to the end of the file otherwise, as long as the file doesn't grow a block after it), and how a
 * scan of such a file ends when no more bytes will come.
 */
class OpenEndsTest {
    private val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val words = List(10) { "word $it" + "x".repeat(it) }

    private fun textChannel(signal: (BlockLayout) -> TestBlock) =
        ChannelSpec("s", DataTypes.STRING_UTF8, 64, byteOffset = 8, type = ChannelTypes.VARIABLE_LENGTH, signalDataBlock = signal)

    // ---- where a block ends

    // A sorted group with a text in signal data blocks, as the unfinished file of a writer that writes both as it goes: the records in
    // blocks DT1 and DT2 and the texts in SD1 and SD2, after one another in the file as DT1, SD1, DT2, SD2.
    private fun interleavedFile(): ByteArray {
        val signal = SignalData.of(words.map { it.encodeToByteArray() })
        val cut = signal.offsets[5].toInt()
        val records = ByteWriter()
        signal.offsets.forEachIndexed { index, offset -> records.f64(index * 0.1).u64(offset) }
        val data = records.toByteArray()
        val signalBlocks = ArrayList<TestBlock>()
        val dataBlocks = ArrayList<TestBlock>()
        val channel = textChannel { layout ->
            val first = plainPart(layout, signal.bytes.copyOfRange(0, cut), "SD")
            val second = plainPart(layout, signal.bytes.copyOfRange(cut, signal.bytes.size), "SD")
            signalBlocks.addAll(listOf(first.block, second.block))
            dataListChain(layout, listOf(first, second))
        }
        return writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(GroupSpec(0, "g", listOf(time, channel), 16, cycleCount = 10)),
                        ByteArray(0),
                        dataBlock = { layout ->
                            val first = plainPart(layout, data.copyOfRange(0, 80))
                            val second = plainPart(layout, data.copyOfRange(80, 160))
                            dataBlocks.addAll(listOf(first.block, second.block))
                            dataListChain(layout, listOf(first, second))
                        },
                    ),
                ),
                finalized = false,
                unfinalizedFlags = COUNTS_AND_LENGTH,
                reorder = { arranged ->
                    arranged.filter { it.type != "DT" && it.type != "SD" } +
                        listOf(dataBlocks[0], signalBlocks[0], dataBlocks[1], signalBlocks[1])
                },
            ),
        )
    }

    @Test
    fun aDataBlockWhoseTrueLengthIsFollowedByAnotherBlockIsNotTakenToTheEndOfTheFile() {
        val file = open(interleavedFile())
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()

        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }
        val progress = runSuspending { scanner.advance() }

        // The ten records of 16 bytes, and not the block of signal data that follows them in the file.
        assertEquals(160L, progress.bytesAvailable)
        assertEquals(words, sink.texts[request].orEmpty())
        assertNull(progress.problem)
    }

    @Test
    fun aDataBlockThatAnotherBlockIsAppendedAfterIsClosedAtItsLengthWhenTheFileGrows() {
        val data = sortedRecords(20)
        var tail: TestBlock? = null
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(
                        0,
                        listOf(sortedGroup(0)),
                        ByteArray(0),
                        dataBlock = { layout ->
                            val parts = chunks(data, 120).map { plainPart(layout, it) }
                            // A block no list names, appended after the last: another block of the writer's, not the last one's data.
                            tail = layout.block("DT", 0, sortedRecords(5, from = 20))
                            dataListChain(layout, parts)
                        },
                    ),
                ),
                finalized = false,
                unfinalizedFlags = COUNTS_AND_LENGTH,
            ),
        )
        val source = GrowingSource(bytes, checkNotNull(tail).offset.toInt())
        val file = runSuspending { Mdf4Reader.open(source) }
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        val before = runSuspending { scanner.advance() }
        source.visible = bytes.size
        val after = runSuspending { scanner.advance() }

        assertEquals(sortedExpected(20), sink.of(request))
        assertEquals(data.size.toLong(), before.bytesAvailable)
        assertEquals(data.size.toLong(), after.bytesAvailable)
    }

    // ---- a scan of a file that will not grow

    @Test
    fun aRecordThatWaitsForAMasterThatLostItsTailIsGivenUpOnceTheSourceIsFinal() {
        val master = GroupSpec(0, "m", listOf(time), 8, cycleCount = 5)
        val first = GroupSpec(1, "a", listOf(ChannelSpec("a", byteOffset = 0)), 4, cycleCount = 5, remoteMaster = master)
        val second = GroupSpec(2, "b", listOf(ChannelSpec("b", byteOffset = 0)), 4, cycleCount = 5)
        val dependent = ByteWriter()
        for (i in 0 until 5) dependent.u8(1).f32(i * 10f).u8(2).f32(i * 100f)
        // The master's data are the last in the file; its length is a header's, and the file lost all but three of its times.
        val masters = ByteWriter()
        for (i in 0 until 3) masters.f64(100.0 + i)
        val bytes = writeFile(
            FileSpec(
                listOf(
                    DataGroupSpec(1, listOf(first, second), dependent.toByteArray()),
                    DataGroupSpec(0, listOf(master), masters.toByteArray(), dataBlockLength = TestBlock.HEADER_SIZE.toLong()),
                ),
                finalized = false,
                unfinalizedFlags = COUNTS_AND_LENGTH,
            ),
        )
        val file = open(bytes)
        val data = file.groups[0]
        val requests = data.channelGroups.flatMap { it.channels }.map { MdfChannelRequest(it) }
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, data, requests, sink) }

        runSuspending { scanner.advance() }
        val waiting = requests.map { sink.of(it).size }
        runSuspending { scanner.advance() }
        val stillWaiting = requests.map { sink.of(it).size }
        val progress = runSuspending { scanner.advance(sourceIsFinal = true) }

        assertEquals(listOf(3, 3), waiting)
        assertEquals(waiting, stillWaiting)
        assertEquals(listOf(5, 5), requests.map { sink.of(it).size })
        assertEquals(listOf(100.0, 101.0, 102.0), sink.of(requests[0]).take(3).map { it.time })
        assertTrue(sink.of(requests[0])[3].time.isNaN())
        assertEquals(List(5) { it * 100.0 }, sink.of(requests[1]).map { it.value })
        assertTrue(progress.isComplete)
    }

    @Test
    fun aTextWhoseOffsetIsPastTheOpenEndOfTheLastSignalDataBlockIsGivenUpOnceTheSourceIsFinal() {
        val signal = SignalData.of(words.map { it.encodeToByteArray() })
        val offsets = signal.offsets.toMutableList()
        offsets[5] = signal.bytes.size + 100L
        val records = ByteWriter()
        offsets.forEachIndexed { index, offset -> records.f64(index * 0.1).u64(offset) }
        // The signal data are the last block in the file, and its length is a header's.
        val channel = textChannel { layout ->
            plainPart(layout, signal.bytes, "SD").block.also {
                it.declaredLength =
                    TestBlock.HEADER_SIZE.toLong()
            }
        }
        val bytes = writeFile(
            FileSpec(
                listOf(DataGroupSpec(0, listOf(GroupSpec(0, "g", listOf(time, channel), 16, cycleCount = 10)), records.toByteArray())),
                finalized = false,
                unfinalizedFlags = COUNTS_AND_LENGTH,
                reorder = { arranged -> arranged.filter { it.type != "SD" } + arranged.filter { it.type == "SD" } },
            ),
        )
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val scanner = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink) }

        runSuspending { scanner.advance() }
        val waiting = sink.texts[request].orEmpty().size
        runSuspending { scanner.advance() }
        val progress = runSuspending { scanner.advance(sourceIsFinal = true) }

        assertEquals(5, waiting)
        assertEquals(words.filterIndexed { index, _ -> index != 5 }, sink.texts[request].orEmpty())
        assertTrue(progress.isComplete)
        assertNull(progress.problem)
    }

    private companion object {
        const val COUNTS_AND_LENGTH = 0x01 or 0x04
    }
}
