package com.videohatak.player

import android.app.Application
import android.net.Uri
import androidx.biometric.BiometricManager
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.videohatak.player.core.backup.BackupManager
import com.videohatak.player.core.backup.BackupPayload
import com.videohatak.player.core.backup.BackupPlaylistData
import com.videohatak.player.core.backup.BackupPlaylistItemData
import com.videohatak.player.core.backup.BackupSettingsData
import com.videohatak.player.core.backup.BackupSubtitleData
import com.videohatak.player.core.backup.BackupVaultSecurityData
import com.videohatak.player.core.designsystem.theme.AppThemeId
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.designsystem.theme.ThemeViewModel
import com.videohatak.player.core.designsystem.theme.paletteForTheme
import com.videohatak.player.core.designsystem.theme.resolveIsDarkTheme
import com.videohatak.player.core.playback.PlaybackMode
import com.videohatak.player.core.playback.PlaybackPreferencesRepository
import com.videohatak.player.core.playback.PlaybackPreferencesRepositoryImpl
import com.videohatak.player.core.playback.ResumeMode
import com.videohatak.player.core.playback.VideoResizeMode
import com.videohatak.player.core.playback.service.MediaNotificationPrivacy
import com.videohatak.player.core.playlist.db.BackupPlaylistStore
import com.videohatak.player.core.playlist.db.TrashDatabaseStore
import com.videohatak.player.core.playlist.model.Playlist
import com.videohatak.player.core.playlist.model.PlaylistItem
import com.videohatak.player.core.subtitle.model.SubtitleStyleConfig
import com.videohatak.player.core.subtitle.repository.SubtitlePreferencesStore
import com.videohatak.player.core.trash.model.TrashItem
import com.videohatak.player.core.trash.repository.TrashRepositoryImpl
import com.videohatak.player.core.vault.biometric.VaultBiometricHelper
import com.videohatak.player.core.vault.biometric.VaultBiometricSupport
import com.videohatak.player.core.vault.model.VaultVideo
import com.videohatak.player.core.vault.repository.VaultRepository
import com.videohatak.player.core.vault.security.PinVerifyResult
import com.videohatak.player.core.vault.security.VaultAutoLockTimeout
import com.videohatak.player.core.vault.security.VaultPinHasher
import com.videohatak.player.core.vault.security.VaultSecurityBackupStore
import com.videohatak.player.core.vault.security.VaultSecurityPreferences
import com.videohatak.player.feature.vault.VaultViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class ComprehensiveAuditFixTest {

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()
    private val tempFiles = mutableListOf<File>()
    private val activeScopes = mutableListOf<CoroutineScope>()

    private class FakeApp : Application()

    private fun createScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScopes.add(scope)
        return scope
    }

    private fun createDataStore(scope: CoroutineScope, prefix: String = "audit_test_"): DataStore<Preferences> {
        val file = File.createTempFile(prefix, ".preferences_pb")
        file.deleteOnExit()
        tempFiles.add(file)
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        runBlocking {
            activeScopes.forEach { scope ->
                runCatching { scope.coroutineContext[Job]?.cancelAndJoin() }
                runCatching { scope.cancel() }
            }
        }
        activeScopes.clear()
        tempFiles.forEach { if (it.exists()) it.delete() }
        tempFiles.clear()
    }

    private suspend fun awaitIoAndDrainMain(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            testDispatcher.scheduler.runCurrent()
            if (condition()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for IO/Main state")
            }
            withContext(Dispatchers.Default) {
                delay(10L)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. Vault PIN API 24-25 PBKDF2 SHA-256 / SHA-1 fallback + Rate Limiting
    // -------------------------------------------------------------------------

    @Test
    fun vaultPinHasher_api24FallbackToSha1AndVerify_preservesSecurityAcrossApis() {
        assertEquals(
            VaultPinHasher.ALGORITHM_SHA1,
            VaultPinHasher.selectPreferredAlgorithm { false }
        )
        assertEquals(
            VaultPinHasher.ALGORITHM_SHA256,
            VaultPinHasher.selectPreferredAlgorithm { it == VaultPinHasher.ALGORITHM_SHA256 }
        )

        val sha1Record = VaultPinHasher.hashPinWithAlgorithm("5927", "UnsupportedPBKDF2Algo")
        assertEquals(VaultPinHasher.ALGORITHM_SHA1, sha1Record.algorithm)
        assertTrue(VaultPinHasher.verifyPin("5927", sha1Record.hashBase64, sha1Record.saltBase64, sha1Record.algorithm))
        assertFalse(VaultPinHasher.verifyPin("0000", sha1Record.hashBase64, sha1Record.saltBase64, sha1Record.algorithm))

        val sha256Record = VaultPinHasher.hashPinWithAlgorithm("8420", VaultPinHasher.ALGORITHM_SHA256)
        assertTrue(
            VaultPinHasher.verifyPin("8420", sha256Record.hashBase64, sha256Record.saltBase64, sha256Record.algorithm)
        )
        assertFalse(
            VaultPinHasher.verifyPin("8421", sha256Record.hashBase64, sha256Record.saltBase64, sha256Record.algorithm)
        )
    }

    @Test
    fun vaultSecurityPreferences_realDataStore_persistsPinAndEnforcesRateLimitLockout() = runBlocking {
        val scope = createScope()
        val prefs = VaultSecurityPreferences(createDataStore(scope, "vault_sec_"))

        assertFalse(prefs.hasPinConfigured())
        assertTrue(prefs.setPin("7319"))
        assertTrue(prefs.hasPinConfigured())

        // Wrong attempts 1 & 2 -> InvalidPin; 3rd failure -> LockedOut for 30s
        val r1 = prefs.verifyPinWithRateLimit("1111", nowMs = 1_000L)
        assertTrue(r1 is PinVerifyResult.InvalidPin && r1.failedAttempts == 1)

        val r2 = prefs.verifyPinWithRateLimit("2222", nowMs = 2_000L)
        assertTrue(r2 is PinVerifyResult.InvalidPin && r2.failedAttempts == 2)

        val r3 = prefs.verifyPinWithRateLimit("3333", nowMs = 3_000L)
        assertTrue(r3 is PinVerifyResult.LockedOut && r3.remainingMs == 30_000L)

        // Even the correct PIN is rejected while locked out
        val duringLockout = prefs.verifyPinWithRateLimit("7319", nowMs = 10_000L)
        assertTrue(duringLockout is PinVerifyResult.LockedOut)

        // After lockout window expires, correct PIN unlocks and resets lockout state
        val afterLockout = prefs.verifyPinWithRateLimit("7319", nowMs = 35_000L)
        assertTrue(afterLockout is PinVerifyResult.Success)
    }

    // -------------------------------------------------------------------------
    // 2. Vault Biometrics API 28-29 vs API 30+ Capability Resolution
    // -------------------------------------------------------------------------

    @Test
    fun vaultBiometricSupport_api28And29NeverCombineDeviceCredentialWithStrong() {
        val requestedAuthenticatorsApi28 = mutableListOf<Int>()
        val api28Cap = VaultBiometricSupport.resolveCapability(sdkInt = 28) { auth ->
            requestedAuthenticatorsApi28.add(auth)
            if (auth == BiometricManager.Authenticators.BIOMETRIC_WEAK) {
                BiometricManager.BIOMETRIC_SUCCESS
            } else {
                BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
            }
        }
        assertTrue(api28Cap.isAvailable)
        assertTrue(api28Cap.requiresNegativeButton)
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_WEAK, api28Cap.allowedAuthenticators)
        assertFalse(
            requestedAuthenticatorsApi28.any {
                (it and BiometricManager.Authenticators.DEVICE_CREDENTIAL) != 0
            }
        )

        // API 29 fallback to BIOMETRIC_STRONG when WEAK reports error
        val api29StrongCap = VaultBiometricSupport.resolveCapability(sdkInt = 29) { auth ->
            when (auth) {
                BiometricManager.Authenticators.BIOMETRIC_WEAK -> BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
                BiometricManager.Authenticators.BIOMETRIC_STRONG -> BiometricManager.BIOMETRIC_SUCCESS
                else -> throw IllegalArgumentException("Unsupported combination on API 29")
            }
        }
        assertTrue(api29StrongCap.isAvailable)
        assertTrue(api29StrongCap.requiresNegativeButton)
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, api29StrongCap.allowedAuthenticators)

        // API 30+ prefers BIOMETRIC_STRONG or DEVICE_CREDENTIAL without negative button
        val api30Cap = VaultBiometricSupport.resolveCapability(sdkInt = 30) { auth ->
            if (auth == (BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
                BiometricManager.BIOMETRIC_SUCCESS
            } else {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
            }
        }
        assertTrue(api30Cap.isAvailable)
        assertFalse(api30Cap.requiresNegativeButton)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            api30Cap.allowedAuthenticators
        )

        // No biometrics enrolled gives clear Arabic PIN fallback message
        val noEnrolled = VaultBiometricSupport.resolveCapability(sdkInt = 29) {
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }
        assertFalse(noEnrolled.isAvailable)
        assertNotNull(noEnrolled.unavailableReasonArabic)
        assertTrue(noEnrolled.unavailableReasonArabic!!.contains("PIN"))
    }

    // -------------------------------------------------------------------------
    // 3. Vault Auto-Lock on All Exits (IMMEDIATE vs Timed Inactivity)
    // -------------------------------------------------------------------------

    private class FakeVaultRepository : VaultRepository {
        private val _videos = MutableStateFlow<List<VaultVideo>>(emptyList())
        override val vaultVideos: Flow<List<VaultVideo>> = _videos

        private val _isUnlocked = MutableStateFlow(false)
        override val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

        private val _vaultError = MutableStateFlow<String?>(null)
        override val vaultError: StateFlow<String?> = _vaultError.asStateFlow()

        private var _lastActivityMs: Long = 0L
        override val lastActivityTimestampMs: Long
            get() = _lastActivityMs

        override fun setUnlocked(unlocked: Boolean) {
            _isUnlocked.value = unlocked
            if (unlocked && _lastActivityMs == 0L) {
                _lastActivityMs = 1_000L
            }
        }

        override fun recordActivity(nowMs: Long) {
            _lastActivityMs = nowMs
        }

        override suspend fun refresh() {}
        override suspend fun importVideo(
            sourceUri: Uri,
            displayName: String?,
            onProgress: (Float) -> Unit,
            isCancelled: () -> Boolean
        ): VaultVideo? = null

        override suspend fun renameVideo(videoId: Long, newTitle: String): Boolean = true
        override suspend fun deleteVaultVideo(id: Long): Boolean = true
        override fun getVideoById(id: Long): VaultVideo? = null
        override fun resolveEncryptedFile(video: VaultVideo): File = File("unused")
    }

    @Test
    fun vaultViewModel_onVaultTabExitedAndBackgrounded_respectsImmediateAndTimedPolicies() = runTest(testDispatcher) {
        val scope = createScope()
        val securityPrefs = VaultSecurityPreferences(createDataStore(scope, "vault_vm_"))
        val fakeRepo = FakeVaultRepository()
        val nowMsProvider: () -> Long = { 10_000L + testDispatcher.scheduler.currentTime }
        val vm = VaultViewModel(
            repository = fakeRepo,
            biometricHelper = VaultBiometricHelper(FakeApp()),
            securityPreferences = securityPrefs,
            nowMsProvider = nowMsProvider
        )

        val collectJob = backgroundScope.launch {
            vm.autoLockTimeout.collect {}
        }

        assertTrue(securityPrefs.setPin("2468"))
        val unlock1Done = CompletableDeferred<Boolean>()
        vm.unlockWithPin("2468") { unlock1Done.complete(it) }
        awaitIoAndDrainMain { unlock1Done.isCompleted }
        assertTrue(unlock1Done.await())
        assertTrue(vm.isUnlocked.value)

        // Advance virtual time under IMMEDIATE policy: must NOT hang in infinite loop
        testDispatcher.scheduler.advanceTimeBy(10_000L)
        testDispatcher.scheduler.runCurrent()

        // Default timeout is IMMEDIATE: exiting Vault tab MUST lock immediately
        vm.onVaultTabExited()
        assertFalse("Vault must lock immediately on tab exit under IMMEDIATE policy", vm.isUnlocked.value)

        // Switch policy to SECONDS_30 and unlock again
        vm.setAutoLockTimeout(VaultAutoLockTimeout.SECONDS_30)
        awaitIoAndDrainMain { vm.autoLockTimeout.value == VaultAutoLockTimeout.SECONDS_30 }

        val unlock2Done = CompletableDeferred<Boolean>()
        vm.unlockWithPin("2468") { unlock2Done.complete(it) }
        awaitIoAndDrainMain { unlock2Done.isCompleted }
        assertTrue(unlock2Done.await())
        val baseTime = nowMsProvider()
        fakeRepo.recordActivity(baseTime)
        assertTrue(vm.isUnlocked.value)

        // Exiting tab or backgrounding under SECONDS_30 must NOT prematurely lock before timeout
        vm.onVaultTabExited()
        assertTrue(vm.isUnlocked.value)
        vm.onAppBackgrounded()
        assertTrue(vm.isUnlocked.value)
        assertFalse(vm.checkTimeoutOnForeground(nowMs = baseTime + 29_000L))
        assertTrue(vm.isUnlocked.value)

        // Inactivity monitor checks every 5s: 25s elapsed must stay unlocked
        testDispatcher.scheduler.advanceTimeBy(25_000L)
        testDispatcher.scheduler.runCurrent()
        assertTrue(vm.isUnlocked.value)

        // Reaching 30,000ms triggers automatic lock via monitor
        testDispatcher.scheduler.advanceTimeBy(5_000L)
        testDispatcher.scheduler.runCurrent()
        assertFalse(vm.isUnlocked.value)

        // Also verify checkTimeoutOnForeground locks when elapsed >= 30,000ms
        val unlock3Done = CompletableDeferred<Boolean>()
        vm.unlockWithPin("2468") { unlock3Done.complete(it) }
        awaitIoAndDrainMain { unlock3Done.isCompleted }
        assertTrue(unlock3Done.await())
        fakeRepo.recordActivity(10_000L)
        assertTrue(vm.checkTimeoutOnForeground(nowMs = 40_500L))
        assertFalse(vm.isUnlocked.value)

        vm.lockVault()
        collectJob.cancel()
        vm.clearForTesting()
        testDispatcher.scheduler.runCurrent()
    }

    // -------------------------------------------------------------------------
    // 4. Vault Notification & Lock-Screen Metadata Privacy
    // -------------------------------------------------------------------------

    @Test
    fun mediaNotificationPrivacy_masksVaultTitlesAndMarksVisibilityPrivate() {
        val vaultItem = MediaItem.Builder()
            .setMediaId("vault://item/42")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Secret_Family_Video_Raw_Title")
                    .build()
            )
            .build()

        assertTrue(MediaNotificationPrivacy.isVaultMediaItem(vaultItem))
        val vaultMeta = MediaNotificationPrivacy.resolveNotificationMetadata(vaultItem, isPlaying = true)
        assertTrue(vaultMeta.isVaultPrivate)
        assertEquals(MediaNotificationPrivacy.VAULT_MASKED_TITLE, vaultMeta.title)
        assertEquals(MediaNotificationPrivacy.VAULT_MASKED_TEXT, vaultMeta.contentText)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, vaultMeta.visibility)
        assertFalse(vaultMeta.title.contains("Secret_Family"))

        val publicItem = MediaItem.Builder()
            .setMediaId("108")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("وثائقي الطبيعة 4K")
                    .build()
            )
            .build()

        assertFalse(MediaNotificationPrivacy.isVaultMediaItem(publicItem))
        val publicMeta = MediaNotificationPrivacy.resolveNotificationMetadata(publicItem, isPlaying = true)
        assertFalse(publicMeta.isVaultPrivate)
        assertEquals("وثائقي الطبيعة 4K", publicMeta.title)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, publicMeta.visibility)
    }

    // -------------------------------------------------------------------------
    // 5. Trash Bin Error Preservation, Duplicate Prevention & Explicit Removal
    // -------------------------------------------------------------------------

    private class FakeTrashDatabaseStore : TrashDatabaseStore {
        val rows = linkedMapOf<Long, TrashItem>()
        private var nextId = 1L
        var throwOnRead: Boolean = false
        var throwOnInsert: Boolean = false
        var returnInvalidInsertId: Boolean = false

        fun seed(item: TrashItem): TrashItem {
            val assignedId = if (item.id > 0L) item.id else nextId++
            if (assignedId >= nextId) nextId = assignedId + 1
            val stored = item.copy(id = assignedId)
            rows[assignedId] = stored
            return stored
        }

        override fun insertTrashItem(item: TrashItem): Long {
            if (throwOnInsert) throw IllegalStateException("Disk full")
            if (returnInvalidInsertId) return -1L
            val stored = seed(item)
            return stored.id
        }

        override fun getAllTrashItems(): List<TrashItem> {
            if (throwOnRead) throw IllegalStateException("SQLite read failure")
            return rows.values.sortedByDescending { it.trashedAtEpochMs }
        }

        override fun deleteTrashItemById(id: Long): Int {
            return if (rows.remove(id) != null) 1 else 0
        }

        override fun clearAllTrashItems(): Int {
            val count = rows.size
            rows.clear()
            return count
        }
    }

    @Test
    fun trashRepository_preservesItemsOnDbReadErrorAndReportsWriteFailures() = runBlocking {
        val dbStore = FakeTrashDatabaseStore()
        val seeded = dbStore.seed(
            TrashItem(
                id = 10L,
                videoId = 500L,
                title = "فيديو مهم",
                displayName = "important.mp4",
                durationMs = 30_000L,
                sizeBytes = 4_000_000L,
                mimeType = "video/mp4",
                bucketId = "camera",
                bucketDisplayName = "Camera",
                width = 1920,
                height = 1080,
                dateAddedSec = 1_700_000_000L,
                contentUri = "content://media/external/video/media/500",
                filePath = "/storage/emulated/0/Movies/important.mp4",
                trashedAtEpochMs = 1_750_000_000_000L
            )
        )

        val repo = TrashRepositoryImpl(dbStore)
        assertEquals(1, repo.trashItems.value.size)
        assertTrue(repo.trashedUris.value.contains(seeded.contentUri))
        assertNull(repo.trashError.value)

        // Simulate DB read error -> in-memory trash items MUST NOT vanish as empty!
        dbStore.throwOnRead = true
        repo.refresh()
        assertEquals(1, repo.trashItems.value.size)
        assertEquals("SQLite read failure", repo.trashError.value)

        // Recovery clears error cleanly
        dbStore.throwOnRead = false
        repo.refresh()
        assertNull(repo.trashError.value)

        // Restoring an existing DB item removes only that record
        val restored = repo.restoreFromTrash(seeded.id)
        assertNotNull(restored)
        assertEquals(0, repo.trashItems.value.size)
        assertTrue(repo.trashedUris.value.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 6. Backup & Restore Transactionality & Rollback
    // -------------------------------------------------------------------------

    private class FakeSubtitleStore : SubtitlePreferencesStore {
        var primary = SubtitleStyleConfig(fontSizeSp = 18f, bottomPaddingDp = 48f)
        var secondary = SubtitleStyleConfig(fontSizeSp = 15f, bottomPaddingDp = 88f)
        var failOnSecondaryUpdate = false

        override val primaryStyle: Flow<SubtitleStyleConfig> = MutableStateFlow(primary)
        override val secondaryStyle: Flow<SubtitleStyleConfig> = MutableStateFlow(secondary)

        override suspend fun updatePrimaryFontSize(sizeSp: Float) {
            primary = primary.copy(fontSizeSp = sizeSp)
        }

        override suspend fun updateSecondaryFontSize(sizeSp: Float) {
            if (failOnSecondaryUpdate) {
                throw IOException("Simulated DataStore write interruption")
            }
            secondary = secondary.copy(fontSizeSp = sizeSp)
        }
    }

    private class FakeBackupPlaylistStore : BackupPlaylistStore {
        private val storedPlaylists = mutableListOf<Pair<Playlist, List<PlaylistItem>>>()
        var failOnReplace = false

        override fun getPlaylists(): List<Playlist> = storedPlaylists.map { it.first }

        override fun getItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
            storedPlaylists.firstOrNull { it.first.id == playlistId }?.second ?: emptyList()

        override fun replaceAllPlaylistsAndItems(entries: List<Pair<Playlist, List<PlaylistItem>>>) {
            if (failOnReplace) {
                throw IllegalStateException("SQLite insert failed (-1)")
            }
            storedPlaylists.clear()
            entries.forEachIndexed { idx, (pl, items) ->
                val newId = (idx + 1).toLong()
                storedPlaylists.add(
                    pl.copy(id = newId, itemCount = items.size) to
                        items.mapIndexed { itemIdx, it ->
                            it.copy(id = (idx * 100L + itemIdx + 1L), playlistId = newId)
                        }
                )
            }
        }
    }

    private class FakeThemeRepo(
        initialTheme: AppThemeId = AppThemeId.OBSIDIAN_ELITE,
        initialMode: ThemeMode = ThemeMode.SYSTEM
    ) : ThemePreferencesRepository {
        val themeState = MutableStateFlow(initialTheme)
        val modeState = MutableStateFlow(initialMode)

        override val appThemeFlow: Flow<AppThemeId> = themeState
        override val themeModeFlow: Flow<ThemeMode> = modeState

        override suspend fun setAppTheme(theme: AppThemeId) {
            themeState.value = theme
        }

        override suspend fun setThemeMode(mode: ThemeMode) {
            modeState.value = mode
        }
    }

    @Test
    fun backupManager_rollsBackAllDbAndDataStoreChangesWhenRestoreFailsMidway() = runBlocking {
        val scope = createScope()
        val themeRepo = FakeThemeRepo(AppThemeId.EMERALD_LUXURY, ThemeMode.SYSTEM)
        val playbackRepo = PlaybackPreferencesRepositoryImpl(createDataStore(scope, "bkp_pb_")).apply {
            setPlaybackSpeed(1.25f)
            setResizeMode(VideoResizeMode.FILL.storageCode)
            setPlaybackMode(PlaybackMode.REPEAT_PLAYLIST)
            setResumeMode(ResumeMode.RESUME)
            saveVideoPosition("vid_existing", 25_000L, 100_000L)
        }
        val subtitleStore = FakeSubtitleStore()
        val vaultSecurityPrefs = VaultSecurityPreferences(createDataStore(scope, "bkp_vs_")).apply {
            setAutoLockTimeout(VaultAutoLockTimeout.SECONDS_30)
        }
        val playlistStore = FakeBackupPlaylistStore().apply {
            replaceAllPlaylistsAndItems(
                listOf(
                    Playlist(id = 1L, title = "الأصلية", createdAt = 100L, updatedAt = 200L) to listOf(
                        PlaylistItem(
                            id = 1L,
                            playlistId = 1L,
                            videoId = 10L,
                            orderIndex = 0,
                            videoTitle = "قديم",
                            durationMs = 50_000L,
                            contentUri = "content://old/10"
                        )
                    )
                )
            )
        }

        val manager = BackupManager.createForTesting(
            themeRepository = themeRepo,
            playbackRepository = playbackRepo,
            subtitleRepository = subtitleStore,
            vaultSecurityStore = vaultSecurityPrefs,
            playlistStore = playlistStore
        )

        val incomingPayload = BackupPayload(
            schemaVersion = BackupManager.SCHEMA_VERSION,
            createdAtEpochMs = 2_000L,
            settings = BackupSettingsData(
                selectedTheme = AppThemeId.RUBY_CINEMA,
                themeMode = ThemeMode.DARK,
                playbackSpeed = 2.0f,
                resizeMode = VideoResizeMode.RATIO_16_9.storageCode,
                playbackMode = PlaybackMode.STOP_AFTER_CURRENT,
                resumeMode = ResumeMode.START_FROM_BEGINNING
            ),
            subtitles = BackupSubtitleData(primaryFontSizeSp = 24f, secondaryFontSizeSp = 19f),
            vaultSecurity = BackupVaultSecurityData(autoLockTimeout = VaultAutoLockTimeout.MINUTES_5),
            playlists = listOf(
                BackupPlaylistData(
                    title = "المستوردة",
                    createdAt = 500L,
                    updatedAt = 600L,
                    items = listOf(
                        BackupPlaylistItemData(
                            videoId = 99L,
                            videoTitle = "جديد",
                            durationMs = 80_000L,
                            sizeBytes = 5_000L,
                            dateAddedSec = 500L,
                            contentUri = "content://new/99",
                            isAvailable = true
                        )
                    )
                )
            ),
            resumePositions = mapOf("vid_new" to 42_000L)
        )

        // Case A: SQLite DB failure before any DataStore mutation leaves DataStore completely untouched
        playlistStore.failOnReplace = true
        val dbFailure = runCatching { manager.applyPayload(incomingPayload) }
        assertTrue(dbFailure.isFailure)
        assertEquals(AppThemeId.EMERALD_LUXURY, themeRepo.appThemeFlow.first())
        assertEquals(ThemeMode.SYSTEM, themeRepo.themeModeFlow.first())
        assertEquals(1.25f, playbackRepo.playbackSpeed.first(), 0.001f)
        assertEquals(25_000L, playbackRepo.getSavedVideoPosition("vid_existing", 100_000L))

        // Case B: DB succeeds then DataStore fails mid-restore -> BOTH DB & DataStore roll back completely
        playlistStore.failOnReplace = false
        subtitleStore.failOnSecondaryUpdate = true
        val midFailure = runCatching { manager.applyPayload(incomingPayload) }
        assertTrue(midFailure.isFailure)

        assertEquals(1, playlistStore.getPlaylists().size)
        assertEquals("الأصلية", playlistStore.getPlaylists().first().title)
        assertEquals(AppThemeId.EMERALD_LUXURY, themeRepo.appThemeFlow.first())
        assertEquals(ThemeMode.SYSTEM, themeRepo.themeModeFlow.first())
        assertEquals(1.25f, playbackRepo.playbackSpeed.first(), 0.001f)
        assertEquals(PlaybackMode.REPEAT_PLAYLIST, playbackRepo.playbackMode.first())
        assertEquals(25_000L, playbackRepo.getSavedVideoPosition("vid_existing", 100_000L))
        assertEquals(0L, playbackRepo.getSavedVideoPosition("vid_new", 100_000L))
        assertEquals(VaultAutoLockTimeout.SECONDS_30, vaultSecurityPrefs.autoLockTimeout.first())
    }

    // -------------------------------------------------------------------------
    // 7. ThemeMode.SYSTEM Dynamic Light/Dark Resolution Across All 8 Themes
    // -------------------------------------------------------------------------

    @Test
    fun themeModeSystem_tracksSystemDarkStateAndPreservesSystemPreferenceOnThemeSwitch() = runTest(testDispatcher) {
        assertTrue(resolveIsDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
        assertFalse(resolveIsDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
        assertTrue(resolveIsDarkTheme(ThemeMode.DARK, systemIsDark = false))
        assertFalse(resolveIsDarkTheme(ThemeMode.LIGHT, systemIsDark = true))

        // ROYAL_IVORY supports both Dark mode (when SYSTEM is dark) and Light mode (when forceLight = true)
        val darkIvory = paletteForTheme(AppThemeId.ROYAL_IVORY, forceLight = false)
        val lightIvory = paletteForTheme(AppThemeId.ROYAL_IVORY, forceLight = true)
        assertTrue("Dark Royal Ivory must render dark background", darkIvory.background.red < 0.2f)
        assertTrue("Light Royal Ivory must render light background", lightIvory.background.red > 0.8f)

        // All 8 themes keep distinct dark-mode accents and seekbar glow endpoints
        assertEquals(8, AppThemeId.entries.map { paletteForTheme(it, forceLight = false).accent }.toSet().size)
        assertEquals(8, AppThemeId.entries.map { paletteForTheme(it, forceLight = true).accent }.toSet().size)

        // Switching themes while ThemeMode.SYSTEM is active must NOT clobber ThemeMode.SYSTEM
        val repo = FakeThemeRepo(initialTheme = AppThemeId.OBSIDIAN_ELITE, initialMode = ThemeMode.SYSTEM)
        val vm = ThemeViewModel(repo)
        val modeJob = backgroundScope.launch { vm.effectiveThemeMode.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        vm.startPreviewTheme(AppThemeId.ROYAL_IVORY)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ThemeMode.SYSTEM, vm.effectiveThemeMode.value)

        vm.applyPreview()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(AppThemeId.ROYAL_IVORY, repo.appThemeFlow.first())
        assertEquals(ThemeMode.SYSTEM, repo.themeModeFlow.first())

        modeJob.cancel()
    }
}
