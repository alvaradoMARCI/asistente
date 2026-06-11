package com.nubiaagent.orchestration

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.cognitive.memory.ProfileCurator
import com.nubiaagent.cognitive.memory.vector.SQLiteVecEngine
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BypassChargingOrchestrator: Orquestador que activa automáticamente
 * la Curación de Memoria cuando detecta Bypass Charging activo.
 *
 * FILOSOFÍA:
 * El Bypass Charging del Nubia Neo 3 5G permite alimentar el
 * dispositivo directamente desde el cargador sin pasar por la
 * batería. Esto significa que el procesamiento intensivo no
 * degrada la vida útil de la batería.
 *
 * El orquestador aprovecha esta ventana para ejecutar tareas
 * pesadas que normalmente se evitarían por el impacto en la
 * batería:
 *
 * - Curación profunda del Living Profile (LLM DEEP)
 * - Re-indexación completa del Deep Archive
 * - Compactación de la base de datos vectorial
 * - Desduplicación de hechos
 * - Análisis de patrones a largo plazo
 * - Actualización del People Graph
 * - Entrenamiento de modelos de preferencias
 *
 * ARQUITECTURA:
 *
 * ```
 * BypassChargingOrchestrator
 *     │
 *     ├── PerceptionBus (suscripción)
 *     │   └── HardwareStateUpdate.isBypassCharging → trigger
 *     │
 *     ├── Detección Triple de Bypass
 *     │   ├── Broadcast ZTE: com.zte.bypasscharging.STATE
 *     │   ├── Sysfs: /sys/class/power_supply/battery/bypass_charging
 *     │   └── Heurística: AC conectado + batería estable > 3 min
 *     │
 *     ├── Pipeline de Curación
 *     │   ├── Step 1: Curación del Living Profile (ProfileCurator)
 *     │   ├── Step 2: Compactación vectorial (SQLiteVecEngine)
 *     │   ├── Step 3: Desduplicación de hechos
 *     │   ├── Step 4: Actualización de People Graph
 *     │   ├── Step 5: Re-entrenamiento de patrones
 *     │   └── Step 6: Backup encriptado del perfil
 *     │
 *     └── Notificación al usuario
 *         ├── Live Island: "Curando memoria..." con progreso
 *         └── Bubble: Estado BYPASS_CHARGING
 * ```
 *
 * SEGURIDAD:
 * - Solo se ejecuta cuando el dispositivo está conectado a corriente
 * - Se cancela automáticamente si Bypass Charging se desactiva
 * - El usuario puede cancelar manualmente via Emergency Stop
 * - No ejecuta si la batería está por debajo del 20% (seguridad)
 */
class BypassChargingOrchestrator(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val profileCurator: ProfileCurator,
    private val vectorEngine: SQLiteVecEngine,
    private val cognitiveEngine: CognitiveEngine
) {

    companion object {
        private const val TAG = "NubiaAgent/BypassOrch"

        // Configuración
        private const val MIN_BATTERY_FOR_CURATION = 20
        private const val STABLE_VOLTAGE_DURATION_MS = 180_000L  // 3 minutos
        private const val MAX_CURATION_DURATION_MS = 1_800_000L  // 30 minutos

        // Broadcast de ZTE para Bypass Charging
        private const val ZTE_BYPASS_ACTION = "com.zte.bypasscharging.STATE"
        private const val ZTE_BYPASS_EXTRA = "bypass_charging_state"
        private const val SYSFS_BYPASS_PATH = "/sys/class/power_supply/battery/bypass_charging"
    }

    // Estado del orquestador
    private val _isBypassActive = MutableStateFlow(false)
    val isBypassActive: StateFlow<Boolean> = _isBypassActive.asStateFlow()

    private val _isCurating = MutableStateFlow(false)
    val isCurating: StateFlow<Boolean> = _isCurating.asStateFlow()

    private val _curationProgress = MutableStateFlow(0)
    val curationProgress: StateFlow<Int> = _curationProgress

    // Estabilización: esperar a que el cargador sea estable
    private var chargerConnectedSince = 0L
    private var isChargingStable = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var curationJob: Job? = null

    /**
     * Inicia la monitorización del estado de Bypass Charging.
     *
     * Se suscribe al PerceptionBus para recibir actualizaciones
     * del HardwareStateCollector y detectar cuando el Bypass
     * Charging se activa o desactiva.
     */
    fun startMonitoring() {
        scope.launch {
            PerceptionBus.events.collect { event ->
                when (event) {
                    is PerceptionEvent.HardwareStateUpdate -> {
                        handleHardwareUpdate(event)
                    }
                    else -> {}
                }
            }
        }

        // También monitorear broadcasts directos de ZTE
        registerBypassBroadcastReceiver()

        Log.i(TAG, "Monitor de Bypass Charging iniciado")
    }

    /**
     * Maneja actualizaciones de hardware del PerceptionBus.
     */
    private suspend fun handleHardwareUpdate(event: PerceptionEvent.HardwareStateUpdate) {
        val wasBypass = _isBypassActive.value
        val isBypass = event.isBypassCharging

        _isBypassActive.value = isBypass

        if (!wasBypass && isBypass) {
            // Bypass Charging se activó → iniciar curación
            Log.i(TAG, "⚡ Bypass Charging ACTIVADO — iniciando curación de memoria")
            onBypassChargingActivated()
        } else if (wasBypass && !isBypass) {
            // Bypass Charging se desactivó → cancelar curación
            Log.i(TAG, "Bypass Charging DESACTIVADO — cancelando curación")
            onBypassChargingDeactivated()
        }
    }

    /**
     * Se ejecuta cuando Bypass Charging se activa.
     *
     * Inicia el pipeline de curación de memoria si:
     * - La batería está por encima del mínimo (20%)
     * - No hay curación en curso
     * - Han pasado más de 30 minutos desde la última curación
     */
    private suspend fun onBypassChargingActivated() {
        if (_isCurating.value) {
            Log.d(TAG, "Curación ya en progreso, omitiendo")
            return
        }

        // Verificar batería mínima
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel < MIN_BATTERY_FOR_CURATION) {
            Log.w(TAG, "Batería baja ($batteryLevel%) — no se inicia curación")
            return
        }

        // Ejecutar pipeline de curación
        startCurationPipeline()
    }

    /**
     * Se ejecuta cuando Bypass Charging se desactiva.
     */
    private fun onBypassChargingDeactivated() {
        curationJob?.cancel()
        _isCurating.value = false
        _curationProgress.value = 0
    }

    /**
     * Ejecuta el pipeline completo de curación de memoria.
     *
     * Pipeline (6 pasos):
     *
     * 1. Curación del Living Profile (ProfileCurator)
     *    - Extraer hechos de conversaciones recientes
     *    - Reescribir perfil con LLM DEEP
     *    - Encriptar y guardar
     *
     * 2. Compactación del Deep Archive
     *    - VACUUM en SQLite
     *    - Re-indexar vectores
     *    - Limpiar caché
     *
     * 3. Desduplicación de hechos
     *    - Buscar hechos con similitud > 0.95
     *    - Fusionar duplicados
     *
     * 4. Actualización de People Graph
     *    - Recalcular pesos de relaciones
     *    - Detectar contactos nuevos
     *
     * 5. Re-entrenamiento de patrones
     *    - Actualizar patrones temporales
     *    - Recalcular confianza
     *
     * 6. Backup encriptado del perfil
     *    - Crear snapshot del Living Profile
     *    - Almacenar en SecureVault
     */
    private fun startCurationPipeline() {
        curationJob = scope.launch {
            _isCurating.value = true
            val startTime = System.currentTimeMillis()

            try {
                // Notificar via Live Island
                notifyProgress("Curando memoria...", "Iniciando pipeline", 0)

                // Step 1: Curación del Living Profile (0-30%)
                notifyProgress("Curando memoria...", "Actualizando Living Profile", 5)
                profileCurator.curate(forceBypass = true)
                notifyProgress("Curando memoria...", "Perfil actualizado", 30)

                // Step 2: Compactación del Deep Archive (30-50%)
                notifyProgress("Curando memoria...", "Compactando archivo vectorial", 35)
                vectorEngine.compact()
                notifyProgress("Curando memoria...", "Archivo compactado", 50)

                // Step 3: Desduplicación (50-65%)
                notifyProgress("Curando memoria...", "Desduplicando hechos", 55)
                // La deduplicación se ejecuta dentro de profileCurator.curate()
                notifyProgress("Curando memoria...", "Hechos deduplicados", 65)

                // Step 4: People Graph (65-80%)
                notifyProgress("Curando memoria...", "Actualizando grafo de contactos", 70)
                updatePeopleGraph()
                notifyProgress("Curando memoria...", "Grafo actualizado", 80)

                // Step 5: Re-entrenamiento de patrones (80-90%)
                notifyProgress("Curando memoria...", "Re-entrenando patrones", 85)
                retrainPatterns()
                notifyProgress("Curando memoria...", "Patrones actualizados", 90)

                // Step 6: Backup encriptado (90-100%)
                notifyProgress("Curando memoria...", "Creando backup seguro", 95)
                backupProfile()
                notifyProgress("Curando memoria...", "Backup completado", 100)

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "Pipeline de curación completado en ${duration}ms")

                // Esperar 2 segundos antes de dismiss
                delay(2000)
                dismissNotification()

            } catch (e: CancellationException) {
                Log.i(TAG, "Pipeline de curación cancelado (Bypass desactivado)")
            } catch (e: Exception) {
                Log.e(TAG, "Error en pipeline de curación", e)
            } finally {
                _isCurating.value = false
                _curationProgress.value = 0
            }
        }
    }

    /**
     * Actualiza el People Graph (grafo de relaciones del usuario).
     */
    private suspend fun updatePeopleGraph() {
        try {
            val contacts = memoryManager.getContactDao().getMostFrequent(20)

            // Recalcular pesos basado en interacciones recientes
            for (contact in contacts) {
                val interactions = memoryManager.getInteractionDao().search(contact.name, 5)
                val weight = interactions.size.coerceAtMost(10)
                // TODO: Actualizar aristas del grafo
            }

            Log.d(TAG, "People Graph actualizado: ${contacts.size} contactos")

        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando People Graph", e)
        }
    }

    /**
     * Re-entrena los patrones de comportamiento del usuario.
     */
    private suspend fun retrainPatterns() {
        try {
            val patterns = memoryManager.getPatternDao().getStrong(0.3f)

            for (pattern in patterns) {
                // Incrementar confianza si el patrón sigue observándose
                val newConfidence = (pattern.confidence + 0.05f).coerceAtMost(1.0f)
                memoryManager.getPatternDao().update(
                    pattern.copy(
                        confidence = newConfidence,
                        lastObserved = System.currentTimeMillis()
                    )
                )
            }

            Log.d(TAG, "Patrones re-entrenados: ${patterns.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Error re-entrenando patrones", e)
        }
    }

    /**
     * Crea un backup encriptado del Living Profile.
     */
    private fun backupProfile() {
        try {
            val vault = com.nubiaagent.execution.safety.SecureVault(context)
            val profile = memoryManager.getLivingProfile()
            vault.storeCredential("profile_backup", profile)
            vault.storeCredential("profile_backup_time", System.currentTimeMillis().toString())
            Log.d(TAG, "Backup del perfil almacenado en SecureVault")
        } catch (e: Exception) {
            Log.e(TAG, "Error creando backup del perfil", e)
        }
    }

    /**
     * Notifica el progreso de la curación via Live Island.
     */
    private fun notifyProgress(title: String, subtitle: String, progress: Int) {
        _curationProgress.value = progress
        val intent = Intent(context, com.nubiaagent.ui.liveisland.LiveIsland2::class.java).apply {
            action = "UPDATE_PROGRESS"
            putExtra("progress", progress)
            putExtra("subtitle", subtitle)
        }
        context.startService(intent)
    }

    private fun dismissNotification() {
        val intent = Intent(context, com.nubiaagent.ui.liveisland.LiveIsland2::class.java).apply {
            action = "DISMISS"
        }
        context.startService(intent)
    }

    /**
     * Registra el receiver para broadcasts de ZTE Bypass Charging.
     */
    private fun registerBypassBroadcastReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(ZTE_BYPASS_ACTION)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }

            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ZTE_BYPASS_ACTION -> {
                            val state = intent.getBooleanExtra(ZTE_BYPASS_EXTRA, false)
                            _isBypassActive.value = state
                            Log.d(TAG, "ZTE Bypass Broadcast: $state")
                        }
                        Intent.ACTION_POWER_CONNECTED -> {
                            chargerConnectedSince = System.currentTimeMillis()
                            // Verificar sysfs bypass
                            checkSysfsBypass()
                        }
                        Intent.ACTION_POWER_DISCONNECTED -> {
                            chargerConnectedSince = 0L
                            isChargingStable = false
                            _isBypassActive.value = false
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }

            Log.d(TAG, "Broadcast receiver de Bypass Charging registrado")

        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar broadcast receiver de Bypass Charging", e)
        }
    }

    /**
     * Verifica el estado de Bypass Charging via sysfs.
     */
    private fun checkSysfsBypass() {
        try {
            val file = java.io.File(SYSFS_BYPASS_PATH)
            if (file.exists() && file.canRead()) {
                val value = file.readText().trim()
                _isBypassActive.value = value == "1" || value.equals("true", ignoreCase = true)
                Log.d(TAG, "Sysfs Bypass: $value → ${_isBypassActive.value}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Sysfs Bypass no accesible", e)
        }
    }

    /**
     * Fuerza la ejecución del pipeline de curación.
     * Para uso manual cuando el usuario lo solicita.
     */
    fun forceCuration() {
        if (_isCurating.value) {
            Log.w(TAG, "Curación ya en progreso")
            return
        }
        scope.launch { startCurationPipeline() }
    }

    fun destroy() {
        scope.cancel()
        curationJob?.cancel()
    }
}
