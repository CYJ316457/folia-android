package top.izuna.foliamajor.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.izuna.foliamajor.BuildConfig
import top.izuna.foliamajor.local.EntityKind
import top.izuna.foliamajor.local.ImportSummary
import top.izuna.foliamajor.local.LibraryAssignment
import top.izuna.foliamajor.local.LibraryEntity
import top.izuna.foliamajor.local.LibraryIndex
import top.izuna.foliamajor.local.LocalCoverStore
import top.izuna.foliamajor.local.LocalLibraryImporter
import top.izuna.foliamajor.local.LocalLibraryScanner
import top.izuna.foliamajor.local.LocalLibraryStore
import top.izuna.foliamajor.local.LocalPlaylist
import top.izuna.foliamajor.local.LocalPlaylistOps
import top.izuna.foliamajor.local.mergeLibraryEntities
import top.izuna.foliamajor.local.toModel
import top.izuna.foliamajor.local.toRecord
import top.izuna.foliamajor.local.unmergeLibraryEntity
import top.izuna.foliamajor.lyrics.Line
import top.izuna.foliamajor.lyrics.LyricParseFormat
import top.izuna.foliamajor.lyrics.getLineRenderEndTime
import top.izuna.foliamajor.lyrics.parseLyricsByFormat
import top.izuna.foliamajor.online.DataUrl
import top.izuna.foliamajor.online.NeteaseClient
import top.izuna.foliamajor.online.NeteaseEndpointStore
import top.izuna.foliamajor.online.NeteaseSessionStore
import top.izuna.foliamajor.online.QrLoginProtocol
import top.izuna.foliamajor.online.QrLoginState
import top.izuna.foliamajor.playback.PlaybackController
import top.izuna.foliamajor.playback.ReplayGainInfo
import top.izuna.foliamajor.playback.ReplayGainMode
import top.izuna.foliamajor.playback.calculateReplayGain
import top.izuna.foliamajor.theme.AiThemeClient
import top.izuna.foliamajor.theme.AiThemeConfig
import top.izuna.foliamajor.theme.AiThemeProvider
import top.izuna.foliamajor.theme.AiThemeStore
import top.izuna.foliamajor.theme.DefaultTheme
import top.izuna.foliamajor.theme.DualTheme
import top.izuna.foliamajor.theme.Theme
import top.izuna.foliamajor.lyrics.PureMusicDetector

/**
 * Playback, search, lyric loading and the local library.
 *
 * The desktop app splits these across an Omni routing layer, a provider registry and four stores.
 * That structure exists to support multiple music providers and runtime-registered mod providers;
 * this slice has two sources (NetEase and local files), so the same behaviour lives in one place.
 */
class PlayerViewModel(
    private val context: Context,
    private val playback: PlaybackController,
) : ViewModel() {

    /** Where the currently displayed track came from. */
    enum class Source { ONLINE, LOCAL }

    /** A row in the UI's song list, flattened so online and local tracks render identically. */
    data class TrackRow(
        val key: String,
        val title: String,
        val artist: String,
        val subtitle: String? = null,
        val source: Source,
        val onlineSong: NeteaseClient.Song? = null,
        val localSong: LocalLibraryStore.SongRecord? = null,
    )

    data class State(
        val query: String = "",
        val results: List<TrackRow> = emptyList(),
        val isSearching: Boolean = false,
        val currentTrack: TrackRow? = null,
        val lyrics: List<Line> = emptyList(),
        val isLoadingLyrics: Boolean = false,
        val status: String? = null,

        // Local library
        val localSongs: List<LocalLibraryStore.SongRecord> = emptyList(),
        val localRoots: List<LocalLibraryStore.RootRecord> = emptyList(),
        val isImporting: Boolean = false,
        val importProgress: String? = null,
        val lastImport: ImportSummary? = null,

        // Entity browsing
        val artists: List<EntitySummary> = emptyList(),
        val albums: List<EntitySummary> = emptyList(),
        /** Entities the importer could not disambiguate; each needs a manual merge. */
        val needsReview: List<EntitySummary> = emptyList(),
        val localBrowse: LocalBrowse = LocalBrowse.SONGS,

        /** The entity long-pressed in the list, awaiting a merge target. */
        val pendingMerge: EntitySummary? = null,
        /** The entity whose songs the user tapped, shown as a detail list. */
        val openedEntity: EntitySummary? = null,
        val entitySongs: List<LocalLibraryStore.SongRecord> = emptyList(),
        val replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
        val playlists: List<LocalPlaylist> = emptyList(),
        /** The playlist whose songs are being shown, or null when browsing all songs. */
        val openedPlaylist: LocalPlaylist? = null,
        val playlistSongs: List<LocalLibraryStore.SongRecord> = emptyList(),
        val lyricMode: top.izuna.foliamajor.ui.lyrics.LyricMode =
            top.izuna.foliamajor.ui.lyrics.LyricMode.CLASSIC,

        /** Which theme the lyric field and chrome use. */
        val activeTheme: Theme = DefaultTheme,
        /** The AI theme generated or cached for the current song, if any. */
        val songTheme: DualTheme? = null,
        val isGeneratingTheme: Boolean = false,
        /** AI settings, edited in the settings sheet. */
        val aiConfig: AiThemeConfig = AiThemeConfig(),

        /** The signed-in NetEase account, or an empty session when signed out. */
        val neteaseSession: NeteaseSessionStore.Session = NeteaseSessionStore.Session(),
        /** Non-null while the QR login sheet shows a code. */
        val qrLogin: QrLoginUi? = null,
        /** True while the login sheet is visible, whether or not a code exists yet. */
        val loginVisible: Boolean = false,
        /** The NetEase API address in use; editable because every user hosts their own. */
        val neteaseApiBase: String = "",

        /** Which tab the song list shows. */
        val tab: Tab = Tab.LOCAL,
    ) {
        /** True when the current song's theme came from the AI generator rather than the default. */
        val usingAiTheme: Boolean get() = activeTheme !== DefaultTheme
        enum class Tab { LOCAL, ONLINE }

        /** Which view the local tab is showing. */
        enum class LocalBrowse { SONGS, ARTISTS, ALBUMS, PLAYLISTS }
    }

    /**
     * The QR login sheet's state.
     *
     * [imageBytes] holds the decoded PNG rather than the data URL: Coil 2 cannot load data URIs (that
     * arrived in Coil 3), so decoding has to happen before the image reaches the UI. A QR is a few
     * kilobytes, and the bytes are replaced wholesale on every refresh.
     */
    data class QrLoginUi(
        val imageBytes: ByteArray,
        val state: QrLoginState,
    ) {
        // Data-class equality over a ByteArray compares identity, which is exactly what is wanted
        // here: the image is only ever replaced as a whole, never mutated in place.
        override fun equals(other: Any?): Boolean =
            this === other || (other is QrLoginUi && imageBytes === other.imageBytes && state == other.state)

        override fun hashCode(): Int = 31 * imageBytes.hashCode() + state.hashCode()
    }

    /**
     * The ReplayGain tags for a row, or null.
     *
     * Only local tracks carry them: an online stream's loudness is whatever the provider serves and
     * the API does not report a gain.
     */
    private fun TrackRow.replayGainInfo(): ReplayGainInfo? {
        val song = localSong ?: return null
        if (song.replayGainTrackDb == null && song.replayGainTrackPeak == null &&
            song.replayGainAlbumDb == null && song.replayGainAlbumPeak == null
        ) {
            return null
        }
        return ReplayGainInfo(
            trackGain = song.replayGainTrackDb,
            trackPeak = song.replayGainTrackPeak,
            albumGain = song.replayGainAlbumDb,
            albumPeak = song.replayGainAlbumPeak,
        )
    }

    /** An artist or album as shown in the local tab, with the song count it owns. */    data class EntitySummary(
        val id: String,
        val name: String,
        val kind: EntityKind,
        val songCount: Int,
        val needsReview: Boolean,
        val aliases: List<String>,
        /** Non-null when this entity was merged into another one and can be split back apart. */
        val mergedInto: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The raw entity graph, kept apart from the derived summaries.
     *
     * `summarizeEntities` folds merges and drops entities with no songs, which is what the list
     * should show. Those same operations make it useless for answering "which songs does this entity
     * own" during a merge, so the graph is kept whole here.
     */
    private val localEntities = MutableStateFlow<List<LibraryEntity>>(emptyList())
    private val localAssignments = MutableStateFlow<List<LibraryAssignment>>(emptyList())

    private val neteaseSession = NeteaseSessionStore(context)
    private val neteaseEndpoint = NeteaseEndpointStore(context)
    private val client = NeteaseClient(
        apiBase = { neteaseEndpoint.load(BuildConfig.NETEASE_API_BASE) },
        session = { neteaseSession.load() },
        onAnonymousCookieRejected = { neteaseSession.clearAnonymousCookie() },
    )
    private val scanner = LocalLibraryScanner(context)
    private val importer = LocalLibraryImporter(context, scanner)
    private val store = LocalLibraryStore(context)
    private val covers = LocalCoverStore(context)
    private val themeStore = AiThemeStore(context)
    private val themeClient = AiThemeClient()

    private var localPlaybackJob: Job? = null
    private var themeJob: Job? = null

    /**
     * Which ReplayGain tag set to honour. Album mode falls back to track tags per song.
     *
     * Declared **before** the `init` block on purpose: Kotlin initialises properties in declaration
     * order, and the collector started in `init` reads this on its first emission. Declaring it
     * after `init` leaves it null at that moment and crashes the app on launch.
     */
    private var replayGainMode: ReplayGainMode = ReplayGainMode.TRACK

    init {
        // The library is loaded at construction rather than on first tab switch: the UI's initial
        // tab is LOCAL, and a loading flash on every launch is worse than a few milliseconds of
        // synchronous-feeling startup work on the IO dispatcher.
        refreshLocalLibrary()

        // AI settings and the last applied theme are read once, synchronously: SharedPreferences is
        // already an in-memory cache after the first read, and deferring it would flash the default
        // theme before the stored one appears.
        _state.update { it.copy(aiConfig = themeStore.loadConfig()) }

        // The NetEase session is restored here rather than on the first search: the search field is
        // visible at launch, and a search that quietly runs anonymously because the cookie had not
        // loaded yet would look like the login did not stick.
        restoreNeteaseSession()
        ensureAnonymousSession()
        _state.update {
            it.copy(neteaseApiBase = neteaseEndpoint.load(BuildConfig.NETEASE_API_BASE))
        }

        // The gain is applied when a track starts, from the values the importer read out of the
        // file's ID3 tag. Media3 exposes no ReplayGain field (its metadata callback fires with a
        // null `extras` for MP3), so the player cannot be asked for it at playback time.
        viewModelScope.launch {
            _state.collect { uiState ->
                playback.setReplayGain(
                    calculateReplayGain(uiState.currentTrack?.replayGainInfo(), replayGainMode)
                )
            }
        }
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        replayGainMode = mode
        playback.setReplayGain(
            calculateReplayGain(_state.value.currentTrack?.replayGainInfo(), mode)
        )
        _state.update { it.copy(replayGainMode = mode) }
    }

    // --- NetEase login ---------------------------------------------------------------------------

    /**
     * Restores the session at startup.
     *
     * The stored profile is shown immediately so the UI never renders "signed out" over a live
     * cookie; the server is then asked to confirm. A confirmation failure (offline, API down) leaves
     * the stored profile in place rather than signing the user out — the cookie is still valid, and
     * dropping it on a network error would force a re-scan for nothing.
     */
    fun restoreNeteaseSession() {
        val stored = neteaseSession.load()
        _state.update { it.copy(neteaseSession = stored) }
        if (!stored.isSignedIn) return

        viewModelScope.launch {
            val confirmed = client.getAccount(stored)
            if (confirmed != null) {
                neteaseSession.save(confirmed)
                _state.update { it.copy(neteaseSession = confirmed) }
            }
        }
    }

    /**
     * Fetches an anonymous cookie so search and playback work before signing in.
     *
     * Best-effort: instances without the endpoint still serve search, so a failure is silent.
     */
    fun ensureAnonymousSession() {
        viewModelScope.launch {
            val current = neteaseSession.load()
            val anonymous = client.ensureAnonymousCookie(current) ?: return@launch
            if (anonymous != current.anonymousCookie) {
                val updated = neteaseSession.setAnonymousCookie(anonymous)
                _state.update { it.copy(neteaseSession = updated) }
            }
        }
    }

    /**
     * Opens the account sheet.
     *
     * A code is only requested when there is nothing better to show: a signed-in user gets their
     * profile, and a code that is already on screen is left alone. Re-requesting on every open would
     * invalidate a code the user is in the middle of scanning, and doing it while signed in would
     * silently start a second login over the first.
     */
    fun openLogin() {
        val current = _state.value
        _state.update { it.copy(loginVisible = true) }

        if (current.neteaseSession.isSignedIn) return

        val live = current.qrLogin?.state
        if (live is QrLoginState.Waiting || live is QrLoginState.Scanned) return

        startQrLogin()
    }

    fun closeLogin() {
        qrPollJob?.cancel()
        qrPollJob = null
        _state.update { it.copy(loginVisible = false) }
    }

    /**
     * Requests a code and starts polling.
     *
     * Re-entrant on purpose: the "expired, get a new code" button calls it again, and the old poll
     * must be cancelled first or two loops race to apply stale results.
     */
    fun startQrLogin() {
        qrPollJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(qrLogin = null, status = "Requesting QR code…") }

            val key = client.getQrKey()
            if (key == null) {
                _state.update {
                    it.copy(status = "Could not reach the music API", loginVisible = true)
                }
                return@launch
            }

            val image = client.createQr(key)
            if (image == null) {
                _state.update {
                    it.copy(status = "The API did not return a QR image", loginVisible = true)
                }
                return@launch
            }

            val bytes = DataUrl.decodeBase64Image(image)
            if (bytes == null) {
                _state.update {
                    it.copy(status = "The API returned an unreadable QR image", loginVisible = true)
                }
                return@launch
            }

            _state.update {
                it.copy(
                    status = null,
                    qrLogin = QrLoginUi(imageBytes = bytes, state = QrLoginState.Waiting),
                )
            }
            pollQr(key)
        }
    }

    private var qrPollJob: Job? = null

    /**
     * Polls `/login/qr/check` until the code is confirmed, expires, or fails terminally.
     *
     * The interval is two seconds: NetEase's own web client polls at one second, but on a phone the
     * user has to pick up the device and open the app, so a slower poll saves requests without
     * making the confirmation feel late. Transient failures keep polling with a backoff so a flaky
     * connection does not end the login.
     */
    private fun pollQr(key: String) {
        qrPollJob = viewModelScope.launch {
            var transientFailures = 0

            while (true) {
                delay(if (transientFailures > 0) 4000L else 2000L)

                val state = QrLoginProtocol.fromCheckResponse(client.checkQr(key))
                updateQrState(state)

                when (state) {
                    is QrLoginState.Confirmed -> {
                        onQrConfirmed(state.cookie)
                        return@launch
                    }
                    is QrLoginState.Expired -> return@launch
                    is QrLoginState.Failed -> {
                        if (!state.transient) return@launch
                        transientFailures += 1
                        // Give up after ~30 seconds of unreachable API: the code expires in about
                        // two minutes anyway, and an endless silent retry reads as a hung screen.
                        if (transientFailures >= 8) {
                            updateQrState(
                                QrLoginState.Failed("Lost connection to the music API", null, false)
                            )
                            return@launch
                        }
                    }
                    else -> transientFailures = 0
                }
            }
        }
    }

    private fun updateQrState(state: QrLoginState) {
        _state.update { current ->
            val login = current.qrLogin ?: return@update current
            current.copy(qrLogin = login.copy(state = state))
        }
    }

    /**
     * Persists the confirmed cookie and loads the account.
     *
     * The cookie from a 803 is enough to act as the user, but the profile needs a second round trip.
     * It is stored with the cookie even when the profile fetch fails: the login itself succeeded, and
     * showing a blank name is better than discarding a valid session.
     */
    private suspend fun onQrConfirmed(cookie: String) {
        val withCookie = neteaseSession.load().copy(cookie = cookie)
        neteaseSession.save(withCookie)

        val account = client.getAccount(withCookie) ?: withCookie.copy(userId = 0L)
        neteaseSession.save(account)

        _state.update {
            it.copy(
                neteaseSession = account,
                loginVisible = false,
                qrLogin = null,
                status = if (account.userId > 0L) {
                    "Signed in as ${account.nickname}"
                } else {
                    "Signed in"
                },
            )
        }
    }

    fun signOutNetease() {
        viewModelScope.launch {
            val current = neteaseSession.load()
            runCatching { client.logout(current) }
            val cleared = neteaseSession.signOut()
            _state.update {
                it.copy(neteaseSession = cleared, loginVisible = false, qrLogin = null, status = "Signed out")
            }
            // The account's cookie is gone, so the anonymous one is the only way to keep browsing.
            ensureAnonymousSession()
        }
    }

    /**
     * Points the client at a different API instance and drops the session.
     *
     * The cookies belong to whichever server issued them, so keeping them across a switch would send
     * one instance's credentials to another. Signing out locally is the only safe behaviour.
     */
    fun setNeteaseApiBase(base: String) {
        val trimmed = base.trim()
        if (trimmed.isEmpty()) return

        neteaseEndpoint.save(trimmed)
        val cleared = neteaseSession.signOut()
        neteaseSession.clearAnonymousCookie()

        qrPollJob?.cancel()
        _state.update {
            it.copy(
                neteaseApiBase = trimmed,
                neteaseSession = cleared,
                qrLogin = null,
                status = "API address updated — sign in again",
            )
        }
        ensureAnonymousSession()
    }

    // --- Local library ---------------------------------------------------------------------------

    fun refreshLocalLibrary() {
        viewModelScope.launch {
            val snapshot = importer.load()
            val entities = snapshot.entities.map { it.toModel() }
            val assignments = snapshot.assignments.map { it.toModel() }
            val summary = summarizeEntities(entities, assignments)

            localEntities.value = entities
            localAssignments.value = assignments

            // Playlists are repaired against the live library on every load: a song can disappear
            // without the playlist being touched, and a dangling id would render as a blank row.
            val liveIds = snapshot.songs.map { it.id }.toSet()
            val repairedPlaylists = LocalPlaylistOps.ensureFavorites(
                snapshot.playlists.map { it.toModel() }
            ).map { LocalPlaylistOps.repair(it, liveIds) }

            _state.update {
                it.copy(
                    localSongs = snapshot.songs.sortedWith(
                        compareBy({ song -> song.artistNames.firstOrNull()?.lowercase() ?: "\uFFFF" },
                            { song -> song.albumName?.lowercase() ?: "" },
                            { song -> song.discNumber ?: 0 },
                            { song -> song.trackNumber ?: Int.MAX_VALUE },
                            { song -> song.title.lowercase() })
                    ),
                    localRoots = snapshot.roots,
                    playlists = repairedPlaylists,
                    artists = summary.artists,
                    albums = summary.albums,
                    needsReview = summary.needsReview,
                )
            }
        }
    }

    /**
     * Groups entities into browsable rows.
     *
     * Merged entities are folded into their survivor, so a user who merged "周杰倫" into "周杰伦"
     * sees one artist with both names as aliases. Entities with no songs are dropped: an entity only
     * exists because a song named it, and a stale one means the song was deleted.
     */
    private fun summarizeEntities(
        entities: List<LibraryEntity>,
        assignments: List<LibraryAssignment>,
    ): EntitySummaryBundle {
        val index = LibraryIndex(entities)
        val songIdByEntity = mutableMapOf<String, MutableSet<String>>()
        val aliasesByEntity = mutableMapOf<String, MutableList<String>>()

        fun attribute(entityId: String, songId: String) {
            val survivingId = index.followRedirect(entityId) ?: return
            songIdByEntity.getOrPut(survivingId) { mutableSetOf() }.add(songId)
            aliasesByEntity.getOrPut(survivingId) { mutableListOf() }
        }

        for (assignment in assignments) {
            for (artistId in assignment.artistEntityIds) attribute(artistId, assignment.songId)
            assignment.albumEntityId?.let { attribute(it, assignment.songId) }
        }

        // Aliases belonging to merged-away entities also resolve to the survivor.
        for (entity in entities) {
            val survivingId = index.followRedirect(entity.id) ?: continue
            if (survivingId == entity.id) continue
            val bucket = aliasesByEntity.getOrPut(survivingId) { mutableListOf() }
            bucket.add(entity.displayName)
        }

        /**
         * Summaries for one kind, **including** merged-away entities.
         *
         * A merged entity has no songs of its own left (the assignment's artist ids now resolve
         * through it to the survivor), so it is listed as a 0-song row that exists purely so the
         * user can find and split it. Filtering them out would make a merge irreversible through the
         * UI, even though the data model supports undoing it.
         */
        fun summaries(kind: EntityKind): List<EntitySummary> =
            index.entitiesById.values
                .filter { it.kind == kind }
                .sortedWith(compareBy({ it.mergedInto != null }, { it.displayName.lowercase() }))
                .map { entity ->
                    EntitySummary(
                        id = entity.id,
                        name = entity.displayName,
                        kind = entity.kind,
                        songCount = songIdByEntity[entity.id]?.size ?: 0,
                        needsReview = entity.needsReview,
                        aliases = (entity.aliases + aliasesByEntity[entity.id].orEmpty()).distinct(),
                        mergedInto = entity.mergedInto,
                    )
                }

        return EntitySummaryBundle(
            artists = summaries(EntityKind.ARTIST),
            albums = summaries(EntityKind.ALBUM),
            needsReview = summaries(EntityKind.ARTIST).filter { it.needsReview } +
                summaries(EntityKind.ALBUM).filter { it.needsReview },
        )
    }

    private data class EntitySummaryBundle(
        val artists: List<EntitySummary>,
        val albums: List<EntitySummary>,
        val needsReview: List<EntitySummary>,
    )

    /**
     * Imports a folder tree chosen through the SAF picker.
     *
     * The importer reads tags for every file, which takes real time on a large library, so progress
     * is surfaced as a count rather than left as an indeterminate spinner.
     */
    fun importFolder(treeUri: Uri) {
        if (_state.value.isImporting) return

        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, importProgress = "Scanning…", status = null) }

            val result = importer.importTree(treeUri) { progress ->
                _state.update {
                    it.copy(
                        importProgress = when (progress.phase) {
                            LocalLibraryImporter.Progress.Phase.SCANNING -> "Scanning…"
                            LocalLibraryImporter.Progress.Phase.DIFFING -> "Checking for changes…"
                            LocalLibraryImporter.Progress.Phase.READING_TAGS ->
                                "Reading tags ${progress.processed}/${progress.total}"
                            LocalLibraryImporter.Progress.Phase.SAVING -> "Saving…"
                            LocalLibraryImporter.Progress.Phase.DONE -> "Done"
                        }
                    )
                }
            }

            result.onSuccess { summary ->
                _state.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        lastImport = summary,
                        status = describeImport(summary),
                    )
                }
                refreshLocalLibrary()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        status = "Import failed: ${error.message}",
                    )
                }
            }
        }
    }

    /**
     * One line describing what an import did.
     *
     * The added/updated split matters on a rescan: "Imported 2000 songs" after changing one tag
     * reads like the whole library was rebuilt, which makes the incremental scan look broken.
     */
    private fun describeImport(summary: ImportSummary): String = buildString {
        if (summary.addedCount > 0) append("Added ${summary.addedCount}")
        if (summary.relinkedCount > 0) {
            if (isNotEmpty()) append(", ")
            append("updated ${summary.relinkedCount}")
        }
        if (summary.removedCount > 0) {
            if (isNotEmpty()) append(", ")
            append("removed ${summary.removedCount}")
        }
        if (isEmpty()) append("No changes")
        append(" · ${summary.importedCount} songs")
        if (summary.lyricCount > 0) append(", ${summary.lyricCount} with lyrics")
        if (summary.skippedCount > 0) append(", ${summary.skippedCount} unsupported")
    }

    fun removeRoot(treeUri: Uri) {
        viewModelScope.launch {
            importer.removeRoot(treeUri)
            refreshLocalLibrary()
        }
    }

    /**
     * Plays a local file.
     *
     * The lyric is read from the sidecar file recorded at import time, then parsed with the format
     * the importer detected. Reading it here rather than at import keeps the index small and means a
     * lyric edited on disk is picked up on the next play.
     */
    fun playLocal(song: LocalLibraryStore.SongRecord) {
        localPlaybackJob?.cancel()

        val row = TrackRow(
            key = "local:${song.id}",
            title = song.title,
            artist = song.artistNames.joinToString("/").ifEmpty { "Unknown Artist" },
            subtitle = song.albumName,
            source = Source.LOCAL,
            localSong = song,
        )

        _state.update {
            it.copy(
                currentTrack = row,
                lyrics = emptyList(),
                isLoadingLyrics = song.sidecarLyricPath != null,
                status = null,
                tab = State.Tab.LOCAL,
            )
        }
        applyThemeForTrack(row.key)

        localPlaybackJob = viewModelScope.launch {
            val rootUri = Uri.parse(song.rootUri)
            val audioUri = scanner.resolveFile(rootUri, song.relativePath)

            if (audioUri == null) {
                _state.update { it.copy(isLoadingLyrics = false, status = "File is no longer available") }
                return@launch
            }

            playback.playUri(
                mediaId = row.key,
                uri = audioUri.toString(),
                title = song.title,
                artist = row.artist,
                coverUri = null,
            )

            val lyricPath = song.sidecarLyricPath
            if (lyricPath == null) {
                _state.update { it.copy(isLoadingLyrics = false, status = "No lyrics file") }
                // No lyric file is not the same as no prompt: an instrumental is generated from its
                // title, so the auto-generation check still has something to work with.
                maybeAutoGenerateTheme()
                return@launch
            }

            val lyricUri = scanner.resolveFile(rootUri, lyricPath)
            val content = lyricUri?.let { scanner.readText(it) }

            if (content.isNullOrBlank()) {
                _state.update { it.copy(isLoadingLyrics = false, status = "Could not read lyrics") }
                maybeAutoGenerateTheme()
                return@launch
            }

            val format = LyricParseFormat.fromFileName(lyricPath) ?: LyricParseFormat.detect(content)
            val parsed = runCatching {
                parseLyricsByFormat(format = format, content = content)
            }.getOrNull()

            // A translation sidecar is a separate file; when present it is aligned against the main
            // lyric by the parser's own timestamp matching.
            val translation = song.translationLyricPath
                ?.let { scanner.resolveFile(rootUri, it) }
                ?.let { scanner.readText(it) }

            val withTranslation = if (parsed != null && !translation.isNullOrBlank()) {
                runCatching {
                    parseLyricsByFormat(
                        format = format,
                        content = content,
                        translation = translation,
                    )
                }.getOrNull() ?: parsed
            } else {
                parsed
            }

            _state.update {
                it.copy(
                    isLoadingLyrics = false,
                    lyrics = withTranslation?.lines.orEmpty(),
                    status = when {
                        withTranslation == null -> "Could not parse lyrics"
                        withTranslation.lines.isEmpty() -> "No lyrics in file"
                        else -> null
                    },
                )
            }
            // After the lyrics land, so the prompt is the real text rather than an empty list.
            maybeAutoGenerateTheme()
        }
    }

    // --- Online search ---------------------------------------------------------------------------

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty() || _state.value.isSearching) return

        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, status = null, tab = State.Tab.ONLINE) }

            val results = client.searchSongs(query).map { song ->
                TrackRow(
                    key = "online:${song.id}",
                    title = song.name,
                    artist = song.artistLabel,
                    subtitle = song.album?.name,
                    source = Source.ONLINE,
                    onlineSong = song,
                )
            }

            _state.update {
                it.copy(
                    isSearching = false,
                    results = results,
                    status = if (results.isEmpty()) "No results" else null,
                )
            }
        }
    }

    fun selectOnline(song: NeteaseClient.Song) {
        val row = TrackRow(
            key = "online:${song.id}",
            title = song.name,
            artist = song.artistLabel,
            subtitle = song.album?.name,
            source = Source.ONLINE,
            onlineSong = song,
        )

        viewModelScope.launch {
            _state.update {
                it.copy(
                    currentTrack = row,
                    lyrics = emptyList(),
                    isLoadingLyrics = true,
                    status = null,
                )
            }
            applyThemeForTrack(row.key)

            val streamUrl = client.getSongUrl(song.id)
            if (streamUrl == null) {
                _state.update { it.copy(isLoadingLyrics = false, status = "Song unavailable") }
                return@launch
            }

            playback.playUri(
                mediaId = row.key,
                uri = streamUrl,
                title = song.name,
                artist = song.artistLabel,
                coverUri = song.coverUrl,
            )

            val lyrics = client.getLyrics(song.id)
            _state.update {
                it.copy(
                    isLoadingLyrics = false,
                    lyrics = lyrics?.lines.orEmpty(),
                    status = if (lyrics == null) "No lyrics" else null,
                )
            }
            // The online API marks instrumentals with a placeholder lyric line rather than a flag,
            // so the detector runs on the parsed lines here.
            maybeAutoGenerateTheme()
        }
    }

    /** Backwards-compatible alias for the online path, used by the existing screen. */
    fun selectSong(song: NeteaseClient.Song) = selectOnline(song)

    // --- Shared ----------------------------------------------------------------------------------

    fun setTab(tab: State.Tab) {
        _state.update { it.copy(tab = tab) }
    }

    fun cycleLyricMode() {
        _state.update { it.copy(lyricMode = it.lyricMode.next()) }
    }

    // --- AI themes -------------------------------------------------------------------------------

    /**
     * Applies the theme cached for [trackKey], or falls back to the default.
     *
     * Called on every track change. A cached theme is applied immediately so a song the user has
     * already generated for looks right from the first frame; generation only happens afterwards,
     * and only when automatic generation is on.
     */
    private fun applyThemeForTrack(trackKey: String?) {
        val cached = trackKey?.let { themeStore.loadTheme(it) }
        _state.update {
            it.copy(
                songTheme = cached,
                activeTheme = cached?.dark ?: DefaultTheme,
            )
        }
    }

    /**
     * Whether the current track has a prompt worth sending.
     *
     * Instrumentals are prompted with their title, because their lyric file is either absent or a
     * 「纯音乐，请欣赏」 notice — the desktop app makes the same substitution, and without it a whole
     * class of tracks fails with "empty prompt".
     */
    private fun themePromptSource(): Pair<String, Boolean>? {
        val state = _state.value
        val track = state.currentTrack ?: return null

        val lyricText = state.lyrics.joinToString("\n") { it.fullText }.trim()
        val isPureMusic = PureMusicDetector.isPureMusicLyricText(lyricText) ||
            state.lyrics.isEmpty() && track.title.isNotBlank()

        val source = if (isPureMusic) track.title else lyricText
        return source.takeIf { it.isNotBlank() }?.let { it to isPureMusic }
    }

    /**
     * Generates a theme for the current song.
     *
     * [automatic] is set by the auto-generation path on track change. It changes only how failures
     * are reported: a manual request that fails gets a status message, while an automatic one that
     * fails (no key, no network) stays silent and leaves the default theme on screen.
     */
    fun generateTheme(automatic: Boolean = false) {
        val state = _state.value
        if (state.isGeneratingTheme) return

        val config = state.aiConfig
        if (!config.isConfigured) {
            if (!automatic) {
                _state.update { it.copy(status = "Set an AI provider and key in Settings first") }
            }
            return
        }

        val source = state.currentTrack?.let { themePromptSource() }
        if (source == null) {
            if (!automatic) {
                _state.update { it.copy(status = "Nothing to analyse yet") }
            }
            return
        }

        val track = state.currentTrack ?: return
        themeJob?.cancel()

        themeJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isGeneratingTheme = true,
                    status = if (automatic) it.status else "Generating theme…",
                )
            }

            val (text, isPureMusic) = source
            val result = runCatching {
                themeClient.generate(
                    sourceText = text,
                    config = config,
                    isPureMusic = isPureMusic,
                    songTitle = if (isPureMusic) track.title else null,
                )
            }

            val dualTheme = result.getOrNull()
            if (dualTheme == null) {
                val message = result.exceptionOrNull()?.message ?: "Theme generation failed"
                _state.update {
                    it.copy(
                        isGeneratingTheme = false,
                        status = if (automatic) it.status else message,
                    )
                }
                return@launch
            }

            themeStore.saveTheme(track.key, dualTheme)
            _state.update {
                it.copy(
                    isGeneratingTheme = false,
                    songTheme = dualTheme,
                    activeTheme = dualTheme.dark,
                    status = "Theme applied: ${dualTheme.dark.name}",
                )
            }
        }
    }

    fun updateAiConfig(config: AiThemeConfig) {
        themeStore.saveConfig(config)
        _state.update { it.copy(aiConfig = config) }
    }

    /**
     * Switches provider and resets the endpoint/model to that provider's defaults.
     *
     * Leaving the previous provider's URL in place would send a Gemini request to an OpenAI
     * endpoint; the user would see a 404 and have to work out why.
     */
    fun setAiProvider(provider: AiThemeProvider) {
        updateAiConfig(
            _state.value.aiConfig.copy(
                provider = provider,
                baseUrl = themeStore.defaultUrlFor(provider),
                model = themeStore.defaultModelFor(provider),
            )
        )
    }

    fun clearCurrentTheme() {
        val track = _state.value.currentTrack ?: return
        themeStore.deleteTheme(track.key)
        _state.update { it.copy(songTheme = null, activeTheme = DefaultTheme) }
    }

    /**
     * Auto-generates after a track change when enabled and nothing is cached.
     *
     * Kept separate from the selection functions so both the local and online paths call one place,
     * and so the "is there a prompt yet" question is answered after the lyrics have loaded rather
     * than at selection time.
     */
    private fun maybeAutoGenerateTheme() {
        val state = _state.value
        if (!state.aiConfig.autoGenerate) return
        if (!state.aiConfig.isConfigured) return
        if (state.isGeneratingTheme) return
        if (state.songTheme != null) return
        if (state.isLoadingLyrics) return
        if (themePromptSource() == null) return

        generateTheme(automatic = true)
    }

    // --- Playlists -------------------------------------------------------------------------------

    private suspend fun mutatePlaylists(transform: (List<LocalPlaylist>) -> List<LocalPlaylist>) {
        val snapshot = store.load()
        val current = LocalPlaylistOps.ensureFavorites(snapshot.playlists.map { it.toModel() })
        val next = transform(current)

        if (next == current) return

        store.save(snapshot.copy(playlists = next.map { it.toRecord() }))
        refreshLocalLibrary()
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@launch

            mutatePlaylists { LocalPlaylistOps.create(it, trimmed).first }
            _state.update { it.copy(status = "Playlist \"$trimmed\" created") }
        }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        viewModelScope.launch {
            mutatePlaylists { LocalPlaylistOps.rename(it, playlistId, name) }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            val target = _state.value.playlists.firstOrNull { it.id == playlistId }
            if (!LocalPlaylistOps.canDelete(target)) {
                _state.update { it.copy(status = "This playlist cannot be deleted") }
                return@launch
            }

            mutatePlaylists { LocalPlaylistOps.delete(it, playlistId) }
            _state.update { state ->
                state.copy(
                    openedPlaylist = if (state.openedPlaylist?.id == playlistId) null else state.openedPlaylist,
                    playlistSongs = if (state.openedPlaylist?.id == playlistId) emptyList() else state.playlistSongs,
                )
            }
        }
    }

    /** Adds a song to a playlist. Used by the row action and by the "favourite" toggle. */
    fun addToPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            mutatePlaylists { LocalPlaylistOps.addSongs(it, playlistId, listOf(songId)) }
            _state.update { it.copy(status = "Added to playlist") }
        }
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            mutatePlaylists { LocalPlaylistOps.removeSongs(it, playlistId, listOf(songId)) }
            refreshOpenedPlaylist()
        }
    }

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            val currentlyFavorite = LocalPlaylistOps.isFavorite(_state.value.playlists, songId)
            mutatePlaylists { LocalPlaylistOps.setFavorite(it, songId, !currentlyFavorite) }
            _state.update {
                it.copy(status = if (currentlyFavorite) "Removed from favourites" else "Added to favourites")
            }
        }
    }

    fun openPlaylist(playlist: LocalPlaylist) {
        _state.update {
            it.copy(
                openedPlaylist = playlist,
                playlistSongs = songsOfPlaylist(playlist),
                localBrowse = State.LocalBrowse.SONGS,
            )
        }
    }

    fun closePlaylist() {
        _state.update { it.copy(openedPlaylist = null, playlistSongs = emptyList()) }
    }

    /** Re-resolves the open playlist's songs after a mutation. */
    private fun refreshOpenedPlaylist() {
        val state = _state.value
        val open = state.openedPlaylist ?: return
        val refreshed = state.playlists.firstOrNull { it.id == open.id } ?: return
        _state.update { it.copy(openedPlaylist = refreshed, playlistSongs = songsOfPlaylist(refreshed)) }
    }

    /** A playlist's songs, in the playlist's own order. */
    private fun songsOfPlaylist(playlist: LocalPlaylist): List<LocalLibraryStore.SongRecord> {
        val byId = _state.value.localSongs.associateBy { it.id }
        return playlist.songIds.mapNotNull { byId[it] }
    }

    fun setLocalBrowse(browse: State.LocalBrowse) {
        _state.update { it.copy(localBrowse = browse) }
    }

    /**
     * Records the entity the user long-pressed, opening the merge target picker.
     *
     * Held in state rather than passed as a callback argument so the picker survives recomposition
     * and so a configuration change does not silently drop a half-finished merge.
     */
    fun requestMerge(entity: EntitySummary) {
        _state.update { it.copy(pendingMerge = entity, openedEntity = null) }
    }

    fun openEntity(entity: EntitySummary) {
        _state.update {
            it.copy(
                openedEntity = entity,
                entitySongs = songsForEntity(entity.id),
                pendingMerge = null,
            )
        }
    }

    fun cancelMerge() {
        _state.update { it.copy(pendingMerge = null) }
    }

    fun closeEntity() {
        _state.update { it.copy(openedEntity = null, entitySongs = emptyList()) }
    }

    /**
     * Merges one entity into another.
     *
     * Deliberately without an undo in the UI: `mergeLibraryEntities` never deletes the loser, it
     * only points it at the winner, so `unmergeLibraryEntity` can restore it. What is lost is the
     * winner's extra alias, which is why the merge dialog shows the aliases that will move.
     */
    fun mergeEntities(sourceId: String, targetId: String) {
        viewModelScope.launch {
            val snapshot = store.load()
            val entities = snapshot.entities.map { it.toModel() }.toMutableList()

            val merged = mergeLibraryEntities(entities, sourceId, targetId)
            if (!merged) {
                _state.update { it.copy(status = "Merge refused: incompatible entities") }
                return@launch
            }

            store.save(
                snapshot.copy(entities = entities.map { it.toRecord() })
            )
            refreshLocalLibrary()
            _state.update { it.copy(status = "Merged") }
        }
    }

    /** Undoes a merge. See [mergeEntities] for why the aliases cannot come back exactly. */
    fun unmergeEntity(entityId: String) {
        viewModelScope.launch {
            val snapshot = store.load()
            val entities = snapshot.entities.map { it.toModel() }.toMutableList()

            if (unmergeLibraryEntity(entities, entityId)) {
                store.save(snapshot.copy(entities = entities.map { it.toRecord() }))
                _state.update { it.copy(openedEntity = null, entitySongs = emptyList()) }
                refreshLocalLibrary()
                _state.update { it.copy(status = "Split back apart") }
            }
        }
    }

    /**
     * The cover URI for a song, or null.
     *
     * Resolves the content-addressed id to a file URI. Returns null when the asset is missing —
     * a corrupt index entry must degrade to "no cover" rather than a broken image request.
     */
    fun coverUriFor(song: LocalLibraryStore.SongRecord): Uri? {
        val assetId = song.coverAssetId ?: return null
        return if (covers.exists(assetId)) covers.uriFor(assetId) else null
    }

    /** The songs an entity owns, following merges to the survivor. */
    fun songsForEntity(entityId: String): List<LocalLibraryStore.SongRecord> {
        val entities = localEntities.value
        val assignments = localAssignments.value
        if (entities.isEmpty()) return emptyList()

        val index = LibraryIndex(entities)
        val survivingId = index.followRedirect(entityId) ?: return emptyList()

        val songIds = mutableSetOf<String>()
        for (assignment in assignments) {
            val artistSurvives = assignment.artistEntityIds.any { index.followRedirect(it) == survivingId }
            val albumSurvives = assignment.albumEntityId?.let { index.followRedirect(it) == survivingId } == true
            if (artistSurvives || albumSurvives) songIds.add(assignment.songId)
        }

        return _state.value.localSongs.filter { it.id in songIds }
    }

    /**
     * The line that should be on screen for a given playback position.
     *
     * Uses [getLineRenderEndTime] rather than `line.endTime` so a line stays up through its exit
     * animation. Iterating from the end finds the latest line that has started, which is what a
     * backwards seek also needs.
     */
    fun activeLineIndex(positionSec: Double): Int {
        val lines = _state.value.lyrics
        for (index in lines.indices.reversed()) {
            val line = lines[index]
            if (positionSec >= line.startTime && positionSec <= getLineRenderEndTime(line)) {
                return index
            }
        }
        return -1
    }

    companion object {
        fun factory(context: Context, playback: PlaybackController): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(context.applicationContext, playback) as T
            }
    }
}