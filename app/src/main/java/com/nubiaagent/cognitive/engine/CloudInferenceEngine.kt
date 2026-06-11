package com.nubiaagent.cognitive.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * CloudInferenceEngine: Motor de inferencia via API cloud con FALLBACK AUTOMÁTICO.
 *
 * SISTEMA DE FALLBACK MULTI-PROVEEDOR:
 * Gemini (GRATIS) → Groq (GRATIS) → OpenAI (de pago)
 *
 * Si un proveedor falla (error, rate limit, sin conexión), automáticamente
 * intenta el siguiente proveedor. Esto garantiza que el asistente SIEMPRE
 * funcione mientras al menos una API esté disponible.
 *
 * PROVEEDORES PRECONFIGURADOS:
 * 1. Google Gemini — GRATIS, ultra rápido, 1M contexto, 15 RPM
 * 2. Groq — GRATIS, velocidad extrema (800 tok/s), 30 RPM
 * 3. OpenAI — De pago (GPT-4o-mini), mejor calidad, sin rate limits
 *
 * SEGURIDAD DE API KEY:
 * Las API keys se almacenan en EncryptedSharedPreferences con AES-256-GCM
 * usando Android Keystore. Nunca se almacenan en texto plano ni se
 * envían a terceros (solo al proveedor de API seleccionado).
 */
class CloudInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "Dayana/CloudEngine"

        // Proveedores
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_GROQ = "groq"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_CUSTOM = "custom"

        // Orden de fallback: se intentan en este orden
        val FALLBACK_ORDER = listOf(PROVIDER_GEMINI, PROVIDER_GROQ, PROVIDER_OPENAI)

        // Modelos por defecto
        val DEFAULT_MODELS = mapOf(
            PROVIDER_GEMINI to "gemini-2.0-flash",
            PROVIDER_GROQ to "llama-3.3-70b-versatile",
            PROVIDER_OPENAI to "gpt-4o-mini",
            PROVIDER_ANTHROPIC to "claude-3-haiku-20240307",
            PROVIDER_OPENROUTER to "openai/gpt-4o-mini",
            PROVIDER_CUSTOM to "gpt-4o-mini"
        )

        // URLs base de API
        val API_BASE_URLS = mapOf(
            PROVIDER_GEMINI to "https://generativelanguage.googleapis.com/v1beta",
            PROVIDER_GROQ to "https://api.groq.com/openai/v1",
            PROVIDER_OPENAI to "https://api.openai.com/v1",
            PROVIDER_ANTHROPIC to "https://api.anthropic.com/v1",
            PROVIDER_OPENROUTER to "https://openrouter.ai/api/v1",
            PROVIDER_CUSTOM to ""  // El usuario configura la URL
        )

        // API Keys preconfiguradas: se leen desde BuildConfig (inyectadas por Gradle)
        // Las keys se almacenan en secrets.properties (local) o GitHub Secrets (CI)
        // NUNCA se hardcodean directamente en el codigo fuente
        private val BUILT_IN_GEMINI_KEY: String get() = com.nubiaagent.BuildConfig.GEMINI_API_KEY
        private val BUILT_IN_GROQ_KEY: String get() = com.nubiaagent.BuildConfig.GROQ_API_KEY
        private val BUILT_IN_OPENAI_KEY: String get() = com.nubiaagent.BuildConfig.OPENAI_API_KEY

        // EncryptedSharedPreferences para API key
        private const val SECURE_PREFS_NAME = "dayana_cloud_engine_secrets"
        private const val KEY_API_KEY = "cloud_api_key"
        private const val KEY_PROVIDER = "cloud_provider"
        private const val KEY_MODEL = "cloud_model"
        private const val KEY_CUSTOM_URL = "cloud_custom_url"
        private const val KEY_ENABLED = "cloud_enabled"
        private const val KEY_KEYS_INITIALIZED = "builtin_keys_initialized"

        // Budget tracking
        private const val KEY_TOTAL_TOKENS_USED = "total_tokens_used"
        private const val KEY_TOTAL_ESTIMATED_COST = "total_estimated_cost_cents"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"

        // Estadísticas de fallback
        private const val KEY_FALLBACK_STATS = "fallback_stats"

        fun getSecurePrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /**
         * Inicializa las API keys preconfiguradas si es la primera vez.
         * Solo se ejecuta una vez tras la instalación.
         */
        fun initializeBuiltinKeys(context: Context) {
            val prefs = getSecurePrefs(context)
            if (prefs.getBoolean(KEY_KEYS_INITIALIZED, false)) return

            prefs.edit()
                .putString("key_gemini", BUILT_IN_GEMINI_KEY)
                .putString("key_groq", BUILT_IN_GROQ_KEY)
                .putString("key_openai", BUILT_IN_OPENAI_KEY)
                .putString(KEY_PROVIDER, PROVIDER_GEMINI)  // Gemini por defecto
                .putString(KEY_MODEL, DEFAULT_MODELS[PROVIDER_GEMINI])
                .putBoolean(KEY_ENABLED, true)
                .putBoolean(KEY_KEYS_INITIALIZED, true)
                .apply()

            Log.i(TAG, "API keys preconfiguradas inicializadas (Gemini + Groq + OpenAI)")
        }

        /**
         * Obtiene la API key para un proveedor específico.
         * Primero busca la key personalizada, luego la preconfigurada.
         */
        fun getApiKeyForProvider(context: Context, provider: String): String? {
            val prefs = getSecurePrefs(context)
            // Key personalizada del usuario
            val customKey = prefs.getString("key_${provider}", null)
            if (!customKey.isNullOrEmpty()) return customKey
            // Fallback a key preconfigurada
            return when (provider) {
                PROVIDER_GEMINI -> BUILT_IN_GEMINI_KEY
                PROVIDER_GROQ -> BUILT_IN_GROQ_KEY
                PROVIDER_OPENAI -> BUILT_IN_OPENAI_KEY
                else -> prefs.getString(KEY_API_KEY, null)
            }
        }

        fun isConfigured(context: Context): Boolean {
            initializeBuiltinKeys(context)
            val prefs = getSecurePrefs(context)
            return prefs.getBoolean(KEY_ENABLED, false)
        }

        fun getApiKey(context: Context): String? {
            return getSecurePrefs(context).getString(KEY_API_KEY, null)
        }

        fun getProvider(context: Context): String {
            initializeBuiltinKeys(context)
            return getSecurePrefs(context).getString(KEY_PROVIDER, PROVIDER_GEMINI) ?: PROVIDER_GEMINI
        }

        fun getModel(context: Context): String {
            val provider = getProvider(context)
            return getSecurePrefs(context).getString(KEY_MODEL, DEFAULT_MODELS[provider]) ?: DEFAULT_MODELS[provider]!!
        }

        fun isEnabled(context: Context): Boolean {
            initializeBuiltinKeys(context)
            return getSecurePrefs(context).getBoolean(KEY_ENABLED, false)
        }

        /**
         * Guarda la configuración del motor cloud de forma encriptada.
         */
        fun saveConfig(
            context: Context,
            apiKey: String,
            provider: String = PROVIDER_GEMINI,
            model: String? = null,
            customUrl: String? = null,
            enabled: Boolean = true
        ) {
            val prefs = getSecurePrefs(context)
            prefs.edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_PROVIDER, provider)
                .putString(KEY_MODEL, model ?: DEFAULT_MODELS[provider])
                .putString(KEY_CUSTOM_URL, customUrl ?: "")
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
            Log.i(TAG, "Configuración cloud guardada: provider=$provider, model=${model ?: DEFAULT_MODELS[provider]}")
        }

        /**
         * Actualiza la API key de un proveedor específico.
         */
        fun saveProviderKey(context: Context, provider: String, apiKey: String) {
            getSecurePrefs(context).edit()
                .putString("key_${provider}", apiKey)
                .apply()
            Log.i(TAG, "API key actualizada para: $provider")
        }

        /**
         * Obtiene el uso acumulado del mes (tokens y costo estimado).
         */
        fun getUsageStats(context: Context): Pair<Long, Float> {
            val prefs = getSecurePrefs(context)
            val today = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
            val lastReset = prefs.getString(KEY_LAST_RESET_DATE, "")

            if (lastReset != today) {
                // Reset mensual
                prefs.edit()
                    .putString(KEY_LAST_RESET_DATE, today)
                    .putLong(KEY_TOTAL_TOKENS_USED, 0)
                    .putFloat(KEY_TOTAL_ESTIMATED_COST, 0f)
                    .apply()
                return Pair(0L, 0f)
            }

            return Pair(
                prefs.getLong(KEY_TOTAL_TOKENS_USED, 0),
                prefs.getFloat(KEY_TOTAL_ESTIMATED_COST, 0f)
            )
        }

        /**
         * Obtiene estadísticas de qué proveedor se usa más (fallback stats).
         */
        fun getFallbackStats(context: Context): Map<String, Int> {
            val prefs = getSecurePrefs(context)
            val statsStr = prefs.getString(KEY_FALLBACK_STATS, "") ?: ""
            if (statsStr.isBlank()) return emptyMap()
            return statsStr.split(",").associate { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else "" to 0
            }.filterKeys { it.isNotBlank() }
        }
    }

    private val securePrefs = getSecurePrefs(context)

    init {
        // Asegurar que las keys preconfiguradas estén disponibles
        initializeBuiltinKeys(context)
    }

    /**
     * Ejecuta inferencia via API cloud con FALLBACK AUTOMÁTICO.
     *
     * Orden: Gemini (gratis) → Groq (gratis) → OpenAI (pago)
     * Si el proveedor principal falla, automáticamente prueba el siguiente.
     */
    suspend fun infer(
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        if (!securePrefs.getBoolean(KEY_ENABLED, false)) {
            return@withContext "[ERROR: Motor cloud deshabilitado. Actívalo en Configuración.]"
        }

        val primaryProvider = securePrefs.getString(KEY_PROVIDER, PROVIDER_GEMINI)!!
        val customUrl = securePrefs.getString(KEY_CUSTOM_URL, "") ?: ""

        // Construir lista de proveedores a intentar: primario + fallback
        val providersToTry = buildProviderList(primaryProvider, customUrl)

        Log.i(TAG, "Inferencia: intentando ${providersToTry.size} proveedores (primario: $primaryProvider)")

        var lastError: String = ""
        for (provider in providersToTry) {
            try {
                val apiKey = getApiKeyForProvider(context, provider)
                if (apiKey.isNullOrEmpty()) {
                    Log.w(TAG, "Sin API key para $provider, saltando...")
                    continue
                }

                val model = if (provider == primaryProvider) {
                    securePrefs.getString(KEY_MODEL, DEFAULT_MODELS[provider]) ?: DEFAULT_MODELS[provider]!!
                } else {
                    DEFAULT_MODELS[provider] ?: continue
                }

                Log.i(TAG, "Intentando proveedor: $provider, modelo: $model")

                val result = when (provider) {
                    PROVIDER_GEMINI -> callGemini(apiKey, model, prompt, maxTokens, temperature)
                    PROVIDER_GROQ -> callGroq(apiKey, model, prompt, maxTokens, temperature)
                    PROVIDER_OPENAI -> callOpenAI(apiKey, model, prompt, maxTokens, temperature)
                    PROVIDER_ANTHROPIC -> callAnthropic(apiKey, model, prompt, maxTokens, temperature)
                    PROVIDER_OPENROUTER -> callOpenRouter(apiKey, model, prompt, maxTokens, temperature)
                    PROVIDER_CUSTOM -> callCustomAPI(apiKey, customUrl, model, prompt, maxTokens, temperature)
                    else -> continue
                }

                // Éxito — actualizar stats y retornar
                updateUsageStats(result.second, provider)
                updateFallbackStats(provider)

                if (provider != primaryProvider) {
                    Log.i(TAG, "Fallback exitoso: $provider respondió (primario $primaryProvider falló)")
                }

                return@withContext result.first

            } catch (e: Exception) {
                lastError = e.message ?: "Error desconocido"
                Log.w(TAG, "Proveedor $provider falló: $lastError")
                // Continuar con el siguiente proveedor
            }
        }

        "[ERROR: Todos los proveedores fallaron. Último error: $lastError]"
    }

    /**
     * Ejecuta inferencia con historial de conversación y fallback automático.
     */
    suspend fun inferWithHistory(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        if (!securePrefs.getBoolean(KEY_ENABLED, false)) {
            return@withContext "[ERROR: Motor cloud deshabilitado]"
        }

        val primaryProvider = securePrefs.getString(KEY_PROVIDER, PROVIDER_GEMINI)!!
        val providersToTry = buildProviderList(primaryProvider, "")

        var lastError: String = ""
        for (provider in providersToTry) {
            try {
                val apiKey = getApiKeyForProvider(context, provider)
                if (apiKey.isNullOrEmpty()) continue

                val model = if (provider == primaryProvider) {
                    securePrefs.getString(KEY_MODEL, DEFAULT_MODELS[provider]) ?: DEFAULT_MODELS[provider]!!
                } else {
                    DEFAULT_MODELS[provider] ?: continue
                }

                val result = when (provider) {
                    PROVIDER_GEMINI -> callGeminiWithHistory(apiKey, model, systemPrompt, messages, maxTokens, temperature)
                    PROVIDER_GROQ -> callGroqWithHistory(apiKey, model, systemPrompt, messages, maxTokens, temperature)
                    PROVIDER_ANTHROPIC -> callAnthropicWithHistory(apiKey, model, systemPrompt, messages, maxTokens, temperature)
                    else -> callOpenAIWithHistory(apiKey, model, systemPrompt, messages, maxTokens, temperature)
                }

                updateUsageStats(result.second, provider)
                updateFallbackStats(provider)
                return@withContext result.first

            } catch (e: Exception) {
                lastError = e.message ?: "Error desconocido"
                Log.w(TAG, "Proveedor $provider falló en inferWithHistory: $lastError")
            }
        }

        "[ERROR: Todos los proveedores fallaron. Último error: $lastError]"
    }

    /**
     * Construye la lista de proveedores a intentar.
     * Primero el seleccionado, luego los demás en orden de fallback.
     */
    private fun buildProviderList(primaryProvider: String, customUrl: String): List<String> {
        val result = mutableListOf<String>()
        result.add(primaryProvider)

        // Agregar fallbacks en orden
        for (provider in FALLBACK_ORDER) {
            if (provider != primaryProvider && provider !in result) {
                result.add(provider)
            }
        }

        // Agregar Anthropic si tiene key y no está ya
        if (PROVIDER_ANTHROPIC !in result) result.add(PROVIDER_ANTHROPIC)

        // Agregar Custom si tiene URL
        if (PROVIDER_CUSTOM !in result && customUrl.isNotBlank()) result.add(PROVIDER_CUSTOM)

        // Agregar OpenRouter si no está
        if (PROVIDER_OPENROUTER !in result) result.add(PROVIDER_OPENROUTER)

        return result
    }

    // ==================== GEMINI API ====================

    private fun callGemini(
        apiKey: String, model: String, prompt: String,
        maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_GEMINI]}/models/${model}:generateContent?key=$apiKey")

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", temperature.toDouble())
                put("topP", 0.9)
            })
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val response = readResponse(conn)
        conn.disconnect()

        val json = JSONObject(response)
        val candidates = json.optJSONArray("candidates")
        val usageMetadata = json.optJSONObject("usageMetadata")
        val totalTokens = usageMetadata?.optInt("totalTokenCount", 0) ?: 0

        val content = if (candidates != null && candidates.length() > 0) {
            candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    if (parts.length() > 0) parts.getJSONObject(0).optString("text", "") else ""
                } ?: ""
        } else ""

        return Pair(content.trim(), totalTokens)
    }

    private fun callGeminiWithHistory(
        apiKey: String, model: String, systemPrompt: String,
        history: List<ChatMessage>, maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_GEMINI]}/models/${model}:generateContent?key=$apiKey")

        val contentsArr = JSONArray().apply {
            history.forEach { msg ->
                val geminiRole = when (msg.role) {
                    "assistant" -> "model"
                    "system" -> "user"
                    else -> msg.role
                }
                put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", msg.content) })
                    })
                })
            }
        }

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", contentsArr)
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", temperature.toDouble())
                put("topP", 0.9)
            })
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        val response = readResponse(conn)
        conn.disconnect()

        val json = JSONObject(response)
        val candidates = json.optJSONArray("candidates")
        val usageMetadata = json.optJSONObject("usageMetadata")
        val totalTokens = usageMetadata?.optInt("totalTokenCount", 0) ?: 0

        val content = if (candidates != null && candidates.length() > 0) {
            candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts")
                ?.let { parts ->
                    if (parts.length() > 0) parts.getJSONObject(0).optString("text", "") else ""
                } ?: ""
        } else ""

        return Pair(content.trim(), totalTokens)
    }

    // ==================== GROQ API ====================

    /**
     * Groq usa formato OpenAI-compatible pero con velocidad ultra rápida.
     * Endpoint: POST https://api.groq.com/openai/v1/chat/completions
     * Auth: Bearer token
     */
    private fun callGroq(
        apiKey: String, model: String, prompt: String,
        maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_GROQ]}/chat/completions")
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        return callOpenAIFormat(url, apiKey, model, messages, maxTokens, temperature)
    }

    private fun callGroqWithHistory(
        apiKey: String, model: String, systemPrompt: String,
        history: List<ChatMessage>, maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_GROQ]}/chat/completions")
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            history.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }
        return callOpenAIFormat(url, apiKey, model, messages, maxTokens, temperature)
    }

    // ==================== OPENAI API ====================

    private fun callOpenAI(
        apiKey: String, model: String, prompt: String,
        maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_OPENAI]}/chat/completions")
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        return callOpenAIFormat(url, apiKey, model, messages, maxTokens, temperature)
    }

    private fun callOpenAIWithHistory(
        apiKey: String, model: String, systemPrompt: String,
        history: List<ChatMessage>, maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_OPENAI]}/chat/completions")
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            history.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }
        return callOpenAIFormat(url, apiKey, model, messages, maxTokens, temperature)
    }

    /**
     * Formato OpenAI compartido por: OpenAI, Groq, OpenRouter, Custom
     */
    private fun callOpenAIFormat(
        url: URL, apiKey: String, model: String,
        messages: JSONArray, maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            put("top_p", 0.9)
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val response = readResponse(conn)
        conn.disconnect()

        val json = JSONObject(response)
        val choices = json.optJSONArray("choices")
        val usage = json.optJSONObject("usage")
        val totalTokens = usage?.optInt("total_tokens", 0) ?: 0

        val content = if (choices != null && choices.length() > 0) {
            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content", "") ?: ""
        } else ""

        return Pair(content.trim(), totalTokens)
    }

    // ==================== OPENROUTER API ====================

    private fun callOpenRouter(
        apiKey: String, model: String, prompt: String,
        maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_OPENROUTER]}/chat/completions")
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/alvaradoMARCI/asistente")
            setRequestProperty("X-Title", "DayanaAssistant")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val response = readResponse(conn)
        conn.disconnect()

        val json = JSONObject(response)
        val choices = json.optJSONArray("choices")
        val usage = json.optJSONObject("usage")
        val totalTokens = usage?.optInt("total_tokens", 0) ?: 0

        val content = if (choices != null && choices.length() > 0) {
            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content", "") ?: ""
        } else ""

        return Pair(content.trim(), totalTokens)
    }

    // ==================== ANTHROPIC API ====================

    private fun callAnthropic(
        apiKey: String, model: String, prompt: String,
        maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_ANTHROPIC]}/messages")

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val response = readResponse(conn)
        conn.disconnect()

        val json = JSONObject(response)
        val contentArr = json.optJSONArray("content")
        val usage = json.optJSONObject("usage")
        val totalTokens = (usage?.optInt("input_tokens", 0) ?: 0) +
                          (usage?.optInt("output_tokens", 0) ?: 0)

        val content = if (contentArr != null && contentArr.length() > 0) {
            contentArr.getJSONObject(0).optString("text", "")
        } else ""

        return Pair(content.trim(), totalTokens)
    }

    private fun callAnthropicWithHistory(
        apiKey: String, model: String, systemPrompt: String,
        history: List<ChatMessage>, maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        val url = URL("${API_BASE_URLS[PROVIDER_ANTHROPIC]}/messages")

        val messagesArr = JSONArray().apply {
            history.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            put("system", systemPrompt)
            put("messages", messagesArr)
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val response = readResponse(conn)
        conn.disconnect()

        val json = JSONObject(response)
        val contentArr = json.optJSONArray("content")
        val usage = json.optJSONObject("usage")
        val totalTokens = (usage?.optInt("input_tokens", 0) ?: 0) +
                          (usage?.optInt("output_tokens", 0) ?: 0)

        val content = if (contentArr != null && contentArr.length() > 0) {
            contentArr.getJSONObject(0).optString("text", "")
        } else ""

        return Pair(content.trim(), totalTokens)
    }

    // ==================== CUSTOM API ====================

    private fun callCustomAPI(
        apiKey: String, customUrl: String, model: String,
        prompt: String, maxTokens: Int, temperature: Float
    ): Pair<String, Int> {
        if (customUrl.isBlank()) {
            return Pair("[ERROR: URL custom no configurada]", 0)
        }
        val url = URL("$customUrl/chat/completions")
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }
        return callOpenAIFormat(url, apiKey, model, messages, maxTokens, temperature)
    }

    // ==================== UTILIDADES ====================

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream

        val reader = BufferedReader(InputStreamReader(stream))
        val response = StringBuilder()
        var line: String?

        while (reader.readLine().also { line = it } != null) {
            response.append(line)
        }
        reader.close()

        if (code !in 200..299) {
            Log.e(TAG, "API error $code: ${response.toString().take(200)}")
            throw Exception("API error $code: ${response.toString().take(100)}")
        }

        return response.toString()
    }

    private fun updateUsageStats(tokensUsed: Int, provider: String) {
        if (tokensUsed <= 0) return

        val costPerToken = when (provider) {
            PROVIDER_GEMINI -> 0.0f     // GRATIS
            PROVIDER_GROQ -> 0.0f       // GRATIS
            PROVIDER_OPENAI -> 0.00005f  // ~$0.05 por 1K tokens
            PROVIDER_ANTHROPIC -> 0.00008f
            PROVIDER_OPENROUTER -> 0.00004f
            else -> 0.00005f
        }

        val estimatedCostCents = tokensUsed * costPerToken * 100

        val currentTokens = securePrefs.getLong(KEY_TOTAL_TOKENS_USED, 0)
        val currentCost = securePrefs.getFloat(KEY_TOTAL_ESTIMATED_COST, 0f)

        securePrefs.edit()
            .putLong(KEY_TOTAL_TOKENS_USED, currentTokens + tokensUsed)
            .putFloat(KEY_TOTAL_ESTIMATED_COST, currentCost + estimatedCostCents)
            .apply()

        Log.d(TAG, "Uso actualizado: +$tokensUsed tokens via $provider, ~$${"%.4f".format(estimatedCostCents / 100)}")
    }

    private fun updateFallbackStats(provider: String) {
        val stats = getFallbackStats(context).toMutableMap()
        stats[provider] = (stats[provider] ?: 0) + 1
        val statsStr = stats.entries.joinToString(",") { "${it.key}:${it.value}" }
        securePrefs.edit().putString(KEY_FALLBACK_STATS, statsStr).apply()
    }

    /**
     * Mensaje de chat con rol y contenido.
     */
    data class ChatMessage(
        val role: String,  // "system", "user", "assistant"
        val content: String
    )
}
