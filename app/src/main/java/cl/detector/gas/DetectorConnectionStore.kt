package cl.detector.gas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class DetectorConnection(address: String, accessKey: String) {
    val address = detectorUrl(address).host
    val accessKey = detectorAuthorization(accessKey).removePrefix("Bearer ")
}

internal fun Reading.confirmsDetectorConnection(): Boolean = when (status) {
    DetectorStatus.NORMAL, DetectorStatus.ALERT, DetectorStatus.TEST,
    DetectorStatus.WARMING, DetectorStatus.NO_DATA -> true
    else -> false
}

internal class DetectorConnectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("detector_connections", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val alias = "detector-connection-v1"
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

    suspend fun save(uid: String, connection: DetectorConnection) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(uid.encodeToByteArray())
        val value = "${connection.address}\n${connection.accessKey}".encodeToByteArray()
        val nonce = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(value), Base64.NO_WRAP)
        if (!preferences.edit().putString(uid, "$nonce:$encrypted").commit()) {
            throw IOException("No se pudo guardar la conexión")
        }
    }

    suspend fun load(uid: String): DetectorConnection? = withContext(Dispatchers.IO) {
        val value = preferences.getString(uid, null) ?: return@withContext null
        val (nonce, encrypted) = value.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(nonce, Base64.NO_WRAP)))
        cipher.updateAAD(uid.encodeToByteArray())
        val (address, accessKey) = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            .decodeToString().split('\n', limit = 2)
        DetectorConnection(address, accessKey)
    }
}
