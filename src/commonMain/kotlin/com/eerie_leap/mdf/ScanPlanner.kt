package com.eerie_leap.mdf

/** The most a record may take; a buffer holds at least one. */
private const val MAX_RECORD_BYTES = 64L * 1024 * 1024

/** The plans of a scan, one for each channel group of the data group, and the requests that couldn't be planned. */
internal class PlannedScan(val groups: List<GroupPlan>, val rejected: List<MdfIssue>)

/** Checks what a scan is asked for against the data group's layout and plans how each record is read. */
internal class ScanPlanner(
    private val dataGroup: MdfDataGroup,
    private val prepared: ScanPreparation,
    /** The file's offsets into variable-length data are stale: the values are taken in the order they come. */
    private val recount: Boolean,
) {
    private val plans = dataGroup.channelGroups.map { GroupPlan(it) }
    private val byGroup = plans.associateBy { it.group }
    private val ownerOf = HashMap<MdfChannel, GroupPlan>().also { owners ->
        for (plan in plans) plan.group.channels.forEach { addOwner(owners, plan, it) }
    }
    private val rejected = ArrayList<MdfIssue>()

    fun plan(requests: List<MdfChannelRequest>, canGroups: List<MdfChannelGroup>): PlannedScan {
        checkLayout()
        plans.forEach { setUpTime(it) }
        for (request in requests) reject(request.channel.name, "channel", attach(request))
        for (group in canGroups) reject(group.name, "channel group", attachFrames(group))
        return PlannedScan(plans, rejected)
    }

    private fun reject(name: String, what: String, failure: MdfFailure?) {
        if (failure != null) rejected.add(MdfIssue(failure, "$what $name"))
    }

    private fun checkLayout() {
        val groups = dataGroup.channelGroups
        groups.firstOrNull { !it.isVlsd && it.recordBytes > MAX_RECORD_BYTES }?.let {
            throw MdfException(MdfFailure.TooLarge(it.offset, it.recordBytes, MAX_RECORD_BYTES))
        }
        if (dataGroup.isSorted && groups.size > 1) {
            damaged(dataGroup.offset, "a sorted data group (no record IDs) holds ${groups.size} channel groups")
        }
        if (!dataGroup.isSorted && groups.map { it.recordId }.toSet().size != groups.size) {
            damaged(dataGroup.offset, "two channel groups of a data group share a record ID")
        }
    }

    private fun addOwner(owners: HashMap<MdfChannel, GroupPlan>, plan: GroupPlan, channel: MdfChannel) {
        owners[channel] = plan
        channel.members.forEach { addOwner(owners, plan, it) }
    }

    private fun attach(request: MdfChannelRequest): MdfFailure? {
        val channel = request.channel
        val plan = ownerOf[channel] ?: return unsupported(channel, "it isn't in a channel group of this data group")
        val type = channel.dataType
        val timeFailure = plan.timeFailure
        return when {
            channel.array != null -> unsupported(channel, arrayReason(channel))
            plan.group.isVlsd -> unsupported(channel, "it is in a group of variable-length values, which has no records of its own")
            timeFailure != null -> timeFailure
            channel.kind == MdfChannelKind.Vlsd -> attachVariableLength(plan, request)
            channel.isNumeric -> attachNumber(plan, request)
            type.isString || type == MdfDataType.ByteArray -> attachFixedText(plan, request)
            type.isNumber -> unsupported(channel, "a channel of the kind ${channel.kind.name} has no values in the records")
            else -> unsupported(channel, "its data type ${type.name} isn't read")
        }
    }

    private fun arrayReason(channel: MdfChannel): String {
        val reason = channel.array?.unreadReason
        return if (reason == null) "it is an array: its elements are channels of their own" else "it is an array that isn't read: $reason"
    }

    private fun attachNumber(plan: GroupPlan, request: MdfChannelRequest): MdfFailure? {
        val channel = request.channel
        val field = if (channel.isVirtual) null else NumberField.of(channel)
        val failure = when {
            !channel.isVirtual && field == null -> unsupported(
                channel,
                "its ${channel.dataType.name} of ${channel.bitCount} bits isn't read",
            )

            !channel.isVirtual -> checkFits(plan, channel)

            else -> null
        } ?: checkValidity(plan, channel)
        if (failure == null) {
            val conversion = channel.conversion
            val converts = !request.stored && conversion != null && !conversion.producesText && !conversion.takesText
            plan.samples.add(
                SamplePlan(
                    request,
                    field,
                    Validity(channel, plan.group.dataBytes),
                    if (converts) conversion else null,
                    channel.dataType.isInteger,
                ),
            )
        }
        return failure
    }

    private fun attachFixedText(plan: GroupPlan, request: MdfChannelRequest): MdfFailure? {
        val channel = request.channel
        val failure = checkFits(plan, channel) ?: checkValidity(plan, channel)
        if (failure == null) {
            val byteCount = channel.byteSpan.toInt()
            plan.texts.add(
                TextPlan(request, channel.dataType, channel.byteOffset, byteCount, Validity(channel, plan.group.dataBytes), null, null),
            )
        }
        return failure
    }

    private fun attachVariableLength(plan: GroupPlan, request: MdfChannelRequest): MdfFailure? {
        val channel = request.channel
        val values = channel.signalDataGroup?.let { byGroup[it] }
        val signal = prepared.signalData[channel]
        val failure = when {
            values == null && signal == null -> prepared.signalFailures[channel] ?: unsupported(channel, noValuesReason(channel))

            !channel.dataType.isString && channel.dataType != MdfDataType.ByteArray -> unsupported(
                channel,
                "its values aren't text or bytes",
            )

            else -> checkFits(plan, channel) ?: checkValidity(plan, channel)
        }
        if (failure == null) {
            val store = values?.let { storeOf(it) }
            val validity = Validity(channel, plan.group.dataBytes)
            val text = TextPlan(request, channel.dataType, 0, 0, validity, NumberField.offsetOf(channel), store, signal)
            plan.texts.add(text)
            values?.textReaders?.add(text)
        }
        return failure
    }

    private fun noValuesReason(channel: MdfChannel): String =
        if (channel.signalDataLink == 0L) "it names no variable-length values" else "its variable-length values can't be located"

    // Where a variable-length group's values are kept as they go by, made when the first plan asks.
    private fun storeOf(values: GroupPlan): VariableLengthStore = values.store ?: VariableLengthStore(recount).also { values.store = it }

    private fun attachFrames(group: MdfChannelGroup): MdfFailure? {
        val plan = byGroup[group]
        val layout = CanFrameLayout.of(group)
        val payloads = layout?.payloads?.let { byGroup[it] }
        val signal = layout?.takeUnless { it.isRemote }?.let { prepared.signalData[it.payloadChannel] }
        val timeFailure = plan?.timeFailure
        return when {
            plan == null -> frameFailure(group, "it isn't in this data group")

            layout == null -> frameFailure(group, "it doesn't hold CAN frames in the CAN_DataFrame layout")

            timeFailure != null -> timeFailure

            layout.hasInlinePayload -> null.also { plan.frames = FramePlan(group, layout, null) }

            payloads == null && signal == null -> prepared.signalFailures[layout.payloadChannel]
                ?: frameFailure(group, "its payloads can't be located")

            else -> {
                val frames = FramePlan(group, layout, payloads?.let { storeOf(it) }, signal)
                plan.frames = frames
                payloads?.frameReaders?.add(frames)
                null
            }
        }
    }

    private fun frameFailure(group: MdfChannelGroup, reason: String): MdfFailure =
        MdfFailure.Unsupported(group.offset, "CAN frames", reason)

    private fun unsupported(channel: MdfChannel, reason: String): MdfFailure =
        MdfFailure.Unsupported(channel.offset, "values of channel ${channel.name}", reason)

    // The channel's bytes must lie inside the record's data.
    private fun checkFits(plan: GroupPlan, channel: MdfChannel): MdfFailure? {
        val end = channel.byteOffset.toLong() + channel.byteSpan
        return if (end > plan.group.dataBytes) {
            MdfFailure.Damaged(
                channel.offset,
                "channel ${channel.name} reaches byte $end of a record of ${plan.group.dataBytes} data bytes",
            )
        } else {
            null
        }
    }

    private fun checkValidity(plan: GroupPlan, channel: MdfChannel): MdfFailure? {
        val bit = channel.invalidationBit
        return if (bit != null && bit >= plan.group.invalidationBytes * Byte.SIZE_BITS) {
            MdfFailure.Damaged(
                channel.offset,
                "channel ${channel.name} has invalidation bit $bit, outside the ${plan.group.invalidationBytes} invalidation bytes",
            )
        } else {
            null
        }
    }

    // The time of a group's samples: its master's value through the master's conversion; a virtual master is the record's number
    // through its conversion; a group with no master has the record's number. A master that can't be read is a failure, and nothing of
    // the group is placed on a time that isn't its own.
    private fun setUpTime(plan: GroupPlan) {
        val group = plan.group
        if (group.hasRemoteMaster) {
            plan.remoteTimes = prepared.masters[group]
            if (plan.remoteTimes == null) {
                plan.timeFailure = prepared.masterFailures[group]
                    ?: MdfFailure.Unsupported(
                        group.offset,
                        "the master of channel group ${group.name}",
                        "its master channel group can't be read",
                    )
            }
            return
        }
        val master = group.master ?: return
        plan.timeIsInteger = master.dataType.isInteger
        if (master.kind == MdfChannelKind.VirtualMaster) {
            plan.timeConversion = usable(master.conversion)
            return
        }
        val field = NumberField.of(master)
        val misfit = checkFits(plan, master)
        when {
            field == null -> plan.timeFailure = unsupported(master, "its ${master.dataType.name} of ${master.bitCount} bits isn't read")

            misfit != null -> plan.timeFailure = misfit

            else -> {
                plan.timeField = field
                plan.timeConversion = usable(master.conversion)
            }
        }
    }

    private fun usable(conversion: MdfConversion?): MdfConversion? = conversion?.takeIf { !it.producesText && !it.takesText }
}
