package com.eerie_leap.mdf

/** What a node of the channel hierarchy (CH) is (`ch_type`). */
public enum class MdfHierarchyType(public val code: Int) {
    /** A group of channels under a common name. */
    Group(0),
    Function(1),
    Structure(2),
    MapList(3),
    InputVariables(4),
    OutputVariables(5),
    LocalVariables(6),
    CalibrationDefinition(7),
    CalibrationReferences(8),
    Unknown(-1),
    ;

    internal companion object {
        fun of(code: Int): MdfHierarchyType = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** A channel of a hierarchy node: the channel group it is in and the channel itself (none where the node names the group alone). */
public class MdfHierarchyMember internal constructor(public val group: MdfChannelGroup?, public val channel: MdfChannel?) {
    override fun toString(): String = "MdfHierarchyMember(${group?.name}, ${channel?.name})"
}

/**
 * A node of the channel hierarchy (CH) as its writer grouped the channels: a [name], what it [type] is, the [members] it names and
 * the [children] it holds. A channel list can be grouped by it.
 */
public class MdfHierarchyNode internal constructor(
    /** Where the node's block is in the file. */
    public val offset: Long,
    public val name: String,
    public val type: MdfHierarchyType,
    public val comment: MdfComment,
    public val members: List<MdfHierarchyMember>,
    public val children: List<MdfHierarchyNode>,
) {
    override fun toString(): String = "MdfHierarchyNode($name, $type, ${members.size} members, ${children.size} children)"
}
