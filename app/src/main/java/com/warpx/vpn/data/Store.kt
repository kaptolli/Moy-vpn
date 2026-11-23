package com.warpx.vpn.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {

    data class WarpProfile(val name: String, val conf: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("warpx", Context.MODE_PRIVATE)

    fun listProfiles(ctx: Context): List<WarpProfile> {
        val json = prefs(ctx).getString("warp_profiles", "[]") ?: "[]"
        val arr = JSONArray(json)
        val out = mutableListOf<WarpProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(WarpProfile(o.getString("name"), o.getString("conf")))
        }
        return out
    }

    fun addProfile(ctx: Context, name: String, conf: String) {
        val list = listProfiles(ctx).toMutableList()
        list.removeAll { it.name == name }
        list.add(WarpProfile(name, conf))
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("name", p.name).put("conf", p.conf))
        }
        prefs(ctx).edit().putString("warp_profiles", arr.toString()).apply()
    }
}
