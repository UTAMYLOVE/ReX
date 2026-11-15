package rx.session

/**
 * 嚴格解析「一行一個 JSON 物件」（events.jsonl）。整行必須剛好是一個合法的物件，
 * 否則回 null（寫到一半斷掉、語法壞掉的行都不算）。只回最外層的欄位：
 * 字串→String、數字→[Num]（原文，呼叫端自己轉）、true/false→Boolean、null→null、巢狀物件／陣列→Unit。
 */
internal object JsonLine {
    data class Num(val text: String)

    fun parseObject(line: String): Map<String, Any?>? {
        val p = P(line)
        return try {
            p.ws()
            val m = p.obj(top = true)
            p.ws()
            if (p.i != line.length) null else m
        } catch (_: Bad) { null }
    }

    private class Bad : Exception()

    private class P(val s: String) {
        var i = 0
        fun bad(): Nothing = throw Bad()
        fun peek(): Char = if (i < s.length) s[i] else bad()
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r' || s[i] == '\n')) i++ }
        fun eat(c: Char) { if (peek() != c) bad(); i++ }

        fun obj(top: Boolean): Map<String, Any?> {
            eat('{'); ws()
            val m = LinkedHashMap<String, Any?>()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); eat(':'); ws()
                val v = value()
                if (top) m[k] = v
                ws()
                when (peek()) { ',' -> i++; '}' -> { i++; return m }; else -> bad() }
            }
        }

        fun arr() {
            eat('['); ws()
            if (peek() == ']') { i++; return }
            while (true) {
                ws(); value(); ws()
                when (peek()) { ',' -> i++; ']' -> { i++; return }; else -> bad() }
            }
        }

        fun value(): Any? = when (peek()) {
            '{' -> { obj(top = false); Unit }
            '[' -> { arr(); Unit }
            '"' -> str()
            't' -> { lit("true"); true }
            'f' -> { lit("false"); false }
            'n' -> { lit("null"); null }
            else -> Num(num())
        }

        fun lit(w: String) { if (!s.startsWith(w, i)) bad(); i += w.length }

        fun num(): String {
            val st = i
            if (peek() == '-') i++
            if (peek() == '0') i++ else digits()
            if (i < s.length && s[i] == '.') { i++; digits() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (peek() == '+' || peek() == '-') i++
                digits()
            }
            return s.substring(st, i)
        }

        fun digits() { if (peek() !in '0'..'9') bad(); while (i < s.length && s[i] in '0'..'9') i++ }

        fun str(): String {
            eat('"')
            val b = StringBuilder()
            while (true) {
                val c = peek(); i++
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        when (peek().also { i++ }) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) bad()
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) bad()
                                val h = hex.toInt(16)
                                b.append(h.toChar()); i += 4
                            }
                            else -> bad()
                        }
                    }
                    c < ' ' -> bad()
                    else -> b.append(c)
                }
            }
        }
    }
}
