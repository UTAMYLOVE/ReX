package io.github.xeno_hf.rex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xeno_hf.rex.res.Res
import io.github.xeno_hf.rex.res.dela_gothic_wordmark
import org.jetbrains.compose.resources.Font
import kotlin.math.tan

/*
 * 夜櫻的「環狀族」視覺元件：貼紙字標、車隊斜條、編號路牌標題、切角卡片、斜切分段鈕、切角開關、斜切按鈕。
 * 規則：斜角一律 10°、切角一律 45°、圓角不超過 2 dp（圓角看起來太可愛）。顏色都從 [RxColors] 來，換主題自動跟著變。
 */

/** 設計常數與衍生色。 */
object Yz {
    /** 10° 斜角的水平位移比例。 */
    val T10 = tan(10.0 * kotlin.math.PI / 180).toFloat()
    /** 櫻粉（六色之一）：字標、重點。 */
    val pastel = Color(0xFFFFFFFF)
    val rose = Color(0xFFDC9FB4)
    /** 粉色在深底上還看得出是粉的那一階：✓、日期牌、小標。 */
    val petal = Color(0xFFFFFFFF)
    val aqua = Color(0xFFA5DEE4)
    val mocha = Color(0xFF9E7A7A)
    val beige = Color(0xFFD7C4BB)
    /** 頂列與底部頁籤的深色帶（日夜都一樣）。 */
    val band = Color(0xFFDC9FB4)
    /** 粉色、紅色、白色底上的深色字（玫瑰黑紫）。 */
    val ink = Color(0xFF9E7A7A)
    /** 沒選的斜切板外框。 */
    val slabLine get() = if (RxColors.palette.light) Color(0xFFFEDFE1) else Color(0xFF9E7A7A)
    /** 小標、單位（夜間玫瑰、日間深玫瑰）。 */
    val label get() = if (RxColors.palette.light) Color(0xFF9E7A7A) else rose
    /** 「注意」：玫瑰粉（不用黃色）。「危險」：紅。 */
    val caution get() = RxColors.warn
    val danger get() = RxColors.danger
    /** 主要框線（夜間白、日間深字色）。 */
    val keyline get() = if (RxColors.palette.light) Color(0xFF9E7A7A) else Color(0xFFFFFFFF)
}

/** 10° 平行四邊形（左下往右上斜）。 */
val SlantShape: Shape = GenericShape { size, _ ->
    val o = size.height * Yz.T10
    moveTo(o, 0f); lineTo(size.width, 0f); lineTo(size.width - o, size.height); lineTo(0f, size.height); close()
}

/** 卡片：右下切角。 */
val YzCardShape = CutCornerShape(bottomEnd = 10.dp)
/** 狀態 chip：左上、右下切角。 */
val YzChipShape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)

/** 卡片樣式的 Column。 */
@Composable
fun YzCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(RxColors.card, YzCardShape).padding(horizontal = 16.dp, vertical = 12.dp), content = content)
        // 選中／最新：左上角露出一小截車隊色「拖車勾」
        if (accent != null) Box(Modifier.padding(start = 14.dp).size(28.dp, 3.dp).background(accent))
    }
}

/** 車隊斜條 ///（寬度遞減）。 */
@Composable
fun YzSlashes(color: Color = Yz.rose, height: Dp = 14.dp) {
    Canvas(Modifier.size(height * 1.6f, height)) {
        val h = size.height; val o = h * Yz.T10 * 1.6f
        var x = 0f
        listOf(0.30f, 0.20f, 0.12f).forEachIndexed { i, w ->
            val ww = size.width * w
            val p = Path().apply { moveTo(x + o, 0f); lineTo(x + o + ww, 0f); lineTo(x + ww, h); lineTo(x, h); close() }
            drawPath(p, color.copy(alpha = 1f - i * 0.2f))
            x += ww + size.width * 0.12f
        }
    }
}

/** 字標用的粗黑體（Dela Gothic One，OFL；只收「夜櫻桜樱」四個字）。 */
@Composable
private fun wordmarkFamily() = FontFamily(Font(Res.font.dela_gothic_wordmark, FontWeight.Normal))

/**
 * 貼紙字標「夜櫻」：最外圈玫瑰色描邊、中間一圈深色空隙、最上面填色（像切割貼紙，不用偏移陰影）。
 */
@Composable
fun YzStickerWordmark(size: Int = 26, fill: Color = Yz.pastel, keyline: Color = Yz.rose, gap: Color = Yz.ink) {
    val family = wordmarkFamily()
    val base = TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.15f).sp)
    Box {
        Text("夜櫻", style = base.copy(color = keyline, drawStyle = Stroke(width = size * 0.42f, join = StrokeJoin.Round)))
        Text("夜櫻", style = base.copy(color = gap, drawStyle = Stroke(width = size * 0.18f, join = StrokeJoin.Round)))
        Text("夜櫻", style = base.copy(color = fill))
    }
}

/** 「YOZAKURA」斜切板。 */
@Composable
fun YzNameSlab(fill: Color = Yz.pastel, text: Color = Yz.ink, keyline: Color = Yz.rose) {
    Box(
        Modifier.border(1.5.dp, keyline, SlantShape).padding(2.dp).background(fill, SlantShape).padding(horizontal = 10.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("YOZAKURA", color = text, style = digits(10.sp, FontWeight.ExtraBold, italic = true).copy(letterSpacing = 1.5.sp))
    }
}

/** 頂列（ハチマキ）：貼紙字標＋名牌＋斜條，下緣一條車隊色細線（不畫到底，右邊留給狀態 chip）。 */
@Composable
fun YzBanner(modifier: Modifier = Modifier, end: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().background(Yz.band).then(modifier)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            YzStickerWordmark()
            Spacer(Modifier.width(10.dp))
            YzNameSlab()
            Spacer(Modifier.width(8.dp))
            YzSlashes()
            Spacer(Modifier.weight(1f))
            end()
        }
        // 下緣雙細線：櫻粉 2 dp＋玫瑰 1 dp，不畫到底（右邊留給狀態 chip）
        Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)) {
            drawRect(Yz.pastel, size = size.copy(width = size.width * 0.74f, height = 2.dp.toPx()))
            drawRect(Yz.rose, topLeft = Offset(0f, 3.dp.toPx()), size = size.copy(width = size.width * 0.70f, height = 1.dp.toPx()))
        }
    }
}

/** 斜線紋（「重連中」等狀態的端蓋）。 */
@Composable
fun YzHatch(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val step = 6.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2.2f)
            x += step
        }
    }
}

/** 狀態 chip：切角框、狀態色外框；[hatch] 時左側加斜線紋端蓋。 */
@Composable
fun YzStateChip(text: String, color: Color, dot: Boolean, hatch: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier.clip(YzChipShape).background(Color(0xFFDC9FB4), YzChipShape)
            .border(1.5.dp, color.copy(alpha = 0.85f), YzChipShape).heightIn(min = if (compact) 26.dp else 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hatch) YzHatch(color, Modifier.size(14.dp, if (compact) 26.dp else 32.dp))
        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        if (dot) { Box(Modifier.size(8.dp).background(color, CutCornerShape(4.dp))); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (hatch) color else Color(0xFFFFFFFF), fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Spacer(Modifier.width(if (compact) 6.dp else 12.dp))
    }
}

/** 區塊標題（高速公路路牌編號）：[n] 標題 /// ────。 */
@Composable
fun YzSectionHeader(index: Int?, title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (index != null) {
            // 路牌編號：櫻粉斜切板＋深色兩位數字
            Box(Modifier.background(Yz.pastel, SlantShape).border(1.dp, if (RxColors.palette.light) Yz.ink else Yz.pastel, SlantShape).padding(horizontal = 8.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
                Text(index.toString().padStart(2, '0'), color = Yz.ink, style = digits(12.sp, FontWeight.ExtraBold, italic = true))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(title, color = if (RxColors.palette.light) RxColors.text else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(10.dp))
        YzSlashes()
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(RxColors.line))
    }
}

/** 卡片裡的一列：左標題、右控制項；[divider] 時上方一條細線。 */
@Composable
fun YzRow(label: String, divider: Boolean = false, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit) {
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(RxColors.line))
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RxColors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 斜切分段鈕（A／B／C、主題、紅線…）：選中的填櫻粉、深色字。 */
@Composable
fun <T> YzSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, minWidth: Dp = 44.dp) {
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Box(
                Modifier.clip(SlantShape).then(if (sel) Modifier.background(Yz.pastel, SlantShape).border(1.dp, if (RxColors.palette.light) Yz.ink else Yz.pastel, SlantShape) else Modifier.border(1.5.dp, Yz.slabLine, SlantShape))
                    .clickable { onSelect(v) }.widthIn(min = minWidth).height(36.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Yz.ink else Yz.label, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** 切角開關：開＝櫻粉軌道＋深色方塊；關＝深色軌道＋茶褐框＋空心方塊。 */
@Composable
fun YzSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val shape = CutCornerShape(topStart = 5.dp, bottomEnd = 5.dp)
    Box(
        Modifier.size(54.dp, 30.dp).clip(shape).then(if (checked) Modifier.background(Yz.pastel, shape).border(1.dp, if (RxColors.palette.light) Yz.ink else Yz.pastel, shape) else Modifier.background(RxColors.track, shape).border(1.5.dp, Yz.mocha, shape))
            .clickable { onChange(!checked) }.padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).then(if (checked) Modifier.background(Yz.ink, shape) else Modifier.background(Yz.beige.copy(alpha = .35f), shape).border(1.5.dp, Yz.beige, shape)))
    }
}

/** 斜切按鈕。[primary]＝櫻粉實心；預設＝框線＋左緣玫瑰色條；[danger]＝紅字。 */
@Composable
fun YzButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier.clip(SlantShape)
            .then(if (primary) Modifier.background(Yz.pastel.copy(alpha = alpha), SlantShape).border(1.dp, (if (RxColors.palette.light) Yz.ink else Yz.pastel).copy(alpha = alpha), SlantShape)
                else Modifier.border(1.5.dp, (if (danger) Yz.danger else Yz.slabLine).copy(alpha = alpha), SlantShape))
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!primary && !danger) Box(Modifier.align(Alignment.CenterStart).padding(start = 2.dp).size(5.dp, 26.dp).background(Yz.rose.copy(alpha = alpha), SlantShape))
        Text(
            text, textAlign = TextAlign.Center, maxLines = 1, fontSize = 15.sp, fontWeight = FontWeight.Black,
            color = (if (primary) Yz.ink else if (danger) Yz.danger else RxColors.text).copy(alpha = alpha),
            modifier = Modifier.padding(start = if (!primary && !danger) 8.dp else 0.dp),
        )
    }
}

/** 直排「夜櫻」車隊貼紙（雙框）。 */
@Composable
fun YzDecal(modifier: Modifier = Modifier, scale: Float = 1f) {
    val family = wordmarkFamily()
    Box(modifier.border((1 * scale).dp, Yz.rose).padding((3 * scale).dp).background(Yz.rose.copy(alpha = 0.92f)).padding(horizontal = (6 * scale).dp, vertical = (4 * scale).dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            listOf("夜", "櫻").forEach { Text(it, fontFamily = family, fontSize = (22 * scale).sp, lineHeight = (25 * scale).sp, color = Yz.ink) }
            Spacer(Modifier.height((2 * scale).dp))
            YzSlashes(Yz.ink, (7 * scale).dp)
        }
    }
}

/** 對話框內容外框（切角、細框）：用在自己畫的彈窗。 */
@Composable
fun YzPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.background(RxColors.card, YzCardShape).border(1.dp, RxColors.line, YzCardShape).padding(16.dp), content = content)
}
