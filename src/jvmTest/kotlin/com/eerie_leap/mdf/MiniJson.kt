package com.eerie_leap.mdf

/** A reader of JSON for the fixtures' file (the tests have no JSON library): objects, arrays, strings, numbers, booleans, null. */
internal class MiniJson private constructor(private val text: String) {
    private var pos = 0

    private fun value(): Any? {
        skipSpaces()
        return when (val c = text[pos]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $pos")
        }
    }

    private fun obj(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        pos++
        skipSpaces()
        if (text[pos] == '}') return result.also { pos++ }
        while (true) {
            skipSpaces()
            val key = string()
            skipSpaces()
            check(text[pos++] == ':') { "a ':' is missing at $pos" }
            result[key] = value()
            skipSpaces()
            val next = text[pos++]
            if (next == '}') return result
            check(next == ',') { "a ',' or '}' is missing at $pos" }
        }
    }

    private fun array(): List<Any?> {
        val result = ArrayList<Any?>()
        pos++
        skipSpaces()
        if (text[pos] == ']') return result.also { pos++ }
        while (true) {
            result.add(value())
            skipSpaces()
            val next = text[pos++]
            if (next == ']') return result
            check(next == ',') { "a ',' or ']' is missing at $pos" }
        }
    }

    private fun string(): String {
        check(text[pos++] == '"') { "a string begins at $pos" }
        val out = StringBuilder()
        while (text[pos] != '"') {
            val c = text[pos++]
            if (c != '\\') {
                out.append(c)
            } else {
                when (val escaped = text[pos++]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> out.append(text.substring(pos, pos + 4).toInt(16).toChar()).also { pos += 4 }
                    else -> out.append(escaped)
                }
            }
        }
        pos++
        return out.toString()
    }

    private fun number(): Any {
        val begin = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] in "+-.eE")) pos++
        val token = text.substring(begin, pos)
        return if (token.any { it in ".eE" }) token.toDouble() else token.toLong()
    }

    private fun literal(word: String, result: Any?): Any? {
        check(text.startsWith(word, pos)) { "expected $word at $pos" }
        pos += word.length
        return result
    }

    private fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    companion object {
        fun parse(text: String): Any? = MiniJson(text).value()
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?> = this as List<Any?>

internal fun Any?.double(): Double = (this as Number).toDouble()

internal fun Any?.long(): Long = (this as Number).toLong()

internal fun Any?.int(): Int = (this as Number).toInt()

internal fun Any?.text(): String = this as String
