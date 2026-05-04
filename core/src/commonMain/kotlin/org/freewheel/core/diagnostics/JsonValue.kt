package org.freewheel.core.diagnostics

/**
 * Minimal typed value for diagnostic event context maps. Hand-rolled because
 * the project has no kotlinx.serialization dependency and the schema is small.
 * The diagnostics pipeline only encodes — never decodes — so this is write-only.
 */
sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()

    companion object {
        fun of(value: String?): JsonValue = value?.let(::Str) ?: Null
        fun of(value: Int): JsonValue = Num(value.toDouble())
        fun of(value: Long): JsonValue = Num(value.toDouble())
        fun of(value: Double): JsonValue = Num(value)
        fun of(value: Boolean): JsonValue = Bool(value)
    }
}

internal fun JsonValue.encode(out: StringBuilder) {
    when (this) {
        is JsonValue.Str -> encodeString(value, out)
        is JsonValue.Num -> encodeNumber(value, out)
        is JsonValue.Bool -> out.append(if (value) "true" else "false")
        is JsonValue.Null -> out.append("null")
    }
}

internal fun encodeString(s: String, out: StringBuilder) {
    out.append('"')
    for (c in s) {
        when (c.code) {
            0x5C -> out.append("\\\\")
            0x22 -> out.append("\\\"")
            0x0A -> out.append("\\n")
            0x0D -> out.append("\\r")
            0x09 -> out.append("\\t")
            0x08 -> out.append("\\b")
            0x0C -> out.append("\\f")
            else -> {
                if (c.code < 0x20) {
                    out.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { out.append('0') }
                    out.append(hex)
                } else {
                    out.append(c)
                }
            }
        }
    }
    out.append('"')
}

/**
 * Format a Double as a JSON number, locale-independent and without a
 * trailing ".0" for integral values within ±1e15.
 */
internal fun encodeNumber(value: Double, out: StringBuilder) {
    if (value.isNaN() || value.isInfinite()) {
        out.append("null")
        return
    }
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
        out.append(value.toLong().toString())
    } else {
        out.append(value.toString())
    }
}
