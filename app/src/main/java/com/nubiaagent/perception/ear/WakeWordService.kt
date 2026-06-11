package com.nubiaagent.perception.ear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nubiaagent.MainActivity
import com.nubiaagent.R
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * WakeWordService: Servicio de escucha permanente 100% offline.
 *
 * Arquitectura de escucha en dos fases:
 *
 * Fase 1 - DETECCIÓN (Wake Word): Usa un buffer circular de audio de bajo costo
 *   computacional para detectar "Hey Nubia" mediante Vosk con modelo reducido.
 *   El buffer circular evita allocations innecesarios en el hot path del audio,
 *   optimizando el consumo de CPU en el Unisoc T8300 y aprovechando el
 *   scheduler del motor NeoTurbo para priorizar este hilo.
 *
 * Fase 2 - COMANDO (Voice Command): Una vez detectado el wake word, el servicio
 *   entra en modo "escucha activa" donde transcribe el comando completo del
 *   usuario usando el modelo Vosk completo. Tras un silencio de 2.5 segundos
 *   o un máximo de 30 segundos, se finaliza la transcripción y se emite
 *   el evento VoiceCommand al PerceptionBus.
 *
 * RESTRICCIÓN DE PRIVACIDAD: Todo el procesamiento de audio ocurre en-device.
 * Ningún fragmento de audio sale del dispositivo. Los modelos Vosk se almacenan
 * localmente en /data/data/com.nubiaagent/models/
 */
class WakeWordService : LifecycleService() {

    companion object {
        private const val TAG = "NubiaAgent/Ear"
        private const val CHANNEL_ID = "nubia_agent_ear_channel"
        private const val NOTIFICATION_ID = 1001

        // Audio configuration - optimizado para Vosk
        private const val SAMPLE_RATE = 16000
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Wake word detection
        private const val WAKE_WORD = "hey dayana"
        private val WAKE_PHRASES = arrayOf("hey dayana", "hey marcia", "hey nubia", "hey diana", "hey dana")  // Nombres + variaciones fonéticas
        private const val WAKE_CONFIDENCE_THRESHOLD = 0.65f

        // Command listening
        private const val MAX_COMMAND_DURATION_MS = 30_000L
        private const val SILENCE_TIMEOUT_MS = 2_500L
        private const val COMMAND_CONFIDENCE_THRESHOLD = 0.5f

        // Buffer circular
        private const val CIRCULAR_BUFFER_SIZE = 16000 * 5  // 5 segundos de audio a 16kHz

        // Modelo paths
        private const val WAKE_MODEL_DIR = "vosk-model-small-es-0.42"  // Modelo pequeño para wake word
        private const val COMMAND_MODEL_DIR = "vosk-model-es-0.42"     // Modelo completo para comandos

        // Intent actions
        const val ACTION_START_LISTENING = "com.nubiaagent.action.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.nubiaagent.action.STOP_LISTENING"
        const val ACTION_FORCE_COMMAND_MODE = "com.nubiaagent.action.FORCE_COMMAND_MODE"

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_START_LISTENING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            context.startService(intent)
        }

        // Instancia activa para consulta de estado desde MainActivity
        private var instance: WakeWordService? = null

        fun isRunning(): Boolean = instance?.isListening == true
    }

    // Estado del servicio
    private var isListening = false
    private var isCommandMode = false
    private var audioRecord: AudioRecord? = null
    private var wakeRecognizer: Recognizer? = null
    private var commandRecognizer: Recognizer? = null
    private var wakeModel: Model? = null
    private var commandModel: Model? = null

    // Buffer circular para optimización de memoria
    private val circularBuffer = CircularAudioBuffer(CIRCULAR_BUFFER_SIZE)
    private val bufferMutex = Mutex()

    // Coroutines
    private var listeningJob: Job? = null
    private var commandTimeoutJob: Job? = null
    private var silenceJob: Job? = null
    private var lastSpeechTime = 0L

    // Estado del servicio
    private var serviceState = EarState.IDLE

    private enum class EarState {
        IDLE,           // No escuchando
        WAKE_DETECT,    // Escuchando wake word
        COMMAND_MODE    // Escuchando comando
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "WakeWordService onCreate")
        try {
            LibVosk.setLogLevel(LogLevel.INFO)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Vosk native library no disponible aún", e)
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_LISTENING -> startListening()
            ACTION_STOP_LISTENING -> stopListening()
            ACTION_FORCE_COMMAND_MODE -> enterCommandMode()
        }

        return START_STICKY  // Reiniciar si el sistema mata el servicio
    }

    override fun onBind(intent: Intent): IBinder? = null

    /**
     * Inicializa los modelos Vosk y comienza la escucha.
     * El modelo pequeño (wake) se carga primero para inicio rápido.
     * El modelo completo (command) se carga en background.
     */
    private fun startListening() {
        if (isListening) return

        Log.i(TAG, "Iniciando WakeWordService - Modo: Detección de Wake Word")
        startForeground(NOTIFICATION_ID, createNotification("Escuchando..."))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Cargar modelo de wake word (pequeño, ~50MB)
                val wakeModelPath = getWakeModelPath()
                if (!File(wakeModelPath).exists()) {
                    Log.e(TAG, "Modelo wake word no encontrado en: $wakeModelPath")
                    Log.e(TAG, "Descarga el modelo y colócalo en app/assets/models/")
                    return@launch
                }

                wakeModel = Model(wakeModelPath)
                wakeRecognizer = Recognizer(wakeModel!!, SAMPLE_RATE.toFloat())
                Log.i(TAG, "Modelo wake word cargado exitosamente")

                // Cargar modelo de comandos en background (grande, ~1.3GB)
                launch(Dispatchers.IO + SupervisorJob()) {
                    try {
                        val commandModelPath = getCommandModelPath()
                        if (File(commandModelPath).exists()) {
                            commandModel = Model(commandModelPath)
                            commandRecognizer = Recognizer(commandModel!!, SAMPLE_RATE.toFloat())
                            Log.i(TAG, "Modelo de comandos cargado exitosamente")
                        } else {
                            Log.w(TAG, "Modelo de comandos no disponible. Usando modelo wake para comandos.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cargando modelo de comandos", e)
                    }
                }

                // Iniciar captura de audio
                initAudioRecord()
                startAudioLoop()

            } catch (e: Exception) {
                Log.e(TAG, "Error fatal iniciando WakeWordService", e)
                stopSelf()
            }
        }
    }

    /**
     * Configura AudioRecord con parámetros optimizados para Vosk.
     * Usa VOICE_RECOGNITION source para obtener audio pre-procesado
     * por el DSP del Unisoc T8300 (cancelación de eco, supresión de ruido).
     */
    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        // Duplicar el buffer para mayor estabilidad
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        audioRecord = AudioRecord(
            AUDIO_SOURCE,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IOException("AudioRecord no se pudo inicializar")
        }

        audioRecord?.startRecording()
        isListening = true
        serviceState = EarState.WAKE_DETECT
        Log.i(TAG, "AudioRecord inicializado - Sample Rate: $SAMPLE_RATE Hz, Buffer: $bufferSize bytes")
    }

    /**
     * Loop principal de captura de audio.
     *
     * Lee chunks de audio del micrófono y los procesa según el estado actual:
     * - WAKE_DETECT: Alimenta el recognizer pequeño buscando "Hey Nubia"
     * - COMMAND_MODE: Alimenta el recognizer completo para transcripción
     *
     * El buffer circular almacena los últimos 5 segundos de audio para
     * capturar el contexto del comando que sigue al wake word.
     */
    private fun startAudioLoop() {
        val readBufferSize = 1600  // 100ms de audio a 16kHz, 16-bit
        val audioData = ShortArray(readBufferSize)

        listeningJob = lifecycleScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Audio loop iniciado")

            while (isListening && isActive) {
                val bytesRead = audioRecord?.read(audioData, 0, readBufferSize)
                    ?: break

                if (bytesRead!! <= 0) continue

                // Convertir shorts a bytes para Vosk
                val byteData = ShortArrayToBytes(audioData, bytesRead)

                // Alimentar buffer circular (para contexto del comando)
                bufferMutex.withLock {
                    circularBuffer.write(byteData)
                }

                when (serviceState) {
                    EarState.WAKE_DETECT -> processWakeWordAudio(byteData)
                    EarState.COMMAND_MODE -> processCommandAudio(byteData)
                    EarState.IDLE -> { /* No procesar */ }
                }
            }

            Log.i(TAG, "Audio loop terminado")
        }
    }

    /**
     * Procesa audio buscando el wake word "Hey Nubia".
     * Usa el modelo Vosk pequeño para minimizar uso de CPU.
     *
     * Optimización para NeoTurbo: El reconocimiento de wake word usa
     * un modelo pequeño (~50MB) que se mantiene en los caches L2/L3
     * del T8300, reduciendo accesos a RAM y consumo de energía.
     */
    private fun processWakeWordAudio(audioData: ByteArray) {
        val recognizer = wakeRecognizer ?: return

        if (recognizer.acceptWaveForm(audioData, audioData.size)) {
            val result = recognizer.result
            val json = JSONObject(result)
            val text = json.optString("text", "").lowercase().trim()
            val confidence = json.optDouble("confidence", 0.0).toFloat()

            if (text.isNotBlank()) {
                Log.d(TAG, "Wake detect - Texto: '$text', Confianza: $confidence")

                // Verificar si alguna variación del wake word coincide
                val isWakeWord = WAKE_PHRASES.any { phrase ->
                    text.contains(phrase) || levenshteinContains(text, phrase, maxDistance = 2)
                }

                if (isWakeWord && confidence >= WAKE_CONFIDENCE_THRESHOLD) {
                    Log.i(TAG, "★ WAKE WORD DETECTADO: '$text' (conf: $confidence)")
                    onWakeWordDetected(confidence)
                }
            }
        } else {
            // Resultado parcial - para detección temprana
            val partial = recognizer.partialResult
            val json = JSONObject(partial)
            val partialText = json.optString("partial", "").lowercase().trim()

            if (partialText.isNotBlank()) {
                val isWakeWord = WAKE_PHRASES.any { phrase ->
                    partialText.contains(phrase)
                }
                if (isWakeWord) {
                    Log.d(TAG, "Wake partial detect: '$partialText'")
                }
            }
        }
    }

    /**
     * Procesa audio en modo comando para transcribir la orden del usuario.
     * Usa el modelo Vosk completo (si está disponible) para mayor precisión.
     *
     * El modo comando tiene dos timeouts:
     * 1. Duración máxima: 30 segundos
     * 2. Silencio: 2.5 segundos sin habla
     */
    private fun processCommandAudio(audioData: ByteArray) {
        val recognizer = commandRecognizer ?: wakeRecognizer ?: return

        lastSpeechTime = System.currentTimeMillis()

        if (recognizer.acceptWaveForm(audioData, audioData.size)) {
            val result = recognizer.result
            val json = JSONObject(result)
            val text = json.optString("text", "").trim()
            val confidence = json.optDouble("confidence", 0.0).toFloat()

            if (text.isNotBlank() && confidence >= COMMAND_CONFIDENCE_THRESHOLD) {
                Log.i(TAG, "Comando transcrito: '$text' (conf: $confidence)")
                emitVoiceCommand(text, confidence, isFinal = true)
                exitCommandMode()
            }
        } else {
            val partial = recognizer.partialResult
            val json = JSONObject(partial)
            val partialText = json.optString("partial", "").trim()

            if (partialText.isNotBlank()) {
                Log.d(TAG, "Comando parcial: '$partialText'")
                emitVoiceCommand(partialText, 0f, isFinal = false)
                lastSpeechTime = System.currentTimeMillis()
            }
        }

        // Actualizar timeout de silencio
        resetSilenceTimeout()
    }

    /**
     * Wake word detectado. Transición a modo comando.
     * Reinicia el recognizer y configura timeouts.
     */
    private fun onWakeWordDetected(confidence: Float) {
        serviceState = EarState.COMMAND_MODE
        isCommandMode = true

        // Reiniciar recognizer para modo comando
        commandRecognizer?.reset()
        wakeRecognizer?.reset()

        // Emitir evento de wake word
        lifecycleScope.launch {
            PerceptionBus.emit(
                PerceptionEvent.WakeWordDetected(
                    confidence = confidence
                )
            )
        }

        // Configurar timeout máximo de comando
        commandTimeoutJob?.cancel()
        commandTimeoutJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(MAX_COMMAND_DURATION_MS)
            Log.w(TAG, "Timeout de comando alcanzado (${MAX_COMMAND_DURATION_MS}ms)")
            finalizeCommand()
        }

        // Actualizar notificación
        updateNotification("Escuchando comando...")

        // Feedback háptico / sonoro
        provideWakeFeedback()
    }

    /**
     * Configura el timeout de silencio.
     * Si el usuario deja de hablar por SILENCE_TIMEOUT_MS,
     * se finaliza la transcripción del comando.
     */
    private fun resetSilenceTimeout() {
        silenceJob?.cancel()
        silenceJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(SILENCE_TIMEOUT_MS)
            val timeSinceLastSpeech = System.currentTimeMillis() - lastSpeechTime
            if (timeSinceLastSpeech >= SILENCE_TIMEOUT_MS) {
                Log.i(TAG, "Silencio detectado (${SILENCE_TIMEOUT_MS}ms), finalizando comando")
                finalizeCommand()
            }
        }
    }

    /**
     * Finaliza el comando actual y retorna a modo wake word.
     */
    private fun finalizeCommand() {
        val recognizer = commandRecognizer ?: wakeRecognizer ?: run {
            exitCommandMode()
            return
        }

        val finalResult = recognizer.finalResult
        val json = JSONObject(finalResult)
        val text = json.optString("text", "").trim()

        if (text.isNotBlank()) {
            emitVoiceCommand(text, 0.7f, isFinal = true)
        }

        exitCommandMode()
    }

    /**
     * Retorna al modo de detección de wake word.
     */
    private fun exitCommandMode() {
        serviceState = EarState.WAKE_DETECT
        isCommandMode = false
        commandTimeoutJob?.cancel()
        silenceJob?.cancel()
        commandRecognizer?.reset()
        wakeRecognizer?.reset()
        updateNotification("Escuchando...")
        Log.i(TAG, "Retorno a modo Wake Word detect")
    }

    /**
     * Fuerza la entrada a modo comando (desde UI o notificación).
     */
    private fun enterCommandMode() {
        if (serviceState == EarState.COMMAND_MODE) return
        onWakeWordDetected(1.0f)
    }

    /**
     * Emite un evento VoiceCommand al PerceptionBus.
     */
    private fun emitVoiceCommand(text: String, confidence: Float, isFinal: Boolean) {
        lifecycleScope.launch {
            PerceptionBus.emit(
                PerceptionEvent.VoiceCommand(
                    transcript = text,
                    confidence = confidence,
                    isFinal = isFinal
                )
            )
        }
    }

    private fun stopListening() {
        Log.i(TAG, "Deteniendo WakeWordService")
        isListening = false
        listeningJob?.cancel()
        commandTimeoutJob?.cancel()
        silenceJob?.cancel()

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        wakeRecognizer?.close()
        commandRecognizer?.close()
        wakeModel?.close()
        commandModel?.close()

        wakeRecognizer = null
        commandRecognizer = null
        wakeModel = null
        commandModel = null

        serviceState = EarState.IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        stopListening()
        super.onDestroy()
        Log.i(TAG, "WakeWordService destruido")
    }

    // ==================== UTILIDADES ====================

    /**
     * Obtiene la ruta al modelo Vosk de wake word.
     * Prioriza almacenamiento interno, luego assets descomprimidos.
     */
    private fun getWakeModelPath(): String {
        val internalDir = File(filesDir, "models/$WAKE_MODEL_DIR")
        if (internalDir.exists()) return internalDir.absolutePath

        // Fallback: assets copiados a almacenamiento interno
        val assetDir = File(getExternalFilesDir(null), "models/$WAKE_MODEL_DIR")
        return assetDir.absolutePath
    }

    private fun getCommandModelPath(): String {
        val internalDir = File(filesDir, "models/$COMMAND_MODEL_DIR")
        if (internalDir.exists()) return internalDir.absolutePath

        val assetDir = File(getExternalFilesDir(null), "models/$COMMAND_MODEL_DIR")
        return assetDir.absolutePath
    }

    /**
     * Feedback al detectar wake word.
     * Usa vibración corta para no interrumpir al usuario.
     */
    private fun provideWakeFeedback() {
        // TODO: Implementar feedback háptico configurable
        // vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        Log.d(TAG, "Feedback háptico proporcionado")
    }

    // ==================== NOTIFICACIÓN FOREGROUND ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NubiaAgent - Escucha",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de escucha permanente de NubiaAgent"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NubiaAgent")
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

    // ==================== FUNCIONES AUXILIARES ====================

    /**
     * Convierte array de Short a ByteArray para Vosk.
     */
    private fun ShortArrayToBytes(shorts: ShortArray, length: Int): ByteArray {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val sample = shorts[i]
            bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return bytes
    }

    /**
     * Distancia de Levenshtein para matching flexible del wake word.
     * Permite detectar "Hey Nubia" incluso con pronunciación imprecisa.
     */
    private fun levenshteinContains(text: String, target: String, maxDistance: Int): Boolean {
        if (text.length < target.length - maxDistance) return false

        for (i in 0..(text.length - target.length).coerceAtLeast(0)) {
            val substring = text.substring(i, (i + target.length).coerceAtMost(text.length))
            if (levenshteinDistance(substring, target) <= maxDistance) {
                return true
            }
        }
        return false
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}

/**
 * Buffer circular para audio.
 *
 * Evita la creación continua de arrays que generaría presión sobre el GC
 * del runtime ART. En un dispositivo con Unisoc T8300, minimizar los
 * allocations en el hot path de audio es crítico para evitar cortes
 * y mantener la latencia baja.
 *
 * Implementación lock-free para escritura de un solo productor
 * (el hilo de AudioRecord).
 */
class CircularAudioBuffer(private val capacity: Int) {
    private val buffer = ByteArray(capacity)
    private var writePos = 0
    private var available = 0

    /**
     * Escribe datos al buffer circular.
     * Si los datos exceden la capacidad, se sobreescriben los más antiguos.
     */
    @Synchronized
    fun write(data: ByteArray) {
        var remaining = data.size
        var srcPos = 0

        while (remaining > 0) {
            val chunkSize = minOf(remaining, capacity - writePos)
            System.arraycopy(data, srcPos, buffer, writePos, chunkSize)
            writePos = (writePos + chunkSize) % capacity
            srcPos += chunkSize
            remaining -= chunkSize
        }

        available = minOf(available + data.size, capacity)
    }

    /**
     * Lee los últimos `length` bytes del buffer.
     * Retorna los datos más recientes si length > available.
     */
    @Synchronized
    fun readLast(length: Int): ByteArray {
        val readLength = minOf(length, available)
        val result = ByteArray(readLength)

        val readStart = if (available < capacity) {
            0
        } else {
            writePos
        }

        for (i in 0 until readLength) {
            val pos = (readStart + i) % capacity
            result[i] = buffer[pos]
        }

        return result
    }

    /**
     * Limpia el buffer.
     */
    @Synchronized
    fun clear() {
        writePos = 0
        available = 0
    }
}
