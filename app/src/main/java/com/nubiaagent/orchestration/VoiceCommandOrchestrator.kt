package com.nubiaagent.orchestration

import android.content.Context
import android.util.Log
import com.nubiaagent.cognitive.agent.AgentLoop
import com.nubiaagent.cognitive.agent.ToolExecutor
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.cognitive.identity.IdentityManager
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.cognitive.voice.VoiceEngine
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import com.nubiaagent.NubiaAgentApp
import kotlinx.coroutines.*

/**
 * VoiceCommandOrchestrator: Puente entre PerceptionBus y AgentLoop.
 *
 * Este singleton resuelve la desconexión crítica del pipeline de voz:
 * WakeWordService emite VoiceCommand al PerceptionBus, pero nadie
 * lo consumía para pasarlo al AgentLoop. Este orquestador se suscribe
 * al bus y enruta los comandos de voz finales al cerebro del agente.
 *
 * ARQUITECTURA:
 * ```
 * WakeWordService → PerceptionBus.VoiceCommand
 *                          ↓
 *               VoiceCommandOrchestrator
 *                          ↓
 *                    AgentLoop.processEvent()
 *                          ↓
 *                   CognitiveEngine.infer()
 *                          ↓
 *                    VoiceEngine.speak()
 * ```
 *
 * CICLO DE VIDA:
 * - initialize(): Crea AgentLoop con dependencias reales y se suscribe al bus.
 * - destroy(): Cancela la suscripción y libera el AgentLoop.
 *
 * THREAD SAFETY:
 * - La instancia se almacena en un @Volatile con double-checked locking.
 * - El scope usa SupervisorJob para que un fallo en processEvent no
 *   cancele la suscripción entera.
 * - Solo se procesan eventos VoiceCommand con isFinal == true y
 *   transcript.isNotBlank() para evitar comandos parciales.
 */
class VoiceCommandOrchestrator private constructor(private val context: Context) {

    companion object {
        private const val TAG = "Dayana/Orchestrator"

        @Volatile
        private var instance: VoiceCommandOrchestrator? = null

        fun getInstance(context: Context): VoiceCommandOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: VoiceCommandOrchestrator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var agentLoop: AgentLoop? = null

    @Volatile
    private var isInitialized = false

    private var collectionJob: Job? = null

    /**
     * Inicializa el orquestador: crea AgentLoop con dependencias reales
     * y se suscribe al PerceptionBus para capturar VoiceCommand finales.
     *
     * Este método es idempotente — llamarlo múltiples veces no tiene efecto
     * secundario más allá del primer invocation.
     *
     * @param cognitiveEngine El motor de inferencia (provido por CognitiveEngine.onCreate)
     */
    @Synchronized
    fun initialize(cognitiveEngine: CognitiveEngine) {
        if (isInitialized) {
            Log.d(TAG, "Ya inicializado, omitiendo re-inicialización")
            return
        }

        Log.i(TAG, "Inicializando orquestador de comandos de voz...")

        // Obtener dependencias desde singletons/injectores existentes
        val appContext = context.applicationContext
        val identityManager = IdentityManager(appContext)
        // loadSoul() es suspend — se usa runBlocking para garantizar que el alma
        // esté cargada antes de crear AgentLoop. initialize() es @Synchronized
        // y se invoca desde CognitiveEngine.onCreate() (LifecycleService).
        runBlocking {
            identityManager.loadSoul()
        }

        val memoryManager = MemoryManager.getInstance(appContext)

        val toolExecutor = ToolExecutor(appContext)

        // VoiceEngine se accede via NubiaAgentApp (lateinit var global)
        val voiceEngine: VoiceEngine? = try {
            (appContext as? NubiaAgentApp)?.voiceEngine
        } catch (e: UninitializedPropertyAccessException) {
            Log.w(TAG, "VoiceEngine aún no inicializado en NubiaAgentApp")
            null
        }

        // Inyectar dependencias en ToolExecutor
        voiceEngine?.let { toolExecutor.setVoiceEngine(it) }
        toolExecutor.setMemoryManager(memoryManager)

        // Crear AgentLoop con todas las dependencias cableadas
        agentLoop = AgentLoop(
            cognitiveEngine = cognitiveEngine,
            identityManager = identityManager,
            memoryManager = memoryManager,
            toolExecutor = toolExecutor,
            voiceEngine = voiceEngine
        )

        Log.i(TAG, "AgentLoop instanciado con dependencias: " +
                "identityManager=OK, memoryManager=OK, toolExecutor=OK, " +
                "voiceEngine=${if (voiceEngine != null) "OK" else "NO_DISPONIBLE"}")

        // Suscribirse al PerceptionBus para capturar VoiceCommand
        subscribeToPerceptionBus()

        isInitialized = true
        Log.i(TAG, "★ Orquestador de voz inicializado — pipeline conectado")
    }

    /**
     * Se suscribe a PerceptionBus.events y filtra solo VoiceCommand
     * con isFinal == true y transcript no vacío.
     *
     * Los comandos parciales (isFinal == false) se ignoran para no
     * disparar el AgentLoop múltiples veces por una sola utterance.
     */
    private fun subscribeToPerceptionBus() {
        collectionJob?.cancel()
        collectionJob = scope.launch {
            PerceptionBus.events.collect { event ->
                if (event is PerceptionEvent.VoiceCommand) {
                    if (event.isFinal && event.transcript.isNotBlank()) {
                        Log.i(TAG, "VoiceCommand final recibido: \"${event.transcript}\" " +
                                "(confianza: ${event.confidence})")
                        dispatchToAgentLoop(event)
                    } else {
                        Log.d(TAG, "VoiceCommand parcial ignorado: \"${event.transcript.take(30)}\"")
                    }
                }
            }
        }
        Log.d(TAG, "Suscrito a PerceptionBus.events — esperando VoiceCommand")
    }

    /**
     * Despacha un PerceptionEvent al AgentLoop para procesamiento.
     *
     * Se ejecuta en el scope del orquestador (Dispatchers.Default) para
     * no bloquear la coroutine de colección del PerceptionBus. Si el
     * AgentLoop ya está procesando un comando (loopState != IDLE), se
     * encola para evitar conflictos de estado.
     */
    private fun dispatchToAgentLoop(event: PerceptionEvent) {
        val loop = agentLoop
        if (loop == null) {
            Log.e(TAG, "AgentLoop no disponible — comando perdido: " +
                    "${(event as? PerceptionEvent.VoiceCommand)?.transcript}")
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "Despachando evento al AgentLoop...")
                val result = loop.processEvent(event)

                when (result) {
                    is com.nubiaagent.cognitive.agent.AgentResult.Success -> {
                        Log.i(TAG, "AgentLoop completó exitosamente: " +
                                "\"${result.response.take(80)}...\"")
                        // Emitir respuesta del agente al PerceptionBus
                        // para que los componentes UI reaccionen
                        PerceptionBus.tryEmit(
                            PerceptionEvent.AgentResponse(
                                text = result.response,
                                isSuccessful = true
                            )
                        )
                    }
                    is com.nubiaagent.cognitive.agent.AgentResult.Failure -> {
                        Log.w(TAG, "AgentLoop falló: ${result.reason}")
                        PerceptionBus.tryEmit(
                            PerceptionEvent.AgentResponse(
                                text = result.reason,
                                isSuccessful = false
                            )
                        )
                    }
                    is com.nubiaagent.cognitive.agent.AgentResult.Cancelled -> {
                        Log.i(TAG, "AgentLoop cancelado: ${result.reason}")
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Despacho cancelado")
            } catch (e: Exception) {
                Log.e(TAG, "Error despachando evento al AgentLoop", e)
            }
        }
    }

    /**
     * Libera los recursos del orquestador.
     * Cancela la suscripción al PerceptionBus y destruye el AgentLoop.
     */
    fun destroy() {
        collectionJob?.cancel()
        collectionJob = null
        agentLoop?.destroy()
        agentLoop = null
        isInitialized = false
        scope.cancel()
        Log.i(TAG, "Orquestador destruido")
    }
}
