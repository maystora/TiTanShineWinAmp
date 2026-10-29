package app.deckline.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val trackNo: Int,
    val durationMs: Long
)

/** The songs on the phone. Shared by the phone screen and Android Auto. */
object MusicLibrary {
    @Volatile var tracks: List<Track> = emptyList()
        private set
    @Volatile private var byId: Map<Long, Track> = emptyMap()
    @Volatile var loaded = false
        private set

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Scans the phone's music. Safe to call from any background thread. */
    @Synchronized
    fun load(context: Context, force: Boolean = false): List<Track> {
        if (loaded && !force) return tracks
        if (!hasPermission(context)) return tracks
        val found = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cur ->
                val iId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iArtistId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
                val iAlbum = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iTrack = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iDur = cur.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cur.moveToNext()) {
                    val artist = cur.getString(iArtist)
                    found += Track(
                        id = cur.getLong(iId),
                        title = cur.getString(iTitle) ?: "Untitled",
                        artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Unknown artist" else artist,
                        artistId = cur.getLong(iArtistId),
                        album = cur.getString(iAlbum) ?: "",
                        albumId = cur.getLong(iAlbumId),
                        trackNo = cur.getInt(iTrack),
                        durationMs = cur.getLong(iDur)
                    )
                }
            }
        } catch (_: Throwable) {
            return tracks
        }
        tracks = found
        byId = found.associateBy { it.id }
        loaded = true
        return found
    }

    fun get(id: Long): Track? = byId[id]

    fun search(query: String): List<Track> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return tracks
        return tracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
    }

    /** Albums as (albumId, songs in track order), sorted by album name. */
    fun albums(): List<Pair<Long, List<Track>>> =
        tracks.groupBy { it.albumId }
            .map { (id, list) -> id to list.sortedWith(compareBy<Track>({ it.trackNo }, { it.title.lowercase() })) }
            .sortedBy { it.second.first().album.lowercase() }

    fun albumTracks(albumId: Long): List<Track> =
        tracks.filter { it.albumId == albumId }
            .sortedWith(compareBy<Track>({ it.trackNo }, { it.title.lowercase() }))

    /** Artists as (artistId, songs), sorted by artist name. */
    fun artists(): List<Pair<Long, List<Track>>> =
        tracks.groupBy { it.artistId }
            .map { (id, list) -> id to list }
            .sortedBy { it.second.first().artist.lowercase() }

    fun artistTracks(artistId: Long): List<Track> =
        tracks.filter { it.artistId == artistId }
            .sortedWith(compareBy<Track>({ it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
}
