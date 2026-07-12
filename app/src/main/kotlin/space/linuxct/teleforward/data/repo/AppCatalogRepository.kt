package space.linuxct.teleforward.data.repo

import kotlinx.coroutines.flow.Flow
import space.linuxct.teleforward.domain.AppInfo
import space.linuxct.teleforward.domain.SeenChannel

/**
 * Supplies the app-selection list (union of seen-apps catalog + PackageManager, avoiding
 * QUERY_ALL_PACKAGES) and the per-app channel list (from the seen-channels catalog).
 */
interface AppCatalogRepository {

    /** One-shot list of installed launchable apps (best-effort, no QUERY_ALL_PACKAGES). */
    suspend fun getInstalledApps(): List<AppInfo>

    /** Reactive union of seen apps + installed apps for the app list. */
    fun observeCatalog(): Flow<List<AppInfo>>

    /**
     * Invalidate the cached installed-apps list and force [observeCatalog] to re-emit. Call when the
     * app is (re)opened so apps installed since the process started show up without a force-close.
     */
    fun refresh()

    suspend fun getAppInfo(packageName: String): AppInfo?

    /** Resolve a human-readable label for [packageName] (cached). */
    suspend fun getAppLabel(packageName: String): String

    /** Channels observed for [packageName]; unknown ids surface as a synthetic "Default". */
    fun observeChannelsForPackage(packageName: String): Flow<List<SeenChannel>>

    suspend fun getChannelsForPackage(packageName: String): List<SeenChannel>
}
