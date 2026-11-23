package com.muse.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 按视频地址保存用户选择的画面比例，再次播放该视频时自动恢复。
 * 只记录非默认（适应屏幕）的比例，恢复默认时删除记录。
 */
object AspectRepository {
    private const val PREFS = "muse_aspect"
    private const val KEY = "aspect_map"

    data class Aspect(val mode: Int, val width: Int, val height: Int)

    private lateinit var sp: SharedPreferences
    private var loaded = false
    private val map = HashMap<String, Aspect>()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(sp.getString(KEY, "{}") ?: "{}")
            json.keys().forEach { uri ->
                val obj = json.optJSONObject(uri) ?: return@forEach
                val mode = obj.optInt("mode", 0)
                if (mode > 0) {
                    map[uri] = Aspect(mode, obj.optInt("w", 0), obj.optInt("h", 0))
                }
            }
        }
    }

    fun get(uri: String): Aspect? = map[uri]

    fun put(uri: String, aspect: Aspect) {
        if (uri.isEmpty()) return
        if (aspect.mode <= 0) {
            if (map.remove(uri) != null) persist()
            return
        }
        if (map[uri] == aspect) return
        map[uri] = aspect
        persist()
    }

    private fun persist() {
        val json = JSONObject()
        map.forEach { (uri, aspect) ->
            json.put(
                uri,
                JSONObject()
                    .put("mode", aspect.mode)
                    .put("w", aspect.width)
                    .put("h", aspect.height)
            )
        }
        sp.edit().putString(KEY, json.toString()).apply()
    }
}
