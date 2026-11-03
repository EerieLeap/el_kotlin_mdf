package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenFirmwareLogTest {
    private fun log(finished: Boolean = true): ByteArray = FirmwareLogWriter().apply {
        val oil = sensor(FirmwareLogWriter.ValueSensor("oil_temp", "°C", "Oil temperature: sump", FirmwareLogWriter.Raw("X * 0.1 + 5")))
        val boost = sensor(FirmwareLogWriter.ValueSensor("boost", "bar", raw = FirmwareLogWriter.Raw(comment = "expression: x ^ 0.5")))
        val frames = can(FirmwareLogWriter.CanSensor("can_in", "Raw CAN"))
        value(oil, 0.0, 90.5f, raw = 855f)
        value(boost, 0.01, 1.2f, raw = 1.44f)
        frame(frames, FirmwareLogWriter.Frame(0.02, 0x316, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }.build(finished)

    @Test
    fun readsTheIdentificationAndTheHeader() {
        val file = open(log())

        assertEquals("4.10", file.identification.versionText)
        assertEquals("EL", file.identification.program)
        assertTrue(file.identification.isFinalized)
        assertEquals(1_700_000_000_000_000_000L, file.header.startNs)
        assertEquals(0, file.header.timeClass)
        assertFalse(file.header.isLocalTime)
        assertTrue(file.issues.isEmpty())
    }

    @Test
    fun readsTheFileHistory() {
        val history = open(log()).history.single()

        assertEquals("rt_daq", history.tool)
        assertEquals("Eerie Leap", history.vendor)
        assertEquals("1.4.2", history.version)
        assertEquals("created", history.comment.text)
    }

    @Test
    fun readsTheHeaderCommentAndItsProperties() {
        val comment = open(log()).comment

        assertEquals("Eerie Leap sensor log", comment.text)
        assertEquals("el_pm100", comment.properties["device_name"])
        assertEquals("41985", comment.properties["product_id"])
        assertEquals("3735928559", comment.properties["sensors_configuration_hash"])
        assertEquals("100", comment.properties["logging_interval_ms"])
        assertEquals(
            listOf(
                "device_name",
                "product_id",
                "device_id",
                "hw_version",
                "sw_version",
                "sensors_configuration_hash",
                "logging_interval_ms",
            ),
            comment.properties.keys.toList(),
        )
    }

    @Test
    fun readsOneUnsortedDataGroupWithAChannelGroupForEachSensor() {
        val file = open(log())

        val group = file.groups.single()
        assertEquals(1, group.recordIdSize)
        assertFalse(group.isSorted)
        assertEquals(listOf("oil_temp", "boost", "", "can_in"), group.channelGroups.map { it.name })
        assertEquals(listOf(1L, 2L, 3L, 4L), group.channelGroups.map { it.recordId })
        assertEquals(group.channelGroups, file.channelGroups)
    }

    @Test
    fun readsAValueSensorsChannels() {
        val group = open(log()).channelGroups.first()

        assertEquals(listOf("Timestamp", "oil_temp", "oil_temp.raw"), group.channels.map { it.name })
        assertEquals(16L, group.dataBytes)
        assertEquals(1L, group.invalidationBytes)
        assertEquals(17L, group.recordBytes)
        assertEquals(1L, group.cycleCount)
        assertEquals("Eerie Leap Sensor", group.source?.name)
        assertEquals(MdfSourceType.IoDevice, group.source?.type)
        assertEquals(MdfBusType.None, group.source?.busType)

        val time = group.channels[0]
        assertSame(time, group.master)
        assertEquals(MdfChannelKind.Master, time.kind)
        assertEquals(MdfSyncType.Time, time.syncType)
        assertEquals(MdfDataType.FloatLittleEndian, time.dataType)
        assertEquals(64, time.bitCount)
        assertEquals(0, time.byteOffset)
        assertEquals("s", time.unit)
        assertNull(time.invalidationBit)
        assertNull(time.conversion)

        val value = group.channels[1]
        assertEquals(MdfChannelKind.Fixed, value.kind)
        assertEquals(MdfSyncType.None, value.syncType)
        assertEquals(MdfDataType.FloatLittleEndian, value.dataType)
        assertEquals(32, value.bitCount)
        assertEquals(8, value.byteOffset)
        assertEquals(0, value.bitOffset)
        assertEquals("°C", value.unit)
        assertEquals("Oil temperature: sump", value.comment.text)
        assertEquals(0, value.invalidationBit)
        assertTrue(value.hasInvalidationBit)
        assertFalse(value.allValuesInvalid)
        assertNull(value.conversion)
        assertTrue(value.isNumeric)
    }

    @Test
    fun readsTheRawChannelWithItsAlgebraicConversion() {
        val raw = open(log()).channelGroups.first().channels[2]

        assertEquals(12, raw.byteOffset)
        assertEquals(1, raw.invalidationBit)
        val conversion = assertIs<MdfConversion.Algebraic>(raw.conversion)
        assertEquals("X * 0.1 + 5", conversion.formula)
        assertEquals(90.5, conversion.toPhysical(855.0), 1e-9)
    }

    @Test
    fun readsTheRawChannelWithOnlyAComment() {
        val raw = open(log()).channelGroups[1].channels[2]

        assertEquals("boost.raw", raw.name)
        assertNull(raw.conversion)
        assertEquals("expression: x ^ 0.5", raw.comment.text)
        assertEquals("", raw.unit)
    }

    @Test
    fun readsTheCanFrameGroupsStructure() {
        val file = open(log())
        val payloads = file.channelGroups[2]
        val frames = file.channelGroups[3]

        assertTrue(payloads.isVlsd)
        assertEquals(3L, payloads.recordId)
        assertEquals(1L, payloads.cycleCount)
        assertEquals(8L, payloads.vlsdBytes)
        assertEquals(0L, payloads.dataBytes)
        assertTrue(payloads.channels.isEmpty())

        assertTrue(frames.isBusEvent)
        assertTrue(frames.isPlainBusEvent)
        assertFalse(frames.isVlsd)
        assertEquals("Raw CAN", frames.comment.text)
        assertEquals(MdfBusType.Can, frames.source?.busType)
        assertEquals(MdfSourceType.Bus, frames.source?.type)
        assertEquals("CAN", frames.source?.name)
        assertEquals(23L, frames.dataBytes)
        assertEquals(listOf("Timestamp", "CAN_DataFrame"), frames.channels.map { it.name })

        val frame = frames.channels[1]
        assertTrue(frame.isBusEvent)
        assertEquals(MdfDataType.ByteArray, frame.dataType)
        assertEquals(
            listOf(
                "CAN_DataFrame.BusChannel",
                "CAN_DataFrame.ID",
                "CAN_DataFrame.IDE",
                "CAN_DataFrame.DLC",
                "CAN_DataFrame.Dir",
                "CAN_DataFrame.EDL",
                "CAN_DataFrame.BRS",
                "CAN_DataFrame.ESI",
                "CAN_DataFrame.DataLength",
                "CAN_DataFrame.DataBytes",
            ),
            frame.members.map { it.name },
        )
        val id = frame.members[1]
        assertEquals(9, id.byteOffset)
        assertEquals(29, id.bitCount)
        val ide = frame.members[2]
        assertEquals(12, ide.byteOffset)
        assertEquals(7, ide.bitOffset)
        assertEquals(1, ide.bitCount)
        val dataBytes = frame.members[9]
        assertEquals(MdfChannelKind.Vlsd, dataBytes.kind)
        assertEquals(15, dataBytes.byteOffset)
        assertEquals(64, dataBytes.bitCount)
        assertSame(payloads, dataBytes.signalDataGroup)
    }

    @Test
    fun readsAFileItsWriterDidntFinish() {
        val file = open(log(finished = false))

        assertFalse(file.identification.isFinalized)
        assertEquals(
            setOf(UnfinalizedFlag.CycleCounts, UnfinalizedFlag.LastDataBlockLength, UnfinalizedFlag.VlsdDataBytes),
            file.identification.unfinalized,
        )
        // The counts are stale (0): the structure reads all the same.
        assertEquals(0L, file.channelGroups.first().cycleCount)
        assertEquals(4, file.channelGroups.size)
    }

    @Test
    fun readsALogWithATwoByteRecordId() {
        val bytes = FirmwareLogWriter(recordIdSize = 2).apply {
            val sensor = sensor(FirmwareLogWriter.ValueSensor("a"))
            value(sensor, 0.0, 1f)
        }.build()

        val group = open(bytes).groups.single()

        assertEquals(2, group.recordIdSize)
        assertEquals(1L, group.channelGroups.single().recordId)
    }

    @Test
    fun readsALogWithoutSensors() {
        val file = open(FirmwareLogWriter().build())

        assertTrue(file.channelGroups.isEmpty())
        assertNotNull(file.groups.singleOrNull())
    }

    @Test
    fun theRawBytesOfTheFixtureAreWhatTheWriterIntends() {
        // A guard for the test writer: the file begins with the identification block as rt_core writes it.
        val bytes = log()

        assertContentEquals("MDF     4.10    EL      ".encodeToByteArray(), bytes.copyOf(24))
    }
}
