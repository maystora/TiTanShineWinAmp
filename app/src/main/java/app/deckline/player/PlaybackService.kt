package app.deckline.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Runs the player in the background (screen off, lock screen, headphones) and
 * offers the music library to Android Auto and other media browsers.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val bg = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val main = Handler(Looper.getMainLooper())

    private val playlistsChanged: () -> Unit = {
        main.post { session?.notifyChildrenChanged(Browse.PLAYLISTS, Playlists.all().size, null) }
    }

    override fun onCreate() {
        super.onCreate()
        Playlists.init(this)

        val player = ExoPlayer.Builder(this, TappedRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .build()

        AudioFx.attach(this, player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioFx.attach(this@PlaybackService, audioSessionId)
            }
            // Some phones drop sound effects when a new song starts, so re-apply them.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) AudioFx.reapply()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = AudioFx.reapply()
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
        Playlists.addListener(playlistsChanged)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        Playlists.removeListener(playlistsChanged)
        session?.run {
            player.release()
            release()
        }
        session = null
        AudioFx.release()
        bg.shutdown()
        super.onDestroy()
    }

    private fun <T> slice(list: List<T>, page: Int, pageSize: Int): List<T> {
        val from = page.toLong() * pageSize.toLong()
        if (from >= list.size) return emptyList()
        val to = minOf(list.size.toLong(), from + pageSize.toLong())
        return list.subList(from.toInt(), to.toInt())
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        private val ctx: Context get() = this@PlaybackService

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(Browse.root(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = bg.submit(Callable {
            MusicLibrary.load(ctx)
            val item = Browse.item(mediaId)
            if (item != null) LibraryResult.ofItem(item, null)
            else LibraryResult.ofError<MediaItem>(SessionError.ERROR_BAD_VALUE)
        })

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = bg.submit(Callable {
            MusicLibrary.load(ctx)
            LibraryResult.ofItemList(slice(Browse.children(parentId), page, pageSize), params)
        })

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = bg.submit(Callable {
            MusicLibrary.load(ctx)
            val count = MusicLibrary.search(query).size
            main.post { session.notifySearchResultChanged(browser, query, count, params) }
            LibraryResult.ofVoid(params)
        })

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = bg.submit(Callable {
            MusicLibrary.load(ctx)
            // Search results play the whole library from the chosen song, like on the phone.
            val items = MusicLibrary.search(query).map { Browse.trackItem(it, Browse.SONGS) }
            LibraryResult.ofItemList(slice(items, page, pageSize), params)
        })

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = bg.submit(Callable {
            MusicLibrary.load(ctx)
            mediaItems.map { Browse.resolve(it) }.toMutableList()
        })

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = bg.submit(Callable {
            MusicLibrary.load(ctx)
            Browse.expand(mediaItems, startIndex, startPositionMs)
        })
    }

    /** Adds a tap into the audio pipeline that feeds the visualizer (no microphone permission needed). */
    private class TappedRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink? =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(SpectrumTap)))
                .build()
    }
}
