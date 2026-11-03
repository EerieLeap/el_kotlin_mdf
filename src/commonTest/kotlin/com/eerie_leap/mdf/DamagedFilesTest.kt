package com.eerie_leap.mdf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DamagedFilesTest {
    private val master = ChannelSpec("Timestamp", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1, unit = "s")
    private val value = ChannelSpec(
        "v",
        byteOffset = 8,
        unit = "V",
        comment = "volts",
        conversion = ConversionSpec.linear(0.0, 2.0),
        source = SourceSpec("src"),
    )
    private val third = ChannelSpec("w", byteOffset = 12)
    private val group = GroupSpec(1, "g", listOf(master, value, third), 16, comment = "grp", source = SourceSpec("acq"))
    private val records = ByteWriter().u8(1).f64(0.5).f32(1f).f32(2f).toByteArray()

    private fun spec(tamper: ((List<TestBlock>) -> Unit)? = null, recordIdSize: Int = 1): FileSpec =
        FileSpec(listOf(DataGroupSpec(recordIdSize, listOf(group), records)), tamper = tamper)

    private fun file(tamper: ((List<TestBlock>) -> Unit)? = null): ByteArray = writeFile(spec(tamper))

    private fun List<TestBlock>.of(type: String, index: Int = 0): TestBlock = filter { it.type == type }[index]

    private fun failure(bytes: ByteArray, lenient: Boolean = false): MdfFailure = failureOf {
        Mdf4Reader.open(bytes.asMdfSource(), lenient)
    }

    // The offsets blocks get in a file, which a second file built the same way has as well.
    private fun offsetsOf(select: (List<TestBlock>) -> TestBlock): Long {
        var block: TestBlock? = null
        writeFile(spec(tamper = { block = select(it) }))
        return checkNotNull(block).offset
    }

    @Test
    fun refusesMdf3WithItsVersion() {
        val bytes = idBlock(versionText = "3.30", version = 330) + ByteArray(200)

        assertEquals(MdfFailure.UnsupportedVersion("3.30"), failure(bytes))
    }

    @Test
    fun refusesAFileThatIsNotMdf() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(300)

        assertEquals(MdfFailure.NotMdf, failure(zip))
    }

    @Test
    fun saysAFileShorterThanItsIdentificationBlockIsCutShort() {
        assertEquals(MdfFailure.Truncated(0, 64), failure(ByteArray(10)))
        assertEquals(MdfFailure.Truncated(0, 64), failure(ByteArray(0)))
    }

    @Test
    fun saysAFileWithoutItsHeaderBlockIsCutShort() {
        val failure = failure(idBlock())

        assertIs<MdfFailure.Truncated>(failure)
        assertEquals(64, failure.offset)
    }

    @Test
    fun saysAFileCutAnywhereInItsMetadataIsCutShort() {
        val dataOffset = offsetsOf { it.of("DT") }
        val whole = file()

        for (size in 0 until dataOffset.toInt()) {
            assertIs<MdfFailure.Truncated>(failure(whole.copyOf(size)), "cut at $size")
        }
        // With all its metadata a file opens, whether or not its data block has begun.
        for (size in dataOffset.toInt()..whole.size) {
            assertEquals(1, open(whole.copyOf(size)).channelGroups.size, "cut at $size")
        }
    }

    @Test
    fun aLinkToABlockOfAnotherTypeIsDamaged() {
        val bytes = file { blocks -> blocks.of("DG").links[1] = blocks.of("TX") }

        val failure = assertIs<MdfFailure.Damaged>(failure(bytes))
        assertTrue(failure.reason.contains("##CG") && failure.reason.contains("##TX"), failure.reason)
    }

    @Test
    fun aLinkToTheMiddleOfNowhereIsDamaged() {
        val cnOffset = offsetsOf { it.of("CN", 1) }
        // Eight bytes into the channel block: no block begins there.
        val bytes = file { blocks -> blocks.of("CN", 0).rawLinks[0] = cnOffset + 8 }

        assertIs<MdfFailure.Damaged>(failure(bytes))
    }

    @Test
    fun aLinkWhereNoBlockCanBeIsDamaged() {
        for (link in listOf(8L, 63L, 1L shl 50, Long.MIN_VALUE, (1L shl 20) + 3)) {
            val bytes = file { blocks -> blocks.of("DG").rawLinks[1] = link }

            assertIs<MdfFailure.Damaged>(failure(bytes), "link to $link")
        }
    }

    @Test
    fun aLinkPastTheEndOfTheFileToWhereABlockCouldBeIsACutFile() {
        val bytes = file { blocks -> blocks.of("DG").rawLinks[1] = 1L shl 20 }

        assertEquals(MdfFailure.Truncated(1L shl 20, (1L shl 20) + 24), failure(bytes))
    }

    @Test
    fun aChainThatLoopsIsDamagedNotAHang() {
        val loops = mapOf<String, (List<TestBlock>) -> Unit>(
            "channel groups" to { blocks -> blocks.of("CG").links[0] = blocks.of("CG") },
            "channels" to { blocks -> blocks.of("CN", 1).links[0] = blocks.of("CN", 0) },
            "file history" to { blocks -> blocks.of("FH").links[0] = blocks.of("FH") },
            "data groups" to { blocks -> blocks.of("DG").links[0] = blocks.of("DG") },
        )
        for ((what, tamper) in loops) {
            val failure = assertIs<MdfFailure.Damaged>(failure(file(tamper)), what)

            assertTrue(failure.reason.contains("comes back"), "$what: ${failure.reason}")
        }
    }

    @Test
    fun aStructureThatContainsItselfIsDamagedNotAnOverflow() {
        val bytes = file { blocks -> blocks.of("CN", 1).links[1] = blocks.of("CN", 1) }

        val failure = assertIs<MdfFailure.Damaged>(failure(bytes))
        assertTrue(failure.reason.contains("nest"), failure.reason)
    }

    @Test
    fun aBlockWhoseLengthMakesNoSenseIsDamaged() {
        for (length in listOf(0L, 10L, 23L, -1L)) {
            val bytes = file { blocks -> blocks.of("CG").declaredLength = length }

            assertIs<MdfFailure.Damaged>(failure(bytes), "length $length")
        }
    }

    @Test
    fun aBlockWhoseLinkCountDoesntFitItsLengthIsDamaged() {
        val bytes = file { blocks -> blocks.of("CN", 1).declaredLinkCount = 1000 }

        assertIs<MdfFailure.Damaged>(failure(bytes))
    }

    @Test
    fun aBlockThatRunsPastTheEndOfTheFileIsCutShort() {
        val bytes = file { blocks -> blocks.of("CN", 1).declaredLength = 1L shl 20 }

        val failure = assertIs<MdfFailure.Truncated>(failure(bytes))
        assertTrue(failure.end > failure.offset)
    }

    @Test
    fun aBlockLargerThanAnyMetadataBlockIsRefused() {
        val bytes = file { blocks -> blocks.of("CN", 1).declaredLength = 1L shl 40 }

        assertIs<MdfFailure.TooLarge>(failure(bytes))
    }

    @Test
    fun aBlockWithoutTheFieldsItsTypeNeedsIsDamaged() {
        // The channel block's links without its fields: the length says so.
        val bytes = file { blocks -> blocks.of("CN", 1).declaredLength = (TestBlock.HEADER_SIZE + 8 * 8).toLong() }

        val failure = assertIs<MdfFailure.Damaged>(failure(bytes))
        assertTrue(failure.reason.contains("##CN"), failure.reason)
    }

    @Test
    fun aRecordIdOfAnImpossibleWidthIsDamaged() {
        assertIs<MdfFailure.Damaged>(failure(writeFile(spec(recordIdSize = 3))))
    }

    @Test
    fun aStrictReaderStopsAtWhatALenientOneLeavesOut() {
        val bytes = file { blocks -> blocks.of("CN", 1).links[7] = blocks.of("DG") }

        assertIs<MdfFailure.Damaged>(failure(bytes))

        val lenient = open(bytes, lenient = true)
        assertEquals(listOf("Timestamp", "v", "w"), lenient.channelGroups.single().channels.map { it.name })
        assertEquals("", lenient.channelGroups.single().channels[1].comment.text)
        val issue = lenient.issues.single()
        assertIs<MdfFailure.Damaged>(issue.failure)
        assertTrue(issue.subject.contains("channel v"), issue.subject)
    }

    @Test
    fun aLenientReaderCutsAChainAtTheDamageAndKeepsTheBlocksBeforeIt() {
        val bytes = file { blocks -> blocks.of("CN", 1).links[0] = blocks.of("TX") }

        assertIs<MdfFailure.Damaged>(failure(bytes))

        val lenient = open(bytes, lenient = true)
        assertEquals(listOf("Timestamp", "v"), lenient.channelGroups.single().channels.map { it.name })
        assertEquals(1, lenient.issues.size)
        // What it kept still scans.
        val sink = scanAll(lenient)
        assertEquals(listOf(2.0), sink.samples.values.single().map { it.value })
    }

    @Test
    fun aLenientReaderReadsADamagedConversionAsNone() {
        val bytes = file { blocks -> blocks.of("CN", 1).links[4] = blocks.of("TX") }

        assertIs<MdfFailure.Damaged>(failure(bytes))
        val lenient = open(bytes, lenient = true)

        assertEquals(null, lenient.channelGroups.single().channels[1].conversion)
        assertEquals(1, lenient.issues.size)
    }

    @Test
    fun aLenientReaderStillNeedsTheIdentificationAndHeader() {
        val badHeader = file { blocks -> blocks.of("HD").declaredLinkCount = 1000 }

        assertIs<MdfFailure.Damaged>(failure(badHeader, lenient = true))
        assertEquals(MdfFailure.NotMdf, failure(ByteArray(300), lenient = true))
    }

    @Test
    fun aLenientReaderReadsWhatIsThereOfAFileCutInItsMetadata() {
        val cutAt = offsetsOf { it.of("CN", 1) } + 20
        val lenient = open(file().copyOf(cutAt.toInt()), lenient = true)

        assertTrue(lenient.issues.isNotEmpty())
        assertTrue(lenient.issues.all { it.failure is MdfFailure.Truncated })
        assertEquals(listOf("Timestamp"), lenient.channelGroups.single().channels.map { it.name })
        assertFalse(lenient.history.isEmpty())
    }

    @Test
    fun aDamagedFileFailsOnlyWithMdfExceptionsWhateverIsDamaged() {
        val good = FirmwareLogWriter().apply {
            val a = sensor(FirmwareLogWriter.ValueSensor("a", "V", "A: volts", FirmwareLogWriter.Raw("X * 2 + 1")))
            val c = can(FirmwareLogWriter.CanSensor("c", "frames"))
            for (i in 0 until 5) {
                value(a, i * 0.1, i.toFloat(), raw = i * 10f, invalid = i % 2)
                frame(c, FirmwareLogWriter.Frame(i * 0.1, 0x100L + i, ByteArray(i + 1) { it.toByte() }))
            }
        }.build()
        val random = Random(20261103)

        repeat(4000) {
            val bytes = good.copyOf()
            repeat(random.nextInt(1, 5)) {
                // Mostly in the metadata at the front, where the links are.
                val limit = if (random.nextInt(4) == 0) bytes.size else minOf(bytes.size, 1200)
                bytes[random.nextInt(limit)] = random.nextInt(256).toByte()
            }
            for (lenient in listOf(false, true)) {
                runCatching { scanWhatOpens(bytes, lenient) }.onFailure { e ->
                    assertTrue(e is MdfException, "a mutated file failed with $e")
                }
            }
        }
    }

    private fun scanWhatOpens(bytes: ByteArray, lenient: Boolean) {
        val file = open(bytes, lenient)
        for (dataGroup in file.groups) {
            val requests = dataGroup.channelGroups.flatMap { g -> g.channels.map { MdfChannelRequest(it) } }
            val frames = dataGroup.channelGroups.filter { CanFrames.isCanDataFrameGroup(it) }
            runSuspending { MdfScanner.create(file, dataGroup, requests, CollectingSink(), frames).advance() }
        }
    }

    @Test
    fun aFileOfRandomBytesAfterAValidIdentificationFailsCleanly() {
        val random = Random(7)

        repeat(300) {
            val bytes = idBlock() + ByteArray(random.nextInt(0, 600)) { random.nextInt(256).toByte() }
            val result = runCatching { open(bytes) }

            assertTrue(result.isSuccess || result.exceptionOrNull() is MdfException, "failed with ${result.exceptionOrNull()}")
        }
    }
}
