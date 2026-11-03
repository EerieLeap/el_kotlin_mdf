package com.eerie_leap.mdf

// Event (EV): the next event, the parent, the start of a range, the name, the comment, then a link for each scope and attachment and,
// if the flags say so, the name of the group of events; then the fields.
private const val EV_LINK_NEXT = 0
private const val EV_LINK_PARENT = 1
private const val EV_LINK_RANGE = 2
private const val EV_LINK_NAME = 3
private const val EV_LINK_COMMENT = 4
private const val EV_LINK_SCOPES = 5
private const val EV_TYPE = 0
private const val EV_SYNC_TYPE = 1
private const val EV_RANGE_TYPE = 2
private const val EV_CAUSE = 3
private const val EV_FLAGS = 4
private const val EV_SCOPE_COUNT = 8
private const val EV_ATTACHMENT_COUNT = 12
private const val EV_CREATOR = 14
private const val EV_SYNC_BASE = 16
private const val EV_SYNC_FACTOR = 24
private const val EV_FIELDS_SIZE = 32

private const val MAX_EVENTS = 100_000

/** Reads the events (EV) of a file and joins them: the parent and range links, the scopes and the attachments they name. */
internal class EventLoader(
    private val context: LoaderContext,
    private val index: FileIndex,
    private val attachments: List<MdfAttachment>,
) {
    /** The events of the chain that begins at [first], and those they name as parent or start of a range. */
    suspend fun load(first: Long, holder: Long): List<MdfEvent> {
        val events = ArrayList<MdfEvent>(
            context.chain(first, holder, "EV", "the events", MAX_EVENTS) { block, _ -> event(block) to block.link(EV_LINK_NEXT) },
        )
        val byOffset = HashMap<Long, MdfEvent>()
        events.forEach { byOffset[it.offset] = it }
        // An event the chain doesn't list but another names is an event of the file all the same.
        var next = 0
        while (next < events.size && events.size < MAX_EVENTS) {
            val event = events[next++]
            for (link in longArrayOf(event.parentLink, event.rangeLink)) {
                if (link != 0L && link !in byOffset) {
                    loadAlone(link, event.offset)?.let { found ->
                        events.add(found)
                        byOffset[link] = found
                    }
                }
            }
        }
        val byAttachment = attachments.associateBy { it.offset }
        for (event in events) {
            event.parent = byOffset[event.parentLink]
            event.rangeStart = byOffset[event.rangeLink]
            event.scopes = event.scopeLinks.asList().mapNotNull { scopeOf(it) }
            event.attachments = event.attachmentLinks.asList().mapNotNull { byAttachment[it] }
        }
        return events
    }

    private suspend fun loadAlone(link: Long, holder: Long): MdfEvent? =
        context.tolerate("an event", null) { event(context.reader.block(link, holder, "EV")) }

    private suspend fun event(block: Block): MdfEvent {
        block.requireData(EV_FIELDS_SIZE)
        val scopeCount = block.u32AsInt(EV_SCOPE_COUNT)
        val attachmentCount = block.u16(EV_ATTACHMENT_COUNT)
        val flags = block.u8(EV_FLAGS)
        val hasGroupName = (flags and MdfEvent.FLAG_GROUP_NAME) != 0
        val linkCount = EV_LINK_SCOPES.toLong() + scopeCount + attachmentCount + if (hasGroupName) 1 else 0
        if (scopeCount < 0 || linkCount > block.linkCount) {
            damaged(block.offset, "an event of ${block.linkCount} links says it has $scopeCount scopes and $attachmentCount attachments")
        }
        val subject = "an event"
        val firstAttachment = EV_LINK_SCOPES + scopeCount
        return MdfEvent(
            offset = block.offset,
            type = MdfEventType.of(block.u8(EV_TYPE)),
            syncType = MdfSyncType.of(block.u8(EV_SYNC_TYPE)),
            range = MdfEventRange.of(block.u8(EV_RANGE_TYPE)),
            cause = MdfEventCause.of(block.u8(EV_CAUSE)),
            flags = flags,
            name = context.text(block.link(EV_LINK_NAME), block.offset, subject),
            comment = context.comment(block.link(EV_LINK_COMMENT), block.offset, subject),
            groupName = if (hasGroupName) context.text(block.link(firstAttachment + attachmentCount), block.offset, subject) else "",
            creatorIndex = block.u16(EV_CREATOR),
            syncBase = block.u64(EV_SYNC_BASE),
            syncFactor = block.f64(EV_SYNC_FACTOR),
            parentLink = block.link(EV_LINK_PARENT),
            rangeLink = block.link(EV_LINK_RANGE),
            scopeLinks = LongArray(scopeCount) { block.link(EV_LINK_SCOPES + it) },
            attachmentLinks = LongArray(attachmentCount) { block.link(firstAttachment + it) },
        )
    }

    // What an event's scope link names: a channel, a channel group, or a data group.
    private fun scopeOf(link: Long): MdfEventScope? {
        val channel = index.channels[link]
        val group = index.channelGroups[link]
        val data = index.dataGroups[link]
        return when {
            channel != null -> MdfEventScope(index.ownerOf(link), channel, null)
            group != null -> MdfEventScope(group, null, null)
            data != null -> MdfEventScope(null, null, data)
            else -> null
        }
    }
}
