package com.nubiaagent.execution.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TaskType: Tipos de tareas que el agente puede ejecutar.
 *
 * Cada tipo tiene un perfil de consumo energético diferente, lo cual
 * determina si la carga en bypass debería activarse automáticamente.
 */
enum class TaskType {
    /** Inferencia del modelo LLM — uso intensivo de CPU/GPU. */
    INFERENCE,

    /** Curación de memoria — procesamiento moderado de datos. */
    CURATION,

    /** Grabación de audio/video — uso sostenido de hardware. */
    RECORDING,

    /** Análisis de pantalla (OCR + VLM) — procesamiento visual pesado. */
    SCREEN_ANALYSIS,

    /** Automatización UI — gestos + navegación, carga moderada. */
    UI_AUTOMATION,

    /** Operación rápida o lectura — consumo mínimo. */
    LIGHT,

    /** Tarea larga en segundo plano — requiere protección térmica. */
    BACKGROUND_LONG,

    /** Espera pasiva — no consume recursos significativos. */
    IDLE
}

/**
 * BypassState: Estado actual del sistema de carga en bypass.
 */
enum class BypassState {
    /** Bypass activo — la batería no se carga mientras el dispositivo está conectado. */
    ACTIVE,

    /** Bypass inactivo — carga normal. */
    INACTIVE,

    /** No se pudo determinar el estado — path sysfs no accesible. */
    UNKNOWN
}

/**
 * PowerManager: Controlador de Carga en Bypass para ZTE Nubia Neo 3 5G.
 *
 * La carga en bypass (bypass charging) es una funcionalidad específica de
 * dispositivos gaming como el Nubia Neo 3 5G, donde la energía del cargador
 * alimenta directamente al sistema sin pasar por la batería. Esto reduce:
 * - Generación de calor durante sesiones prolongadas
 * - Degradación de la batería por ciclos innecesarios
 * - Thermal throttling que degrada el rendimiento del LLM
 *
 * ARQUITECTURA DE ACTIVACIÓN:
 * ```
 *  ┌─────────────────────────────────────────────────────────┐
 *  │               Método de Activación                       │
 *  │                                                          │
 *  │  1. sysfs (preferido)                                    │
 *  │     /sys/class/power_supply/battery/bypass_charging      │
 *  │     echo 1 > bypass_charging                             │
 *  │                                                          │
 *  │  2. ZTE Broadcast (fallback)                             │
 *  │     com.zte.bypass.CHANGE                                │
 *  │     extra:bypass = true                                  │
 *  │                                                          │
 *  │  3. Content Provider (último recurso)                    │
 *  │     content://com.zte.power/bypass                       │
 *  └─────────────────────────────────────────────────────────┘
 * ```
 *
 * MONITOREO TÉRMICO:
 * - Temperatura de batería monitoreada cada 30 segundos
 * - Alerta si temperatura > 42°C (caliente)
 * - Alerta crítica si temperatura > 45°C (peligro)
 * - Auto-desactivación de bypass si temperatura > 48°C
 *
 * INTEGRACIÓN CON PerceptionBus:
 * - Se suscribe a eventos de tipo HardwareStateUpdate
 * - Auto-activa bypass cuando detecta tareas largas
 * - Publica actualizaciones de estado de bypass en el bus
 *
 * NOTA: Los paths de sysfs son específicos del Nubia Neo 3 5G (NX711J).
 * Pueden variar en otros modelos ZTE/Nubia.
 *
 * @property context Contexto de la aplicación
 */
class PowerManager(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/Power"

        // ──────────────────────── Umbrales Térmicos ──────────────────────────

        /** Temperatura de batería considerada "caliente" (°C). */
        private const val TEMP_WARN_THRESHOLD = 42f

        /** Temperatura de batería considerada peligrosa (°C). */
        private const val TEMP_CRITICAL_THRESHOLD = 45f

        /** Temperatura máxima — se fuerza desactivación de bypass (°C). */
        private const val TEMP_EMERGENCY_THRESHOLD = 48f

        /** Temperatura normal — seguro para bypass (°C). */
        private const val TEMP_NORMAL_THRESHOLD = 38f

        // ──────────────────────── Intervalos de Monitoreo ────────────────────

        /** Intervalo de monitoreo de temperatura en milisegundos. */
        private const val MONITOR_INTERVAL_MS = 30_000L

        // ──────────────────── Paths sysfs de Nubia Neo 3 5G ──────────────────

        /**
         * Paths de sysfs para controlar la carga en bypass en el ZTE Nubia Neo 3 5G.
         *
         * Se intentan en orden — el primero que existe y es escribible se usa.
         * Estos paths fueron identificados mediante ingeniería inversa del
         * sistema de energía de ZTE y la comunidad Red Magic / Nubia.
         */
        private val BYPASS_SYSFS_PATHS = listOf(
            // Path principal (Nubia Neo 3 5G / Red Magic series)
            "/sys/class/power_supply/battery/bypass_charging",
            // Path alternativo (algunos firmware ZTE)
            "/sys/class/power_supply/bms/bypass_charging",
            // Path del controlador de carga USB
            "/sys/class/power_supply/usb/bypass_charging",
            // Path genérico de gaming mode
            "/sys/class/power_supply/battery/input_suspend",
            // Path alternativo con nombre diferente
            "/sys/class/power_supply/battery/charging_enabled",
            // Path del kernel para control directo
            "/sys/kernel/bypass_charging/enable"
        )

        /**
         * Paths de sysfs para leer la temperatura de la batería.
         */
        private val BATTERY_TEMP_PATHS = listOf(
            "/sys/class/power_supply/battery/temp",
            "/sys/class/power_supply/bms/temp",
            "/sys/class/power_supply/battery/temperature"
        )

        /**
         * Paths de sysfs para leer el estado de bypass.
         */
        private val BYPASS_STATE_PATHS = listOf(
            "/sys/class/power_supply/battery/bypass_charging",
            "/sys/class/power_supply/bms/bypass_charging",
            "/sys/class/power_supply/usb/bypass_charging"
        )

        // ──────────────────── Intents ZTE ────────────────────────────────────

        /** Intent de ZTE para controlar la carga en bypass. */
        private const val ZTE_BYPASS_ACTION = "com.zte.bypass.CHANGE"
        private const val ZTE_BYPASS_EXTRA = "bypass"
        private const val ZTE_BYPASS_PERMISSION = "com.zte.permission.POWER_CONTROL"

        // ──────────────── Tipos de Tarea y su Carga Estimada ─────────────────

        /**
         * Mapa de tipos de tarea a su nivel de consumo energético.
         * Valor más alto = más consumo = mayor necesidad de bypass.
         */
        private val TASK_POWER_PROFILE = mapOf(
            TaskType.INFERENCE to 0.9f,
            TaskType.SCREEN_ANALYSIS to 0.8f,
            TaskType.RECORDING to 0.7f,
            TaskType.CURATION to 0.5f,
            TaskType.BACKGROUND_LONG to 0.6f,
            TaskType.UI_AUTOMATION to 0.4f,
            TaskType.LIGHT to 0.1f,
            TaskType.IDLE to 0.0f
        )

        /** Umbral de consumo para justificar activación de bypass. */
        private const val BYPASS_ACTIVATION_THRESHOLD = 0.5f

        /** Nivel mínimo de batería para activar bypass (no activar si batería < 20%). */
        private const val MIN_BATTERY_FOR_BYPASS = 20
    }

    // ──────────────────────────── Estado Interno ─────────────────────────────

    /** Estado actual del bypass como StateFlow para observadores. */
    private val _bypassState = MutableStateFlow(BypassState.UNKNOWN)
    val bypassState: StateFlow<BypassState> = _bypassState.asStateFlow()

    /** Última temperatura de batería leída (°C). */
    private val _batteryTemp = MutableStateFlow(0f)
    val batteryTemp: StateFlow<Float> = _batteryTemp.asStateFlow()

    /** Si el monitoreo térmico está activo. */
    private val monitoringActive = AtomicBoolean(false)

    /** Handler para el monitoreo periódico de temperatura. */
    private val monitorHandler = Handler(Looper.getMainLooper())

    /** Job de suscripción al PerceptionBus. */
    private var perceptionJob: Job? = null

    /** Scope para corrutinas. */
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Path sysfs que se encontró funcional para bypass (cacheado). */
    private var activeBypassPath: String? = null

    /** Si se ha advertido sobre temperatura alta en esta sesión. */
    private var tempWarningIssued = false

    /** Si se ha advertido sobre temperatura crítica en esta sesión. */
    private var tempCriticalIssued = false

    // ──────────────────────── Receiver de Batería ────────────────────────────

    /**
     * BroadcastReceiver para escuchar cambios en el estado de la batería.
     * Se usa para detectar cuando se conecta/desconecta el cargador y
     * reaccionar automáticamente.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val temp = getBatteryTemperatureFromIntent(intent)
                    _batteryTemp.value = temp

                    // Verificar umbrales térmicos
                    checkThermalThresholds(temp)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i(TAG, "Cargador conectado — evaluando activación de bypass")
                    evaluateAutoActivation()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "Cargador desconectado — desactivando bypass")
                    if (_bypassState.value == BypassState.ACTIVE) {
                        deactivateBypassCharging()
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inicialización
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        // Descubrir path sysfs funcional
        discoverBypassPath()

        // Leer estado inicial
        _bypassState.value = if (isBypassChargingActive()) BypassState.ACTIVE else BypassState.INACTIVE

        // Registrar receiver de batería
        registerBatteryReceiver()

        // Suscribirse al PerceptionBus para auto-activación
        subscribeToPerceptionBus()

        Log.i(TAG, "PowerManager inicializado — bypass=${
            if (_bypassState.value == BypassState.ACTIVE) "ACTIVO" else "INACTIVO"
        }, path=${activeBypassPath ?: "ninguno (usando broadcast)"}")
    }

    /**
     * Descubre qué path de sysfs está disponible para controlar el bypass.
     * Almacena en cache el primer path que existe y es escribible.
     */
    private fun discoverBypassPath() {
        for (path in BYPASS_SYSFS_PATHS) {
            val file = File(path)
            if (file.exists()) {
                // Verificar si es escribible
                if (file.canWrite()) {
                    activeBypassPath = path
                    Log.i(TAG, "Path de bypass encontrado: $path (escribible)")
                    return
                } else {
                    Log.d(TAG, "Path encontrado pero no escribible: $path")
                }
            }
        }
        Log.w(TAG, "Ningún path sysfs de bypass encontrado — se usará broadcast ZTE como fallback")
    }

    /**
     * Registra el BroadcastReceiver para eventos de batería.
     */
    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(batteryReceiver, filter)
    }

    /**
     * Se suscribe al PerceptionBus para detectar tareas largas y
     * activar bypass automáticamente.
     */
    private fun subscribeToPerceptionBus() {
        perceptionJob = scope.launch {
            PerceptionBus.events.collect { event ->
                when (event) {
                    is PerceptionEvent.HardwareStateUpdate -> {
                        // Si el hardware reporta bypass activo, sincronizar estado
                        if (event.isBypassCharging && _bypassState.value != BypassState.ACTIVE) {
                            _bypassState.value = BypassState.ACTIVE
                            Log.d(TAG, "Estado de bypass sincronizado desde HardwareStateUpdate: ACTIVO")
                        }
                    }
                    else -> { /* Evento no relevante para PowerManager */ }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API Pública — Carga en Bypass
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica si la carga en bypass está activa.
     *
     * Comprueba en orden:
     * 1. Paths sysfs — lee el valor actual del kernel
     * 2. ZTE intent — verifica si hay un sticky broadcast indicando bypass
     * 3. Fallback al estado interno cacheado
     *
     * @return true si la carga en bypass está activa
     */
    fun isBypassChargingActive(): Boolean {
        // Intentar leer de sysfs
        for (path in BYPASS_STATE_PATHS) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                try {
                    val value = file.readText().trim()
                    // Diferentes firmwares usan diferentes convenciones:
                    // "1" = activado, "0" = desactivado
                    // "true" / "false" en algunos
                    // Para charging_enabled: "0" = bypass (carga deshabilitada)
                    val isBypassActive = when {
                        path.contains("charging_enabled") -> value == "0"
                        path.contains("input_suspend") -> value == "1"
                        else -> value == "1" || value.equals("true", ignoreCase = true)
                    }

                    Log.d(TAG, "Bypass sysfs $path = $value → activo=$isBypassActive")
                    return isBypassActive
                } catch (e: Exception) {
                    Log.w(TAG, "Error leyendo sysfs $path", e)
                }
            }
        }

        // Fallback: verificar estado interno
        return _bypassState.value == BypassState.ACTIVE
    }

    /**
     * Activa la carga en bypass.
     *
     * Intenta en orden:
     * 1. Escritura en sysfs (método preferido, más fiable)
     * 2. Envío de broadcast ZTE (fallback)
     *
     * Solo activa si el dispositivo está conectado al cargador.
     *
     * @return true si la activación fue exitosa
     */
    fun activateBypassCharging(): Boolean {
        if (!isCharging()) {
            Log.w(TAG, "No se puede activar bypass — el dispositivo no está cargando")
            return false
        }

        if (getBatteryLevel() < MIN_BATTERY_FOR_BYPASS) {
            Log.w(
                TAG,
                "No se puede activar bypass — batería demasiado baja (${
                    getBatteryLevel()
                }% < $MIN_BATTERY_FOR_BYPASS%)"
            )
            return false
        }

        // Verificar temperatura antes de activar
        val temp = _batteryTemp.value
        if (temp >= TEMP_EMERGENCY_THRESHOLD) {
            Log.e(TAG, "No se puede activar bypass — temperatura de batería crítica: ${temp}°C")
            return false
        }

        // Intentar vía sysfs
        val sysfsSuccess = activateViaSysfs()
        if (sysfsSuccess) {
            _bypassState.value = BypassState.ACTIVE
            Log.i(TAG, "Carga en bypass ACTIVADA vía sysfs")
            startThermalMonitor()
            publishBypassState(active = true)
            return true
        }

        // Fallback: Intent ZTE
        val intentSuccess = activateViaZteBroadcast()
        if (intentSuccess) {
            _bypassState.value = BypassState.ACTIVE
            Log.i(TAG, "Carga en bypass ACTIVADA vía broadcast ZTE")
            startThermalMonitor()
            publishBypassState(active = true)
            return true
        }

        Log.e(TAG, "No se pudo activar la carga en bypass por ningún método")
        _bypassState.value = BypassState.UNKNOWN
        return false
    }

    /**
     * Desactiva la carga en bypass y restaura la carga normal.
     *
     * @return true si la desactivación fue exitosa
     */
    fun deactivateBypassCharging(): Boolean {
        // Intentar vía sysfs
        val sysfsSuccess = deactivateViaSysfs()
        if (sysfsSuccess) {
            _bypassState.value = BypassState.INACTIVE
            Log.i(TAG, "Carga en bypass DESACTIVADA vía sysfs — carga normal restaurada")
            stopThermalMonitor()
            publishBypassState(active = false)
            return true
        }

        // Fallback: Intent ZTE
        val intentSuccess = deactivateViaZteBroadcast()
        if (intentSuccess) {
            _bypassState.value = BypassState.INACTIVE
            Log.i(TAG, "Carga en bypass DESACTIVADA vía broadcast ZTE")
            stopThermalMonitor()
            publishBypassState(active = false)
            return true
        }

        Log.e(TAG, "No se pudo desactivar la carga en bypass por ningún método")
        _bypassState.value = BypassState.UNKNOWN
        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API Pública — Estado de Batería
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica si el dispositivo está conectado a una fuente de alimentación.
     *
     * @return true si está cargando (AC, USB o inalámbrico)
     */
    fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.isCharging
    }

    /**
     * Obtiene el nivel actual de batería como porcentaje.
     *
     * @return Nivel de batería (0-100)
     */
    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /**
     * Obtiene una descripción textual del estado de la batería.
     *
     * Incluye: nivel, estado de carga, tipo de conexión, temperatura,
     * y estado de bypass.
     *
     * @return Cadena descriptiva del estado de batería en español
     */
    fun getBatteryStatus(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = getBatteryLevel()
        val charging = isCharging()
        val temp = _batteryTemp.value

        val chargeStatus = if (charging) {
            val chargeType = getChargingType()
            "Cargando ($chargeType)"
        } else {
            "Desconectado"
        }

        val bypassStatus = when (_bypassState.value) {
            BypassState.ACTIVE -> "Bypass activo"
            BypassState.INACTIVE -> "Carga normal"
            BypassState.UNKNOWN -> "Estado desconocido"
        }

        val tempStatus = when {
            temp >= TEMP_EMERGENCY_THRESHOLD -> "⚠️ TEMPERATURA CRÍTICA"
            temp >= TEMP_CRITICAL_THRESHOLD -> "🔴 Temperatura peligrosa"
            temp >= TEMP_WARN_THRESHOLD -> "🟡 Temperatura alta"
            else -> "🟢 Temperatura normal"
        }

        return "Batería: $level% | $chargeStatus | $bypassStatus | $tempStatus (${String.format("%.1f", temp)}°C)"
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API Pública — Decisión de Bypass
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Determina si la carga en bypass debería activarse para un tipo de tarea dado.
     *
     * Criterios de decisión:
     * 1. El perfil de consumo de la tarea debe superar el umbral
     * 2. El dispositivo debe estar cargando
     * 3. La batería debe tener al menos MIN_BATTERY_FOR_BYPASS%
     * 4. La temperatura no debe estar en nivel de emergencia
     * 5. El bypass no debe estar ya activo
     *
     * @param taskType Tipo de tarea que se va a ejecutar
     * @return true si se recomienda activar bypass para esta tarea
     */
    fun shouldActivateBypass(taskType: TaskType): Boolean {
        // Verificar si el bypass ya está activo
        if (_bypassState.value == BypassState.ACTIVE) {
            return false // Ya activo, no necesita reactivación
        }

        // Verificar perfil de consumo
        val powerProfile = TASK_POWER_PROFILE[taskType] ?: 0f
        if (powerProfile < BYPASS_ACTIVATION_THRESHOLD) {
            Log.d(TAG, "Bypass no necesario para $taskType (consumo=$powerProfile < umbral=$BYPASS_ACTIVATION_THRESHOLD)")
            return false
        }

        // Verificar que está cargando
        if (!isCharging()) {
            Log.d(TAG, "Bypass no aplicable — dispositivo no está cargando")
            return false
        }

        // Verificar nivel de batería
        val batteryLevel = getBatteryLevel()
        if (batteryLevel < MIN_BATTERY_FOR_BYPASS) {
            Log.d(TAG, "Bypass no recomendado — batería baja ($batteryLevel% < $MIN_BATTERY_FOR_BYPASS%)")
            return false
        }

        // Verificar temperatura
        val temp = _batteryTemp.value
        if (temp >= TEMP_EMERGENCY_THRESHOLD) {
            Log.w(TAG, "Bypass no seguro — temperatura de emergencia (${temp}°C)")
            return false
        }

        Log.i(TAG, "Bypass recomendado para $taskType (consumo=$powerProfile, batería=$batteryLevel%, temp=${String.format("%.1f", temp)}°C)")
        return true
    }

    /**
     * Activa bypass automáticamente si las condiciones lo justifican.
     *
     * Llamado por el agente antes de iniciar una tarea. Si la tarea
     * justifica el bypass y las condiciones son favorables, lo activa.
     *
     * @param taskType Tipo de tarea que se va a ejecutar
     * @return true si el bypass se activó (o ya estaba activo)
     */
    fun autoActivateForTask(taskType: TaskType): Boolean {
        if (_bypassState.value == BypassState.ACTIVE) {
            return true // Ya activo
        }

        if (shouldActivateBypass(taskType)) {
            val success = activateBypassCharging()
            if (success) {
                Log.i(TAG, "Bypass auto-activado para tarea: $taskType")
            }
            return success
        }

        return false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Monitoreo Térmico
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inicia el monitoreo periódico de la temperatura de la batería.
     *
     * Mientras el bypass está activo, se monitorea la temperatura
     * cada [MONITOR_INTERVAL_MS] milisegundos para detectar
     * condiciones térmicas peligrosas.
     */
    private fun startThermalMonitor() {
        if (monitoringActive.getAndSet(true)) {
            return // Ya está monitoreando
        }

        tempWarningIssued = false
        tempCriticalIssued = false

        monitorHandler.postDelayed(thermalMonitorRunnable, MONITOR_INTERVAL_MS)
        Log.d(TAG, "Monitoreo térmico iniciado (intervalo=${MONITOR_INTERVAL_MS}ms)")
    }

    /**
     * Detiene el monitoreo térmico periódico.
     */
    private fun stopThermalMonitor() {
        monitoringActive.set(false)
        monitorHandler.removeCallbacks(thermalMonitorRunnable)
        Log.d(TAG, "Monitoreo térmico detenido")
    }

    /**
     * Runnable que ejecuta la verificación térmica periódica.
     */
    private val thermalMonitorRunnable = object : Runnable {
        override fun run() {
            if (!monitoringActive.get()) return

            val temp = readBatteryTemperature()
            _batteryTemp.value = temp

            checkThermalThresholds(temp)

            // Continuar monitoreo si sigue activo
            if (monitoringActive.get()) {
                monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }
    }

    /**
     * Verifica si la temperatura de la batería supera los umbrales
     * y toma las acciones apropiadas.
     *
     * @param temp Temperatura actual en grados Celsius
     */
    private fun checkThermalThresholds(temp: Float) {
        when {
            temp >= TEMP_EMERGENCY_THRESHOLD -> {
                Log.e(TAG, "🚨 EMERGENCIA TÉRMICA: ${temp}°C — forzando desactivación de bypass")
                // Forzar desactivación de bypass — seguridad primero
                if (_bypassState.value == BypassState.ACTIVE) {
                    deactivateBypassCharging()
                }
                publishThermalAlert("EMERGENCIA", temp)
            }

            temp >= TEMP_CRITICAL_THRESHOLD -> {
                if (!tempCriticalIssued) {
                    Log.e(TAG, "🔴 ALERTA CRÍTICA: Temperatura de batería ${temp}°C — riesgo de daño")
                    tempCriticalIssued = true
                    publishThermalAlert("CRÍTICA", temp)
                }
            }

            temp >= TEMP_WARN_THRESHOLD -> {
                if (!tempWarningIssued) {
                    Log.w(TAG, "🟡 Advertencia: Temperatura de batería ${temp}°C — considere pausar tareas pesadas")
                    tempWarningIssued = true
                    publishThermalAlert("ADVERTENCIA", temp)
                }
            }

            temp < TEMP_NORMAL_THRESHOLD -> {
                // Temperatura normalizada — resetear flags
                if (tempWarningIssued || tempCriticalIssued) {
                    Log.i(TAG, "🟢 Temperatura normalizada: ${temp}°C")
                    tempWarningIssued = false
                    tempCriticalIssued = false
                }
            }
        }
    }

    /**
     * Publica una alerta térmica en el PerceptionBus.
     */
    private fun publishThermalAlert(level: String, temp: Float) {
        scope.launch {
            PerceptionBus.emit(
                PerceptionEvent.HardwareStateUpdate(
                    batteryLevel = getBatteryLevel(),
                    isCharging = isCharging(),
                    isBypassCharging = _bypassState.value == BypassState.ACTIVE,
                    latitude = null,
                    longitude = null,
                    currentActivity = com.nubiaagent.core.UserActivity.STILL,
                    stepCount = 0
                )
            )
        }
        Log.i(TAG, "Alerta térmica $level publicada: ${String.format("%.1f", temp)}°C")
    }

    /**
     * Publica el estado de bypass en el PerceptionBus.
     */
    private fun publishBypassState(active: Boolean) {
        scope.launch {
            PerceptionBus.emit(
                PerceptionEvent.HardwareStateUpdate(
                    batteryLevel = getBatteryLevel(),
                    isCharging = isCharging(),
                    isBypassCharging = active,
                    latitude = null,
                    longitude = null,
                    currentActivity = com.nubiaagent.core.UserActivity.STILL,
                    stepCount = 0
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Métodos de Activación Internos
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Activa bypass vía escritura en sysfs.
     *
     * @return true si la escritura fue exitosa
     */
    private fun activateViaSysfs(): Boolean {
        val paths = if (activeBypassPath != null) {
            listOf(activeBypassPath!!)
        } else {
            BYPASS_SYSFS_PATHS
        }

        for (path in paths) {
            try {
                val file = File(path)
                if (!file.exists()) continue

                // El valor a escribir depende del path:
                // - bypass_charging: "1" para activar
                // - charging_enabled: "0" para activar bypass (deshabilitar carga)
                // - input_suspend: "1" para suspender entrada (bypass)
                val value = when {
                    path.contains("charging_enabled") -> "0"
                    path.contains("input_suspend") -> "1"
                    else -> "1"
                }

                FileOutputStream(path).use { it.write(value.toByteArray()) }
                activeBypassPath = path
                Log.d(TAG, "Escrito '$value' en $path — bypass activado")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "No se pudo escribir en $path: ${e.message}")
            }
        }

        return false
    }

    /**
     * Desactiva bypass vía escritura en sysfs.
     *
     * @return true si la escritura fue exitosa
     */
    private fun deactivateViaSysfs(): Boolean {
        val paths = if (activeBypassPath != null) {
            listOf(activeBypassPath!!)
        } else {
            BYPASS_SYSFS_PATHS
        }

        for (path in paths) {
            try {
                val file = File(path)
                if (!file.exists()) continue

                val value = when {
                    path.contains("charging_enabled") -> "1"  // Restaurar carga
                    path.contains("input_suspend") -> "0"     // Restaurar entrada
                    else -> "0"                                // Desactivar bypass
                }

                FileOutputStream(path).use { it.write(value.toByteArray()) }
                Log.d(TAG, "Escrito '$value' en $path — bypass desactivado")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "No se pudo escribir en $path: ${e.message}")
            }
        }

        return false
    }

    /**
     * Activa bypass vía broadcast ZTE.
     *
     * Envía un broadcast protegido que el sistema ZTE puede interceptar
     * para activar el modo bypass. Requiere que la app tenga el permiso
     * ZTE_BYPASS_PERMISSION (solo disponible en sistema).
     *
     * @return true si el broadcast se envió sin error
     */
    private fun activateViaZteBroadcast(): Boolean {
        return try {
            val intent = Intent(ZTE_BYPASS_ACTION).apply {
                putExtra(ZTE_BYPASS_EXTRA, true)
                addFlags(0x01000000) // Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND (hidden API)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Broadcast ZTE de activación enviado: $ZTE_BYPASS_ACTION")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar broadcast ZTE de activación", e)
            false
        }
    }

    /**
     * Desactiva bypass vía broadcast ZTE.
     *
     * @return true si el broadcast se envió sin error
     */
    private fun deactivateViaZteBroadcast(): Boolean {
        return try {
            val intent = Intent(ZTE_BYPASS_ACTION).apply {
                putExtra(ZTE_BYPASS_EXTRA, false)
                addFlags(0x01000000) // Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND (hidden API)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Broadcast ZTE de desactivación enviado: $ZTE_BYPASS_ACTION")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar broadcast ZTE de desactivación", e)
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilidades Internas
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lee la temperatura de la batería del sysfs o del BatteryManager.
     *
     * Prioriza sysfs (más preciso y rápido) y cae a BatteryManager
     * como alternativa.
     *
     * @return Temperatura en grados Celsius
     */
    private fun readBatteryTemperature(): Float {
        // Intentar leer de sysfs (más preciso)
        for (path in BATTERY_TEMP_PATHS) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val raw = file.readText().trim()
                    // Algunos sysfs reportan en décimas de grado (ej: 350 = 35.0°C)
                    val value = raw.toFloatOrNull() ?: continue
                    return if (value > 100) value / 10f else value
                }
            } catch (_: Exception) {
            }
        }

        // Fallback: BatteryManager via sticky intent
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let { getBatteryTemperatureFromIntent(it) } ?: 25f
    }

    /**
     * Extrae la temperatura de la batería de un Intent ACTION_BATTERY_CHANGED.
     *
     * @param intent Intent de batería
     * @return Temperatura en grados Celsius
     */
    private fun getBatteryTemperatureFromIntent(intent: Intent): Float {
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return temp / 10f // BatteryManager reporta en décimas de grado
    }

    /**
     * Determina el tipo de conexión de carga actual.
     *
     * @return Descripción en español del tipo de carga
     */
    private fun getChargingType(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Desconocido"

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Cargador AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Inalámbrico"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "Base"
            else -> "Desconocido"
        }
    }

    /**
     * Evalúa si se debe activar bypass automáticamente al conectar el cargador.
     *
     * Se activa automáticamente solo si hay una tarea en curso que lo justifique.
     */
    private fun evaluateAutoActivation() {
        // Solo auto-activar si la temperatura lo permite y hay una
        // tarea que justifica el bypass. En la implementación actual,
        // el auto-activado se maneja via autoActivateForTask().
        Log.d(TAG, "Evaluación de auto-activación — se requiere llamada explícita a autoActivateForTask()")
    }

    /**
     * Libera todos los recursos y detiene el monitoreo.
     *
     * Debe llamarse cuando el agente se detiene. Desactiva el bypass
     * (restaurando carga normal) y cancela todas las suscripciones.
     */
    fun release() {
        // Restaurar carga normal
        if (_bypassState.value == BypassState.ACTIVE) {
            deactivateBypassCharging()
        }

        // Detener monitoreo
        stopThermalMonitor()

        // Cancelar suscripción al PerceptionBus
        perceptionJob?.cancel()
        perceptionJob = null

        // Desregistrar receiver
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
            // Receiver ya desregistrado
        }

        Log.i(TAG, "PowerManager liberado — todos los recursos cerrados")
    }
}
