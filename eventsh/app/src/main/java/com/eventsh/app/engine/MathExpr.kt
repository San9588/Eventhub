package com.eventsh.app.engine

/**
 * Tiny arithmetic evaluator used by Variable Set.
 * After %VAR% references are resolved, the resulting string is evaluated as
 * math when it is a pure numeric expression. Supports:
 *   + - * / %  ^  parentheses  unary minus  decimal numbers
 */
object MathExpr {
    /**
     * Parses [expr] as a complete math expression and returns the numeric
     * result, or null when it is empty / malformed / not pure math.
     */
    fun eval(expr: String): Double? = try {
        val p = Parser(expr)
        val v = p.parseExpr()
        if (p.isDone) v else null
    } catch (e: Exception) {
        null
    }

    /**
     * Returns [expr] evaluated as a number when it looks like math, otherwise
     * null so callers keep the raw string. Whole results are printed without
     * a trailing ".0".
     */
    fun tryEval(expr: String): String? {
        val s = expr.trim()
        if (s.isEmpty()) return null
        if (!s.any { it in "+-*/%^()" }) return null
        val d = eval(s) ?: return null
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            return d.toLong().toString()
        }
        return try {
            java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString()
        } catch (e: Exception) {
            d.toString()
        }
    }

    /** Recursive-descent parser: expr := term (('+'|'-') term)* etc. */
    private class Parser(private val s: String) {
        private var pos = 0

        val isDone: Boolean get() {
            skipWs()
            return pos >= s.length
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                if (pos >= s.length) return v
                when (s[pos]) {
                    '+' -> {
                        pos++
                        v += parseTerm()
                    }
                    '-' -> {
                        pos++
                        v -= parseTerm()
                    }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                if (pos >= s.length) return v
                when (s[pos]) {
                    '*' -> {
                        pos++
                        v *= parseFactor()
                    }
                    '/' -> {
                        pos++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("div by zero")
                        v /= d
                    }
                    '%' -> {
                        pos++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("mod by zero")
                        v %= d
                    }
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            skipWs()
            if (pos >= s.length) throw IllegalArgumentException("expected number")
            when (s[pos]) {
                '-' -> {
                    pos++
                    return -parseFactor()
                }
                '+' -> {
                    pos++
                    return parseFactor()
                }
                '(' -> {
                    pos++
                    val v = parseExpr()
                    skipWs()
                    if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("missing )")
                    pos++
                    return pow(v)
                }
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("bad token")
            return pow(s.substring(start, pos).toDouble())
        }

        /** Right-associative power operator. */
        private fun pow(v: Double): Double {
            skipWs()
            if (pos < s.length && s[pos] == '^') {
                pos++
                return Math.pow(v, parseFactor())
            }
            return v
        }
    }
}
