package cl.detector.gas

import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import java.io.InputStream
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

enum class DetectorStatus {
    NORMAL, ALERT, TEST, WARMING, NO_DATA, OFFLINE, INVALID, TLS_ERROR, ACCESS_DENIED
}

data class Reading(
    val status: DetectorStatus,
    val adc: Int? = null,
    val fanOrdered: Boolean? = null
)

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

internal fun parseReading(json: JsonObject): Reading {
    require(json.get("unidad")?.takeIf { it.isJsonPrimitive }?.asString == "ADC_0_1023")
    val connected = requireNotNull(json.boolean("conexion_arduino"))
    val state = json.get("estado")?.takeIf { it.isJsonPrimitive }?.asString
    val raw = requireNotNull(json.get("lectura"))
    val fanField = json.get("ventilador_ordenado")
    val fan = json.boolean("ventilador_ordenado")
    require(fanField == null || fanField.isJsonNull || fan != null)

    if (!connected) {
        require(state == "sin_datos" && raw.isJsonNull && fan == null)
        return Reading(DetectorStatus.NO_DATA)
    }
    val status = when (state) {
        "sin_alerta" -> DetectorStatus.NORMAL
        "alerta" -> DetectorStatus.ALERT
        "prueba" -> DetectorStatus.TEST
        "calentando" -> DetectorStatus.WARMING
        else -> throw IllegalArgumentException("Estado desconocido")
    }
    require(raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber)
    val adc = raw.asString.toIntOrNull()
    require(adc != null && adc in 0..1023)
    return Reading(status, adc, fan)
}

internal fun detectorUrl(input: String): HttpUrl {
    val address = input.trim()
    val raw = if ("://" in address) address else "https://$address"
    require('@' !in raw && '\\' !in raw)
    val url = requireNotNull(raw.toHttpUrlOrNull())
    require(url.scheme == "https" && url.port == 443)
    require(url.encodedPath == "/" && url.query == null && url.fragment == null)
    val parts = url.host.split('.')
    require(parts.size == 4)
    val octets = parts.map { part ->
        val value = requireNotNull(part.toIntOrNull())
        require(value in 0..255 && value.toString() == part)
        value
    }
    val (first, second) = octets
    require(first == 10 || first == 172 && second in 16..31 || first == 192 && second == 168)
    return url
}

private val keyFormat = Regex("[0-9a-fA-F]{32}")

internal fun detectorAuthorization(input: String): String {
    val key = input.trim()
    require(keyFormat.matches(key))
    return "Bearer ${key.lowercase()}"
}

internal interface DetectorApi {
    @Headers("Cache-Control: no-cache, no-store")
    @GET("estado")
    suspend fun read(@Header("Authorization") authorization: String): JsonObject
}

internal class WifiDetector(url: HttpUrl, private val authorization: String, certificate: InputStream) {
    // Esta confianza se limita al cliente del detector. OkHttp comprueba la IP del certificado.
    private val trustManager = detectorTrustManager(certificate)
    private val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    private val api = Retrofit.Builder()
        .baseUrl(url)
        .client(
            OkHttpClient.Builder()
                .sslSocketFactory(tls.socketFactory, trustManager)
                .callTimeout(5, TimeUnit.SECONDS)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(DetectorApi::class.java)

    suspend fun read() = parseReading(api.read(authorization))
}

internal fun detectorTrustManager(input: InputStream): X509TrustManager {
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
    certificate.checkValidity()
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("detector", certificate)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(store)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}
