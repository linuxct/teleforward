package space.linuxct.teleforward.data.repo

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.domain.AppInfo
import space.linuxct.teleforward.domain.SeenChannel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the app-selection catalog (union of the seen-apps catalog + installed launchable apps)
 * and delegates per-app channel enumeration to [seenChannelRepository]. PackageManager is queried
 * via the launcher intent (no QUERY_ALL_PACKAGES); labels and the installed-apps list are cached.
 */
@Singleton
class AppCatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val seenChannelRepository: SeenChannelRepository,
) : AppCatalogRepository {

    private val labelCache = ConcurrentHashMap<String, String>()
    private val installedCacheMutex = Mutex()

    @Volatile
    private var installedCache: List<AppInfo>? = null

    override suspend fun getInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seenPackages = HashSet<String>()
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName
                if (!seenPackages.add(packageName)) return@mapNotNull null
                val label = resolveInfo.loadLabel(pm).toString()
                labelCache[packageName] = label
                AppInfo(
                    packageName = packageName,
                    label = label,
                    isSeen = false,
                    lastSeen = null,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    override fun observeCatalog(): Flow<List<AppInfo>> =
        seenChannelRepository.observeAll().map { channels -> buildCatalog(channels) }

    override suspend fun getAppInfo(packageName: String): AppInfo? {
        val lastSeen = seenChannelRepository.lastSeenForPackage(packageName)
        if (lastSeen == null && !isInstalled(packageName)) return null
        return AppInfo(
            packageName = packageName,
            label = getAppLabel(packageName),
            isSeen = lastSeen != null,
            lastSeen = lastSeen,
        )
    }

    override suspend fun getAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val label = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        }
        labelCache[packageName] = label
        return label
    }

    override fun observeChannelsForPackage(packageName: String): Flow<List<SeenChannel>> =
        seenChannelRepository.observeForPackage(packageName)

    override suspend fun getChannelsForPackage(packageName: String): List<SeenChannel> =
        seenChannelRepository.getForPackage(packageName)

    /** Union seen packages (with their latest lastSeen) with the cached installed-apps list. */
    private suspend fun buildCatalog(channels: List<SeenChannel>): List<AppInfo> {
        val lastSeenByPackage: Map<String, Long> = channels
            .groupBy { it.packageName }
            .mapValues { (_, list) -> list.maxOf { it.lastSeen } }

        val catalog = LinkedHashMap<String, AppInfo>()
        // Seen apps first, most-recent first.
        lastSeenByPackage.entries
            .sortedByDescending { it.value }
            .forEach { (packageName, lastSeen) ->
                catalog[packageName] = AppInfo(
                    packageName = packageName,
                    label = getAppLabel(packageName),
                    isSeen = true,
                    lastSeen = lastSeen,
                )
            }
        // Then installed apps not already present, tagged with seen state if applicable.
        cachedInstalledApps().forEach { app ->
            if (!catalog.containsKey(app.packageName)) {
                catalog[app.packageName] = app.copy(
                    isSeen = lastSeenByPackage.containsKey(app.packageName),
                    lastSeen = lastSeenByPackage[app.packageName],
                )
            }
        }
        return catalog.values.toList()
    }

    private suspend fun cachedInstalledApps(): List<AppInfo> {
        installedCache?.let { return it }
        return installedCacheMutex.withLock {
            installedCache ?: getInstalledApps().also { installedCache = it }
        }
    }

    private suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }
}
