package cl.detector.gas

internal object DemoDetector {
    fun read(status: DetectorStatus): Reading = when (status) {
        DetectorStatus.NORMAL -> Reading(status, 95, false)
        DetectorStatus.ALERT -> Reading(status, 535, true)
        DetectorStatus.TEST -> Reading(status, 95, true)
        DetectorStatus.WARMING -> Reading(status, 80, false)
        DetectorStatus.NO_DATA, DetectorStatus.OFFLINE, DetectorStatus.INVALID,
        DetectorStatus.TLS_ERROR, DetectorStatus.ACCESS_DENIED -> Reading(status)
    }
}
