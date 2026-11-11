package io.github.xeno_hf.rex.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.xeno_hf.rex.ui.HealthReportBody
import io.github.xeno_hf.rex.ui.RxColors
import io.github.xeno_hf.rex.ui.RxTheme
import io.github.xeno_hf.rex.ui.Strings
import io.github.xeno_hf.rex.ui.ThemeChoice
import org.jetbrains.skia.EncodedImageFormat
import rx.defs.Loga
import rx.session.EngineHealth
import java.io.File

/** 開發用：用合成的騎乘資料畫出引擎健檢頁（手機直向大小），輸出 PNG。 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }
    Strings.setLanguage(listOf(System.getProperty("rx.lang") ?: "zh-Hant-TW"))
    val cases = mapOf("problems" to EngineHealth.analyze(ride(problems = true)), "healthy" to EngineHealth.analyze(ride(problems = false)))
    for ((name, report) in cases) for (theme in listOf(ThemeChoice.NIPPON, ThemeChoice.EMERALD)) {
        val density = 2f
        val scene = ImageComposeScene((412 * density).toInt(), (if (name == "problems") 1180 else 900).times(density).toInt(), Density(density)) {
            RxTheme(theme) { Box(Modifier.fillMaxSize().background(RxColors.bg).padding(12.dp)) { HealthReportBody(report) } }
        }
        scene.render()
        Thread.sleep(300) // 字型資源載入後再畫一次
        val file = File(out, "health_${name}_${theme.name.lowercase()}.png")
        file.writeBytes(scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println(file.path)
    }
}

/** 合成騎乘（10 Hz、6 分鐘）：怠速 → 巡航 → 全油門。[problems] 加上過熱、全油門偏稀、充電偏低與一個故障碼。 */
private fun ride(problems: Boolean): Loga {
    val names = listOf("Time", "RPM", "T_Eng", "Volt_Batt", "TPS_Percent", "Vehicle_Speed", "AFR_WBO2", "AFR_Target", "Fuel_CL",
        "Cyl1_Injector_Period_Rate", "Miss_CRK_Cnt", "Idle_Desir_RPM", "Malf7.P0562")
    val sb = StringBuilder("<VAR ID>\n").append(names.indices.joinToString(",")).append("\n<VAR NAME>\n").append(names.joinToString(",")).append("\n<DATA START>\n")
    val n = 3600
    for (i in 0 until n) {
        val t = i / 10.0
        val idle = t < 90
        val wot = t in 300.0..330.0
        val wave = kotlin.math.sin(t / 7.0)
        val rpm = if (idle) 1500 + wave * 25 else if (wot) 6000 + (t - 300) * 110 else 4800 + wave * 900
        val temp = (60 + t * 0.35).coerceAtMost(if (problems) 122.0 else 96.0)
        val volt = (if (problems) 12.8 else 14.1) + wave * 0.08
        val target = if (wot) 12.8 else 14.5
        val afr = target + wave * 0.15 + if (problems && wot) 1.2 else 0.0
        val row = listOf(t * 1000, rpm, temp, volt, if (idle) 0.0 else if (wot) 100.0 else 32 + wave * 12, if (idle) 0.0 else 62 + wave * 15,
            afr, target, if (problems) 12.0 + wave else 2.0 + wave, if (wot) 72.0 else 22.0, 0.0, 1500.0, if (problems && t > 200) 1.0 else 0.0)
        sb.append(row.joinToString(",")).append('\n')
    }
    return Loga.parse(sb.toString().encodeToByteArray())
}
