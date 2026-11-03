package com.eerie_leap.mdf

/** The data groups, channel groups and channels of a file by the offset of their blocks, for blocks that name them by link. */
internal class FileIndex(private val groups: List<MdfDataGroup>) {
    val dataGroups: Map<Long, MdfDataGroup> by lazy(LazyThreadSafetyMode.NONE) { groups.associateBy { it.offset } }

    val channelGroups: Map<Long, MdfChannelGroup> by lazy(LazyThreadSafetyMode.NONE) {
        groups.flatMap { it.channelGroups }.associateBy { it.offset }
    }

    private val owners = HashMap<Long, MdfChannelGroup>()
    private val byOffset = HashMap<Long, MdfChannel>()

    // By the offset of the channel's block: the channel with members (a structure, an array) before its members.
    val channels: Map<Long, MdfChannel> by lazy(LazyThreadSafetyMode.NONE) {
        for (data in groups) {
            for (group in data.channelGroups) {
                for (channel in group.allChannels) {
                    if (channel.offset !in byOffset) {
                        byOffset[channel.offset] = channel
                        owners[channel.offset] = group
                    }
                }
            }
        }
        byOffset
    }

    /** The channel group the channel whose block is at [offset] is in. */
    fun ownerOf(offset: Long): MdfChannelGroup? = channels.let { owners[offset] }
}
