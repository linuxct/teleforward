package space.linuxct.teleforward.diag

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.di.IoDispatcher
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NDJSON [DiagStore] backed by `filesDir/diag/records.ndjson`. A [Mutex] serializes all file access
 * (appends run on the listener's IO scope). When the file exceeds [MAX_BYTES] it is rotated by
 * dropping the oldest lines, keeping the newest that fit into half the cap.
 */
@Singleton
class DiagStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DiagStore {

    private val mutex = Mutex()
    private val dir: File get() = File(context.filesDir, DIR)
    private val file: File get() = File(dir, FILE)

    override suspend fun append(record: ForensicRecord) {
        withContext(io) {
            mutex.withLock {
                runCatching {
                    dir.mkdirs()
                    val line = record.jsonLine.replace('\n', ' ').replace('\r', ' ') + "\n"
                    FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
                    if (file.length() > MAX_BYTES) rotate()
                }
            }
        }
    }

    override suspend fun readAllAsText(): String = withContext(io) {
        mutex.withLock {
            runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        }
    }

    override suspend fun count(): Int = withContext(io) {
        mutex.withLock {
            runCatching {
                if (!file.exists()) 0 else file.useLines { seq -> seq.count { it.isNotBlank() } }
            }.getOrDefault(0)
        }
    }

    override suspend fun clear() {
        withContext(io) {
            mutex.withLock { runCatching { if (file.exists()) file.delete() } }
        }
    }

    /** Keep the newest lines that fit into half the cap; drop the rest. Best-effort. */
    private fun rotate() {
        runCatching {
            val lines = file.readLines()
            val kept = ArrayDeque<String>()
            var size = 0L
            for (l in lines.asReversed()) {
                if (l.isBlank()) continue
                val add = l.toByteArray(Charsets.UTF_8).size + 1
                if (size + add > MAX_BYTES / 2) break
                kept.addFirst(l)
                size += add
            }
            file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
        }
    }

    private companion object {
        const val DIR = "diag"
        const val FILE = "records.ndjson"
        const val MAX_BYTES = 5L * 1024 * 1024
    }
}
