package space.linuxct.teleforward.diag

/**
 * A single captured forensic record, already serialized to one NDJSON line (no embedded newlines).
 * Produced by [NotificationForensics.capture] and appended by [DiagStore].
 */
data class ForensicRecord(val jsonLine: String)

/**
 * Append-only NDJSON store for forensic records, living in app-private `filesDir/diag/records.ndjson`.
 * Mutex-guarded; size-capped (~5 MB) with oldest-drop rotation. All methods are best-effort and never
 * throw into the listener.
 */
interface DiagStore {

    /** Append [record] as one NDJSON line, rotating oldest lines when the size cap is exceeded. */
    suspend fun append(record: ForensicRecord)

    /** The whole NDJSON file as text (empty string when absent). */
    suspend fun readAllAsText(): String

    /** Number of stored records (non-blank lines). */
    suspend fun count(): Int

    /** Delete all stored records. */
    suspend fun clear()
}
