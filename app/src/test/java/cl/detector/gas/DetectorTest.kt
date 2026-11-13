package cl.detector.gas

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DetectorTest {
    @Test fun compatibleWithCurrentFirmware() {
        val reading = parse("""{"lectura":95,"estado":"sin_alerta","conexion_arduino":true,"unidad":"ADC_0_1023"}""")
        assertEquals(DetectorStatus.NORMAL, reading.status)
        assertEquals(95, reading.adc)
        assertNull(reading.fanOrdered)
    }

    @Test fun reportsExplicitFanOrder() {
        val reading = parse("""{"lectura":520,"estado":"alerta","conexion_arduino":true,"unidad":"ADC_0_1023","ventilador_ordenado":true}""")
        assertEquals(true, reading.fanOrdered)
    }

    @Test fun acceptsStaleLinkWithoutReusingOldReading() {
        val reading = parse("""{"lectura":null,"estado":"sin_datos","conexion_arduino":false,"unidad":"ADC_0_1023","ventilador_ordenado":null}""")
        assertEquals(DetectorStatus.NO_DATA, reading.status)
        assertNull(reading.adc)
    }

    @Test fun rejectsInvalidOrContradictoryReadings() {
        listOf(
            """{"lectura":95,"estado":"sin_datos","conexion_arduino":false,"unidad":"ADC_0_1023"}""",
            """{"lectura":1024,"estado":"alerta","conexion_arduino":true,"unidad":"ADC_0_1023"}""",
            """{"lectura":"95","estado":"prueba","conexion_arduino":true,"unidad":"ADC_0_1023"}""",
            """{"lectura":95.5,"estado":"prueba","conexion_arduino":true,"unidad":"ADC_0_1023"}""",
            """{"lectura":95,"estado":"otro","conexion_arduino":true,"unidad":"ADC_0_1023"}""",
            """{"lectura":95,"estado":"prueba","conexion_arduino":true,"unidad":"ppm"}""",
            """{"lectura":95,"estado":"prueba","unidad":"ADC_0_1023"}""",
            """{"lectura":95,"estado":"prueba","conexion_arduino":true,"unidad":"ADC_0_1023","ventilador_ordenado":"true"}"""
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { parse(json) }
        }
    }

    @Test fun requiresHttpsAndPrivateIpv4() {
        assertEquals("https://192.168.1.50/", detectorUrl("192.168.1.50").toString())
        assertEquals("https://172.16.1.5/", detectorUrl("https://172.16.1.5").toString())
        listOf("8.8.8.8", "http://192.168.1.50", "192.168.1.50.example", "192.168.0.1/otro", "10.0.0.1:8080").forEach {
            assertThrows(IllegalArgumentException::class.java) { detectorUrl(it) }
        }
    }

    @Test fun detectorKeyMustContainExactly32HexCharacters() {
        val key = "0123456789abcdef0123456789abcdef"
        assertEquals("Bearer $key", detectorAuthorization(" $key "))
        listOf("", key.dropLast(1), key.replace('0', 'g'), "$key\nextra").forEach {
            assertThrows(IllegalArgumentException::class.java) { detectorAuthorization(it) }
        }
    }

    private fun parse(json: String) = parseReading(JsonParser.parseString(json).asJsonObject)
}
