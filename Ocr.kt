package app.osmsquad

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class Ln(val text: String, val box: Rect)
data class TRow(val text: String, val y: Int)

object Ocr {
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val CNT = Regex("""(\d{1,2})\s*/\s*20""")

    suspend fun lines(b: Bitmap): List<Ln>? = suspendCancellableCoroutine<List<Ln>?> { c ->
        rec.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { r ->
                c.resume(r.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { Ln(l.text, it) } })
            }
            .addOnFailureListener { c.resume(null) }
    }

    /** Aynı yatay hizadaki hücreleri tek satırda birleştirir. */
    fun rows(ls: List<Ln>): List<TRow> {
        if (ls.isEmpty()) return emptyList()
        val sorted = ls.sortedBy { it.box.centerY() }
        val tol = (sorted.map { it.box.height() }.sorted()[sorted.size / 2] * 0.6).toInt().coerceAtLeast(6)
        val groups = mutableListOf<MutableList<Ln>>()
        for (l in sorted) {
            val g = groups.lastOrNull()
            if (g != null && Math.abs(l.box.centerY() - g.map { it.box.centerY() }.average()) < tol) g.add(l)
            else groups.add(mutableListOf(l))
        }
        return groups.map { g ->
            TRow(g.sortedBy { it.box.left }.joinToString(" ") { it.text }, g.map { it.box.centerY() }.average().toInt())
        }
    }

    /** Üst çubuktaki "n / 20" sayacı; seçimlerin doğrulanması bununla yapılır. */
    fun counter(ls: List<Ln>, h: Int): Int? {
        val top = ls.filter { it.box.centerY() < h * 0.15 }.sortedBy { it.box.left }.joinToString(" ") { it.text }
        return CNT.find(top)?.groupValues?.get(1)?.toInt()
    }

    /**
     * Pitch slotuna dokununca açılan kategori seçme ekranı; dört grup başlığını
     * aynı anda gösterir ve her grubun satırında ayrı bir mavi + düğmesi bulunur.
     * Bu ekran oyuncu arama sonuçlarıyla karıştırılmamalıdır.
     */
    fun isCategoryChooser(ls: List<Ln>): Boolean {
        val groups = ls.mapNotNull { categoryGroup(it.text) }.toSet()
        return groups.size >= 2
    }

    private fun categoryGroup(text: String): String? {
        val t = Matcher.n(text)
        return when {
            t.contains("kaleci") || t.contains("goalkeeper") -> "GK"
            t.contains("defans") || t.contains("defender") -> "DF"
            t.contains("ortasaha") || t.contains("midfielder") -> "MD"
            t.contains("forvet") || t.contains("forward") || t.contains("attacker") -> "FW"
            else -> null
        }
    }

    /** Kategori seçme ekranı hariç, OSM oyuncu tablosunun başlıklarını tanır. */
    fun isList(ls: List<Ln>): Boolean {
        if (ls.isEmpty() || isCategoryChooser(ls)) return false
        val t = Matcher.n(ls.joinToString(" ") { it.text })
        val hasPrice = t.contains("fiyat") || t.contains("price")
        val hasPositionColumn = t.contains("poz") || t.contains("position")
        val hasNationalityColumn = t.contains("uyr") || t.contains("nationality")
        return hasPrice && (hasPositionColumn || hasNationalityColumn)
    }
}
