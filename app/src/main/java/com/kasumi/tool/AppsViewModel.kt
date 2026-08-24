package com.kasumi.tool

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * A package that is ready on disk and still has to be handed to the system
 * installer by the Activity.
 *
 * This is state rather than a one-shot event on purpose. The download runs in
 * [androidx.lifecycle.viewModelScope] and therefore survives a configuration
 * change, but the collector lives in the composition and does not. A shared flow
 * with `replay = 0` drops anything emitted while nobody is subscribed, so an
 * install that finished during a rotation used to vanish: the APK was downloaded,
 * the spinner stopped, and no installer ever opened. As state it is simply
 * replayed to whatever composition exists next.
 */
sealed interface InstallRequest {
    data class Single(val file: File) : InstallRequest
    data class Splits(val files: List<File>) : InstallRequest
}

/** Everything the apps screen renders. */
data class AppsUiState(
    val apps: List<ApkItem> = emptyList(),
    val fileStats: Map<String, FileStats> = emptyMap(),
    val sortMode: SortMode = SortMode.NAME_ASC,
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val pendingInstall: InstallRequest? = null,
)

/**
 * Transient effects. Only messages travel this way: losing a snackbar because
 * the screen was being recreated is harmless, losing an install is not.
 */
sealed interface AppsEvent {
    data class Message(val text: UiText) : AppsEvent
}

/**
 * Owns the apps-tab state and drives [ApkRepository].
 *
 * Previously all of this lived as `mutableStateOf` fields on MainActivity, so a
 * rotation or a process death threw away the loaded catalogue, the cached file
 * stats and the sort mode, and re-ran the network refresh. Holding it here means
 * the state survives configuration changes and the work is cancelled exactly
 * once, when the screen really goes away.
 */
class AppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ApkRepository(
        context = application,
        client = (application as KasumiApplication).okHttpClient,
    )

    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AppsEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AppsEvent> = _events.asSharedFlow()

    private data class FilterInput(
        val apps: List<ApkItem>,
        val query: String,
        val sortMode: SortMode,
        val stats: Map<String, FileStats>,
    )

    /**
     * Filtering and sorting run off the main thread and only when an input that
     * actually affects the result changed — not on every unrelated state update.
     */
    val filteredApps: StateFlow<List<ApkItem>> = _uiState
        .map { FilterInput(it.apps, it.searchQuery, it.sortMode, it.fileStats) }
        .distinctUntilChanged()
        .map { input ->
            withContext(Dispatchers.Default) {
                filterAndSortApps(input.apps, input.query, input.sortMode, input.stats)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val cacheDir: File get() = getApplication<Application>().cacheDir

    init {
        viewModelScope.launch {
            val cached = repository.loadItems()
            _uiState.update { it.copy(apps = cached, isLoading = true) }
            refreshStats(cached)
            val refreshed = repository.refreshFromRemote()
            if (refreshed != null) {
                _uiState.update { it.copy(apps = refreshed) }
                refreshStats(refreshed)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // --- User actions ---------------------------------------------------------

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun setSortMode(mode: SortMode) = _uiState.update { it.copy(sortMode = mode) }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val refreshed = repository.refreshFromRemote()
                if (refreshed != null) {
                    _uiState.update { it.copy(apps = refreshed) }
                    refreshStats(refreshed)
                    emit(UiText.res(R.string.source_refreshed))
                } else {
                    emit(UiText.res(R.string.source_refresh_failed))
                }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val (count, size) = repository.clearCache()
            refreshStats(_uiState.value.apps)
            emit(UiText.res(R.string.cache_cleared, count, formatFileSize(size)))
        }
    }

    /**
     * Makes the APK available locally, then installs it: silently through root
     * when available, otherwise by handing the file back to the Activity, which
     * owns the installer intents.
     */
    fun install(item: ApkItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val apkFile = prepareFile(item)
                if (apkFile == null) {
                    emit(UiText.res(R.string.install_prepare_failed))
                    return@launch
                }
                refreshStatsFor(item)

                if (isSplitPackage(item, apkFile)) {
                    installSplitPackage(apkFile)
                } else {
                    installSingle(apkFile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Install failed for " + item.name, e)
                emit(UiText.res(R.string.install_error, e.message ?: ""))
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // --- Install helpers ------------------------------------------------------

    private suspend fun prepareFile(item: ApkItem): File? {
        val cached = repository.cachedFileFor(item)
        if (item.sourceType == SourceType.URL && cached.exists() && cached.length() > 0) return cached
        return when (item.sourceType) {
            SourceType.LOCAL -> item.uri?.let { repository.copyFromUri(it.toUri()) }
            SourceType.URL -> repository.downloadApk(item)
        }
    }

    private fun isSplitPackage(item: ApkItem, file: File): Boolean {
        val url = item.url?.lowercase(Locale.ROOT).orEmpty()
        val name = file.name.lowercase(Locale.ROOT)
        return SPLIT_EXTENSIONS.any { url.contains(it) || name.endsWith(it) }
    }

    private suspend fun installSplitPackage(apkFile: File) {
        val pkg = withContext(Dispatchers.IO) { repository.extractSplitsAndObb(apkFile) }
        if (pkg.apks.isEmpty()) {
            emit(UiText.res(R.string.install_no_apk_inside))
            return
        }
        pkg.obb?.let { repository.installObbFiles(it) }

        val installedByRoot = withContext(Dispatchers.IO) {
            RootInstaller.isDeviceRooted() && RootInstaller.installApks(pkg.apks).first
        }
        if (installedByRoot) {
            emit(UiText.res(R.string.install_success))
        } else {
            _uiState.update { it.copy(pendingInstall = InstallRequest.Splits(pkg.apks)) }
        }
    }

    private suspend fun installSingle(apkFile: File) {
        val installedByRoot = withContext(Dispatchers.IO) {
            RootInstaller.isDeviceRooted() && RootInstaller.installApk(apkFile).first
        }
        if (installedByRoot) {
            emit(UiText.res(R.string.install_success))
        } else {
            _uiState.update { it.copy(pendingInstall = InstallRequest.Single(apkFile)) }
        }
    }

    /**
     * Clears the pending request once the Activity has actually started the
     * installer. Acknowledging only afterwards means a request survives an
     * Activity that is destroyed mid-handover and is retried on the next one,
     * rather than being dropped.
     */
    fun onInstallRequestHandled() = _uiState.update { it.copy(pendingInstall = null) }

    // --- Stats ----------------------------------------------------------------

    private suspend fun refreshStats(apps: List<ApkItem>) {
        val stats = FileStatsHelper.computeAll(apps, cacheDir)
        _uiState.update { it.copy(fileStats = stats) }
    }

    private suspend fun refreshStatsFor(item: ApkItem) {
        val stats = FileStatsHelper.computeOne(item, cacheDir)
        _uiState.update { it.copy(fileStats = it.fileStats + (item.id to stats)) }
    }

    private suspend fun emit(text: UiText) = _events.emit(AppsEvent.Message(text))

    companion object {
        private const val TAG = "AppsViewModel"
        private val SPLIT_EXTENSIONS = listOf(".apks", ".xapk", ".apkm")
    }
}
