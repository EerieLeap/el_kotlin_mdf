package com.eerie_leap.mdf

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * An algebraic conversion's formula (`cc_type` 3), read and evaluated in `Double`. It takes what the Eerie Leap firmware writes
 * (and every reader evaluates alike): the value `X` (or `x`), decimal numbers with a fraction or an exponent, `+ - * /`, a unary
 * minus, parentheses, and the functions `sin cos tan sqrt abs log`, `log` being the natural logarithm, as numexpr (asammdf) and the
 * unit's expression engine both read it. Anything else (`X1`, `^`, `exp`, ...) is [Rejected], and the conversion leaves the values
 * as stored.
 */
internal class MdfFormula private constructor(private val root: Node) {
    fun evaluate(x: Double): Double = root.evaluate(x)

    sealed interface Result

    class Parsed(val formula: MdfFormula) : Result

    class Rejected(val reason: String) : Result

    companion object {
        private const val MAX_DEPTH = 64

        private val FUNCTIONS: Map<String, (Double) -> Double> = mapOf(
            "sin" to { v -> sin(v) },
            "cos" to { v -> cos(v) },
            "tan" to { v -> tan(v) },
            "sqrt" to { v -> sqrt(v) },
            "abs" to { v -> abs(v) },
            "log" to { v -> ln(v) },
        )

        fun parse(text: String): Result = try {
            Parsed(MdfFormula(FormulaParser(text).parse()))
        } catch (e: FormulaException) {
            Rejected(e.message.orEmpty())
        }
    }

    private fun interface Node {
        fun evaluate(x: Double): Double
    }

    private class FormulaException(message: String) : Exception(message)

    private class FormulaParser(private val text: String) {
        private var pos = 0
        private var depth = 0

        fun parse(): Node {
            val node = expression()
            skipSpaces()
            if (pos < text.length) fail("unexpected '${text[pos]}'")
            return node
        }

        private fun fail(reason: String): Nothing = throw FormulaException("$reason at position $pos in \"$text\"")

        private fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return text.getOrNull(pos)
        }

        private fun expression(): Node {
            var left = term()
            while (true) {
                val operator = peek()
                if (operator != '+' && operator != '-') return left
                pos++
                val right = term()
                val l = left
                left =
                    if (operator ==
                        '+'
                    ) {
                        Node { x -> l.evaluate(x) + right.evaluate(x) }
                    } else {
                        Node { x -> l.evaluate(x) - right.evaluate(x) }
                    }
            }
        }

        private fun term(): Node {
            var left = unary()
            while (true) {
                val operator = peek()
                if (operator != '*' && operator != '/') return left
                pos++
                val right = unary()
                val l = left
                left =
                    if (operator ==
                        '*'
                    ) {
                        Node { x -> l.evaluate(x) * right.evaluate(x) }
                    } else {
                        Node { x -> l.evaluate(x) / right.evaluate(x) }
                    }
            }
        }

        private fun unary(): Node {
            val sign = peek()
            if (sign != '-' && sign != '+') return primary()
            pos++
            val operand = nested { unary() }
            return if (sign == '-') Node { x -> -operand.evaluate(x) } else operand
        }

        // Parentheses, calls and signs nest only so far, so that a hostile formula can't overflow the stack.
        private fun nested(parse: () -> Node): Node {
            if (++depth > MAX_DEPTH) fail("the formula nests more than $MAX_DEPTH levels deep")
            val node = parse()
            depth--
            return node
        }

        private fun primary(): Node {
            val c = peek() ?: fail("the formula ends where a value is expected")
            return when {
                c == '(' -> parenthesized()
                c.isDigit() || c == '.' -> number()
                c.isLetter() || c == '_' -> identifier()
                else -> fail("unexpected '$c'")
            }
        }

        private fun parenthesized(): Node {
            pos++
            val inner = nested { expression() }
            if (peek() != ')') fail("a ')' is missing")
            pos++
            return inner
        }

        private fun number(): Node {
            val begin = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) readExponent()
            val value = text.substring(begin, pos).toDoubleOrNull() ?: fail("'${text.substring(begin, pos)}' isn't a number")
            return Node { value }
        }

        // An exponent (`e5`, `E-3`) belongs to the number only when digits follow it.
        private fun readExponent() {
            var end = pos + 1
            if (end < text.length && (text[end] == '+' || text[end] == '-')) end++
            if (end < text.length && text[end].isDigit()) {
                while (end < text.length && text[end].isDigit()) end++
                pos = end
            }
        }

        private fun identifier(): Node {
            val begin = pos
            while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
            val name = text.substring(begin, pos)
            if (peek() == '(') return call(name)
            if (name == "X" || name == "x") return Node { x -> x }
            fail("'$name' isn't the value X")
        }

        private fun call(name: String): Node {
            val function = FUNCTIONS[name] ?: fail("'$name' isn't a function this reader evaluates")
            pos++
            val argument = nested { expression() }
            if (peek() != ')') fail("a ')' is missing")
            pos++
            return Node { x -> function(argument.evaluate(x)) }
        }
    }
}
