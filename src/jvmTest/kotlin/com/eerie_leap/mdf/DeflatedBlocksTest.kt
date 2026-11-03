package com.eerie_leap.mdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zipped data blocks compressed by the JVM's own zlib (real Huffman codes and matches, not the stored blocks of the common tests). */
class DeflatedBlocksTest {
    private val master = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val value = ChannelSpec("v", byteOffset = 8)

    private fun deflate(data: ByteArray, raw: Boolean): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, raw)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun records(count: Int, idSize: Int): ByteArray {
        val out = ByteWriter()
        for (i in 0 until count) {
            if (idSize > 0) out.u8(1)
            // A slowly rising time and a value that repeats: what compresses well, as logs do.
            out.f64(i * 0.01).f32((i % 50) * 2f)
        }
        return out.toByteArray()
    }

    private fun file(
        plain: ByteArray,
        idSize: Int,
        zipType: Int,
        raw: Boolean = false,
        zipped: Boolean = true,
    ): ByteArray {
        val recordCount = plain.size / (12 + idSize)
        val group = GroupSpec(if (idSize == 0) 0 else 1, "g", listOf(master, value), 12, cycleCount = recordCount.toLong())
        val stored = if (zipType == ZIP_TRANSPOSED_DEFLATE) transpose(plain, 12) else plain
        val dataBlock: ((BlockLayout) -> TestBlock)? =
            if (zipped) {
                { layout -> zippedBlock(layout, plain, deflate(stored, raw), zipType, if (zipType == ZIP_TRANSPOSED_DEFLATE) 12 else 0) }
            } else {
                null
            }
        return writeFile(FileSpec(listOf(DataGroupSpec(idSize, listOf(group), plain, dataBlock = dataBlock))))
    }

    private fun samples(bytes: ByteArray): List<Sample> {
        val file = open(bytes)
        val request = MdfChannelRequest(file.channelGroups.single().channels[1])
        val sink = CollectingSink()
        val progress = runSuspending { MdfScanner.create(file, file.groups.single(), listOf(request), sink).advance() }
        assertNull(progress.problem)
        assertTrue(progress.isComplete)
        return sink.of(request)
    }

    @Test
    fun readsDeflatedUnsortedData() {
        val plain = records(100_000, idSize = 1)
        val plainFile = file(plain, 1, ZIP_DEFLATE, zipped = false)
        val zippedFile = file(plain, 1, ZIP_DEFLATE)

        assertTrue(zippedFile.size < plainFile.size / 4, "compressed ${zippedFile.size} of ${plainFile.size}")
        val samples = samples(zippedFile)
        assertEquals(100_000, samples.size)
        assertEquals(samples(plainFile), samples)
    }

    @Test
    fun readsRawDeflateData() {
        val plain = records(2000, idSize = 1)

        assertEquals(samples(file(plain, 1, ZIP_DEFLATE, zipped = false)), samples(file(plain, 1, ZIP_DEFLATE, raw = true)))
    }

    @Test
    fun readsTransposedDeflatedSortedData() {
        val plain = records(50_000, idSize = 0)
        val plainFile = file(plain, 0, ZIP_DEFLATE, zipped = false)
        val zippedFile = file(plain, 0, ZIP_TRANSPOSED_DEFLATE)

        assertTrue(zippedFile.size < plainFile.size / 4, "compressed ${zippedFile.size} of ${plainFile.size}")
        assertEquals(samples(plainFile), samples(zippedFile))
    }

    @Test
    fun saysDataThatInflatesToOtherThanTheBlockClaimsIsDamaged() {
        val plain = records(100, idSize = 1)
        val group = GroupSpec(1, "g", listOf(master, value), 12)
        for (claimed in listOf(plain.size - 1, plain.size + 1)) {
            val bytes = writeFile(
                FileSpec(
                    listOf(
                        DataGroupSpec(
                            1,
                            listOf(group),
                            ByteArray(0),
                            dataBlock = { layout ->
                                zippedBlock(layout, ByteArray(claimed), deflate(plain, raw = false))
                            },
                        ),
                    ),
                ),
            )

            assertTrue(failureOf { samples(bytes) } is MdfFailure.Damaged, "claimed $claimed bytes")
        }
    }
}
