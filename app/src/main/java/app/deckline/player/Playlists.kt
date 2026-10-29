package app.deckline.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class Playlist(val id: String, val name: String, val trackIds: List<Long>)

/** User playlists, saved in the app's private storage. Shared by the phone screen and Android Auto. */
object Playlists {
    private var file: File? = null
    private val items = mutableListOf<Playlist>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "playlists.json")
        file = f
        try {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ids = o.getJSONArray("tracks")
                    items += Playlist(
                        o.getString("id"),
                        o.getString("name"),
                        List(ids.length()) { ids.getLong(it) }
                    )
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    @Synchronized fun all(): List<Playlist> = items.toList()
    @Synchronized fun get(id: String): Playlist? = items.firstOrNull { it.id == id }

    fun create(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString().take(8), name, emptyList())
        synchronized(this) { items += p; save() }
        notifyChanged()
        return p
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun delete(id: String) {
        synchronized(this) { items.removeAll { it.id == id }; save() }
        notifyChanged()
    }

    /** Adds songs that aren't already in the playlist. Returns how many were added. */
    fun add(id: String, trackIds: List<Long>): Int {
        var added = 0
        update(id) { p ->
            val fresh = trackIds.distinct().filter { it !in p.trackIds }
            added = fresh.size
            p.copy(trackIds = p.trackIds + fresh)
        }
        return added
    }

    fun removeTrack(id: String, trackId: Long) = update(id) { p -> p.copy(trackIds = p.trackIds - trackId) }

    /** Moves a song up (delta = -1) or down (delta = 1). */
    fun moveTrack(id: String, trackId: Long, delta: Int) = update(id) { p ->
        val list = p.trackIds.toMutableList()
        val from = list.indexOf(trackId)
        val to = from + delta
        if (from < 0 || to < 0 || to >= list.size) p
        else { list.removeAt(from); list.add(to, trackId); p.copy(trackIds = list) }
    }

    private fun update(id: String, change: (Playlist) -> Playlist) {
        synchronized(this) {
            val i = items.indexOfFirst { it.id == id }
            if (i < 0) return
            items[i] = change(items[i])
            save()
        }
        notifyChanged()
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (p in items) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("tracks", JSONArray().apply { p.trackIds.forEach { put(it) } })
            })
        }
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        } catch (_: Throwable) {
        }
    }

    private fun notifyChanged() = listeners.forEach { it() }
}
