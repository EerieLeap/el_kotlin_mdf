package com.eerie_leap.mdf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A file with a block of every kind the reader takes (lists under a header, signal data, column storage, arrays, a remote master, a
 * hierarchy, events and attachments), damaged at random: a byte here and there, and numbers (sizes, counts, links) set to the values
 * that overflow. Whatever the damage, reading it ends in an [MdfException] or in what the file still holds, never in any other failure.
 */
class RichFileMutationTest {
    // Everything a reader can ask of a file: its model, its attachments and a scan of every group asking for every channel.
    private fun readEverything(bytes: ByteArray, lenient: Boolean) {
        val file = open(bytes, lenient)
        file.channelGroups.flatMap { it.allChannels }.forEach { channel ->
            channel.array?.let { "${it.unreadReason} ${it.elementCount} ${it.fixedAxes}" }
            channel.conversion?.toString()
        }
        file.events.forEach { it.toString() }
        for (attachment in file.attachments) {
            runCatching { runSuspending { file.readAttachment(attachment, MAX_ATTACHMENT_BYTES) } }
                .onFailure { if (it !is MdfException) throw it }
        }
        for (data in file.groups) {
            val requests = data.channelGroups.flatMap { group -> group.allChannels.map { MdfChannelRequest(it) } }
            val scanner = runSuspending { MdfScanner.create(file, data, requests, CollectingSink()) }
            runSuspending { scanner.advance() }
            runSuspending { scanner.advance(sourceIsFinal = true) }
        }
    }

    // The file as it arrives: a scan of each data group over the first two thirds of it, then all of it, then told it is all there is.
    private fun readGrowing(bytes: ByteArray) {
        val groups = runCatching { open(bytes, lenient = true).groups.size }.getOrDefault(0)
        for (index in 0 until groups) readGrowingGroup(bytes, index)
    }

    private fun readGrowingGroup(bytes: ByteArray, index: Int) {
        val source = GrowingSource(bytes, bytes.size * 2 / 3)
        val file = tolerated { runSuspending { Mdf4Reader.open(source, lenient = true) } }
        val data = file?.groups?.getOrNull(index) ?: return
        val requests = data.channelGroups.flatMap { group -> group.allChannels.map { MdfChannelRequest(it) } }
        val scanner = tolerated { runSuspending { MdfScanner.create(file, data, requests, CollectingSink()) } } ?: return
        runSuspending { scanner.advance() }
        source.visible = bytes.size
        runSuspending { scanner.advance() }
        runSuspending { scanner.advance(sourceIsFinal = true) }
    }

    // [block]'s result, or null where it fails as a damaged or cut file does; any other failure is the test's.
    private fun <T> tolerated(block: () -> T): T? = runCatching(block).getOrElse { if (it is MdfException) null else throw it }

    private fun mutate(good: ByteArray, random: Random, notes: StringBuilder): ByteArray {
        val bytes = good.copyOf()
        repeat(random.nextInt(1, MAX_MUTATIONS + 1)) {
            if (random.nextBoolean()) {
                val at = random.nextInt(bytes.size)
                val value = random.nextInt(BYTE_VALUES)
                bytes[at] = value.toByte()
                notes.append(" byte $at=$value")
            } else {
                // A number of a block (they are eight-byte aligned) set to one that overflows when it is added or multiplied.
                val at = random.nextInt(bytes.size / Long.SIZE_BYTES) * Long.SIZE_BYTES
                val value = EXTREMES[random.nextInt(EXTREMES.size)]
                for (b in 0 until Long.SIZE_BYTES) bytes[at + b] = (value shr (Byte.SIZE_BITS * b)).toByte()
                notes.append(" u64 $at=$value")
            }
        }
        return bytes
    }

    @Test
    fun theUndamagedFileReadsInFull() {
        val file = open(RichFile.bytes())

        assertTrue(file.issues.isEmpty(), "${file.issues}")
        assertEquals(listOf("arrays", "texts", "columns", "remote", "other"), file.channelGroups.map { it.name })
        assertEquals(1, file.hierarchy.size)
        assertEquals(2, file.attachments.size)
        assertEquals(2, file.events.size)
        readEverything(RichFile.bytes(), lenient = false)
    }

    @Test
    fun theUnfinishedAndUnfilledFilesReadToo() {
        for (bytes in listOf(RichFile.unfinished(), RichFile.unfilled())) {
            val file = open(bytes)

            assertTrue(file.issues.isEmpty(), "${file.issues}")
            readEverything(bytes, lenient = false)
        }
    }

    @Test
    fun aDamagedFileOfEveryKindOfBlockFailsOnlyWithMdfExceptions() {
        val corpus = listOf(RichFile.bytes(), RichFile.unfinished(), RichFile.unfilled())
        val random = Random(SEED)
        // The failures that were not MdfExceptions, one for each place they happened at.
        val failures = LinkedHashMap<String, String>()

        repeat(MUTATED_FILES) { index ->
            val notes = StringBuilder()
            val bytes = mutate(corpus[index % corpus.size], random, notes)
            val reads = listOf<Pair<String, () -> Unit>>(
                "strict" to { readEverything(bytes, lenient = false) },
                "lenient" to { readEverything(bytes, lenient = true) },
                "growing" to { readGrowing(bytes) },
            )
            for ((how, read) in reads) {
                runCatching(read).exceptionOrNull()?.takeIf { it !is MdfException }?.let {
                    val trace = it.stackTraceToString()
                    val place = trace.lineSequence().drop(1).firstOrNull().orEmpty()
                    failures.getOrPut("${it::class.simpleName} $place") { "file $index ($how,$notes): ${trace.take(STACK_CHARS)}" }
                }
            }
        }

        assertTrue(failures.isEmpty(), failures.values.joinToString("\n\n"))
    }

    private companion object {
        const val SEED = 20261104
        const val MUTATED_FILES = 20_000
        const val MAX_MUTATIONS = 4
        const val BYTE_VALUES = 256
        const val MAX_ATTACHMENT_BYTES = 1L shl 20
        const val STACK_CHARS = 1500
        val EXTREMES = longArrayOf(
            0L,
            1L,
            2L,
            3L,
            8L,
            24L,
            255L,
            256L,
            65_535L,
            65_536L,
            -1L,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            1L shl 31,
            1L shl 32,
            (1L shl 31) - 1,
            0xFFFFFFFFL,
            0xFFFFFFFF00000001UL.toLong(),
            0x7FFFFFFFFFFFFFF0L,
        )
    }
}
