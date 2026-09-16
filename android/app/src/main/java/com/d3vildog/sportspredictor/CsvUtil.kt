package com.d3vildog.sportspredictor

/**
 * Minimal RFC4180-ish CSV parser: handles quoted fields (needed because
 * some nflverse columns, e.g. headshot_url, contain literal commas inside
 * quotes) without pulling in a full CSV library dependency.
 */
object CsvUtil {

    fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    class Table(val header: List<String>, val rows: List<List<String>>) {
        private val index: Map<String, Int> = header.withIndex().associate { (i, name) -> name to i }

        fun col(row: List<String>, name: String): String {
            val i = index[name] ?: return ""
            return if (i < row.size) row[i] else ""
        }
    }

    /** Parses a full CSV body (with header row) from a sequence of lines. */
    fun parse(lines: Sequence<String>): Table {
        val iter = lines.iterator()
        if (!iter.hasNext()) return Table(emptyList(), emptyList())
        val header = parseLine(iter.next())
        val rows = mutableListOf<List<String>>()
        while (iter.hasNext()) {
            val line = iter.next()
            if (line.isBlank()) continue
            rows.add(parseLine(line))
        }
        return Table(header, rows)
    }
}
