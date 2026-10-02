package com.mostlygoodmetrics.sdk

/** Bounds persisted/remote input before recursive JSON parsers allocate objects. */
internal object JsonSafety {
    const val MAX_CACHE_BYTES = 1024 * 1024
    const val MAX_METADATA_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 16

    fun isSafe(input: String, maxBytes: Int = MAX_METADATA_BYTES): Boolean {
        if (input.length > maxBytes) return false
        var bytes = 0
        var depth = 0
        var nodes = 0
        var inString = false
        var escaped = false
        for (char in input) {
            // Count a conservative UTF-8 upper bound without allocating a copy.
            bytes += when { char.code < 128 -> 1; char.code < 2048 -> 2; else -> 3 }
            if (bytes > maxBytes) return false
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '[', '{' -> { if (++depth > MAX_DEPTH || ++nodes > 65_536) return false }
                ',', ':' -> if (++nodes > 65_536) return false
                ']', '}' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !inString
    }
}
