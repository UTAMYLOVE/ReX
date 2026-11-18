package io.github.xeno_hf.rex

import rx.session.Presets

/**
 * 夜櫻（以 ReX 為基礎的監控 App）自己的固定規則。
 *
 * 夜櫻把很多 ReX 的選項拿掉了，改成固定值；以前在測試版改過的值可能還留在設定裡，
 * 所以更新後第一次啟動時調回來，只做一次。
 */
object Yozakura {
    const val TILES_KEY = "monFields"
    private const val MIGRATED_KEY = "yozakura.v1"

    fun migrate(kv: KeyValueStore) {
        if (kv.getBoolean(MIGRATED_KEY, false)) return
        // 監控四格改成三種風格共用：沿用目前風格那一組
        if (kv.getString(TILES_KEY, null) == null) {
            val style = kv.getString("monStyle", null) ?: "C"
            kv.getString("monFields.$style", null)?.let { kv.putString(TILES_KEY, it) }
        }
        kv.putString("preset", Presets.ALL_CHANNELS.id) // 監控、油圖監看、健檢都要用到全部通道
        kv.putInt("idleStopMin", 0)                     // 不自動斷線
        kv.putBoolean("autoRecord", true)               // 引擎發動就自動錄
        kv.putBoolean("autoConnect", true)              // 打開就連
        kv.putBoolean("runAfterClose", true)            // 把 App 滑掉照樣連線；要停按通知上的「結束」
        kv.putBoolean("locked", true)                   // 油圖只能在油圖視窗裡暫時解鎖切換
        kv.putString("bind", BindMode.AUTO.name)
        kv.putBoolean(MIGRATED_KEY, true)
    }

    /** 第二次調整：沒裝 AF2 的車，空燃比那格一直是「—」，換成別的。 */
    fun migrate2(kv: KeyValueStore) {
        if (kv.getBoolean("yozakura.v2", false)) return
        fun swap(key: String, with: String) {
            val v = kv.getString(key, null) ?: return
            val ids = v.split(',').toMutableList()
            val i = ids.indexOf("afr"); if (i < 0) return
            ids[i] = if (with in ids) "tps" else with
            kv.putString(key, ids.joinToString(","))
        }
        swap(TILES_KEY, "tps"); swap("ovRows", "iat")
        kv.putBoolean("yozakura.v2", true)
    }

    /** 第三次調整：原本的「夜櫻（暗）」現在是「紅白黑戰鬥」，舊設定先回到櫻色，要用戰鬥配色再自己選。 */
    fun migrate3(kv: KeyValueStore) {
        if (kv.getBoolean("yozakura.v3", false)) return
        if (kv.getString("theme", null) == "YOZAKURA") kv.putString("theme", "NIPPON")
        kv.putBoolean("yozakura.v3", true)
    }
}
