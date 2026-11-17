package rx.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rx.defs.Loga

/** Synthetic ride only. Checks what the AI gets: engine data yes, speed/GPS no. */
class EngineDigestTest {
    private val names = listOf(
        "Time", "RPM", "TPS_Percent", "T_Eng", "T_Air", "SA", "Cyl1_Comn_FuelPW", "Cyl1_Eng_AP", "Volt_Batt",
        "AFR_Target", "AFR_WBO2", "NBO2_Volt", "Fuel_CL", "Idle_Desir_RPM", "Miss_CRK_Cnt", "MapNum",
        "Vehicle_Speed", "Phone_GPS_Lat", "Phone_GPS_Lon", "Malf0.P0117", "Malf8.MILReq",
    )

    /** 10 Hz, 10 minutes: cold start, idle 2 min, cruise, last 20 s wide open; no AF2 (AFR_WBO2 = 0). */
    private fun ride(): Loga {
        val sb = StringBuilder("<aRacer MX APP Log File>\nCreated Date:2026-01-01 00:00:00\n\n<VAR ID>\n")
        sb.append(names.indices.joinToString(",")).append("\n<VAR GROUP>\n").append(names.joinToString(",") { "Stage_1" })
        sb.append("\n<VAR NAME>\n").append(names.joinToString(",")).append("\n<DATA START>\n")
        val n = 600 * 10
        for (i in 0 until n) {
            val t = i / 10.0
            val idle = t < 120
            val wot = t >= 580
            val row = linkedMapOf(
                "Time" to t * 1000, "RPM" to if (idle) 1600.0 + (i % 7 - 3) * 15 else if (wot) 8800.0 else 5200.0,
                "TPS_Percent" to if (idle) 0.0 else if (wot) 100.0 else 25.0,
                "T_Eng" to minOf(95.0, 30.0 + t * 0.25), "T_Air" to 38.0, "SA" to if (wot) 28.0 else 22.0,
                "Cyl1_Comn_FuelPW" to if (wot) 6.5 else 2.4, "Cyl1_Eng_AP" to if (wot) 98.0 else 55.0, "Volt_Batt" to 14.2,
                "AFR_Target" to if (wot) 12.8 else 14.5, "AFR_WBO2" to 0.0, "NBO2_Volt" to (if (i % 10 < 5) 150.0 else 750.0),
                "Fuel_CL" to 1.5, "Idle_Desir_RPM" to 1600.0, "Miss_CRK_Cnt" to (if (t > 300) 3.0 else 0.0),
                "MapNum" to 2.0, "Vehicle_Speed" to if (idle) 0.0 else 77.0, "Phone_GPS_Lat" to 25.0478, "Phone_GPS_Lon" to 121.5319,
                "Malf0.P0117" to (if (t > 400) 1.0 else 0.0), "Malf8.MILReq" to 1.0,
            )
            sb.append(names.joinToString(",") { row.getValue(it).toString() }).append('\n')
        }
        return Loga.parse(sb.toString().encodeToByteArray())
    }

    @Test fun includesEngineDataAndNotSpeedOrLocation() {
        val d = EngineDigest.build(ride())
        // 引擎數據有進去
        for (s in listOf("轉速", "點火角度", "噴油時間", "歧管壓力", "原廠含氧感知器電壓", "各工況", "全油門", "轉速分布", "暖車", "怠速", "曲軸訊號遺失：這趟增加 3 次", "MAP 2", "P0117")) {
            assertTrue(s in d, "missing '$s' in:\n$d")
        }
        // 沒裝 AF2：要說沒有，不能拿 0 去算
        assertTrue("寬頻空燃比（AF2）" in d.substringAfter("紀錄裡沒有或讀數無效"), d)
        // 車速、GPS、狀態旗標不能出現
        for (s in listOf("Vehicle_Speed", "車速", "km/h", "77.0", "GPS", "25.04", "121.53", "MILReq")) assertFalse(s in d, "leaked '$s' in:\n$d")
        // 篇幅要合理（AI 按字數計費）
        assertTrue(d.length < 3000, "too long: ${d.length}")
    }

    @Test fun shortOrEmptyRides() {
        val empty = Loga(emptyMap(), listOf(0, 1), listOf("a", "a"), listOf("Time", "RPM"), emptyList(), 0)
        assertTrue(EngineDigest.build(empty).startsWith("（"))
    }
}
