package com.videohatak.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.data.repository.LocalVideoRepositoryImpl
import com.videohatak.player.core.designsystem.component.VideoHatakTopBar
import com.videohatak.player.core.designsystem.theme.LocalVideoHatakPalette
import com.videohatak.player.core.designsystem.theme.LocalWindowSizeClass
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepositoryImpl
import com.videohatak.player.core.designsystem.theme.ThemeState
import com.videohatak.player.core.designsystem.theme.ThemeViewModel
import com.videohatak.player.core.designsystem.theme.VideoHatakTheme
import com.videohatak.player.core.designsystem.theme.resolveIsDarkTheme
import com.videohatak.player.core.designsystem.theme.themeDataStore
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.permission.PermissionHelper
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.VolumeComboShortcutDetector
import com.videohatak.player.core.playback.pip.PipHelper
import com.videohatak.player.core.playback.playbackDataStore
import com.videohatak.player.core.playback.service.MediaNotificationPrivacy
import com.videohatak.player.core.playback.service.VideoPlaybackService
import com.videohatak.player.core.playlist.model.toVideoItem
import com.videohatak.player.core.playlist.repository.PlaylistRepositoryImpl
import com.videohatak.player.core.trash.repository.TrashRepositoryImpl
import com.videohatak.player.core.vault.model.VaultHiddenMatcher
import com.videohatak.player.core.vault.model.toVideoItem
import com.videohatak.player.core.vault.repository.VaultImportOutcome
import com.videohatak.player.core.vault.repository.VaultRepositoryImpl
import com.videohatak.player.feature.library.DataStoreLibraryScrollStore
import com.videohatak.player.feature.library.LibraryScreen
import com.videohatak.player.feature.library.LibraryTab
import com.videohatak.player.feature.library.LibraryUiEvent
import com.videohatak.player.feature.library.LibraryViewModel
import com.videohatak.player.feature.player.PlayerScreen
import com.videohatak.player.feature.player.PlayerViewModel
import com.videohatak.player.feature.player.components.MiniPlayerBar
import com.videohatak.player.feature.playlist.PlaylistUiEvent
import com.videohatak.player.feature.playlist.PlaylistViewModel
import com.videohatak.player.feature.vault.VaultUiEvent
import com.videohatak.player.feature.vault.VaultViewModel
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels {
        ThemeViewModel.provideFactory(
            ThemePreferencesRepositoryImpl(applicationContext.themeDataStore)
        )
    }

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.provideFactory(
            videoRepository = LocalVideoRepositoryImpl(applicationContext),
            trashRepository = TrashRepositoryImpl(applicationContext),
            scrollStore = DataStoreLibraryScrollStore(applicationContext),
            vaultHiddenEntries = VaultRepositoryImpl.getInstance(applicationContext).hiddenOriginalEntries
        )
    }

    private val playerViewModel: PlayerViewModel by viewModels {
        PlayerViewModel.provideFactory(
            application,
            PlaybackPreferencesRepositoryImpl(applicationContext.playbackDataStore)
        )
    }

    private val playlistViewModel: PlaylistViewModel by viewModels {
        val hiddenFlow = VaultRepositoryImpl.getInstance(applicationContext).hiddenOriginalEntries
        PlaylistViewModel.provideFactory(
            repository = PlaylistRepositoryImpl(
                context = applicationContext,
                hiddenOriginalsProvider = { hiddenFlow.value }
            ),
            vaultHiddenEntries = hiddenFlow
        )
    }

    private val vaultViewModel: VaultViewModel by viewModels {
        VaultViewModel.provideFactory(
            VaultRepositoryImpl.getInstance(applicationContext),
            applicationContext
        )
    }

    private val backupManager by lazy { BackupManager(applicationContext) }

    private val isInPipModeState = mutableStateOf(false)
    private val canUseBiometricsState = mutableStateOf(true)
    private var activeVideoState: VideoItem? = null

    private val volumeComboDetector = VolumeComboShortcutDetector {
        val exo = playerViewModel.playerManager.exoPlayer
        if (exo.mediaItemCount > 0) {
            playerViewModel.nextTrack()
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        requestedOrientation = MAIN_APP_ORIENTATION
        canUseBiometricsState.value = vaultViewModel.canUseBiometrics()

        val startTimestamp = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val isLoading = themeViewModel.themeState.value is ThemeState.Loading
            val isTimedOut = (System.currentTimeMillis() - startTimestamp) > 400
            isLoading && !isTimedOut
        }

        enableEdgeToEdge()

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val themeState by themeViewModel.themeState.collectAsStateWithLifecycle()
            val persistedAppTheme by themeViewModel.appTheme.collectAsStateWithLifecycle()
            val selectedAppTheme by themeViewModel.effectiveAppTheme.collectAsStateWithLifecycle()
            val effectiveThemeMode by themeViewModel.effectiveThemeMode.collectAsStateWithLifecycle()
            val isThemePreviewActive by themeViewModel.isPreviewActive.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val uiScope = rememberCoroutineScope()

            val currentThemeMode = when (themeState) {
                is ThemeState.Success -> effectiveThemeMode
                is ThemeState.Loading -> effectiveThemeMode
            }

            val isDark = resolveIsDarkTheme(currentThemeMode, isSystemInDarkTheme())

            DisposableEffect(isDark) {
                val insetsController = WindowInsetsControllerCompat(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
                onDispose {}
            }

            // Dedicated Media Permission Launcher (safe against cancel / empty result / OEM quirks)
            val mediaPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { _ ->
                val status = PermissionHelper.checkPermissionStatus(this@MainActivity, this@MainActivity)
                libraryViewModel.onPermissionStatusChanged(status)
            }

            // Optional Notification Permission Launcher for background media controls (Android 13+)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ -> }

            // Scoped Storage MediaStore write consent launcher for Rename & Move
            val writePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                libraryViewModel.onWritePermissionGranted(result.resultCode == RESULT_OK)
            }

            // Legacy API 24-28 WRITE_EXTERNAL_STORAGE permission launcher for Delete/Rename/Move
            val legacyFileWritePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                libraryViewModel.onWritePermissionGranted(granted)
            }

            var pendingVaultRestoreIds by remember { mutableStateOf<List<Long>>(emptyList()) }
            val vaultLegacyRestorePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                val ids = pendingVaultRestoreIds
                pendingVaultRestoreIds = emptyList()
                vaultViewModel.onLegacyRestoreWritePermissionResult(granted, ids)
            }

            var pendingOriginalDeleteVaultIds by remember { mutableStateOf<List<Long>>(emptyList()) }
            val vaultOriginalDeleteConsentLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                val ids = pendingOriginalDeleteVaultIds
                pendingOriginalDeleteVaultIds = emptyList()
                vaultViewModel.onOriginalDeleteConsentResult(
                    granted = result.resultCode == RESULT_OK,
                    vaultIds = ids
                )
            }

            val vaultLegacyOriginalDeletePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                val ids = pendingOriginalDeleteVaultIds
                pendingOriginalDeleteVaultIds = emptyList()
                vaultViewModel.onLegacyOriginalDeletePermissionResult(
                    granted = granted,
                    vaultIds = ids
                )
            }

            LaunchedEffect(Unit) {
                val initialStatus = PermissionHelper.checkPermissionStatus(this@MainActivity, this@MainActivity)
                libraryViewModel.onPermissionStatusChanged(initialStatus)
            }

            LaunchedEffect(Unit) {
                libraryViewModel.events.collect { event ->
                    when (event) {
                        is LibraryUiEvent.ShowMessage -> {
                            snackbarHostState.showSnackbar(event.message)
                        }
                        is LibraryUiEvent.LaunchWritePermission -> {
                            runCatching {
                                writePermissionLauncher.launch(
                                    IntentSenderRequest.Builder(event.intentSender).build()
                                )
                            }.onFailure {
                                uiScope.launch {
                                    snackbarHostState.showSnackbar("تعذر فتح نافذة إذن تعديل الملف")
                                }
                            }
                        }
                        is LibraryUiEvent.RequestLegacyWritePermission -> {
                            runCatching {
                                legacyFileWritePermissionLauncher.launch(event.permission)
                            }.onFailure {
                                libraryViewModel.onWritePermissionGranted(false)
                            }
                        }
                    }
                }
            }

            var activeVideoForPlayback by remember { mutableStateOf<VideoItem?>(null) }
            var activeContextVideos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
            activeVideoState = activeVideoForPlayback
            val currentActiveVideo by rememberUpdatedState(activeVideoForPlayback)

            LaunchedEffect(Unit) {
                playlistViewModel.events.collect { event ->
                    when (event) {
                        is PlaylistUiEvent.ShowMessage -> {
                            if (currentActiveVideo == null) {
                                snackbarHostState.showSnackbar(event.message)
                            }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                vaultViewModel.events.collect { event ->
                    when (event) {
                        is VaultUiEvent.ShowMessage -> {
                            if (currentActiveVideo == null) {
                                snackbarHostState.showSnackbar(event.message)
                            }
                        }
                        is VaultUiEvent.LibraryMediaUpdated -> {
                            libraryViewModel.refresh()
                            playlistViewModel.refresh()
                        }
                        is VaultUiEvent.RequestLegacyWritePermission -> {
                            pendingVaultRestoreIds = event.pendingRestoreIds
                            runCatching {
                                vaultLegacyRestorePermissionLauncher.launch(event.permission)
                            }.onFailure {
                                val ids = pendingVaultRestoreIds
                                pendingVaultRestoreIds = emptyList()
                                vaultViewModel.onLegacyRestoreWritePermissionResult(false, ids)
                            }
                        }
                        is VaultUiEvent.RequestOriginalDeleteConsent -> {
                            pendingOriginalDeleteVaultIds = event.vaultIds
                            runCatching {
                                vaultOriginalDeleteConsentLauncher.launch(
                                    IntentSenderRequest.Builder(event.intentSender).build()
                                )
                            }.onFailure {
                                val ids = pendingOriginalDeleteVaultIds
                                pendingOriginalDeleteVaultIds = emptyList()
                                vaultViewModel.onOriginalDeleteConsentResult(granted = false, vaultIds = ids)
                            }
                        }
                        is VaultUiEvent.RequestLegacyOriginalDeletePermission -> {
                            pendingOriginalDeleteVaultIds = event.vaultIds
                            runCatching {
                                vaultLegacyOriginalDeletePermissionLauncher.launch(event.permission)
                            }.onFailure {
                                val ids = pendingOriginalDeleteVaultIds
                                pendingOriginalDeleteVaultIds = emptyList()
                                vaultViewModel.onLegacyOriginalDeletePermissionResult(granted = false, vaultIds = ids)
                            }
                        }
                    }
                }
            }

            val isInPip by isInPipModeState

            val uiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
            val searchQuery by libraryViewModel.searchQuery.collectAsStateWithLifecycle()
            val sortOrder by libraryViewModel.sortOrder.collectAsStateWithLifecycle()
            val currentTab by libraryViewModel.currentTab.collectAsStateWithLifecycle()
            val selectedFolder by libraryViewModel.selectedFolder.collectAsStateWithLifecycle()
            val videosScrollPosition by libraryViewModel.videosScrollPosition.collectAsStateWithLifecycle()
            val isVideosScrollLoaded by libraryViewModel.isVideosScrollLoaded.collectAsStateWithLifecycle()
            val foldersScrollPosition by libraryViewModel.foldersScrollPosition.collectAsStateWithLifecycle()
            val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
            val currentPlayerVideo by playerViewModel.currentVideoFlow.collectAsStateWithLifecycle()
            val playbackMode by playerViewModel.playbackMode.collectAsStateWithLifecycle()
            val resumeMode by playerViewModel.resumeMode.collectAsStateWithLifecycle()
            val seekIntervalSeconds by playerViewModel.seekIntervalSeconds.collectAsStateWithLifecycle()

            LaunchedEffect(
                isInPip,
                playerUiState.isPlaying,
                playerUiState.seekIntervalSeconds,
                playerUiState.videoWidth,
                playerUiState.videoHeight,
                currentPlayerVideo?.width,
                currentPlayerVideo?.height,
                activeVideoForPlayback?.width,
                activeVideoForPlayback?.height
            ) {
                if (isInPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val w = playerUiState.videoWidth.takeIf { it > 0 }
                        ?: currentPlayerVideo?.width?.takeIf { it > 0 }
                        ?: activeVideoForPlayback?.width?.takeIf { it > 0 }
                        ?: 16
                    val h = playerUiState.videoHeight.takeIf { it > 0 }
                        ?: currentPlayerVideo?.height?.takeIf { it > 0 }
                        ?: activeVideoForPlayback?.height?.takeIf { it > 0 }
                        ?: 9
                    PipHelper(this@MainActivity).updatePipActions(
                        activity = this@MainActivity,
                        isPlaying = playerUiState.isPlaying,
                        seekIntervalSeconds = playerUiState.seekIntervalSeconds,
                        videoWidth = w,
                        videoHeight = h
                    )
                }
            }

            val playlists by playlistViewModel.playlists.collectAsStateWithLifecycle()
            val selectedPlaylistWithItems by playlistViewModel.selectedPlaylistWithItems.collectAsStateWithLifecycle()
            val favoriteVideoIds by playlistViewModel.favoriteVideoIds.collectAsStateWithLifecycle()
            val favoriteVideoUris by playlistViewModel.favoriteVideoUris.collectAsStateWithLifecycle()
            val favoritesPlaylistId by playlistViewModel.favoritesPlaylistId.collectAsStateWithLifecycle()
            val favoriteItems by playlistViewModel.favoriteItems.collectAsStateWithLifecycle()

            val vaultIsUnlocked by vaultViewModel.isUnlocked.collectAsStateWithLifecycle()
            val vaultVideos by vaultViewModel.vaultVideos.collectAsStateWithLifecycle()
            val vaultCount by vaultViewModel.vaultCount.collectAsStateWithLifecycle()
            val vaultImportState by vaultViewModel.importState.collectAsStateWithLifecycle()
            val vaultRestoreState by vaultViewModel.restoreState.collectAsStateWithLifecycle()
            val vaultBiometricError by vaultViewModel.biometricError.collectAsStateWithLifecycle()
            val vaultError by vaultViewModel.vaultError.collectAsStateWithLifecycle()
            val vaultHasPinConfigured by vaultViewModel.hasPinConfigured.collectAsStateWithLifecycle()
            val vaultAutoLockTimeout by vaultViewModel.autoLockTimeout.collectAsStateWithLifecycle()
            val vaultCanUseBiometrics by canUseBiometricsState
            val trashItems by libraryViewModel.trashItems.collectAsStateWithLifecycle()
            val trashError by libraryViewModel.trashError.collectAsStateWithLifecycle()
            val trashExistenceMap by libraryViewModel.trashExistenceMap.collectAsStateWithLifecycle()
            val missingTrashItemPendingRemoval by libraryViewModel.missingTrashItemPendingRemoval.collectAsStateWithLifecycle()

            val hiddenOriginalEntries by vaultViewModel.hiddenOriginalEntries.collectAsStateWithLifecycle()

            val favoriteVideos = remember(favoriteItems, uiState.allVideos, trashItems, hiddenOriginalEntries) {
                val trashedUriSet = trashItems.mapTo(HashSet()) { it.contentUri }
                favoriteItems.filter { item ->
                    item.isAvailable &&
                        item.contentUri !in trashedUriSet &&
                        !VaultHiddenMatcher.isPlaylistItemHidden(item, hiddenOriginalEntries)
                }.map { item ->
                    uiState.allVideos.firstOrNull {
                        it.id == item.videoId || it.contentUri.toString() == item.contentUri
                    } ?: item.toVideoItem()
                }
            }

            val exportBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/json")
            ) { uri: Uri? ->
                if (uri != null) {
                    uiScope.launch {
                        backupManager.exportToUri(uri)
                            .onSuccess { summary ->
                                snackbarHostState.showSnackbar(
                                    "تم تصدير النسخة الاحتياطية (${summary.playlistCount} قوائم، ${summary.playlistItemCount} مقطع)"
                                )
                            }
                            .onFailure { err ->
                                snackbarHostState.showSnackbar(err.localizedMessage ?: "فشل تصدير النسخة الاحتياطية")
                            }
                    }
                }
            }

            val importBackupLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    uiScope.launch {
                        backupManager.importFromUri(uri)
                            .onSuccess { summary ->
                                playlistViewModel.refresh()
                                vaultViewModel.refresh()
                                snackbarHostState.showSnackbar(
                                    "تمت الاستعادة بنجاح (${summary.playlistCount} قوائم، ${summary.playlistItemCount} مقطع)"
                                )
                            }
                            .onFailure { err ->
                                snackbarHostState.showSnackbar(err.localizedMessage ?: "فشل استيراد النسخة الاحتياطية")
                            }
                    }
                }
            }

            val vaultImportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    uris.forEach { uri ->
                        val grantedRw = runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        }.isSuccess
                        if (!grantedRw) {
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                        }
                    }
                    vaultViewModel.importVideosToPrivateVault(uris) {
                        vaultViewModel.refresh()
                        libraryViewModel.refresh()
                        playlistViewModel.refresh()
                    }
                }
            }

            fun isVaultPlaybackOngoing(): Boolean {
                val active = activeVideoForPlayback ?: activeVideoState
                val current = playerViewModel.currentVideo
                val uri = playerUiState.mediaUri
                return (active != null && MediaNotificationPrivacy.isVaultVideo(active)) ||
                    (current != null && MediaNotificationPrivacy.isVaultVideo(current)) ||
                    MediaNotificationPrivacy.isVaultUri(uri)
            }

            fun stopVaultPlaybackImmediatelyIfNeeded() {
                if (isVaultPlaybackOngoing()) {
                    activeVideoForPlayback = null
                    activeVideoState = null
                    playerViewModel.stopPlaybackCompletely()
                    VideoPlaybackService.stopAndCancelNotification(this@MainActivity)
                }
            }

            fun moveVideosToVault(videos: List<VideoItem>) {
                val candidates = videos.filterNot { MediaNotificationPrivacy.isVaultVideo(it) }
                if (candidates.isEmpty()) return
                val activeUri = playerViewModel.uiState.value.mediaUri
                    ?: activeVideoForPlayback?.contentUri
                    ?: playerViewModel.currentVideo?.contentUri
                val activeId = activeVideoForPlayback?.id ?: playerViewModel.currentVideo?.id
                val movingActiveVideo = candidates.any { target ->
                    target.contentUri == activeUri ||
                        (activeId != null && activeId > 0L && target.id == activeId)
                }
                if (movingActiveVideo) {
                    activeVideoForPlayback = null
                    activeVideoState = null
                    playerViewModel.stopPlaybackCompletely()
                    VideoPlaybackService.stopAndCancelNotification(this@MainActivity)
                }
                val repo = VaultRepositoryImpl.getInstance(applicationContext)
                uiScope.launch {
                    val progressSnackbarJob = uiScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            if (candidates.size == 1) {
                                "جاري تشفير ونقل \"${candidates.first().safeTitle}\" إلى الخزنة الخاصة..."
                            } else {
                                "جاري تشفير ونقل ${candidates.size} فيديو إلى الخزنة الخاصة..."
                            }
                        )
                    }
                    val verifiedVaultIdsForOriginalDelete = mutableListOf<Long>()
                    var alreadyProtectedCount = 0
                    var failedCount = 0
                    var lastFailureReason: String? = null
                    try {
                        for (target in candidates) {
                            val currentActiveUri = playerViewModel.uiState.value.mediaUri
                            if (currentActiveUri == target.contentUri || activeVideoForPlayback?.contentUri == target.contentUri) {
                                activeVideoForPlayback = null
                                activeVideoState = null
                                playerViewModel.stopPlaybackCompletely()
                                VideoPlaybackService.stopAndCancelNotification(this@MainActivity)
                            }
                            when (
                                val outcome = repo.importMediaVideoDetailed(
                                    sourceUri = target.contentUri,
                                    displayName = target.safeTitle,
                                    originalFileName = target.displayName.takeIf { it.isNotBlank() } ?: target.safeTitle,
                                    originalRelativePath = target.relativePath.takeIf { it.isNotBlank() },
                                    originalSizeBytes = target.sizeBytes.takeIf { it > 0L },
                                    originalDateModifiedSec = target.dateModifiedSec.takeIf { it > 0L },
                                    originalMediaStoreId = target.id.takeIf { it > 0L },
                                    originalFilePath = target.path.takeIf { it.isNotBlank() }
                                )
                            ) {
                                is VaultImportOutcome.Imported -> {
                                    verifiedVaultIdsForOriginalDelete.add(outcome.video.id)
                                }
                                is VaultImportOutcome.AlreadyInVault -> {
                                    if (outcome.integrityVerified) {
                                        if (outcome.existingVideo.isOriginalDeletedProtected) {
                                            alreadyProtectedCount++
                                        } else {
                                            verifiedVaultIdsForOriginalDelete.add(outcome.existingVideo.id)
                                        }
                                    } else {
                                        failedCount++
                                        lastFailureReason = "تعذر التحقق من سلامة النسخة الموجودة في الخزنة — لم يتم المساس بالملف الأصلي"
                                    }
                                }
                                is VaultImportOutcome.Failed -> {
                                    failedCount++
                                    lastFailureReason = outcome.reason
                                }
                                VaultImportOutcome.Cancelled -> Unit
                            }
                        }
                    } finally {
                        progressSnackbarJob.cancel()
                        snackbarHostState.currentSnackbarData?.dismiss()
                    }
                    vaultViewModel.refresh()
                    libraryViewModel.refresh()
                    playlistViewModel.refresh()
                    if (verifiedVaultIdsForOriginalDelete.isNotEmpty()) {
                        vaultViewModel.deleteOriginalsForVaultEntries(
                            vaultIds = verifiedVaultIdsForOriginalDelete,
                            encryptedFailedCount = failedCount
                        )
                    } else if (alreadyProtectedCount > 0 && failedCount == 0) {
                        snackbarHostState.showSnackbar("الفيديو محمي ومشفّر مسبقًا داخل الخزنة الخاصة")
                    } else if (failedCount > 0) {
                        snackbarHostState.showSnackbar(
                            lastFailureReason ?: "تعذر تشفير ونقل الفيديو إلى الخزنة — بقيت الملفات الأصلية ظاهرة وسليمة"
                        )
                    }
                }
            }

            val isVaultActive = currentTab == LibraryTab.VAULT ||
                (activeVideoForPlayback?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true) ||
                (currentPlayerVideo?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true) ||
                MediaNotificationPrivacy.isVaultUri(playerUiState.mediaUri)
            DisposableEffect(isVaultActive) {
                if (isVaultActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LaunchedEffect(vaultIsUnlocked) {
                if (!vaultIsUnlocked) {
                    stopVaultPlaybackImmediatelyIfNeeded()
                }
            }

            var previousTab by remember { mutableStateOf(currentTab) }
            LaunchedEffect(currentTab) {
                if (previousTab == LibraryTab.VAULT && currentTab != LibraryTab.VAULT) {
                    stopVaultPlaybackImmediatelyIfNeeded()
                    vaultViewModel.onVaultTabExited()
                }
                if (currentTab == LibraryTab.TRASH) {
                    libraryViewModel.refreshTrashStatus()
                }
                previousTab = currentTab
                if (currentTab != LibraryTab.SETTINGS) {
                    themeViewModel.cancelPreview()
                }
            }

            DisposableEffect(Unit) {
                val repo = VaultRepositoryImpl.getInstance(applicationContext)
                val stopIfDeletingActiveVaultVideo: suspend (Long) -> Unit = { deletingId ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        val livePlayerUri = playerViewModel.uiState.value.mediaUri
                        val liveActiveVideo = activeVideoState
                        val liveCurrentPlayerVideo = playerViewModel.currentVideo
                        val activeUri = livePlayerUri
                            ?: liveActiveVideo?.contentUri
                            ?: liveCurrentPlayerVideo?.contentUri
                        val activeMatchesId = activeUri != null &&
                            com.videohatak.player.core.playback.service.MediaNotificationPrivacy.isVaultUri(activeUri) &&
                            (activeUri.host == deletingId.toString() || activeUri.lastPathSegment == deletingId.toString())
                        val directIdMatch = (liveActiveVideo?.bucketId == "secure_vault" && liveActiveVideo.id == deletingId) ||
                            (liveCurrentPlayerVideo?.bucketId == "secure_vault" && liveCurrentPlayerVideo.id == deletingId)
                        if (activeMatchesId || directIdMatch) {
                            activeVideoForPlayback = null
                            playerViewModel.stopPlaybackCompletely()
                        }
                    }
                }
                repo.beforeDeleteHook = stopIfDeletingActiveVaultVideo
                vaultViewModel.setBeforeDeleteVideoHandler(stopIfDeletingActiveVaultVideo)
                onDispose {
                    repo.beforeDeleteHook = null
                    vaultViewModel.setBeforeDeleteVideoHandler(null)
                }
            }

            LaunchedEffect(currentPlayerVideo) {
                if (activeVideoForPlayback != null && currentPlayerVideo != null) {
                    activeVideoForPlayback = currentPlayerVideo
                }
            }

            LaunchedEffect(playerUiState.mediaUri, activeVideoForPlayback) {
                if (activeVideoForPlayback == null && playerViewModel.currentVideo == null && playerUiState.mediaUri != null) {
                    playerViewModel.syncVideoFromPlayerManager()
                }
            }

            LaunchedEffect(uiState.allVideos) {
                val current = activeVideoForPlayback ?: playerViewModel.currentVideo
                if (current != null && current.bucketId != "secure_vault") {
                    val updated = uiState.allVideos.firstOrNull { it.id == current.id }
                    if (updated != null && (updated.title != current.title || updated.displayName != current.displayName)) {
                        if (activeVideoForPlayback?.id == updated.id) {
                            activeVideoForPlayback = updated
                        }
                        playerViewModel.updateActiveVideo(updated)
                    }
                }
            }

            LaunchedEffect(vaultVideos) {
                val current = activeVideoForPlayback ?: playerViewModel.currentVideo
                if (current != null && current.bucketId == "secure_vault") {
                    val updatedVault = vaultVideos.firstOrNull { it.id == current.id }?.toVideoItem()
                    if (updatedVault != null && updatedVault.safeTitle != current.safeTitle) {
                        if (activeVideoForPlayback?.id == updatedVault.id) {
                            activeVideoForPlayback = updatedVault
                        }
                        playerViewModel.updateActiveVideo(updatedVault)
                    }
                }
            }

            fun requestNotificationIfNeeded() {
                val notifPerm = PermissionHelper.getNotificationPermission()
                if (notifPerm != null && !PermissionHelper.hasNotificationPermission(this@MainActivity)) {
                    runCatching { notificationPermissionLauncher.launch(notifPerm) }
                }
            }

            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                VideoHatakTheme(
                    themeMode = currentThemeMode,
                    appTheme = selectedAppTheme,
                    darkTheme = isDark
                ) {
                    val palette = LocalVideoHatakPalette.current
                    val activeVideo = activeVideoForPlayback
                    val isInPlayerScreen = activeVideo != null

                    LaunchedEffect(isInPlayerScreen) {
                        if (!isInPlayerScreen) {
                            requestedOrientation = MAIN_APP_ORIENTATION
                        }
                    }

                    if (activeVideo != null) {
                        val isActiveFavorite = (activeVideo.id in favoriteVideoIds) ||
                            (activeVideo.contentUri.toString() in favoriteVideoUris)
                        PlayerScreen(
                            video = activeVideo,
                            contextVideos = activeContextVideos,
                            viewModel = playerViewModel,
                            isInPipMode = isInPip,
                            playlists = playlists,
                            isFavorite = isActiveFavorite,
                            onToggleFavorite = { targetVideo ->
                                playlistViewModel.toggleFavorite(targetVideo)
                            },
                            onToggleFavoriteWithResult = playlistViewModel::toggleFavoriteWithFeedback,
                            onAddToPlaylists = playlistViewModel::addVideoToPlaylists,
                            onAddToPlaylistsWithResult = playlistViewModel::addVideoToPlaylistsWithResult,
                            onCreatePlaylistAndAdd = { name, targetVideo ->
                                playlistViewModel.createPlaylistAndAddVideo(name, targetVideo)
                            },
                            onCreatePlaylistAndAddWithResult = playlistViewModel::createPlaylistAndAddVideoWithResult,
                            onAddToVault = { targetVideo ->
                                moveVideosToVault(listOf(targetVideo))
                            },
                            onRenameVideo = { targetVideo, newName ->
                                if (MediaNotificationPrivacy.isVaultVideo(targetVideo)) {
                                    val matchingVault = vaultVideos.firstOrNull { it.id == targetVideo.id }
                                    val targetVaultId = matchingVault?.id ?: targetVideo.id
                                    vaultViewModel.renameVideo(targetVaultId, newName)
                                } else {
                                    libraryViewModel.renameVideo(targetVideo, newName)
                                }
                            },
                            onMoveToTrash = libraryViewModel::moveToTrash,
                            onPlaylistMutated = {
                                playlistViewModel.refresh()
                            },
                            onUserInteraction = {
                                if (activeVideo.bucketId == "secure_vault" || activeVideo.contentUri.scheme == "vault") {
                                    vaultViewModel.recordUserActivity()
                                }
                            },
                            onBackClick = {
                                val exitingVault = MediaNotificationPrivacy.isVaultVideo(activeVideo) ||
                                    MediaNotificationPrivacy.isVaultUri(playerViewModel.uiState.value.mediaUri)
                                if (exitingVault) {
                                    playerViewModel.stopPlaybackCompletely()
                                    VideoPlaybackService.stopAndCancelNotification(this@MainActivity)
                                }
                                activeVideoForPlayback = null
                            }
                        )
                    } else {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            containerColor = palette.background,
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            topBar = {
                                VideoHatakTopBar(
                                    title = stringResource(id = R.string.app_name),
                                    actions = {
                                        IconButton(
                                            onClick = {
                                                libraryViewModel.clearFolderSelection()
                                                libraryViewModel.onTabSelected(
                                                    if (currentTab == LibraryTab.TRASH) LibraryTab.VIDEOS else LibraryTab.TRASH
                                                )
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "سلة المهملات",
                                                tint = if (currentTab == LibraryTab.TRASH) palette.accent else palette.onSurfaceMuted
                                            )
                                        }
                                        // Discreet single lock action in top bar
                                        IconButton(
                                            onClick = {
                                                libraryViewModel.clearFolderSelection()
                                                libraryViewModel.onTabSelected(
                                                    if (currentTab == LibraryTab.VAULT) LibraryTab.VIDEOS else LibraryTab.VAULT
                                                )
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Lock,
                                                contentDescription = "الخزنة الخاصة",
                                                tint = if (currentTab == LibraryTab.VAULT) palette.accent else palette.onSurfaceMuted
                                            )
                                        }
                                    }
                                )
                            },
                            bottomBar = {
                                val backgroundVideo = currentPlayerVideo ?: playerUiState.mediaUri?.let { uri ->
                                    val isVaultBgUri = MediaNotificationPrivacy.isVaultUri(uri)
                                    VideoItem(
                                        id = uri.hashCode().toLong(),
                                        contentUri = uri,
                                        title = if (isVaultBgUri) MediaNotificationPrivacy.VAULT_MASKED_TITLE else playerUiState.title.ifBlank { "تشغيل حالي" },
                                        displayName = if (isVaultBgUri) MediaNotificationPrivacy.VAULT_MASKED_TITLE else playerUiState.title.ifBlank { "تشغيل حالي" },
                                        durationMs = playerUiState.durationMs,
                                        sizeBytes = 0L,
                                        bucketId = if (isVaultBgUri) "secure_vault" else "restored",
                                        dateAddedSec = 0L,
                                        width = playerUiState.videoWidth,
                                        height = playerUiState.videoHeight
                                    )
                                }
                                val isBgVault = backgroundVideo?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true ||
                                    MediaNotificationPrivacy.isVaultUri(playerUiState.mediaUri)
                                // Never render MiniPlayerBar for Vault videos
                                if (backgroundVideo != null &&
                                    !isBgVault &&
                                    (playerUiState.isPlaying || playerUiState.currentPositionMs > 0)
                                ) {
                                    MiniPlayerBar(
                                        video = backgroundVideo,
                                        uiState = playerUiState,
                                        onExpand = {
                                            playerViewModel.setPipActive(false)
                                            playerViewModel.setControlsVisible(true)
                                            activeVideoForPlayback = backgroundVideo
                                        },
                                        onTogglePlayPause = playerViewModel.playerManager::togglePlayPause,
                                        onPreviousTrack = { playerViewModel.previousTrack() },
                                        onNextTrack = { playerViewModel.nextTrack() },
                                        onClose = playerViewModel::stopPlaybackCompletely
                                    )
                                }
                            }
                        ) { innerPadding ->
                            LibraryScreen(
                                uiState = uiState,
                                searchQuery = searchQuery,
                                onSearchQueryChanged = libraryViewModel::onSearchQueryChanged,
                                sortOrder = sortOrder,
                                onSortOrderChanged = libraryViewModel::onSortOrderChanged,
                                currentTab = currentTab,
                                onTabSelected = libraryViewModel::onTabSelected,
                                selectedFolder = selectedFolder,
                                onFolderSelected = libraryViewModel::onFolderSelected,
                                clearFolderSelection = libraryViewModel::clearFolderSelection,
                                onRequestPermission = {
                                    PermissionHelper.markMediaPermissionRequested(this@MainActivity)
                                    val perms = PermissionHelper.getMediaPermissions()
                                    val launched = runCatching {
                                        mediaPermissionLauncher.launch(perms)
                                    }.isSuccess
                                    if (!launched) {
                                        runCatching {
                                            startActivity(
                                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.fromParts("package", packageName, null)
                                                }
                                            )
                                        }
                                    }
                                },
                                onOpenSettings = {
                                    runCatching {
                                        startActivity(
                                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", packageName, null)
                                            }
                                        )
                                    }
                                },
                                onRefresh = libraryViewModel::refresh,
                                onVideoClick = { video, contextList ->
                                    requestNotificationIfNeeded()
                                    playerViewModel.setPipActive(false)
                                    playerViewModel.setControlsVisible(true)
                                    activeContextVideos = contextList
                                    activeVideoForPlayback = video
                                },
                                onRenameVideo = libraryViewModel::renameVideo,
                                onMoveVideoToFolder = libraryViewModel::moveVideoToFolder,
                                onAddVideoToVault = { video ->
                                    moveVideosToVault(listOf(video))
                                },
                                onMoveVideoToTrash = libraryViewModel::moveToTrash,
                                onMoveVideosToFolder = libraryViewModel::moveVideosToFolder,
                                onAddVideosToVault = { videos ->
                                    moveVideosToVault(videos)
                                },
                                onTrashVideos = libraryViewModel::trashVideos,
                                trashItems = trashItems,
                                trashError = trashError,
                                trashExistenceMap = trashExistenceMap,
                                missingTrashItemPendingRemoval = missingTrashItemPendingRemoval,
                                onRefreshTrashStatus = libraryViewModel::refreshTrashStatus,
                                onDismissTrashError = libraryViewModel::dismissTrashError,
                                onRestoreFromTrash = libraryViewModel::restoreFromTrash,
                                onConfirmRemoveMissingTrashRecord = libraryViewModel::confirmRemoveMissingTrashRecord,
                                onDismissMissingTrashRemoval = libraryViewModel::dismissMissingTrashRemoval,
                                onPermanentDeleteTrashItem = libraryViewModel::deleteTrashItemPermanently,
                                onEmptyTrashBin = libraryViewModel::emptyTrashBin,
                                selectedAppTheme = selectedAppTheme,
                                persistedAppTheme = persistedAppTheme,
                                isThemePreviewActive = isThemePreviewActive,
                                themeMode = currentThemeMode,
                                playbackMode = playbackMode,
                                resumeMode = resumeMode,
                                onSelectAppTheme = themeViewModel::startPreviewTheme,
                                onApplyThemePreview = themeViewModel::applyPreview,
                                onCancelThemePreview = themeViewModel::cancelPreview,
                                onSelectThemeMode = { mode ->
                                    if (isThemePreviewActive) {
                                        themeViewModel.startPreviewThemeMode(mode)
                                    } else {
                                        themeViewModel.setThemeMode(mode)
                                    }
                                },
                                onSelectPlaybackMode = playerViewModel::setPlaybackMode,
                                onSelectResumeMode = playerViewModel::setResumeMode,
                                onExportBackup = {
                                    exportBackupLauncher.launch("videohatak_backup_${System.currentTimeMillis()}.json")
                                },
                                onImportBackup = {
                                    importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                playlists = playlists,
                                selectedPlaylistWithItems = selectedPlaylistWithItems,
                                onSelectPlaylist = playlistViewModel::selectPlaylist,
                                onCreatePlaylist = playlistViewModel::createPlaylist,
                                onRenamePlaylist = playlistViewModel::renamePlaylist,
                                onDeletePlaylist = playlistViewModel::deletePlaylist,
                                onRemovePlaylistItem = playlistViewModel::removeItemFromPlaylist,
                                onRemovePlaylistItems = { itemIds, playlistId ->
                                    playlistViewModel.removeItemsFromPlaylist(itemIds, playlistId)
                                },
                                onMovePlaylistItemUp = { index -> playlistViewModel.moveItem(index, index - 1) },
                                onMovePlaylistItemDown = { index -> playlistViewModel.moveItem(index, index + 1) },
                                onPlayPlaylist = { playlistId, startVideoId, shuffle ->
                                    requestNotificationIfNeeded()
                                    playerViewModel.setPipActive(false)
                                    playerViewModel.setControlsVisible(true)
                                    activeContextVideos = emptyList()
                                    playerViewModel.playPlaylist(
                                        playlistId = playlistId,
                                        startVideoId = startVideoId,
                                        shuffle = shuffle
                                    ) { startedVideo ->
                                        activeVideoForPlayback = startedVideo
                                    }
                                },
                                onAddToPlaylists = playlistViewModel::addVideoToPlaylists,
                                onAddVideosToPlaylists = { list, ids ->
                                    playlistViewModel.addVideosToPlaylists(list, ids)
                                },
                                favoritesPlaylistId = favoritesPlaylistId,
                                favoriteVideos = favoriteVideos,
                                favoriteVideoIds = favoriteVideoIds,
                                favoriteVideoUris = favoriteVideoUris,
                                onToggleFavorite = playlistViewModel::toggleFavorite,
                                onAddVideosToFavorites = { list ->
                                    playlistViewModel.addVideosToFavorites(list)
                                },
                                onRemoveVideosFromFavorites = { list ->
                                    playlistViewModel.removeVideosFromFavorites(list)
                                },
                                vaultIsUnlocked = vaultIsUnlocked,
                                vaultVideos = vaultVideos,
                                vaultTotalCount = vaultCount,
                                vaultImportState = vaultImportState,
                                vaultBiometricError = vaultBiometricError,
                                vaultError = vaultError,
                                vaultHasPinConfigured = vaultHasPinConfigured,
                                vaultCanUseBiometrics = vaultCanUseBiometrics,
                                vaultAutoLockTimeout = vaultAutoLockTimeout,
                                onVaultAuthenticate = {
                                    vaultViewModel.authenticateWithBiometrics(this@MainActivity)
                                },
                                onVaultUnlockWithPin = { pin ->
                                    vaultViewModel.unlockWithPin(pin)
                                },
                                onVaultSetupInitialPin = { pin, cb ->
                                    vaultViewModel.setupInitialVaultPinAndUnlock(pin, onComplete = cb)
                                },
                                onVaultChangePin = { currentPin, newPin, cb ->
                                    vaultViewModel.changeVaultPin(currentPin, newPin, cb)
                                },
                                onVaultSetPin = { newPin ->
                                    vaultViewModel.setVaultPin(newPin) { ok, msg ->
                                        if (!ok) {
                                            uiScope.launch { snackbarHostState.showSnackbar(msg) }
                                        }
                                    }
                                },
                                onVaultRemovePin = {
                                    vaultViewModel.removeVaultPin()
                                },
                                onVaultSelectAutoLockTimeout = vaultViewModel::setAutoLockTimeout,
                                onVaultLock = {
                                    stopVaultPlaybackImmediatelyIfNeeded()
                                    vaultViewModel.lockVault()
                                },
                                onVaultImportRequest = {
                                    vaultImportLauncher.launch(arrayOf("video/*"))
                                },
                                onVaultCancelImport = vaultViewModel::cancelImport,
                                onVaultDismissImportState = vaultViewModel::dismissImportState,
                                onVaultDismissError = vaultViewModel::clearError,
                                onVaultVideoClick = { vaultVideo ->
                                    // Never show notifications or PiP for Vault playback
                                    vaultViewModel.recordUserActivity()
                                    activeContextVideos = emptyList()
                                    activeVideoForPlayback = vaultVideo.toVideoItem()
                                },
                                onVaultRenameVideo = vaultViewModel::renameVideo,
                                vaultRestoreState = vaultRestoreState,
                                onVaultRestoreVideo = vaultViewModel::restoreVideoFromVault,
                                onVaultRestoreVideos = { ids ->
                                    vaultViewModel.restoreVideosFromVault(ids)
                                },
                                onVaultDeleteVideo = vaultViewModel::deleteVideo,
                                onVaultDeleteVideos = { ids ->
                                    vaultViewModel.deleteVideos(ids)
                                },
                                onVaultRetryDeleteOriginal = vaultViewModel::retryDeleteOriginal,
                                onVaultUserInteraction = vaultViewModel::recordUserActivity,
                                seekIntervalSeconds = seekIntervalSeconds,
                                onSelectSeekIntervalSeconds = playerViewModel::setSeekIntervalSeconds,
                                videosScrollPosition = videosScrollPosition,
                                isVideosScrollLoaded = isVideosScrollLoaded,
                                onSaveVideosScrollPosition = libraryViewModel::saveVideosScrollPosition,
                                foldersScrollPosition = foldersScrollPosition,
                                onSaveFoldersScrollPosition = libraryViewModel::saveFoldersScrollPosition,
                                onOpenFavoritesPlaylist = playlistViewModel::openFavoritesPlaylist,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isShortcutActive = activeVideoState != null ||
            playerViewModel.uiState.value.mediaUri != null ||
            playerViewModel.uiState.value.isPlaying
        if (volumeComboDetector.onKeyEvent(event.keyCode, event.action, event.repeatCount, isShortcutActive)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            volumeComboDetector.reset()
        }
    }

    override fun onPause() {
        volumeComboDetector.reset()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        val isVaultPlayback = (activeVideoState?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true) ||
            (playerViewModel.currentVideo?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true) ||
            MediaNotificationPrivacy.isVaultUri(playerViewModel.uiState.value.mediaUri)
        vaultViewModel.onAppBackgrounded()
        if (isVaultPlayback) {
            // Stop Vault playback immediately & never spawn any background notification/service
            activeVideoState = null
            playerViewModel.stopPlaybackCompletely()
            VideoPlaybackService.stopAndCancelNotification(this)
            return
        }
        val exo = playerViewModel.playerManager.exoPlayer
        if (exo.mediaItemCount > 0 && (exo.playWhenReady || exo.isPlaying)) {
            VideoPlaybackService.startServiceForPlayback(this)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState.value = isInPictureInPictureMode
        playerViewModel.setPipActive(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            playerViewModel.setControlsVisible(true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val video = activeVideoState
        val liveUiState = playerViewModel.uiState.value
        val isVaultPlayback = (video?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true) ||
            (playerViewModel.currentVideo?.let { MediaNotificationPrivacy.isVaultVideo(it) } == true) ||
            MediaNotificationPrivacy.isVaultUri(liveUiState.mediaUri)
        val isAudioOnly = liveUiState.isAudioOnlyMode
        if (video != null && !isVaultPlayback && !isAudioOnly && playerViewModel.playerManager.exoPlayer.isPlaying) {
            val resolvedWidth = liveUiState.videoWidth.takeIf { it > 0 }
                ?: video.width.takeIf { it > 0 }
                ?: playerViewModel.currentVideo?.width?.takeIf { it > 0 }
                ?: 16
            val resolvedHeight = liveUiState.videoHeight.takeIf { it > 0 }
                ?: video.height.takeIf { it > 0 }
                ?: playerViewModel.currentVideo?.height?.takeIf { it > 0 }
                ?: 9
            val entered = PipHelper.enterPip(
                activity = this,
                videoWidth = resolvedWidth,
                videoHeight = resolvedHeight,
                isPlaying = true,
                seekIntervalSeconds = liveUiState.seekIntervalSeconds
            )
            if (entered) {
                playerViewModel.setPipActive(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        canUseBiometricsState.value = vaultViewModel.canUseBiometrics()
        vaultViewModel.checkTimeoutOnForeground()
        val currentStatus = PermissionHelper.checkPermissionStatus(this, this)
        libraryViewModel.onPermissionStatusChanged(currentStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val actuallyInPip = isInPictureInPictureMode
            isInPipModeState.value = actuallyInPip
            if (playerViewModel.uiState.value.isPipActive != actuallyInPip) {
                playerViewModel.setPipActive(actuallyInPip)
                if (!actuallyInPip) {
                    playerViewModel.setControlsVisible(true)
                }
            }
        }
        val exo = playerViewModel.playerManager.exoPlayer
        if (activeVideoState != null || playerViewModel.currentVideo != null || exo.mediaItemCount > 0) {
            playerViewModel.syncVideoFromPlayerManager()
            playerViewModel.playerManager.syncWithCurrentPlayer()
        }
    }

    override fun onDestroy() {
        volumeComboDetector.reset()
        super.onDestroy()
    }

    companion object {
        internal const val MAIN_APP_ORIENTATION: Int = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
}
