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
背景知識（用來判斷，不是用來嚇車主）：
- 速克達是 CVT 無段變速：全油門時轉速常被傳動維持在一個區間，所以「全油門時轉速變化很小」是正常的；不要把它當成怠速不穩或斷火。
- 怠速轉速、油門、點火角度、噴油時間、歧管壓力都會隨負載與海拔、氣溫變動；比較「同一種工況」才有意義，不要拿怠速和巡航的數字互相比。
- 水冷車引擎溫度穩定在 80–100°C 屬正常；氣冷車（或油冷）會高很多，100–130°C 不一定有問題，要看車主填的冷卻方式。
- 充電電壓怠速時約 13.0–14.8 V 屬常見範圍；一般是車子開始騎、轉速上來後比怠速高。
- 原廠窄頻含氧感知器（NBO2）電壓正常會在約 100–900 mV 之間快速來回跳（閉迴路在修正混合比）；長時間貼在低檔或高檔才可疑。沒有 AF2 時，空燃比欄位是 0 或空的，不是故障，也不要拿它判斷濃稀。
- 燃油修正（Fuel_CL）是 ECU 為了把混合比拉到目標而加減的百分比：長期偏離 0 很多（例如超過 ±10%）才值得提；偶爾短暫偏離正常。
- 紀錄只代表這一趟；單趟資料、冷車短程、塞車為主時，結論的可信度低，要講明白。
規則：
- 用繁體中文、口語、具體。總長 500 字以內。只寫純文字：不要用 ** 粗體、# 標題、表格或其他 Markdown 符號；要分項就用「1.」「2.」或「・」開頭。
- 「車輛資料」是車主自己填的，照字面理解，不要自行延伸或另外猜車款；沒填的就是不知道，結論要保守。規則健檢的門檻是通用值，氣冷車、改裝車的正常範圍不同時，以車輛資料為準，不要照規則的「偏高」就下結論。
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
        appendLine("車輛資料（車主填的）：")
        appendLine(vehicleNote.ifBlank { "車主沒填車款。只知道是接 aRacer Super X 噴射電腦的速克達，缸徑、冷卻方式、改裝都不確定，相關結論要保守。" })
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
