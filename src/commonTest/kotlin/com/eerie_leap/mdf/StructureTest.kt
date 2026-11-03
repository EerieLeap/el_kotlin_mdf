package com.eerie_leap.mdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StructureTest {
    private fun dataGroup(vararg groups: GroupSpec, recordIdSize: Int = 1) = DataGroupSpec(recordIdSize, groups.toList(), ByteArray(0))

    @Test
    fun readsTheHeadersTimeFields() {
        val bytes = writeFile(
            FileSpec(
                listOf(dataGroup()),
                startNs = 1_234_567_890_123_456_789L,
                timeFlags = 0x03,
                timeClass = 16,
                timeZoneMinutes = 60,
                daylightSavingMinutes = -30,
            ),
        )

        val header = open(bytes).header

        assertEquals(1_234_567_890_123_456_789L, header.startNs)
        assertEquals(60, header.timeZoneOffsetMinutes)
        assertEquals(-30, header.daylightSavingOffsetMinutes)
        assertEquals(16, header.timeClass)
        assertTrue(header.isLocalTime)
        assertTrue(header.hasTimeOffsets)
        assertEquals(0.0, header.startAngleRad)
    }

    @Test
    fun readsEveryFileHistoryEntryInOrder() {
        val file = open(writeFile(FileSpec(listOf(dataGroup()), toolId = "logger", laterTools = listOf("converter", "editor"))))

        assertEquals(listOf("logger", "converter", "editor"), file.history.map { it.tool })
        assertEquals(listOf("Tests", "Tests", "Tests"), file.history.map { it.vendor })
    }

    @Test
    fun aFileWithoutAHeaderCommentHasAnEmptyOne() {
        val file = open(writeFile(FileSpec(listOf(dataGroup()), headerComment = null)))

        assertTrue(file.comment.isEmpty)
    }

    @Test
    fun readsSeveralDataGroupsEachWithItsChannelGroups() {
        val first = dataGroup(GroupSpec(1, "a", listOf(ChannelSpec("x")), 4), GroupSpec(2, "b", listOf(ChannelSpec("y")), 4))
        val second = dataGroup(GroupSpec(0, "c", listOf(ChannelSpec("z")), 4), recordIdSize = 0)
        val file = open(writeFile(FileSpec(listOf(first, second))))

        assertEquals(2, file.groups.size)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), file.groups.map { g -> g.channelGroups.map { it.name } })
        assertEquals(listOf("a", "b", "c"), file.channelGroups.map { it.name })
        assertEquals(listOf(1, 0), file.groups.map { it.recordIdSize })
        assertFalse(file.groups[0].isSorted)
        assertTrue(file.groups[1].isSorted)
    }

    @Test
    fun aDataGroupMayHaveNoChannelGroupsAndAGroupNoChannels() {
        val file = open(writeFile(FileSpec(listOf(dataGroup(), dataGroup(GroupSpec(1, "empty", emptyList(), 0))))))

        assertTrue(file.groups[0].channelGroups.isEmpty())
        val group = file.groups[1].channelGroups.single()
        assertTrue(group.channels.isEmpty())
        assertNull(group.master)
    }

    @Test
    fun theMasterIsTheFirstMasterElseTheFirstVirtualMaster() {
        val time = ChannelSpec("t", DataTypes.FLOAT_LE, 64, type = ChannelTypes.MASTER, syncType = 1)
        val virtual = ChannelSpec("vt", DataTypes.UNSIGNED_LE, 0, type = ChannelTypes.VIRTUAL_MASTER)
        val groups = listOf(
            GroupSpec(1, "real", listOf(ChannelSpec("x"), time), 12),
            GroupSpec(2, "virtual", listOf(ChannelSpec("y"), virtual), 4),
            GroupSpec(3, "none", listOf(ChannelSpec("z")), 4),
        )
        val file = open(writeFile(FileSpec(listOf(dataGroup(*groups.toTypedArray())))))

        assertEquals(listOf("t", "vt", null), file.channelGroups.map { it.master?.name })
        assertEquals(MdfSyncType.Time, file.channelGroups[0].master?.syncType)
    }

    @Test
    fun aChannelsMembersAreItsStructure() {
        val members = listOf(ChannelSpec("a", DataTypes.UNSIGNED_LE, 8), ChannelSpec("b", DataTypes.UNSIGNED_LE, 8, byteOffset = 1))
        val structure = ChannelSpec("s", DataTypes.BYTE_ARRAY, 16, members = members)
        val file = open(writeFile(FileSpec(listOf(dataGroup(GroupSpec(1, "g", listOf(structure), 2))))))

        val channel = file.channelGroups.single().channels.single()
        assertEquals(listOf("a", "b"), channel.members.map { it.name })
        assertEquals(listOf(0, 1), channel.members.map { it.byteOffset })
        assertTrue(channel.members.all { it.members.isEmpty() })
    }

    @Test
    fun aVariableLengthChannelFindsItsGroupAmongTheDataGroupsOwn() {
        val values = GroupSpec(2, null, emptyList(), 0, flags = GroupFlags.VARIABLE_LENGTH)
        val text = ChannelSpec("s", DataTypes.STRING_UTF8, 64, type = ChannelTypes.VARIABLE_LENGTH, signalData = values)
        val file = open(writeFile(FileSpec(listOf(dataGroup(values, GroupSpec(1, "g", listOf(text), 8))))))

        assertSame(file.channelGroups[0], file.channelGroups[1].channels.single().signalDataGroup)
        assertTrue(file.channelGroups[0].isVlsd)
        assertEquals(MdfChannelKind.Vlsd, file.channelGroups[1].channels.single().kind)
    }

    @Test
    fun theFlagsOfAChannelAreReadAsProperties() {
        val flags = ChannelFlags.ALL_INVALID or ChannelFlags.BUS_EVENT
        val channels = listOf(ChannelSpec("a", flags = flags), ChannelSpec("b", invalidationBit = 3), ChannelSpec("c"))
        val file = open(writeFile(FileSpec(listOf(dataGroup(GroupSpec(1, "g", channels, 12, invalidationBytes = 1))))))

        val (a, b, c) = file.channelGroups.single().channels
        assertTrue(a.allValuesInvalid && a.isBusEvent)
        assertNull(a.invalidationBit)
        assertEquals(3, b.invalidationBit)
        assertTrue(b.hasInvalidationBit)
        assertFalse(c.allValuesInvalid || c.hasInvalidationBit || c.isBusEvent)
    }

    @Test
    fun anUnknownTypeOrDataTypeIsKeptAsUnknown() {
        val channel = ChannelSpec("odd", dataType = 99, type = 42, syncType = 9)
        val file = open(writeFile(FileSpec(listOf(dataGroup(GroupSpec(1, "g", listOf(channel), 4))))))

        val read = file.channelGroups.single().channels.single()
        assertEquals(MdfChannelKind.Unknown, read.kind)
        assertEquals(MdfSyncType.Unknown, read.syncType)
        assertEquals(MdfDataType.Unknown, read.dataType)
        assertFalse(read.isNumeric)
    }

    @Test
    fun theDataTypesKnowWhatTheyAre() {
        assertTrue(MdfDataType.UnsignedIntegerLittleEndian.isInteger && !MdfDataType.UnsignedIntegerLittleEndian.isSigned)
        assertTrue(MdfDataType.SignedIntegerBigEndian.isSigned && MdfDataType.SignedIntegerBigEndian.isBigEndian)
        assertTrue(MdfDataType.FloatBigEndian.isFloat && MdfDataType.FloatBigEndian.isNumber && !MdfDataType.FloatBigEndian.isInteger)
        assertTrue(MdfDataType.StringUtf16BigEndian.isString && MdfDataType.StringUtf16BigEndian.isBigEndian)
        assertFalse(MdfDataType.ByteArray.isNumber || MdfDataType.ByteArray.isString)
        assertEquals(18, MdfDataType.entries.size)
    }

    @Test
    fun aSourceKnowsItsTypeAndBus() {
        val source = SourceSpec("CAN", path = "CAN", type = 2, bus = 2)
        val file =
            open(writeFile(FileSpec(listOf(dataGroup(GroupSpec(1, "g", listOf(ChannelSpec("x", source = source)), 4, source = source))))))

        val read = file.channelGroups.single().source
        assertEquals("CAN", read?.name)
        assertEquals("CAN", read?.path)
        assertEquals(MdfSourceType.Bus, read?.type)
        assertEquals(MdfBusType.Can, read?.busType)
        assertSame(read, file.channelGroups.single().channels.single().source)
    }
}
