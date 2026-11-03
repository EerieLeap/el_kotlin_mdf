package com.eerie_leap.mdf

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MdfFormulaTest {
    private fun evaluate(formula: String, x: Double): Double {
        val parsed = assertIs<MdfFormula.Parsed>(MdfFormula.parse(formula), formula)
        return parsed.formula.evaluate(x)
    }

    private fun assertRejected(formula: String) {
        val result = MdfFormula.parse(formula)
        assertTrue(result is MdfFormula.Rejected, "\"$formula\" should be rejected but gave $result")
        assertTrue(result.reason.isNotEmpty(), formula)
    }

    @Test
    fun evaluatesTheValue() {
        assertEquals(3.5, evaluate("X", 3.5))
        assertEquals(3.5, evaluate("x", 3.5))
    }

    @Test
    fun evaluatesTheFourOperatorsWithTheirPrecedence() {
        assertEquals(14.0, evaluate("2 + 3 * 4", 0.0))
        assertEquals(20.0, evaluate("(2 + 3) * 4", 0.0))
        assertEquals(1.0, evaluate("10 - 6 - 3", 0.0))
        assertEquals(1.0, evaluate("12 / 4 / 3", 0.0))
        assertEquals(7.0, evaluate("X * 2 + 1", 3.0))
        assertEquals(10.0, evaluate("(X * X) + 1", 3.0))
    }

    @Test
    fun evaluatesUnaryMinusAndPlus() {
        assertEquals(-3.0, evaluate("-X", 3.0))
        assertEquals(3.0, evaluate("+X", 3.0))
        assertEquals(-6.0, evaluate("2 * -X", 3.0))
        assertEquals(4.0, evaluate("1 - -X", 3.0))
        assertEquals(3.0, evaluate("--X", 3.0))
        assertEquals(-7.0, evaluate("-(X + 4)", 3.0))
    }

    @Test
    fun readsDecimalNumbersWithFractionsAndExponents() {
        assertEquals(0.5, evaluate(".5", 0.0))
        assertEquals(5.0, evaluate("5.", 0.0))
        assertEquals(0.015, evaluate("1.5e-2", 0.0), 1e-15)
        assertEquals(1500.0, evaluate("1.5E+3", 0.0))
        assertEquals(100.0, evaluate("1e2", 0.0))
        assertEquals(3.0, evaluate("X * 1e0", 3.0))
    }

    @Test
    fun evaluatesTheFunctionsTheFirmwareWrites() {
        assertEquals(sin(1.25), evaluate("sin(X)", 1.25), 0.0)
        assertEquals(cos(1.25), evaluate("cos(X)", 1.25), 0.0)
        assertEquals(tan(1.25), evaluate("tan(X)", 1.25), 0.0)
        assertEquals(sqrt(2.0), evaluate("sqrt(X)", 2.0), 0.0)
        assertEquals(2.5, evaluate("abs(X)", -2.5), 0.0)
        // log is the natural logarithm.
        assertEquals(ln(10.0), evaluate("log(X)", 10.0), 0.0)
        assertEquals(1.0, evaluate("log(2.718281828459045)", 0.0), 1e-15)
    }

    @Test
    fun evaluatesTheFirmwaresOwnFormulas() {
        // conversions.mf4's formulas, as ToMdfFormula writes them.
        val x = 0.8
        assertEquals(sqrt(x) * 2 + 1, evaluate("sqrt(X) * 2 + 1", x), 0.0)
        assertEquals(x * x + 3, evaluate("(X * X) + 3", x), 0.0)
        assertEquals(
            kotlin.math.abs(x) - ln(x) + sin(x) * cos(x) + tan(x) / 10 + sqrt(x),
            evaluate("abs(X) - log(X) + sin(X) * cos(X) + tan(X) / 10 + sqrt(X)", x),
            1e-15,
        )
        assertEquals(PI, evaluate("X", PI))
    }

    @Test
    fun followsIeeeForDivisionByZeroAndRoots() {
        assertEquals(Double.POSITIVE_INFINITY, evaluate("1 / X", 0.0))
        assertTrue(evaluate("0 / X", 0.0).isNaN())
        assertTrue(evaluate("sqrt(X)", -1.0).isNaN())
        assertTrue(evaluate("log(X)", -1.0).isNaN())
    }

    @Test
    fun ignoresSpaces() {
        assertEquals(7.0, evaluate("  X*2 +   1 ", 3.0))
        assertEquals(sqrt(4.0), evaluate("sqrt (X)", 4.0), 0.0)
    }

    @Test
    fun rejectsWhatItDoesNotEvaluate() {
        for (formula in listOf("X1", "x1", "X ^ 2", "X ** 2", "X ^ 2 + 1", "exp(X)", "pow(X, 2)", "max(X, 1)", "pi * X", "Y", "X(2)")) {
            assertRejected(formula)
        }
    }

    @Test
    fun rejectsMalformedFormulas() {
        val malformed =
            listOf("", "   ", "X +", "* X", "(X", "X)", "()", "X X", "2X", "1.2.3", "1e", "X $ 2", "sin X", "sin(", "sin()", "X / / 2")
        malformed.forEach { assertRejected(it) }
    }

    @Test
    fun rejectsAFormulaThatNestsAbsurdlyDeep() {
        assertRejected("(".repeat(200) + "X" + ")".repeat(200))
        assertRejected("-".repeat(200) + "X")
    }

    @Test
    fun acceptsNestingWithinTheLimit() {
        assertEquals(3.0, evaluate("(".repeat(30) + "X" + ")".repeat(30), 3.0))
    }
}
