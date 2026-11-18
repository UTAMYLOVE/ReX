package io.github.xeno_hf.rex.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import rx.protocol.Fmt
import rx.session.RevFlash
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 儀表繪圖（監控主畫面三種風格＋懸浮小儀表共用）。
 *
 * 座標一律用「設計單位」：手機寬 412，和設計稿相同；呼叫端先把畫布縮放成這個座標系。
 * 錶面日夜都是深色（重機儀表的做法：淺色錶面加淺色燈在太陽下看不見），頁面才跟著日夜換。
 */
object Inst {
    // 六色
    val sakura = Color(0xFFFEDFE1); val beige = Color(0xFFD7C4BB); val rose = Color(0xFFDC9FB4)
    val white = Color(0xFFFFFFFF); val peak = Color(0xFF6F8BFF); val aqua = Color(0xFFA5DEE4); val mocha = Color(0xFF9E7A7A)
    // 衍生
    // 夜櫻 A2「粉嫩櫻色環狀」：玫瑰黑紫底、粉色系轉速環；不用黃色，紅色只有一種
    val bg = Color(0xFF200D19); val surf1 = Color(0xFF33192A); val surf2 = Color(0xFF3E1E30); val pod = Color(0xFF3A1B2C)
    val ink = Color(0xFF1C0E15); val labelDay = Color(0xFF7A4656); val red = Color(0xFFE5484D); val redDay = Color(0xFFE5484D)
    val roseLine = Color(0xFFA8406A); val aquaLine = Color(0xFFA8406A); val channel = Color(0xFF10050C)
    val rimTop = Color(0xFF714459); val rimBottom = Color(0xFF12060E)
    val petal = Color(0xFFF7C1D0); val hotRose = Color(0xFFE17481); val band = Color(0xFF12060E)
    val segOff = rose.copy(alpha = 0.15f); val ghost = red.copy(alpha = 0.24f)

    const val T10 = 0.17632698f // tan 10°
    const val SKEW = -0.17632698f // 文字 −10° 斜（Android/Skia 的負值＝往右斜）
    const val SEGS = 29
    const val PITCH = 270f / SEGS
    const val GAP = 1.8f
}

/** 頁面配色（日夜）。錶盤本身不換。 */
data class PagePal(
    val night: Boolean, val page: Color, val num: Color, val label: Color, val tile: Color, val bar: Color, val track: Color,
    val chipStroke: Color, val rec: Color, val caution: Color, val fault: Color,
) {
    companion object {
        val NIGHT = PagePal(true, Inst.bg, Inst.sakura, Inst.rose, Inst.surf1, Inst.petal, Inst.petal.copy(alpha = .13f), Color(0xFF6E4059), Inst.sakura, Inst.rose, Inst.red)
        val DAY = PagePal(false, Inst.sakura, Color(0xFF1F0E16), Inst.labelDay, Color.White, Inst.roseLine, Color(0xFFEDB5C3).copy(alpha = .55f), Color(0xFFE6B3C0), Inst.roseLine, Inst.rose, Inst.redDay)
    }
    fun of(lv: Lv) = when (lv) { Lv.OK -> num; Lv.CAUTION -> caution; Lv.FAULT -> fault }
}

/**
 * 錶面配色（日夜）。白天：白色錶面、深色刻度與數字、深玫瑰指針、紅色針尖；外殼與 LED 槽日夜都是深色。
 * 每個錶盤函式開頭設定 [day]（繪製都在主執行緒依序進行）。懸浮小儀表一律用夜間配色（疊在地圖上要深色）。
 */
private object Tone {
    var day = false
    val major get() = if (day) Color(0xFF1F0E16) else Inst.sakura
    val mid get() = if (day) Color(0xFF4A2E38) else Inst.beige.copy(alpha = .7f)
    val minor get() = if (day) Color(0xFF8C6672) else Inst.mocha
    val numeral get() = if (day) Color(0xFF1F0E16) else Inst.beige
    val readout get() = if (day) Color(0xFF1F0E16) else Inst.sakura
    val sub get() = if (day) Inst.labelDay else Inst.beige
    val needle get() = if (day) Inst.roseLine else Inst.rose
    val tip get() = if (day) Inst.red else Color.White
    val ring get() = if (day) Color(0xFF8C6672).copy(alpha = .35f) else Inst.beige.copy(alpha = .25f)
}

/** 轉速 → 29 段分段刻度（0–5k 每段 1,000；5–10k 每段 250；10–12k 每段 500），起點 135°，270°。 */
object RevScale {
    fun idx(r: Double): Double { val x = r.coerceIn(0.0, 12_000.0); return if (x <= 5000) x / 1000 else if (x <= 10_000) 5 + (x - 5000) / 250 else 25 + (x - 10_000) / 500 }
    fun ang(r: Double): Float = (135 + idx(r) * Inst.PITCH).toFloat()
    /** 第 n 段（1 起算）的下限 rpm。 */
    fun segLow(n: Int): Double = when { n <= 5 -> (n - 1) * 1000.0; n <= 25 -> 5000.0 + (n - 6) * 250; else -> 10_000.0 + (n - 26) * 500 }
    fun lit(rpm: Double): Int { var n = 0; for (i in 1..Inst.SEGS) if (rpm > segLow(i) || (i == 1 && rpm > 0)) n = i; return n }

    /** 色階門檻依紅線 R 計算。 */
    fun color(rpmLow: Double, redline: Int): Color = when {
        rpmLow < redline - 5000 -> Inst.rose.copy(alpha = .6f)
        rpmLow < redline - 2000 -> Inst.petal
        rpmLow < redline - 1000 -> Inst.rose
        rpmLow < redline - 500 -> Inst.hotRose
        else -> Inst.red
    }
}

/** 一幀儀表要畫的資料。 */
data class DialData(
    val rpm: Double, val needleRpm: Double, val speed: Int?, val map: Int?, val redline: Int,
    val flash: RevFlash.Output, val peakRpm: Double?, val peakAlpha: Float, val connected: Boolean,
) {
    val revFlash get() = flash.state == RevFlash.State.REV
    val wotFlash get() = flash.state == RevFlash.State.WOT_BURST
}

// ---------------------------------------------------------------- 小工具

private fun pt(cx: Float, cy: Float, r: Float, a: Float): Offset {
    val rad = a * PI.toFloat() / 180f
    return Offset(cx + r * cos(rad), cy + r * sin(rad))
}

fun sectorPath(cx: Float, cy: Float, r1: Float, r2: Float, a1: Float, a2: Float): Path = Path().apply {
    arcTo(Rect(cx - r2, cy - r2, cx + r2, cy + r2), a1, a2 - a1, true)
    arcTo(Rect(cx - r1, cy - r1, cx + r1, cy + r1), a2, a1 - a2, false)
    close()
}

/** 45° 切角（只切左上、右下）。 */
fun chamPath(x: Float, y: Float, w: Float, h: Float, c: Float): Path = Path().apply {
    moveTo(x + c, y); lineTo(x + w, y); lineTo(x + w, y + h - c); lineTo(x + w - c, y + h); lineTo(x, y + h); lineTo(x, y + c); close()
}

/** 10° 平行四邊形（上緣往右偏）。 */
fun paraPath(x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
    val o = h * Inst.T10
    moveTo(x + o, y); lineTo(x + w, y); lineTo(x + w - o, y + h); lineTo(x, y + h); close()
}

/** 在設計單位下畫字：[size] 是設計單位字高（不受系統字級放大影響），[y] 是基線。 */
fun DrawScope.txt(
    tm: TextMeasurer, s: String, x: Float, y: Float, size: Float, color: Color,
    weight: FontWeight = FontWeight.Medium, digits: Boolean = false, skew: Boolean = false,
    anchor: Char = 'l', spacing: Float = 0f, alpha: Float = 1f,
): Float {
    val style = TextStyle(
        fontFamily = if (digits) Digits else FontFamily.Default, fontWeight = weight,
        fontSize = (size / (density * fontScale)).sp, letterSpacing = (spacing * size / (density * fontScale)).sp,
    )
    val r = tm.measure(s, style)
    val w = r.size.width.toFloat()
    // 斜體往右上傾，錨點以基線中段為準，整體寬度不變
    val x0 = when (anchor) { 'c' -> x - w / 2; 'r' -> x - w; else -> x }
    if (skew) {
        val m = androidx.compose.ui.graphics.Matrix().apply { values[androidx.compose.ui.graphics.Matrix.SkewX] = -Inst.T10 }
        withTransform({ translate(x0, y); transform(m); translate(-x0, -y) }) {
            drawText(r, color = color, topLeft = Offset(x0, y - r.firstBaseline), alpha = alpha)
        }
    } else drawText(r, color = color, topLeft = Offset(x0, y - r.firstBaseline), alpha = alpha)
    return w
}

fun DrawScope.textWidth(tm: TextMeasurer, s: String, size: Float, weight: FontWeight = FontWeight.Medium, digits: Boolean = false): Float =
    tm.measure(s, TextStyle(fontFamily = if (digits) Digits else FontFamily.Default, fontWeight = weight, fontSize = (size / (density * fontScale)).sp)).size.width.toFloat()

fun rpmText(r: Double) = Fmt.grouped((kotlin.math.round(r / 50) * 50).toLong())

/** 光暈：同一形狀以低不透明度、加寬描邊疊幾層（不用模糊濾鏡，硬體加速也畫得出來）。 */
private fun DrawScope.glow(p: Path, c: Color, radius: Float, alpha: Float) {
    for (k in 1..3) drawPath(p, c.copy(alpha = alpha / k), style = Stroke(width = radius * k * 0.9f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

// ---------------------------------------------------------------- LED 環

/**
 * 29 段 LED 環。閃爍時已亮的段全部同色同相位；OFF 相位降到 20%，填充位置仍看得到。
 * [revColor]：REV 閃的顏色（A 風格用水色換色，其餘紅）。
 */
fun DrawScope.ledRing(
    d: DialData, cx: Float, cy: Float, r1: Float, r2: Float, glowOn: Boolean, revColor: Color = Inst.red,
    cap: Boolean = false, gapPx: Float? = null,
) {
    val lit = if (d.connected) RevScale.lit(d.rpm) else 0
    val flashing = d.revFlash || d.wotFlash
    val flashCol = if (d.revFlash) revColor else Inst.white
    val dim = if (d.flash.dimmed) 0.2f else 1f
    val gapDeg = gapPx?.let { it / ((r1 + r2) / 2) * 180f / PI.toFloat() } ?: Inst.GAP
    for (n in 1..Inst.SEGS) {
        val a1 = 135 + (n - 1) * Inst.PITCH + gapDeg / 2
        val a2 = 135 + n * Inst.PITCH - gapDeg / 2
        val p = sectorPath(cx, cy, r1, r2, a1, a2)
        val low = RevScale.segLow(n)
        if (n <= lit) {
            val base = if (flashing) flashCol else RevScale.color(low, d.redline)
            // 光暈只在平常狀態（閃爍時兩個相位都關，避免光暈跟著脈動放大閃爍面積）
            if (glowOn && !flashing) glow(p, base, 3f, if (low >= d.redline - 2000) .16f else .08f)
            drawPath(p, base.copy(alpha = base.alpha * dim))
        } else {
            drawPath(p, if (low >= d.redline) Inst.ghost else Inst.segOff)
        }
    }
    if (cap && lit > 0) {
        val a2 = 135 + lit * Inst.PITCH - gapDeg / 2
        val da = 2f / ((r1 + r2) / 2) * 180f / PI.toFloat()
        drawPath(sectorPath(cx, cy, r1 - 3, r2 + 3, a2 - da, a2), Color.White)
    }
    // peak-hold：2 單位白色徑向刻線，3 秒後淡出，不閃
    d.peakRpm?.let { pk ->
        if (d.peakAlpha > 0.01f) {
            val a = RevScale.ang(pk)
            // 小藍條：剛剛到過的最高轉速，比燈環內外各多出一點，容易看到
            drawLine(Inst.peak.copy(alpha = d.peakAlpha * .35f), pt(cx, cy, r1 - 5, a), pt(cx, cy, r2 + 5, a), strokeWidth = 8f)
            drawLine(Inst.peak.copy(alpha = d.peakAlpha), pt(cx, cy, r1 - 5, a), pt(cx, cy, r2 + 5, a), strokeWidth = 3.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- 錶盤共用

private fun DrawScope.ticks(cx: Float, cy: Float, rFace: Float, redline: Int, majorColor: Color = Tone.major) {
    fun tick(r: Double, rin: Float, w: Float, c: Color) {
        val a = RevScale.ang(r)
        drawLine(c, pt(cx, cy, rin, a), pt(cx, cy, rFace, a), strokeWidth = w)
    }
    for (k in listOf(1000, 2000, 3000, 4000)) tick(k.toDouble(), rFace - 10, 1.5f, Tone.minor)
    var k = 5500; while (k <= 11500) { tick(k.toDouble(), rFace - 10, 1.5f, Tone.mid); k += 1000 }
    for (q in listOf(8250, 8750, 9250, 9750)) tick(q.toDouble(), rFace - 6, 1f, Tone.minor)
    for (q in listOf(0, 5000, 6000, 7000, 8000, 9000, 10000, 11000, 12000)) tick(q.toDouble(), rFace - 18, 3f, if (q >= redline) Inst.red else majorColor)
}

private fun DrawScope.numerals(tm: TextMeasurer, cx: Float, cy: Float, r: Float, redline: Int, size: Float = 20f) {
    for (k in listOf(0, 5, 6, 7, 8, 9, 10, 11, 12)) {
        val p = pt(cx, cy, r, RevScale.ang(k * 1000.0))
        txt(tm, "$k", p.x, p.y + size * 0.345f, size, if (k * 1000 >= redline) Inst.red else Tone.numeral, if (Tone.day) FontWeight.Bold else FontWeight.SemiBold, digits = true, anchor = 'c')
    }
}

private fun DrawScope.face(cx: Float, cy: Float, r: Float) {
    if (Tone.day) {
        // 白天：白色錶面（大太陽下最清楚），邊緣一點點粉
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFBEFF2)), center = Offset(cx, cy - r * .3f), radius = r * 1.2f), r, Offset(cx, cy))
        for (i in 0 until 4) drawCircle(Color(0xFF3A1B2C).copy(alpha = .06f), r - i * 1.6f, Offset(cx, cy), style = Stroke(1.8f))
        return
    }
    drawCircle(Brush.radialGradient(listOf(Inst.surf2, Inst.bg), center = Offset(cx, cy - r * .3f), radius = r * 1.34f), r, Offset(cx, cy))
    // 內陰影
    for (i in 0 until 6) drawCircle(Color.Black.copy(alpha = .09f), r - i * 1.6f, Offset(cx, cy), style = Stroke(1.8f))
    // 壓紋 180 條
    for (i in 0 until 180) { val a = i * 2f; drawLine(Inst.sakura.copy(alpha = .025f), pt(cx, cy, 98f, a), pt(cx, cy, 145f, a), .5f) }
}

private fun DrawScope.chapterRing(cx: Float, cy: Float, r: Float) {
    val steps = 72
    for (k in 0 until steps) {
        val a1 = k * 360f / steps; val mid = a1 + 2.5f
        val t = (0.5 + 0.5 * cos(2 * (mid - 225) * PI / 180)).toFloat()
        val c = if (Tone.day) lerpColor(Color(0xFFE6B3C0), Inst.roseLine, t) else lerpColor(Inst.mocha, Inst.beige, t)
        drawArc(c, a1, 360f / steps + .3f, false, Offset(cx - r, cy - r), Size(2 * r, 2 * r), style = Stroke(1.5f))
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

private fun DrawScope.bezel(cx: Float, cy: Float, r: Float) {
    drawCircle(Inst.pod, r + 2, Offset(cx, cy))
    drawCircle(Brush.verticalGradient(listOf(Inst.rimTop, Inst.rimBottom), startY = cy - r, endY = cy + r), r, Offset(cx, cy), style = Stroke(3f))
    drawArc(Inst.sakura.copy(alpha = .18f), 180f, 180f, false, Offset(cx - r + .5f, cy - r + .5f), Size(2 * r - 1, 2 * r - 1), style = Stroke(1f))
}

private fun DrawScope.glass(cx: Float, cy: Float) {
    if (Tone.day) return
    val p = Path()
    for (i in 0..50) { val a = 200f + i * 2; val q = pt(cx, cy, 159.5f, a); if (i == 0) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y) }
    for (i in 50 downTo 0) { val a = 200f + i * 2; val t = sin((a - 200) / 100 * PI).toFloat(); val q = pt(cx, cy, 159.5f - 12.5f * t, a); p.lineTo(q.x, q.y) }
    p.close()
    drawPath(p, Brush.linearGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent), start = Offset(cx - 150, cy - 60), end = Offset(cx + 10, cy - 150)))
}

/** KOSO 式指針：根部寬 4 收到針尖 1.5，白色針尖，配重 26，軸心 Ø34。 */
private fun DrawScope.needle(cx: Float, cy: Float, ang: Float, glowOn: Boolean, hub: Boolean = true, len: Float = 157f, root: Float = 0f) {
    // 陰影（螢幕座標往下 2，不隨針轉）
    translate(0f, 2f) {
        rotate(ang, Offset(cx, cy)) {
            drawPath(Path().apply { moveTo(cx + root, cy - 2.4f); lineTo(cx + len, cy - .8f); lineTo(cx + len, cy + .8f); lineTo(cx + root, cy + 2.4f); close() }, Color.Black.copy(alpha = .35f))
        }
    }
    rotate(ang, Offset(cx, cy)) {
        val body = Path().apply { moveTo(cx + root, cy - 2f); lineTo(cx + len, cy - .75f); lineTo(cx + len, cy + .75f); lineTo(cx + root, cy + 2f); close() }
        if (glowOn) glow(Path().apply { moveTo(cx + len * .7f, cy - 1.2f); lineTo(cx + len, cy - .75f); lineTo(cx + len, cy + .75f); lineTo(cx + len * .7f, cy + 1.2f); close() }, Inst.rose, 2f, .25f)
        drawPath(body, Tone.needle)
        drawPath(Path().apply { moveTo(cx + len - 20, cy - 1.1f); lineTo(cx + len, cy - .75f); lineTo(cx + len, cy + .75f); lineTo(cx + len - 20, cy + 1.1f); close() }, Tone.tip)
        if (hub) drawPath(Path().apply { moveTo(cx, cy - 2.6f); lineTo(cx - 13, cy - 4); lineTo(cx - 26, cy - 4); lineTo(cx - 26, cy + 4); lineTo(cx - 13, cy + 4); lineTo(cx, cy + 2.6f); close() }, Tone.needle)
    }
    if (hub) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF42283A), Color(0xFF13090E)), center = Offset(cx - 5, cy - 6), radius = 17f), 17f, Offset(cx, cy))
        drawCircle(Tone.needle, 15.5f, Offset(cx, cy), style = Stroke(3f))
        drawCircle(Color.Black.copy(alpha = .5f), 13.5f, Offset(cx, cy), style = Stroke(1f))
        drawCircle(Inst.sakura, 4f, Offset(cx, cy))
    }
}

/** MAP 顏色：1 水藍、2 玫瑰、3 白。 */
fun mapFill(m: Int?) = Inst.rose

// ---------------------------------------------------------------- C「KOSO」

/** 錶盤裡的一個小數值（標籤、數字、單位、顏色）。 */
class DialSlot(val label: String, val text: String, val unit: String, val color: Color?)

/** C 風格錶盤。設計座標：寬 412，圓心 (206, 196)，畫布高 [C_H]。 */
const val C_H = 394f

fun DrawScope.dialKoso(tm: TextMeasurer, d: DialData, night: Boolean, slots: List<DialSlot> = emptyList()) {
    Tone.day = !night
    val cx = 206f; val cy = 196f
    val glowOn = night
    // 外殼：圓 r189 ∪ 下巴（x92–320，平底 y388，右下 14 切角）
    val pod = Path().apply {
        addArcPath(this, cx, cy, 189f, 52.9f, 127.1f)
        lineTo(92f, 388f); lineTo(306f, 388f); lineTo(320f, 374f); close()
    }
    drawPath(pod, Brush.verticalGradient(listOf(Color(0xFF3A1B2C), Inst.bg), startY = 7f, endY = 388f))
    drawPath(sectorPath(cx, cy, 164f, 184f, 132.6f, 407.4f), Inst.channel)
    drawPath(pod, Brush.verticalGradient(listOf(Inst.rimTop, Inst.rimBottom), startY = 7f, endY = 388f), style = Stroke(3f))
    drawArc(Inst.sakura.copy(alpha = .18f), 180f, 180f, false, Offset(cx - 188.5f, cy - 188.5f), Size(377f, 377f), style = Stroke(1f))
    ledRing(d, cx, cy, 170f, 182f, glowOn)
    // 色帶（Ducati 式）：跟在指針後，屬於轉速環，跟環一起閃
    if (d.connected && d.rpm > 0) {
        val col = when { d.revFlash -> Inst.red; d.wotFlash -> Inst.white; else -> RevScale.color(d.rpm, d.redline).copy(alpha = 1f) }
        drawArc(col.copy(alpha = if (d.flash.dimmed) .2f else 1f), 135f, RevScale.ang(d.needleRpm) - 135f, false, Offset(cx - 166.25f, cy - 166.25f), Size(332.5f, 332.5f), style = Stroke(1.5f))
    }
    // 錶面 R160，底部在 y312 截平
    val faceClip = Path().apply { addArcPath(this, cx, cy, 160f, 46.47f, 133.53f); close() }
    clipRect(0f, 0f, 412f, 312f) { chapterRing(cx, cy, 161.75f) }
    drawLine(Brush.horizontalGradient(listOf(Inst.beige, Inst.mocha, Inst.beige), startX = 95f, endX = 317f), Offset(95.1f, 313f), Offset(316.9f, 313f), 1.5f)
    clipPath(faceClip) {
        face(cx, cy, 160f)
        drawCircle(Tone.ring, 96f, Offset(cx, cy), style = Stroke(1f))
        ticks(cx, cy, 160f, d.redline)
        numerals(tm, cx, cy, 122f, d.redline)
        // 沒有指針：轉速看外圈燈環，正中央放大的數位轉速
        if (d.connected) {
            txt(tm, rpmText(d.rpm), cx, cy + 20f, 54f, if (d.revFlash) Inst.red else Tone.readout, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c', spacing = -.02f)
            txt(tm, "RPM", cx, cy + 46f, 14f, Tone.sub, anchor = 'c', spacing = .12f)
        }
        // 內圈上半：兩個小數值（在指針底下，像 GT3 RS 錶裡的小視窗）
        for (i in 0..1) slots.getOrNull(i)?.let { s ->
            val x = cx + (if (i == 0) -47f else 47f)
            txt(tm, s.label, x, 136f, 11f, s.color ?: Tone.sub, anchor = 'c')
            val w = textWidth(tm, s.text, 23f, FontWeight.ExtraBold, digits = true)
            val uw = if (s.unit.isEmpty()) 0f else textWidth(tm, s.unit, 10f) + 3f
            txt(tm, s.text, x - (w + uw) / 2, 162f, 23f, s.color ?: Tone.readout, FontWeight.ExtraBold, digits = true)
            if (s.unit.isNotEmpty()) txt(tm, s.unit, x - (w + uw) / 2 + w + 3f, 162f, 10f, s.color ?: Tone.sub)
        }
        glass(cx, cy)
    }
    // 左下角：第三個小數值（跟右邊 MAP 框對稱）
    slots.getOrNull(2)?.let { s ->
        val lx = 16f; val ly = 332f
        val line = s.color ?: if (night) Inst.rose.copy(alpha = .55f) else Inst.roseLine
        drawPath(chamPath(lx, ly, 62f, 56f, 10f), if (night) Inst.bg else Color.White)
        drawPath(chamPath(lx + .75f, ly + .75f, 60.5f, 54.5f, 9.7f), line, style = Stroke(1.5f))
        txt(tm, s.text, lx + 31f, ly + 32f, 22f, s.color ?: Tone.readout, FontWeight.ExtraBold, digits = true, anchor = 'c')
        if (s.unit.isNotEmpty()) txt(tm, s.unit, lx + 31f, ly + 48f, 10f, if (night) Inst.beige else Inst.labelDay, anchor = 'c')
        txt(tm, s.label, lx, 326f, 12f, if (night) Inst.beige else Inst.labelDay)
    }
    // 下巴：速度
    val spd = d.speed?.toString() ?: "--"
    val w = textWidth(tm, spd, 88f, FontWeight.ExtraBold, digits = true)
    // 白天：車速放在白色液晶窗裡
    if (Tone.day) {
        drawPath(chamPath(108f, 320f, 198f, 62f, 8f), Color.White)
        drawPath(chamPath(108.5f, 320.5f, 197f, 61f, 7.8f), Color(0xFF1F0E16).copy(alpha = .18f), style = Stroke(1f))
    }
    txt(tm, spd, 246f - w, 380f, 88f, Tone.readout, FontWeight.ExtraBold, digits = true, skew = true, spacing = -.02f)
    txt(tm, "km/h", 252f, 380f, 13f, Tone.sub, spacing = .06f)
    // MAP 框（Ducati 檔位位置）。白天：白板＋深色框＋深玫瑰硬陰影
    val x = 340f; val y = 332f
    if (Tone.day) {
        drawPath(chamPath(x + 4f, y + 4f, 56f, 56f, 10f), Inst.roseLine)
        drawPath(chamPath(x, y, 56f, 56f, 10f), Color.White)
        drawPath(chamPath(x + .75f, y + .75f, 54.5f, 54.5f, 9.7f), Color(0xFF1F0E16), style = Stroke(1.5f))
        txt(tm, d.map?.toString() ?: "–", x + 28f, 380f, 60f, Color(0xFF1F0E16), FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
    } else {
        drawPath(chamPath(x, y, 56f, 56f, 10f), Inst.bg)
        drawPath(chamPath(x + .75f, y + .75f, 54.5f, 54.5f, 9.7f), Inst.rose, style = Stroke(1.5f))
        drawPath(chamPath(x + 3.5f, y + 3.5f, 49f, 49f, 8.5f), mapFill(d.map))
        txt(tm, d.map?.toString() ?: "–", x + 28f, 380f, 60f, Inst.bg, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
    }
    txt(tm, "MAP", 396f, 326f, 13f, if (night) Inst.beige else Inst.labelDay, anchor = 'r', spacing = .08f)
}

/** 在 path 上加一段圓弧：從 [a1] 逆向（經過上方）畫到 [a2]（大弧）。 */
private fun addArcPath(p: Path, cx: Float, cy: Float, r: Float, a1: Float, a2: Float) {
    val s = pt(cx, cy, r, a1)
    p.moveTo(s.x, s.y)
    p.arcTo(Rect(cx - r, cy - r, cx + r, cy + r), a1, -(360f - (a2 - a1)), false)
}

// ---------------------------------------------------------------- A「CORSA」

/** A 風格：沒有指針，LED 環就是轉速錶，車速在正中，MAP 用拉長六角徽章。圓心 (206, 196)。 */
const val A_H = 392f

fun DrawScope.dialCorsa(tm: TextMeasurer, d: DialData, night: Boolean) {
    Tone.day = !night
    val cx = 206f; val cy = 196f
    bezel(cx, cy, 188f)
    drawPath(sectorPath(cx, cy, 164f, 186f, 132.6f, 407.4f), Inst.channel)
    ledRing(d, cx, cy, 170f, 182f, night, revColor = Inst.sakura, cap = true)
    chapterRing(cx, cy, 161.75f)
    face(cx, cy, 160f)
    drawCircle(Tone.ring, 96f, Offset(cx, cy), style = Stroke(1f))
    ticks(cx, cy, 160f, d.redline)
    numerals(tm, cx, cy, 122f, d.redline)
    glass(cx, cy)
    val spd = d.speed?.toString() ?: "--"
    txt(tm, spd, cx, 222f, 88f, Tone.readout, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c', spacing = -.02f)
    txt(tm, "km/h", cx, 246f, 13f, Tone.sub, anchor = 'c', spacing = .06f)
    if (d.connected) {
        val w1 = textWidth(tm, rpmText(d.rpm), 28f, FontWeight.ExtraBold, digits = true)
        val w2 = textWidth(tm, "RPM", 13f)
        val x0 = cx - (w1 + 6 + w2) / 2
        txt(tm, rpmText(d.rpm), x0, 284f, 28f, Tone.readout, FontWeight.ExtraBold, digits = true)
        txt(tm, "RPM", x0 + w1 + 6, 284f, 13f, Tone.sub, spacing = .08f)
    }
    // 拉長六角 MAP 徽章（全 App 唯一的六角形）
    val bw = 76f; val bh = 36f; val bp = 12f; val bx = cx - bw / 2; val by = 364f - bh / 2
    val hex = Path().apply { moveTo(bx + bp, by); lineTo(bx + bw - bp, by); lineTo(bx + bw, by + bh / 2); lineTo(bx + bw - bp, by + bh); lineTo(bx + bp, by + bh); lineTo(bx, by + bh / 2); close() }
    drawPath(hex, Inst.bg)
    drawPath(hex, Inst.rose, style = Stroke(1.5f))
    val inner = Path().apply { moveTo(bx + bp + 1, by + 3.5f); lineTo(bx + bw - bp - 1, by + 3.5f); lineTo(bx + bw - 4, by + bh / 2); lineTo(bx + bw - bp - 1, by + bh - 3.5f); lineTo(bx + bp + 1, by + bh - 3.5f); lineTo(bx + 4, by + bh / 2); close() }
    drawPath(inner, mapFill(d.map))
    txt(tm, "MAP", bx + 13, by + bh / 2 + 4.5f, 12f, Inst.bg, FontWeight.Bold, spacing = .06f)
    txt(tm, d.map?.toString() ?: "–", bx + bw - 19, by + bh / 2 + 10, 28f, Inst.bg, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
}

// ---------------------------------------------------------------- B「PIT」

/** B 風格：頂部橫向換檔燈（唯一會閃）＋恆亮指針圓錶（沒有 LED 環）。設計座標高 [B_H]，圓心 (206, 228)。 */
const val B_H = 430f

fun DrawScope.dialPit(tm: TextMeasurer, d: DialData, night: Boolean) {
    Tone.day = !night
    // 換檔燈：兩半各 5 段，由外往內兩兩點亮
    val segL = floatArrayOf(38f, 68f, 98f, 128f, 158f); val segR = floatArrayOf(348f, 318f, 288f, 258f, 228f)
    val R = d.redline
    val thr = intArrayOf(R - 4000, R - 3000, R - 2000, R - 1200, R - 600)
    val stage = listOf(Inst.rose.copy(alpha = .6f), Inst.petal, Inst.rose, Inst.hotRose, Inst.red)
    val flashing = d.revFlash || d.wotFlash
    val dim = if (d.flash.dimmed) .2f else 1f
    for (i in 0 until 5) {
        val lit = d.connected && d.rpm >= thr[i]
        val c = when {
            flashing -> (if (d.revFlash) Inst.red else Inst.white).copy(alpha = dim)
            lit -> stage[i]
            i == 4 -> Inst.ghost
            else -> Inst.segOff
        }
        for (x in floatArrayOf(segL[i], segR[i])) {
            val p = paraPath(x, 6f, 26f, 12f)
            if (lit && night && !flashing) glow(p, stage[i], 3f, .12f)
            drawPath(p, c)
        }
    }
    for (x in floatArrayOf(16f, 380f)) drawPath(chamPath(x + .5f, 6.5f, 15f, 11f, 5.8f), Inst.mocha.copy(alpha = .45f), style = Stroke(1f))
    // 反白數字 chip：轉速經過就填色
    val nums = (0 until 5).map { (R / 1000) - 4 + it }
    for (i in 0 until 5) {
        val passed = d.connected && d.rpm >= nums[i] * 1000
        for (x in floatArrayOf(segL[i], segR[i])) {
            val cxn = x + 13
            if (passed) drawPath(paraPath(cxn - 9, 22f, 18f, 14f), if (nums[i] * 1000 >= R) Inst.red else Inst.sakura)
            txt(tm, "${nums[i]}", cxn, 34f, 12f, if (passed) Inst.bg else Tone.sub, FontWeight.Bold, digits = true, anchor = 'c')
        }
    }
    // 錶盤
    val cx = 206f; val cy = 228f
    bezel(cx, cy, 166f)
    face(cx, cy, 160f)
    drawCircle(Tone.ring, 96f, Offset(cx, cy), style = Stroke(1f))
    ticks(cx, cy, 160f, d.redline)
    // 外圈數字畫在外殼外面（頁面底色上），跟著日夜換色
    numerals(tm, cx, cy, 178f, d.redline, 22f)
    glass(cx, cy)
    val spd = d.speed?.toString() ?: "--"
    txt(tm, spd, cx, 254f, 88f, Tone.readout, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c', spacing = -.02f)
    txt(tm, "km/h", cx, 278f, 13f, Tone.sub, anchor = 'c', spacing = .06f)
    if (d.connected) {
        val w1 = textWidth(tm, rpmText(d.rpm), 28f, FontWeight.ExtraBold, digits = true)
        val x0 = cx - (w1 + 6 + textWidth(tm, "rpm", 13f)) / 2
        txt(tm, rpmText(d.rpm), x0, 316f, 28f, Tone.readout, FontWeight.ExtraBold, digits = true)
        txt(tm, "rpm", x0 + w1 + 6, 316f, 13f, Tone.sub)
    }
    // 浮動短針：r100–157，不壓到中間的數字
    needle(cx, cy, RevScale.ang(if (d.connected) d.needleRpm else 0.0), false, hub = false, root = 100f)
    // MAP：平行四邊形徽章，放在錶底缺口
    val bx = 206f - 44; val by = 390f
    drawPath(paraPath(bx, by, 88f, 40f), if (Tone.day) Color.White else Inst.bg)
    drawPath(paraPath(bx, by, 88f, 40f), if (Tone.day) Color(0xFF1F0E16) else Inst.rose, style = Stroke(1.5f))
    drawPath(paraPath(bx + 50, by + 5, 32f, 30f), mapFill(d.map))
    txt(tm, "MAP", bx + 14, by + 26, 13f, Tone.sub, spacing = .08f)
    txt(tm, d.map?.toString() ?: "–", bx + 66, by + 32, 28f, Inst.bg, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
}

// ---------------------------------------------------------------- 懸浮小儀表（側翼式，212×128）

data class WingRow(val label: String, val value: String, val unit: String, val lv: Lv)

const val WING_W = 212f
const val WING_H = 128f

/** 側翼式懸浮小儀表：左側三列資料翼＋右側 Ø128 圓錶（轉速環、轉速數字、車速、MAP）。 */
fun DrawScope.wingGauge(tm: TextMeasurer, d: DialData, rows: List<WingRow>) {
    Tone.day = false
    val worst = rows.maxOfOrNull { it.lv.ordinal }?.let { Lv.entries[it] } ?: Lv.OK
    val edge = when (worst) { Lv.FAULT -> Inst.red; Lv.CAUTION -> Inst.rose; Lv.OK -> Inst.mocha.copy(alpha = .45f) }
    val X = 0f; val Y = 6f; val W = 120f; val H = 116f
    // 陰影
    translate(0f, 2f) { drawPath(chamPath(X, Y, W, H, 14f), Color.Black.copy(alpha = .25f)); drawCircle(Color.Black.copy(alpha = .25f), 65f, Offset(148f, 64f)) }
    drawPath(chamPath(X, Y, W, H, 14f), Brush.verticalGradient(listOf(Color(0xFF3A1B2C), Inst.bg), startY = Y, endY = Y + H))
    drawPath(chamPath(X + .75f, Y + .75f, W - 1.5f, H - 1.5f, 13.7f), edge, style = Stroke(if (worst == Lv.FAULT) 2.5f else 1.5f))
    rows.take(3).forEachIndexed { i, r ->
        val top = Y + 8 + i * 35
        val c = when (r.lv) { Lv.OK -> Inst.sakura; Lv.CAUTION -> Inst.rose; Lv.FAULT -> Inst.red }
        if (r.lv != Lv.OK) drawRect(c, Offset(6f, top + 3), Size(3f, 28f))
        txt(tm, r.label, 15f, top + 11, 11f, if (r.lv == Lv.OK) Inst.beige else c, alpha = if (r.lv == Lv.OK) .85f else 1f)
        val w = txt(tm, r.value, 15f, top + 30, 21f, c, FontWeight.ExtraBold, digits = true, skew = true)
        if (r.unit.isNotEmpty()) txt(tm, r.unit, 15f + w + 3, top + 30, 10.5f, Inst.beige, alpha = .85f)
        if (i < 2) drawLine(Inst.mocha.copy(alpha = .28f), Offset(15f, top + 35.5f), Offset(72f, top + 35.5f), 1f)
    }
    // 圓錶
    drawCircle(Color(0xFF0B0809), 67f, Offset(148f, 64f))
    translate(84f, 0f) { miniDial(tm, d, worst == Lv.FAULT) }
}

/** Ø128 小圓錶本體（座標原點在圓的外接方框左上角）。 */
fun DrawScope.miniDial(tm: TextMeasurer, d: DialData, fault: Boolean) {
    Tone.day = false
    val cx = 64f; val cy = 64f
    drawCircle(Brush.verticalGradient(listOf(Inst.rimTop, Inst.rimBottom)), 64f, Offset(cx, cy))
    drawCircle(Brush.radialGradient(listOf(Inst.surf2, Inst.bg), center = Offset(64f, 48f), radius = 76f), 62f, Offset(cx, cy))
    ledRing(d, cx, cy, 51f, 60f, glowOn = false, cap = true, gapPx = 55.5f * Inst.PITCH * PI.toFloat() / 180f - 6.8f)
    for (k in listOf(5000.0, 10000.0)) { val a = RevScale.ang(k); drawLine(Inst.beige.copy(alpha = .4f), pt(cx, cy, 46f, a), pt(cx, cy, 49f, a), 2f) }
    drawCircle(if (fault) Inst.red else Inst.mocha, 63f, Offset(cx, cy), style = Stroke(if (fault) 3.5f else 1.5f))
    if (d.connected) txt(tm, rpmText(d.rpm), cx, 36f, 13f, Inst.beige, FontWeight.Bold, digits = true, anchor = 'c')
    txt(tm, d.speed?.toString() ?: "--", cx, 81f, 44f, Inst.sakura, FontWeight.ExtraBold, digits = true, skew = true, anchor = 'c')
    drawPath(paraPath(44f, 98f, 40f, 20f), mapFill(d.map))
    val mw = textWidth(tm, "M", 10f, FontWeight.ExtraBold, digits = true)
    val nw = textWidth(tm, d.map?.toString() ?: "–", 18f, FontWeight.ExtraBold, digits = true)
    val x0 = 64f - (mw + 1.8f + nw) / 2
    txt(tm, "M", x0, 114f, 10f, Inst.bg, FontWeight.ExtraBold, digits = true)
    txt(tm, d.map?.toString() ?: "–", x0 + mw + 1.8f, 114.2f, 18f, Inst.bg, FontWeight.ExtraBold, digits = true)
}

/** 依目前的狀態算出要給儀表畫的一幀資料（主畫面與懸浮窗共用）。 */
class DialAnimator(var settings: RevFlash.Settings = RevFlash.Settings()) {
    private val flash = RevFlash(settings)
    private var needle = 0.0
    private var lastMs = -1L
    private var peak = 0.0
    private var peakAt = 0L
    /** 只給預覽圖用：固定顯示一條最高轉速記號。 */
    companion object { var demoPeak: Double? = null }

    fun frame(tMs: Long, rpm: Double?, tps: Double?, speed: Int?, map: Int?, connected: Boolean): DialData {
        flash.settings = settings
        val out = flash.update(tMs, rpm, tps)
        val r = rpm ?: 0.0
        val dt = if (lastMs < 0) 0.0 else (tMs - lastMs).coerceIn(0, 200).toDouble()
        lastMs = tMs
        // 2,500 rpm 以下不平滑（看得到怠速游動）；以上臨界阻尼 τ≈70 ms
        needle = if (r < 2500 || dt == 0.0) r else needle + (r - needle) * (1 - kotlin.math.exp(-dt / 70.0))
        // peak-hold：最高轉速保持 3 秒，再淡出 0.4 秒
        if (r >= peak || tMs - peakAt > 3400) { if (r >= peak || r > 0) { peak = r; peakAt = tMs } }
        val age = tMs - peakAt
        // 小藍條：轉速掉下來（比最高低 250 以上）才顯示，不限高轉
        demoPeak?.let { return DialData(r, needle, speed, map, settings.redline, out, it, 1f, connected) }
        val alpha = if (peak < 1500 || peak - r < 250) 0f else if (age <= 3000) 1f else max(0f, 1f - (age - 3000) / 400f)
        return DialData(r, needle, speed, map, settings.redline, out, peak.takeIf { it > 0 }, min(1f, alpha), connected)
    }
}

