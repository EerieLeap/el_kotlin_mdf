package com.eerie_leap.mdf

/** The most masters that lead to masters: a master group that has a master elsewhere in turn. */
private const val MAX_MASTER_DEPTH = 3

/**
 * What a scan needs besides the records of its data group: the signal data blocks of the variable-length channels it reads,
 * located, and the masters of the channel groups whose master channel is in another channel group, each read by a scan of its
 * own. What can't be had is a failure, which rejects the requests that need it.
 */
internal class ScanPreparation(
    val signalData: Map<MdfChannel, SignalDataValues>,
    val signalFailures: Map<MdfChannel, MdfFailure>,
    val masters: Map<MdfChannelGroup, MasterTimes>,
    val masterFailures: Map<MdfChannelGroup, MdfFailure>,
)

/** Makes the [ScanPreparation] of a scan. */
internal class ScanPreparer(private val file: MdfFile, private val dataGroup: MdfDataGroup, private val depth: Int) {
    private val signalData = HashMap<MdfChannel, SignalDataValues>()
    private val signalFailures = HashMap<MdfChannel, MdfFailure>()
    private val masters = HashMap<MdfChannelGroup, MasterTimes>()
    private val masterFailures = HashMap<MdfChannelGroup, MdfFailure>()

    suspend fun prepare(requests: List<MdfChannelRequest>, canGroups: List<MdfChannelGroup>): ScanPreparation {
        val wanted = requests.map { it.channel }.toHashSet()
        val inSignalData = wanted.filter { it.kind == MdfChannelKind.Vlsd && it.signalDataGroup == null && it.signalDataLink != 0L }
        val payloads = canGroups.mapNotNull { CanFrameLayout.of(it) }.filter { it.payloads == null && !it.hasInlinePayload }
        for (channel in inSignalData + payloads.map { it.payloadChannel }) locateSignalData(channel)
        for (group in dataGroup.channelGroups) {
            if (group.hasRemoteMaster && (group in canGroups || group.allChannels.any { it in wanted })) prepareMaster(group)
        }
        return ScanPreparation(signalData, signalFailures, masters, masterFailures)
    }

    private suspend fun locateSignalData(channel: MdfChannel) {
        if (channel in signalData || channel in signalFailures) return
        try {
            signalData[channel] = SignalDataValues(DataLocator.locateSignalData(file, channel.signalDataLink, channel.offset))
        } catch (e: MdfException) {
            signalFailures[channel] = e.failure
        }
    }

    // The scan of the master of a group whose master channel is in another channel group, which the group's records follow.
    private suspend fun prepareMaster(group: MdfChannelGroup) {
        val remote = group.remoteMaster
        val owner = remote?.let { r -> file.groups.firstOrNull { data -> data.channelGroups.any { it === r } } }
        val channel = if (remote != null && !remote.hasRemoteMaster) remote.master else null
        val reason = when {
            remote == null || owner == null -> "its master channel group isn't in the file"
            channel == null -> "its master channel group has no master channel of its own"
            depth >= MAX_MASTER_DEPTH -> "its masters lead to masters too many times"
            else -> null
        }
        if (reason != null || owner == null || channel == null) {
            masterFailures[group] = MdfFailure.Unsupported(group.offset, "the master of channel group ${group.name}", reason.orEmpty())
            return
        }
        if (channel.kind == MdfChannelKind.VirtualMaster) {
            val conversion = channel.conversion?.takeIf { !it.producesText && !it.takesText }
            val isInteger = channel.dataType.isInteger
            masters[group] = MasterTimes { index -> conversion?.toPhysical(index.toDouble(), isInteger) ?: index.toDouble() }
            return
        }
        try {
            val times = MasterTimes()
            val scan = MdfScanner.build(file, owner, listOf(MdfChannelRequest(channel)), times, depth = depth + 1)
            scan.rejected.firstOrNull()?.let { throw MdfException(it.failure) }
            times.attach(scan)
            masters[group] = times
        } catch (e: MdfException) {
            masterFailures[group] = e.failure
        }
    }
}
