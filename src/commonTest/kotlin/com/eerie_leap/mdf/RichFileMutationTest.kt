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
            runSuspending { MdfScanner.create(file, data, requests, CollectingSink()).advance() }
        }
    }

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
    fun aDamagedFileOfEveryKindOfBlockFailsOnlyWithMdfExceptions() {
        val good = RichFile.bytes()
        val random = Random(SEED)
        // The failures that were not MdfExceptions, one for each place they happened at.
        val failures = LinkedHashMap<String, String>()

        repeat(MUTATED_FILES) { index ->
            val notes = StringBuilder()
            val bytes = mutate(good, random, notes)
            for (lenient in listOf(false, true)) {
                runCatching { readEverything(bytes, lenient) }.exceptionOrNull()?.takeIf { it !is MdfException }?.let {
                    val trace = it.stackTraceToString()
                    val place = trace.lineSequence().drop(1).firstOrNull().orEmpty()
                    failures.getOrPut("${it::class.simpleName} $place") {
                        "file $index (lenient=$lenient,$notes): ${trace.take(STACK_CHARS)}"
                    }
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
