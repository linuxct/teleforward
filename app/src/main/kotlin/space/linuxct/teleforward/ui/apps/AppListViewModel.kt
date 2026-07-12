package space.linuxct.teleforward.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.linuxct.teleforward.data.repo.AppCatalogRepository
import space.linuxct.teleforward.data.repo.RulesRepository
import space.linuxct.teleforward.domain.AppInfo
import space.linuxct.teleforward.domain.RuleMode
import space.linuxct.teleforward.domain.SelectionRule
import javax.inject.Inject

/** The three mutually-exclusive views of the app list. */
enum class AppFilter { ALL, ENABLED, SEEN }

/** A single row in the app list, projecting an [AppInfo] together with its current rule state. */
data class AppRowUi(
    val packageName: String,
    val label: String,
    val isSeen: Boolean,
    val lastSeen: Long?,
    /** true when an enabled whole-app INCLUDE rule exists (the per-app switch state). */
    val wholeAppEnabled: Boolean,
    /** number of enabled per-channel INCLUDE rules for this app. */
    val enabledChannelCount: Int,
)

/** Single immutable UI state for the app list screen. */
data class AppListUiState(
    val loading: Boolean = true,
    val query: String = "",
    val filter: AppFilter = AppFilter.ALL,
    val apps: List<AppRowUi> = emptyList(),
    /** total apps in the catalog before search/filter (to distinguish "empty catalog" from "no match"). */
    val totalApps: Int = 0,
)

/**
 * Drives the app-selection list: combines the app catalog with the user's selection rules so each
 * row's switch reflects the current whole-app rule, and exposes search + filter as UI state.
 */
@HiltViewModel
class AppListViewModel @Inject constructor(
    private val appCatalogRepository: AppCatalogRepository,
    private val rulesRepository: RulesRepository,
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val filterFlow = MutableStateFlow(AppFilter.ALL)

    val uiState: StateFlow<AppListUiState> = combine(
        appCatalogRepository.observeCatalog(),
        rulesRepository.observeAllRules(),
        queryFlow,
        filterFlow,
    ) { catalog, rules, query, filter ->
        buildState(catalog, rules, query, filter)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppListUiState(),
    )

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun onFilterChange(value: AppFilter) {
        filterFlow.value = value
    }

    /** Re-query installed apps so newly-installed apps appear (called when the screen resumes). */
    fun onRefresh() {
        appCatalogRepository.refresh()
    }

    /** Whole-app switch: INCLUDE the app when turned on, remove the whole-app rule when turned off. */
    fun onToggleApp(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                rulesRepository.setWholeAppRule(packageName, RuleMode.INCLUDE, enabled = true)
            } else {
                rulesRepository.removeRule(packageName, channelId = null)
            }
        }
    }

    private fun buildState(
        catalog: List<AppInfo>,
        rules: List<SelectionRule>,
        query: String,
        filter: AppFilter,
    ): AppListUiState {
        val rulesByPackage = rules.groupBy { it.packageName }
        val rows = catalog.map { app ->
            val appRules = rulesByPackage[app.packageName].orEmpty()
            AppRowUi(
                packageName = app.packageName,
                label = app.label,
                isSeen = app.isSeen,
                lastSeen = app.lastSeen,
                wholeAppEnabled = appRules.any {
                    it.channelId == null && it.enabled && it.mode == RuleMode.INCLUDE
                },
                enabledChannelCount = appRules.count {
                    it.channelId != null && it.enabled && it.mode == RuleMode.INCLUDE
                },
            )
        }

        val trimmed = query.trim()
        val visible = rows.asSequence()
            .filter { row ->
                trimmed.isEmpty() ||
                    row.label.contains(trimmed, ignoreCase = true) ||
                    row.packageName.contains(trimmed, ignoreCase = true)
            }
            .filter { row ->
                when (filter) {
                    AppFilter.ALL -> true
                    AppFilter.ENABLED -> row.wholeAppEnabled || row.enabledChannelCount > 0
                    AppFilter.SEEN -> row.isSeen
                }
            }
            .sortedWith(
                compareByDescending<AppRowUi> { it.isSeen }
                    .thenByDescending { it.lastSeen ?: 0L }
                    .thenBy { it.label.lowercase() },
            )
            .toList()

        return AppListUiState(
            loading = false,
            query = query,
            filter = filter,
            apps = visible,
            totalApps = rows.size,
        )
    }
}
