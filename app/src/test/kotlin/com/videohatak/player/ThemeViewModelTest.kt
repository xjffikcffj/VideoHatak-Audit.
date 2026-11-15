package com.videohatak.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.videohatak.player.core.designsystem.theme.ThemeMode
import com.videohatak.player.core.designsystem.theme.ThemePreferencesRepository
import com.videohatak.player.core.designsystem.theme.ThemeState
import com.videohatak.player.core.designsystem.theme.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

@OptIn(ExperimentalCoroutinesApi::class)
class ThemeViewModelTest {

    @get:Rule
    val timeoutRule: Timeout = Timeout.seconds(30)

    private val testDispatcher = StandardTestDispatcher()
    private val activeViewModelStores = mutableListOf<ViewModelStore>()

    private class FakeThemePreferencesRepository : ThemePreferencesRepository {
        val flow = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeModeFlow: Flow<ThemeMode> = flow

        override suspend fun setThemeMode(mode: ThemeMode) {
            flow.value = mode
        }
    }

    private lateinit var fakeRepository: FakeThemePreferencesRepository

    private fun createViewModel(): Pair<ViewModelStore, ThemeViewModel> {
        val store = ViewModelStore().also { activeViewModelStores.add(it) }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ThemeViewModel(fakeRepository) as T
            }
        }
        return store to ViewModelProvider(store, factory)[ThemeViewModel::class.java]
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeThemePreferencesRepository()
    }

    @After
    fun tearDown() {
        try {
            activeViewModelStores.forEach { store ->
                runCatching { store.clear() }
            }
            activeViewModelStores.clear()
            runCatching { testDispatcher.scheduler.advanceUntilIdle() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun defaultState_loadsFromRepository() = runTest(testDispatcher) {
        val (store, viewModel) = createViewModel()
        val collectJob = backgroundScope.launch { viewModel.themeState.collect {} }

        // Advance dispatcher to collect the initial flow emission
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.themeState.value
        assertTrue("Expected ThemeState.Success but was $state", state is ThemeState.Success)
        assertEquals(ThemeMode.SYSTEM, (state as ThemeState.Success).themeMode)
        collectJob.cancelAndJoin()
        store.clear()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun setThemeMode_updatesRepositoryAndState() = runTest(testDispatcher) {
        val (store, viewModel) = createViewModel()
        val collectJob = backgroundScope.launch { viewModel.themeState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.DARK)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.themeState.value
        assertTrue(state is ThemeState.Success)
        assertEquals(ThemeMode.DARK, (state as ThemeState.Success).themeMode)
        assertEquals(ThemeMode.DARK, fakeRepository.flow.value)
        collectJob.cancelAndJoin()
        store.clear()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun setThemeMode_switchToLight_updatesCorrectly() = runTest(testDispatcher) {
        val (store, viewModel) = createViewModel()
        val collectJob = backgroundScope.launch { viewModel.themeState.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.LIGHT)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.themeState.value
        assertTrue(state is ThemeState.Success)
        assertEquals(ThemeMode.LIGHT, (state as ThemeState.Success).themeMode)
        assertEquals(ThemeMode.LIGHT, fakeRepository.flow.value)
        collectJob.cancelAndJoin()
        store.clear()
        testDispatcher.scheduler.advanceUntilIdle()
    }
}
