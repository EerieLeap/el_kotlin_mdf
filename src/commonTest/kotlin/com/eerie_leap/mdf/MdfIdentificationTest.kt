package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdfIdentificationTest {
    @Test
    fun readsAFinishedLogOfAnEerieLeapUnit() {
        val identification = MdfIdentification.read(idBlock())

        assertEquals(410, identification.version)
        assertEquals("4.10", identification.versionText)
        assertEquals("EL", identification.program)
        assertTrue(identification.isFinalized)
        assertEquals(emptySet(), identification.unfinalized)
    }

    @Test
    fun readsWhatAnUnfinishedLogLeftToUpdate() {
        // rt_core's writer sets these three while a file is open (Mdf4File::WriteHeaderToStream).
        val identification = MdfIdentification.read(idBlock(identifier = "UnFinMF ", flags = 0x25))

        assertFalse(identification.isFinalized)
        assertEquals(
            setOf(UnfinalizedFlag.CycleCounts, UnfinalizedFlag.LastDataBlockLength, UnfinalizedFlag.VlsdDataBytes),
            identification.unfinalized,
        )
    }

    @Test
    fun readsTextsPaddedWithNuls() {
        val block = idBlock()
        // "MDF" followed by five NULs where rt_core writes spaces.
        ByteArray(5).copyInto(block, destinationOffset = 3)

        assertTrue(MdfIdentification.read(block).isFinalized)
    }

    @Test
    fun refusesMdf3() {
        val failure = assertFailsWith<MdfException> { MdfIdentification.read(idBlock(versionText = "3.30    ", version = 330)) }

        assertEquals(MdfFailure.UnsupportedVersion("3.30"), failure.failure)
    }

    @Test
    fun refusesAFileThatIsNoMdf() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(60)

        assertEquals(MdfFailure.NotMdf, assertFailsWith<MdfException> { MdfIdentification.read(zip) }.failure)
    }

    @Test
    fun refusesAFileShorterThanTheBlock() {
        val failure = assertFailsWith<MdfException> { MdfIdentification.read(idBlock().copyOf(10)) }

        assertEquals(MdfFailure.Truncated(offset = 0, end = 64), failure.failure)
    }

    // An identification block as rt_core's writer makes it (IdBlock::Serialize): texts padded with spaces, numbers little-endian.
    private fun idBlock(
        identifier: String = "MDF     ",
        versionText: String = "4.10    ",
        program: String = "EL      ",
        version: Int = 410,
        flags: Int = 0,
    ): ByteArray {
        val block = ByteArray(64)
        identifier.encodeToByteArray().copyInto(block, 0)
        versionText.encodeToByteArray().copyInto(block, 8)
        program.encodeToByteArray().copyInto(block, 16)
        block[28] = version.toByte()
        block[29] = (version shr 8).toByte()
        block[60] = flags.toByte()
        block[61] = (flags shr 8).toByte()
        return block
    }
}
