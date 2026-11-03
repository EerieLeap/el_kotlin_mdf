package com.eerie_leap.mdf

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Public files of another logger (CSS Electronics' CANedge, MDF 4.11, left unfinished as a power loss leaves a log), fetched into
 * `build/samples` by `resources/samples/fetch_samples.py`; the test runs only when they are there. Their frame counts are
 * what asammdf finds in them, and the first frames are compared with asammdf's where the fetch script made them.
 */
class PublicSamplesTest {
    private class Known(val frames: Long, val crc: Long)

    private val known = mapOf(
        "a170.mf4" to Known(2010, 2294495711),
        "b1.mf4" to Known(5588, 3649137072),
        "c2.mf4" to Known(9600, 1428889579),
        "fcbf1.mf4" to Known(1815, 2403339614),
        "s3.mf4" to Known(72009, 4153689598),
    )

    private val folder = File("build/samples")

    private fun samples(): List<File> = known.keys.map { File(folder, it) }.filter { it.isFile }

    private fun crcOf(bytes: ByteArray): Long = CRC32().also { it.update(bytes) }.value

    // Every data group, with all its numeric channels and its CAN frames: the frames found, and whether anything went wrong.
    private fun scanEverything(file: MdfFile): Pair<List<MdfCanFrame>, Int> {
        val frames = ArrayList<MdfCanFrame>()
        var problems = 0
        for (data in file.groups) {
            val requests = data.channelGroups.flatMap { g ->
                g.allChannels.filter { it.isNumeric && it !== g.master }.map { MdfChannelRequest(it) }
            }
            val canGroups = data.channelGroups.filter { CanFrames.isCanDataFrameGroup(it) }
            val sink = CollectingSink()
            val scanner = runSuspending { MdfScanner.create(file, data, requests, sink, canGroups) }
            val progress = runSuspending { scanner.advance() }
            if (progress.problem != null || scanner.rejected.isNotEmpty() || scanner.unresolvedFrames != 0L) problems++
            frames.addAll(sink.frames)
        }
        return frames to problems
    }

    @Test
    fun readsEveryPublicSampleThatWasFetched() {
        assumeTrue("no samples in ${folder.absolutePath}: run resources/samples/fetch_samples.py", samples().isNotEmpty())
        for (sample in samples()) {
            val bytes = sample.readBytes()
            val want = known.getValue(sample.name)
            assertEquals(want.crc, crcOf(bytes), "${sample.name} is not the file the test knows")

            val file = open(bytes)
            val (frames, problems) = scanEverything(file)

            assertEquals("4.11", file.identification.versionText, sample.name)
            assertTrue(!file.identification.isFinalized, sample.name)
            assertTrue(file.issues.isEmpty(), "${sample.name}: ${file.issues}")
            assertEquals(0, problems, sample.name)
            assertEquals(want.frames, frames.size.toLong(), "${sample.name}: frames")
        }
    }

    @Test
    fun readsTheFirstFramesOfThePublicSamplesLikeAsammdf() {
        val withFrames = samples().filter { File(folder, it.name.replace(".mf4", ".frames.json")).isFile }
        assumeTrue("no frames.json files in ${folder.absolutePath}: run fetch_samples.py with asammdf installed", withFrames.isNotEmpty())
        for (sample in withFrames) {
            val json = MiniJson.parse(File(folder, sample.name.replace(".mf4", ".frames.json")).readText()).obj()
            val file = open(sample.readBytes())
            val group = file.channelGroups.first { it.name == "CAN_DataFrame" }
            val frames = ArrayList<MdfCanFrame>()
            runSuspending { CanFrames.read(file, file.groups.first { group in it.channelGroups }, group) { frames.add(it) } }

            assertEquals(json["total"].long(), frames.size.toLong(), sample.name)
            for ((index, row) in json["frames"].list().map { it.list() }.withIndex()) {
                val frame = frames[index]
                val what = "${sample.name}[$index]"
                assertEquals(row[0].double(), frame.time, 1e-9, what)
                assertEquals(row[1].int(), frame.bus, what)
                assertEquals(row[2].long(), frame.id, what)
                assertEquals(row[3].int() != 0, frame.isExtended, what)
                assertEquals(row[4].int(), frame.dataLength, what)
                assertEquals(row[5].text(), frame.data.toHexString(), what)
            }
            assertNull(file.issues.firstOrNull())
        }
    }
}
