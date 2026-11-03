package com.eerie_leap.mdf

/**
 * Writes the files rt_core's `Mdf4LoggerSensorReading` writes, in its layout (the logging plan, 1.1; the writer in
 * `src/subsys/mdf/` and the logger in `src/domain/logging_domain/loggers/`): one unsorted data group with record IDs of 1 byte (2
 * past 127 sensors), a channel group for each sensor with a `Timestamp` master of float64 seconds, a float32 value with an
 * invalidation bit and, for a sensor with an expression, a float32 `<id>.raw`; for a raw CAN frame sensor a variable-length
 * group and a `CAN_DataFrame` group. The records are appended in the order they are added, as the ticks write them.
 */
internal class FirmwareLogWriter(private val recordIdSize: Int = 1) {
    /** A sensor with a float32 value and, with [raw], a float32 raw value: a formula as an algebraic conversion, else a comment. */
    class ValueSensor(val id: String, val unit: String = "", val comment: String? = null, val raw: Raw? = null)

    class Raw(val formula: String? = null, val comment: String? = null)

    class CanSensor(val id: String, val comment: String? = null)

    class Frame(
        val time: Double,
        val id: Long,
        val data: ByteArray,
        val bus: Int = 1,
        val isExtended: Boolean = false,
        val isTransmit: Boolean = false,
        val isFd: Boolean = false,
        val isBitRateSwitch: Boolean = false,
    )

    private class ValueGroup(val sensor: ValueSensor, val recordId: Long) {
        var cycles = 0L
    }

    private class CanGroup(val sensor: CanSensor, val payloadId: Long, val frameId: Long) {
        var frames = 0L
        var payloadBytes = 0L
        var vlsdOffset = 0L
    }

    private val values = ArrayList<ValueGroup>()
    private val cans = ArrayList<CanGroup>()

    // The sensors in the order they were declared, which is the order of their groups in the file.
    private val declared = ArrayList<Any>()
    private val records = ByteWriter()
    private var nextRecordId = 1L

    /** The properties of the header's comment; the unit's board, product and configuration. */
    var properties: List<Pair<String, String>> = listOf(
        "device_name" to "el_pm100",
        "product_id" to "41985",
        "device_id" to "0",
        "hw_version" to "2",
        "sw_version" to "66048",
        "sensors_configuration_hash" to "3735928559",
        "logging_interval_ms" to "100",
    )

    var startNs: Long = 1_700_000_000_000_000_000L

    /** The tool and its version, as the file history names them. */
    var toolId: String = "rt_daq"
    var toolVersion: String = "1.4.2"

    /** The size of the records so far: the data after the data block's header. */
    val recordBytes: Int get() = records.size

    fun sensor(sensor: ValueSensor): Int {
        values.add(ValueGroup(sensor, nextRecordId++))
        declared.add(values.last())
        return values.size - 1
    }

    fun can(sensor: CanSensor): Int {
        cans.add(CanGroup(sensor, nextRecordId++, nextRecordId++))
        declared.add(cans.last())
        return cans.size - 1
    }

    /** One record of a value sensor: the time, the value, the raw value if it has one, and the invalidation mask (bit 0 the value's). */
    fun value(
        sensor: Int,
        time: Double,
        value: Float,
        raw: Float? = null,
        invalid: Int = 0,
    ) {
        val group = values[sensor]
        recordId(group.recordId)
        records.f64(time).f32(value)
        if (group.sensor.raw != null) records.f32(raw ?: 0f)
        records.u8(invalid)
        group.cycles++
    }

    /** A CAN frame: its payload record and then its frame record, as the logger writes them. */
    fun frame(sensor: Int, frame: Frame) {
        val group = cans[sensor]
        recordId(group.payloadId)
        records.u32(frame.data.size.toLong()).bytes(frame.data)
        recordId(group.frameId)
        records.f64(frame.time)
        records.u8(frame.bus)
        records.u32(frame.id or if (frame.isExtended) IDE_BIT else 0L)
        records.u8(dlcOf(frame.data.size) or bit(frame.isTransmit, DIR) or bit(frame.isFd, EDL) or bit(frame.isBitRateSwitch, BRS))
        records.u8(frame.data.size)
        records.u64(group.vlsdOffset)
        group.vlsdOffset += U32_SIZE + frame.data.size
        group.payloadBytes += frame.data.size
        group.frames++
    }

    /** The file. A finished one has its lengths and counts and the identifier `MDF`; one not finished is as the writer leaves it. */
    fun build(finished: Boolean = true): ByteArray {
        val groups = ArrayList<GroupSpec>()
        val source = SourceSpec("Eerie Leap Sensor", type = SOURCE_IO_DEVICE)
        for (group in declared) {
            if (group is ValueGroup) {
                groups.add(valueGroup(group, source, finished))
            } else if (group is CanGroup) {
                val payloads = vlsdGroup(group, finished)
                groups.add(payloads)
                groups.add(canGroup(group, payloads, finished))
            }
        }
        val dataGroup = DataGroupSpec(
            recordIdSize = recordIdSize,
            groups = groups,
            records = records.toByteArray(),
            // The writer patches the length of the data block when it closes the file; until then it covers the header alone.
            dataBlockLength = if (finished) null else TestBlock.HEADER_SIZE.toLong(),
        )
        return writeFile(
            FileSpec(
                dataGroups = listOf(dataGroup),
                finalized = finished,
                unfinalizedFlags = if (finished) 0 else UNFINALIZED_FLAGS,
                startNs = startNs,
                headerComment = headerComment(),
                toolId = toolId,
                toolVendor = "Eerie Leap",
                toolVersion = toolVersion,
            ),
        )
    }

    private fun recordId(id: Long) {
        for (i in 0 until recordIdSize) records.u8((id shr (Byte.SIZE_BITS * i)).toInt())
    }

    private fun headerComment(): String {
        val entries = properties.joinToString("") { (name, value) -> "<e name=\"${escapeXml(name)}\">${escapeXml(value)}</e>" }
        return "<HDcomment><TX>Eerie Leap sensor log</TX><common_properties>$entries</common_properties></HDcomment>"
    }

    private fun valueGroup(group: ValueGroup, source: SourceSpec, finished: Boolean): GroupSpec {
        val sensor = group.sensor
        val timestamp = ChannelSpec(
            name = "Timestamp",
            dataType = DataTypes.FLOAT_LE,
            bitCount = 64,
            type = ChannelTypes.MASTER,
            syncType = SYNC_TIME,
            unit = "s",
        )
        val value = ChannelSpec(
            name = sensor.id,
            byteOffset = TIME_BYTES,
            invalidationBit = 0,
            unit = sensor.unit.ifEmpty { null },
            comment = sensor.comment,
        )
        val channels = arrayListOf(timestamp, value)
        sensor.raw?.let { raw ->
            channels.add(
                ChannelSpec(
                    name = "${sensor.id}.raw",
                    byteOffset = TIME_BYTES + FLOAT_BYTES,
                    invalidationBit = 1,
                    conversion = raw.formula?.let { ConversionSpec.algebraic(it) },
                    comment = if (raw.formula == null) raw.comment else null,
                ),
            )
        }
        return GroupSpec(
            recordId = group.recordId,
            name = sensor.id,
            channels = channels,
            dataBytes = TIME_BYTES + FLOAT_BYTES * (channels.size - 1),
            invalidationBytes = 1,
            cycleCount = if (finished) group.cycles else 0,
            source = source,
        )
    }

    private fun vlsdGroup(group: CanGroup, finished: Boolean) = GroupSpec(
        recordId = group.payloadId,
        name = null,
        channels = emptyList(),
        dataBytes = 0,
        flags = GroupFlags.VARIABLE_LENGTH,
        cycleCount = if (finished) group.frames else 0,
        vlsdBytes = if (finished) group.payloadBytes else 0,
    )

    private fun canGroup(group: CanGroup, payloads: GroupSpec, finished: Boolean): GroupSpec {
        fun member(name: String, offset: Int, bitOffset: Int, bits: Int) = ChannelSpec(
            name = "CAN_DataFrame.$name",
            dataType = DataTypes.UNSIGNED_LE,
            bitCount = bits,
            byteOffset = TIME_BYTES + offset,
            bitOffset = bitOffset,
            flags = ChannelFlags.BUS_EVENT,
        )
        val members = listOf(
            member("BusChannel", 0, 0, BITS_8),
            member("ID", 1, 0, CAN_ID_BITS),
            member("IDE", 1 + IDE_BIT_POSITION / Byte.SIZE_BITS, IDE_BIT_POSITION % Byte.SIZE_BITS, 1),
            member("DLC", FLAGS_AT, 0, DLC_BITS),
            member("Dir", FLAGS_AT, DIR, 1),
            member("EDL", FLAGS_AT, EDL, 1),
            member("BRS", FLAGS_AT, BRS, 1),
            member("ESI", FLAGS_AT, ESI, 1),
            member("DataLength", DATA_LENGTH_AT, 0, BITS_8),
            ChannelSpec(
                name = "CAN_DataFrame.DataBytes",
                dataType = DataTypes.BYTE_ARRAY,
                bitCount = U64_BITS,
                byteOffset = TIME_BYTES + DATA_BYTES_AT,
                type = ChannelTypes.VARIABLE_LENGTH,
                flags = ChannelFlags.BUS_EVENT,
                signalData = payloads,
            ),
        )
        val frame = ChannelSpec(
            name = "CAN_DataFrame",
            dataType = DataTypes.BYTE_ARRAY,
            bitCount = FRAME_BYTES * Byte.SIZE_BITS,
            byteOffset = TIME_BYTES,
            flags = ChannelFlags.BUS_EVENT,
            members = members,
        )
        val timestamp = ChannelSpec("Timestamp", bitCount = 64, type = ChannelTypes.MASTER, syncType = SYNC_TIME, unit = "s")
        return GroupSpec(
            recordId = group.frameId,
            name = group.sensor.id,
            channels = listOf(timestamp, frame),
            dataBytes = TIME_BYTES + FRAME_BYTES,
            flags = GroupFlags.BUS_EVENT or GroupFlags.PLAIN_BUS_EVENT,
            cycleCount = if (finished) group.frames else 0,
            comment = group.sensor.comment,
            pathSeparator = PATH_SEPARATOR,
            source = SourceSpec("CAN", path = "CAN", type = SOURCE_BUS, bus = BUS_CAN),
        )
    }

    private fun dlcOf(length: Int): Int = when {
        length <= MAX_CLASSIC_LENGTH -> length
        else -> FD_LENGTHS.indexOfFirst { length <= it } + MAX_CLASSIC_LENGTH + 1
    }

    private fun bit(set: Boolean, position: Int): Int = if (set) 1 shl position else 0

    companion object {
        const val UNFINALIZED_FLAGS = 0x01 or 0x04 or 0x20
        private const val IDE_BIT = 1L shl 31
        private const val PATH_SEPARATOR = '.'.code
        private const val IDE_BIT_POSITION = 31
        private const val SYNC_TIME = 1
        private const val SOURCE_IO_DEVICE = 3
        private const val SOURCE_BUS = 2
        private const val BUS_CAN = 2
        private const val TIME_BYTES = 8
        private const val FLOAT_BYTES = 4
        private const val U32_SIZE = 4
        private const val U64_BITS = 64
        private const val BITS_8 = 8
        private const val CAN_ID_BITS = 29
        private const val DLC_BITS = 4
        private const val FLAGS_AT = 5
        private const val DATA_LENGTH_AT = 6
        private const val DATA_BYTES_AT = 7
        private const val FRAME_BYTES = 15
        private const val DIR = 4
        private const val EDL = 5
        private const val BRS = 6
        private const val ESI = 7
        private const val MAX_CLASSIC_LENGTH = 8
        private val FD_LENGTHS = intArrayOf(12, 16, 20, 24, 32, 48, 64)
    }
}
