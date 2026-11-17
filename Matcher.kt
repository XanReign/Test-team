package app.osmsquad

import java.text.Normalizer

/** Oyuncuyu yalnızca adla değil; ad + yaş + pozisyon + reyting ile eşleştirir. */
object Matcher {
    private val MARKS = Regex("\\p{M}")
    private val NONLET = Regex("[^a-z]")

    fun n(s: String): String = Normalizer.normalize(s.replace('ı', 'i').replace('İ', 'I'), Normalizer.Form.NFD)
        .replace(MARKS, "").lowercase().replace(NONLET, "")

    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var p = d[0]; d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, p + if (a[i - 1] == b[j - 1]) 0 else 1)
                p = t
            }
        }
        return d[b.length]
    }

    fun nameOk(a: String, b: String): Boolean {
        val x = n(a); val y = n(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // OCR can confuse one character even in short names (e.g. Epolo/EpoIo).
        // Age + position + rating are checked separately before a row is accepted.
        return x == y || (minOf(x.length, y.length) >= 4 && lev(x, y) <= 1)
    }

    private fun nameAppears(expected: String, text: String): Boolean {
        val target = n(expected)
        val tokens = Regex("[\\p{L}]+")
            .findAll(text)
            .map { n(it.value) }
            .filter { it.isNotEmpty() }
            .toList()
        if (target.isEmpty()) return false
        if (tokens.any { nameOk(target, it) }) return true
        val expectedParts = expected.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (expectedParts.size > 1 && tokens.size >= expectedParts.size) {
            val width = expectedParts.size
            for (i in 0..tokens.size - width) {
                if (nameOk(n(expectedParts.joinToString("") ), tokens.subList(i, i + width).joinToString(""))) return true
            }
        }
        return n(text).contains(target)
    }

    /**
     * Target-guided OCR fallback. OSM sometimes renders a player's name/age and
     * table cells as separate OCR lines, so the legacy whole-row regex can miss
     * a visible player. We anchor on the requested name, collect nearby lines,
     * then require the exact age and position. Outfield rows must also contain
     * the requested rating in the correct H/D/O stat column.
     */
    fun matchOcr(t: AutoPlayer, lines: List<Ln>, screenHeight: Int): List<Row> {
        val band = maxOf(28, (screenHeight * 0.065f).toInt())
        val candidates = linkedMapOf<Int, Row>()
        val anchors = lines.filter { nameAppears(t.name, it.text) }
        for (anchor in anchors) {
            val y = anchor.box.centerY()
            val near = lines.filter { kotlin.math.abs(it.box.centerY() - y) <= band }
            val combined = near.sortedWith(compareBy<Ln> { it.box.centerY() }.thenBy { it.box.left })
                .joinToString(" ") { it.text }
            if (!nameAppears(t.name, combined)) continue
            val rowY = near.map { it.box.centerY() }.average().toInt()

            val ageTokens = Regex("\\d{1,2}")
                .findAll(combined)
                .mapNotNull { it.value.toIntOrNull() }
                .toList()
            if (t.age !in ageTokens) continue

            val posTokens = Regex("[\\p{L}]+")
                .findAll(combined)
                .map { Parser.pos(it.value) }
                .toList()
            if (posTokens.none { canon(it) == canon(t.pos) }) continue

            val fallbackRow = if (t.col == "k") {
                // OSM goalkeeper row exposes a KL position and outfield stat columns;
                // the keeper rating itself is not represented as H/D/O.
                Row(t.name, t.age, "", t.pos, 0, 0, 0, rowY)
            } else {
                val positionMatch = Regex(
                    "(?i)(?<![A-ZĞÜİŞÇ])(?:KLC|KL|SLB|STP|SĞB|SGB|OS|OOS|SLA|SF|SĞA|SGA)(?![A-ZĞÜİŞÇ])"
                ).find(combined) ?: continue
                val suffix = combined.substring(positionMatch.range.last + 1)
                val values = Regex("(?<!\\d)\\d{1,3}(?!\\d)")
                    .findAll(suffix)
                    .mapNotNull { it.value.toIntOrNull() }
                    .toList()
                val index = when (t.col) { "h" -> 0; "d" -> 1; "o" -> 2; else -> -1 }
                if (index !in values.indices || values[index] != t.rating) continue
                val stats = values.take(3)
                Row(t.name, t.age, "", t.pos,
                    stats.getOrElse(0) { 0 }, stats.getOrElse(1) { 0 }, stats.getOrElse(2) { 0 }, rowY)
            }
            // Collapse multiple OCR anchors from the same physical player row.
            candidates[y / maxOf(24, band / 2)] = fallbackRow
        }
        return candidates.values.toList()
    }

    private fun col(c: String, r: Row): Int? = when (c) { "h" -> r.h; "d" -> r.d; "o" -> r.o; else -> null }

    /** Kaleci (col "k") reytingi listede görünmediği için reyting karşılaştırması atlanır. */
    private fun canon(p: String) = if (p == "KLC") "KL" else p

    fun match(t: AutoPlayer, rows: List<Row>): List<Row> = rows.filter { r ->
        nameOk(t.name, r.name) && (t.age == 0 || r.age == t.age) && canon(r.pos) == canon(t.pos) &&
            (col(t.col, r)?.let { it == t.rating } ?: true)
    }
}
