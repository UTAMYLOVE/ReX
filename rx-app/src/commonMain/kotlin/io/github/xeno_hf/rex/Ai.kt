package io.github.xeno_hf.rex

import rx.protocol.Fmt
import rx.session.EngineHealth

/** AI 解讀服務。 */
enum class AiProvider(val id: String, val label: String, val defaultModel: String) {
    CLAUDE("claude", "Claude", "claude-opus-5-5"),
    DEEPSEEK("deepseek", "DeepSeek", "deepseek-chat");

    companion object { fun parse(s: String?) = entries.firstOrNull { it.id == s } ?: CLAUDE }
}

/** 平台實作（Android：Claude 用官方 Java SDK，DeepSeek 用 HTTPS）。 */
interface AiClient {
    /** 回傳模型寫的純文字；失敗丟例外（訊息會直接顯示給使用者）。 */
    suspend fun complete(provider: AiProvider, apiKey: String, model: String, system: String, user: String): String
}

/**
 * 給 AI 的提示詞。送健檢摘要（分數、數值、規則發現）＋引擎數據詳細摘要（[rx.session.EngineDigest]）；
 * 不送原始紀錄、車速、GPS 或裝置資訊。
 */
object AiPrompt {
    val SYSTEM = """
你是機車引擎診斷助手，協助台灣的速克達車主看懂 aRacer 噴射電腦的騎乘紀錄摘要。
規則：
- 用繁體中文、口語、具體。總長 500 字以內。只寫純文字：不要用 ** 粗體、# 標題、表格或其他 Markdown 符號；要分項就用「1.」「2.」或「・」開頭。
- 「車輛」那行是車主自己寫的說明（改裝、缸徑等），照字面理解，不要自行延伸。
- 先用一句話講整體狀況，再逐項說明有問題的地方：可能原因（由最常見到少見）、先檢查什麼、要不要停騎。
- 標了【樣本太少，僅供參考】的工況（例如全油門只占 1%）不要下結論，最多提一句「資料太少看不出來」。數據之間互相矛盾、或你不確定某個數值的意義時，直接說不確定，不要硬解釋。
- 善用「引擎數據詳細摘要」：比對各工況（怠速／巡航／全油門）的點火角度、噴油時間、歧管壓力、含氧電壓、燃油修正，看溫度變化與暖車時間、怠速穩定度、曲軸訊號遺失，找出規則健檢沒抓到的趨勢；沒有異常就簡短說哪些地方看起來健康。
- 沒有寬頻空燃比（AF2）時，用原廠含氧感知器電壓（約 100–900 mV 來回跳＝閉迴路正常；長時間偏低＝偏稀、偏高＝偏濃）和燃油修正判斷混合比，並說明這只是粗略判斷。
- 門檻是一般四行程機車的通用值，不是這台車的規格。改缸、改排氣、氣冷車的正常範圍可能不同，必要時提醒車主。
- 資料不足或只有一趟紀錄時，說清楚哪些結論不確定，不要編造數據裡沒有的東西。
- 有過熱、全油門偏稀、充電電壓異常這類可能傷車的情況，要明確建議先停騎或盡快檢查。
- 不要建議修改 ECU 調校數值的具體數字；調校交給店家或車主自己用 aRacer 官方工具。
""".trim()

    fun user(r: EngineHealth.Report, vehicleNote: String, rideName: String, findingText: (EngineHealth.Finding) -> String, metricLabel: (String) -> String, digest: String? = null): String = buildString {
        appendLine("車輛：${vehicleNote.ifBlank { "未填（aRacer Super X 噴射電腦的速克達）" }}")
        appendLine("紀錄：$rideName，引擎運轉 ${Fmt.fixed(r.runningSec / 60, 1)} 分鐘，${r.samples} 筆資料")
        appendLine("規則健檢總分：${r.score}／100（${r.level}）")
        appendLine("關鍵數值：")
        r.metrics.forEach { m ->
            appendLine("- ${metricLabel(m.key)}：${m.value?.let { Fmt.fixed(it, m.decimals) + " " + m.unit } ?: "紀錄裡沒有"}${if (m.level != EngineHealth.Level.OK) "（${m.level}）" else ""}")
        }
        appendLine("規則判斷的發現：")
        if (r.findings.isEmpty()) appendLine("- 沒有")
        r.findings.forEach { appendLine("- [${it.level}] ${findingText(it)}") }
        appendLine("沒檢查到的系統：${r.areas.filter { !it.checked }.joinToString("、") { it.area.name }.ifEmpty { "無" }}")
        if (!digest.isNullOrBlank()) { appendLine(); appendLine(digest) }
        append("請依上面的資料解讀這趟騎乘的引擎狀況。")
    }
}
