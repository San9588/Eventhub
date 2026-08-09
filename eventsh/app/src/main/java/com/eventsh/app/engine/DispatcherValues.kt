package com.eventsh.app.engine

import android.content.Context

/**
 * Pure value / logic helpers shared by the task runner and the action executor:
 * If-condition evaluation, For-loop value lists, 1-based array persistence and
 * the For-loop frame. No Android side effects - everything here is about
 * strings, numbers and in-memory maps.
 */
internal fun evalCondition(expr: String, vars: Map<String, String>): Boolean {
    val s = expr.trim()
    if (s.isEmpty()) return true
    var bestOp: String? = null
    var bestIdx = -1
    for (op in arrayOf("!~", "~", ">=", "<=", "!=", "==", "=", ">", "<")) {
        val idx = s.indexOf(op)
        if (idx > 0 && (bestIdx < 0 || idx < bestIdx)) {
            bestIdx = idx
            bestOp = op
        }
    }
    if (bestOp != null) {
        val l = Vars.resolve(s.substring(0, bestIdx).trim(), vars)
        val r = Vars.resolve(s.substring(bestIdx + bestOp.length).trim(), vars)
        return when (bestOp) {
            "=", "==" -> l == r
            "!=" -> l != r
            ">", ">=", "<", "<=" -> {
                val a = l.toDoubleOrNull()
                val b = r.toDoubleOrNull()
                if (a == null || b == null) false
                else when (bestOp) {
                    ">" -> a > b
                    ">=" -> a >= b
                    "<" -> a < b
                    else -> a <= b
                }
            }
            "~" -> ContextGate.matchPattern(r, l)
            else -> !ContextGate.matchPattern(r, l)
        }
    }
    return Vars.resolve(s, vars).isNotBlank()
}

/**
 * Parses a value spec into a list. Supports "1..5" (range, forward or
 * backward), "a,b,c" (comma list) and "%arr" (1-based array elements).
 * %VAR% references inside a plain spec are resolved first.
 */
internal fun parseValueList(spec: String, vars: Map<String, String>): List<String> {
    val raw = spec.trim()
    if (raw.isBlank()) return emptyList()
    if (raw.startsWith("%")) {
        val base = raw.removePrefix("%").trim()
        val out = mutableListOf<String>()
        var i = 1
        while (out.size < 1000) {
            val v = vars[base + i]
            if (v == null) break
            out.add(v)
            i++
        }
        return out
    }
    val resolved = Vars.resolve(raw, vars).trim()
    val range = Regex("^(-?\\d+)\\.\\.(-?\\d+)$").find(resolved)
    if (range != null) {
        val from = range.groupValues[1].toInt()
        val to = range.groupValues[2].toInt()
        val step = if (from <= to) 1 else -1
        val out = mutableListOf<String>()
        var v = from
        var guard = 0
        while (if (step > 0) v <= to else v >= to) {
            out.add(v.toString())
            if (++guard > 100000) break
            v += step
        }
        return out
    }
    return resolved.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/** Reads a 1-based array %name1..%nameN (stops at the first gap). */
internal fun readArray(ctx: Context, vars: Map<String, String>, name: String): List<String> {
    val out = mutableListOf<String>()
    var i = 1
    while (i <= 10000) {
        val v = vars[name + i] ?: UserVars.get(ctx, name + i) ?: break
        out.add(v)
        i++
    }
    return out
}

/** Writes a 1-based array, keeps the base var in sync and clears leftovers. */
internal fun writeArray(ctx: Context, vars: MutableMap<String, String>, name: String, list: List<String>) {
    list.forEachIndexed { i, v ->
        val k = name + (i + 1)
        UserVars.set(ctx, k, v)
        vars[k] = v
    }
    var i = list.size + 1
    while (i <= list.size + 1000) {
        val k = name + i
        val inVars = vars.remove(k)
        val inDisk = UserVars.get(ctx, k)
        if (inVars == null && inDisk == null) break
        if (inDisk != null) UserVars.remove(ctx, k)
        i++
    }
    val base = list.joinToString(",")
    UserVars.set(ctx, name, base)
    vars[name] = base
}

/** A running For loop: start action index + remaining values + loop variable. */
internal data class ForFrame(val start: Int, val iter: Iterator<String>, val varName: String)
