package com.videohatak.player

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.videohatak.player.core.data.repository.VideoRepository
import com.videohatak.player.core.diagnostics.PerformanceDiagnostics
import com.videohatak.player.core.model.SortOrder
import com.videohatak.player.core.model.VideoFolder
import com.videohatak.player.core.model.VideoItem
import com.videohatak.player.core.model.VideoSortField
import com.videohatak.player.core.model.VideoSortOrder
import com.videohatak.player.core.model.SortDirection
import com.videohatak.player.core.permission.VideoPermissionStatus
import com.videohatak.player.core.playback.PlayerOrientationMode
import com.videohatak.player.core.playlist.db.TrashDatabaseStore
import com.videohatak.player.core.thumbnail.cache.VideoThumbnailLoader
import com.videohatak.player.core.trash.model.TrashItem
import com.videohatak.player.core.trash.repository.TrashRepository
import com.videohatak.player.core.trash.repository.TrashRepositoryImpl
import com.videohatak.player.core.vault.model.VaultEntryState
import com.videohatak.player.core.vault.model.VaultHiddenMatcher
import com.videohatak.player.core.vault.model.VaultHiddenOriginal
import com.videohatak.player.core.vault.model.VaultOriginalStorageType
import com.videohatak.player.feature.library.LibraryViewModel
import com.videohatak.player.feature.player.resolveOrientationRestoreOnExitPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LibraryPerformanceAndRotationVerificationTest {

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        PerformanceDiagnostics.onSlowOperationListener = null
        Dispatchers.resetMain()
    }

    @Test
    fun libraryViewModel_onPermissionStatusChanged_deduplicatesResumeAndOnlyReloadsOnChangeOrForce() =
        runTest(testDispatcher) {
            val videoRepo = CountingVideoRepository(sampleVideos(3))
            val trashRepo = CountingTrashRepository()
            val vm = LibraryViewModel(
                videoRepository = videoRepo,
                trashRepository = trashRepo,
                computeDispatcher = testDispatcher
            )

            // Cold start with FullAccess -> triggers one load
            vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)
            advanceUntilIdle()
            assertEquals(1, videoRepo.getVideosCalls.get())
            assertEquals(1, trashRepo.refreshCalls.get())

            // Repeated onResume calls with same FullAccess must NOT re-query MediaStore or Trash DB
            vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)
            vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)
            advanceUntilIdle()
            assertEquals(1, videoRepo.getVideosCalls.get())
            assertEquals(1, trashRepo.refreshCalls.get())

            // Transition to PartialAccess (changed) -> reloads videos once without redundant trash refresh
            vm.onPermissionStatusChanged(VideoPermissionStatus.PartialAccess)
            advanceUntilIdle()
            assertEquals(2, videoRepo.getVideosCalls.get())
            assertEquals(1, trashRepo.refreshCalls.get())

            vm.onPermissionStatusChanged(VideoPermissionStatus.PartialAccess)
            advanceUntilIdle()
            assertEquals(2, videoRepo.getVideosCalls.get())
            assertEquals(1, trashRepo.refreshCalls.get())

            // System media picker result with forceRefresh=true under PartialAccess MUST reload both
            vm.onPermissionStatusChanged(VideoPermissionStatus.PartialAccess, forceRefresh = true)
            advanceUntilIdle()
            assertEquals(3, videoRepo.getVideosCalls.get())
            assertEquals(2, trashRepo.refreshCalls.get())

            // Explicit refresh() invokes Trash + Video load strictly once per refresh (no double trash refresh)
            vm.refresh()
            advanceUntilIdle()
            assertEquals(4, videoRepo.getVideosCalls.get())
            assertEquals(3, trashRepo.refreshCalls.get())
        }

    @Test
    fun libraryViewModel_concurrentRefresh_cancelsStaleLoadAndKeepsNewestResults() =
        runTest(testDispatcher) {
            val firstGate = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<Unit>()
            val callIndex = AtomicInteger(0)

            val staleVideos = sampleVideos(1, prefix = "stale")
            val freshVideos = sampleVideos(2, prefix = "fresh")

            val repo = object : CountingVideoRepository(emptyList()) {
                override suspend fun getVideos(): List<VideoItem> {
                    val idx = callIndex.incrementAndGet()
                    getVideosCalls.incrementAndGet()
                    return if (idx == 1) {
                        firstGate.await()
                        staleVideos
                    } else {
                        secondGate.await()
                        freshVideos
                    }
                }
            }

            val vm = LibraryViewModel(
                videoRepository = repo,
                computeDispatcher = testDispatcher
            )
            val collectJob = launch(start = CoroutineStart.UNDISPATCHED) { vm.uiState.collect {} }

            vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)
            runCurrent() // enter getVideos() #1 and suspend on firstGate
            vm.refresh() // cancels call #1 and launches call #2
            runCurrent() // enter getVideos() #2 and suspend on secondGate
            assertTrue(vm.uiState.value.isLoading)

            // Even if stale call #1 unblocks first, call #2 is still active & stale data is ignored
            firstGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isLoading)

            secondGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isLoading)
            assertEquals(listOf("fresh_1", "fresh_2"), vm.uiState.value.allVideos.map { it.title })

            collectJob.cancel()
        }

    @Test
    fun libraryViewModel_filterSortAndFolderGrouping_runOffMainThreadAndCacheFoldersAcrossSearchAndSort() =
        runBlocking {
            val mainThreadRef = AtomicReference<Thread>()
            val workerThreadRef = AtomicReference<Thread>()
            val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "vh-main-thread").also { mainThreadRef.set(it) }
            }
            val mainDispatcher = mainExecutor.asCoroutineDispatcher()
            val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "vh-compute-worker").also { workerThreadRef.set(it) }
            }
            val workerDispatcher = workerExecutor.asCoroutineDispatcher()
            Dispatchers.setMain(mainDispatcher)
            val vmStore = ViewModelStore()
            var collectJob: Job? = null
            try {
                val repo = CountingVideoRepository(sampleVideos(6))
                val vm = ViewModelProvider(
                    vmStore,
                    LibraryViewModel.provideFactory(
                        videoRepository = repo,
                        computeDispatcher = workerDispatcher
                    )
                )[LibraryViewModel::class.java]

                collectJob = launch(mainDispatcher) {
                    vm.uiState.collect {}
                }

                vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)

                val loadedState = withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoading && it.allVideos.size == 6 }
                }
                assertEquals(2, loadedState.folders.size)
                assertEquals(1, repo.groupIntoFoldersCalls.get())

                // Searching, sorting, and selecting a folder must filter/sort without recomputing groupIntoFolders
                vm.onSearchQueryChanged("video_1")
                val searchedState = withTimeout(5_000L) {
                    vm.uiState.first { it.filteredVideos.size == 1 }
                }
                assertEquals(1, searchedState.filteredVideos.size)

                vm.onSortOrderChanged(SortOrder.NAME_ASC)
                vm.onFolderSelected(loadedState.folders.first())
                vm.onSearchQueryChanged("")
                val folderFilteredState = withTimeout(5_000L) {
                    vm.uiState.first { it.filteredVideos.size == loadedState.folders.first().videoCount }
                }
                assertEquals(loadedState.folders.first().videoCount, folderFilteredState.filteredVideos.size)

                assertEquals(
                    "groupIntoFolders must NOT recompute when searchQuery, sortOrder, or selectedFolder changes",
                    1,
                    repo.groupIntoFoldersCalls.get()
                )
                assertExecutedStrictlyOnWorkerThread(
                    operationName = "filterAndSort",
                    recordedRefs = repo.filterThreadRefs,
                    recordedNames = repo.filterThreads,
                    expectedWorkerThread = workerThreadRef.get(),
                    forbiddenMainThread = mainThreadRef.get()
                )
                assertExecutedStrictlyOnWorkerThread(
                    operationName = "groupIntoFolders",
                    recordedRefs = repo.groupThreadRefs,
                    recordedNames = repo.groupThreads,
                    expectedWorkerThread = workerThreadRef.get(),
                    forbiddenMainThread = mainThreadRef.get()
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { collectJob?.cancelAndJoin() }
                }
                runCatching { vmStore.clear() }
                Dispatchers.setMain(testDispatcher)
                workerDispatcher.close()
                workerExecutor.shutdownNow()
                mainDispatcher.close()
                mainExecutor.shutdownNow()
            }
        }

    @Test
    fun libraryViewModel_emptyVideoListSkipsGroupIntoFoldersAndLoadsActualVideosOnce() =
        runBlocking {
            val mainThreadRef = AtomicReference<Thread>()
            val workerThreadRef = AtomicReference<Thread>()
            val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "vh-main-thread").also { mainThreadRef.set(it) }
            }
            val mainDispatcher = mainExecutor.asCoroutineDispatcher()
            val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "vh-compute-worker").also { workerThreadRef.set(it) }
            }
            val workerDispatcher = workerExecutor.asCoroutineDispatcher()
            Dispatchers.setMain(mainDispatcher)
            val vmStore = ViewModelStore()
            var collectJob: Job? = null
            try {
                val videosRef = AtomicReference<List<VideoItem>>(emptyList())
                val repo = object : CountingVideoRepository(emptyList()) {
                    override suspend fun getVideos(): List<VideoItem> {
                        getVideosCalls.incrementAndGet()
                        return videosRef.get()
                    }
                }
                val vaultHiddenFlow = MutableStateFlow<List<VaultHiddenOriginal>>(emptyList())
                val vm = ViewModelProvider(
                    vmStore,
                    LibraryViewModel.provideFactory(
                        videoRepository = repo,
                        vaultHiddenEntries = vaultHiddenFlow,
                        computeDispatcher = workerDispatcher
                    )
                )[LibraryViewModel::class.java]

                collectJob = launch(mainDispatcher) {
                    vm.uiState.collect {}
                }

                // 1. Initial empty state & empty MediaStore result must NEVER call groupIntoFolders()
                vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)
                val emptyLoadedState = withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoading && it.permissionStatus == VideoPermissionStatus.FullAccess }
                }
                assertTrue(emptyLoadedState.allVideos.isEmpty())
                assertTrue(emptyLoadedState.folders.isEmpty())
                assertEquals(
                    "groupIntoFolders must NOT be called when video list is empty",
                    0,
                    repo.groupIntoFoldersCalls.get()
                )

                // 2. Loading actual videos invokes groupIntoFolders() once on computeDispatcher
                val realVideos = sampleVideos(6)
                videosRef.set(realVideos)
                vm.refresh()
                val populatedState = withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoading && it.allVideos.size == 6 }
                }
                assertEquals(2, populatedState.folders.size)
                assertEquals(1, repo.groupIntoFoldersCalls.get())
                assertExecutedStrictlyOnWorkerThread(
                    operationName = "groupIntoFolders",
                    recordedRefs = repo.groupThreadRefs,
                    recordedNames = repo.groupThreads,
                    expectedWorkerThread = workerThreadRef.get(),
                    forbiddenMainThread = mainThreadRef.get()
                )

                // 3. Search & sort must NOT re-invoke groupIntoFolders()
                vm.onSearchQueryChanged("video_2")
                val searchedState = withTimeout(5_000L) {
                    vm.uiState.first { it.filteredVideos.size == 1 }
                }
                assertEquals(1, searchedState.filteredVideos.size)
                vm.onSortOrderChanged(SortOrder.NAME_DESC)
                vm.onSearchQueryChanged("")
                withTimeout(5_000L) {
                    vm.uiState.first {
                        it.filteredVideos.size == 6 && it.filteredVideos.first().title == "video_6"
                    }
                }
                assertEquals(1, repo.groupIntoFoldersCalls.get())
                assertExecutedStrictlyOnWorkerThread(
                    operationName = "filterAndSort",
                    recordedRefs = repo.filterThreadRefs,
                    recordedNames = repo.filterThreads,
                    expectedWorkerThread = workerThreadRef.get(),
                    forbiddenMainThread = mainThreadRef.get()
                )

                // 4. Hiding all videos resets folders to empty without calling groupIntoFolders()
                vaultHiddenFlow.value = realVideos.map { v ->
                    VaultHiddenOriginal(
                        vaultId = v.id,
                        originalUri = v.contentUri.toString(),
                        originalMediaStoreId = v.id,
                        storageType = VaultOriginalStorageType.MEDIA_STORE,
                        vaultState = VaultEntryState.ORIGINAL_HIDDEN
                    )
                }
                val allHiddenState = withTimeout(5_000L) {
                    vm.uiState.first { it.allVideos.isEmpty() }
                }
                assertTrue(allHiddenState.folders.isEmpty())
                assertEquals(1, repo.groupIntoFoldersCalls.get())
            } finally {
                withContext(NonCancellable) {
                    runCatching { collectJob?.cancelAndJoin() }
                }
                runCatching { vmStore.clear() }
                Dispatchers.setMain(testDispatcher)
                workerDispatcher.close()
                workerExecutor.shutdownNow()
                mainDispatcher.close()
                mainExecutor.shutdownNow()
            }
        }

    @Test
    fun libraryViewModel_selectedFolderSync_updatesWithoutSideEffectLoopOrDuplicateGrouping() =
        runBlocking {
            val mainThreadRef = AtomicReference<Thread>()
            val workerThreadRef = AtomicReference<Thread>()
            val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "vh-main-thread").also { mainThreadRef.set(it) }
            }
            val mainDispatcher = mainExecutor.asCoroutineDispatcher()
            val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "vh-compute-worker").also { workerThreadRef.set(it) }
            }
            val workerDispatcher = workerExecutor.asCoroutineDispatcher()
            Dispatchers.setMain(mainDispatcher)
            val vmStore = ViewModelStore()
            var collectJob: Job? = null
            try {
                val videosRef = AtomicReference(sampleVideos(6))
                val repo = object : CountingVideoRepository(emptyList()) {
                    override suspend fun getVideos(): List<VideoItem> {
                        getVideosCalls.incrementAndGet()
                        return videosRef.get()
                    }
                }
                val vm = ViewModelProvider(
                    vmStore,
                    LibraryViewModel.provideFactory(
                        videoRepository = repo,
                        computeDispatcher = workerDispatcher
                    )
                )[LibraryViewModel::class.java]
                collectJob = launch(mainDispatcher) {
                    vm.uiState.collect {}
                }

                vm.onPermissionStatusChanged(VideoPermissionStatus.FullAccess)
                val initialState = withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoading && it.allVideos.size == 6 }
                }
                assertEquals(1, repo.groupIntoFoldersCalls.get())

                val evenFolder = initialState.folders.first { it.bucketId == "folder_even" }
                assertEquals(3, evenFolder.videoCount)
                vm.onFolderSelected(evenFolder)
                withTimeout(5_000L) {
                    vm.uiState.first { it.filteredVideos.size == 3 }
                }
                assertEquals(1, repo.groupIntoFoldersCalls.get())

                // Refresh with 8 videos (4 per folder): selectedFolder syncs to videoCount=4 with strictly +1 group call
                videosRef.set(sampleVideos(8))
                vm.refresh()
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoading && it.allVideos.size == 8 && it.filteredVideos.size == 4 }
                }
                val updatedSelected = withTimeout(5_000L) {
                    vm.selectedFolder.first { it?.videoCount == 4 }
                }
                assertEquals("folder_even", updatedSelected?.bucketId)
                assertEquals(
                    "Refreshing while a folder is selected must NOT trigger duplicate groupIntoFolders",
                    2,
                    repo.groupIntoFoldersCalls.get()
                )

                // Refresh with empty list: clears selectedFolder to null without calling groupIntoFolders again
                videosRef.set(emptyList())
                vm.refresh()
                withTimeout(5_000L) {
                    vm.uiState.first { !it.isLoading && it.allVideos.isEmpty() }
                }
                withTimeout(5_000L) {
                    vm.selectedFolder.first { it == null }
                }
                assertEquals(2, repo.groupIntoFoldersCalls.get())
                assertExecutedStrictlyOnWorkerThread(
                    operationName = "filterAndSort",
                    recordedRefs = repo.filterThreadRefs,
                    recordedNames = repo.filterThreads,
                    expectedWorkerThread = workerThreadRef.get(),
                    forbiddenMainThread = mainThreadRef.get()
                )
                assertExecutedStrictlyOnWorkerThread(
                    operationName = "groupIntoFolders",
                    recordedRefs = repo.groupThreadRefs,
                    recordedNames = repo.groupThreads,
                    expectedWorkerThread = workerThreadRef.get(),
                    forbiddenMainThread = mainThreadRef.get()
                )
            } finally {
                withContext(NonCancellable) {
                    runCatching { collectJob?.cancelAndJoin() }
                }
                runCatching { vmStore.clear() }
                Dispatchers.setMain(testDispatcher)
                workerDispatcher.close()
                workerExecutor.shutdownNow()
                mainDispatcher.close()
                mainExecutor.shutdownNow()
            }
        }

    @Test
    fun videoThumbnailLoader_cancellationPropagatesAndNeverInvokesFallbackDecode() = runBlocking {
        val startedLatch = CountDownLatch(1)
        val fallbackCalls = AtomicInteger(0)
        val uniqueVideoId = 987654321L
        val uri = Uri.parse("content://media/external/video/media/$uniqueVideoId")

        var caughtCancellation: CancellationException? = null
        val job = launch(Dispatchers.Default) {
            try {
                VideoThumbnailLoader.loadThumbnailInternal(
                    videoId = uniqueVideoId,
                    contentUri = uri,
                    ioDispatcher = Dispatchers.IO,
                    cacheLookup = { null },
                    cacheStore = { _, _ -> },
                    primaryFetcher = { _, signal ->
                        startedLatch.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!signal.isCanceled && System.nanoTime() < deadline) {
                            Thread.sleep(5)
                        }
                        if (signal.isCanceled) {
                            throw android.os.OperationCanceledException()
                        }
                        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    },
                    fallbackFetcher = {
                        fallbackCalls.incrementAndGet()
                        null
                    }
                )
            } catch (ce: CancellationException) {
                caughtCancellation = ce
                throw ce
            }
        }

        assertTrue(startedLatch.await(3, TimeUnit.SECONDS))
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull("CancellationException must propagate rather than falling back", caughtCancellation)
        assertEquals("Fallback MediaMetadataRetriever must NEVER run when cancelled", 0, fallbackCalls.get())
    }

    @Test
    fun trashRepository_asyncInitAvoidsSyncMainThreadDbReadAndLoadsOnRefresh() = runTest(testDispatcher) {
        val dbReadCalls = AtomicInteger(0)
        val seeded = TrashItem(
            id = 42L,
            videoId = 700L,
            title = "trashed_clip",
            displayName = "trashed_clip.mp4",
            durationMs = 15_000L,
            sizeBytes = 1_000_000L,
            mimeType = "video/mp4",
            bucketId = "b1",
            bucketDisplayName = "Movies",
            width = 1280,
            height = 720,
            dateAddedSec = 1700000000L,
            contentUri = "content://media/external/video/media/700",
            filePath = "/storage/emulated/0/Movies/trashed_clip.mp4",
            trashedAtEpochMs = 1750000000000L
        )
        val store = object : TrashDatabaseStore {
            private val rows = mutableListOf(seeded)
            override fun insertTrashItem(item: TrashItem): Long = 0L
            override fun getAllTrashItems(): List<TrashItem> {
                dbReadCalls.incrementAndGet()
                return rows.toList()
            }
            override fun deleteTrashItemById(id: Long): Int = 0
            override fun clearAllTrashItems(): Int = 0
        }

        // Constructing with preloadSync=false must NOT block caller thread with SQLite read
        val repo = TrashRepositoryImpl(dbStore = store, ioDispatcher = testDispatcher, preloadSync = false)
        assertEquals(0, dbReadCalls.get())
        assertTrue(repo.trashItems.value.isEmpty())

        repo.refresh()
        assertEquals(1, dbReadCalls.get())
        assertEquals(1, repo.trashItems.value.size)
        assertTrue(repo.trashedUris.value.contains(seeded.contentUri))
    }

    @Test
    fun vaultHiddenMatcher_batchFilterVisibleVideos_accuratelyExcludesHiddenEntriesAndKeepsRestored() {
        val allVideos = (1..300).map { i ->
            VideoItem(
                id = i.toLong(),
                contentUri = Uri.parse("content://media/external/video/media/$i"),
                title = "video_$i",
                displayName = "video_$i.mp4",
                path = "/storage/emulated/0/Movies/video_$i.mp4",
                bucketId = if (i % 2 == 0) "b_even" else "b_odd",
                bucketDisplayName = if (i % 2 == 0) "Even" else "Odd"
            )
        }
        val hiddenEntries = listOf(
            VaultHiddenOriginal(
                vaultId = 1L,
                originalUri = "content://media/external/video/media/10",
                originalMediaStoreId = 10L,
                originalFilePath = "/storage/emulated/0/Movies/video_10.mp4",
                storageType = VaultOriginalStorageType.MEDIA_STORE,
                vaultState = VaultEntryState.ORIGINAL_HIDDEN
            ),
            VaultHiddenOriginal(
                vaultId = 2L,
                originalUri = "",
                originalMediaStoreId = 25L,
                originalFilePath = "/storage/emulated/0/Movies/video_25.mp4",
                storageType = VaultOriginalStorageType.MEDIA_STORE,
                vaultState = VaultEntryState.ORIGINAL_DELETED_VERIFIED
            ),
            VaultHiddenOriginal(
                vaultId = 3L,
                originalUri = "content://media/external/video/media/30",
                originalMediaStoreId = 30L,
                originalFilePath = "/storage/emulated/0/Movies/video_30.mp4",
                storageType = VaultOriginalStorageType.MEDIA_STORE,
                vaultState = VaultEntryState.ORIGINAL_RESTORED_VERIFIED
            )
        )

        val visible = VaultHiddenMatcher.filterVisibleVideos(allVideos, hiddenEntries)
        val visibleIds = visible.map { it.id }.toSet()

        assertEquals(298, visible.size)
        assertFalse(10L in visibleIds)
        assertFalse(25L in visibleIds)
        assertTrue("ORIGINAL_RESTORED_VERIFIED entry must remain visible", 30L in visibleIds)
    }

    @Test
    fun mainAppOrientation_syncAndPlayerExit_neverLeakPortraitOrLandscapeToMainScreens() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, MainActivity.MAIN_APP_ORIENTATION)

        // When outside player and requestedOrientation drifted (e.g. PORTRAIT), MainActivity restores FULL_SENSOR
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            MainActivity.resolveOrientationSyncTarget(
                isInPlayerScreen = false,
                currentRequestedOrientation = PlayerOrientationMode.PORTRAIT.activityOrientation
            )
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            MainActivity.resolveOrientationSyncTarget(
                isInPlayerScreen = false,
                currentRequestedOrientation = PlayerOrientationMode.LANDSCAPE.activityOrientation
            )
        )
        // Already FULL_SENSOR -> no redundant binder update
        assertNull(
            MainActivity.resolveOrientationSyncTarget(
                isInPlayerScreen = false,
                currentRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            )
        )
        // Inside player -> MainActivity must NOT override player's selected orientation mode
        assertNull(
            MainActivity.resolveOrientationSyncTarget(
                isInPlayerScreen = true,
                currentRequestedOrientation = PlayerOrientationMode.PORTRAIT.activityOrientation
            )
        )
        assertNull(
            MainActivity.resolveOrientationSyncTarget(
                isInPlayerScreen = true,
                currentRequestedOrientation = PlayerOrientationMode.LANDSCAPE.activityOrientation
            )
        )

        // Exiting PlayerScreen hosted by MainActivity ALWAYS restores FULL_SENSOR regardless of player lock
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            resolveOrientationRestoreOnExitPlayer(
                isMainActivity = true,
                orientationBeforePlayer = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            )
        )
    }

    @Test
    fun performanceDiagnostics_recordsSlowOperationWithoutSwallowingExceptions() {
        val recorded = mutableListOf<PerformanceDiagnostics.SlowOperationRecord>()
        PerformanceDiagnostics.onSlowOperationListener = { recorded.add(it) }

        PerformanceDiagnostics.recordIfSlow(
            operation = "test_slow_op",
            elapsedMs = 150L,
            mainThresholdMs = 50L,
            bgThresholdMs = 100L,
            details = { "count=10" }
        )
        assertEquals(1, recorded.size)
        assertEquals("test_slow_op", recorded.first().operation)

        var threw = false
        try {
            PerformanceDiagnostics.measure("failing_op") {
                throw IllegalStateException("boom")
            }
        } catch (ise: IllegalStateException) {
            threw = ise.message == "boom"
        }
        assertTrue(threw)
    }

    private fun sampleVideos(count: Int, prefix: String = "video"): List<VideoItem> {
        return (1..count).map { i ->
            val bucket = if (i % 2 == 0) "folder_even" else "folder_odd"
            VideoItem(
                id = i.toLong(),
                contentUri = Uri.parse("content://media/external/video/media/${prefix}_$i"),
                title = "${prefix}_$i",
                displayName = "${prefix}_$i.mp4",
                durationMs = i * 10_000L,
                sizeBytes = i * 1_000_000L,
                dateAddedSec = 1700000000L + i,
                bucketId = bucket,
                bucketDisplayName = bucket
            )
        }
    }

    private fun assertExecutedStrictlyOnWorkerThread(
        operationName: String,
        recordedRefs: List<Thread>,
        recordedNames: List<String>,
        expectedWorkerThread: Thread?,
        forbiddenMainThread: Thread?
    ) {
        assertTrue("$operationName must execute at least once", recordedRefs.isNotEmpty())
        assertNotNull("Worker thread must have been initialized", expectedWorkerThread)
        assertTrue(
            "$operationName must run only on worker Thread ($expectedWorkerThread), actual refs=$recordedRefs, names=$recordedNames",
            recordedRefs.all { it === expectedWorkerThread }
        )
        if (forbiddenMainThread != null) {
            assertTrue(
                "$operationName must NEVER run on Main Thread ($forbiddenMainThread), actual refs=$recordedRefs, names=$recordedNames",
                recordedRefs.none { it === forbiddenMainThread }
            )
        }
        assertTrue(
            "$operationName thread names must start with 'vh-compute-worker', actual=$recordedNames",
            recordedNames.all { it == "vh-compute-worker" || it.startsWith("vh-compute-worker ") }
        )
        assertTrue(
            "$operationName thread names must NOT start with 'vh-main-thread', actual=$recordedNames",
            recordedNames.none { it == "vh-main-thread" || it.startsWith("vh-main-thread ") }
        )
    }

    private open class CountingVideoRepository(
        private val items: List<VideoItem>
    ) : VideoRepository {
        val getVideosCalls = AtomicInteger(0)
        val groupIntoFoldersCalls = AtomicInteger(0)
        val filterThreads = CopyOnWriteArrayList<String>()
        val groupThreads = CopyOnWriteArrayList<String>()
        val filterThreadRefs = CopyOnWriteArrayList<Thread>()
        val groupThreadRefs = CopyOnWriteArrayList<Thread>()

        override fun observeVideos(): Flow<List<VideoItem>> = flowOf(items)

        override suspend fun getVideos(): List<VideoItem> {
            getVideosCalls.incrementAndGet()
            return items
        }

        override fun filterAndSort(
            videos: List<VideoItem>,
            query: String,
            sortOrder: VideoSortOrder,
            folderId: String?
        ): List<VideoItem> {
            val current = Thread.currentThread()
            filterThreadRefs.add(current)
            filterThreads.add(current.name)
            val filtered = videos.filter { v ->
                (folderId == null || v.bucketId == folderId) &&
                    (query.isBlank() || v.safeTitle.contains(query, ignoreCase = true))
            }
            return when (sortOrder.field) {
                VideoSortField.NAME -> if (sortOrder.direction == SortDirection.ASCENDING) {
                    filtered.sortedBy { it.safeTitle.lowercase() }
                } else {
                    filtered.sortedByDescending { it.safeTitle.lowercase() }
                }
                else -> filtered
            }
        }

        override fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> {
            val current = Thread.currentThread()
            groupThreadRefs.add(current)
            groupThreads.add(current.name)
            groupIntoFoldersCalls.incrementAndGet()
            return videos.groupBy { it.bucketId }.map { (bucketId, list) ->
                VideoFolder(
                    bucketId = bucketId,
                    displayName = list.first().bucketDisplayName,
                    videoCount = list.size,
                    totalSizeBytes = list.sumOf { it.sizeBytes },
                    firstVideoItem = list.firstOrNull()
                )
            }
        }
    }

    private class CountingTrashRepository : TrashRepository {
        val refreshCalls = AtomicInteger(0)
        private val _trashItems = MutableStateFlow<List<TrashItem>>(emptyList())
        override val trashItems: StateFlow<List<TrashItem>> = _trashItems.asStateFlow()
        private val _trashedUris = MutableStateFlow<Set<String>>(emptySet())
        override val trashedUris: StateFlow<Set<String>> = _trashedUris.asStateFlow()
        private val _trashError = MutableStateFlow<String?>(null)
        override val trashError: StateFlow<String?> = _trashError.asStateFlow()

        override suspend fun refresh() {
            refreshCalls.incrementAndGet()
        }

        override suspend fun moveToTrash(video: VideoItem): TrashItem? = null
        override suspend fun restoreFromTrash(trashId: Long): TrashItem? = null
        override suspend fun removeRecord(trashId: Long): Boolean = false
        override suspend fun clearAllRecords() {}
        override fun clearError() {}
    }
}
