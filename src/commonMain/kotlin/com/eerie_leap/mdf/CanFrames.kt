package com.eerie_leap.mdf

private const val CAN_ID_MASK = 0x1FFFFFFFL
private const val IDE_BIT_OF_ID = 31
private const val CAN_DATA_FRAME = "CAN_DataFrame"

/**
 * A CAN frame of a `CAN_DataFrame` bus event group (the ASAM bus logging layout): when, on which bus, its ID and flags, and its
 * payload.
 */
@Suppress("LongParameterList") // A frame's fields; the model holds each of them.
public class MdfCanFrame(
    /** Seconds from the group's master (see [MdfScanner]). */
    public val time: Double,
    /** The bus as the file numbers it; the Eerie Leap firmware writes its CAN interface + 1, and 0 where it doesn't know. */
    public val bus: Int,
    /** The identifier, 11 or 29 bits. */
    public val id: Long,
    public val isExtended: Boolean,
    /** The data length code, as the frame carries it (9 to 15 are CAN FD lengths of 12 to 64 bytes). */
    public val dlc: Int,
    /** The frame was transmitted by the logger; otherwise received. */
    public val isTransmit: Boolean,
    /** CAN FD: a frame with extended data length. */
    public val isFd: Boolean,
    public val isBitRateSwitch: Boolean,
    public val hasErrorState: Boolean,
    public val dataLength: Int,
    public val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MdfCanFrame && time == other.time && bus == other.bus && id == other.id && isExtended == other.isExtended &&
            dlc == other.dlc && isTransmit == other.isTransmit && isFd == other.isFd && isBitRateSwitch == other.isBitRateSwitch &&
            hasErrorState == other.hasErrorState && dataLength == other.dataLength && data.contentEquals(other.data)

    override fun hashCode(): Int = (time.hashCode() * HASH_MULTIPLIER + id.hashCode()) * HASH_MULTIPLIER + data.contentHashCode()

    override fun toString(): String = "MdfCanFrame(t=$time, bus=$bus, id=0x${id.toString(HEX_RADIX)}, ${data.size} bytes)"

    private companion object {
        const val HASH_MULTIPLIER = 31
        const val HEX_RADIX = 16
    }
}

/**
 * Where the parts of a CAN frame are in a `CAN_DataFrame` group's records, found by the names of its channels: the identifier and
 * the payload, which it needs, and the [optional] parts a writer may leave out. The payload is a variable-length value of another
 * channel group ([payloads]) or of signal data blocks ([payloadChannel]'s link), or bytes in the record itself ([inline]).
 */
internal class CanFrameLayout private constructor(
    /** The variable-length group the payloads are in; null where they are in signal data blocks or in the record. */
    val payloads: MdfChannelGroup?,
    /** The `DataBytes` channel. */
    val payloadChannel: MdfChannel,
    private val identifier: NumberField,
    /** The offset to the payload in the variable-length data; unused for a payload in the record. */
    val dataBytes: NumberField,
    private val optional: Map<String, NumberField>,
    private val inline: Boolean,
) {
    /** The payload is bytes in the record, not in variable-length data. */
    val hasInlinePayload: Boolean get() = inline

    /** The payload bytes of the record whose data begins at [at] in [record], for a layout that [hasInlinePayload]. */
    fun inlinePayload(record: ByteArray, at: Int): ByteArray {
        val start = at + payloadChannel.byteOffset
        return record.copyOfRange(start, start + payloadChannel.byteSpan.toInt())
    }

    /** The frame whose fields are in the record data that begins at [at] in [record], with [payload] as its data. */
    fun frame(record: ByteArray, at: Int, time: Double, payload: ByteArray): MdfCanFrame {
        val rawId = identifier.readBits(record, at)
        val extension = optional["IDE"]
        val isExtended = if (extension != null) extension.readBits(record, at) != 0L else ((rawId ushr IDE_BIT_OF_ID) and 1L) != 0L
        val length = optional["DataLength"]?.readBits(record, at)?.toInt()
        val data = if (length != null && length in 0 until payload.size) payload.copyOf(length) else payload
        return MdfCanFrame(
            time = time,
            bus = optional["BusChannel"]?.readBits(record, at)?.toInt() ?: 0,
            id = rawId and CAN_ID_MASK,
            isExtended = isExtended,
            dlc = optional["DLC"]?.readBits(record, at)?.toInt() ?: data.size,
            isTransmit = flag("Dir", record, at),
            isFd = flag("EDL", record, at),
            isBitRateSwitch = flag("BRS", record, at),
            hasErrorState = flag("ESI", record, at),
            dataLength = data.size,
            data = data,
        )
    }

    private fun flag(name: String, record: ByteArray, at: Int): Boolean = optional[name]?.readBits(record, at)?.let { it != 0L } ?: false

    companion object {
        private val OPTIONAL_MEMBERS = listOf("BusChannel", "IDE", "DLC", "Dir", "EDL", "BRS", "ESI", "DataLength")

        // Every member must lie inside the record's data, as the planner checks the channels it reads.
        private fun membersFit(group: MdfChannelGroup, members: Collection<MdfChannel>): Boolean =
            members.all { it.byteOffset.toLong() + it.byteSpan <= group.dataBytes }

        /** The layout of a `CAN_DataFrame` group, null where [group] isn't one a frame can be read from. */
        fun of(group: MdfChannelGroup): CanFrameLayout? {
            val frame = group.channels.firstOrNull { it.name == CAN_DATA_FRAME || it.name.endsWith(".$CAN_DATA_FRAME") }
            val members = frame?.members.orEmpty().associateBy { it.name.substringAfterLast('.') }
            if (!group.isBusEvent || !membersFit(group, members.values)) return null
            val identifier = members["ID"]?.let { NumberField.of(it) }
            val payload = members["DataBytes"]
            if (identifier == null || payload == null) return null
            val inline = payload.kind == MdfChannelKind.Fixed && payload.dataType == MdfDataType.ByteArray && payload.signalDataLink == 0L
            val variable = payload.signalDataGroup != null || payload.signalDataLink != 0L
            if (!inline && !variable) return null
            val optional = OPTIONAL_MEMBERS.mapNotNull { name -> members[name]?.let { NumberField.of(it) }?.let { name to it } }.toMap()
            return CanFrameLayout(payload.signalDataGroup, payload, identifier, NumberField.offsetOf(payload), optional, inline)
        }
    }
}

/** What there is of the CAN bus logging in a file. */
public object CanFrames {
    /** Whether [group] holds CAN frames in the layout of the bus logging standard (`CAN_DataFrame`), which [MdfScanner] reads. */
    public fun isCanDataFrameGroup(group: MdfChannelGroup): Boolean = CanFrameLayout.of(group) != null

    /** The bus event groups of [file] other than CAN data frames (remote and error frames, LIN, FlexRay, Ethernet): listed, not read. */
    public fun otherBusEventGroups(file: MdfFile): List<MdfChannelGroup> =
        file.channelGroups.filter { it.isBusEvent && !it.isVlsd && !isCanDataFrameGroup(it) }

    /**
     * Reads the frames of [frameGroup] (a CAN data frame group of [dataGroup] of [file]) that the file holds now, handing each to
     * [onFrame]. A payload that no frame names is ignored. Returns how far the scan got; for a file still arriving use an
     * [MdfScanner] and call it again as it grows.
     */
    public suspend fun read(
        file: MdfFile,
        dataGroup: MdfDataGroup,
        frameGroup: MdfChannelGroup,
        onFrame: (MdfCanFrame) -> Unit,
    ): MdfScanProgress {
        val sink = object : MdfSampleSink {
            override fun onSample(request: MdfChannelRequest, time: Double, value: Double, isInvalid: Boolean) = Unit

            override fun onCanFrame(group: MdfChannelGroup, frame: MdfCanFrame) = onFrame(frame)
        }
        return MdfScanner.create(file, dataGroup, emptyList(), sink, listOf(frameGroup)).advance()
    }
}
