package rx.session

import kotlinx.io.Source
import kotlinx.io.readLine
import rx.defs.Loga

/**
 * 省記憶體的 `.loga` 讀取，給引擎健檢和 AI 摘要用。
 *
 * [Loga.parse] 會把整個檔案變成一個大字串、每一列留下全部三百多欄，騎一小時就要兩百多 MB，手機會記憶體不足。
 * 這裡改成一行一行讀、只留 [keep] 要的欄位；列數太多時每 N 列留一列（旗標欄取這 N 列的最大值，短暫出現的故障碼不會漏）。
 */
object LogaReader {
    /** 健檢和 AI 摘要用得到的欄位：所有數值通道，加上故障碼和幾個狀態旗標。 */
    fun healthColumn(name: String): Boolean =
        '.' !in name || name == "Fuel5.WBO2_Ready" || name == "Fuel1.Dec_FC_En" || name == "Mode1.Eng_Over_Heat" || DecodedLayout.isFaultCode(name)

    fun read(source: Source, sizeBytes: Long, keep: (String) -> Boolean = ::healthColumn, maxRows: Int = 60_000): Loga {
        val header = LinkedHashMap<String, String>()
        val sections = HashMap<String, List<String>>()
        var headBytes = 0L
        var pendingTag: String? = null
        var found = false
        while (true) {
            val line = source.readLine() ?: break
            headBytes += line.length + 1
            if (line.startsWith("<DATA START>")) { found = true; break }
            val tag = pendingTag
            when {
                tag != null -> { sections[tag] = line.split(','); pendingTag = null }
                line.startsWith("<") -> pendingTag = line.substringAfter('<').substringBefore('>').takeIf { it.startsWith("VAR ") }
                else -> { val k = line.indexOf(':'); if (k > 0) header[line.substring(0, k)] = line.substring(k + 1) }
            }
        }
        require(found) { "not a loga: missing <DATA START>" }
        val allNames = (sections["VAR NAME"] ?: emptyList()).map { it.trim() }
        val allIds = (sections["VAR ID"] ?: emptyList()).map { it.trim().toIntOrNull() }
        val allGroups = sections["VAR GROUP"] ?: emptyList()
        val total = allNames.size
        require(total > 0) { "not a loga: no <VAR NAME>" }

        // 第 0 欄（時間）一定留
        val out = IntArray(total) { -1 }
        val keptIdx = ArrayList<Int>()
        for (j in 0 until total) if (j == 0 || keep(allNames[j])) { out[j] = keptIdx.size; keptIdx += j }
        val k = keptIdx.size
        val isFlag = BooleanArray(k) { '.' in allNames[keptIdx[it]] }

        val rows = ArrayList<DoubleArray>()
        var rejected = 0
        var step = 0          // 0＝還沒決定（看到第一列資料才估）
        var inWindow = 0
        var acc: DoubleArray? = null
        val tmp = DoubleArray(k)
        while (true) {
            val line = source.readLine() ?: break
            if (line.isBlank()) continue
            if (step == 0) {
                val est = ((sizeBytes - headBytes).coerceAtLeast(0) / (line.length + 1).coerceAtLeast(1)).toInt()
                step = if (maxRows <= 0 || est <= maxRows) 1 else (est + maxRows - 1) / maxRows
            }
            // 一次掃過整行：數欄位，只把要留的欄轉成數字
            var field = 0; var start = 0; var ok = true
            val n = line.length
            var i = 0
            while (i <= n) {
                if (i == n || line[i] == ',') {
                    if (field < total) {
                        val o = out[field]
                        if (o >= 0) {
                            val v = line.substring(start, i).trim().toDoubleOrNull()
                            if (v == null || !v.isFinite()) ok = false else tmp[o] = v
                        }
                    }
                    field++; start = i + 1
                }
                i++
            }
            if (!ok || field != total) { rejected++; continue }
            val a = acc
            if (a == null) acc = tmp.copyOf()
            else for (c in 0 until k) if (isFlag[c] && tmp[c] > a[c]) a[c] = tmp[c]
            inWindow++
            if (inWindow >= step) { rows += acc!!; acc = null; inWindow = 0 }
        }
        acc?.let { rows += it }
        return Loga(header, keptIdx.mapNotNull { allIds.getOrNull(it) }, keptIdx.map { allGroups.getOrElse(it) { "" } }, keptIdx.map { allNames[it] }, rows, rejected)
    }
}
