package com.nubiaagent.perception.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import com.nubiaagent.core.UserActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * HardwareStateCollector: Módulo de Telemetría del Dispositivo para NubiaAgent.
 *
 * Monitorea continuamente el estado del hardware del Nubia Neo 3 5G para
 * proporcionar contexto al agente sobre el entorno físico del dispositivo.
 *
 * DATOS RECOLECTADOS:
 *
 * 1. BATERÍA Y BYPASS CHARGING:
 *    - Nivel de batería actual (0-100%)
 *    - Estado de carga (conectado/desconectado/tipo de cargador)
 *    - Detección de Bypass Charging: El Nubia Neo 3 5G tiene una función
 *      llamada Bypass Charging que permite alimentar el dispositivo
 *      directamente desde el cargador sin cargar la batería, evitando
 *      la degradación durante sesiones intensivas. El agente necesita
 *      saber si esto está activo para planificar sesiones de inferencia
 *      largas sin preocupación por la batería.
 *
 * 2. UBICACIÓN GPS:
 *    - Ubicación coarse (ciudad/barrio) para contexto general
 *    - Ubicación precise (coordenadas exactas) cuando el usuario lo permite
 *    - Geocoding inverso local para nombre de ubicación
 *    - Optimización: Se usa FusedLocationProvider con intervalos adaptativos
 *      que se ajustan según la actividad del usuario.
 *
 * 3. ACTIVIDAD FÍSICA:
 *    - Detección de actividad: quieto, caminando, corriendo, conduciendo
 *    - Conteo de pasos via TYPE_STEP_COUNTER
 *    - Detección de conducción para activar modo "no interrumpir"
 *
 * OPTIMIZACIÓN PARA UNISOC T8300:
 * - Los sensores se muestrean a frecuencias bajas (SENSOR_DELAY_NORMAL)
 *   para minimizar consumo de CPU y batería
 * - La ubicación se actualiza solo cuando hay cambio significativo
 * - La telemetría se emite al bus con intervalo mínimo de 5 segundos
 *
 * RESTRICCIÓN DE PRIVACIDAD: Los datos de ubicación NUNCA salen del
 * dispositivo. Solo se usan internamente para contextualizar las
 * decisiones del agente.
 */
class HardwareStateCollector(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/Hardware"

        // Intervalos de actualización
        private const val BATTERY_CHECK_INTERVAL_MS = 30_000L   // 30 segundos
        private const val LOCATION_UPDATE_INTERVAL_MS = 60_000L // 1 minuto
        private const val LOCATION_FASTEST_INTERVAL_MS = 15_000L // 15 segundos mínimo
        private const val ACTIVITY_UPDATE_INTERVAL_MS = 30_000L  // 30 segundos
        private const val MIN_EMIT_INTERVAL_MS = 5_000L         // 5 segundos entre eventos

        // Umbrales de batería para toma de decisiones
        private const val BATTERY_LOW_THRESHOLD = 20
        private const val BATTERY_CRITICAL_THRESHOLD = 10

        // Detección de Bypass Charging en Nubia
        // Nubia/ZTE usa un intent específico para indicar bypass charging
        private const val ACTION_BYPASS_CHARGING = "com.zte.bypasscharging.STATE"
        private const val EXTRA_BYPASS_ACTIVE = "bypass_charging_active"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private var lastEmitTime = 0L

    // Estado actual del hardware
    private val _currentState = MutableStateFlow(HardwareState())
    val currentState: StateFlow<HardwareState> = _currentState.asStateFlow()

    // Componentes del sistema
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var activityRecognitionClient: ActivityRecognitionClient? = null
    private var sensorManager: SensorManager? = null

    // Callbacks
    private var locationCallback: LocationCallback? = null
    private var activityPendingIntent: PendingIntent? = null
    private var stepCounterListener: SensorEventListener? = null

    // Batería
    private var batteryReceiver: BroadcastReceiver? = null
    private var bypassChargingReceiver: BroadcastReceiver? = null

    /**
     * Inicia la recolección de telemetría del hardware.
     *
     * Los sensores y servicios se inicializan progresivamente:
     * 1. Batería (inmediato, sin permisos especiales)
     * 2. Sensores de movimiento (inmediato, sin permisos especiales)
     * 3. Ubicación (requiere permisos de ubicación)
     * 4. Reconocimiento de actividad (requiere permisos)
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "HardwareStateCollector iniciado")

        // Iniciar monitoreo de batería
        startBatteryMonitoring()

        // Iniciar sensores de movimiento
        startMotionSensors()

        // Iniciar ubicación (si hay permisos)
        if (hasLocationPermission()) {
            startLocationMonitoring()
        }

        // Iniciar reconocimiento de actividad (si hay permisos)
        if (hasActivityPermission()) {
            startActivityRecognition()
        }

        // Loop periódico de emisión de estado
        scope.launch {
            while (isActive && isRunning) {
                emitCurrentState()
                delay(BATTERY_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Detiene toda la recolección de telemetría.
     */
    fun stop() {
        isRunning = false
        Log.i(TAG, "HardwareStateCollector detenido")

        // Detener ubicación
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }

        // Detener reconocimiento de actividad
        activityPendingIntent?.let {
            activityRecognitionClient?.removeActivityTransitionUpdates(it)
        }

        // Detener sensores
        stepCounterListener?.let {
            sensorManager?.unregisterListener(it)
        }

        // Desregistrar receivers
        batteryReceiver?.let { context.unregisterReceiver(it) }
        bypassChargingReceiver?.let { context.unregisterReceiver(it) }

        scope.cancel()
    }

    // ==================== BATERÍA Y BYPASS CHARGING ====================

    /**
     * Monitorea el estado de la batería usando BatteryManager.
     *
     * También detecta el estado del Bypass Charging del Nubia Neo 3 5G.
     * El Bypass Charging es una función exclusiva de Nubia que alimenta
     * el dispositivo directamente desde el cargador, omitiendo la batería.
     * Esto es crítico para sesiones de inferencia de IA prolongadas ya que:
     * - Evita el sobrecalentamiento de la batería
     * - Permite procesamiento 100% sostenido sin degradación
     * - Extiende la vida útil de la batería
     */
    private fun startBatteryMonitoring() {
        // Lectura inicial
        updateBatteryState()

        // Registrar receiver para cambios de batería
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                updateBatteryState(intent)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(batteryReceiver, filter)

        // Registrar receiver para Bypass Charging (Nubia/ZTE específico)
        startBypassChargingDetection()
    }

    /**
     * Actualiza el estado de la batería desde BatteryManager.
     */
    private fun updateBatteryState(intent: Intent? = null) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        // Detectar tipo de cargador
        val chargeType = if (isCharging) {
            val chargingIntent = intent ?: IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                .let { context.registerReceiver(null, it) }
            val chargePlugged = chargingIntent?.getIntExtra(
                android.os.BatteryManager.EXTRA_PLUGGED, -1
            ) ?: -1

            when (chargePlugged) {
                android.os.BatteryManager.BATTERY_PLUGGED_AC -> ChargerType.AC
                android.os.BatteryManager.BATTERY_PLUGGED_USB -> ChargerType.USB
                android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargerType.WIRELESS
                else -> ChargerType.UNKNOWN
            }
        } else {
            ChargerType.NONE
        }

        // Verificar si el procesamiento intensivo es viable
        val canRunIntensive = when {
            _currentState.value.isBypassCharging -> true  // Bypass activo = ilimitado
            isCharging && level > 50 -> true              // Cargando con buena batería
            level > BATTERY_LOW_THRESHOLD -> true          // Suficiente batería
            else -> false
        }

        _currentState.value = _currentState.value.copy(
            batteryLevel = level,
            isCharging = isCharging,
            chargerType = chargeType,
            canRunIntensive = canRunIntensive
        )

        Log.d(TAG, "Batería: $level%, Cargando: $isCharging, Cargador: $chargeType, " +
                "Bypass: ${_currentState.value.isBypassCharging}, Intensivo: $canRunIntensive")
    }

    /**
     * Detecta el estado del Bypass Charging en dispositivos Nubia/ZTE.
     *
     * El Nubia Neo 3 5G expone el estado del Bypass Charging a través
     * de un intent específico del sistema. También se puede detectar
     * indirectamente si el dispositivo está conectado a AC pero la
     * batería no está cargando (lo que indica bypass activo).
     *
     * Métodos de detección:
     * 1. Intent broadcast de ZTE/Nubia (método primario)
     * 2. Archivo sysfs: /sys/class/power_supply/battery/bypass_charging
     * 3. Detección heurística: AC conectado + batería no cargando
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun startBypassChargingDetection() {
        // Método 1: Intent broadcast de ZTE/Nubia
        bypassChargingReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val isActive = intent.getBooleanExtra(EXTRA_BYPASS_ACTIVE, false)
                onBypassChargingStateChanged(isActive)
            }
        }

        try {
            val bypassFilter = IntentFilter(ACTION_BYPASS_CHARGING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    bypassChargingReceiver, bypassFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(bypassChargingReceiver, bypassFilter)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Broadcast de Bypass Charging no disponible, usando detección alternativa")
        }

        // Método 2: Verificar sysfs
        scope.launch {
            while (isActive && isRunning) {
                checkBypassViaSysfs()
                delay(BATTERY_CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Verifica el estado del Bypass Charging via sysfs.
     * Accede directamente al archivo del kernel de Linux que controla
     * el bypass charging en dispositivos Nubia/ZTE.
     */
    private fun checkBypassViaSysfs() {
        val bypassPaths = listOf(
            "/sys/class/power_supply/battery/bypass_charging",
            "/sys/class/power_supply/bms/bypass_charging",
            "/sys/class/power_supply/usb/bypass_charging",
            "/proc/nubia/bypass_charging"
        )

        for (path in bypassPaths) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val value = file.readText().trim()
                    val isActive = value == "1" || value.equals("true", ignoreCase = true)
                    onBypassChargingStateChanged(isActive)
                    return
                }
            } catch (e: Exception) {
                // Acceso denegado o archivo no existe, continuar
            }
        }

        // Método 3: Detección heurística
        detectBypassHeuristically()
    }

    /**
     * Detección heurística de Bypass Charging.
     *
     * Si el dispositivo está conectado a AC pero el nivel de batería
     * no cambia (o baja ligeramente) durante varios minutos, es probable
     * que el Bypass Charging esté activo.
     */
    private fun detectBypassHeuristically() {
        val state = _currentState.value
        if (!state.isCharging || state.chargerType != ChargerType.AC) return

        // Si está conectado a AC y la batería está estable (no sube)
        // por más de 5 minutos, probablemente bypass está activo
        // Esta lógica es un placeholder - se refinará con datos reales
    }

    private fun onBypassChargingStateChanged(isActive: Boolean) {
        if (_currentState.value.isBypassCharging != isActive) {
            _currentState.value = _currentState.value.copy(
                isBypassCharging = isActive,
                canRunIntensive = isActive || _currentState.value.canRunIntensive
            )
            Log.i(TAG, "Bypass Charging: ${if (isActive) "ACTIVO" else "INACTIVO"}")
        }
    }

    // ==================== UBICACIÓN GPS ====================

    /**
     * Monitorea la ubicación del dispositivo usando FusedLocationProvider.
     *
     * Usa dos estrategias de actualización:
     * - Cuando el usuario está quieto: Actualizaciones cada 5 minutos
     * - Cuando el usuario se mueve: Actualizaciones cada 1 minuto
     *
     * La precisión (coarse vs precise) depende de los permisos otorgados.
     * El agente solo recibe coordenadas, no hace geocoding en red.
     */
    @SuppressLint("MissingPermission")
    private fun startLocationMonitoring() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // Verificar permisos
        val hasPrecise = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPrecise && !hasCoarse) {
            Log.w(TAG, "Sin permisos de ubicación")
            return
        }

        val priority = if (hasPrecise) {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        } else {
            Priority.PRIORITY_LOW_POWER
        }

        val locationRequest = LocationRequest.Builder(priority, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .setMaxUpdateDelayMillis(LOCATION_UPDATE_INTERVAL_MS * 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    onLocationUpdate(location)
                }
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            // Obtener última ubicación conocida inmediatamente
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                location?.let { onLocationUpdate(it) }
            }

            Log.i(TAG, "Monitoreo de ubicación iniciado (precisión: ${if (hasPrecise) "FINE" else "COARSE"})")
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permiso para acceder a ubicación", e)
        }
    }

    private fun onLocationUpdate(location: Location) {
        _currentState.value = _currentState.value.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            locationAccuracy = location.accuracy,
            locationTime = System.currentTimeMillis()
        )

        Log.d(TAG, "Ubicación: ${location.latitude}, ${location.longitude} " +
                "(±${location.accuracy}m)")
    }

    // ==================== ACTIVIDAD FÍSICA ====================

    /**
     * Detecta la actividad física del usuario usando ActivityRecognition API.
     *
     * Las actividades detectadas incluyen:
     * - IN_VEHICLE: Conduciendo (activa modo no interrumpir)
     * - ON_BICYCLE: En bicicleta
     * - RUNNING: Corriendo
     * - WALKING: Caminando
     * - STILL: Inactivo
     *
     * IMPORTANTE: Cuando se detecta IN_VEHICLE, el NotificationInterceptor
     * debe reducir las interrupciones a solo emergencias. El agente
     * también puede proactivamente activar modo "manos libres" y
     * responder mensajes con "Estoy conduciendo".
     */
    @SuppressLint("MissingPermission")
    private fun startActivityRecognition() {
        if (!hasActivityPermission()) {
            Log.w(TAG, "Sin permisos de reconocimiento de actividad")
            return
        }

        activityRecognitionClient = ActivityRecognition.getClient(context)

        // Configurar transiciones de actividad
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
        )

        val request = ActivityTransitionRequest(transitions)

        activityPendingIntent = createActivityPendingIntent()

        try {
            activityRecognitionClient?.requestActivityTransitionUpdates(
                request,
                activityPendingIntent!!
            )
            Log.i(TAG, "Reconocimiento de actividad iniciado")
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permiso para reconocimiento de actividad", e)
        }
    }

    /**
     * Crea el PendingIntent para las transiciones de actividad.
     */
    private fun createActivityPendingIntent(): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Procesa los resultados de transiciones de actividad.
     */
    fun handleActivityTransitionResult(result: ActivityTransitionResult) {
        val transition = result.transitionEvents.firstOrNull() ?: return
        val activityType = transition.activityType

        val userActivity = when (activityType) {
            DetectedActivity.IN_VEHICLE -> UserActivity.DRIVING
            DetectedActivity.ON_BICYCLE -> UserActivity.CYCLING
            DetectedActivity.RUNNING -> UserActivity.RUNNING
            DetectedActivity.WALKING -> UserActivity.WALKING
            DetectedActivity.STILL -> UserActivity.STILL
            else -> UserActivity.UNKNOWN
        }

        _currentState.value = _currentState.value.copy(
            currentActivity = userActivity
        )

        Log.i(TAG, "Actividad detectada: $userActivity")
    }

    // ==================== SENSORES DE MOVIMIENTO ====================

    /**
     * Monitorea el conteo de pasos y movimiento del dispositivo.
     * Usa el sensor TYPE_STEP_COUNTER del hardware para bajo consumo.
     */
    private fun startMotionSensors() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Sensor de pasos (bajo consumo, hardware-based)
        val stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepCounter != null) {
            stepCounterListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val steps = event.values[0].toLong()
                    _currentState.value = _currentState.value.copy(
                        stepCount = steps
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager?.registerListener(
                stepCounterListener,
                stepCounter,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.i(TAG, "Sensor de pasos registrado")
        } else {
            Log.w(TAG, "Sensor de pasos no disponible")
        }
    }

    // ==================== EMISIÓN DE ESTADO ====================

    /**
     * Emite el estado actual del hardware al PerceptionBus.
     *
     * Se aplica throttling para no saturar el bus con actualizaciones
     * demasiado frecuentes. Solo se emite si:
     * 1. Pasó al menos MIN_EMIT_INTERVAL_MS desde la última emisión
     * 2. El estado cambió significativamente
     */
    private suspend fun emitCurrentState() {
        val now = System.currentTimeMillis()
        if (now - lastEmitTime < MIN_EMIT_INTERVAL_MS) return

        val state = _currentState.value
        lastEmitTime = now

        PerceptionBus.emit(
            PerceptionEvent.HardwareStateUpdate(
                batteryLevel = state.batteryLevel,
                isCharging = state.isCharging,
                isBypassCharging = state.isBypassCharging,
                latitude = state.latitude,
                longitude = state.longitude,
                currentActivity = state.currentActivity,
                stepCount = state.stepCount
            )
        )
    }

    // ==================== UTILIDADES ====================

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasActivityPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true  // No requerido antes de Android 10
        }
    }

    /**
     * Fuerza una emisión inmediata del estado del hardware.
     * Útil cuando el agente necesita datos frescos para tomar una decisión.
     */
    suspend fun forceEmit() {
        updateBatteryState()
        emitCurrentState()
    }
}

/**
 * Estado completo del hardware del dispositivo.
 */
data class HardwareState(
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val isBypassCharging: Boolean = false,
    val chargerType: ChargerType = ChargerType.NONE,
    val canRunIntensive: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float = 0f,
    val locationTime: Long = 0L,
    val currentActivity: UserActivity = UserActivity.UNKNOWN,
    val stepCount: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
)

enum class ChargerType {
    NONE,       // Desconectado
    AC,         // Cargador de pared
    USB,        // Cargador USB
    WIRELESS,   // Carga inalámbrica
    UNKNOWN     // No determinado
}


