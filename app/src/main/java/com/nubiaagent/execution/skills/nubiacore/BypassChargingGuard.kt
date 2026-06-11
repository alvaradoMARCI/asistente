package com.nubiaagent.execution.skills.nubiacore

import android.content.Context
import android.util.Log
import com.nubiaagent.execution.hardware.BypassState
import com.nubiaagent.execution.hardware.PowerManager
import com.nubiaagent.execution.hardware.TaskType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BypassChargingGuard: Habilidad de activación inteligente de carga en bypass.
 *
 * Gestiona automáticamente el estado de bypass charging según el perfil de la
 * tarea que el agente está ejecutando. Las tareas intensivas (inferencia LLM,
 * curación de memoria) activan bypass para evitar thermal throttling, mientras
 * que tareas ligeras no lo necesitan.
 *
 * PERFILES DE TAREA:
 * ```
 *  ┌──────────────────┬────────────────┬─────────────────────────────────────┐
 *  │ Perfil           │ Política       │ Justificación                       │
 *  ├──────────────────┼────────────────┼─────────────────────────────────────┤
 *  │ INFERENCE        │ Siempre bypass │ CPU/GPU al 100%, calor extremo      │
 *  │ CURATION         │ Siempre bypass │ Procesamiento sostenido de datos    │
 *  │ RECORDING        │ Recomendar     │ Hardware sostenido, calor moderado  │
 *  │ BRIEFING         │ Opcional       │ Procesamiento breve, calor bajo     │
 *  │ LIGHT_TASK       │ No necesario   │ Consumo mínimo, sin riesgo térmico  │
 *  └──────────────────┴────────────────┴─────────────────────────────────────┘
 * ```
 *
 * CICLO DE VIDA:
 * 1. Antes de tarea → activateForTask() evalúa perfil y activa bypass si procede
 * 2. Durante tarea larga → monitorLongTask() gestiona estado dinámicamente
 * 3. Después de tarea → deactivateAfterTask() restaura carga normal
 *
 * INTEGRACIÓN CON PowerManager:
 * - Usa PowerManager.activateBypassCharging() / deactivateBypassCharging()
 * - Consulta PowerManager.shouldActivateBypass() para decisión informada
 * - Lee PowerManager.bypassState y PowerManager.batteryTemp para monitoreo
 *
 * @property context Contexto de la aplicación
 * @property powerManager Controlador de carga en bypass del hardware
 */
class BypassChargingGuard(
    private val context: Context,
    private val powerManager: PowerManager
) {

    companion object {
        private const val TAG = "NubiaAgent/BypassGuard"

        /** Intervalo de monitoreo para tareas largas (ms). */
        private const val MONITOR_INTERVAL_MS = 15_000L

        /** Duración estimada mínima para considerar bypass en tareas RECORDING (min). */
        private const val RECORDING_BYPASS_THRESHOLD_MIN = 10

        /** Temperatura a partir de la cual se advierte al usuario (°C). */
        private const val TEMP_ADVICE_THRESHOLD = 40f
    }

    /**
     * Perfil de bypass para cada tipo de tarea del agente.
     *
     * Define si el bypass es obligatorio, recomendado, opcional o innecesario.
     */
    enum class BypassPolicy {
        /** Bypass obligatorio — la tarea genera calor extremo. */
        ALWAYS,
        /** Bypass recomendado — la tarea genera calor moderado-sostenido. */
        RECOMMEND,
        /** Bypass opcional — la tarea puede beneficiarse pero no es crítico. */
        OPTIONAL,
        /** Bypass innecesario — la tarea no genera calor significativo. */
        NOT_NEEDED
    }

    /**
     * Estado interno del guardián de bypass.
     */
    data class GuardState(
        /** Si el bypass fue activado por este guardián. */
        val bypassActivatedByUs: Boolean = false,
        /** Nombre de la tarea actual bajo monitoreo. */
        val currentTask: String? = null,
        /** Tipo de tarea actual. */
        val currentTaskType: BypassPolicy? = null,
        /** Timestamp de activación del bypass. */
        val activatedAt: Long = 0L,
        /** Duración estimada de la tarea en minutos. */
        val estimatedDurationMin: Int = 0
    )

    // ─── Estado interno ───

    private val _guardState = MutableStateFlow(GuardState())
    val guardState: StateFlow<GuardState> = _guardState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    // ─── Mapa de perfiles de tarea a política de bypass ───

    private val taskProfileMap = mapOf(
        "INFERENCE" to BypassPolicy.ALWAYS,
        "CURATION" to BypassPolicy.ALWAYS,
        "RECORDING" to BypassPolicy.RECOMMEND,
        "SCREEN_ANALYSIS" to BypassPolicy.RECOMMEND,
        "BRIEFING" to BypassPolicy.OPTIONAL,
        "UI_AUTOMATION" to BypassPolicy.OPTIONAL,
        "LIGHT_TASK" to BypassPolicy.NOT_NEEDED,
        "IDLE" to BypassPolicy.NOT_NEEDED
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Activa la carga en bypass si es apropiado para la tarea descrita.
     *
     * Evalúa el perfil de la tarea y decide si activar bypass basándose en:
     * - Política del perfil (ALWAYS → siempre activar, NOT_NEEDED → nunca)
     * - Estado actual de la batería y temperatura
     * - Si el dispositivo está conectado al cargador
     * - Duración estimada de la tarea
     *
     * @param taskDescription Descripción de la tarea (se analiza para inferir perfil)
     * @param estimatedDurationMinutes Duración estimada en minutos
     * @return Result con mensaje descriptivo en español
     */
    fun activateForTask(
        taskDescription: String,
        estimatedDurationMinutes: Int
    ): Result<String> {
        val profile = inferTaskProfile(taskDescription)
        val policy = taskProfileMap[profile] ?: BypassPolicy.OPTIONAL

        Log.i(TAG, "Evaluando bypass para tarea: '$taskDescription' " +
                "(perfil=$profile, política=$policy, duración=${estimatedDurationMinutes}min)")

        return when (policy) {
            BypassPolicy.ALWAYS -> {
                // Intentar activar bypass siempre
                val activated = attemptBypassActivation()
                if (activated) {
                    _guardState.value = GuardState(
                        bypassActivatedByUs = true,
                        currentTask = taskDescription,
                        currentTaskType = policy,
                        activatedAt = System.currentTimeMillis(),
                        estimatedDurationMin = estimatedDurationMinutes
                    )
                    Result.success(
                        "Carga en bypass ACTIVADA para tarea '$taskDescription'. " +
                        "La energía alimentará el sistema directamente sin cargar la batería, " +
                        "reduciendo calor y protegiendo el rendimiento."
                    )
                } else {
                    _guardState.value = GuardState(
                        bypassActivatedByUs = false,
                        currentTask = taskDescription,
                        currentTaskType = policy,
                        activatedAt = 0L,
                        estimatedDurationMin = estimatedDurationMinutes
                    )
                    Result.success(
                        "No se pudo activar bypass para '$taskDescription'. " +
                        getReasonNotActivated()
                    )
                }
            }

            BypassPolicy.RECOMMEND -> {
                // Activar si la duración justifica el bypass o si ya hay temperatura alta
                val shouldBypass = estimatedDurationMinutes >= RECORDING_BYPASS_THRESHOLD_MIN ||
                        powerManager.batteryTemp.value >= TEMP_ADVICE_THRESHOLD

                if (shouldBypass) {
                    val activated = attemptBypassActivation()
                    if (activated) {
                        _guardState.value = GuardState(
                            bypassActivatedByUs = true,
                            currentTask = taskDescription,
                            currentTaskType = policy,
                            activatedAt = System.currentTimeMillis(),
                            estimatedDurationMin = estimatedDurationMinutes
                        )
                        Result.success(
                            "Carga en bypass ACTIVADA para '$taskDescription' " +
                            "(tarea de ${estimatedDurationMinutes}min). " +
                            "Se recomienda bypass para evitar sobrecalentamiento sostenido."
                        )
                    } else {
                        Result.success(
                            "Bypass recomendado pero no activado para '$taskDescription'. " +
                            getReasonNotActivated()
                        )
                    }
                } else {
                    Result.success(
                        "Bypass no necesario para '$taskDescription' " +
                        "(duración corta: ${estimatedDurationMinutes}min). " +
                        "Se activará automáticamente si la tarea se prolonga."
                    )
                }
            }

            BypassPolicy.OPTIONAL -> {
                // Solo activar si hay temperatura alta o batería llena
                val temp = powerManager.batteryTemp.value
                val batteryLevel = powerManager.getBatteryLevel()

                if (temp >= TEMP_ADVICE_THRESHOLD || batteryLevel >= 80) {
                    val activated = attemptBypassActivation()
                    if (activated) {
                        _guardState.value = GuardState(
                            bypassActivatedByUs = true,
                            currentTask = taskDescription,
                            currentTaskType = policy,
                            activatedAt = System.currentTimeMillis(),
                            estimatedDurationMin = estimatedDurationMinutes
                        )
                        Result.success(
                            "Carga en bypass activada opcionalmente para '$taskDescription'. " +
                            "Temperatura actual: ${"%.1f".format(temp)}°C, batería: ${batteryLevel}%."
                        )
                    } else {
                        Result.success(
                            "Bypass opcional no activado para '$taskDescription'. " +
                            getReasonNotActivated()
                        )
                    }
                } else {
                    Result.success(
                        "Bypass opcional no activado para '$taskDescription'. " +
                        "Las condiciones no lo justifican (temp: ${"%.1f".format(temp)}°C, batería: ${batteryLevel}%)."
                    )
                }
            }

            BypassPolicy.NOT_NEEDED -> {
                Result.success(
                    "Bypass innecesario para '$taskDescription'. " +
                    "Esta tarea no genera suficiente calor para requerir carga en bypass."
                )
            }
        }
    }

    /**
     * Desactiva la carga en bypass después de que la tarea ha terminado.
     *
     * Solo desactiva si fue este guardián quien activó el bypass.
     * Si el bypass ya estaba activo antes de la tarea, se respeta.
     *
     * @return Result con mensaje descriptivo en español
     */
    fun deactivateAfterTask(): Result<String> {
        val state = _guardState.value

        if (!state.bypassActivatedByUs) {
            Log.d(TAG, "No se desactiva bypass — no fue activado por este guardián")
            return Result.success(
                "No se desactiva la carga en bypass: no fue activada por esta tarea."
            )
        }

        val taskName = state.currentTask ?: "tarea desconocida"
        val durationMs = if (state.activatedAt > 0) {
            System.currentTimeMillis() - state.activatedAt
        } else 0L
        val durationMin = durationMs / 60_000

        val deactivated = powerManager.deactivateBypassCharging()

        _guardState.value = GuardState()

        return if (deactivated) {
            Log.i(TAG, "Bypass desactivado tras completar: '$taskName' (${durationMin}min)")
            Result.success(
                "Carga en bypass DESACTIVADA tras completar '$taskName' " +
                "(duración real: ${durationMin} minutos). " +
                "Carga normal restaurada."
            )
        } else {
            Log.w(TAG, "No se pudo desactivar bypass tras tarea: '$taskName'")
            Result.success(
                "No se pudo desactivar la carga en bypass tras '$taskName'. " +
                "Puede que sea necesario desactivarla manualmente desde ajustes."
            )
        }
    }

    /**
     * Retorna una recomendación en español sobre el estado de carga actual.
     *
     * Incluye información sobre:
     * - Estado del bypass (activo/inactivo/desconocido)
     * - Nivel de batería y temperatura
     * - Si está conectado al cargador
     * - Recomendación de acción
     *
     * @return Texto de recomendación en español
     */
    fun getChargingRecommendation(): String {
        val bypassState = powerManager.bypassState.value
        val batteryLevel = powerManager.getBatteryLevel()
        val isCharging = powerManager.isCharging()
        val temp = powerManager.batteryTemp.value
        val statusText = powerManager.getBatteryStatus()

        val recommendation = when {
            !isCharging -> {
                when {
                    batteryLevel < 20 ->
                        "Batería baja (${batteryLevel}%). Conecta el cargador para " +
                        "asegurar rendimiento durante tareas intensivas."
                    batteryLevel < 50 ->
                        "Batería moderada (${batteryLevel}%). Sin carga conectada. " +
                        "Si vas a realizar tareas pesadas, considera conectar el cargador " +
                        "con bypass activado."
                    else ->
                        "Batería suficiente (${batteryLevel}%). Sin carga conectada. " +
                        "Conecta el cargador si planeas sesiones largas de inferencia."
                }
            }

            bypassState == BypassState.ACTIVE -> {
                when {
                    temp >= 42f ->
                        "Cargando con BYPASS activo, pero temperatura alta (${ "%.1f".format(temp)}°C). " +
                        "El sistema está monitoreando. Si la temperatura sube más, " +
                        "el bypass se desactivará automáticamente por seguridad."
                    batteryLevel < 25 ->
                        "Cargando con BYPASS activo, pero batería baja (${batteryLevel}%). " +
                        "Considera desactivar el bypass temporalmente para cargar la batería " +
                        "si no tienes tareas intensivas en curso."
                    else ->
                        "Cargando con BYPASS activo. Óptimo para tareas pesadas — " +
                        "la energía alimenta el sistema directamente, reduciendo calor. " +
                        "Batería: ${batteryLevel}%, temperatura: ${ "%.1f".format(temp)}°C."
                }
            }

            bypassState == BypassState.INACTIVE -> {
                when {
                    temp >= 40f ->
                        "Cargando normalmente, pero temperatura elevada (${ "%.1f".format(temp)}°C). " +
                        "Se recomienda activar bypass para reducir calor y proteger " +
                        "el rendimiento del dispositivo."
                    batteryLevel >= 80 ->
                        "Cargando normalmente con batería alta (${batteryLevel}%). " +
                        "Considera activar bypass para preservar la batería y reducir calor."
                    else ->
                        "Cargando normalmente. Batería: ${batteryLevel}%, " +
                        "temperatura: ${ "%.1f".format(temp)}°C. " +
                        "El bypass se activará automáticamente cuando inicies tareas intensivas."
                }
            }

            else -> {
                "Estado de carga en bypass desconocido. Batería: ${batteryLevel}%, " +
                        "temperatura: ${ "%.1f".format(temp)}°C."
            }
        }

        return "$recommendation\n\nEstado: $statusText"
    }

    /**
     * Monitorea una tarea en ejecución y gestiona el estado de bypass dinámicamente.
     *
     * Se ejecuta como corrutina de fondo que periódicamente evalúa:
     * - Temperatura de la batería → desactiva bypass si es peligrosa
     * - Duración de la tarea → activa bypass si la tarea se prolonga
     * - Estado de la carga → reacciona si se desconecta el cargador
     *
     * @param taskName Nombre descriptivo de la tarea en ejecución
     */
    fun monitorLongTask(taskName: String) {
        // Cancelar monitor anterior si existe
        monitorJob?.cancel()

        monitorJob = scope.launch {
            Log.i(TAG, "Inicio de monitoreo para tarea larga: '$taskName'")
            var elapsedMinutes = 0

            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                elapsedMinutes += (MONITOR_INTERVAL_MS / 60_000).toInt().coerceAtLeast(1)

                val state = _guardState.value
                val temp = powerManager.batteryTemp.value
                val isCharging = powerManager.isCharging()
                val bypassActive = powerManager.bypassState.value == BypassState.ACTIVE

                // Verificar emergencia térmica
                if (temp >= 45f && bypassActive) {
                    Log.w(TAG, "Temperatura crítica (${ "%.1f".format(temp)}°C) durante '$taskName' — desactivando bypass")
                    val deactivated = powerManager.deactivateBypassCharging()
                    if (deactivated) {
                        _guardState.value = state.copy(bypassActivatedByUs = false)
                        Log.i(TAG, "Bypass desactivado por seguridad térmica durante '$taskName'")
                    }
                    // No cancelamos el monitoreo, seguimos vigilando
                }

                // Si se desconectó el cargador, el bypass deja de tener efecto
                if (!isCharging && bypassActive) {
                    Log.i(TAG, "Cargador desconectado durante '$taskName' — bypass sin efecto")
                    _guardState.value = state.copy(bypassActivatedByUs = false)
                }

                // Si la tarea se prolonga y no tenemos bypass, considerar activar
                if (!bypassActive && isCharging && elapsedMinutes >= RECORDING_BYPASS_THRESHOLD_MIN) {
                    val policy = state.currentTaskType ?: BypassPolicy.OPTIONAL
                    if (policy == BypassPolicy.RECOMMEND || policy == BypassPolicy.ALWAYS) {
                        Log.i(TAG, "Tarea '$taskName' prolongada (${elapsedMinutes}min) — activando bypass")
                        val activated = attemptBypassActivation()
                        if (activated) {
                            _guardState.value = state.copy(bypassActivatedByUs = true)
                        }
                    }
                }

                Log.d(TAG, "Monitoreo '$taskName': ${elapsedMinutes}min transcurridos, " +
                        "temp=${ "%.1f".format(temp)}°C, bypass=$bypassActive, cargando=$isCharging")
            }

            Log.i(TAG, "Monitoreo finalizado para tarea: '$taskName'")
        }
    }

    /**
     * Detiene el monitoreo de la tarea actual.
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.d(TAG, "Monitoreo de tarea detenido")
    }

    /**
     * Limpia recursos del guardián.
     */
    fun destroy() {
        stopMonitoring()
        scope.cancel()
        Log.i(TAG, "BypassChargingGuard destruido")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MÉTODOS PRIVADOS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Infiere el perfil de tarea a partir de su descripción textual.
     *
     * Analiza palabras clave en la descripción para determinar el tipo de tarea.
     * Si no se puede inferir, retorna LIGHT_TASK como perfil conservador.
     *
     * @param description Descripción de la tarea
     * @return Nombre del perfil de tarea (clave de taskProfileMap)
     */
    private fun inferTaskProfile(description: String): String {
        val desc = description.lowercase()

        return when {
            // INFERENCE: Inferencia del modelo LLM
            desc.contains("inferencia") || desc.contains("llm") ||
            desc.contains("modelo") || desc.contains("generación de texto") ||
            desc.contains("respuesta del modelo") || desc.contains("completar") ||
            desc.contains("inference") -> "INFERENCE"

            // CURATION: Curación de memoria
            desc.contains("curación") || desc.contains("memoria") ||
            desc.contains("procesar datos") || desc.contains("curation") ||
            desc.contains("consolidar") || desc.contains("archivar") ||
            desc.contains("resumir interacciones") -> "CURATION"

            // RECORDING: Grabación de audio/video
            desc.contains("grabación") || desc.contains("audio") ||
            desc.contains("video") || desc.contains("recording") ||
            desc.contains("transcribir") || desc.contains("escuchar") -> "RECORDING"

            // SCREEN_ANALYSIS: Análisis de pantalla
            desc.contains("pantalla") || desc.contains("ocr") ||
            desc.contains("vlm") || desc.contains("análisis visual") ||
            desc.contains("captura") || desc.contains("screen analysis") -> "SCREEN_ANALYSIS"

            // BRIEFING: Briefing matutino
            desc.contains("briefing") || desc.contains("resumen matutino") ||
            desc.contains("informe diario") || desc.contains("resumen del día") -> "BRIEFING"

            // UI_AUTOMATION: Automatización UI
            desc.contains("automatización") || desc.contains("navegar") ||
            desc.contains("gesto") || desc.contains("ui automation") ||
            desc.contains("pulsar botón") -> "UI_AUTOMATION"

            // IDLE: En espera
            desc.contains("espera") || desc.contains("idle") ||
            desc.contains("inactivo") -> "IDLE"

            // Default: tarea ligera
            else -> "LIGHT_TASK"
        }
    }

    /**
     * Intenta activar la carga en bypass usando el PowerManager.
     *
     * Verifica condiciones previas y delega la activación al PowerManager.
     *
     * @return true si el bypass se activó exitosamente (o ya estaba activo)
     */
    private fun attemptBypassActivation(): Boolean {
        // Si ya está activo, no hacer nada
        if (powerManager.bypassState.value == BypassState.ACTIVE) {
            Log.d(TAG, "Bypass ya activo — no se necesita reactivación")
            return true
        }

        // Verificar si el PowerManager recomienda bypass
        val taskType = mapPolicyToTaskType(_guardState.value.currentTaskType)
        if (taskType != null && !powerManager.shouldActivateBypass(taskType)) {
            // El PowerManager no lo recomienda, pero intentemos si la política es ALWAYS
            val policy = _guardState.value.currentTaskType
            if (policy != BypassPolicy.ALWAYS) {
                Log.d(TAG, "PowerManager no recomienda bypass para esta tarea")
                return false
            }
        }

        return powerManager.activateBypassCharging()
    }

    /**
     * Mapea la política de bypass a un TaskType del PowerManager.
     */
    private fun mapPolicyToTaskType(policy: BypassPolicy?): TaskType? {
        return when (policy) {
            BypassPolicy.ALWAYS -> TaskType.INFERENCE
            BypassPolicy.RECOMMEND -> TaskType.RECORDING
            BypassPolicy.OPTIONAL -> TaskType.UI_AUTOMATION
            BypassPolicy.NOT_NEEDED -> TaskType.LIGHT
            null -> null
        }
    }

    /**
     * Retorna la razón por la que no se pudo activar el bypass.
     */
    private fun getReasonNotActivated(): String {
        val reasons = mutableListOf<String>()

        if (!powerManager.isCharging()) {
            reasons.add("el dispositivo no está conectado al cargador")
        }

        val batteryLevel = powerManager.getBatteryLevel()
        if (batteryLevel < 20) {
            reasons.add("batería demasiado baja (${batteryLevel}%)")
        }

        val temp = powerManager.batteryTemp.value
        if (temp >= 48f) {
            reasons.add("temperatura de emergencia (${ "%.1f".format(temp)}°C)")
        }

        if (powerManager.bypassState.value == BypassState.ACTIVE) {
            reasons.add("el bypass ya está activo")
        }

        return if (reasons.isNotEmpty()) {
            "Razón: ${reasons.joinToString(", ")}."
        } else {
            "No se pudo determinar la razón. Verifica los permisos del sistema."
        }
    }
}
