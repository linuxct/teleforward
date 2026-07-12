package space.linuxct.teleforward.diag

import android.content.Intent

/**
 * Assembles the diagnostics dump (env header + NDJSON records + own-process logcat) into a single
 * file under `filesDir/diag/`, exposed through the FileProvider, and returns a ready-to-fire
 * `ACTION_SEND` chooser [Intent]. The EXPORTER does not start the activity — the Settings UI does
 * (via its existing `launchSafely` pattern), so it can add `FLAG_ACTIVITY_NEW_TASK`/handle failures.
 */
interface DiagExporter {

    /**
     * Build the dump file and return an `Intent.createChooser(ACTION_SEND, …)` carrying a
     * `content://` URI (with `FLAG_GRANT_READ_URI_PERMISSION`). Runs off the main thread.
     */
    suspend fun exportToShareIntent(): Intent
}
