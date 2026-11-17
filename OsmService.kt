package app.osmsquad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

private data class Cap(val lines: List<Ln>, val bitmap: Bitmap) {
    val w: Int get() = bitmap.width
    val h: Int get() = bitmap.height
}

/**
 * OSM automasyon servisi.
 * Ekran tanınması kesin değilse dokunmayı durdurur. Pitch konumu renk alanından,
 * kategori ve oyuncu satırları OCR'dan; erişilebilir metin varsa node bounds'tan alınır.
 */
class OsmService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private lateinit var info: TextView
    private lateinit var prog: TextView
    private var squad: Squad? = null
    private val done = linkedSetOf<String>()
    private var lastShot = 0L
    private var current = ""

    private val prefs get() = getSharedPreferences("osm", Context.MODE_PRIVATE)
    private val targetPkg get() = prefs.getString("pkg", "com.gamebasics.osm") ?: "com.gamebasics.osm"
    private val waitMs get() = prefs.getLong("waitMs", 1000L).coerceIn(300L, 5000L)
    private val retries get() = prefs.getInt("retries", 2).coerceIn(0, 5)
    private val plusX get() = prefs.getFloat("plusX", 0.924f).coerceIn(0.55f, 0.99f)

    private var evPkg: String? = null
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val p = e?.packageName?.toString() ?: return
        if (p != packageName && !p.startsWith("com.android.systemui") && !p.contains("inputmethod") && !p.contains("keyboard")) evPkg = p
    }

    private fun fgPkg(): String? {
        val active = rootInActiveWindow?.packageName?.toString()
        return if (active != null && active != packageName) active else evPkg
    }

    override fun onInterrupt() { stop() }
    override fun onDestroy() {
        scope.cancel()
        panel?.let { runCatching { wm?.removeView(it) } }
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6102040.toInt())
            setPadding(14, 10, 14, 10)
        }
        info = TextView(this).apply { setTextColor(-1); textSize = 12f }
        prog = TextView(this).apply { setTextColor(-1); textSize = 14f }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                textSize = 10f
                setOnClickListener { action() }
            })
        }
        button("BAŞLAT") { start() }
        button("DURAKLAT") { pause("Kullanıcı duraklattı") }
        button("DEVAM") { start() }
        button("DURDUR") { stop() }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(Button(this).apply { text = "TANI"; textSize = 10f; setOnClickListener { diagnose() } })
        box.addView(info); box.addView(prog); box.addView(buttons); box.addView(tools)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; y = 80 }
        wm?.addView(box, lp)
        panel = box
        load()
        render("Hazır. OSM kadro/saha ekranını açıp BAŞLAT'a bas.")
    }

    private fun load() {
        squad = prefs.getString("squad", null)?.let { runCatching { SquadIO.parse(it) }.getOrNull() }
        done.clear()
        val saved = runCatching { JSONArray(prefs.getString("done", "[]")) }.getOrNull()
        if (saved != null) for (i in 0 until saved.length()) done.add(saved.optString(i))
    }

    private fun save() { prefs.edit().putString("done", JSONArray(done.toList()).toString()).apply() }

    private fun render(message: String) {
        val s = squad
        info.text = (if (s != null) "${s.formation} · hedef ${s.target}" else "Kadro yok") +
            (if (current.isNotEmpty()) "\nŞu an: $current" else "") + "\n$message"
        prog.text = "${done.size}/20"
    }

    private fun start() {
        load()
        if (squad == null) { render("Önce uygulamada 20 oyuncuyu girip otomasyon hazırlığını yap."); return }
        if (!prefs.getBoolean("allowPurchase", false)) { render("Otomasyon kapalı: satın alma riski onayı yok."); return }
        if (job?.isActive == true) return
        job = scope.launch {
            try { runAll() }
            catch (e: CancellationException) { render("DURAKLADI") }
            catch (e: Exception) { fail("Beklenmeyen hata: ${e.message}") }
        }
    }

    private fun pause(why: String) { job?.cancel(); render("DURAKLADI: $why") }
    private fun stop() { job?.cancel(); current = ""; render("Durduruldu. Tamamlananlar kaydedildi.") }
    private fun fail(message: String): Boolean { pause(message); return false }

    private suspend fun runAll() {
        val s = squad ?: return
        if (fgPkg() != targetPkg) { fail("Önce OSM'yi aç. Algılanan paket: ${fgPkg() ?: "yok"}"); return }
        for (p in s.players.sortedBy { it.order }) {
            currentCoroutineContext().ensureActive()
            if (p.key in done) continue
            current = "${p.name} (${p.slot} · ${p.pos})"
            render("Boş mevki → kategori + → oyuncu arama…")
            if (!preparePlayerList(p)) return
            if (!stepInList(p)) return
        }
        current = ""
        val final = capture()
        val count = final?.let { Ocr.counter(it.lines, it.h) }
        render(if (count == 20) "20/20 sayaç doğrulandı." else "20 oyuncu işlendi; son sayaç doğrulanamadı ($count). Ekranı kontrol et.")
    }

    /**
     * OSM akışı: saha/yedek yuvasına dokun → kategori ekranında ilgili satırın
     * mavi + düğmesine dokun → oyuncu arama ekranını aç.
     * Kategori başlığının kendisine dokunulmaz; her kategori için ayrı + vardır.
     */
    private suspend fun preparePlayerList(p: AutoPlayer): Boolean {
        var cap = capture() ?: return fail("Ekran görüntüsü alınamadı")
        if (fgPkg() != targetPkg) return fail("OSM ön planda değil")

        val group = Formations.groupOfPosition(p.pos)
            ?: return fail("Pozisyon grubu tanınmadı: ${p.pos}")
        var chooserOpen = Ocr.isCategoryChooser(cap.lines)
        var resultsOpen = Ocr.isList(cap.lines)

        // DEVAM'a basıldığında arama listesi veya kategori seçimi zaten açıksa,
        // aynı yuvaya tekrar dokunmadan mevcut adımdan devam et.
        if (!chooserOpen && !resultsOpen && findEditable() != null) resultsOpen = true

        if (!chooserOpen && !resultsOpen) {
            if (p.slot.startsWith("xi")) {
                val formation = Formations.all.firstOrNull { it.id == squad?.formation }
                    ?: return fail("Diziliş tanınmıyor")
                val slot = formation.slots.firstOrNull { it.id == p.slot }
                    ?: return fail("Saha yuvası bulunamadı: ${p.slot}")
                val rect = detectPitchBounds(cap.bitmap)
                    ?: return fail("Saha alanı güvenilir biçimde algılanamadı. Dokunma yapılmadı.")
                val x = rect.left + rect.width() * (slot.x / 100f)
                val y = rect.top + rect.height() * (slot.y / 100f)
                if (x !in 0f..cap.w.toFloat() || y !in 0f..cap.h.toFloat()) {
                    return fail("Saha koordinatı ekran dışında")
                }
                render("${slot.label} saha yuvası seçiliyor…")
                if (!gesture(Path().apply { moveTo(x, y) }, 70)) {
                    return fail("Saha yuvasına dokunulamadı")
                }
            } else {
                // Yedek yuvası seçimi: önce yedek alanını aç, sonra istenen boş yuvayı seç.
                if (!tapNamedNode(listOf("yedekler", "subs", "bench"))) {
                    return fail("Yedekler sekmesi tanınmadı; yanlış ekrana dokunmadım.")
                }
                delay(waitMs)
                if (!tapNamedNode(listOf(p.slot, subLabel(p.slot)))) {
                    return fail("${p.slot} yedek yuvası bulunamadı.")
                }
            }
            delay(waitMs)
            cap = capture() ?: return fail("Mevki seçildikten sonra ekran okunamadı")
            chooserOpen = Ocr.isCategoryChooser(cap.lines)
            resultsOpen = Ocr.isList(cap.lines)
        }

        if (chooserOpen) {
            render("${Formations.GROUP_NAME[group]} kategorisinin + düğmesi seçiliyor…")
            if (!tapCategoryPlus(cap, group)) {
                return fail("${Formations.GROUP_NAME[group]} satırındaki + düğmesi bulunamadı; yanlış kategoriye dokunmadım.")
            }
            delay(waitMs)
            cap = capture() ?: return fail("Kategori + düğmesinden sonra ekran okunamadı")
            if (Ocr.isCategoryChooser(cap.lines)) {
                return fail("${Formations.GROUP_NAME[group]} + düğmesine basıldı ancak kategori ekranı kapanmadı.")
            }
            resultsOpen = Ocr.isList(cap.lines)
        }

        if (!resultsOpen && findEditable() == null) {
            // Bazı OSM sürümlerinde + düğmesi önce listeyi açar, arama alanı
            // ayrıca büyüteç düğmesiyle görünür hâle gelir.
            if (!tapSearchControl(cap)) return fail("Kategori seçildi ancak oyuncu arama ekranı doğrulanamadı")
            delay(waitMs)
            cap = capture() ?: return fail("Arama ekranı okunamadı")
        }

        if (!Ocr.isList(cap.lines) && findEditable() == null) {
            return fail("Oyuncu listesi açılmadı; isim aramasına geçmedim.")
        }
        return true
    }

    private suspend fun returnToCorrectRosterScreen(p: AutoPlayer): Boolean {
        var cap = capture() ?: return false
        val isSub = p.role == "SUB"
        fun expected(c: Cap): Boolean = if (isSub) {
            c.lines.any { Matcher.n(it.text).contains("yedek") || Matcher.n(it.text).contains("bench") || Matcher.n(it.text).contains("substitute") }
        } else detectPitchBounds(c.bitmap) != null

        var attempts = 0
        while (Ocr.isList(cap.lines) && attempts < 2) {
            clearSearch()
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(waitMs)
            cap = capture() ?: return false
            attempts++
        }
        if (expected(cap)) return true
        // Bazı sürümlerde önce kategori penceresine, sonra saha ekranına dönülür.
        while (attempts < 3 && !expected(cap)) {
            if (!cap.lines.any { line ->
                    val v = Matcher.n(line.text)
                    listOf("kaleci", "defans", "ortasaha", "forvet").any { v.contains(it) }
                }) return false
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(waitMs)
            cap = capture() ?: return false
            attempts++
        }
        return expected(cap)
    }

    private fun subLabel(slot: String): String = when {
        slot.startsWith("gk") -> "Yedek Kaleci ${slot.takeLast(1).toIntOrNull()?.plus(1) ?: 1}"
        slot.startsWith("df") -> "Yedek Defans ${slot.takeLast(1).toIntOrNull()?.plus(1) ?: 1}"
        slot.startsWith("md") -> "Yedek Orta saha ${slot.takeLast(1).toIntOrNull()?.plus(1) ?: 1}"
        slot.startsWith("fw") -> "Yedek Forvet ${slot.takeLast(1).toIntOrNull()?.plus(1) ?: 1}"
        else -> slot
    }

    private fun categoryGroup(text: String): String? {
        val value = Matcher.n(text)
        return when {
            value.contains("kaleci") || value.contains("goalkeeper") -> "GK"
            value.contains("defans") || value.contains("defender") -> "DF"
            value.contains("ortasaha") || value.contains("midfielder") -> "MD"
            value.contains("forvet") || value.contains("forward") || value.contains("attacker") -> "FW"
            else -> null
        }
    }

    private fun tapCategoryPlus(cap: Cap, group: String): Boolean {
        // Aynı grup için kısa kategori başlığı ile açıklama satırını ayırt et:
        // "Kaleciler" başlıktır, "Kaleciler bölgesi için ..." ise + satırıdır.
        val matching = cap.lines.filter { categoryGroup(it.text) == group }
        val heading = matching.minByOrNull { Matcher.n(it.text).length } ?: return false
        val headings = matchingCategoryHeadings(cap.lines)
        val nextHeading = headings.filter { it.second.box.centerY() > heading.box.centerY() }
            .minByOrNull { it.second.box.centerY() }
        val previousHeading = headings.filter { it.second.box.centerY() < heading.box.centerY() }
            .maxByOrNull { it.second.box.centerY() }

        val description = matching.filter { it !== heading && it.box.centerY() > heading.box.centerY() }
            .minByOrNull { it.box.centerY() - heading.box.centerY() }
        val estimatedRowY = if (nextHeading != null) {
            (heading.box.bottom + nextHeading.second.box.top) / 2
        } else {
            // Son kategori (genellikle Kaleciler) için bölüm yüksekliğini önceki
            // başlık aralığından tahmin et; ekranın altındaki saha alanına kayma.
            val gap = previousHeading?.let { heading.box.centerY() - it.second.box.centerY() }
                ?: (cap.h * 0.16f).toInt()
            heading.box.centerY() + gap / 2
        }
        val rowY = description?.box?.centerY() ?: estimatedRowY
        val halfBand = max(44, (cap.h * 0.055f).toInt())
        val top = max(0, rowY - halfBand)
        val bottom = min(cap.h - 1, rowY + halfBand)

        // Tercih edilen yöntem: ekrandaki mavi dairesel + ikonunu görüntüden bul.
        val icon = findBluePlusCenter(cap.bitmap, top, bottom)
        if (icon != null) return dispatchTap(icon.first, icon.second)

        // WebView erişilebilirlik ağacı + düğmesine bounds veriyorsa onu kullan.
        val root = rootInActiveWindow
        var best: Rect? = null
        var bestArea = Int.MAX_VALUE
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser && node.isClickable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val rightSide = bounds.centerX() >= cap.w * 0.82f
                val inRow = bounds.centerY() in top..bottom
                val sizeOk = bounds.width() in 12..150 && bounds.height() in 12..150
                val area = bounds.width() * bounds.height()
                if (rightSide && inRow && sizeOk && area < bestArea) {
                    best = bounds
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        val b = best ?: return false
        return dispatchTap(b.centerX().toFloat(), b.centerY().toFloat())
    }

    private fun matchingCategoryHeadings(lines: List<Ln>): List<Pair<String, Ln>> {
        return listOf("FW", "MD", "DF", "GK").mapNotNull { group ->
            lines.filter { categoryGroup(it.text) == group }
                .minByOrNull { Matcher.n(it.text).length }
                ?.let { group to it }
        }.sortedBy { it.second.box.centerY() }
    }

    /** Mavi + simgesinin (beyaz artı merkezli) mavi çevresini bulur. */
    private fun findBluePlusCenter(bitmap: Bitmap, top: Int, bottom: Int): Pair<Float, Float>? {
        val left = (bitmap.width * 0.82f).toInt().coerceAtLeast(0)
        val right = (bitmap.width * 0.985f).toInt().coerceAtMost(bitmap.width - 1)
        val y0 = top.coerceIn(0, bitmap.height - 1)
        val y1 = bottom.coerceIn(y0, bitmap.height - 1)
        var count = 0
        var sumX = 0L
        var sumY = 0L
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in y0..y1 step 2) {
            for (x in left..right step 2) {
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                if (b > 170 && g > 95 && b > g * 1.05f && g > r * 1.4f && b > r * 1.6f) {
                    count++
                    sumX += x
                    sumY += y
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
        val width = maxX - minX
        val height = maxY - minY
        if (count < 18 || width !in 12..90 || height !in 12..90) return null
        return (sumX.toFloat() / count) to (sumY.toFloat() / count)
    }

    private suspend fun stepInList(p: AutoPlayer): Boolean {
        var scrolls = 0
        var lastSig = ""
        var searchTries = 0
        if (!ensureSearchField(p.name)) return fail("Arama kutusu bulunamadı")
        delay(waitMs)

        while (true) {
            currentCoroutineContext().ensureActive()
            val cap = capture() ?: return fail("Ekran görüntüsü/OCR alınamadı")
            if (fgPkg() != targetPkg) return fail("OSM ön planda değil")
            if (!Ocr.isList(cap.lines)) return fail("Oyuncu listesi ekranı kayboldu")
            val before = Ocr.counter(cap.lines, cap.h) ?: return fail("n/20 sayacı okunamadı; seçim yapılmadı")
            if (before >= 20) return fail("Sayaç zaten 20/20")

            val rows = Ocr.rows(cap.lines).mapNotNull { row -> Parser.row(row.text, row.y) }
            val matches = Matcher.match(p, rows)
            if (matches.size > 1) return fail("Birden fazla eşleşme var: ${p.name}. Yanlış oyuncu eklenmedi.")
            if (matches.size == 1) {
                val row = matches.single()
                if (!tapPlusForRow(cap, row.y)) return fail("${p.name} satırının + düğmesi bulunamadı")
                delay(waitMs)
                val afterCap = capture() ?: return fail("Ekleme sonrası ekran okunamadı")
                val after = Ocr.counter(afterCap.lines, afterCap.h)
                if (after != before + 1) return fail("${p.name} eklemesi sayaçla doğrulanamadı ($before → $after). Durdum.")
                done.add(p.key); save(); render("✔ ${p.name} eklendi ve sayaç doğrulandı")
                if (!returnToCorrectRosterScreen(p)) return fail("Oyuncu eklendi ancak sonraki yuva için saha/yedek ekranı doğrulanamadı.")
                return true
            }

            if (searchTries < retries) {
                searchTries++
                if (!replaceSearchText(p.name)) return fail("Arama yenilenemedi: ${p.name}")
                delay(waitMs)
                continue
            }
            val signature = rows.joinToString("|") { "${it.name}:${it.age}:${it.pos}" }
            if (signature == lastSig || ++scrolls > 30) return fail("Tam eşleşme bulunamadı: ${p.name} (${p.age}, ${p.pos}). Yanlış oyuncu eklenmedi.")
            lastSig = signature
            if (!gesture(Path().apply { moveTo(cap.w * 0.52f, cap.h * 0.80f); lineTo(cap.w * 0.52f, cap.h * 0.42f) }, 420)) {
                return fail("Oyuncu listesi kaydırılamadı")
            }
            delay(650)
        }
    }

    private suspend fun ensureSearchField(name: String): Boolean {
        if (findEditable() == null) {
            val cap = capture() ?: return false
            if (!tapSearchControl(cap)) return false
            delay(350)
        }
        return replaceSearchText(name)
    }

    private suspend fun replaceSearchText(name: String): Boolean {
        for (attempt in 0..retries) {
            val node = findEditable()
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(200)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(250)
                node.refresh()
                val currentText = node.text?.toString()?.trim()
                if (ok && currentText.equals(name, ignoreCase = true)) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    return true
                }
            }
            delay(waitMs)
        }
        return false
    }

    private fun clearSearch() {
        val node = findEditable() ?: return
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || found != null) return
            if (node.isVisibleToUser && (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
                found = node; return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return found
    }

    private fun tapSearchControl(cap: Cap): Boolean {
        if (tapNamedNode(listOf("ara", "search", "oyuncu ara", "player search"))) return true
        val line = cap.lines.firstOrNull {
            val n = Matcher.n(it.text)
            n == "ara" || n == "search" || n.contains("oyuncuara") || n.contains("playersearch")
        } ?: return false
        return dispatchTap(line.box.centerX().toFloat(), line.box.centerY().toFloat())
    }

    private fun tapPlusForRow(cap: Cap, rowY: Int): Boolean {
        val root = rootInActiveWindow
        var point: Pair<Float, Float>? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || point != null) return
            val label = Matcher.n(node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            if (node.isVisibleToUser && node.isClickable &&
                (label == "ekle" || label == "add" || label == "plus" || label == "＋" || label == "+")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (kotlin.math.abs(b.centerY() - rowY) < max(70, cap.h / 25)) point = b.centerX().toFloat() to b.centerY().toFloat()
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        val p = point
        return if (p != null) dispatchTap(p.first, p.second)
        else dispatchTap(cap.w * plusX, rowY.toFloat()) // son çare: kalibre edilmiş ekran oranı
    }

    private fun tapNamedNode(names: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        var target: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || target != null) return
            val value = Matcher.n(node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            if (node.isVisibleToUser && names.any { value.contains(Matcher.n(it)) }) {
                var candidate: AccessibilityNodeInfo? = node
                var depth = 0
                while (candidate != null && depth < 5) {
                    if (candidate.isVisibleToUser && candidate.isClickable) { target = candidate; break }
                    candidate = candidate.parent
                    depth++
                }
                if (target != null) return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun dispatchTap(x: Float, y: Float): Boolean =
        dispatchGesture(GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60)
        ).build(), null, null)

    /** Ekrandaki en büyük yeşil saha benzeri alanın sınırını örneklemeyle bulur. */
    private fun detectPitchBounds(bitmap: Bitmap): Rect? {
        val w = bitmap.width; val h = bitmap.height
        val step = max(2, min(w, h) / 220)
        val rowCount = IntArray(h / step + 1)
        val colCount = IntArray(w / step + 1)
        var total = 0
        var y = (h * 0.08f).toInt()
        while (y < h * 0.96f) {
            var x = (w * 0.03f).toInt()
            while (x < w * 0.97f) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                if (g > 55 && g > r * 1.12 && g > b * 1.05) {
                    rowCount[y / step]++; colCount[x / step]++; total++
                }
                x += step
            }
            y += step
        }
        if (total < (w / step) * (h / step) * 0.035) return null
        val minRow = rowCount.indices.firstOrNull { rowCount[it] > (w / step) * 0.22 } ?: return null
        val maxRow = rowCount.indices.reversed().firstOrNull { rowCount[it] > (w / step) * 0.22 } ?: return null
        val minCol = colCount.indices.firstOrNull { colCount[it] > (h / step) * 0.12 } ?: return null
        val maxCol = colCount.indices.reversed().firstOrNull { colCount[it] > (h / step) * 0.12 } ?: return null
        val rect = Rect(minCol * step, minRow * step, min(w, (maxCol + 1) * step), min(h, (maxRow + 1) * step))
        if (rect.width() < w * 0.45 || rect.height() < h * 0.22) return null
        return rect
    }

    private fun kbVisible(): Boolean = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun diagnose() {
        val root = rootInActiveWindow
        if (root == null) { render("TANI: aktif pencere okunamadı"); return }
        var nodes = 0; var edits = 0; var clicks = 0
        val out = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || nodes > 2500) return
            nodes++
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (editable) edits++
            if (node.isClickable) clicks++
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if ((editable || text != null) && out.length < 3500) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                out.append("  ".repeat(depth.coerceAtMost(8)))
                    .append(node.className?.toString()?.substringAfterLast('.'))
                    .append(if (editable) " [EDIT]" else "")
                    .append(if (node.isClickable) " [CLICK]" else "")
                    .append(" [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]: ")
                    .append(text.orEmpty()).append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val summary = "pkg=${root.packageName} node=$nodes editable=$edits clickable=$clicks keyboard=${kbVisible()}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OSM TANI", summary + "\n" + out))
        render("TANI: $summary\nAyrıntılar panoya kopyalandı.")
    }

    private suspend fun gesture(path: Path, duration: Long): Boolean = suspendCancellableCoroutine { cont ->
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    private suspend fun capture(): Cap? {
        val wait = 1100 - (System.currentTimeMillis() - lastShot)
        if (wait > 0) delay(wait)
        panel?.visibility = View.INVISIBLE
        delay(100)
        val bitmap: Bitmap? = try {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val copied = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (cont.isActive) cont.resume(copied)
                    }
                    override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        } finally {
            panel?.visibility = View.VISIBLE
            lastShot = System.currentTimeMillis()
        }
        if (bitmap == null) return null
        val lines = Ocr.lines(bitmap) ?: return null
        return Cap(lines, bitmap)
    }
}
