package app.deckline.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.pow

class MainActivity : Activity() {

    // display and transport
    private lateinit var countText: TextView
    private lateinit var timeText: TextView
    private lateinit var titleText: TextView
    private lateinit var flagPlay: TextView
    private lateinit var flagShuf: TextView
    private lateinit var flagRep: TextView
    private lateinit var flagEq: TextView
    private lateinit var trackNo: TextView
    private lateinit var vizMode: TextView
    private lateinit var durText: TextView
    private lateinit var spectrum: SpectrumView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: ImageButton
    private lateinit var shuffleChip: TextView
    private lateinit var repeatChip: TextView
    private lateinit var volume: SeekBar

    // tabs
    private lateinit var tabs: List<TextView>
    private lateinit var panels: List<View>

    // library
    private lateinit var search: EditText
    private lateinit var emptyBox: View
    private lateinit var emptyText: TextView
    private lateinit var allowBtn: TextView
    private lateinit var list: ListView

    // playlists
    private lateinit var plActions: LinearLayout
    private lateinit var plTitle: TextView
    private lateinit var plSub: TextView
    private lateinit var plEmpty: TextView
    private lateinit var plList: ListView

    // equalizer
    private lateinit var eqContainer: LinearLayout

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var shown: List<Track> = emptyList()
    private var queueSig = ""
    private var openPlaylistId: String? = null
    private var playlistRows: List<Playlist> = emptyList()
    private var playlistTracks: List<Track> = emptyList()
    private val libAdapter = RowsAdapter()
    private val plAdapter = RowsAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var loading = false
    private var dragging = false
    private var showRemain = false
    private val prefs by lazy { getSharedPreferences("deckline", MODE_PRIVATE) }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val playlistsChanged: () -> Unit = { runOnUiThread { refreshPlaylists() } }

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Playlists.init(this)
        bindViews()
        wireControls()
        list.adapter = libAdapter
        plList.adapter = plAdapter
        showTab(prefs.getInt("tab", 0))
        refreshPlaylists()
        if (!MusicLibrary.hasPermission(this)) showNeedsPermission() // the library loads in onResume once allowed
    }

    override fun onStart() {
        super.onStart()
        Playlists.addListener(playlistsChanged)
        AudioFx.onChanged = { runOnUiThread { buildEqualizer(); refresh() } }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (t: Throwable) { null } ?: return@addListener
            controller = c
            c.addListener(playerListener)
            c.volume = volumeCurve(prefs.getInt("volume", 85))
            refresh()
            buildEqualizer()
        }, ContextCompat.getMainExecutor(this))
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // Re-scan every time the app comes back, so newly added music shows up.
        if (MusicLibrary.hasPermission(this)) loadLibrary()
    }

    override fun onStop() {
        Playlists.removeListener(playlistsChanged)
        AudioFx.onChanged = null
        handler.removeCallbacks(ticker)
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        spectrum.active = false
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---------------- views ----------------

    private fun bindViews() {
        countText = findViewById(R.id.countText)
        timeText = findViewById(R.id.timeText)
        titleText = findViewById(R.id.titleText)
        flagPlay = findViewById(R.id.flagPlay)
        flagShuf = findViewById(R.id.flagShuf)
        flagRep = findViewById(R.id.flagRep)
        flagEq = findViewById(R.id.flagEq)
        trackNo = findViewById(R.id.trackNo)
        vizMode = findViewById(R.id.vizMode)
        durText = findViewById(R.id.durText)
        spectrum = findViewById(R.id.spectrum)
        seek = findViewById(R.id.seek)
        playBtn = findViewById(R.id.playBtn)
        shuffleChip = findViewById(R.id.shuffleChip)
        repeatChip = findViewById(R.id.repeatChip)
        volume = findViewById(R.id.volume)
        tabs = listOf(findViewById(R.id.tabLib), findViewById(R.id.tabPl), findViewById(R.id.tabEq))
        panels = listOf(findViewById(R.id.libPanel), findViewById(R.id.plPanel), findViewById(R.id.eqPanel))
        search = findViewById(R.id.search)
        emptyBox = findViewById(R.id.emptyBox)
        emptyText = findViewById(R.id.emptyText)
        allowBtn = findViewById(R.id.allowBtn)
        list = findViewById(R.id.list)
        plActions = findViewById(R.id.plActions)
        plTitle = findViewById(R.id.plTitle)
        plSub = findViewById(R.id.plSub)
        plEmpty = findViewById(R.id.plEmpty)
        plList = findViewById(R.id.plList)
        eqContainer = findViewById(R.id.eqContainer)
        titleText.isSelected = true // lets the long title scroll
    }

    private fun wireControls() {
        playBtn.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.prevBtn).setOnClickListener {
            val c = controller ?: return@setOnClickListener
            if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
        }
        findViewById<View>(R.id.nextBtn).setOnClickListener { controller?.seekToNextMediaItem() }
        findViewById<View>(R.id.stopBtn).setOnClickListener {
            controller?.let { it.pause(); it.seekTo(0) }
        }
        timeText.setOnClickListener { showRemain = !showRemain; updateTime() }

        // Long-press the song title on the display to add the playing song to a playlist.
        titleText.setOnLongClickListener {
            val id = currentTrackId()
            if (id != null) addToPlaylistDialog(listOf(id))
            id != null
        }

        shuffleChip.setOnClickListener {
            controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        repeatChip.setOnClickListener {
            val c = controller ?: return@setOnClickListener
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }

        volume.progress = prefs.getInt("volume", 85)
        volume.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                controller?.volume = volumeCurve(p)
                prefs.edit().putInt("volume", p).apply()
            }
        })

        seek.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                dragging = false
                val c = controller ?: return
                val d = c.duration
                if (d > 0) c.seekTo(sb.progress * d / 1000)
            }
        })

        spectrum.setModeQuietly(prefs.getInt("viz", 0))
        vizMode.text = if (spectrum.mode == 0) "Spectrum" else "Scope"
        spectrum.onModeChange = { m ->
            vizMode.text = if (m == 0) "Spectrum" else "Scope"
            prefs.edit().putInt("viz", m).apply()
        }

        tabs.forEachIndexed { i, t -> t.setOnClickListener { showTab(i) } }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        // Tapping a song (even a search result) plays the whole library, starting at that song.
        list.setOnItemClickListener { _, _, position, _ ->
            val t = shown.getOrNull(position) ?: return@setOnItemClickListener
            val all = MusicLibrary.tracks
            playTracks(all, all.indexOfFirst { it.id == t.id }.coerceAtLeast(0), Browse.SONGS)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let { addToPlaylistDialog(listOf(it.id)) }
            true
        }

        plList.setOnItemClickListener { _, _, position, _ ->
            val open = openPlaylistId
            if (open == null) {
                playlistRows.getOrNull(position)?.let { openPlaylistId = it.id; refreshPlaylists() }
            } else {
                playTracks(playlistTracks, position, "playlist:$open")
            }
        }
        plList.setOnItemLongClickListener { _, _, position, _ ->
            val open = openPlaylistId
            if (open == null) playlistRows.getOrNull(position)?.let { playlistOptions(it) }
            else playlistTracks.getOrNull(position)?.let { songInPlaylistOptions(open, it) }
            true
        }

        allowBtn.setOnClickListener { askPermission() }
    }

    private fun showTab(index: Int) {
        tabs.forEachIndexed { i, t -> t.isSelected = i == index }
        panels.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        prefs.edit().putInt("tab", index).apply()
    }

    // ---------------- playback ----------------

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount == 0 -> if (MusicLibrary.tracks.isNotEmpty()) playTracks(MusicLibrary.tracks, 0, Browse.SONGS)
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
        }
    }

    /** Plays [tracks] as the queue, starting at [index]. [folder] records where the list came from. */
    private fun playTracks(tracks: List<Track>, index: Int, folder: String) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val sig = folder + ":" + tracks.joinToString(",") { it.id.toString() }
        if (sig == queueSig && c.mediaItemCount == tracks.size) {
            c.seekTo(index, 0)
        } else {
            c.setMediaItems(tracks.map { Browse.trackItem(it, folder) }, index, 0)
            queueSig = sig
        }
        c.prepare()
        c.play()
    }

    private fun currentTrackId(): Long? = controller?.currentMediaItem?.mediaId?.let { Browse.trackIdOf(it) }

    private fun refresh() {
        val c = controller
        val playing = c?.isPlaying == true
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playBtn.contentDescription = if (playing) "Pause" else "Play"
        spectrum.active = playing

        val item = c?.currentMediaItem
        if (c != null && item != null) {
            val md = item.mediaMetadata
            val artist = md.artist?.toString().orEmpty()
            val title = md.title?.toString().orEmpty()
            titleText.text = if (artist.isNotEmpty()) "$artist  —  $title" else title
            trackNo.text = String.format(Locale.US, "%02d/%02d", c.currentMediaItemIndex + 1, c.mediaItemCount)
        } else {
            titleText.text = if (MusicLibrary.tracks.isEmpty()) "Pick a song to begin" else "Tap a song to play"
            trackNo.text = "--/--"
        }

        val pos = c?.currentPosition ?: 0L
        setFlag(flagPlay, playing)
        flagPlay.text = when {
            playing -> "PLAY"
            pos > 0 -> "PAUSE"
            else -> "STOP"
        }
        val shuffle = c?.shuffleModeEnabled == true
        val repeat = c?.repeatMode ?: Player.REPEAT_MODE_OFF
        setFlag(flagShuf, shuffle)
        setFlag(flagRep, repeat != Player.REPEAT_MODE_OFF)
        flagRep.text = if (repeat == Player.REPEAT_MODE_ONE) "REP1" else "REP"
        setFlag(flagEq, AudioFx.enabled && AudioFx.available)
        shuffleChip.isSelected = shuffle
        repeatChip.isSelected = repeat != Player.REPEAT_MODE_OFF
        repeatChip.text = when (repeat) {
            Player.REPEAT_MODE_ALL -> "Repeat: all"
            Player.REPEAT_MODE_ONE -> "Repeat: one"
            else -> "Repeat: off"
        }
        libAdapter.notifyDataSetChanged()
        plAdapter.notifyDataSetChanged()
        updateTime()
    }

    private fun setFlag(v: TextView, on: Boolean) {
        v.setTextColor(getColor(if (on) R.color.vfd else R.color.vfd_dim))
        if (on) v.setShadowLayer(8f, 0f, 0f, getColor(R.color.vfd_glow)) else v.setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun updateTime() {
        val c = controller
        val pos = c?.currentPosition ?: 0L
        val dur = c?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        timeText.text = if (showRemain && dur > 0) "-" + fmt(dur - pos) else fmt(pos)
        durText.text = if (dur > 0) fmt(dur) else "-:--"
        if (!dragging) seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
    }

    private fun volumeCurve(p: Int): Float = (p / 100f).pow(1.6f)

    // ---------------- library ----------------

    private fun askPermission() {
        val perm = MusicLibrary.permission
        val askedBefore = prefs.getBoolean("asked_perm", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(perm)) {
            // Android won't show the prompt again; send the person to the app's settings page.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            prefs.edit().putBoolean("asked_perm", true).apply()
            requestPermissions(arrayOf(perm), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (MusicLibrary.hasPermission(this)) loadLibrary() else showNeedsPermission()
    }

    private fun showNeedsPermission() {
        emptyBox.visibility = View.VISIBLE
        allowBtn.visibility = View.VISIBLE
        emptyText.text = "Vinamp plays the music stored on your phone.\nAllow access so it can find your songs."
        list.visibility = View.GONE
        countText.text = ""
        if (!prefs.getBoolean("asked_perm", false)) askPermission()
    }

    private fun loadLibrary() {
        if (loading) return
        loading = true
        if (MusicLibrary.tracks.isEmpty()) countText.text = "Scanning…"
        io.execute {
            MusicLibrary.load(this, force = true)
            runOnUiThread {
                loading = false
                if (isFinishing) return@runOnUiThread
                applyFilter()
                refreshPlaylists()
                refresh()
            }
        }
    }

    private fun applyFilter() {
        val all = MusicLibrary.tracks
        val q = search.text.toString().trim()
        shown = MusicLibrary.search(q)
        countText.text = when (all.size) {
            0 -> ""
            1 -> "1 song"
            else -> "${all.size} songs"
        }
        val empty = shown.isEmpty()
        emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        allowBtn.visibility = if (MusicLibrary.hasPermission(this)) View.GONE else View.VISIBLE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.text = when {
            !MusicLibrary.hasPermission(this) -> "Vinamp plays the music stored on your phone.\nAllow access so it can find your songs."
            all.isEmpty() -> "No music found on this phone.\nCopy MP3, M4A, FLAC or OGG files into your Music folder, then reopen Vinamp."
            else -> "No songs match “$q”."
        }
        libAdapter.rows = shown.map { trackRow(it) }
    }

    private fun trackRow(t: Track) = Row(
        title = t.title,
        subtitle = if (t.album.isNotBlank()) "${t.artist}  ·  ${t.album}" else t.artist,
        right = if (t.durationMs > 0) fmt(t.durationMs) else "",
        trackId = t.id
    )

    // ---------------- playlists ----------------

    private fun refreshPlaylists() {
        plActions.removeAllViews()
        val open = openPlaylistId?.let { Playlists.get(it) }
        if (open == null) {
            openPlaylistId = null
            playlistRows = Playlists.all()
            plTitle.visibility = View.GONE
            plSub.visibility = View.GONE
            plActions.addView(chip("New playlist") {
                promptName("New playlist", "Playlist ${playlistRows.size + 1}") { name ->
                    openPlaylistId = Playlists.create(name).id
                    refreshPlaylists()
                }
            })
            plAdapter.rows = playlistRows.map { Row(it.name, songsLabel(it.trackIds.size), "›", -1) }
            plEmpty.text = "No playlists yet.\nTap New playlist, or long-press any song in your library to add it to one."
            showListOrEmpty(playlistRows.isEmpty())
            return
        }

        playlistTracks = open.trackIds.mapNotNull { MusicLibrary.get(it) }
        plTitle.visibility = View.VISIBLE
        plSub.visibility = View.VISIBLE
        plTitle.text = open.name
        val total = playlistTracks.sumOf { it.durationMs.coerceAtLeast(0) }
        plSub.text = songsLabel(playlistTracks.size) + if (total > 0) "  ·  " + longDuration(total) else ""

        plActions.addView(chip("‹ All playlists") { openPlaylistId = null; refreshPlaylists() })
        plActions.addView(chip("Play") {
            controller?.shuffleModeEnabled = false
            playTracks(playlistTracks, 0, "playlist:${open.id}")
        })
        plActions.addView(chip("Shuffle") {
            if (playlistTracks.isNotEmpty()) {
                controller?.shuffleModeEnabled = true
                playTracks(playlistTracks, playlistTracks.indices.random(), "playlist:${open.id}")
            }
        })
        plActions.addView(chip("Add songs") { pickSongsDialog(open.id) })
        plActions.addView(chip("Rename") { renamePlaylist(open) })
        plActions.addView(chip("Delete") { deletePlaylist(open) })

        plAdapter.rows = playlistTracks.map { trackRow(it) }
        plEmpty.text = "This playlist is empty.\nTap Add songs, or long-press songs in your library."
        showListOrEmpty(playlistTracks.isEmpty())
    }

    private fun showListOrEmpty(empty: Boolean) {
        plEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        plList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun promptName(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            hint = "Playlist name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        dialog().setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onSave(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addToPlaylistDialog(trackIds: List<Long>) {
        val lists = Playlists.all()
        val names = arrayOf("New playlist…") + lists.map { it.name }
        dialog().setTitle("Add to playlist")
            .setItems(names) { _, which ->
                if (which == 0) {
                    promptName("New playlist", "Playlist ${lists.size + 1}") { name ->
                        addTo(Playlists.create(name), trackIds)
                    }
                } else {
                    addTo(lists[which - 1], trackIds)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTo(p: Playlist, trackIds: List<Long>) {
        val added = Playlists.add(p.id, trackIds)
        toast(
            when {
                added == 0 && trackIds.size == 1 -> "Already in ${p.name}"
                added == 0 -> "Those songs are already in ${p.name}"
                added == 1 -> "Added to ${p.name}"
                else -> "Added $added songs to ${p.name}"
            }
        )
    }

    /** A searchable, tick-box list of the whole library. */
    private fun pickSongsDialog(playlistId: String) {
        val p = Playlists.get(playlistId) ?: return
        val selected = linkedSetOf<Long>()
        var filtered = MusicLibrary.tracks
        val listView = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_MULTIPLE }
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        listView.adapter = adapter

        fun reload() {
            adapter.clear()
            adapter.addAll(filtered.map { "${it.title} — ${it.artist}" })
            listView.clearChoices()
            filtered.forEachIndexed { i, t -> listView.setItemChecked(i, t.id in selected) }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val id = filtered[position].id
            if (listView.isItemChecked(position)) selected += id else selected -= id
        }
        val searchBox = EditText(this).apply {
            hint = "Search"
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filtered = MusicLibrary.search(s?.toString().orEmpty())
                    reload()
                }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(searchBox)
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        }
        reload()
        dialog().setTitle("Add songs to ${p.name}")
            .setView(box)
            .setPositiveButton("Add") { _, _ -> if (selected.isNotEmpty()) addTo(p, selected.toList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playlistOptions(p: Playlist) {
        dialog().setTitle(p.name)
            .setItems(arrayOf("Open", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> { openPlaylistId = p.id; refreshPlaylists() }
                    1 -> renamePlaylist(p)
                    2 -> deletePlaylist(p)
                }
            }
            .show()
    }

    private fun renamePlaylist(p: Playlist) =
        promptName("Rename playlist", p.name) { name -> Playlists.rename(p.id, name) }

    private fun deletePlaylist(p: Playlist) {
        dialog().setTitle("Delete “${p.name}”?")
            .setMessage("The playlist will be removed. Your songs stay on your phone.")
            .setPositiveButton("Delete") { _, _ ->
                if (openPlaylistId == p.id) openPlaylistId = null
                Playlists.delete(p.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun songInPlaylistOptions(playlistId: String, t: Track) {
        dialog().setTitle(t.title)
            .setItems(arrayOf("Move up", "Move down", "Remove from playlist")) { _, which ->
                when (which) {
                    0 -> Playlists.moveTrack(playlistId, t.id, -1)
                    1 -> Playlists.moveTrack(playlistId, t.id, 1)
                    2 -> Playlists.removeTrack(playlistId, t.id)
                }
            }
            .show()
    }

    private fun songsLabel(n: Int) = if (n == 1) "1 song" else "$n songs"

    private fun longDuration(ms: Long): String {
        val min = ms / 60000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    // ---------------- equalizer ----------------

    private fun buildEqualizer() {
        eqContainer.removeAllViews()
        val eq = AudioFx.eq
        if (eq == null) {
            eqContainer.addView(label(
                if (controller == null) "Starting the player…" else "The equalizer isn’t available on this phone.", 15f
            ))
            return
        }

        val sw = Switch(this).apply {
            text = "Equalizer on"
            textSize = 16f
            setTextColor(getColor(R.color.ink))
            isChecked = AudioFx.enabled
            setPadding(0, 0, 0, dp(10))
            setOnCheckedChangeListener { _, on -> AudioFx.setEnabled(on); refresh() }
        }
        eqContainer.addView(sw)
        // Moving any control switches the equalizer on, so changes are always heard.
        fun autoEnable() { if (!sw.isChecked) sw.isChecked = true }

        if (!AudioFx.hasControl) {
            eqContainer.addView(label(
                "Another app is controlling sound effects, so your changes may not be heard. " +
                    "Turn off other equalizer apps or your phone’s own sound effects (such as Dolby Atmos), then come back.",
                14f
            ).apply { setTextColor(getColor(R.color.vfd_warn)); setPadding(0, 0, 0, dp(10)) })
        }

        val bandCount = AudioFx.bandCount
        val range = eq.bandLevelRange // millibels, e.g. [-1500, 1500]
        val min = range[0].toInt()
        val max = range[1].toInt()
        val bars = mutableListOf<SeekBar>()
        val values = mutableListOf<TextView>()
        val presetChips = mutableListOf<Pair<Int, TextView>>()

        fun db(level: Int) = String.format(Locale.US, "%+.1f dB", level / 100f)
        fun markPresets() = presetChips.forEach { (id, chip) -> chip.isSelected = id == AudioFx.preset }
        fun syncBars() {
            for (b in 0 until bandCount) {
                val lvl = AudioFx.bandLevel(b)
                bars[b].progress = lvl - min
                values[b].text = db(lvl)
            }
            markPresets()
        }

        // presets
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val flatChip = chip("Flat") { autoEnable(); AudioFx.flat(); syncBars() }
        presetChips += AudioFx.PRESET_FLAT to flatChip
        row.addView(flatChip)
        val presetCount = try { eq.numberOfPresets.toInt() } catch (_: Throwable) { 0 }
        for (i in 0 until presetCount) {
            val name = try { eq.getPresetName(i.toShort()) } catch (_: Throwable) { continue }
            if (name.equals("Normal", true) || name.equals("Flat", true)) continue
            val c = chip(name) { autoEnable(); AudioFx.usePreset(i); syncBars() }
            presetChips += i to c
            row.addView(c)
        }
        scroller.addView(row)
        eqContainer.addView(scroller)

        // bands
        for (b in 0 until bandCount) {
            val hz = try { eq.getCenterFreq(b.toShort()) / 1000 } catch (_: Throwable) { 0 }
            val name = when {
                hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
                hz >= 1000 -> String.format(Locale.US, "%.1f kHz", hz / 1000f)
                else -> "$hz Hz"
            }
            val value = label("", 14f).apply { typeface = Typeface.MONOSPACE }
            values += value
            eqContainer.addView(header(name, value))

            val bar = slider(max - min) { p ->
                autoEnable()
                val lvl = p + min
                AudioFx.setBand(b, lvl)
                value.text = db(lvl)
                markPresets()
            }
            bars += bar
            eqContainer.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }

        // bass boost
        if (AudioFx.bassSupported) {
            val value = label("", 14f).apply { typeface = Typeface.MONOSPACE }
            eqContainer.addView(header("Bass boost", value))
            value.text = "${AudioFx.bassStrength / 10}%"
            val bar = slider(1000) { p ->
                autoEnable()
                AudioFx.setBass(p)
                value.text = "${p / 10}%"
            }
            bar.progress = AudioFx.bassStrength
            eqContainer.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }

        syncBars()

        val reset = chip("Reset everything") {
            AudioFx.flat()
            AudioFx.setBass(0)
            buildEqualizer()
        }
        eqContainer.addView(reset, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })
        eqContainer.addView(label(
            "Pick a preset or drag the sliders. Your settings are remembered and also apply in Android Auto.", 13f
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun header(name: String, value: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(14), 0, 0)
        addView(label(name, 15f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(getColor(R.color.ink))
        })
        addView(value)
    }

    private fun slider(maxValue: Int, onUserChange: (Int) -> Unit): SeekBar = SeekBar(this).apply {
        max = maxValue
        progressTintList = ColorStateList.valueOf(getColor(R.color.play))
        thumbTintList = ColorStateList.valueOf(getColor(R.color.play))
        setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onUserChange(p)
            }
        })
    }

    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColorStateList(R.color.chip_text))
            setBackgroundResource(R.drawable.chip_bg)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(R.color.ink_soft))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- lists ----------------

    data class Row(val title: String, val subtitle: String, val right: String, val trackId: Long)

    inner class RowsAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_track, parent, false)
            val r = rows[position]
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.artist).text = r.subtitle
            v.findViewById<TextView>(R.id.duration).text = r.right
            val current = r.trackId >= 0 && r.trackId == currentTrackId()
            v.setBackgroundColor(if (current) getColor(R.color.row_active) else Color.TRANSPARENT)
            return v
        }
    }

    private open class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    companion object {
        fun fmt(ms: Long): String {
            val s = (ms.coerceAtLeast(0) / 1000).toInt()
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
    }
}
