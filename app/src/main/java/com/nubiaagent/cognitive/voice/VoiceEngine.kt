package com.nubiaagent.cognitive.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.nubiaagent.cognitive.persona.PersonaManager
import com.nubiaagent.cognitive.persona.PersonaProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * VoiceEngine: Motor de voz con Android TTS (nativo) + OpenAI/ElevenLabs (cloud).
 *
 * ARQUITECTURA DE VOZ (v2 — Android TTS como provider principal):
 *
 * ```
 * VoiceEngine
 *     │
 *     ├── 1. Android TTS (OFFLINE — provider principal)
 *     │   ├── TextToSpeech nativo de Android
 *     │   ├── Funciona inmediatamente sin dependencias externas
 *     │   ├── 100% offline y privado
 *     │   ├── Pitch y velocidad configurables por persona
 *     │   ├── Voz en español del sistema
 *     │   └── Compatible con todos los dispositivos Android 8+
 *     │
 *     ├── 2. OpenAI TTS (CLOUD — fallback premium)
 *     │   ├── Modelo tts-1, voces naturales
 *     │   ├── Solo si hay API key + internet
 *     │   ├── Reproducción via MediaPlayer (MP3)
 *     │   └── Voz mapeada por persona
 *     │
 *     ├── 3. ElevenLabs (CLOUD — segunda opción cloud)
 *     │   ├── Voces más naturales del mercado
 *     │   ├── Solo si hay API key + internet
 *     │   ├── Reproducción via MediaPlayer (MP3)
 *     │   └── Voice IDs por persona
 *     │
 *     └── 4. Piper ONNX (OFFLINE_HQ — futuro)
 *         ├── Placeholder para integración futura
 *         ├── Modelos ONNX para ARM64
 *         └── Latencia <500ms en Unisoc T8300
 * ```
 *
 * FLUJO DE SÍNTESIS:
 *
 * ```
 * speak(text) → synthesize(text, persona)
 *     │
 *     ├─ OFFLINE → Android TTS (o Piper cuando esté disponible)
 *     ├─ CLOUD   → OpenAI/ElevenLabs (MediaPlayer) → fallback Android TTS
 *     └─ AUTO    → Si hay internet + API key → Cloud → fallback Android TTS
 *                  Si no hay internet → Android TTS directamente
 * ```
 *
 * INTEGRACIÓN CON PERSONAS:
 * - Cada PersonaProfile define ttsPitch y ttsSpeed
 * - Al cambiar persona, se actualizan los parámetros de voz
 * - Android TTS permite ajuste fino de tono y velocidad por persona
 *
 * GESTIÓN DE RECURSOS:
 * - TextToSpeech se inicializa asíncronamente (OnInitListener)
 * - MediaPlayer se crea/libera por cada síntesis cloud
 * - AudioTrack se usa solo para PCM (Piper futuro)
 * - shutdown() libera todos los recursos correctamente
 */
class VoiceEngine(
    private val context: Context,
    private val personaManager: PersonaManager
) {

    companion object {
        private const val TAG = "Dayana/Voice"

        // Directorios
        private const val VOICES_DIR = "piper_voices"
        private const val PIPER_MODEL_DIR = "piper_models"

        // OpenAI TTS
        private const val OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech"
        private const val OPENAI_TTS_MODEL = "tts-1"

        // ElevenLabs
        private const val ELEVENLABS_TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech"

        // Audio config (para Piper futuro)
        private const val SAMPLE_RATE = 22050
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    // ==================== ESTADO DEL MOTOR ====================

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _voiceMode = MutableStateFlow(VoiceMode.AUTO)
    val voiceMode: StateFlow<VoiceMode> = _voiceMode.asStateFlow()

    private val _currentVoice = MutableStateFlow("Hestia (Cálida)")
    val currentVoice: StateFlow<String> = _currentVoice.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // ==================== ANDROID TTS ====================

    private var androidTts: TextToSpeech? = null
    private var ttsInitialized = false
    private var pendingUtterances = mutableListOf<Pair<String, PersonaProfile>>()

    // Parámetros de voz actuales (por persona)
    private var currentPitch = 1.0f
    private var currentSpeed = 1.0f

    // ==================== CLOUD TTS ====================

    private var mediaPlayer: MediaPlayer? = null

    // AudioTrack para Piper futuro
    private var audioTrack: AudioTrack? = null

    // SecureVault para claves API
    private val secureVault = com.nubiaagent.execution.safety.SecureVault(context)

    // Scope para operaciones asíncronas
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // CompletableDeferred para esperar inicialización de TTS
    private val ttsReady = CompletableDeferred<Boolean>()

    /**
     * Inicializa el motor de voz.
     *
     * 1. Inicializa Android TextToSpeech
     * 2. Configura directorios para modelos Piper
     * 3. Registra listener de cambio de persona
     * 4. Carga la voz de la persona activa
     */
    fun initialize() {
        try {
            // Crear directorios para modelos Piper futuro
            val voicesDir = File(context.filesDir, VOICES_DIR)
            if (!voicesDir.exists()) voicesDir.mkdirs()

            val modelsDir = File(context.filesDir, PIPER_MODEL_DIR)
            if (!modelsDir.exists()) modelsDir.mkdirs()

            // Inicializar Android TTS
            initAndroidTts()

            // Configurar listener de cambio de persona
            personaManager.setOnPersonaChangedListener { persona ->
                onPersonaChanged(persona)
            }

            // Cargar voz de la persona activa
            val activePersona = personaManager.activePersona.value
            _currentVoice.value = activePersona.voiceName
            currentPitch = activePersona.ttsPitch
            currentSpeed = activePersona.ttsSpeed

            Log.i(TAG, "Motor de voz inicializado — Persona: ${activePersona.displayName}, " +
                    "Voz: ${activePersona.voiceName}, Pitch: $currentPitch, Speed: $currentSpeed")

        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando motor de voz", e)
        }
    }

    /**
     * Inicializa el motor TextToSpeech de Android.
     *
     * La inicialización es asíncrona: Android TTS llama a onInit()
     * cuando está listo. Las utterances pendientes se procesan
     * automáticamente una vez inicializado.
     */
    private fun initAndroidTts() {
        androidTts = TextToSpeech(context.applicationContext, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    val tts = androidTts ?: return

                    // Configurar idioma español
                    val localeResult = tts.setLanguage(Locale("es", "ES"))
                    if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
                        localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Español no disponible, intentando Locale por defecto")
                        tts.setLanguage(Locale.getDefault())
                    }

                    // Configurar pitch y speed de la persona activa
                    tts.setPitch(currentPitch)
                    tts.setSpeechRate(currentSpeed)

                    // Configurar listener de progreso de utterance
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                            Log.d(TAG, "TTS hablando: utterance $utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            Log.d(TAG, "TTS completado: utterance $utteranceId")
                        }

                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            Log.w(TAG, "TTS error en utterance: $utteranceId")
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            _isSpeaking.value = false
                            Log.d(TAG, "TTS detenido: utterance $utteranceId (interrupted=$interrupted)")
                        }
                    })

                    ttsInitialized = true
                    _isReady.value = true
                    ttsReady.complete(true)

                    // Procesar utterances pendientes
                    processPendingUtterances()

                    Log.i(TAG, "Android TTS inicializado correctamente (idioma: es_ES)")
                } else {
                    Log.e(TAG, "Error inicializando Android TTS: status=$status")
                    ttsReady.complete(false)
                }
            }
        })
    }

    /**
     * Procesa utterances que se encolaron antes de que TTS estuviera listo.
     */
    private fun processPendingUtterances() {
        if (pendingUtterances.isEmpty()) return

        Log.d(TAG, "Procesando ${pendingUtterances.size} utterances pendientes")
        pendingUtterances.forEach { (text, persona) ->
            speakWithAndroidTts(text, persona)
        }
        pendingUtterances.clear()
    }

    // ==================== API PRINCIPAL ====================

    /**
     * Sintetiza texto a voz y lo reproduce.
     *
     * Este es el punto de entrada principal para que Dayana hable.
     * Selecciona el motor TTS según el modo activo y la disponibilidad.
     *
     * Flujo de selección:
     * - OFFLINE: Siempre Android TTS (Piper futuro como alternativa)
     * - CLOUD: Intenta OpenAI → ElevenLabs → fallback Android TTS
     * - AUTO: Cloud si hay internet + API key, sino Android TTS
     *
     * @param text Texto a sintetizar
     * @param personaProfile Perfil de persona (para voz específica)
     */
    suspend fun speak(text: String, personaProfile: PersonaProfile? = null) {
        if (text.isBlank()) return

        val persona = personaProfile ?: personaManager.activePersona.value

        withContext(Dispatchers.Main) {
            // Detener cualquier reproducción en curso
            stopSpeaking()

            _isSpeaking.value = true

            try {
                val mode = _voiceMode.value

                when (mode) {
                    VoiceMode.OFFLINE -> {
                        speakWithAndroidTts(text, persona)
                    }

                    VoiceMode.CLOUD -> {
                        // Intentar cloud, fallback a Android TTS
                        val cloudSuccess = speakWithCloud(text, persona)
                        if (!cloudSuccess) {
                            Log.i(TAG, "Cloud TTS no disponible, usando Android TTS como fallback")
                            speakWithAndroidTts(text, persona)
                        }
                    }

                    VoiceMode.AUTO -> {
                        if (isNetworkAvailable()) {
                            val cloudSuccess = speakWithCloud(text, persona)
                            if (!cloudSuccess) {
                                speakWithAndroidTts(text, persona)
                            }
                        } else {
                            speakWithAndroidTts(text, persona)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en speak()", e)
                // Último recurso: intentar Android TTS directamente
                try {
                    speakWithAndroidTts(text, persona)
                } catch (e2: Exception) {
                    Log.e(TAG, "Android TTS también falló", e2)
                    _isSpeaking.value = false
                }
            }
        }
    }

    /**
     * Detiene la reproducción de voz actual.
     * Libera recursos de Android TTS, MediaPlayer y AudioTrack.
     */
    fun stopSpeaking() {
        try {
            // Detener Android TTS
            androidTts?.stop()

            // Detener y liberar MediaPlayer (cloud TTS)
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null

            // Detener y liberar AudioTrack (Piper PCM futuro)
            audioTrack?.let {
                it.stop()
                it.release()
            }
            audioTrack = null

            _isSpeaking.value = false
            Log.d(TAG, "Reproducción de voz detenida")
        } catch (e: Exception) {
            Log.w(TAG, "Error deteniendo reproducción", e)
        }
    }

    /**
     * Cambia el modo de voz (Auto/Offline/Cloud).
     */
    fun setVoiceMode(mode: VoiceMode) {
        _voiceMode.value = mode
        Log.i(TAG, "Modo de voz cambiado a: $mode")
    }

    /**
     * Lista las voces disponibles según el modo y API keys.
     */
    fun listAvailableVoices(): List<VoiceInfo> {
        val voices = mutableListOf<VoiceInfo>()

        // Voces Android TTS (siempre disponibles)
        voices.addAll(getAndroidTtsVoices())

        // Voces Piper (futuro)
        voices.addAll(getPiperVoices())

        // Voces cloud (si hay API keys)
        if (secureVault.hasCredential("openai_api_key")) {
            voices.addAll(getOpenAIVoices())
        }
        if (secureVault.hasCredential("elevenlabs_api_key")) {
            voices.addAll(getElevenLabsVoices())
        }

        return voices
    }

    /**
     * Configura la API key para voces cloud.
     */
    fun setApiKey(provider: String, apiKey: String) {
        when (provider.lowercase()) {
            "openai" -> secureVault.storeCredential("openai_api_key", apiKey)
            "elevenlabs" -> secureVault.storeCredential("elevenlabs_api_key", apiKey)
            else -> Log.w(TAG, "Proveedor TTS desconocido: $provider")
        }
        Log.i(TAG, "API key configurada para: $provider")
    }

    // ==================== ANDROID TTS (PROVIDER PRINCIPAL) ====================

    /**
     * Sintetiza texto usando Android TextToSpeech nativo.
     *
     * Este es el provider principal que funciona:
     * - 100% offline
     * - Sin dependencias externas
     * - Compatible con todos los dispositivos Android 8+
     * - Pitch y velocidad configurables por persona
     * - Cola de utterances automática
     *
     * Si TTS aún no está inicializado, encola la utterance
     * para reproducir tan pronto como esté listo.
     *
     * @param text Texto a hablar
     * @param persona Persona cuyo perfil de voz se usará
     */
    private fun speakWithAndroidTts(text: String, persona: PersonaProfile) {
        val tts = androidTts

        if (tts == null || !ttsInitialized) {
            Log.w(TAG, "Android TTS no listo aún, encolando: '${text.take(30)}...'")
            pendingUtterances.add(text to persona)
            return
        }

        try {
            // Actualizar parámetros de voz según la persona
            val pitch = persona.ttsPitch
            val speed = persona.ttsSpeed

            tts.setPitch(pitch)
            tts.setSpeechRate(speed)

            currentPitch = pitch
            currentSpeed = speed

            // Intentar seleccionar voz específica en español
            selectBestSpanishVoice(tts, persona)

            // Generar utterance ID único para tracking
            val utteranceId = "dayana_${System.currentTimeMillis()}"

            // Hablar
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            if (result == TextToSpeech.SUCCESS) {
                Log.i(TAG, "Android TTS hablando: '${text.take(40)}...' " +
                        "(persona: ${persona.displayName}, pitch: $pitch, speed: $speed)")
            } else {
                Log.e(TAG, "Android TTS error al hablar: result=$result")
                _isSpeaking.value = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en speakWithAndroidTts", e)
            _isSpeaking.value = false
        }
    }

    /**
     * Selecciona la mejor voz en español disponible en el motor TTS.
     *
     * Estrategia:
     * 1. Buscar voz femenina en español (si está disponible)
     * 2. Si no hay femenina, usar cualquier voz en español
     * 3. Si no hay español, usar la voz por defecto
     *
     * Algunos dispositivos tienen voces de alta calidad descargables
     * desde Configuración → Idioma → Text-to-speech.
     */
    private fun selectBestSpanishVoice(tts: TextToSpeech, persona: PersonaProfile) {
        val voices = tts.voices ?: return

        // Filtrar voces en español
        val spanishVoices = voices.filter {
            it.locale.language == "es" && !it.isNetworkConnectionRequired
        }

        if (spanishVoices.isEmpty()) {
            Log.d(TAG, "No hay voces en español offline disponibles")
            return
        }

        // Intentar seleccionar voz femenina si es posible
        // Los nombres de voz suelen contener indicadores de género
        val femaleVoice = spanishVoices.find {
            it.name.contains("female", ignoreCase = true) ||
            it.name.contains("mujer", ignoreCase = true) ||
            it.name.contains("es-es-x-esf", ignoreCase = true)
        }

        val selectedVoice = femaleVoice ?: spanishVoices.first()

        try {
            val result = tts.setVoice(selectedVoice)
            if (result == TextToSpeech.SUCCESS) {
                Log.d(TAG, "Voz seleccionada: ${selectedVoice.name} " +
                        "(locale: ${selectedVoice.locale})")
            } else {
                Log.w(TAG, "No se pudo seleccionar voz: ${selectedVoice.name}")
            }
        } catch (e: Exception) {
            // Algunas voces pueden no ser seleccionables
            Log.d(TAG, "Voz no seleccionable: ${selectedVoice.name}", e)
        }
    }

    // ==================== CLOUD TTS ====================

    /**
     * Intenta síntesis cloud (OpenAI → ElevenLabs).
     * Retorna true si la síntesis fue exitosa y se está reproduciendo.
     */
    private suspend fun speakWithCloud(text: String, persona: PersonaProfile): Boolean {
        return withContext(Dispatchers.IO) {
            // Intentar OpenAI primero
            val openaiKey = secureVault.getCredential("openai_api_key")
            if (openaiKey != null) {
                val audio = synthesizeOpenAI(text, openaiKey, persona)
                if (audio != null) {
                    withContext(Dispatchers.Main) {
                        playMp3Audio(audio)
                    }
                    return@withContext true
                }
            }

            // Fallback a ElevenLabs
            val elevenlabsKey = secureVault.getCredential("elevenlabs_api_key")
            if (elevenlabsKey != null) {
                val audio = synthesizeElevenLabs(text, elevenlabsKey, persona)
                if (audio != null) {
                    withContext(Dispatchers.Main) {
                        playMp3Audio(audio)
                    }
                    return@withContext true
                }
            }

            false
        }
    }

    /**
     * Síntesis con OpenAI TTS API.
     *
     * Modelos: tts-1 (rápido), tts-1-hd (alta calidad)
     * Formato: MP3 → se reproduce via MediaPlayer
     *
     * Mapeo de voz por persona:
     * - Hestia → nova (cálida, femenina)
     * - Metis → alloy (neutral, eficiente)
     * - Argus → shimmer (firme)
     * - Athena → fable (erudita)
     * - Selene → echo (suave, calmada)
     * - Iris → shimmer (expresiva)
     */
    private fun synthesizeOpenAI(
        text: String,
        apiKey: String,
        persona: PersonaProfile
    ): ByteArray? {
        return try {
            val voiceName = persona.openaiVoice

            val url = URL(OPENAI_TTS_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val requestBody = JSONObject().apply {
                put("model", OPENAI_TTS_MODEL)
                put("input", text)
                put("voice", voiceName)
                put("response_format", "mp3")
                put("speed", persona.ttsSpeed.toDouble())
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }

            if (connection.responseCode == 200) {
                val audioBytes = connection.inputStream.readBytes()
                Log.d(TAG, "OpenAI TTS: ${audioBytes.size} bytes MP3 (voz: $voiceName)")
                audioBytes
            } else {
                val errorBody = connection.errorStream?.readBytes()?.decodeToString()
                Log.w(TAG, "OpenAI TTS error: ${connection.responseCode} — $errorBody")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en síntesis OpenAI", e)
            null
        }
    }

    /**
     * Síntesis con ElevenLabs API.
     *
     * ElevenLabs ofrece las voces más naturales del mercado.
     * Requiere API key almacenada en SecureVault.
     * Formato: MP3 → se reproduce via MediaPlayer
     */
    private fun synthesizeElevenLabs(
        text: String,
        apiKey: String,
        persona: PersonaProfile
    ): ByteArray? {
        return try {
            val voiceId = persona.elevenlabsVoiceId

            val url = URL("$ELEVENLABS_TTS_URL/$voiceId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("xi-api-key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "audio/mpeg")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            val requestBody = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                    put("style", 0.3)
                    put("use_speaker_boost", true)
                })
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray())
            }

            if (connection.responseCode == 200) {
                val audioBytes = connection.inputStream.readBytes()
                Log.d(TAG, "ElevenLabs: ${audioBytes.size} bytes MP3 (voz: $voiceId)")
                audioBytes
            } else {
                val errorBody = connection.errorStream?.readBytes()?.decodeToString()
                Log.w(TAG, "ElevenLabs error: ${connection.responseCode} — $errorBody")
                null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en síntesis ElevenLabs", e)
            null
        }
    }

    // ==================== PIPER TTS (FUTURO) ====================

    /**
     * Síntesis offline con Piper TTS (placeholder).
     *
     * Piper usa modelos ONNX ligeros que corren directamente
     * en el Unisoc T8300 sin necesidad de internet.
     *
     * La integración real requiere:
     * 1. Compilar libpiper para Android ARM64
     * 2. Descargar modelos .onnx para español
     * 3. Implementar inferencia via ONNX Runtime
     *
     * NOTA: Cuando Piper esté disponible, se usará como
     * alternativa de alta calidad al Android TTS nativo.
     * Android TTS seguirá siendo el fallback universal.
     */
    private fun synthesizePiper(text: String, persona: PersonaProfile): ByteArray? {
        try {
            val modelFile = File(context.filesDir, "$PIPER_MODEL_DIR/${persona.voiceId}.onnx")

            if (modelFile.exists()) {
                // TODO: Integración real con Piper ONNX
                // val env = OrtEnvironment.getEnvironment()
                // val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                // val inputIds = tokenize(text) // Piper phonemize + tokenize
                // val result = session.run(mapOf("input_ids" to inputIds))
                // val audio = result[0].value as Array<FloatArray>
                // return convertToPcm16(audio)
                Log.d(TAG, "Modelo Piper encontrado: ${modelFile.name} — pendiente integración ONNX")
            } else {
                Log.d(TAG, "Modelo Piper no encontrado: ${modelFile.name}")
            }

            // Fallback a Android TTS (Piper no disponible aún)
            Log.i(TAG, "Piper TTS no disponible, usando Android TTS como fallback")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error en síntesis Piper", e)
            return null
        }
    }

    // ==================== REPRODUCCIÓN DE AUDIO ====================

    /**
     * Reproduce audio MP3 via MediaPlayer.
     *
     * Se usa para la salida de OpenAI y ElevenLabs que
     * devuelven audio en formato MP3.
     *
     * El audio se escribe a un archivo temporal y se
     * reproduce con MediaPlayer, que soporta MP3 nativamente.
     */
    private fun playMp3Audio(mp3Data: ByteArray) {
        try {
            // Liberar MediaPlayer anterior si existe
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }

            // Escribir MP3 a archivo temporal
            val tempFile = File(context.cacheDir, "tts_cloud_${System.currentTimeMillis()}.mp3")
            FileOutputStream(tempFile).use { fos ->
                fos.write(mp3Data)
            }

            // Crear y configurar MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    _isSpeaking.value = false
                    tempFile.delete()
                    Log.d(TAG, "MediaPlayer: reproducción completada")
                }
                setOnErrorListener { _, what, extra ->
                    _isSpeaking.value = false
                    tempFile.delete()
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    true
                }
                prepare()
                start()
            }

            Log.d(TAG, "Reproduciendo MP3 cloud: ${mp3Data.size} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo MP3", e)
            _isSpeaking.value = false
        }
    }

    /**
     * Reproduce audio PCM via AudioTrack.
     *
     * Se usará cuando Piper TTS esté integrado y genere
     * audio PCM 22050Hz mono 16-bit directamente.
     */
    private fun playPcmAudio(audioData: ByteArray) {
        try {
            audioTrack?.let {
                it.stop()
                it.release()
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, audioData.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(audioData, 0, audioData.size)
            audioTrack?.play()

            Log.d(TAG, "Audio PCM reproduciéndose: ${audioData.size} bytes")

        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo audio PCM", e)
        }
    }

    // ==================== CATÁLOGO DE VOCES ====================

    private fun getAndroidTtsVoices(): List<VoiceInfo> {
        val voices = mutableListOf<VoiceInfo>()

        // Listar voces Android TTS disponibles en español
        androidTts?.voices?.filter {
            it.locale.language == "es" && !it.isNetworkConnectionRequired
        }?.forEach { voice ->
            voices.add(VoiceInfo(
                id = "android-${voice.name}",
                displayName = "Android: ${voice.locale.displayName}",
                provider = "AndroidTTS",
                type = "offline",
                isLocal = true
            ))
        }

        // Si no se encontraron voces específicas, añadir la default
        if (voices.isEmpty()) {
            voices.add(VoiceInfo(
                id = "android-default-es",
                displayName = "Android TTS (Español por defecto)",
                provider = "AndroidTTS",
                type = "offline",
                isLocal = true
            ))
        }

        return voices
    }

    private fun getPiperVoices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("es_ES-hestia-medium", "Piper: Hestia (Cálida)", "Piper", "offline", true),
            VoiceInfo("es_ES-metis-medium", "Piper: Metis (Estratégica)", "Piper", "offline", true),
            VoiceInfo("es_ES-argus-medium", "Piper: Argus (Vigilante)", "Piper", "offline", true),
            VoiceInfo("es_ES-athena-medium", "Piper: Athena (Sabia)", "Piper", "offline", true),
            VoiceInfo("es_ES-selene-medium", "Piper: Selene (Nocturna)", "Piper", "offline", true),
            VoiceInfo("es_ES-iris-medium", "Piper: Iris (Social)", "Piper", "offline", true),
        )
    }

    private fun getOpenAIVoices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("openai-alloy", "OpenAI: Alloy (Neutral)", "OpenAI", "cloud", false),
            VoiceInfo("openai-echo", "OpenAI: Echo (Suave)", "OpenAI", "cloud", false),
            VoiceInfo("openai-fable", "OpenAI: Fable (Erudita)", "OpenAI", "cloud", false),
            VoiceInfo("openai-nova", "OpenAI: Nova (Femenina)", "OpenAI", "cloud", false),
            VoiceInfo("openai-shimmer", "OpenAI: Shimmer (Firme)", "OpenAI", "cloud", false),
        )
    }

    private fun getElevenLabsVoices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("eleven-rachel", "ElevenLabs: Rachel", "ElevenLabs", "cloud", false),
            VoiceInfo("eleven-bella", "ElevenLabs: Bella", "ElevenLabs", "cloud", false),
            VoiceInfo("eleven-elli", "ElevenLabs: Elli", "ElevenLabs", "cloud", false),
            VoiceInfo("eleven-domi", "ElevenLabs: Domi", "ElevenLabs", "cloud", false),
        )
    }

    // ==================== UTILIDADES ====================

    private fun onPersonaChanged(persona: PersonaProfile) {
        _currentVoice.value = persona.voiceName
        currentPitch = persona.ttsPitch
        currentSpeed = persona.ttsSpeed

        // Actualizar parámetros de Android TTS si está inicializado
        androidTts?.let { tts ->
            if (ttsInitialized) {
                tts.setPitch(persona.ttsPitch)
                tts.setSpeechRate(persona.ttsSpeed)
                Log.d(TAG, "Android TTS params actualizados — pitch: ${persona.ttsPitch}, speed: ${persona.ttsSpeed}")
            }
        }

        Log.i(TAG, "Voz cambiada a: ${persona.voiceName} (persona: ${persona.displayName}, " +
                "pitch: ${persona.ttsPitch}, speed: ${persona.ttsSpeed})")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Libera todos los recursos del motor de voz.
     *
     * DEBE llamarse cuando el componente que posee VoiceEngine se destruya
     * (ej: Application.onTerminate(), Service.onDestroy()).
     *
     * Libera:
     * - TextToSpeech (shutdown)
     * - MediaPlayer (release)
     * - AudioTrack (release)
     * - CoroutineScope (cancel)
     */
    fun destroy() {
        scope.cancel()

        try {
            androidTts?.stop()
            androidTts?.shutdown()
            androidTts = null
            ttsInitialized = false
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando Android TTS", e)
        }

        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando MediaPlayer", e)
        }

        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando AudioTrack", e)
        }

        pendingUtterances.clear()

        Log.i(TAG, "VoiceEngine destruido — recursos liberados")
    }
}

/**
 * Modos de operación del motor de voz.
 */
enum class VoiceMode {
    OFFLINE,    // Android TTS / Piper (100% privado)
    CLOUD,      // OpenAI/ElevenLabs (máxima naturalidad)
    AUTO        // Cloud si hay internet, Offline si no
}

/**
 * Información de una voz disponible.
 */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    val provider: String,    // "AndroidTTS", "Piper", "OpenAI", "ElevenLabs"
    val type: String,        // "offline", "cloud"
    val isLocal: Boolean
)
