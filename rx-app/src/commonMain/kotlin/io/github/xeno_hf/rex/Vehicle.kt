package io.github.xeno_hf.rex

/**
 * 車輛資料問卷：第一次開 App 先問，設定裡可以再改。答案存在偏好設定（鍵 veh.<id>），
 * 整理成一段文字交給 AI（[describe]）。問卷文字寫死繁體中文（App 主要給台灣車主用）。
 */
object VehicleProfile {
    enum class Kind { TEXT, SINGLE, MULTI }
    class Q(val id: String, val label: String, val kind: Kind, val options: List<String> = emptyList(), val hint: String = "")
    class Step(val title: String, val intro: String, val questions: List<Q>)

    const val SEP = "|"

    val STEPS = listOf(
        Step("車子", "先告訴我是什麼車。不確定的可以留空或選「不確定」，AI 會保守一點。", listOf(
            Q("model", "車款／型號", Kind.TEXT, hint = "例：勁戰六代、JET SL、DRG、G6、Force"),
            Q("year", "年份", Kind.TEXT, hint = "例：2022"),
            Q("cc", "排氣量（cc；改過就填現在的）", Kind.TEXT, hint = "例：155"),
            Q("cooling", "冷卻方式", Kind.SINGLE, listOf("水冷", "氣冷", "油冷", "不確定")),
            Q("trans", "傳動", Kind.SINGLE, listOf("CVT 速克達", "手排／打檔車", "半自動／離合器自動")),
            Q("inj", "噴油", Kind.SINGLE, listOf("單噴油嘴", "雙噴油嘴", "不確定")),
        )),
        Step("感測器與 ECU", "這些會決定哪些數據可以信。", listOf(
            Q("ecu", "噴射電腦", Kind.SINGLE, listOf("aRacer Super X", "aRacer Mini X", "其他 aRacer", "不確定")),
            Q("af2", "有沒有裝 AF2 寬頻空燃比感知器", Kind.SINGLE, listOf("有", "沒有", "不確定")),
            Q("tune", "ECU 調校狀態", Kind.SINGLE, listOf("出廠／原廠油圖", "已經請店家調校過", "自己調過", "不確定")),
            Q("maps", "MAP 1／2／3 各自用途", Kind.TEXT, hint = "例：1 省油、2 一般、3 全開；沒用就留空"),
        )),
        Step("改裝", "有改的都勾起來。改裝會讓很多數據的「正常範圍」不一樣。", listOf(
            Q("mods", "改裝項目（可多選）", Kind.MULTI, listOf("排氣管", "空濾／進氣", "加大缸徑／行程", "汽門／凸輪", "加大噴嘴", "加大節氣門", "點火（火星塞／線圈）", "普利盤／壓板／普利珠", "離合器／傳動", "散熱（水箱／機油冷卻）", "都沒改")),
            Q("modsText", "改裝細節（品牌、規格都可以）", Kind.TEXT, hint = "例：加大到 70 缸、某牌排氣管、噴嘴 200cc"),
            Q("fuel", "常加的汽油", Kind.SINGLE, listOf("92", "95", "98", "不一定")),
        )),
        Step("使用狀況", "怎麼騎，也會影響怎麼判斷。", listOf(
            Q("use", "主要用途", Kind.SINGLE, listOf("通勤", "休閒", "常拉高轉／跑山", "通勤和休閒都有", "外送／長時間騎")),
            Q("road", "常騎的路況（可多選）", Kind.MULTI, listOf("市區塞車", "快速道路／高速", "山路", "常載人或載重")),
            Q("daily", "平均每天騎多遠", Kind.SINGLE, listOf("10 公里內", "10–30 公里", "30–60 公里", "60 公里以上")),
            Q("oil", "機油多久換、上次什麼時候換", Kind.TEXT, hint = "例：每 2000 km，上次 800 km 前"),
            Q("concern", "最想知道／最擔心的是什麼", Kind.TEXT, hint = "例：怕過熱、油圖會自己跳、怠速不穩"),
        )),
    )

    private fun all() = STEPS.flatMap { it.questions }

    /** 整理成一段文字給 AI；沒填的不寫。 */
    fun describe(get: (String) -> String, freeNote: String): String = buildString {
        for (q in all()) {
            val v = get(q.id).trim()
            if (v.isEmpty()) continue
            val shown = if (q.kind == Kind.MULTI) v.split(SEP).filter { it.isNotBlank() }.joinToString("、") else v
            val unit = if (q.id == "cc") " cc" else ""
            appendLine("${q.label.substringBefore('（')}：$shown$unit")
        }
        val notes = ArrayList<String>()
        val cooling = get("cooling"); val af2 = get("af2"); val inj = get("inj"); val mods = get("mods").split(SEP).toSet()
        if (cooling == "氣冷" || cooling == "油冷") notes += "氣冷／油冷車，引擎溫度的正常範圍比水冷高，不要照水冷的標準判斷過熱。"
        if (af2 == "沒有") notes += "沒有 AF2：空燃比欄位是空的或 0，不是故障；混合比只能看原廠窄頻含氧電壓和燃油修正。"
        if (inj == "雙噴油嘴") notes += "雙噴油嘴：紀錄裡只有第一支噴嘴的噴油時間，看不到第二支，不要因此判斷噴油量不足。"
        if (get("tune") != "出廠／原廠油圖" && get("tune").isNotEmpty()) notes += "ECU 已調校過，目標空燃比、點火角度和噴油時間都可能跟原廠不同，用這趟自己的數字比，不要套原廠標準。"
        if ("加大缸徑／行程" in mods || "汽門／凸輪" in mods) notes += "改過缸徑／汽門，空氣量和怠速進氣需求會變大；怠速偏低或怠速進氣控制量偏高不一定是故障。"
        if ("排氣管" in mods || "空濾／進氣" in mods) notes += "改過排氣或進氣，混合比偏離原廠很常見，要看燃油修正有沒有長期偏離，而不是單看空燃比。"
        if (get("daily") == "10 公里內") notes += "每天騎很短：冷車短程多，電瓶、積碳和機油稀釋要多留意；單趟溫度偏低可能只是還沒熱透。"
        if (notes.isNotEmpty()) { appendLine("判斷提示："); notes.forEach { appendLine("- $it") } }
        if (freeNote.isNotBlank()) appendLine("車主補充：${freeNote.trim()}")
    }.trim()
}
