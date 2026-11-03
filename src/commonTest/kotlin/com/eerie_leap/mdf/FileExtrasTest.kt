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

/** What hangs on a file's header: its channel hierarchy (CH), events (EV) and attachments (AT). */
class FileExtrasTest {
    private val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val temperature = ChannelSpec("temperature", byteOffset = 8, unit = "C")
    private val speed = ChannelSpec("speed", byteOffset = 12, unit = "km/h")
    private val timeB = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
    private val pressure = ChannelSpec("pressure", byteOffset = 8)
    private val groupA = GroupSpec(1, "engine", listOf(time, temperature, speed), 16)
    private val groupB = GroupSpec(2, "brakes", listOf(timeB, pressure), 12)

    private fun fileOf(extras: (FileBuild) -> Unit, tamper: ((List<TestBlock>) -> Unit)? = null): ByteArray = writeFile(
        FileSpec(listOf(DataGroupSpec(1, listOf(groupA, groupB), ByteArray(0))), extras = extras, tamper = tamper),
    )

    @Test
    fun readsTheChannelHierarchyWithItsMembersAndChildren() {
        val nodes = listOf(
            HierarchySpec(
                "Powertrain",
                type = 0,
                comment = "what drives the car",
                members = listOf(groupA to temperature, groupA to speed),
                children = listOf(
                    HierarchySpec("Brakes", type = 2, members = listOf(groupB to pressure, groupB to null)),
                    HierarchySpec("Empty", type = 7),
                ),
            ),
            HierarchySpec("Calibration", type = 99),
        )
        val file = open(fileOf({ build -> build.header.links[2] = writeHierarchy(build, nodes) }))

        val (powertrain, calibration) = file.hierarchy

        assertEquals("Powertrain", powertrain.name)
        assertEquals(MdfHierarchyType.Group, powertrain.type)
        assertEquals("what drives the car", powertrain.comment.text)
        val engine = file.channelGroups[0]
        val brakes = file.channelGroups[1]
        assertEquals(listOf("temperature", "speed"), powertrain.members.map { it.channel?.name })
        assertSame(engine, powertrain.members[0].group)
        assertSame(engine.channels[1], powertrain.members[0].channel)
        assertEquals(listOf("Brakes", "Empty"), powertrain.children.map { it.name })
        assertEquals(MdfHierarchyType.Structure, powertrain.children[0].type)
        assertEquals(MdfHierarchyType.CalibrationDefinition, powertrain.children[1].type)
        assertSame(brakes, powertrain.children[0].members[0].group)
        assertNull(powertrain.children[0].members[1].channel)
        assertSame(brakes, powertrain.children[0].members[1].group)
        assertTrue(powertrain.children[1].members.isEmpty())
        assertEquals(MdfHierarchyType.Unknown, calibration.type)
        assertTrue(calibration.children.isEmpty())
    }

    @Test
    fun aFileWithoutAHierarchyHasNone() {
        assertTrue(open(fileOf({})).hierarchy.isEmpty())
    }

    @Test
    fun readsEventsWithTheirPlaceTypeAndLinks() {
        val begin = EventSpec("run", type = 3, range = 1, syncBase = 1_000_000_000, syncFactor = 1e-9, flags = 1)
        val end =
            EventSpec("run end", type = 4, range = 2, syncBase = 5, syncFactor = 2.5, rangeStart = begin, comment = "ended", cause = 4)
        val marker = EventSpec(
            "bookmark",
            type = 6,
            syncType = 4,
            syncBase = 17,
            syncFactor = 1.0,
            parent = begin,
            groupName = "mine",
            scopes = listOf(groupA, temperature),
            creatorIndex = 0,
        )
        val file = open(fileOf({ build -> build.header.links[4] = writeEvents(build, listOf(begin, end, marker), emptyMap()) }))

        val (first, second, third) = file.events

        assertEquals("run", first.name)
        assertEquals(MdfEventType.StartRecordingTrigger, first.type)
        assertEquals(MdfEventRange.RangeBeginning, first.range)
        assertEquals(MdfSyncType.Time, first.syncType)
        assertEquals(1.0, first.value, 1e-12)
        assertTrue(first.isPostProcessing)
        assertEquals(MdfEventType.StopRecordingTrigger, second.type)
        assertEquals(MdfEventRange.RangeEnd, second.range)
        assertEquals(MdfEventCause.User, second.cause)
        assertEquals("ended", second.comment.text)
        assertEquals(12.5, second.value, 1e-12)
        assertSame(first, second.rangeStart)
        assertEquals(MdfEventType.Marker, third.type)
        assertEquals(MdfSyncType.Index, third.syncType)
        assertEquals(17.0, third.value)
        assertEquals("mine", third.groupName)
        assertSame(first, third.parent)
        assertNull(third.rangeStart)
        assertEquals(2, third.scopes.size)
        assertSame(file.channelGroups[0], third.scopes[0].group)
        assertNull(third.scopes[0].channel)
        assertSame(file.channelGroups[0].channels[1], third.scopes[1].channel)
        assertSame(file.channelGroups[0], third.scopes[1].group)
    }

    @Test
    fun readsTheAttachmentsAnEventNames() {
        val note = AttachmentSpec("note.txt", "hello".encodeToByteArray())
        val event = EventSpec("with a file", attachments = listOf(note))
        val file = open(
            fileOf({ build ->
                val (first, blocks) = writeAttachments(build.layout, listOf(note))
                build.header.links[3] = first
                build.header.links[4] = writeEvents(build, listOf(event), blocks)
            }),
        )

        assertSame(file.attachments.single(), file.events.single().attachments.single())
        assertEquals("note.txt", file.events.single().attachments.single().fileName)
    }

    @Test
    fun anEventNamedAsAParentButNotInTheChainIsAnEventAllTheSame() {
        val parent = EventSpec("parent")
        val child = EventSpec("child", parent = parent)
        val file = open(
            fileOf(
                { build ->
                    val first = checkNotNull(writeEvents(build, listOf(child, parent), emptyMap()))
                    // The chain lists the child alone; the parent is only linked from it.
                    first.links[0] = null
                    build.header.links[4] = first
                },
            ),
        )

        assertEquals(listOf("child", "parent"), file.events.map { it.name })
        assertSame(file.events[1], file.events[0].parent)
    }

    @Test
    fun readsEmbeddedAttachmentsPlainAndDeflated() {
        val dbc = "VERSION \"\"\nBO_ 1 MESSAGE: 8 Vector__XXX\n".repeat(20).encodeToByteArray()
        val plain = AttachmentSpec("signals.dbc", dbc, mimeType = "application/dbc", comment = "the CAN database")
        val packed = AttachmentSpec("signals2.dbc", dbc, isCompressed = true, md5 = null)
        val elsewhere = AttachmentSpec("c:/calibration.a2l", ByteArray(0), isEmbedded = false)
        val file = open(fileOf({ build -> build.header.links[3] = writeAttachments(build.layout, listOf(plain, packed, elsewhere)).first }))

        val (first, second, third) = file.attachments

        assertEquals("signals.dbc", first.fileName)
        assertEquals("application/dbc", first.mimeType)
        assertEquals("the CAN database", first.comment.text)
        assertTrue(first.isEmbedded)
        assertFalse(first.isCompressed)
        assertEquals("000102030405060708090a0b0c0d0e0f", first.md5)
        assertEquals(dbc.size.toLong(), first.originalSize)
        assertEquals(dbc.size.toLong(), first.embeddedSize)
        assertTrue(second.isCompressed)
        assertNull(second.md5)
        assertEquals(dbc.size.toLong(), second.originalSize)
        assertFalse(third.isEmbedded)
        assertContentEquals(dbc, runSuspending { file.readAttachment(first) })
        assertContentEquals(dbc, runSuspending { file.readAttachment(second) })
        assertIs<MdfFailure.Unsupported>(failureOf { file.readAttachment(third) })
    }

    @Test
    fun anAttachmentIsNotReadPastTheLimitItIsGiven() {
        val big = AttachmentSpec("big.bin", ByteArray(5000) { it.toByte() })
        val file = open(fileOf({ build -> build.header.links[3] = writeAttachments(build.layout, listOf(big)).first }))

        assertIs<MdfFailure.TooLarge>(failureOf { file.readAttachment(file.attachments.single(), maxBytes = 4096) })
        assertEquals(5000, runSuspending { file.readAttachment(file.attachments.single(), maxBytes = 5000) }.size)
    }

    @Test
    fun anAttachmentCompressedOtherwiseThanByDeflateIsNotInflated() {
        val data = AttachmentSpec("x.bin", ByteArray(10), isCompressed = true, zipType = 2)
        val file = open(fileOf({ build -> build.header.links[3] = writeAttachments(build.layout, listOf(data)).first }))

        assertIs<MdfFailure.Unsupported>(failureOf { file.readAttachment(file.attachments.single()) })
    }

    @Test
    fun saysAnAttachmentWhoseDataRunPastItsBlockIsDamaged() {
        val bytes = fileOf({ build ->
            build.header.links[3] =
                writeAttachments(build.layout, listOf(AttachmentSpec("x.bin", ByteArray(10), declaredEmbeddedSize = 1000))).first
        })

        val failure = assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(bytes.asMdfSource()) })
        assertTrue(failure.reason.contains("attachment"), failure.reason)
    }

    @Test
    fun saysAnAttachmentOfASizeNoArrayHoldsIsTooLargeNotACrash() {
        // The original size field is 24 bytes into the block's fields; the sizes are 64-bit numbers, and negative ones are not sizes.
        for (size in listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L shl 40)) {
            val data = AttachmentSpec("x.bin", ByteArray(100) { it.toByte() }, isCompressed = true)
            val bytes = fileOf(
                { build -> build.header.links[3] = writeAttachments(build.layout, listOf(data)).first },
                tamper = { blocks -> ByteWriter().u64(size).toByteArray().copyInto(blocks.first { it.type == "AT" }.data, 24) },
            )
            val file = open(bytes)

            assertIs<MdfFailure.TooLarge>(failureOf { file.readAttachment(file.attachments.single()) }, "size $size")
        }
    }

    @Test
    fun saysDeflatedAttachmentDataThatDoesNotInflateToItsSizeIsDamaged() {
        val data = AttachmentSpec("x.bin", ByteArray(100) { it.toByte() }, isCompressed = true)
        val bytes = fileOf(
            { build -> build.header.links[3] = writeAttachments(build.layout, listOf(data)).first },
            tamper = { blocks ->
                // The original size field, at 24 bytes into the block's fields, says more than the data inflate to.
                ByteWriter().u64(500).toByteArray().copyInto(blocks.first { it.type == "AT" }.data, 24)
            },
        )
        val file = open(bytes)

        assertIs<MdfFailure.Damaged>(failureOf { file.readAttachment(file.attachments.single()) })
    }

    @Test
    fun saysAnAttachmentCutShortInItsDataIsCutShort() {
        val data = AttachmentSpec("x.bin", ByteArray(2000) { it.toByte() })
        val bytes = fileOf({ build -> build.header.links[3] = writeAttachments(build.layout, listOf(data)).first })
        // Cut in the middle of its data, which the blocks that name it follow: those blocks are lost too, and a lenient reader goes on.
        val attachment = open(bytes).attachments.single()
        val file = open(bytes.copyOf((attachment.dataOffset + 1000).toInt()), lenient = true)

        assertIs<MdfFailure.Truncated>(failureOf { file.readAttachment(file.attachments.single()) })
    }

    @Test
    fun readsEverythingOfAFileWithAHierarchyEventsAndAttachmentsTogether() {
        val file = open(
            fileOf({ build ->
                build.header.links[2] = writeHierarchy(build, listOf(HierarchySpec("All", members = listOf(groupA to speed))))
                val (first, blocks) = writeAttachments(build.layout, listOf(AttachmentSpec("a.txt", "abc".encodeToByteArray())))
                build.header.links[3] = first
                build.header.links[4] = writeEvents(build, listOf(EventSpec("e", syncBase = 3, syncFactor = 2.0)), blocks)
            }),
        )

        assertEquals(1, file.hierarchy.size)
        assertEquals(1, file.attachments.size)
        assertEquals(6.0, file.events.single().value)
        assertTrue(file.issues.isEmpty())
    }

    @Test
    fun saysABrokenHierarchyEventOrAttachmentChainIsDamagedNotAHang() {
        val loops = mapOf<String, (FileBuild) -> Unit>(
            "events" to { build -> build.header.links[4] = writeEvents(build, listOf(EventSpec("a"), EventSpec("b")), emptyMap()) },
            "attachments" to { build ->
                build.header.links[3] =
                    writeAttachments(build.layout, listOf(AttachmentSpec("a", ByteArray(1)), AttachmentSpec("b", ByteArray(1)))).first
            },
            "hierarchy" to { build -> build.header.links[2] = writeHierarchy(build, listOf(HierarchySpec("a"), HierarchySpec("b"))) },
        )
        val types = mapOf("events" to "EV", "attachments" to "AT", "hierarchy" to "CH")
        for ((what, extras) in loops) {
            val bytes = fileOf(extras, tamper = { blocks ->
                val chain = blocks.filter { it.type == types.getValue(what) }
                chain.first { it.links[0] != null }.links[0] = chain.first { it.links[0] == null }.also { last -> last.links[0] = last }
            })

            val failure = assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(bytes.asMdfSource()) }, what)
            assertTrue(failure.reason.contains("comes back"), "$what: ${failure.reason}")
        }
    }

    @Test
    fun saysAnEventOrAHierarchyNodeThatCountsMoreThanItHasLinksIsDamaged() {
        val badEvent = fileOf({ build ->
            build.header.links[4] = writeEvents(build, listOf(EventSpec("a")), emptyMap())
        }, tamper = { blocks -> ByteWriter().u32(9).toByteArray().copyInto(blocks.first { it.type == "EV" }.data, 8) })
        val badNode = fileOf({ build ->
            build.header.links[2] = writeHierarchy(build, listOf(HierarchySpec("a", members = listOf(groupA to speed))))
        }, tamper = { blocks -> ByteWriter().u32(9).toByteArray().copyInto(blocks.first { it.type == "CH" }.data, 0) })

        assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(badEvent.asMdfSource()) })
        assertIs<MdfFailure.Damaged>(failureOf { Mdf4Reader.open(badNode.asMdfSource()) })
        val lenient = open(badEvent, lenient = true)
        assertTrue(lenient.events.isEmpty())
        assertNotNull(lenient.issues.singleOrNull())
    }
}
