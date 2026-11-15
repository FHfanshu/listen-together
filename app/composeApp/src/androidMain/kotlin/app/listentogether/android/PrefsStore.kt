package app.listentogether.android

import android.content.Context
import app.listentogether.KeyValueStore

class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("listen_together", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}
