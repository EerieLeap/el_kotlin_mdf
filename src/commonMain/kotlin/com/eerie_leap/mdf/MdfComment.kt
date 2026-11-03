package com.eerie_leap.mdf

/**
 * What a comment says. A comment is plain text (a TX block) or XML (an MD block, as MDF 4 writes it: `<HDcomment>`,
 * `<CNcomment>`, ...). [parse] reads either with a forgiving scanner, not an XML parser: it finds the parts a reader wants and
 * ignores the rest.
 */
public class MdfComment internal constructor(
    /** The comment's text: a plain comment's whole text, or an XML comment's `<TX>`; empty where there is none. */
    public val text: String,
    /**
     * The `<e name="…">value</e>` entries of its `<common_properties>`, in file order. An entry inside a `<tree name="…">` is named
     * `tree/entry`.
     */
    public val properties: Map<String, String>,
    /**
     * Its other simple child elements, by name: a file history entry's `tool_id`, `tool_vendor` and `tool_version`, a header's
     * `time_source`, a channel's `formula`.
     */
    public val elements: Map<String, String>,
) {
    public val isEmpty: Boolean get() = text.isEmpty() && properties.isEmpty() && elements.isEmpty()

    override fun toString(): String = "MdfComment(text=$text, properties=$properties, elements=$elements)"

    public companion object {
        public val EMPTY: MdfComment = MdfComment("", emptyMap(), emptyMap())

        /** The text of a plain comment (a TX block): markup in it is text. */
        internal fun plain(text: String): MdfComment = if (text.isBlank()) EMPTY else MdfComment(text.trim(), emptyMap(), emptyMap())

        /** A plain comment, or an MD block's XML, read for its text, properties and elements. Never fails. */
        public fun parse(text: String): MdfComment {
            val start = text.indexOfFirst { !it.isWhitespace() && it.code != BYTE_ORDER_MARK }
            if (start < 0) return EMPTY
            if (text[start] != '<') return MdfComment(text.trim(), emptyMap(), emptyMap())
            return CommentScanner(text, start).scan() ?: MdfComment(text.trim(), emptyMap(), emptyMap())
        }

        private const val BYTE_ORDER_MARK = 0xFEFF
    }
}

private const val COMMON_PROPERTIES = "common_properties"
private const val PROPERTY = "e"
private const val TREE = "tree"
private const val TEXT = "TX"
private const val NAME_ATTRIBUTE = "name"
private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_BASE = 0x10000
private const val SURROGATE_BITS = 10
private const val HIGH_SURROGATE_BASE = 0xD800
private const val LOW_SURROGATE_BASE = 0xDC00
private const val LOW_SURROGATE_MASK = 0x3FF
private const val HEX_RADIX = 16
private const val DECIMAL_RADIX = 10
private const val MAX_ENTITY_NAME = 12

private class Frame(val name: String, val attribute: String?) {
    val text = StringBuilder()
    var hasChildren = false
}

// Reads the tags of an XML-like text and keeps what a comment is about; it never fails, and finds nothing in text that isn't XML.
private class CommentScanner(private val source: String, private var pos: Int) {
    private val stack = ArrayList<Frame>()
    private var text = ""
    private val properties = LinkedHashMap<String, String>()
    private val elements = LinkedHashMap<String, String>()
    private var completed = 0

    fun scan(): MdfComment? {
        while (pos < source.length) {
            val tag = source.indexOf('<', pos)
            if (tag < 0) {
                appendText(source.substring(pos))
                break
            }
            if (tag > pos) appendText(source.substring(pos, tag))
            pos = tag
            readMarkup()
        }
        return if (completed > 0) MdfComment(text, properties, elements) else null
    }

    private fun readMarkup() {
        when {
            source.startsWith("<!--", pos) -> skipPast("-->")
            source.startsWith("<![CDATA[", pos) -> readCharacterData()
            source.startsWith("<?", pos) -> skipPast("?>")
            source.startsWith("<!", pos) -> skipPast(">")
            source.startsWith("</", pos) -> readClosingTag()
            else -> readOpeningTag()
        }
    }

    private fun skipPast(end: String) {
        val found = source.indexOf(end, pos)
        pos = if (found < 0) source.length else found + end.length
    }

    private fun readCharacterData() {
        val begin = pos + "<![CDATA[".length
        val end = source.indexOf("]]>", begin).let { if (it < 0) source.length else it }
        stack.lastOrNull()?.text?.append(source, begin, end)
        pos = minOf(source.length, end + "]]>".length)
    }

    private fun readClosingTag() {
        val end = source.indexOf('>', pos).let { if (it < 0) source.length else it }
        val name = source.substring(pos + "</".length, end).trim()
        pos = minOf(source.length, end + 1)
        val index = stack.indexOfLast { it.name == name }
        if (index < 0) return
        while (stack.size > index + 1) close()
        close()
    }

    private fun readOpeningTag() {
        val end = findTagEnd()
        val isEmpty = end > pos && source[end - 1] == '/'
        val inside = source.substring(pos + 1, if (isEmpty) end - 1 else end)
        pos = minOf(source.length, end + 1)
        val nameEnd = inside.indexOfFirst { it.isWhitespace() }.let { if (it < 0) inside.length else it }
        val name = inside.substring(0, nameEnd)
        if (name.isEmpty()) return
        stack.lastOrNull()?.hasChildren = true
        stack.add(Frame(name, attributeOf(inside.substring(nameEnd), NAME_ATTRIBUTE)))
        if (isEmpty) close()
    }

    // The `>` that ends the tag at pos, skipping any inside a quoted attribute value.
    private fun findTagEnd(): Int {
        var quote = NO_QUOTE
        var i = pos + 1
        while (i < source.length) {
            val c = source[i]
            when {
                quote != NO_QUOTE -> if (c == quote) quote = NO_QUOTE
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
            }
            i++
        }
        return source.length
    }

    private class Attribute(val name: String, val value: String, val end: Int)

    private fun attributeOf(attributes: String, wanted: String): String? {
        var attribute = nextAttribute(attributes, 0)
        while (attribute != null && attribute.name != wanted) attribute = nextAttribute(attributes, attribute.end)
        return attribute?.value
    }

    private fun nextAttribute(attributes: String, from: Int): Attribute? {
        val equals = attributes.indexOf('=', from)
        if (equals < 0) return null
        val open = skipSpaces(attributes, equals + 1)
        val quote = attributes.getOrNull(open)
        val close = if (quote == '"' || quote == '\'') attributes.indexOf(quote, open + 1) else -1
        if (close < 0) return null
        return Attribute(attributes.substring(from, equals).trim(), decode(attributes.substring(open + 1, close)), close + 1)
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun appendText(raw: String) {
        stack.lastOrNull()?.text?.append(decode(raw))
    }

    private fun close() {
        val frame = stack.removeAt(stack.size - 1)
        completed++
        val parent = stack.lastOrNull()
        val value = frame.text.toString().trim()
        when {
            frame.name == TEXT && stack.size <= 1 -> text = value
            frame.name == PROPERTY && frame.attribute != null && inProperties() -> properties[propertyName(frame.attribute)] = value
            stack.size == 1 && !frame.hasChildren && frame.name != COMMON_PROPERTIES -> elements[frame.name] = value
        }
        if (parent == null) pos = source.length
    }

    private fun inProperties(): Boolean = stack.any { it.name == COMMON_PROPERTIES }

    private fun propertyName(name: String): String {
        val trees = stack.filter { it.name == TREE }.mapNotNull { it.attribute }
        return (trees + name).joinToString("/")
    }

    private companion object {
        const val NO_QUOTE = ' '
    }
}

/** [text] with the XML entities replaced: `&amp; &lt; &gt; &quot; &apos;` and numeric ones, `&#65;` and `&#x41;`. */
internal fun decode(text: String): String {
    if (text.indexOf('&') < 0) return text
    val result = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val end = if (text[i] == '&') semicolonAfter(text, i) else -1
        val entity = if (end > i) entityValue(text.substring(i + 1, end)) else null
        if (entity == null) {
            result.append(text[i])
            i++
        } else {
            result.append(entity)
            i = end + 1
        }
    }
    return result.toString()
}

// The `;` that ends an entity begun by the `&` at [ampersand], looked for only as far as an entity's name goes: a text of nothing
// but ampersands would otherwise be read to its end at each of them.
private fun semicolonAfter(text: String, ampersand: Int): Int {
    val limit = minOf(text.length, ampersand + MAX_ENTITY_NAME + 1)
    for (at in ampersand + 1 until limit) {
        if (text[at] == ';') return at
    }
    return -1
}

private fun entityValue(name: String): String? = when (name) {
    "amp" -> "&"
    "lt" -> "<"
    "gt" -> ">"
    "quot" -> "\""
    "apos" -> "'"
    else -> numericEntity(name)
}

private fun numericEntity(name: String): String? {
    if (!name.startsWith("#")) return null
    val hex = name.startsWith("#x") || name.startsWith("#X")
    val code = name.substring(if (hex) 2 else 1).toIntOrNull(if (hex) HEX_RADIX else DECIMAL_RADIX)
    return when {
        code == null || code <= 0 || code > MAX_CODE_POINT -> null

        code < SUPPLEMENTARY_BASE -> code.toChar().toString()

        else -> {
            val offset = code - SUPPLEMENTARY_BASE
            val high = HIGH_SURROGATE_BASE + (offset shr SURROGATE_BITS)
            val low = LOW_SURROGATE_BASE + (offset and LOW_SURROGATE_MASK)
            "${high.toChar()}${low.toChar()}"
        }
    }
}
