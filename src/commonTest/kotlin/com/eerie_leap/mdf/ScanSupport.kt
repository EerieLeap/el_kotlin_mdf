package com.eerie_leap.mdf

/** One sample a scan handed over. */
internal data class Sample(val time: Double, val value: Double, val isInvalid: Boolean)

/** Collects what a scan hands over, by channel request. */
internal class CollectingSink : MdfSampleSink {
    val samples = LinkedHashMap<MdfChannelRequest, MutableList<Sample>>()
    val texts = LinkedHashMap<MdfChannelRequest, MutableList<String>>()
    val frames = ArrayList<MdfCanFrame>()
    val framesOf = LinkedHashMap<MdfChannelGroup, MutableList<MdfCanFrame>>()

    override fun onSample(request: MdfChannelRequest, time: Double, value: Double, isInvalid: Boolean) {
        samples.getOrPut(request) { ArrayList() }.add(Sample(time, value, isInvalid))
    }

    override fun onText(request: MdfChannelRequest, time: Double, text: String, isInvalid: Boolean) {
        texts.getOrPut(request) { ArrayList() }.add(text)
    }

    override fun onCanFrame(group: MdfChannelGroup, frame: MdfCanFrame) {
        frames.add(frame)
        framesOf.getOrPut(group) { ArrayList() }.add(frame)
    }

    fun of(request: MdfChannelRequest): List<Sample> = samples[request].orEmpty()
}

/** Every channel but the masters, as requests for their converted values. */
internal fun valueRequests(file: MdfFile, stored: Boolean = false): List<MdfChannelRequest> = file.channelGroups.flatMap { group ->
    group.channels.filter { it.isNumeric && it !== group.master }.map { MdfChannelRequest(it, stored) }
}

/** Scans all that [file] holds of its first data group, in one call. */
internal fun scanAll(
    file: MdfFile,
    requests: List<MdfChannelRequest> = valueRequests(file),
    canGroups: List<MdfChannelGroup> = emptyList(),
): CollectingSink {
    val sink = CollectingSink()
    runSuspending { MdfScanner.create(file, file.groups.first(), requests, sink, canGroups).advance() }
    return sink
}
