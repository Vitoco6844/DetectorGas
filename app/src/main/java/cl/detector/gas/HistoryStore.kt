package cl.detector.gas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedReading(val id: Long, val receivedAt: Long, val reading: Reading)

internal class HistoryStore(context: Context) {
    private val database = Room.databaseBuilder(context, HistoryDatabase::class.java, "detector.db").build()
    private val key: SecretKey by lazy {
        val alias = "detector-history-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    suspend fun save(owner: String, receivedAt: Long, reading: Reading) = withContext(Dispatchers.IO) {
        val adc = requireNotNull(reading.adc)
        val account = ownerHash(owner)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(account.encodeToByteArray())
        val data = "$receivedAt|${reading.status.name}|$adc|${reading.fanOrdered}".encodeToByteArray()
        database.history().insert(HistoryRow(account, cipher.doFinal(data), cipher.iv))
    }

    suspend fun latest(owner: String): List<SavedReading> = withContext(Dispatchers.IO) {
        val account = ownerHash(owner)
        database.history().latest(account).map { row ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, row.nonce))
            cipher.updateAAD(account.encodeToByteArray())
            val (time, status, adc, fan) = cipher.doFinal(row.payload).decodeToString().split('|')
            SavedReading(row.id, time.toLong(), Reading(DetectorStatus.valueOf(status), adc.toInt(), fan.toBooleanStrictOrNull()))
        }
    }

    private fun ownerHash(owner: String): String = MessageDigest.getInstance("SHA-256")
        .digest(owner.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
