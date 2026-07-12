package space.linuxct.teleforward.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.BuildConfig
import space.linuxct.teleforward.di.IoDispatcher
import space.linuxct.teleforward.util.NotificationAccess
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DiagExporter] impl. Assembles one text file = env header + `--- RECORDS (NDJSON) ---` + records +
 * best-effort own-process logcat (`logcat -d --pid=<self>`), then returns a share chooser Intent
 * backed by the FileProvider content URI.
 */
@Singleton
class DiagExporterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val diagStore: DiagStore,
) : DiagExporter {

    override suspend fun exportToShareIntent(): Intent = withContext(io) {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "teleforward-diag-$ts.txt")

        val records = diagStore.readAllAsText()
        val count = diagStore.count()

        file.writeText(
            buildString {
                append(header(count, ts))
                append("\n\n--- RECORDS (NDJSON) ---\n")
                append(records)
                append("\n\n--- LOGCAT (own process) ---\n")
                append(ownLogcat())
                append('\n')
            },
        )

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "TeleForward diagnostics $ts")
            clipData = android.content.ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, "Share TeleForward diagnostics").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun header(count: Int, ts: String): String = buildString {
        append("=== TeleForward diagnostics ===\n")
        append("WARNING: records full notification content — for debugging only; share only with people you trust.\n")
        append("generatedAt: $ts\n")
        append("recordCount: $count\n")
        append("manufacturer: ${Build.MANUFACTURER}\n")
        append("model: ${Build.MODEL}\n")
        append("product: ${Build.PRODUCT}\n")
        append("fingerprint: ${Build.FINGERPRINT}\n")
        append("androidRelease: ${Build.VERSION.RELEASE}\n")
        append("sdkInt: ${Build.VERSION.SDK_INT}\n")
        append("securityPatch: ${securityPatch()}\n")
        append("locale: ${Locale.getDefault()}\n")
        append("app: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}\n")
        append("notificationAccessGranted: ${runCatching { NotificationAccess.isEnabled(context) }.getOrDefault(false)}\n")
    }

    private fun securityPatch(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "n/a"

    /** Legitimately readable own-process logcat (our probe logs + swallowed exceptions). */
    private fun ownLogcat(): String = runCatching {
        val pid = Process.myPid()
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        runCatching { proc.destroy() }
        out.ifBlank { "(no own-process logcat lines)" }
    }.getOrElse { "logcat unavailable: ${it.javaClass.simpleName}: ${it.message}" }
}
