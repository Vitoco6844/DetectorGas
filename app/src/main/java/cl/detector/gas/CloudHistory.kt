package cl.detector.gas

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okio.ByteString.Companion.toByteString
import java.security.MessageDigest

data class CloudCounts(val pending: Int, val uploaded: Int)

internal fun firestoreCommit(project: String, uid: String, rows: List<HistoryRow>): JsonObject =
    JsonObject().apply {
        add("writes", JsonArray().apply {
            rows.forEach { row ->
                val hash = MessageDigest.getInstance("SHA-256").apply { update(row.nonce) }
                    .digest(row.payload).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val name = "projects/$project/databases/(default)/documents/users/$uid/readings/$hash"
                add(JsonObject().apply {
                    add("update", JsonObject().apply {
                        addProperty("name", name)
                        add("fields", JsonObject().apply {
                            add("payload", firestoreBytes(row.payload))
                            add("nonce", firestoreBytes(row.nonce))
                        })
                    })
                })
            }
        })
    }

private fun firestoreBytes(bytes: ByteArray): JsonObject = JsonObject().apply {
    addProperty("bytesValue", bytes.toByteString().base64())
}

internal suspend fun uploadBatch(
    rows: List<HistoryRow>,
    send: suspend (List<HistoryRow>) -> Boolean,
    acknowledge: suspend (List<Long>) -> Unit
): Boolean {
    if (!send(rows)) return false
    acknowledge(rows.map { it.id })
    return true
}

internal fun nextDetectorDelay(current: Long, status: DetectorStatus): Long =
    if (status == DetectorStatus.OFFLINE) (current * 2).coerceAtMost(8_000) else 1_000
