package com.termkey.ime

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Macro(val label: String, val text: String)

object MacroManager {

    private const val PREFS_KEY = "macros_json"

    private val DEFAULT_MACROS = listOf(
        Macro("sudo", "sudo "),
        Macro("grep -r", "grep -r "),
        Macro("| less", " | less"),
        Macro("| grep", " | grep "),
        Macro("2>&1", " 2>&1"),
        Macro("&&", " && "),
        Macro("||", " || "),
        Macro("chmod +x", "chmod +x "),
        Macro("ssh", "ssh -p 22 "),
        Macro("tar xzf", "tar -xzf "),
        Macro("find . -name", "find . -name \"\" 2>/dev/null"),
        Macro("\$()", "\$()"),
        Macro(">>", " >> "),
        Macro("ps aux", "ps aux | grep "),
        Macro("systemctl", "systemctl status "),
        Macro("journalctl", "journalctl -u  -f"),
        Macro("df -h", "df -h"),
        Macro("free -h", "free -h"),
        Macro("netstat", "netstat -tlnp"),
        Macro("curl -s", "curl -s "),
    )

    fun getMacros(context: Context): List<Macro> {
        val prefs = context.getSharedPreferences("termkey_macros", Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, null) ?: return DEFAULT_MACROS
        return try {
            val type = object : TypeToken<List<Macro>>() {}.type
            Gson().fromJson<List<Macro>>(json, type) ?: DEFAULT_MACROS
        } catch (e: Exception) {
            DEFAULT_MACROS
        }
    }

    fun saveMacros(context: Context, macros: List<Macro>) {
        val prefs = context.getSharedPreferences("termkey_macros", Context.MODE_PRIVATE)
        prefs.edit().putString(PREFS_KEY, Gson().toJson(macros)).apply()
    }

    fun resetToDefaults(context: Context) {
        val prefs = context.getSharedPreferences("termkey_macros", Context.MODE_PRIVATE)
        prefs.edit().remove(PREFS_KEY).apply()
    }
}
