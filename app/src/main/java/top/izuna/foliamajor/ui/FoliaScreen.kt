package top.izuna.foliamajor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.izuna.foliamajor.local.EntityKind
import top.izuna.foliamajor.playback.ReplayGainMode
import top.izuna.foliamajor.playback.PlaybackController
import top.izuna.foliamajor.theme.toThemeColor
import top.izuna.foliamajor.ui.theme.foliaTheme
import top.izuna.foliamajor.ui.lyrics.CadenzaLyricCanvas
import top.izuna.foliamajor.ui.lyrics.CappellaLyricCanvas
import top.izuna.foliamajor.ui.lyrics.FumeLyricCanvas
import top.izuna.foliamajor.ui.lyrics.CladdaghLyricCanvas
import top.izuna.foliamajor.ui.lyrics.LyricCanvas
import top.izuna.foliamajor.ui.lyrics.LyricMode
import top.izuna.foliamajor.ui.lyrics.PartitaLyricCanvas
import top.izuna.foliamajor.ui.lyrics.PendoloLyricCanvas
import top.izuna.foliamajor.ui.lyrics.StillLyricCanvas
import top.izuna.foliamajor.ui.lyrics.TiltLyricCanvas
import coil.compose.AsyncImage

/**
 * The whole screen: a full-bleed lyric field with a song list, search and transport overlaid.
 *
 * Mirrors the desktop app's layout priority — lyrics own the screen, everything else floats above
 * them and fades out. The list is an overlay rather than a navigation destination because the lyric
 * field must never unmount: tearing down the renderer on every song change is exactly the flicker
 * the desktop app's runtime host exists to avoid.
 */
@Composable
fun FoliaScreen(
    state: PlayerViewModel.State,
    playback: PlaybackController.State,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectOnline: (top.izuna.foliamajor.online.NeteaseClient.Song) -> Unit,
    onSelectLocal: (top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> Unit,
    onImportFolder: (android.net.Uri) -> Unit,
    onTabChange: (PlayerViewModel.State.Tab) -> Unit,
    onLocalBrowseChange: (PlayerViewModel.State.LocalBrowse) -> Unit,
    onMergeRequest: (PlayerViewModel.EntitySummary) -> Unit,
    onMerge: (sourceId: String, targetId: String) -> Unit,
    onOpenEntity: (PlayerViewModel.EntitySummary) -> Unit,
    onCancelMerge: () -> Unit,
    onCloseEntity: () -> Unit,
    onUnmergeEntity: (String) -> Unit,
    onReplayGainModeChange: (top.izuna.foliamajor.playback.ReplayGainMode) -> Unit,
    onCycleLyricMode: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (top.izuna.foliamajor.local.LocalPlaylist) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onClosePlaylist: () -> Unit,
    onAddToPlaylist: (playlistId: String, songId: String) -> Unit,
    coverUriFor: (top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> android.net.Uri?,
    /** Songs belonging to the opened entity, resolved by the ViewModel. */
    entitySongs: List<top.izuna.foliamajor.local.LocalLibraryStore.SongRecord>,
    onTogglePlayPause: () -> Unit,
    onSeekToLine: (Double) -> Unit,
    onGenerateTheme: () -> Unit,
    onClearTheme: () -> Unit,
    onAiConfigChange: (top.izuna.foliamajor.theme.AiThemeConfig) -> Unit,
    onAiProviderChange: (top.izuna.foliamajor.theme.AiThemeProvider) -> Unit,
    onOpenLogin: () -> Unit,
    onCloseLogin: () -> Unit,
    onRefreshQrCode: () -> Unit,
    onSignOutNetease: () -> Unit,
    onNeteaseApiBaseChange: (String) -> Unit,
) {
    val toTheme = foliaTheme()
    val background = toTheme.backgroundColor.toThemeColor()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    // The lyric field is the app's whole point, so it starts visible. The list opens on demand.
    var listVisible by remember { mutableStateOf(false) }

    // The song pending an "add to playlist" choice, set by long-pressing a row.
    var addToPlaylistFor by remember {
        mutableStateOf<top.izuna.foliamajor.local.LocalLibraryStore.SongRecord?>(null)
    }

    // The AI settings sheet.
    var settingsVisible by remember { mutableStateOf(false) }

    val viewModelUnmerge: (String) -> Unit = onUnmergeEntity


    // SAF folder picker. The tree URI is persisted by the importer, so the grant survives restarts.
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) onImportFolder(uri)
    }

    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()

    // Height the transport block occupies, measured rather than assumed. The lyric field used to
    // reserve a hard-coded 120dp at the bottom, which is only a third of the real transport block:
    // the preview line and the translation then landed on the track title, and a bottom-anchored
    // mode (Cappella) put its newest bubble over the play button.
    var transportHeightPx by remember { mutableIntStateOf(0) }
    val transportHeight = with(LocalDensity.current) { transportHeightPx.toDp() }

    val activeIndex = remember(state.lyrics, playback.positionSec) {
        activeLineIndex(state.lyrics, playback.positionSec)
    }
    val activeLine = state.lyrics.getOrNull(activeIndex)
    val nextLine = state.lyrics.getOrNull(activeIndex + 1)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        // The lyric field, inset so the status bar and the transport never overlap lyric text.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(top = safeInsets.calculateTopPadding(), bottom = transportHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                // Every branch is listed rather than falling through an `else`: the stage height is
                // the one thing a new mode must decide, and a default here is how a transcript ends
                // up wearing a single-line frame.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when (state.lyricMode) {
                            LyricMode.CLASSIC -> Modifier.height(240.dp)
                            LyricMode.STILL -> Modifier.height(300.dp)
                            LyricMode.TILT -> Modifier.height(240.dp)
                            LyricMode.CLADDAGH, LyricMode.PARTITA -> Modifier.height(320.dp)
                            LyricMode.CADENZA -> Modifier.height(360.dp)
                            // Fume is a whole-page composition with its own camera; the box is the
                            // camera's viewport rather than a frame around one line.
                            LyricMode.FUME -> Modifier.height(420.dp)
                            // Pendolo's hub sits at mid-height with the gear train hanging below
                            // it, so it wants every pixel the transport leaves. `fillMaxHeight`
                            // rather than `fillMaxSize`: the latter would also take the column's
                            // width and push the wheel off-centre.
                            LyricMode.PENDOLO -> Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                            // Cappella is a chat list, and a chat list anchors to its bottom edge, so
                            // the stage is capped rather than sized by its content. It cannot take
                            // the literal remaining height: the column's bottom inset (120dp) is
                            // smaller than the transport block, so "remaining" reaches underneath
                            // the play button and the newest bubble lands on top of it.
                            LyricMode.CAPPELLA -> Modifier.height(420.dp)
                        }
                    ),
            ) {
                when (state.lyricMode) {
                    LyricMode.CLASSIC -> LyricCanvas(
                        line = activeLine,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        fontSizeSp = 30f,
                    )

                    LyricMode.STILL -> StillLyricCanvas(
                        line = activeLine,
                        nextLine = nextLine,
                        previousLine = state.lyrics.getOrNull(activeIndex - 1),
                        theme = toTheme,
                        fontSizeSp = 28f,
                    )

                    LyricMode.TILT -> TiltLyricCanvas(
                        line = activeLine,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        fontSizeSp = 28f,
                        options = top.izuna.foliamajor.lyrics.layout.TiltOptions(),
                    )

                    LyricMode.PARTITA -> PartitaLyricCanvas(
                        line = activeLine,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        fontSizeSp = 28f,
                        options = top.izuna.foliamajor.lyrics.layout.PartitaOptions(),
                    )

                    LyricMode.CLADDAGH -> CladdaghLyricCanvas(
                        line = activeLine,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        fontSizeSp = 26f,
                    )

                    LyricMode.CADENZA -> CadenzaLyricCanvas(
                        line = activeLine,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        fontSizeSp = 30f,
                    )

                    LyricMode.FUME -> FumeLyricCanvas(
                        lines = state.lyrics,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                    )

                    LyricMode.CAPPELLA -> CappellaLyricCanvas(
                        lines = state.lyrics,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        title = state.currentTrack?.title.orEmpty().ifBlank { "Lyrics" },
                    )

                    LyricMode.PENDOLO -> PendoloLyricCanvas(
                        lines = state.lyrics,
                        positionSec = playback.positionSec,
                        theme = toTheme,
                        isPlaying = playback.isPlaying,
                    )
                }
            }

            AnimatedVisibility(
                // Modes that draw their own context show what is coming themselves, so the shared
                // preview would overlap live text rather than supplement it.
                visible = nextLine != null && !state.lyricMode.drawsOwnContext,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    text = nextLine?.fullText.orEmpty(),
                    color = secondary.copy(alpha = 0.5f),
                    fontSize = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            activeLine?.translation?.takeIf {
                it.isNotBlank() && !state.lyricMode.drawsOwnContext
            }?.let { translation ->
                Text(
                    text = translation,
                    color = secondary.copy(alpha = 0.75f),
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                )
            }
        }

        // Transport, pinned to the bottom.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { transportHeightPx = it.height }
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 24.dp,
                    bottom = 24.dp + safeInsets.calculateBottomPadding(),
                ),
        ) {
            state.currentTrack?.let { track ->
                Text(
                    text = track.title,
                    color = primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    color = secondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            state.status?.let { status ->
                Text(
                    text = status,
                    color = secondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            state.importProgress?.let { progress ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = accent,
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = progress, color = secondary, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Two rows: play/position/modes, then the action icons.
            //
            // One row does not fit. At 360dp the play button, timer, mode labels and five icon
            // buttons need ~410dp, and Compose resolves the overflow by shrinking the icon buttons
            // to a few pixels each — they stay tappable but become invisible, which is worse than
            // wrapping. Splitting also puts the transport where the thumb expects it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(accent.copy(alpha = 0.18f)),
                ) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = primary,
                    )
                }

                Spacer(Modifier.width(16.dp))

                if (playback.isBuffering || state.isLoadingLyrics) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Text(
                    text = formatPosition(playback.positionSec, playback.durationMs / 1000.0),
                    color = secondary,
                    fontSize = 12.sp,
                )

                Spacer(Modifier.weight(1f))

                // Lyric mode. Tapping cycles through the implemented visualizers.
                Text(
                    text = state.lyricMode.label,
                    color = accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable { onCycleLyricMode() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )

                // ReplayGain mode. A single tap cycles off → track → album; a menu would be
                // heavier than the choice deserves, and the label states the current mode.
                Text(
                    text = when (state.replayGainMode) {
                        ReplayGainMode.OFF -> "RG off"
                        ReplayGainMode.TRACK -> "RG track"
                        ReplayGainMode.ALBUM -> "RG album"
                    },
                    color = if (state.replayGainMode == ReplayGainMode.OFF) secondary else accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clickable {
                            onReplayGainModeChange(
                                when (state.replayGainMode) {
                                    ReplayGainMode.OFF -> ReplayGainMode.TRACK
                                    ReplayGainMode.TRACK -> ReplayGainMode.ALBUM
                                    ReplayGainMode.ALBUM -> ReplayGainMode.OFF
                                }
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // AI theme. Tapping generates one for the current song; the sparkle turns accent
                // coloured while a request is in flight so the user can see it is working without
                // a separate progress row.
                IconButton(
                    onClick = onGenerateTheme,
                    enabled = !state.isGeneratingTheme,
                ) {
                    if (state.isGeneratingTheme) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = accent,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "Generate AI theme",
                            tint = if (state.usingAiTheme) accent else secondary,
                        )
                    }
                }

                IconButton(onClick = { settingsVisible = true }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "AI settings",
                        tint = secondary,
                    )
                }

                // Account. Accent when signed in, so the state is visible without opening the sheet.
                IconButton(onClick = onOpenLogin) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = "NetEase account",
                        tint = if (state.neteaseSession.isSignedIn) accent else secondary,
                    )
                }

                // Import is the only way to get music into this client without an online account.
                IconButton(onClick = { folderPicker.launch(null) }) {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = "Import folder",
                        tint = secondary,
                    )
                }

                IconButton(onClick = { listVisible = !listVisible }) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = "Song list",
                        tint = if (listVisible) accent else secondary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search online", fontSize = 14.sp) },
                singleLine = true,
                // Enter searches. Without this the IME's action key inserts a newline into a
                // single-line field, which does nothing visible and leaves the user thinking the
                // search is broken.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch() },
                ),
                trailingIcon = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = secondary)
                    }
                },
            )
        }

        // Song list. Covers the lyric field only while the user is browsing, and stops above the
        // transport row: covering the transport would hide the only control that closes the list.
        AnimatedVisibility(
            visible = listVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    // fillMaxWidth, not fillMaxSize: the transport block below stays visible and
                    // tappable while the list is open. With fillMaxSize the list covered the whole
                    // screen — including the button that closes it — and the padding only inset the
                    // content inside an overlay that still swallowed every tap.
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .background(background.copy(alpha = 0.96f))
                    .padding(
                        top = 32.dp + safeInsets.calculateTopPadding(),
                        bottom = 8.dp,
                    ),
            ) {
                TabRow(
                    selected = state.tab,
                    localCount = state.localSongs.size,
                    onSelect = onTabChange,
                )

                when (state.tab) {
                    PlayerViewModel.State.Tab.LOCAL -> {
                        // A second, local-only nav: the same songs grouped three ways.
                        BrowseRow(
                            selected = state.localBrowse,
                            onSelect = onLocalBrowseChange,
                        )

                        when (state.localBrowse) {
                            PlayerViewModel.State.LocalBrowse.SONGS -> LocalList(
                                // An open playlist replaces the full library in this view.
                                songs = state.openedPlaylist?.let { state.playlistSongs } ?: state.localSongs,
                                currentKey = state.currentTrack?.key,
                                coverUriFor = coverUriFor,
                                header = state.openedPlaylist?.name,
                                onCloseHeader = if (state.openedPlaylist != null) onClosePlaylist else null,
                                onLongPress = { song -> addToPlaylistFor = song },
                                onSelect = { song ->
                                    onSelectLocal(song)
                                    listVisible = false
                                },
                            )

                            PlayerViewModel.State.LocalBrowse.ARTISTS -> EntityList(
                                entities = state.artists,
                                onMergeRequest = onMergeRequest,
                                onShowSongs = onOpenEntity,
                            )

                            PlayerViewModel.State.LocalBrowse.ALBUMS -> EntityList(
                                entities = state.albums,
                                onMergeRequest = onMergeRequest,
                                onShowSongs = onOpenEntity,
                            )

                            PlayerViewModel.State.LocalBrowse.PLAYLISTS -> PlaylistList(
                                playlists = state.playlists,
                                onCreate = onCreatePlaylist,
                                onOpen = onOpenPlaylist,
                                onDelete = onDeletePlaylist,
                            )
                        }
                    }

                    PlayerViewModel.State.Tab.ONLINE -> OnlineList(
                        rows = state.results,
                        isSearching = state.isSearching,
                        currentKey = state.currentTrack?.key,
                        onSelect = { row ->
                            row.onlineSong?.let(onSelectOnline)
                            listVisible = false
                        },
                    )
                }
            }
        }

        // BACK closes the topmost overlay instead of the Activity.
        //
        // Both overlays are plain full-screen Boxes drawn over the same screen, so without this the
        // system BACK finishes MainActivity — the user loses their place (and, mid-merge, the
        // selection) for what reads as "close this panel". The song list is included: it is an
        // overlay too, and BACK is the natural way out of it.
        BackHandler(
            enabled = state.openedEntity != null ||
                state.pendingMerge != null ||
                settingsVisible ||
                state.loginVisible ||
                addToPlaylistFor != null ||
                listVisible
        ) {
            when {
                state.loginVisible -> onCloseLogin()
                settingsVisible -> settingsVisible = false
                state.openedEntity != null -> onCloseEntity()
                state.pendingMerge != null -> onCancelMerge()
                addToPlaylistFor != null -> addToPlaylistFor = null
                listVisible -> listVisible = false
            }
        }

        // Merge target picker. Only entities of the same kind are offered: merging an album into an
        // artist is refused by mergeLibraryEntities anyway, and offering it would just be a wasted
        // tap that looks like a bug.
        state.pendingMerge?.let { source ->
            MergeTargetPicker(
                source = source,
                candidates = when (source.kind) {
                    EntityKind.ARTIST -> state.artists
                    EntityKind.ALBUM -> state.albums
                },
                onPick = { target ->
                    onMerge(source.id, target.id)
                    // The picker closes immediately: the merge runs in the background, and leaving
                    // the sheet open would leave the user staring at a list they already chose from.
                    onCancelMerge()
                },
                onDismiss = onCancelMerge,
            )
        }

        // The songs an entity owns. Read-only: browsing into an entity must not be a way to edit it.
        // Playlist picker for a long-pressed song.
        addToPlaylistFor?.let { song ->
            AddToPlaylistSheet(
                songTitle = song.title,
                playlists = state.playlists,
                onPick = { playlist ->
                    onAddToPlaylist(playlist.id, song.id)
                    addToPlaylistFor = null
                },
                onDismiss = { addToPlaylistFor = null },
            )
        }

        if (settingsVisible) {
            AiSettingsSheet(
                config = state.aiConfig,
                songTheme = state.songTheme,
                usingAiTheme = state.usingAiTheme,
                onConfigChange = onAiConfigChange,
                onProviderChange = onAiProviderChange,
                onGenerate = onGenerateTheme,
                onClear = onClearTheme,
                onDismiss = { settingsVisible = false },
            )
        }

        if (state.loginVisible) {
            LoginSheet(
                session = state.neteaseSession,
                qr = state.qrLogin,
                apiBase = state.neteaseApiBase,
                onRefreshCode = onRefreshQrCode,
                onApiBaseChange = onNeteaseApiBaseChange,
                onSignOut = onSignOutNetease,
                onDismiss = onCloseLogin,
            )
        }

        state.openedEntity?.let { entity ->
            EntitySongsSheet(
                entity = entity,
                songs = entitySongs,
                currentKey = state.currentTrack?.key,
                coverUriFor = coverUriFor,
                onSelect = onSelectLocal,
                onUnmerge = { viewModelUnmerge(entity.id) },
                onDismiss = onCloseEntity,
            )
        }
    }
}

@Composable
private fun TabRow(
    selected: PlayerViewModel.State.Tab,
    localCount: Int,
    onSelect: (PlayerViewModel.State.Tab) -> Unit,
) {
    val toTheme = foliaTheme()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        TabLabel(
            text = "Local ($localCount)",
            isSelected = selected == PlayerViewModel.State.Tab.LOCAL,
            primary = primary,
            secondary = secondary,
            accent = accent,
            onClick = { onSelect(PlayerViewModel.State.Tab.LOCAL) },
        )
        TabLabel(
            text = "Online",
            isSelected = selected == PlayerViewModel.State.Tab.ONLINE,
            primary = primary,
            secondary = secondary,
            accent = accent,
            onClick = { onSelect(PlayerViewModel.State.Tab.ONLINE) },
        )
    }
}

@Composable
private fun TabLabel(
    text: String,
    isSelected: Boolean,
    primary: Color,
    secondary: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = if (isSelected) accent else secondary,
        fontSize = 14.sp,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

@Composable
private fun LocalList(
    songs: List<top.izuna.foliamajor.local.LocalLibraryStore.SongRecord>,
    currentKey: String?,
    coverUriFor: (top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> android.net.Uri?,
    header: String? = null,
    onCloseHeader: (() -> Unit)? = null,
    onLongPress: ((top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> Unit)? = null,
    onSelect: (top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> Unit,
) {
    val toTheme = foliaTheme()
    val accent = toTheme.accentColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()

    if (songs.isEmpty()) {
        EmptyHint(
            if (header != null) "This playlist is empty." else "No local songs yet.\nTap the folder icon to import music."
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (header != null) {
            item(key = "__header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = header,
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (onCloseHeader != null) {
                        Text(
                            text = "Close",
                            color = secondary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable(onClick = onCloseHeader)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        items(songs, key = { it.id }) { song ->
            SongRow(
                title = song.title,
                artist = song.artistNames.joinToString("/").ifEmpty { "Unknown Artist" },
                detail = buildString {
                    song.albumName?.let { append(it) }
                    if (song.sidecarLyricPath != null) {
                        if (isNotEmpty()) append(" · ")
                        append("lyrics")
                    }
                }.ifEmpty { null },
                isCurrent = currentKey == "local:${song.id}",
                coverUri = coverUriFor(song),
                onLongClick = onLongPress?.let { { it(song) } },
                onClick = { onSelect(song) },
            )
        }
    }
}

@Composable
private fun OnlineList(
    rows: List<PlayerViewModel.TrackRow>,
    isSearching: Boolean,
    currentKey: String?,
    onSelect: (PlayerViewModel.TrackRow) -> Unit,
) {
    when {
        isSearching -> EmptyHint("Searching…")
        rows.isEmpty() -> EmptyHint("Search for a song above.")
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.key }) { row ->
                SongRow(
                    title = row.title,
                    artist = row.artist,
                    detail = row.subtitle,
                    isCurrent = currentKey == row.key,
                    coverUri = null,
                    onClick = { onSelect(row) },
                )
            }
        }
    }
}

/**
 * The local tab's secondary navigation: the same songs grouped three ways.
 *
 * Smaller than the main tab row because it is a refinement of the current tab, not a peer tab.
 */
@Composable
private fun BrowseRow(
    selected: PlayerViewModel.State.LocalBrowse,
    onSelect: (PlayerViewModel.State.LocalBrowse) -> Unit,
) {
    val toTheme = foliaTheme()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val items = listOf(
            "Songs" to PlayerViewModel.State.LocalBrowse.SONGS,
            "Artists" to PlayerViewModel.State.LocalBrowse.ARTISTS,
            "Albums" to PlayerViewModel.State.LocalBrowse.ALBUMS,
            "Playlists" to PlayerViewModel.State.LocalBrowse.PLAYLISTS,
        )

        for ((label, browse) in items) {
            val isSelected = selected == browse
            Text(
                text = label,
                color = if (isSelected) accent else secondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .clickable { onSelect(browse) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * Artist or album rows.
 *
 * A row is merged by long-pressing it and then picking another row, which matches how the desktop
 * app selects merge targets. The `needsReview` badge is the whole reason this view exists: an entity
 * the importer could not disambiguate would otherwise be indistinguishable from a clean one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntityList(
    entities: List<PlayerViewModel.EntitySummary>,
    onMergeRequest: (PlayerViewModel.EntitySummary) -> Unit,
    onShowSongs: (PlayerViewModel.EntitySummary) -> Unit,
) {
    val toTheme = foliaTheme()
    if (entities.isEmpty()) {
        EmptyHint("Nothing to show yet.")
        return
    }

    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entities, key = { it.id }) { entity ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onShowSongs(entity) },
                        onLongClick = { onMergeRequest(entity) },
                    )
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.name,
                        color = if (entity.needsReview) accent else primary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append("${entity.songCount} songs")
                            if (entity.aliases.size > 1) {
                                append(" · ${entity.aliases.size - 1} aliases")
                            }
                        },
                        color = secondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entity.needsReview) {
                        Text(
                            text = "Needs review · possible duplicate",
                            color = accent,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The merge target picker.
 *
 * Merging is destructive in the sense that matters: the loser's aliases move to the winner and the
 * split is only approximate afterwards. So the header states exactly what will happen, and the list
 * shows song counts so the user can tell "1 song" from "40 songs" before committing.
 */
@Composable
private fun MergeTargetPicker(
    source: PlayerViewModel.EntitySummary,
    candidates: List<PlayerViewModel.EntitySummary>,
    onPick: (PlayerViewModel.EntitySummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val toTheme = foliaTheme()
    val background = toTheme.backgroundColor.toThemeColor()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background.copy(alpha = 0.97f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 24.dp),
        ) {
            Text(
                text = "Merge «${source.name}»",
                color = primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "Its ${source.songCount} songs and ${source.aliases.size} aliases move to " +
                    "the entry you pick. Existing songs keep their files.",
                color = secondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )

            Spacer(Modifier.height(8.dp))

            val others = candidates.filterNot { it.id == source.id }
            if (others.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No other entries to merge into.", color = secondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(others, key = { it.id }) { target ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(target) }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = target.name,
                                color = primary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append("${target.songCount} songs")
                                    if (target.aliases.size > 1) {
                                        append(" · ${target.aliases.size - 1} aliases")
                                    }
                                },
                                color = secondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Tap outside to cancel",
            color = accent.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

/** The songs an artist or album owns, in playing order. */
@Composable
private fun EntitySongsSheet(
    entity: PlayerViewModel.EntitySummary,
    songs: List<top.izuna.foliamajor.local.LocalLibraryStore.SongRecord>,
    currentKey: String?,
    coverUriFor: (top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> android.net.Uri?,
    onSelect: (top.izuna.foliamajor.local.LocalLibraryStore.SongRecord) -> Unit,
    onUnmerge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val toTheme = foliaTheme()
    val background = toTheme.backgroundColor.toThemeColor()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background.copy(alpha = 0.97f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 24.dp),
        ) {
            Text(
                text = entity.name,
                color = primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "${entity.songCount} songs",
                color = secondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            entity.aliases.takeIf { it.size > 1 }?.let { aliases ->
                Text(
                    text = "Also known as: ${aliases.joinToString(" / ")}",
                    color = secondary.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }

            // Only an entity that was merged *into* another has anything to split back apart.
            // Showing the button on a surviving entity would be a no-op that looks like a failure.
            if (entity.mergedInto != null) {
                Text(
                    text = "Merged into another entry",
                    color = accent.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                Text(
                    text = "Split back apart",
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onUnmerge() }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            if (songs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No songs.", color = secondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            title = song.title,
                            artist = song.artistNames.joinToString("/")
                                .ifEmpty { "Unknown Artist" },
                            detail = null,
                            isCurrent = currentKey == "local:${song.id}",
                            coverUri = coverUriFor(song),
                            onClick = { onSelect(song) },
                        )
                    }
                }
            }
        }

        Text(
            text = "Tap outside to close",
            color = accent.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

/**
 * The playlist list.
 *
 * Creating is inline (a text field plus a button) rather than a dialog: a playlist is one short
 * string, and a modal for it costs more taps than the naming does.
 */
@Composable
private fun PlaylistList(
    playlists: List<top.izuna.foliamajor.local.LocalPlaylist>,
    onCreate: (String) -> Unit,
    onOpen: (top.izuna.foliamajor.local.LocalPlaylist) -> Unit,
    onDelete: (String) -> Unit,
) {
    val toTheme = foliaTheme()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    var draftName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("New playlist", fontSize = 13.sp) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Create",
                color = if (draftName.isBlank()) secondary.copy(alpha = 0.4f) else accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(enabled = draftName.isNotBlank()) {
                        onCreate(draftName)
                        draftName = ""
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(playlist) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            color = if (playlist.isFavorite) accent else primary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${playlist.songIds.size} songs",
                            color = secondary,
                            fontSize = 11.sp,
                        )
                    }

                    // The favourites list is not deletable, so no button is shown for it.
                    if (!playlist.isFavorite) {
                        Text(
                            text = "Delete",
                            color = secondary.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clickable { onDelete(playlist.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The playlist picker shown after long-pressing a song. */
@Composable
private fun AddToPlaylistSheet(
    songTitle: String,
    playlists: List<top.izuna.foliamajor.local.LocalPlaylist>,
    onPick: (top.izuna.foliamajor.local.LocalPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    val toTheme = foliaTheme()
    val background = toTheme.backgroundColor.toThemeColor()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background.copy(alpha = 0.97f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 24.dp),
        ) {
            Text(
                text = "Add to playlist",
                color = primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = songTitle,
                color = secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(8.dp))

            if (playlists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No playlists yet.", color = secondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(playlists, key = { it.id }) { playlist ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist) }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = playlist.name,
                                color = if (playlist.isFavorite) accent else primary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${playlist.songIds.size} songs",
                                color = secondary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Tap outside to cancel",
            color = accent.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    val toTheme = foliaTheme()
    val secondary = toTheme.secondaryColor.toThemeColor()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = secondary.copy(alpha = 0.7f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

/**
 * The NetEase account sheet: signed-out prompt with a QR code, or the signed-in profile.
 *
 * The QR image arrives as a `data:image/png;base64,…` URL because the API renders it server-side.
 * Coil loads data URLs directly, so there is no Base64 decode and no Bitmap in this file.
 */
@Composable
private fun LoginSheet(
    session: top.izuna.foliamajor.online.NeteaseSessionStore.Session,
    qr: PlayerViewModel.QrLoginUi?,
    apiBase: String,
    onRefreshCode: () -> Unit,
    onApiBaseChange: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    val toTheme = foliaTheme()
    val background = toTheme.backgroundColor.toThemeColor()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    // Buffered locally: committing on every keystroke would sign the user out (and cancel the QR
    // poll) as soon as the first character of a new URL arrived.
    var endpointDraft by remember(apiBase) { mutableStateOf(apiBase) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        // Behind the content, so taps on the code and the buttons still land.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 48.dp, bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "NetEase account",
                color = primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(16.dp))

            if (session.isSignedIn) {
                AsyncImage(
                    model = session.avatarUrl.ifBlank { null },
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(accent.copy(alpha = 0.15f)),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = session.nickname.ifBlank { "Signed in" },
                    color = primary,
                    fontSize = 15.sp,
                )
                Text(
                    text = "UID ${session.userId}" +
                        if (session.vipType > 0) " · VIP ${session.vipType}" else "",
                    color = secondary,
                    fontSize = 11.sp,
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Sign out",
                    color = accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(onClick = onSignOut)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            } else {
                val state = qr?.state
                val image = qr?.imageBytes

                if (image != null) {
                    // The API draws the QR on a white field with no margin of its own; the padding is
                    // what makes it scannable against a dark theme.
                    AsyncImage(
                        model = image,
                        contentDescription = "Login QR code",
                        modifier = Modifier
                            .size(220.dp)
                            .background(Color.White)
                            .padding(10.dp),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .background(accent.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = accent,
                            strokeWidth = 2.dp,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = when (state) {
                        null -> "Requesting a code…"
                        top.izuna.foliamajor.online.QrLoginState.Waiting -> "Scan with the NetEase app"
                        top.izuna.foliamajor.online.QrLoginState.Scanned ->
                            "Scanned — confirm on your phone"
                        top.izuna.foliamajor.online.QrLoginState.Expired -> "The code expired"
                        is top.izuna.foliamajor.online.QrLoginState.Confirmed -> "Signing in…"
                        is top.izuna.foliamajor.online.QrLoginState.Failed ->
                            (state as top.izuna.foliamajor.online.QrLoginState.Failed).message
                    },
                    color = if (state is top.izuna.foliamajor.online.QrLoginState.Failed) accent else secondary,
                    fontSize = 13.sp,
                )

                if (state is top.izuna.foliamajor.online.QrLoginState.Scanned) {
                    Text(
                        text = "Tap the confirm button in the app on your phone.",
                        color = secondary.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "New code",
                    color = accent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(onClick = onRefreshCode)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            // The API address. Every user hosts their own NetEase API instance, so this cannot be a
            // build-time constant; changing it signs the current account out because the cookies
            // belong to the server that issued them.
            Spacer(Modifier.height(20.dp))
            Text(
                text = "API address",
                color = secondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
            OutlinedTextField(
                value = endpointDraft,
                onValueChange = { endpointDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                placeholder = { Text("http://192.168.1.10:3000", fontSize = 13.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = primary),
            )
            Text(
                text = "Apply",
                color = if (endpointDraft.trim() != apiBase) accent else secondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable(enabled = endpointDraft.trim() != apiBase) {
                        onApiBaseChange(endpointDraft)
                    }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        Text(
            text = "Tap outside to close",
            color = accent.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
        )
    }
}

/**
 * AI provider settings and the current song's theme.
 *
 * A plain scrollable form rather than a preference screen: there are four fields, and the sheet also
 * shows the generated theme's name and provider, which a preference screen has nowhere to put.
 *
 * The key field is masked but not encrypted at rest — Android's autofill and backup exclusions are
 * the platform answer to that, and pretending a local XOR would add is worse than being honest about
 * where the key lives.
 */
@Composable
private fun AiSettingsSheet(
    config: top.izuna.foliamajor.theme.AiThemeConfig,
    songTheme: top.izuna.foliamajor.theme.DualTheme?,
    usingAiTheme: Boolean,
    onConfigChange: (top.izuna.foliamajor.theme.AiThemeConfig) -> Unit,
    onProviderChange: (top.izuna.foliamajor.theme.AiThemeProvider) -> Unit,
    onGenerate: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val toTheme = foliaTheme()
    val background = toTheme.backgroundColor.toThemeColor()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Opaque, not translucent: the sheet sits over the transport and song title, and a
            // ghosted version of them behind the form reads as a rendering bug rather than as
            // depth. The slight lift away from the theme background keeps the sheet's edge legible
            // when an AI theme picks a background close to the system surface colour.
            .background(background)
            .background(primary.copy(alpha = 0.04f)),
    ) {
        // The dismiss target is the first child, so it sits *behind* the form: a later sibling would
        // be drawn on top and swallow every tap on a text field. Compose hit-tests topmost-first, and
        // the form has no pointer input on its empty areas, so taps between fields reach this.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 48.dp, bottom = 56.dp),
        ) {
            Text(
                text = "AI theme",
                color = primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = "The model reads the lyrics and picks a palette for this song.",
                color = secondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(16.dp))

            // Provider. Two options, so a segmented row beats a dropdown: both are visible and one
            // tap selects either.
            Row(modifier = Modifier.padding(horizontal = 24.dp)) {
                ProviderChip(
                    label = "OpenAI-compatible",
                    selected = config.provider == top.izuna.foliamajor.theme.AiThemeProvider.OPENAI_COMPATIBLE,
                    accent = accent,
                    secondary = secondary,
                    onClick = { onProviderChange(top.izuna.foliamajor.theme.AiThemeProvider.OPENAI_COMPATIBLE) },
                )
                Spacer(Modifier.width(8.dp))
                ProviderChip(
                    label = "Gemini",
                    selected = config.provider == top.izuna.foliamajor.theme.AiThemeProvider.GEMINI,
                    accent = accent,
                    secondary = secondary,
                    onClick = { onProviderChange(top.izuna.foliamajor.theme.AiThemeProvider.GEMINI) },
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsField(
                label = "Endpoint",
                value = config.baseUrl,
                placeholder = "https://api.openai.com/v1/chat/completions",
                onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            )
            SettingsField(
                label = "Model",
                value = config.model,
                placeholder = "gpt-4o-mini",
                onValueChange = { onConfigChange(config.copy(model = it)) },
            )
            SettingsField(
                label = "API key",
                value = config.apiKey,
                placeholder = "sk-…",
                masked = true,
                onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onConfigChange(config.copy(autoGenerate = !config.autoGenerate)) }
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (config.autoGenerate) "☑" else "☐",
                    color = if (config.autoGenerate) accent else secondary,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Generate on song change", color = primary, fontSize = 14.sp)
                    Text(
                        text = "Only for songs with no cached theme.",
                        color = secondary,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (config.isConfigured) "Ready" else "Endpoint, model and key are required",
                color = if (config.isConfigured) accent else secondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(16.dp))

            songTheme?.let { theme ->
                Text(
                    text = theme.dark.name,
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                theme.dark.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        color = secondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = "by ${theme.dark.provider ?: "AI"} · ${theme.dark.wordColors.size} keywords",
                    color = secondary.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                )
                Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    theme.dark.wordColors.take(8).forEach { entry ->
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp, bottom = 6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(entry.color.toThemeColor(accent).copy(alpha = 0.22f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(entry.word, color = primary, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = "Generate for this song",
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(enabled = config.isConfigured, onClick = onGenerate)
                        .padding(vertical = 10.dp),
                )
                if (usingAiTheme) {
                    Spacer(Modifier.width(24.dp))
                    Text(
                        text = "Reset to default",
                        color = secondary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(onClick = onClear)
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }

        Text(
            text = "Tap outside to close",
            color = accent.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Above the gesture bar: at 16dp the label sat on top of the navigation pill.
                .padding(bottom = 48.dp),
        )
    }
}

@Composable
private fun ProviderChip(
    label: String,
    selected: Boolean,
    accent: Color,
    secondary: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) accent else secondary,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (selected) 0.18f else 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    masked: Boolean = false,
) {
    val toTheme = foliaTheme()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(label, color = secondary, fontSize = 11.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, fontSize = 13.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = primary),
            visualTransformation = if (masked) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        )
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    title: String,
    artist: String,
    detail: String?,
    isCurrent: Boolean,
    coverUri: android.net.Uri?,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val toTheme = foliaTheme()
    val primary = toTheme.primaryColor.toThemeColor()
    val secondary = toTheme.secondaryColor.toThemeColor()
    val accent = toTheme.accentColor.toThemeColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed-size slot whether or not a cover exists, so rows with and without art align.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(secondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri != null) {
                coil.compose.AsyncImage(
                    model = coverUri,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = secondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isCurrent) accent else primary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                color = secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = secondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Same lookup the ViewModel exposes, duplicated here so the composable can remember its result. */
private fun activeLineIndex(lines: List<top.izuna.foliamajor.lyrics.Line>, positionSec: Double): Int {
    for (index in lines.indices.reversed()) {
        val line = lines[index]
        val renderEnd = line.renderHints?.renderEndTime ?: line.endTime
        if (positionSec >= line.startTime && positionSec <= renderEnd) {
            return index
        }
    }
    return -1
}

private fun formatPosition(positionSec: Double, durationSec: Double): String {
    fun clock(total: Double): String {
        val safe = total.coerceAtLeast(0.0).toInt()
        return "%d:%02d".format(safe / 60, safe % 60)
    }
    return "${clock(positionSec)} / ${clock(durationSec)}"
}
