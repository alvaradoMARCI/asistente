package com.nubiaagent.execution.skills.hardware_ctrl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Habilidad de lectura de sensores y ubicación para NubiaAgent.
 * Permite acceder al acelerómetro, giroscopio, proximidad, luz, contador de pasos
 * y ubicación GPS del dispositivo ZTE Nubia Neo 3 5G.
 */
class SensorLocationSkill(private val context: Context) {

    companion object {
        private const val TAG = "SensorLocationSkill"
        private const val LOCATION_TIMEOUT_MS = 15_000L
        private const val SENSOR_TIMEOUT_MS = 5_000L
        private const val MAX_GEODER_ATTEMPTS = 3

        // Nombres de sensores en español para los registros
        private val SENSOR_NAMES_ES = mapOf(
            Sensor.TYPE_ACCELEROMETER to "Acelerómetro",
            Sensor.TYPE_GYROSCOPE to "Giroscopio",
            Sensor.TYPE_PROXIMITY to "Sensor de Proximidad",
            Sensor.TYPE_LIGHT to "Sensor de Luz Ambiental",
            Sensor.TYPE_STEP_COUNTER to "Contador de Pasos",
            Sensor.TYPE_MAGNETIC_FIELD to "Campo Magnético",
            Sensor.TYPE_PRESSURE to "Barómetro",
            Sensor.TYPE_RELATIVE_HUMIDITY to "Humedad Relativa",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "Temperatura Ambiental"
        )

        private val SENSOR_TYPE_MAP = mapOf(
            "acelerometro" to Sensor.TYPE_ACCELEROMETER,
            "acelerómetro" to Sensor.TYPE_ACCELEROMETER,
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "giroscopio" to Sensor.TYPE_GYROSCOPE,
            "giroscopio" to Sensor.TYPE_GYROSCOPE,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "proximidad" to Sensor.TYPE_PROXIMITY,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "luz" to Sensor.TYPE_LIGHT,
            "luz_ambiental" to Sensor.TYPE_LIGHT,
            "light" to Sensor.TYPE_LIGHT,
            "pasos" to Sensor.TYPE_STEP_COUNTER,
            "contador_pasos" to Sensor.TYPE_STEP_COUNTER,
            "step_counter" to Sensor.TYPE_STEP_COUNTER,
            "campo_magnetico" to Sensor.TYPE_MAGNETIC_FIELD,
            "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "presion" to Sensor.TYPE_PRESSURE,
            "pressure" to Sensor.TYPE_PRESSURE
        )
    }

    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val geocoder: Geocoder by lazy {
        Geocoder(context, Locale("es", "ES"))
    }

    /**
     * Datos de ubicación con geocodificación inversa.
     */
    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val altitude: Double,
        val speed: Float,
        val address: String?
    )

    // ─── Lectura genérica de sensores ──────────────────────────────────────

    /**
     * Lee los valores de un sensor específico.
     * @param sensorType Nombre del sensor en español, inglés o tipo de Android.
     *                   Valores soportados: "acelerometro", "giroscopio", "proximidad",
     *                   "luz", "pasos", "campo_magnetico", "presion"
     * @return Result con mapa de nombres de ejes a valores flotantes.
     */
    fun readSensor(sensorType: String): Result<Map<String, Float>> {
        val normalizedType = sensorType.lowercase().trim().replace(" ", "_")
        val androidSensorType = SENSOR_TYPE_MAP[normalizedType]
            ?: return Result.failure(IllegalArgumentException(
                "Tipo de sensor desconocido: '$sensorType'. " +
                "Sensores disponibles: ${SENSOR_TYPE_MAP.keys.joinToString(", ")}"
            ))

        val sensor = sensorManager.getDefaultSensor(androidSensorType)
            ?: return Result.failure(IllegalStateException(
                "Sensor '${SENSOR_NAMES_ES[androidSensorType] ?: sensorType}' " +
                "no disponible en este dispositivo."
            ))

        val sensorNameEs = SENSOR_NAMES_ES[androidSensorType] ?: sensorType
        android.util.Log.i(TAG, "Leyendo sensor: $sensorNameEs")

        val latch = CountDownLatch(1)
        val sensorValues = AtomicReference<Map<String, Float>>(emptyMap())
        val errorRef = AtomicReference<Throwable?>(null)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = when (androidSensorType) {
                    Sensor.TYPE_ACCELEROMETER -> mapOf(
                        "eje_x" to event.values[0],
                        "eje_y" to event.values[1],
                        "eje_z" to event.values[2]
                    )
                    Sensor.TYPE_GYROSCOPE -> mapOf(
                        "velocidad_angular_x" to event.values[0],
                        "velocidad_angular_y" to event.values[1],
                        "velocidad_angular_z" to event.values[2]
                    )
                    Sensor.TYPE_PROXIMITY -> mapOf(
                        "distancia_cm" to event.values[0]
                    )
                    Sensor.TYPE_LIGHT -> mapOf(
                        "iluminancia_lux" to event.values[0]
                    )
                    Sensor.TYPE_STEP_COUNTER -> mapOf(
                        "total_pasos" to event.values[0]
                    )
                    Sensor.TYPE_MAGNETIC_FIELD -> mapOf(
                        "campo_x" to event.values[0],
                        "campo_y" to event.values[1],
                        "campo_z" to event.values[2]
                    )
                    Sensor.TYPE_PRESSURE -> mapOf(
                        "presion_hpa" to event.values[0]
                    )
                    else -> {
                        val map = mutableMapOf<String, Float>()
                        for (i in event.values.indices) {
                            if (i < 10) map["valor_$i"] = event.values[i]
                        }
                        map.toMap()
                    }
                }
                sensorValues.set(values)
                sensorManager.unregisterListener(this)
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                val accuracyName = when (accuracy) {
                    SensorManager.SENSOR_STATUS_NO_CONTACT -> "sin_contacto"
                    SensorManager.SENSOR_STATUS_UNRELIABLE -> "no_confiable"
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "baja"
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "media"
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "alta"
                    else -> "desconocida"
                }
                android.util.Log.d(TAG, "Precisión del $sensorNameEs cambiada a: $accuracyName")
            }
        }

        val registered = sensorManager.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_UI
        )

        if (!registered) {
            return Result.failure(IllegalStateException(
                "No se pudo registrar el listener para el sensor $sensorNameEs."
            ))
        }

        val awaited = latch.await(SENSOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!awaited) {
            sensorManager.unregisterListener(listener)
            return Result.failure(IllegalStateException(
                "Tiempo de espera agotado leyendo el sensor $sensorNameEs."
            ))
        }

        val result = sensorValues.get()
        android.util.Log.i(TAG, "Sensor $sensorNameEs leído correctamente: $result")
        return Result.success(result)
    }

    // ─── Lectura de todos los sensores ─────────────────────────────────────

    /**
     * Lee todos los sensores disponibles y devuelve un mapa con sus valores.
     */
    fun readAllSensors(): Result<Map<String, Map<String, Float>>> {
        val allResults = mutableMapOf<String, Map<String, Float>>()
        val sensorTypes = listOf(
            "acelerometro", "giroscopio", "proximidad", "luz", "pasos"
        )

        for (type in sensorTypes) {
            readSensor(type).onSuccess { values ->
                allResults[type] = values
            }
        }

        return if (allResults.isNotEmpty()) {
            Result.success(allResults)
        } else {
            Result.failure(IllegalStateException("No se pudo leer ningún sensor del dispositivo."))
        }
    }

    // ─── Ubicación GPS ────────────────────────────────────────────────────

    /**
     * Obtiene la ubicación actual del dispositivo usando FusedLocationProviderClient.
     * @param precise Si es true, usa GPS de alta precisión; si no, usa ubicación aproximada.
     */
    suspend fun getLocation(precise: Boolean = true): Result<LocationData> {
        return try {
            val locationResult = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<android.location.Location> { cont ->
                    val priority = if (precise) {
                        Priority.PRIORITY_HIGH_ACCURACY
                    } else {
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY
                    }

                    val locationRequest = LocationRequest.Builder(priority, 1L)
                        .setMaxUpdates(1)
                        .setWaitForAccurateLocation(precise)
                        .build()

                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            result.lastLocation?.let { location ->
                                fusedLocationClient.removeLocationUpdates(this)
                                if (cont.isActive) {
                                    cont.resume(location)
                                }
                            }
                        }
                    }

                    try {
                        @Suppress("MissingPermission")
                        fusedLocationClient.requestLocationUpdates(
                            locationRequest, callback, Looper.getMainLooper()
                        )
                    } catch (e: SecurityException) {
                        if (cont.isActive) {
                            cont.resumeWithException(
                                SecurityException("Permiso de ubicación no concedido.")
                            )
                        }
                    }

                    cont.invokeOnCancellation {
                        fusedLocationClient.removeLocationUpdates(callback)
                    }
                }
            }

            if (locationResult == null) {
                // Intentar obtener la última ubicación conocida como respaldo
                return getLastKnownLocation()
            }

            val address = reverseGeocode(locationResult.latitude, locationResult.longitude)

            val locationData = LocationData(
                latitude = locationResult.latitude,
                longitude = locationResult.longitude,
                accuracy = locationResult.accuracy,
                altitude = if (locationResult.hasAltitude()) locationResult.altitude else 0.0,
                speed = if (locationResult.hasSpeed()) locationResult.speed else 0f,
                address = address
            )

            android.util.Log.i(
                TAG,
                "Ubicación obtenida: lat=${locationData.latitude}, " +
                "lon=${locationData.longitude}, precisión=${locationData.accuracy}m" +
                (locationData.address?.let { ", dirección=$it" } ?: "")
            )

            Result.success(locationData)
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Permiso de ubicación no concedido: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Obtiene la última ubicación conocida como alternativa.
     */
    private suspend fun getLastKnownLocation(): Result<LocationData> {
        return try {
            val location = withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    try {
                        @Suppress("MissingPermission")
                        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                            if (cont.isActive) cont.resume(loc)
                        }.addOnFailureListener { e ->
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    } catch (e: SecurityException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }

            if (location == null) {
                return Result.failure(IllegalStateException(
                    "No se pudo obtener la ubicación. Verifique que el GPS esté activado " +
                    "y los permisos concedidos."
                ))
            }

            val address = reverseGeocode(location.latitude, location.longitude)

            Result.success(LocationData(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                speed = if (location.hasSpeed()) location.speed else 0f,
                address = address
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Geocodificación inversa: convierte coordenadas en dirección legible.
     */
    private fun reverseGeocode(latitude: Double, longitude: Double): String? {
        var attempt = 0
        while (attempt < MAX_GEODER_ATTEMPTS) {
            try {
                val addresses: MutableList<Address>? = geocoder.getFromLocation(latitude, longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val parts = mutableListOf<String>()
                    addr.thoroughfare?.let { parts.add(it) }
                    addr.subLocality?.let { parts.add(it) }
                    addr.locality?.let { parts.add(it) }
                    addr.adminArea?.let { parts.add(it) }
                    addr.countryName?.let { parts.add(it) }
                    return if (parts.isNotEmpty()) parts.joinToString(", ") else null
                }
                return null
            } catch (e: IOException) {
                attempt++
                android.util.Log.w(TAG, "Error de geocodificación inversa (intento ${attempt + 1}): ${e.message}")
                if (attempt >= MAX_GEODER_ATTEMPTS) return null
                Thread.sleep(500L * attempt)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error inesperado en geocodificación inversa: ${e.message}")
                return null
            }
        }
        return null
    }

    // ─── Métodos de conveniencia para sensores específicos ─────────────────

    /**
     * Lee el contador de pasos del sensor de hardware.
     * @return Result con el total de pasos desde el último reinicio del dispositivo.
     */
    fun getStepCount(): Result<Long> {
        val result = readSensor("pasos")
        return result.map { values ->
            val steps = values["total_pasos"]?.toLong()
                ?: return Result.failure(IllegalStateException(
                    "No se pudo leer el valor del contador de pasos."
                ))
            android.util.Log.i(TAG, "Contador de pasos: $steps pasos")
            steps
        }
    }

    /**
     * Lee el sensor de proximidad.
     * @return Result con la distancia al objeto más cercano en cm.
     *         Típicamente 0.0 (cerca) o 5.0 (lejos) en la mayoría de dispositivos.
     */
    fun getProximity(): Result<Float> {
        val result = readSensor("proximidad")
        return result.map { values ->
            val distance = values["distancia_cm"]
                ?: return Result.failure(IllegalStateException(
                    "No se pudo leer el valor del sensor de proximidad."
                ))
            val estado = if (distance < 1.0f) "objeto cercano" else "sin objeto cercano"
            android.util.Log.i(TAG, "Sensor de proximidad: ${distance}cm ($estado)")
            distance
        }
    }

    /**
     * Lee el sensor de luz ambiental.
     * @return Result con la iluminancia en lux.
     */
    fun getAmbientLight(): Result<Float> {
        val result = readSensor("luz")
        return result.map { values ->
            val lux = values["iluminancia_lux"]
                ?: return Result.failure(IllegalStateException(
                    "No se pudo leer el valor del sensor de luz ambiental."
                ))
            val descripcion = when {
                lux < 1f -> "oscuridad total"
                lux < 50f -> "interior tenue"
                lux < 200f -> "interior normal"
                lux < 500f -> "interior iluminado"
                lux < 1000f -> "exterior nublado"
                else -> "luz solar directa"
            }
            android.util.Log.i(TAG, "Sensor de luz: ${lux}lux ($descripcion)")
            lux
        }
    }

    /**
     * Lee el acelerómetro y devuelve los valores de los tres ejes.
     */
    fun getAccelerometer(): Result<Map<String, Float>> {
        return readSensor("acelerometro")
    }

    /**
     * Lee el giroscopio y devuelve las velocidades angulares.
     */
    fun getGyroscope(): Result<Map<String, Float>> {
        return readSensor("giroscopio")
    }

    /**
     * Verifica qué sensores están disponibles en el dispositivo.
     * @return Mapa con nombre en español y disponibilidad (true/false).
     */
    fun checkAvailableSensors(): Map<String, Boolean> {
        val sensors = mapOf(
            "Acelerómetro" to Sensor.TYPE_ACCELEROMETER,
            "Giroscopio" to Sensor.TYPE_GYROSCOPE,
            "Proximidad" to Sensor.TYPE_PROXIMITY,
            "Luz Ambiental" to Sensor.TYPE_LIGHT,
            "Contador de Pasos" to Sensor.TYPE_STEP_COUNTER,
            "Campo Magnético" to Sensor.TYPE_MAGNETIC_FIELD,
            "Barómetro" to Sensor.TYPE_PRESSURE
        )

        return sensors.mapValues { (_, type) ->
            sensorManager.getDefaultSensor(type) != null
        }
    }

    /**
     * Devuelve un resumen en español del estado de todos los sensores y la ubicación.
     */
    suspend fun getStatusSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("📋 Estado de Sensores y Ubicación")
        sb.appendLine("═══════════════════════════════════")

        val available = checkAvailableSensors()
        sb.appendLine("\n🔌 Sensores disponibles:")
        available.forEach { (name, available) ->
            val icon = if (available) "✅" else "❌"
            sb.appendLine("  $icon $name")
        }

        // Intentar leer algunos sensores clave
        sb.appendLine("\n📊 Lecturas actuales:")

        getAmbientLight().onSuccess { lux ->
            val desc = when {
                lux < 1f -> "oscuridad"
                lux < 50f -> "tenue"
                lux < 200f -> "normal"
                lux < 500f -> "iluminado"
                else -> "luz solar"
            }
            sb.appendLine("  💡 Luz: %.1f lux (%s)".format(lux, desc))
        }.onFailure {
            sb.appendLine("  💡 Luz: no disponible")
        }

        getProximity().onSuccess { distance ->
            val desc = if (distance < 1.0f) "objeto cercano" else "libre"
            sb.appendLine("  📏 Proximidad: %.1f cm (%s)".format(distance, desc))
        }.onFailure {
            sb.appendLine("  📏 Proximidad: no disponible")
        }

        getStepCount().onSuccess { steps ->
            sb.appendLine("  🚶 Pasos: $steps")
        }.onFailure {
            sb.appendLine("  🚶 Pasos: no disponible")
        }

        getAccelerometer().onSuccess { values ->
            sb.appendLine(
                "  📐 Acelerómetro: X=%.2f, Y=%.2f, Z=%.2f m/s²".format(
                    values["eje_x"] ?: 0f,
                    values["eje_y"] ?: 0f,
                    values["eje_z"] ?: 0f
                )
            )
        }.onFailure {
            sb.appendLine("  📐 Acelerómetro: no disponible")
        }

        // Ubicación
        sb.appendLine("\n📍 Ubicación:")
        getLocation(precise = false).onSuccess { loc ->
            sb.appendLine("  Latitud: %.6f".format(loc.latitude))
            sb.appendLine("  Longitud: %.6f".format(loc.longitude))
            sb.appendLine("  Precisión: %.1f m".format(loc.accuracy))
            if (loc.address != null) {
                sb.appendLine("  Dirección: ${loc.address}")
            }
        }.onFailure { e ->
            sb.appendLine("  ❌ No disponible: ${e.message}")
        }

        return sb.toString()
    }
}
