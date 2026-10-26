package mihon.feature.translation.core.vision

/**
 * Reads the `character_dict` list out of a PaddleOCR `inference.yml`.
 *
 * Only the subset of YAML that PaddleOCR emits is handled: one `  - value` per line where the
 * value is bare, single-quoted (`''` escapes a quote) or double-quoted (backslash escapes).
 */
object PaddleDictionary {

    fun parse(yaml: String): List<String> {
        val lines = yaml.lineSequence().iterator()
        var inList = false
        var indent = -1
        val out = ArrayList<String>(20_000)
        while (lines.hasNext()) {
            val line = lines.next()
            if (!inList) {
                if (line.trimEnd() == "character_dict:" || line.trim() == "character_dict:") inList = true
                continue
            }
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("- ") && trimmed != "-") {
                // End of the list (next key or end of block).
                if (trimmed.isEmpty()) continue
                break
            }
            val currentIndent = line.length - trimmed.length
            if (indent == -1) indent = currentIndent
            if (currentIndent != indent) break
            out += unquote(trimmed.removePrefix("-").removePrefix(" "))
        }
        return out
    }

    fun unquote(raw: String): String {
        if (raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'') {
            return raw.substring(1, raw.length - 1).replace("''", "'")
        }
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            return unescapeDoubleQuoted(raw.substring(1, raw.length - 1))
        }
        return raw
    }

    private fun unescapeDoubleQuoted(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c)
                i++
                continue
            }
            val n = s[i + 1]
            when (n) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '0' -> sb.append('\u0000')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                ' ' -> sb.append(' ')
                'x' -> {
                    sb.append(s.substring(i + 2, i + 4).toInt(16).toChar())
                    i += 2
                }
                'u' -> {
                    sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                    i += 4
                }
                'U' -> {
                    sb.appendCodePoint(s.substring(i + 2, i + 10).toInt(16))
                    i += 8
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }
}
