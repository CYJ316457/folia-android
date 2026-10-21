package top.izuna.foliamajor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import top.izuna.foliamajor.playback.PlaybackController
import top.izuna.foliamajor.ui.FoliaScreen
import top.izuna.foliamajor.ui.PlayerViewModel
import top.izuna.foliamajor.ui.theme.FoliaTheme

/**
 * The single activity.
 *
 * Portrait and full-screen: this is a lyric player, and the desktop app's whole presentation is a
 * full-bleed lyric field. The system bars are left edge-to-edge rather than hidden so the status bar
 * clock stays visible, which is what the desktop app's "overlay" chrome mode does.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app surface is near-black, so both system bar styles must be forced to light icons.
        // Left to the default, the status bar draws dark icons on the dark background and the clock
        // and battery disappear entirely.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            // The view model owns the active theme, so it is created before the theme wrapper: a
            // generated theme has to repaint the Material scheme and the lyric canvas together.
            val context = LocalContext.current
            val playback = remember { PlaybackController(context) }
            val viewModel: PlayerViewModel = viewModel(
                factory = PlayerViewModel.factory(context, playback),
            )
            val uiState by viewModel.state.collectAsStateWithLifecycle()

            FoliaTheme(theme = uiState.activeTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FoliaApp(playback = playback, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun FoliaApp(
    playback: PlaybackController,
    viewModel: PlayerViewModel,
) {
    val playerState by playback.state.collectAsStateWithLifecycle()

    // The session lives in the playback service; the controller resolves asynchronously, and until
    // it does the UI renders its idle state rather than blocking on the connection.
    LaunchedEffect(Unit) { playback.connect() }
    DisposableEffect(Unit) {
        onDispose { playback.release() }
    }

    val uiState by viewModel.state.collectAsStateWithLifecycle()

    FoliaScreen(
        state = uiState,
        playback = playerState,
        onQueryChange = viewModel::onQueryChange,
        onSearch = viewModel::search,
        onSelectOnline = viewModel::selectOnline,
        onSelectLocal = viewModel::playLocal,
        onImportFolder = viewModel::importFolder,
        onTabChange = viewModel::setTab,
        onLocalBrowseChange = viewModel::setLocalBrowse,
        onMergeRequest = { viewModel.requestMerge(it) },
        onMerge = viewModel::mergeEntities,
        onOpenEntity = viewModel::openEntity,
        onCancelMerge = viewModel::cancelMerge,
        onCloseEntity = viewModel::closeEntity,
        onUnmergeEntity = viewModel::unmergeEntity,
        onReplayGainModeChange = viewModel::setReplayGainMode,
        onCycleLyricMode = viewModel::cycleLyricMode,
        onCreatePlaylist = viewModel::createPlaylist,
        onOpenPlaylist = viewModel::openPlaylist,
        onDeletePlaylist = viewModel::deletePlaylist,
        onClosePlaylist = viewModel::closePlaylist,
        onAddToPlaylist = viewModel::addToPlaylist,
        entitySongs = uiState.entitySongs,
        coverUriFor = viewModel::coverUriFor,
        onTogglePlayPause = playback::togglePlayPause,
        onSeekToLine = playback::seekToSeconds,
        onGenerateTheme = { viewModel.generateTheme() },
        onClearTheme = viewModel::clearCurrentTheme,
        onAiConfigChange = viewModel::updateAiConfig,
        onAiProviderChange = viewModel::setAiProvider,
        onOpenLogin = viewModel::openLogin,
        onCloseLogin = viewModel::closeLogin,
        onRefreshQrCode = viewModel::startQrLogin,
        onSignOutNetease = viewModel::signOutNetease,
        onNeteaseApiBaseChange = viewModel::setNeteaseApiBase,
    )
}
