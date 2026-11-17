package cl.detector.gas

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudSyncTest {
    @Test fun onlyAcknowledgedRowsLeaveTheQueue() = runBlocking {
        val marked = mutableListOf<Long>()
        val rows = listOf(row(1), row(2))
        assertFalse(uploadBatch(rows, send = { false }, acknowledge = { marked.addAll(it) }))
        assertTrue(marked.isEmpty())
        assertTrue(uploadBatch(rows, send = { true }, acknowledge = { marked.addAll(it) }))
        assertEquals(listOf(1L, 2L), marked)
    }

    @Test fun retryUsesTheSameDocumentAndOnlySendsEncryptedFields() {
        val body = firestoreCommit("test-project", "test-uid", listOf(row(1)))
        val update = body.getAsJsonArray("writes")[0].asJsonObject.getAsJsonObject("update")
        assertTrue(update["name"].asString.startsWith("projects/test-project/databases/(default)/documents/users/test-uid/readings/"))
        val fields = update.getAsJsonObject("fields")
        assertEquals(setOf("payload", "nonce"), fields.keySet())
        assertEquals("AwQ=", fields.getAsJsonObject("payload")["bytesValue"].asString)
        assertEquals(body, firestoreCommit("test-project", "test-uid", listOf(row(99))))
    }

    @Test fun reconnectionBackoffReturnsToNormalAfterAResponse() {
        var wait = 1000L
        repeat(5) { wait = nextDetectorDelay(wait, DetectorStatus.OFFLINE) }
        assertEquals(8000L, wait)
        assertEquals(1000L, nextDetectorDelay(wait, DetectorStatus.NO_DATA))
        assertEquals(1000L, nextDetectorDelay(wait, DetectorStatus.NORMAL))
    }

    private fun row(id: Long) = HistoryRow("owner", byteArrayOf(3, 4), byteArrayOf(1, 2)).apply { this.id = id }
}
