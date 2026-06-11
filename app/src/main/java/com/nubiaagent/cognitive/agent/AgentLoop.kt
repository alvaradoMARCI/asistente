package com.nubiaagent.cognitive.agent

import android.util.Log
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.cognitive.engine.InferenceConfig
import com.nubiaagent.cognitive.identity.IdentityManager
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import com.nubiaagent.core.UiElement
import com.nubiaagent.core.UserActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AgentLoop: Orquestador de Razonamiento Agéntico para Dayana.
 *
 * Implementa el ciclo fundamental **Pensar → Actuar → Observar** que
 * convierte a Dayana de un chatbot reactivo a un agente autónomo
 * capaz de descomponer tareas complejas y ejecutarlas de forma iterativa.
 *
 * ARQUITECTURA DEL LOOP:
 *
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │                    AGENT LOOP                            │
 * │                                                          │
 *  │  ┌─────────┐    ┌─────────┐    ┌──────────┐           │
 *  │  │ PENSAR  │───→│ ACTUAR  │───→│ OBSERVAR │──┐        │
 *  │  └─────────┘    └─────────┘    └──────────┘  │        │
 *  │       ↑                                       │        │
 *  │       └───────────────────────────────────────┘        │
 *  │              (si tarea no completada)                   │
 *  │                                                         │
 *  │  Input: PerceptionEvent (del PerceptionBus)            │
 *  │  Output: ToolExecution (al ToolExecutor)               │
 *  │  Memory: consulta/almacena via MemoryManager           │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * FASES DEL LOOP:
 *
 * 1. PENSAR (Think):
 *    - Recibe input del PerceptionBus (wake word, notificación, pantalla)
 *    - Consulta la memoria para contexto relevante
 *    - Construye el prompt para el LLM con toda la información
 *    - El LLM genera un plan de acción o una respuesta directa
 *
 * 2. ACTUAR (Act):
 *    - Parsea la respuesta del LLM para identificar tool calls
 *    - Verifica permisos según el perfil de autonomía
 *    - Si necesita confirmación: solicita al usuario
 *    - Si puede ejecutar: invoca el ToolExecutor
 *
 * 3. OBSERVAR (Observe):
 *    - Recopila el resultado de la herramienta ejecutada
 *    - Evalúa si la acción tuvo el efecto esperado
 *    - Si falló: re-planifica con información del error
 *    - Si tuvo éxito: actualiza memoria y verifica si la tarea está completa
 *    - Si no está completa: vuelve a PENSAR con el nuevo contexto
 *
 * RE-PLANIFICACIÓN:
 * El loop tiene un máximo de 10 iteraciones por tarea para evitar
 * loops infinitos. Si después de 10 intentos la tarea no se completa,
 * el agente informa al usuario y sugiere alternativas.
 *
 * GESTIÓN DE CONTEXTO:
 * Cada iteración del loop agrega información al contexto del LLM.
 * Para evitar desbordar el contexto (4096 tokens), se usa una
 * estrategia de sliding window que mantiene:
 * - System prompt (fijo)
 * - Últimas 3 observaciones
 * - Último plan de acción
 * - Última herramienta ejecutada y su resultado
 */
class AgentLoop(
    private val cognitiveEngine: CognitiveEngine,
    private val identityManager: IdentityManager,
    private val memoryManager: MemoryManager,
    private val toolExecutor: ToolExecutor
) {
    companion object {
        private const val TAG = "Dayana/Loop"

        // Límites del loop
        private const val MAX_ITERATIONS = 10
        private const val MAX_THINKING_TOKENS = 512
        private const val MAX_RESPONSE_TOKENS = 1024

        // Patrones para parsear tool calls del LLM
        private val TOOL_CALL_PATTERN = Regex(
            """(?:ACCIÓN|ACTION|TOOL):\s*(\w+(?:\.\w+)*)\(([^)]*)\)""",
            RegexOption.IGNORE_CASE
        )
        private val OBSERVATION_PATTERN = Regex(
            """(?:OBSERVACIÓN|OBSERVATION):\s*(.+?)(?=\n(?:PENSAMIENTO|THOUGHT|ACCIÓN|ACTION)|$)""",
            RegexOption.IGNORE_CASE
        )
        private val THOUGHT_PATTERN = Regex(
            """(?:PENSAMIENTO|THOUGHT|PIENSO):\s*(.+?)(?=\n(?:ACCIÓN|ACTION|OBSERVACIÓN|OBSERVATION)|$)""",
            RegexOption.IGNORE_CASE
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _loopState = MutableStateFlow(LoopState.IDLE)
    val loopState: StateFlow<LoopState> = _loopState.asStateFlow()

    private val _currentTask = MutableStateFlow<String?>(null)
    val currentTask: StateFlow<String?> = _currentTask.asStateFlow()

    private val _iterationCount = MutableStateFlow(0)
    val iterationCount: StateFlow<Int> = _iterationCount.asStateFlow()

    private var autonomyProfile: AutonomyProfile = AutonomyProfile.BALANCEADO
    private var conversationHistory = mutableListOf<Pair<String, String>>()

    enum class LoopState {
        IDLE,           // Esperando input
        THINKING,       // LLM razonando
        ACTING,         // Ejecutando herramienta
        OBSERVING,      // Evaluando resultado
        WAITING_USER,   // Esperando confirmación del usuario
        COMPLETED,      // Tarea completada
        FAILED          // Tarea fallida después de MAX_ITERATIONS
    }

    /**
     * Procesa un evento de percepción a través del Agent Loop completo.
     *
     * Este es el punto de entrada principal del agente. Se llama
     * cuando el PerceptionBus emite un evento que requiere acción.
     *
     * @param event El evento de percepción que dispara el loop
     * @param onPartialResponse Callback para streaming de la respuesta
     * @param onToolCall Callback cuando se invoca una herramienta
     * @param onNeedsConfirmation Callback cuando se necesita aprobación del usuario
     * @return El resultado final del loop
     */
    suspend fun processEvent(
        event: PerceptionEvent,
        onPartialResponse: ((String) -> Unit)? = null,
        onToolCall: ((ToolCall) -> Unit)? = null,
        onNeedsConfirmation: ((ToolCall) -> suspend () -> Boolean)? = null
    ): AgentResult {
        _loopState.value = LoopState.THINKING
        _iterationCount.value = 0

        val taskDescription = describeEvent(event)
        _currentTask.value = taskDescription

        Log.i(TAG, "★ Iniciando Agent Loop para: $taskDescription")

        // Contexto del loop - se va construyendo iterativamente
        val loopContext = AgentContext(
            originalEvent = event,
            observations = mutableListOf(),
            actions = mutableListOf(),
            results = mutableListOf()
        )

        // Consultar memoria antes de empezar
        val memoryContext = memoryManager.recallRelevant(taskDescription)
        loopContext.memoryHints = memoryContext

        try {
            for (iteration in 1..MAX_ITERATIONS) {
                _iterationCount.value = iteration
                Log.d(TAG, "Iteración $iteration/$MAX_ITERATIONS")

                // ============ FASE 1: PENSAR ============
                _loopState.value = LoopState.THINKING
                val thinkingResult = think(loopContext, event, iteration)

                if (thinkingResult.isDirectResponse) {
                    // El LLM decidió responder directamente sin herramientas
                    Log.i(TAG, "Respuesta directa (sin herramientas)")
                    memoryManager.storeInteraction(
                        userMessage = taskDescription,
                        assistantResponse = thinkingResult.response,
                        toolsUsed = emptyList()
                    )
                    _loopState.value = LoopState.COMPLETED
                    return AgentResult.Success(thinkingResult.response)
                }

                // ============ FASE 2: ACTUAR ============
                _loopState.value = LoopState.ACTING
                val toolCall = thinkingResult.toolCall
                    ?: run {
                        Log.w(TAG, "No se pudo parsear tool call de la respuesta")
                        _loopState.value = LoopState.COMPLETED
                        return AgentResult.Success(thinkingResult.rawResponse)
                    }

                Log.i(TAG, "Tool call: ${toolCall.toolName}(${toolCall.parameters})")
                onToolCall?.invoke(toolCall)

                // Verificar permisos según autonomía
                if (!canExecuteAutonomously(toolCall)) {
                    _loopState.value = LoopState.WAITING_USER
                    val confirmed = onNeedsConfirmation?.invoke(toolCall)?.invoke() ?: false
                    if (!confirmed) {
                        Log.i(TAG, "Usuario rechazó la acción: ${toolCall.toolName}")
                        loopContext.observations.add(
                            "El usuario rechazó la acción ${toolCall.toolName}. " +
                                    "No ejecutar esta acción y buscar alternativa."
                        )
                        continue  // Re-planificar
                    }
                }

                // Ejecutar la herramienta
                val executionResult = toolExecutor.execute(toolCall)
                loopContext.actions.add(toolCall)
                loopContext.results.add(executionResult)

                // ============ FASE 3: OBSERVAR ============
                _loopState.value = LoopState.OBSERVING
                val observation = observe(executionResult)
                loopContext.observations.add(observation)

                Log.d(TAG, "Observación: ${observation.take(100)}...")

                // Verificar si la tarea está completada
                if (isTaskCompleted(executionResult, loopContext)) {
                    Log.i(TAG, "★ Tarea completada en iteración $iteration")
                    memoryManager.storeInteraction(
                        userMessage = taskDescription,
                        assistantResponse = observation,
                        toolsUsed = loopContext.actions.map { it.toolName }
                    )
                    _loopState.value = LoopState.COMPLETED
                    return AgentResult.Success(observation)
                }

                // Si no está completada, el loop continúa con el nuevo contexto
            }

            // Máximo de iteraciones alcanzado
            Log.w(TAG, "Máximo de iteraciones alcanzado ($MAX_ITERATIONS)")
            _loopState.value = LoopState.FAILED
            return AgentResult.Failure(
                "No pude completar la tarea después de $MAX_ITERATIONS intentos. " +
                        "Última observación: ${loopContext.observations.lastOrNull()}"
            )

        } catch (e: CancellationException) {
            _loopState.value = LoopState.IDLE
            return AgentResult.Failure("Loop cancelado: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error en Agent Loop", e)
            _loopState.value = LoopState.FAILED
            return AgentResult.Failure("Error: ${e.message}")
        }
    }

    /**
     * FASE 1: PENSAR
     *
     * Construye el prompt completo para el LLM incluyendo:
     * - System prompt (SOUL + Living Profile + tools + autonomía)
     * - Historial de conversación reciente
     * - Contexto del loop actual (observaciones previas, acciones ejecutadas)
     * - Input actual (evento de percepción)
     *
     * El LLM debe responder con formato estructurado:
     * - OBSERVACIÓN: Qué percibe
     * - PENSAMIENTO: Qué planea hacer
     * - ACCIÓN: Qué herramienta invocar (o respuesta directa)
     */
    private suspend fun think(
        context: AgentContext,
        event: PerceptionEvent,
        iteration: Int
    ): ThinkResult {
        // Construir system prompt con herramientas disponibles
        val toolDescriptions = toolExecutor.getToolDescriptions()
        val hardwareSummary = buildHardwareSummary(event)

        val systemPrompt = identityManager.buildSystemPrompt(
            toolDescriptions = toolDescriptions,
            autonomyLevel = autonomyProfile.name,
            hardwareSummary = hardwareSummary,
            compressed = iteration > 3  // Comprimir en iteraciones avanzadas para ahorrar tokens
        )

        // Construir mensaje del usuario con contexto del loop
        val userMessage = buildUserMessage(context, event, iteration)

        // Formatear como chat prompt
        val fullPrompt = identityManager.formatChatPrompt(
            systemPrompt = systemPrompt,
            conversationHistory = conversationHistory.takeLast(4),  // Últimos 4 turnos
            currentMessage = userMessage
        )

        // Invocar al LLM
        val config = if (iteration > 5) InferenceConfig.FAST else InferenceConfig.BALANCED
        val response = cognitiveEngine.infer(fullPrompt, config)

        // Parsear la respuesta
        return parseThinkResult(response)
    }

    /**
     * Construye el mensaje del usuario que incluye todo el contexto
     * del loop actual: evento original, observaciones previas,
     * acciones ejecutadas y sus resultados.
     */
    private fun buildUserMessage(
        context: AgentContext,
        event: PerceptionEvent,
        iteration: Int
    ): String {
        return buildString {
            append("[ITERACIÓN $iteration/$MAX_ITERATIONS]\n\n")

            // Evento original
            append("[EVENTO ORIGINAL]\n")
            append(describeEvent(event))
            append("\n\n")

            // Observaciones previas del loop
            if (context.observations.isNotEmpty()) {
                append("[OBSERVACIONES PREVIAS]\n")
                context.observations.takeLast(3).forEachIndexed { i, obs ->
                    append("${i + 1}. $obs\n")
                }
                append("\n")
            }

            // Acciones ejecutadas y resultados
            if (context.actions.isNotEmpty()) {
                append("[ACCIONES EJECUTADAS]\n")
                context.actions.takeLast(3).forEachIndexed { i, action ->
                    val result = context.results.getOrNull(i)
                    append("${i + 1}. ${action.toolName}(${action.parameters}) → ")
                    append(result?.summary ?: "sin resultado")
                    append("\n")
                }
                append("\n")
            }

            // Hints de memoria
            if (context.memoryHints.isNotBlank()) {
                append("[MEMORIA RELEVANTE]\n")
                append(context.memoryHints.take(500))
                append("\n\n")
            }

            // Instrucción de acción
            if (iteration == 1) {
                append("¿Qué debes hacer con este evento? Piensa paso a paso y decide qué acción tomar.")
            } else {
                append("La tarea aún no está completada. Basándote en las observaciones previas, " +
                        "¿qué acción debes tomar ahora? Si necesitas un enfoque diferente, re-planifica.")
            }
        }
    }

    /**
     * FASE 3: OBSERVAR
     *
     * Evalúa el resultado de una acción y genera una observación
     * que el agente puede usar en la siguiente iteración del loop.
     */
    private fun observe(result: ToolResult): String {
        return when (result) {
            is ToolResult.Success -> {
                "Acción exitosa. Resultado: ${result.summary}"
            }
            is ToolResult.PartialSuccess -> {
                "Acción parcialmente exitosa. Lo que funcionó: ${result.successPart}. " +
                        "Lo que falló: ${result.failurePart}. Puede ser necesario intentar un enfoque diferente."
            }
            is ToolResult.Failure -> {
                "Acción fallida. Error: ${result.error}. Necesito re-planificar y " +
                        "considerar una alternativa o informar al usuario."
            }
            is ToolResult.NeedsInput -> {
                "Se necesita información adicional del usuario: ${result.question}"
            }
        }
    }

    /**
     * Determina si una herramienta puede ejecutarse automáticamente
     * según el perfil de autonomía activo.
     */
    private fun canExecuteAutonomously(toolCall: ToolCall): Boolean {
        return autonomyProfile.canExecute(toolCall.toolName, toolCall.isDestructive)
    }

    /**
     * Determina si la tarea se ha completado observando el resultado
     * y el contexto del loop.
     */
    private fun isTaskCompleted(result: ToolResult, context: AgentContext): Boolean {
        return when (result) {
            is ToolResult.Success -> {
                // Verificar si la acción resuelve la tarea original
                val taskKeywords = context.originalEvent.let { event ->
                    when (event) {
                        is PerceptionEvent.VoiceCommand -> event.transcript.split(" ")
                        is PerceptionEvent.NotificationReceived -> event.title.split(" ")
                        else -> emptyList()
                    }
                }
                // Heurística simple: si el resultado menciona las keywords, probablemente se completó
                result.summary.split(" ").any { it.lowercase() in taskKeywords.map { k -> k.lowercase() } }
                    || context.actions.size >= 3  // Si ya ejecutamos 3+ acciones, probablemente es suficiente
            }
            is ToolResult.NeedsInput -> false
            is ToolResult.PartialSuccess -> false
            is ToolResult.Failure -> context.actions.size >= 3  // No seguir reintentando
        }
    }

    /**
     * Describe un PerceptionEvent en lenguaje natural para el LLM.
     */
    private fun describeEvent(event: PerceptionEvent): String {
        return when (event) {
            is PerceptionEvent.WakeWordDetected ->
                "El usuario dijo el wake word (confianza: ${event.confidence}). Está esperando un comando."

            is PerceptionEvent.VoiceCommand ->
                "Comando de voz del usuario: \"${event.transcript}\" (confianza: ${event.confidence}, finalizado: ${event.isFinal})"

            is PerceptionEvent.ScreenChanged ->
                "La pantalla cambió. App activa: ${event.packageName}, Activity: ${event.activityName}"

            is PerceptionEvent.UiElementsReady ->
                "Pantalla de ${event.packageName} con ${event.elements.size} elementos interactivos. " +
                        "Árbol original: ${event.rawXmlSize} nodos. Elementos principales: " +
                        event.elements.take(5).joinToString(", ") {
                            "${it.viewType.name}${if (it.text != null) "(\"${it.text.take(20)}\")" else ""}"
                        }

            is PerceptionEvent.ScreenshotReady ->
                "Captura de pantalla disponible en ${event.imagePath} (${event.width}x${event.height}). " +
                        "El árbol de accesibilidad estaba vacío - se necesita visión artificial."

            is PerceptionEvent.NotificationReceived ->
                "Notificación ${if (event.isUrgent) "URGENTE" else "normal"} de ${event.packageName}: " +
                        "\"${event.title}\" - \"${event.text.take(100)}\" (categoría: ${event.category.name})"

            is PerceptionEvent.HardwareStateUpdate ->
                "Estado del hardware: Batería ${event.batteryLevel}% " +
                        "${if (event.isCharging) "(cargando" + if (event.isBypassCharging) " con Bypass" else "" + ")" else ""}, " +
                        "Actividad: ${event.currentActivity.name}, Pasos: ${event.stepCount}"
        }
    }

    /**
     * Construye un resumen del hardware para el system prompt.
     */
    private fun buildHardwareSummary(event: PerceptionEvent): String {
        return if (event is PerceptionEvent.HardwareStateUpdate) {
            "Batería: ${event.batteryLevel}% ${if (event.isBypassCharging) "(Bypass Charging activo)" else ""}\n" +
                    "Ubicación: ${event.latitude?.let { "$it, ${event.longitude}" } ?: "no disponible"}\n" +
                    "Actividad: ${event.currentActivity.name}\n" +
                    "Pasos hoy: ${event.stepCount}"
        } else {
            "Consulta al módulo de hardware para estado actualizado."
        }
    }

    /**
     * Parsea la respuesta del LLM para extraer la acción o respuesta directa.
     */
    private fun parseThinkResult(response: String): ThinkResult {
        // Buscar tool calls en la respuesta
        val toolMatch = TOOL_CALL_PATTERN.find(response)

        if (toolMatch != null) {
            val toolName = toolMatch.groupValues[1]
            val params = toolMatch.groupValues[2]
            val isDestructive = ToolRegistry.isDestructive(toolName)

            return ThinkResult(
                isDirectResponse = false,
                toolCall = ToolCall(
                    toolName = toolName,
                    parameters = params,
                    isDestructive = isDestructive,
                    rawThought = response
                ),
                rawResponse = response
            )
        }

        // No se encontró tool call - respuesta directa
        return ThinkResult(
            isDirectResponse = true,
            toolCall = null,
            response = response.trim(),
            rawResponse = response
        )
    }

    /**
     * Establece el perfil de autonomía.
     */
    fun setAutonomyProfile(profile: AutonomyProfile) {
        autonomyProfile = profile
        Log.i(TAG, "Perfil de autonomía cambiado a: ${profile.name}")
    }

    /**
     * Agrega un turno al historial de conversación.
     */
    fun addToHistory(role: String, content: String) {
        conversationHistory.add(role to content)
        // Mantener solo los últimos 10 turnos
        if (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
        }
    }

    /**
     * Limpia el historial de conversación.
     */
    fun clearHistory() {
        conversationHistory.clear()
    }

    fun destroy() {
        scope.cancel()
    }
}

// ==================== MODELOS DE DATOS ====================

/**
 * Contexto acumulado del Agent Loop.
 */
data class AgentContext(
    val originalEvent: PerceptionEvent,
    val observations: MutableList<String>,
    val actions: MutableList<ToolCall>,
    val results: MutableList<ToolResult>,
    var memoryHints: String = ""
)

/**
 * Resultado de la fase de Pensar.
 */
data class ThinkResult(
    val isDirectResponse: Boolean,
    val toolCall: ToolCall?,
    val response: String = "",
    val rawResponse: String
)

/**
 * Representa una llamada a herramienta parseada del LLM.
 */
data class ToolCall(
    val toolName: String,
    val parameters: String,
    val isDestructive: Boolean = false,
    val rawThought: String = ""
)

/**
 * Resultado de ejecutar una herramienta.
 */
sealed class ToolResult {
    abstract val summary: String

    data class Success(override val summary: String) : ToolResult()
    data class PartialSuccess(
        override val summary: String,
        val successPart: String,
        val failurePart: String
    ) : ToolResult()

    data class Failure(override val summary: String, val error: String) : ToolResult()
    data class NeedsInput(override val summary: String, val question: String) : ToolResult()
}

/**
 * Resultado final del Agent Loop.
 */
sealed class AgentResult {
    data class Success(val response: String) : AgentResult()
    data class Failure(val reason: String) : AgentResult()
    data class Cancelled(val reason: String) : AgentResult()
}
