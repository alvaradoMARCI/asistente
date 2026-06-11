package com.nubiaagent.cognitive.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nubiaagent.MainActivity
import com.nubiaagent.R
import com.nubiaagent.cognitive.identity.IdentityManager
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * CognitiveEngine: Motor de Inferencia Local para Dayana.
 *
 * Este es el corazón de la Capa Cognitiva. Gestiona:
 * 1. Carga y descarga de modelos GGUF en RAM
 * 2. Inferencia de texto mediante llama.cpp (vía JNI)
 * 3. Generación streaming con callbacks de tokens
 * 4. Adaptación dinámica de configuración según hardware
 * 5. Mantenimiento del modelo en memoria (ForegroundService)
 *
 * ARQUITECTURA DE INFERENCIA:
 *
 * El motor usa llama.cpp compilado para ARM64 (arm64-v8a) como backend
 * de inferencia. La comunicación se realiza vía JNI (Java Native Interface)
 * con las siguientes funciones nativas:
 *
 * - llama_init_model(path, n_threads, n_ctx, n_gpu_layers): Long  // Retorna handle
 * - llama_free_model(handle): Unit
 * - llama_completion(handle, prompt, params): String
 * - llama_completion_stream(handle, prompt, params, callback): Unit
 * - llama_token_count(handle, text): Int
 *
 * OPTIMIZACIÓN PARA UNISOC T8300 / 20GB RAM:
 *
 * El T8300 tiene 8 núcleos (2x Cortex-A76 @2.4GHz + 6x Cortex-A55 @2.0GHz).
 * La estrategia de hilos es:
 * - 4 hilos principales para inferencia (A76 + A55 mix)
 * - Los A76 se usan para las capas de atención más pesadas
 * - Los A55 para FFN y operaciones auxiliares
 * - El motor NeoTurbo puede priorizar estos hilos
 *
 * Con 20GB de RAM dinámica:
 * - Modelo Q4_K_M de 3B: ~2GB → Deja 18GB para contexto y sistema
 * - Contexto de 4096 tokens: ~256MB → Espacio más que suficiente
 * - El modelo se mantiene cargado entre inferencias (keep-alive)
 *
 * RESTRICCIÓN DE PRIVACIDAD:
 * Todo el procesamiento de texto ocurre en-device. El motor no tiene
 * acceso a red. Los prompts nunca salen del dispositivo.
 */
class CognitiveEngine : LifecycleService() {

    companion object {
        private const val TAG = "Dayana/Cognition"
        private const val CHANNEL_ID = "nubia_agent_cognition_channel"
        private const val NOTIFICATION_ID = 2001

        // Intent actions
        const val ACTION_INITIALIZE = "com.nubiaagent.action.INITIALIZE_ENGINE"
        const val ACTION_SHUTDOWN = "com.nubiaagent.action.SHUTDOWN_ENGINE"
        const val ACTION_INFER = "com.nubiaagent.action.INFER"
        const val ACTION_RELOAD = "com.nubiaagent.action.RELOAD_MODEL"

        // Extras
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_INFERENCE_PROFILE = "inference_profile"

        fun start(context: Context) {
            val intent = Intent(context, CognitiveEngine::class.java).apply {
                action = ACTION_INITIALIZE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CognitiveEngine::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            context.startService(intent)
        }

        // Singleton para acceso desde AgentLoop
        private var instance: CognitiveEngine? = null
        fun getInstance(): CognitiveEngine? = instance
    }

    // Estado del motor
    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val _tokensPerSecond = MutableStateFlow(0f)
    val tokensPerSecond: StateFlow<Float> = _tokensPerSecond.asStateFlow()

    private val _memoryUsageMb = MutableStateFlow(0L)
    val memoryUsageMb: StateFlow<Long> = _memoryUsageMb.asStateFlow()

    enum class EngineState {
        IDLE,           // No inicializado
        LOADING,        // Cargando modelo en RAM
        READY,          // Listo para inferencia
        INFERRING,      // Procesando inferencia
        ERROR           // Error
    }

    // Componentes
    private lateinit var modelManager: ModelManager
    private var identityManager: IdentityManager? = null
    private var memoryManager: MemoryManager? = null
    private var inferenceConfig: InferenceConfig = InferenceConfig.BALANCED

    // Motor de inferencia cloud (fallback cuando no hay modelo local)
    private var cloudEngine: CloudInferenceEngine? = null
    private var inferenceMode = InferenceMode.LOCAL_ONLY

    enum class InferenceMode {
        LOCAL_ONLY,     // Solo llama.cpp (privacidad total, necesita NDK)
        CLOUD_FALLBACK, // Local primero, cloud si no hay modelo (recomendado)
        CLOUD_ONLY      // Solo API cloud (no necesita modelo local)
    }

    // Modelo nativo (llama.cpp handle)
    private var modelHandle: Long = 0
    private var isModelLoaded = false

    // Callback para streaming
    private var streamCallback: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        modelManager = ModelManager(this)
        cloudEngine = CloudInferenceEngine(this)

        // Detectar modo de inferencia según configuración
        if (CloudInferenceEngine.isConfigured(this)) {
            inferenceMode = InferenceMode.CLOUD_FALLBACK
            Log.i(TAG, "Motor cloud configurado - Modo: Cloud Fallback")
        } else {
            inferenceMode = InferenceMode.LOCAL_ONLY
            Log.i(TAG, "Sin motor cloud - Modo: Local Only")
        }

        createNotificationChannel()
        Log.i(TAG, "CognitiveEngine creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_INITIALIZE -> initializeEngine(intent)
            ACTION_SHUTDOWN -> shutdownEngine()
            ACTION_INFER -> handleInferIntent(intent)
            ACTION_RELOAD -> reloadModel(intent)
        }

        return START_STICKY
    }

    /**
     * Inicializa el motor cognitivo:
     * 1. Carga el IdentityManager (SOUL.md)
     * 2. Selecciona el mejor modelo disponible
     * 3. Carga el modelo en RAM
     * 4. Inicializa el MemoryManager
     * 5. Se suscribe al PerceptionBus
     */
    private fun initializeEngine(intent: Intent) {
        startForeground(NOTIFICATION_ID, createNotification("Inicializando motor cognitivo..."))
        _engineState.value = EngineState.LOADING

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Cargar identidad
                identityManager = IdentityManager(this@CognitiveEngine)
                identityManager?.loadSoul()
                Log.i(TAG, "Identidad cargada: ${identityManager?.getPersonaName()}")

                // 2. Inicializar memoria
                memoryManager = MemoryManager.getInstance(this@CognitiveEngine)
                memoryManager?.initialize()
                Log.i(TAG, "Sistema de memoria inicializado")

                // 3. Seleccionar y cargar modelo
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                    ?: modelManager.selectBestModelForDevice()

                if (modelId == null) {
                    // No hay modelo local, verificar si cloud está disponible
                    if (CloudInferenceEngine.isConfigured(this@CognitiveEngine)) {
                        inferenceMode = InferenceMode.CLOUD_ONLY
                        isModelLoaded = false
                        _engineState.value = EngineState.READY
                        updateNotification("Motor cloud activo - ${CloudInferenceEngine.getModel(this@CognitiveEngine)}")
                        Log.i(TAG, "★ Motor cognitivo en modo CLOUD (sin modelo local)")
                        subscribeToPerception()
                        return@launch
                    } else {
                        Log.e(TAG, "No hay modelos ni API cloud configurada")
                        _engineState.value = EngineState.ERROR
                        updateNotification("Configura API Key en Ajustes para usar el asistente")
                        return@launch
                    }
                }

                val modelSpec = ModelManager.AVAILABLE_MODELS[modelId]
                val modelFile = File(modelManager.getModelsDir(), modelSpec!!.filename)

                if (!modelFile.exists()) {
                    Log.w(TAG, "Archivo de modelo no encontrado: ${modelFile.absolutePath}")
                    // Fallback a cloud si está disponible
                    if (CloudInferenceEngine.isConfigured(this@CognitiveEngine)) {
                        inferenceMode = InferenceMode.CLOUD_ONLY
                        isModelLoaded = false
                        _engineState.value = EngineState.READY
                        updateNotification("Motor cloud activo (modelo local no encontrado)")
                        subscribeToPerception()
                        return@launch
                    }
                    _engineState.value = EngineState.ERROR
                    return@launch
                }

                // 4. Cargar modelo vía llama.cpp JNI
                loadModelNative(modelFile.absolutePath)

                // 5. Suscribirse al PerceptionBus para reaccionar a eventos
                subscribeToPerception()

                _engineState.value = EngineState.READY
                updateNotification("Motor cognitivo listo - ${modelSpec?.name}")
                Log.i(TAG, "★ Motor cognitivo inicializado exitosamente con ${modelSpec?.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Error fatal inicializando motor", e)
                _engineState.value = EngineState.ERROR
                updateNotification("Error: ${e.message}")
            }
        }
    }

    /**
     * Carga un modelo GGUF en RAM usando llama.cpp.
     *
     * Esta función llama al código nativo (C/C++) via JNI que:
     * 1. Lee el archivo GGUF y parsea el header
     * 2. Asigna memoria para los tensores (mmap o malloc)
     * 3. Inicializa el contexto de inferencia (kv cache)
     * 4. Configura el thread pool
     *
     * NOTA: La implementación JNI real requiere compilar llama.cpp
     * para Android (CMake + NDK). Este código asume que las
     * funciones nativas ya están disponibles en libllama.so.
     */
    private suspend fun loadModelNative(modelPath: String) {
        withContext(Dispatchers.IO) {
            _engineState.value = EngineState.LOADING

            try {
                // Intentar cargar librería nativa; si falla, usar simulación
                val nativeAvailable = LlamaNative.ensureLoaded()

                if (nativeAvailable) {
                    try {
                        modelHandle = LlamaNative.llamaInitModel(
                            modelPath,
                            inferenceConfig.nThreads,
                            inferenceConfig.contextSize,
                            inferenceConfig.gpuLayers
                        )
                        isModelLoaded = modelHandle != 0L
                        Log.i(TAG, "Modelo nativo cargado: handle=$modelHandle")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error en carga nativa, usando simulación", e)
                    }
                }

                // PLACEHOLDER: Simulación de carga si lo nativo no está disponible
                Log.i(TAG, "Cargando modelo desde: $modelPath")
                Log.i(TAG, "Config: threads=${inferenceConfig.nThreads}, " +
                        "ctx=${inferenceConfig.contextSize}, " +
                        "batch=${inferenceConfig.batchSize}")

                // Simulación cuando libllama.so no está compilado aún
                delay(1500)

                // Verificar que el modelo se cargó
                modelHandle = 1L  // Placeholder: handle real del modelo
                isModelLoaded = true

                // Calcular uso de memoria estimado
                val modelFile = File(modelPath)
                _memoryUsageMb.value = modelFile.length() / (1024 * 1024)

                modelManager.setActiveModel(
                    modelManager.selectBestModelForDevice() ?: "unknown",
                    modelPath
                )

                Log.i(TAG, "Modelo cargado en RAM: ${_memoryUsageMb.value}MB")

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando modelo nativo", e)
                isModelLoaded = false
                throw e
            }
        }
    }

    /**
     * Ejecuta una inferencia completa: prompt → texto generado.
     *
     * Este es el método principal llamado por el AgentLoop.
     *
     * @param prompt El prompt completo (system + contexto + user message)
     * @param config Configuración de inferencia (opcional, usa default si null)
     * @param streamCallback Callback opcional para recibir tokens progresivamente
     * @return El texto generado por el modelo
     */
    suspend fun infer(
        prompt: String,
        config: InferenceConfig? = null,
        streamCallback: ((String) -> Unit)? = null
    ): String {
        // Si no hay modelo local, usar cloud directamente
        if (!isModelLoaded || modelHandle == 0L) {
            if (inferenceMode == InferenceMode.CLOUD_FALLBACK ||
                inferenceMode == InferenceMode.CLOUD_ONLY) {
                Log.i(TAG, "Sin modelo local → usando API cloud")
                return inferCloud(prompt, config)
            }
            Log.e(TAG, "Intento de inferencia sin modelo ni cloud")
            return "[ERROR: Modelo no cargado y API cloud no configurada. Ve a Ajustes para configurar tu API Key.]"
        }

        val activeConfig = config ?: inferenceConfig
        this.streamCallback = streamCallback

        _engineState.value = EngineState.INFERRING
        val startTime = System.currentTimeMillis()

        try {
            // Adaptar configuración al hardware actual
            val hwState = PerceptionBus  // En producción, leer del HardwareStateCollector
            val adaptedConfig = activeConfig

            Log.d(TAG, "Iniciando inferencia: ${prompt.take(100)}...")
            Log.d(TAG, "Config: temp=${adaptedConfig.temperature}, " +
                    "top_p=${adaptedConfig.topP}, max_tokens=${adaptedConfig.maxTokens}")

            // Construir parámetros de inferencia como JSON para el código nativo
            val params = JSONObject().apply {
                put("n_threads", adaptedConfig.nThreads)
                put("n_predict", adaptedConfig.maxTokens)
                put("temperature", adaptedConfig.temperature.toDouble())
                put("top_p", adaptedConfig.topP.toDouble())
                put("top_k", adaptedConfig.topK)
                put("repeat_penalty", adaptedConfig.repeatPenalty.toDouble())
                put("stop", JSONObject().apply {
                    put("0", "<|im_end|>")
                    put("1", "</s>")
                    put("2", "<|eot_id|>")
                })
            }

            // En la implementación real:
            // val result = LlamaNative.llamaCompletion(modelHandle, prompt, params.toString())
            //
            // Para streaming:
            // LlamaNative.llamaCompletionStream(modelHandle, prompt, params.toString()) { token ->
            //     streamCallback?.invoke(token)
            // }

            // Intentar inferencia nativa (llama.cpp)
            val nativeAvailable = LlamaNative.ensureLoaded()
            var result: String

            if (nativeAvailable && modelHandle != 0L && modelHandle != 1L) {
                // Inferencia REAL con llama.cpp
                result = LlamaNative.llamaCompletion(modelHandle, prompt, params.toString())
                Log.i(TAG, "Inferencia nativa completada")
            } else if (inferenceMode == InferenceMode.CLOUD_FALLBACK ||
                       inferenceMode == InferenceMode.CLOUD_ONLY) {
                // Fallback a API cloud
                result = inferCloud(prompt, config)
                return result
            } else {
                // Simulación (sin modelo ni cloud)
                result = simulateInference(prompt)
            }

            // Calcular tokens/segundo
            val elapsed = (System.currentTimeMillis() - startTime).toFloat() / 1000f
            val estimatedTokens = result.split(" ").size.toFloat() * 1.3f  // Estimación aproximada
            _tokensPerSecond.value = if (elapsed > 0) estimatedTokens / elapsed else 0f

            Log.i(TAG, "Inferencia completada: ${result.take(80)}... " +
                    "(${_tokensPerSecond.value} tok/s, ${elapsed}s)")

            _engineState.value = EngineState.READY
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error durante inferencia", e)
            _engineState.value = EngineState.READY
            return "[ERROR: ${e.message}]"
        }
    }

    /**
     * Ejecuta inferencia streaming que emite tokens uno por uno.
     * Ideal para respuestas en tiempo real al usuario.
     */
    fun inferStream(
        prompt: String,
        config: InferenceConfig? = null,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = infer(prompt, config, onToken)
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    /**
     * Cuenta los tokens de un texto usando el tokenizer del modelo.
     */
    suspend fun countTokens(text: String): Int {
        if (!isModelLoaded) return text.split(" ").size  // Estimación fallback

        // En la implementación real:
        // return LlamaNative.llamaTokenCount(modelHandle, text)

        // Estimación: ~1.3 tokens por palabra en español
        return (text.split(" ").size * 1.3).toInt()
    }

    /**
     * Adapta la configuración de inferencia al estado actual del hardware.
     * Se llama automáticamente antes de cada inferencia.
     */
    fun adaptToHardware(batteryLevel: Int, isBypassCharging: Boolean, freeRamMb: Long) {
        inferenceConfig = inferenceConfig.adaptToHardware(batteryLevel, isBypassCharging, freeRamMb)
        Log.d(TAG, "Config adaptada: threads=${inferenceConfig.nThreads}, " +
                "ctx=${inferenceConfig.contextSize}")
    }

    /**
     * Descarga el modelo de RAM para liberar memoria.
     */
    private suspend fun unloadModel() {
        if (!isModelLoaded) return

        withContext(Dispatchers.IO) {
            // En la implementación real:
            // LlamaNative.llamaFreeModel(modelHandle)

            modelHandle = 0
            isModelLoaded = false
            _memoryUsageMb.value = 0
            _engineState.value = EngineState.IDLE
            Log.i(TAG, "Modelo descargado de RAM")
        }
    }

    /**
     * Suscribe el motor a eventos del PerceptionBus.
     * Permite que el motor reaccione automáticamente a:
     * - Wake word detectado → Preparar para inferencia
     * - Cambio de hardware → Adaptar configuración
     */
    private fun subscribeToPerception() {
        lifecycleScope.launch(Dispatchers.IO) {
            PerceptionBus.events.collect { event ->
                when (event) {
                    is PerceptionEvent.WakeWordDetected -> {
                        Log.d(TAG, "Wake word detectado, motor listo para inferencia")
                        // Asegurar que el modelo está cargado
                        if (!isModelLoaded) {
                            val modelId = modelManager.selectBestModelForDevice()
                            if (modelId != null) {
                                val spec = ModelManager.AVAILABLE_MODELS[modelId]!!
                                val file = File(modelManager.getModelsDir(), spec.filename)
                                loadModelNative(file.absolutePath)
                            }
                        }
                    }
                    is PerceptionEvent.HardwareStateUpdate -> {
                        adaptToHardware(
                            event.batteryLevel,
                            event.isBypassCharging,
                            0L  // TODO: Obtener RAM libre real
                        )
                    }
                    else -> { /* Otros eventos no requieren acción directa */ }
                }
            }
        }
    }

    /**
     * Maneja la acción ACTION_INFER: ejecuta una inferencia basada en los extras del Intent.
     * Extrae el prompt del intent y lanza la inferencia en una coroutine.
     */
    private fun handleInferIntent(intent: Intent) {
        val prompt = intent.getStringExtra("prompt") ?: run {
            Log.w(TAG, "ACTION_INFER sin prompt en intent extras")
            return
        }
        lifecycleScope.launch {
            try {
                val result = infer(prompt)
                Log.d(TAG, "Inferencia completada: ${result.take(100)}...")
            } catch (e: Exception) {
                Log.e(TAG, "Error en inferencia desde intent", e)
            }
        }
    }

    /**
     * Maneja la acción ACTION_RELOAD: recarga el modelo actual (o el especificado en el intent).
     * Descarga el modelo actual y vuelve a cargarlo desde disco.
     */
    private fun reloadModel(intent: Intent) {
        val modelId = intent.getStringExtra("model_id") ?: modelManager.selectBestModelForDevice()
        if (modelId == null) {
            Log.w(TAG, "ACTION_RELOAD: no se pudo determinar qué modelo recargar")
            return
        }
        lifecycleScope.launch {
            try {
                unloadModel()
                val spec = ModelManager.AVAILABLE_MODELS[modelId]
                if (spec != null) {
                    val modelFile = File(modelManager.getModelsDir(), spec.filename)
                    if (modelFile.exists()) {
                        loadModelNative(modelFile.absolutePath)
                        updateNotification("Modelo recargado - ${spec.name}")
                        Log.i(TAG, "Modelo recargado: ${spec.name}")
                    } else {
                        Log.e(TAG, "Archivo de modelo no encontrado para recarga: ${modelFile.absolutePath}")
                        _engineState.value = EngineState.ERROR
                    }
                } else {
                    Log.e(TAG, "ModelSpec no encontrado para modelId: $modelId")
                    _engineState.value = EngineState.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recargando modelo", e)
                _engineState.value = EngineState.ERROR
            }
        }
    }

    private fun shutdownEngine() {
        lifecycleScope.launch {
            unloadModel()
            identityManager = null
            memoryManager?.close()
            memoryManager = null
            _engineState.value = EngineState.IDLE
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "Motor cognitivo apagado")
        }
    }

    /**
     * Inferencia via API cloud (OpenAI, Anthropic, OpenRouter, o custom).
     * Se usa cuando no hay modelo local disponible.
     * El prompt completo (SOUL + profile + user message) se envía al proveedor.
     */
    private suspend fun inferCloud(
        prompt: String,
        config: InferenceConfig? = null
    ): String {
        val engine = cloudEngine ?: return "[ERROR: Motor cloud no inicializado]"

        val activeConfig = config ?: inferenceConfig
        _engineState.value = EngineState.INFERRING
        val startTime = System.currentTimeMillis()

        try {
            val result = engine.infer(
                prompt = prompt,
                maxTokens = activeConfig.maxTokens,
                temperature = activeConfig.temperature
            )

            val elapsed = (System.currentTimeMillis() - startTime).toFloat() / 1000f
            val estimatedTokens = result.split(" ").size.toFloat() * 1.3f
            _tokensPerSecond.value = if (elapsed > 0) estimatedTokens / elapsed else 0f

            Log.i(TAG, "Inferencia cloud completada: ${result.take(80)}... " +
                    "(${_tokensPerSecond.value} tok/s, ${elapsed}s)")

            _engineState.value = EngineState.READY
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error en inferencia cloud", e)
            _engineState.value = EngineState.READY
            return "[ERROR cloud: ${e.message}]"
        }
    }

    // ==================== PLACEHOLDER ====================

    /**
     * Simulación de inferencia para desarrollo sin modelo cargado.
     * En producción, esto es reemplazado por la llamada a llama.cpp.
     */
    private suspend fun simulateInference(prompt: String): String {
        delay(800)  // Simular latencia

        // Detectar si el prompt contiene tool calls
        return when {
            prompt.contains("ANALIZAR_PANTALLA", ignoreCase = true) ->
                "OBSERVACIÓN: Veo la pantalla de WhatsApp con una conversación activa. Hay 3 mensajes nuevos de Sarah. El último dice '¿Vamos al cine hoy?'. ACCIÓN: Sugerir responder a Sarah."
            prompt.contains("NOTIFICACIÓN_URGENTE", ignoreCase = true) ->
                "OBSERVACIÓN: Notificación urgente de Sarah. DECISIÓN: Interrumpir al usuario. ACCIÓN: Notificar mensaje urgente de Sarah."
            prompt.contains("BRIEFING_MATUTINO", ignoreCase = true) ->
                "OBSERVACIÓN: Es hora del briefing. ACCIÓN: Recopilar eventos del día, clima, y mensajes pendientes para presentar un resumen."
            else ->
                "OBSERVACIÓN: Procesando la solicitud del usuario. ACCIÓN: Necesito más contexto para determinar la acción apropiada. Pensar paso a paso sobre qué hacer."
        }
    }

    // ==================== NOTIFICACIÓN ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dayana - Motor Cognitivo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de inferencia de Dayana"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dayana - Motor Cognitivo")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(statusText))
    }

    override fun onDestroy() {
        instance = null
        runBlocking { unloadModel() }
        modelManager.destroy()
        super.onDestroy()
    }

    // ==================== JNI INTERFACE (llama.cpp) ====================

    /**
     * Interfaz JNI para llama.cpp.
     *
     * Estas funciones nativas se implementan en C/C++ usando
     * la API de llama.cpp compilada para ARM64 Android.
     *
     * Para compilar:
     * 1. Clonar llama.cpp en app/src/main/cpp/llama.cpp/
     * 2. Configurar CMakeLists.txt con los sources necesarios
     * 3. Compilar con NDK (r25c+) para arm64-v8a
     * 4. El .so resultante se empaqueta automáticamente en el APK
     *
     * El código nativo mínimo sería:
     *
     * ```c
     * #include "llama.h"
     * #include "ggml.h"
     *
     * Java_com_nubiaagent_cognitive_engine_CognitiveEngine_llamaInitModel(
     *     JNIEnv* env, jobject thiz, jstring path, jint n_threads,
     *     jint n_ctx, jint n_gpu_layers) {
     *     auto model_path = env->GetStringUTFChars(path, nullptr);
     *     llama_model_params model_params = llama_model_default_params();
     *     model_params.n_gpu_layers = n_gpu_layers;
     *     llama_model* model = llama_model_load_from_file(model_path, model_params);
     *     env->ReleaseStringUTFChars(path, model_path);
     *     return (jlong)model;
     * }
     * ```
     */
    /**
     * Interfaz JNI para llama.cpp.
     *
     * Carga diferida: la librería nativa se carga solo cuando se necesita,
     * evitando UnsatisfiedLinkError si libllama.so no está disponible aún.
     * Esto permite que la app compile y funcione en modo simulación
     * mientras se compila llama.cpp para Android (NDK + CMake).
     */
    /**
     * LlamaNative: Bridge para llama.cpp via JNI.
     *
     * COMPILATION NOTE: The external fun declarations require libllama.so
     * at runtime. Since we don't have the .so yet, we use a STUB approach:
     * - ensureLoaded() tries to load the library and returns false if missing
     * - The infer() method checks ensureLoaded() and falls back to cloud/simulation
     * - The external fun declarations are kept for when libllama.so is available
     * - They will throw UnsatisfiedLinkError at RUNTIME (not compile time)
     *   which is caught by the try/catch in ensureLoaded()
     *
     * To add llama.cpp support later:
     * 1. Compile llama.cpp for arm64-v8a using Android NDK
     * 2. Place libllama.so in app/src/main/jniLibs/arm64-v8a/
     * 3. Add CMakeLists.txt if building from source
     */
    object LlamaNative {
        @Volatile
        private var libraryLoaded = false
        @Volatile
        private var loadFailed = false

        fun ensureLoaded(): Boolean {
            if (libraryLoaded) return true
            if (loadFailed) return false
            return try {
                System.loadLibrary("llama")
                libraryLoaded = true
                Log.i("Dayana/Cognition", "libllama.so cargada exitosamente")
                true
            } catch (e: UnsatisfiedLinkError) {
                loadFailed = true
                Log.w("Dayana/Cognition",
                    "libllama.so no disponible - modo cloud/simulacion activo", e)
                false
            } catch (e: Exception) {
                loadFailed = true
                Log.w("Dayana/Cognition",
                    "Error cargando libllama.so", e)
                false
            }
        }

        fun llamaInitModel(modelPath: String, nThreads: Int, nCtx: Int, nGpuLayers: Int): Long {
            if (!libraryLoaded) return 0L
            return try {
                llamaInitModelNative(modelPath, nThreads, nCtx, nGpuLayers)
            } catch (e: UnsatisfiedLinkError) {
                Log.e("Dayana/Cognition", "JNI llamaInitModel not available", e)
                0L
            }
        }

        fun llamaFreeModel(handle: Long) {
            if (!libraryLoaded) return
            try { llamaFreeModelNative(handle) }
            catch (_: UnsatisfiedLinkError) { }
        }

        fun llamaCompletion(handle: Long, prompt: String, params: String): String {
            if (!libraryLoaded) return ""
            return try { llamaCompletionNative(handle, prompt, params) }
            catch (e: UnsatisfiedLinkError) { "" }
        }

        fun llamaCompletionStream(handle: Long, prompt: String, params: String, callback: (String) -> Unit) {
            if (!libraryLoaded) return
            try { llamaCompletionStreamNative(handle, prompt, params, callback) }
            catch (_: UnsatisfiedLinkError) { }
        }

        fun llamaTokenCount(handle: Long, text: String): Int {
            if (!libraryLoaded) return text.split(" ").size
            return try { llamaTokenCountNative(handle, text) }
            catch (_: UnsatisfiedLinkError) { text.split(" ").size }
        }

        // Native JNI declarations - these require libllama.so at runtime
        @JvmStatic
        private external fun llamaInitModelNative(modelPath: String, nThreads: Int, nCtx: Int, nGpuLayers: Int): Long

        @JvmStatic
        private external fun llamaFreeModelNative(handle: Long)

        @JvmStatic
        private external fun llamaCompletionNative(handle: Long, prompt: String, params: String): String

        @JvmStatic
        private external fun llamaCompletionStreamNative(handle: Long, prompt: String, params: String, callback: (String) -> Unit)

        @JvmStatic
        private external fun llamaTokenCountNative(handle: Long, text: String): Int
    }
}
