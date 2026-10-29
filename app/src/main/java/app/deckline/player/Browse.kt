package app.deckline.player

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession

/**
 * The menu tree Android Auto shows, and the rules for turning a tapped item into a queue.
 *
 * Folder ids: "root", "playlists", "songs", "albums", "artists",
 *             "playlist:<id>", "album:<id>", "artist:<id>".
 * Song ids:   "<folder id>|<song id>", so we know which list a song was picked from.
 */
object Browse {
    const val ROOT = "root"
    const val PLAYLISTS = "playlists"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"

    fun trackIdOf(mediaId: String): Long? = mediaId.substringAfterLast('|').toLongOrNull()
    private fun folderOf(mediaId: String): String = mediaId.substringBeforeLast('|', "")

    fun root(): MediaItem = folder(ROOT, "Vinamp", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, playable = false)

    fun trackItem(t: Track, folder: String): MediaItem = MediaItem.Builder()
        .setMediaId("$folder|${t.id}")
        .setUri(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun folder(id: String, title: String, subtitle: String?, type: Int, playable: Boolean): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .build()
            )
            .build()

    private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"

    /** The songs inside a folder, in play order. */
    fun tracksOf(folderId: String): List<Track> = when {
        folderId == SONGS -> MusicLibrary.tracks
        folderId.startsWith("playlist:") ->
            Playlists.get(folderId.removePrefix("playlist:"))?.trackIds?.mapNotNull { MusicLibrary.get(it) } ?: emptyList()
        folderId.startsWith("album:") ->
            folderId.removePrefix("album:").toLongOrNull()?.let { MusicLibrary.albumTracks(it) } ?: emptyList()
        folderId.startsWith("artist:") ->
            folderId.removePrefix("artist:").toLongOrNull()?.let { MusicLibrary.artistTracks(it) } ?: emptyList()
        else -> emptyList()
    }

    fun children(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            folder(PLAYLISTS, "Playlists", null, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, false),
            folder(SONGS, "Songs", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, false),
            folder(ALBUMS, "Albums", null, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, false),
            folder(ARTISTS, "Artists", null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, false)
        )
        PLAYLISTS -> Playlists.all().map {
            folder("playlist:${it.id}", it.name, songs(it.trackIds.size), MediaMetadata.MEDIA_TYPE_PLAYLIST, true)
        }
        ALBUMS -> MusicLibrary.albums().map { (id, list) ->
            folder("album:$id", list.first().album.ifBlank { "Unknown album" }, list.first().artist, MediaMetadata.MEDIA_TYPE_ALBUM, true)
        }
        ARTISTS -> MusicLibrary.artists().map { (id, list) ->
            folder("artist:$id", list.first().artist, songs(list.size), MediaMetadata.MEDIA_TYPE_ARTIST, true)
        }
        else -> tracksOf(parentId).map { trackItem(it, parentId) }
    }

    fun item(mediaId: String): MediaItem? {
        trackIdOf(mediaId)?.let { id ->
            if (mediaId.contains('|')) return MusicLibrary.get(id)?.let { trackItem(it, folderOf(mediaId)) }
        }
        if (mediaId == ROOT) return root()
        return children(ROOT).firstOrNull { it.mediaId == mediaId }
            ?: listOf(PLAYLISTS, ALBUMS, ARTISTS).asSequence()
                .flatMap { children(it).asSequence() }
                .firstOrNull { it.mediaId == mediaId }
    }

    /** Fills in the file address for songs coming from a remote (the phone screen or the car). */
    fun resolve(item: MediaItem): MediaItem {
        val id = trackIdOf(item.mediaId) ?: return item
        val t = MusicLibrary.get(id) ?: return item
        return trackItem(t, folderOf(item.mediaId).ifEmpty { SONGS })
    }

    /**
     * When the car asks to play one song (or a whole folder, or a voice search),
     * turn it into the full list it came from, starting at the right song.
     */
    fun expand(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        if (items.size == 1) {
            val one = items[0]
            val query = one.requestMetadata.searchQuery
            if (one.mediaId.isEmpty() && query != null) {
                val found = MusicLibrary.search(query).ifEmpty { MusicLibrary.tracks }
                return MediaSession.MediaItemsWithStartPosition(found.map { trackItem(it, SONGS) }, 0, 0)
            }
            val folder = folderOf(one.mediaId)
            val id = trackIdOf(one.mediaId)
            if (folder.isNotEmpty() && id != null) {
                val list = tracksOf(folder)
                val index = list.indexOfFirst { it.id == id }
                if (index >= 0) {
                    return MediaSession.MediaItemsWithStartPosition(list.map { trackItem(it, folder) }, index, startPositionMs)
                }
            }
            if (!one.mediaId.contains('|')) {
                val list = tracksOf(one.mediaId)
                if (list.isNotEmpty()) {
                    return MediaSession.MediaItemsWithStartPosition(list.map { trackItem(it, one.mediaId) }, 0, 0)
                }
            }
        }
        return MediaSession.MediaItemsWithStartPosition(items.map { resolve(it) }, maxOf(startIndex, 0), startPositionMs)
    }
}
