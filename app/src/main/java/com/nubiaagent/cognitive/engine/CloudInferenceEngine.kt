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
 * CloudInferenceEngine: Motor de inferencia via API cloud.
 *
 * Soluciona el problema de no tener PC para compilar llama.cpp:
 * en lugar de inferencia local, usa APIs de lenguaje en la nube
 * para que NubiaAgent funcione INMEDIATAMENTE tras instalar el APK.
 *
 * PROVEEDORES SOPORTADOS:
 * 1. OpenAI (GPT-4o-mini, GPT-4o, GPT-3.5-turbo) — Mejor calidad
 * 2. Anthropic (Claude Haiku, Claude Sonnet) — Buen razonamiento
 * 3. OpenRouter — Gateway multi-modelo, precios bajos
 * 4. Cualquier API compatible con formato OpenAI (LM Studio, Ollama, etc.)
 *
 * ESTRATEGIA DE FALLBACK:
 * Local (llama.cpp) → Cloud (OpenAI/Anthropic) → Simulación
 *
 * El motor siempre prefiere inferencia local por privacidad, pero
 * si no hay modelo nativo disponible, transparentemente usa la API
 * cloud. El usuario configura su API key en la pantalla de ajustes.
 *
 * SEGURIDAD DE API KEY:
 * La API key se almacena en EncryptedSharedPreferences con AES-256-GCM
 * usando Android Keystore. Nunca se almacena en texto plano ni se
 * envía a terceros (solo al proveedor de API seleccionado).
 *
 * MODO ECONÓMICO:
 * - GPT-4o-mini: ~$0.15/1M input tokens, ~$0.60/1M output tokens
 * - Claude Haiku: ~$0.25/1M input tokens, ~$1.25/1M output tokens
 * - OpenRouter: Varía por modelo, algunos gratuitos
 * Con uso moderado (~50 consultas/día), el costo mensual es < $3 USD.
 */
class CloudInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/CloudEngine"

        // Proveedores
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OPENROUTER = "openrouter"
        const val PROVIDER_CUSTOM = "custom"

        // Modelos por defecto
        val DEFAULT_MODELS = mapOf(
            PROVIDER_OPENAI to "gpt-4o-mini",
            PROVIDER_ANTHROPIC to "claude-3-haiku-20240307",
            PROVIDER_OPENROUTER to "openai/gpt-4o-mini",
            PROVIDER_CUSTOM to "gpt-4o-mini"
        )

        // URLs base de API
        val API_BASE_URLS = mapOf(
            PROVIDER_OPENAI to "https://api.openai.com/v1",
            PROVIDER_ANTHROPIC to "https://api.anthropic.com/v1",
            PROVIDER_OPENROUTER to "https://openrouter.ai/api/v1",
            PROVIDER_CUSTOM to ""  // El usuario configura la URL
        )

        // EncryptedSharedPreferences para API key
        private const val SECURE_PREFS_NAME = "nubia_cloud_engine_secrets"
        private const val KEY_API_KEY = "cloud_api_key"
        private const val KEY_PROVIDER = "cloud_provider"
        private const val KEY_MODEL = "cloud_model"
        private const val KEY_CUSTOM_URL = "cloud_custom_url"
        private const val KEY_ENABLED = "cloud_enabled"

        // Budget tracking
        private const val KEY_TOTAL_TOKENS_USED = "total_tokens_used"
        private const val KEY_TOTAL_ESTIMATED_COST = "total_estimated_cost_cents"
        private const val KEY_LAST_RESET_DATE = "last_reset_date"

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

        fun isConfigured(context: Context): Boolean {
            val prefs = getSecurePrefs(context)
            return prefs.getString(KEY_API_KEY, null) != null &&
                   prefs.getBoolean(KEY_ENABLED, false)
        }

        fun getApiKey(context: Context): String? {
            return getSecurePrefs(context).getString(KEY_API_KEY, null)
        }

        fun getProvider(context: Context): String {
            return getSecurePrefs(context).getString(KEY_PROVIDER, PROVIDER_OPENAI) ?: PROVIDER_OPENAI
        }

        fun getModel(context: Context): String {
            val provider = getProvider(context)
            return getSecurePrefs(context).getString(KEY_MODEL, DEFAULT_MODELS[provider]) ?: DEFAULT_MODELS[provider]!!
        }

        fun isEnabled(context: Context): Boolean {
            return getSecurePrefs(context).getBoolean(KEY_ENABLED, false)
        }

        /**
         * Guarda la configuración del motor cloud de forma encriptada.
         */
        fun saveConfig(
            context: Context,
            apiKey: String,
            provider: String = PROVIDER_OPENAI,
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
    }

    private val securePrefs = getSecurePrefs(context)

    /**
     * Ejecuta inferencia via API cloud.
     *
     * Formato de messages compatible con OpenAI Chat Completions:
     * - system: Instrucciones del SOUL + Living Profile
     * - user: Mensaje del usuario
     * - assistant: Respuestas previas del agente
     *
     * @param prompt El prompt completo (system + contexto + mensaje)
     * @param maxTokens Máximo de tokens a generar
     * @param temperature Temperatura de muestreo (0.0 - 2.0)
     * @return Texto generado por el modelo
     */
    suspend fun infer(
        prompt: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        if (!securePrefs.getBoolean(KEY_ENABLED, false)) {
            return@withContext "[ERROR: Motor cloud deshabilitado]"
        }

        val apiKey = securePrefs.getString(KEY_API_KEY, null)
        if (apiKey.isNullOrEmpty()) {
            return@withContext "[ERROR: API Key no configurada]"
        }

        val provider = securePrefs.getString(KEY_PROVIDER, PROVIDER_OPENAI)!!
        val model = securePrefs.getString(KEY_MODEL, DEFAULT_MODELS[provider]) ?: DEFAULT_MODELS[provider]!!
        val customUrl = securePrefs.getString(KEY_CUSTOM_URL, "") ?: ""

        Log.i(TAG, "Inferencia cloud: provider=$provider, model=$model")

        try {
            val result = when (provider) {
                PROVIDER_OPENAI -> callOpenAI(apiKey, model, prompt, maxTokens, temperature)
                PROVIDER_ANTHROPIC -> callAnthropic(apiKey, model, prompt, maxTokens, temperature)
                PROVIDER_OPENROUTER -> callOpenRouter(apiKey, model, prompt, maxTokens, temperature)
                PROVIDER_CUSTOM -> callCustomAPI(apiKey, customUrl, model, prompt, maxTokens, temperature)
                else -> callOpenAI(apiKey, model, prompt, maxTokens, temperature)
            }

            // Actualizar estadísticas de uso
            updateUsageStats(result.second)

            result.first
        } catch (e: Exception) {
            Log.e(TAG, "Error en inferencia cloud: ${e.message}", e)
            "[ERROR: ${e.message}]"
        }
    }

    /**
     * Ejecuta inferencia con historial de conversación (más natural).
     * Separar system prompt del historial permite mejor control.
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

        val apiKey = securePrefs.getString(KEY_API_KEY, null)
        if (apiKey.isNullOrEmpty()) {
            return@withContext "[ERROR: API Key no configurada]"
        }

        val provider = securePrefs.getString(KEY_PROVIDER, PROVIDER_OPENAI)!!
        val model = securePrefs.getString(KEY_MODEL, DEFAULT_MODELS[provider]) ?: DEFAULT_MODELS[provider]!!

        try {
            val result = when (provider) {
                PROVIDER_ANTHROPIC -> callAnthropicWithHistory(apiKey, model, systemPrompt, messages, maxTokens, temperature)
                else -> callOpenAIWithHistory(apiKey, model, systemPrompt, messages, maxTokens, temperature)
            }

            updateUsageStats(result.second)
            result.first
        } catch (e: Exception) {
            Log.e(TAG, "Error en inferencia cloud: ${e.message}", e)
            "[ERROR: ${e.message}]"
        }
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

        // Parsear respuesta
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
            setRequestProperty("X-Title", "NubiaAgent")
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
        // Asume formato OpenAI-compatible (LM Studio, Ollama, etc.)
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

    private fun updateUsageStats(tokensUsed: Int) {
        if (tokensUsed <= 0) return

        val provider = securePrefs.getString(KEY_PROVIDER, PROVIDER_OPENAI)!!
        val costPerToken = when (provider) {
            PROVIDER_OPENAI -> 0.00005f  // ~$0.05 por 1K tokens (GPT-4o-mini promedio)
            PROVIDER_ANTHROPIC -> 0.00008f
            PROVIDER_OPENROUTER -> 0.00004f
            else -> 0.00005f
        }

        val estimatedCostCents = tokensUsed * costPerToken * 100  // en centavos USD

        val currentTokens = securePrefs.getLong(KEY_TOTAL_TOKENS_USED, 0)
        val currentCost = securePrefs.getFloat(KEY_TOTAL_ESTIMATED_COST, 0f)

        securePrefs.edit()
            .putLong(KEY_TOTAL_TOKENS_USED, currentTokens + tokensUsed)
            .putFloat(KEY_TOTAL_ESTIMATED_COST, currentCost + estimatedCostCents)
            .apply()

        Log.d(TAG, "Uso actualizado: +$tokensUsed tokens, ~$${"%.4f".format(estimatedCostCents / 100)}")
    }

    /**
     * Mensaje de chat con rol y contenido.
     */
    data class ChatMessage(
        val role: String,  // "system", "user", "assistant"
        val content: String
    )
}
