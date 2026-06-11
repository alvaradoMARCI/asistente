package com.nubiaagent.cognitive.engine

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nubiaagent.cognitive.engine.CloudInferenceEngine
import com.nubiaagent.cognitive.identity.IdentityManager

/**
 * SettingsActivity: Pantalla de configuración del asistente Dayana.
 *
 * Permite al usuario configurar:
 * - Nombre del asistente (Dayana, Marcia, Nubia)
 * - Proveedor de API principal (Gemini, Groq, OpenAI, Anthropic, OpenRouter, Custom)
 * - API Keys (con fallback automático: Gemini → Groq → OpenAI)
 * - Modelo a usar
 * - URL custom (para LM Studio, Ollama, etc.)
 * - Habilitar/deshabilitar el motor cloud
 *
 * FALLBACK AUTOMÁTICO:
 * Las 3 API keys (Gemini, Groq, OpenAI) están preconfiguradas.
 * Si un proveedor falla, automáticamente prueba el siguiente.
 * Esto garantiza que el asistente SIEMPRE funcione.
 */
class SettingsActivity : Activity() {

    // Colores Mecha Futurista
    private val COLOR_BG = 0xFF1A1A2E.toInt()
    private val COLOR_SURFACE = 0xFF16213E.toInt()
    private val COLOR_ACCENT = 0xFFE94560.toInt()
    private val COLOR_TEXT = 0xFFC0C0C0.toInt()
    private val COLOR_TEXT_DIM = 0xFF808080.toInt()
    private val COLOR_INPUT_BG = 0xFF0F0F1A.toInt()
    private val COLOR_SUCCESS = 0xFF00C853.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Asegurar que las keys preconfiguradas estén disponibles
        CloudInferenceEngine.initializeBuiltinKeys(this)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            setPadding(32, 48, 32, 48)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ===== TÍTULO =====
        layout.addView(createTitle("CONFIGURACION DE DAYANA"))

        layout.addView(createSpacer(16))

        // ===== NOMBRE DEL ASISTENTE =====
        layout.addView(createLabel("Nombre del Asistente"))

        val identityManager = IdentityManager(this)
        val currentName = identityManager.getPersonaName()

        val nameSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                IdentityManager.ASSISTANT_NAMES
            )
            setSelection(IdentityManager.ASSISTANT_NAMES.indexOf(currentName).coerceAtLeast(0))
        }
        layout.addView(nameSpinner)

        layout.addView(createSpacer(8))
        layout.addView(createInfoText(
            "El asistente responderá al nombre que elijas. " +
            "Wake word: \"Hey [nombre]\" (ej: \"Hey Dayana\")"
        ))

        layout.addView(createSpacer(20))

        // ===== SWITCH HABILITAR =====
        val enabledSwitch = Switch(this).apply {
            text = "  Motor Cloud habilitado"
            setTextColor(COLOR_TEXT)
            textSize = 16f
            isChecked = CloudInferenceEngine.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                val prefs = CloudInferenceEngine.getSecurePrefs(this@SettingsActivity)
                prefs.edit().putBoolean("cloud_enabled", isChecked).apply()
                if (isChecked) {
                    showToast("Motor habilitado — 3 API keys activas con fallback")
                }
            }
        }
        layout.addView(enabledSwitch)

        layout.addView(createSpacer(8))
        layout.addView(createInfoText(
            "API keys preconfiguradas: Gemini (GRATIS) + Groq (GRATIS) + OpenAI. " +
            "Si una falla, automáticamente prueba la siguiente."
        ))

        layout.addView(createSpacer(16))

        // ===== PROVEEDOR PRINCIPAL =====
        layout.addView(createLabel("Proveedor Principal"))

        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                listOf("Google Gemini (GRATIS)", "Groq (GRATIS)", "OpenAI", "Anthropic (Claude)", "OpenRouter", "Custom / Local")
            )
            setSelection(when (CloudInferenceEngine.getProvider(this@SettingsActivity)) {
                CloudInferenceEngine.PROVIDER_GEMINI -> 0
                CloudInferenceEngine.PROVIDER_GROQ -> 1
                CloudInferenceEngine.PROVIDER_OPENAI -> 2
                CloudInferenceEngine.PROVIDER_ANTHROPIC -> 3
                CloudInferenceEngine.PROVIDER_OPENROUTER -> 4
                CloudInferenceEngine.PROVIDER_CUSTOM -> 5
                else -> 0
            })
        }
        layout.addView(providerSpinner)

        layout.addView(createSpacer(12))

        // ===== API KEY =====
        layout.addView(createLabel("API Key (opcional — ya hay keys preconfiguradas)"))

        val apiKeyInput = EditText(this).apply {
            hint = "Dejar vacío para usar keys preconfiguradas"
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_DIM)
            setBackgroundColor(COLOR_INPUT_BG)
            setPadding(24, 16, 24, 16)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(apiKeyInput)

        layout.addView(createSpacer(8))

        layout.addView(createInfoText(
            "Las 3 API keys ya están integradas y encriptadas. Solo ingresa una key " +
            "si quieres reemplazar la preconfigurada para el proveedor seleccionado. " +
            "Almacenada con AES-256-GCM via Android Keystore."
        ))

        layout.addView(createSpacer(12))

        // ===== MODELO =====
        layout.addView(createLabel("Modelo"))

        val modelInput = EditText(this).apply {
            hint = "gemini-2.0-flash"
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_DIM)
            setBackgroundColor(COLOR_INPUT_BG)
            setPadding(24, 16, 24, 16)
            textSize = 14f
            setText(CloudInferenceEngine.getModel(this@SettingsActivity))
        }
        layout.addView(modelInput)

        layout.addView(createSpacer(8))

        // Actualizar modelo cuando cambia proveedor
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val provider = when (pos) {
                    0 -> CloudInferenceEngine.PROVIDER_GEMINI
                    1 -> CloudInferenceEngine.PROVIDER_GROQ
                    2 -> CloudInferenceEngine.PROVIDER_OPENAI
                    3 -> CloudInferenceEngine.PROVIDER_ANTHROPIC
                    4 -> CloudInferenceEngine.PROVIDER_OPENROUTER
                    5 -> CloudInferenceEngine.PROVIDER_CUSTOM
                    else -> CloudInferenceEngine.PROVIDER_GEMINI
                }
                modelInput.hint = CloudInferenceEngine.DEFAULT_MODELS[provider] ?: "gpt-4o-mini"
                if (modelInput.text.isNullOrBlank()) {
                    modelInput.setText(CloudInferenceEngine.DEFAULT_MODELS[provider] ?: "")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ===== URL CUSTOM =====
        layout.addView(createLabel("URL Custom (solo para Custom)"))

        val customUrlInput = EditText(this).apply {
            hint = "http://192.168.1.100:1234/v1"
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_DIM)
            setBackgroundColor(COLOR_INPUT_BG)
            setPadding(24, 16, 24, 16)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            val prefs = CloudInferenceEngine.getSecurePrefs(this@SettingsActivity)
            setText(prefs.getString("cloud_custom_url", "") ?: "")
        }
        layout.addView(customUrlInput)

        layout.addView(createSpacer(8))
        layout.addView(createInfoText(
            "Para LM Studio u Ollama en tu red local, usa la IP de tu PC."
        ))

        layout.addView(createSpacer(24))

        // ===== BOTÓN GUARDAR =====
        val saveButton = Button(this).apply {
            text = "GUARDAR CONFIGURACION"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(COLOR_ACCENT)
            setPadding(32, 24, 32, 24)
            textSize = 16f
            setOnClickListener {
                saveConfig(nameSpinner, providerSpinner, apiKeyInput, modelInput, customUrlInput, enabledSwitch)
            }
        }
        layout.addView(saveButton)

        layout.addView(createSpacer(16))

        // ===== TEST CONNECTION =====
        val testButton = Button(this).apply {
            text = "PROBAR CONEXION"
            setTextColor(COLOR_TEXT)
            setBackgroundColor(COLOR_SURFACE)
            setPadding(32, 24, 32, 24)
            textSize = 14f
            setOnClickListener { testConnection() }
        }
        layout.addView(testButton)

        layout.addView(createSpacer(24))

        // ===== USO Y COSTOS =====
        layout.addView(createTitle("USO DEL MES"))

        val (tokensUsed, costCents) = CloudInferenceEngine.getUsageStats(this)
        val costDollars = costCents / 100f

        // Estadísticas de fallback
        val fallbackStats = CloudInferenceEngine.getFallbackStats(this)
        val fallbackInfo = if (fallbackStats.isNotEmpty()) {
            "\n\nProveedores utilizados:\n" +
            fallbackStats.entries.joinToString("\n") { "  ${it.key}: ${it.value} consultas" }
        } else ""

        layout.addView(createInfoText(
            "Tokens consumidos: ${String.format("%,d", tokensUsed)}\n" +
            "Costo estimado: $${String.format("%.2f", costDollars)} USD\n" +
            "Sistema de fallback: Gemini → Groq → OpenAI$fallbackInfo\n\n" +
            "Referencia de precios (por 1M tokens):\n" +
            "  Gemini 2.0 Flash: GRATIS (15 RPM, 1M contexto)\n" +
            "  Groq Llama 3.3: GRATIS (30 RPM, ultra rápido)\n" +
            "  GPT-4o-mini:  $0.15 entrada / $0.60 salida\n" +
            "  Claude Haiku:  $0.25 entrada / $1.25 salida\n" +
            "  OpenRouter:    Varía (algunos gratuitos)"
        ))

        layout.addView(createSpacer(32))

        // ===== BOTÓN VOLVER =====
        val backButton = Button(this).apply {
            text = "VOLVER"
            setTextColor(COLOR_TEXT)
            setBackgroundColor(COLOR_SURFACE)
            setPadding(32, 16, 32, 16)
            setOnClickListener { finish() }
        }
        layout.addView(backButton)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun saveConfig(
        nameSpinner: Spinner,
        providerSpinner: Spinner,
        apiKeyInput: EditText,
        modelInput: EditText,
        customUrlInput: EditText,
        enabledSwitch: Switch
    ) {
        // Guardar nombre del asistente
        val selectedName = IdentityManager.ASSISTANT_NAMES[nameSpinner.selectedItemPosition]
        val identityManager = IdentityManager(this)
        identityManager.setPersonaName(selectedName)

        // Guardar proveedor
        val provider = when (providerSpinner.selectedItemPosition) {
            0 -> CloudInferenceEngine.PROVIDER_GEMINI
            1 -> CloudInferenceEngine.PROVIDER_GROQ
            2 -> CloudInferenceEngine.PROVIDER_OPENAI
            3 -> CloudInferenceEngine.PROVIDER_ANTHROPIC
            4 -> CloudInferenceEngine.PROVIDER_OPENROUTER
            5 -> CloudInferenceEngine.PROVIDER_CUSTOM
            else -> CloudInferenceEngine.PROVIDER_GEMINI
        }

        // Si el usuario ingresó una API key nueva, guardarla para ese proveedor
        val apiKeyRaw = apiKeyInput.text.toString().trim()
        if (apiKeyRaw.isNotBlank()) {
            CloudInferenceEngine.saveProviderKey(this, provider, apiKeyRaw)
        }

        val model = modelInput.text.toString().ifBlank {
            CloudInferenceEngine.DEFAULT_MODELS[provider] ?: "gemini-2.0-flash"
        }

        val customUrl = customUrlInput.text.toString()

        // Obtener la key para el proveedor (preconfigurada o personalizada)
        val activeKey = CloudInferenceEngine.getApiKeyForProvider(this, provider) ?: ""

        CloudInferenceEngine.saveConfig(
            context = this,
            apiKey = activeKey,
            provider = provider,
            model = model,
            customUrl = customUrl,
            enabled = enabledSwitch.isChecked
        )

        showToast("Configuracion guardada — Nombre: $selectedName, Proveedor: $provider, Fallback: activo")
    }

    private fun testConnection() {
        if (!CloudInferenceEngine.isConfigured(this)) {
            showToast("Habilita el Motor Cloud primero")
            return
        }

        showToast("Probando conexion con fallback automatico...")

        Thread {
            try {
                val engine = CloudInferenceEngine(this)
                val result = kotlinx.coroutines.runBlocking {
                    engine.infer(
                        prompt = "Responde solo: CONEXION_EXITOSA",
                        maxTokens = 20,
                        temperature = 0f
                    )
                }

                runOnUiThread {
                    if (result.contains("CONEXION_EXITOSA", ignoreCase = true) ||
                        result.isNotBlank() && !result.startsWith("[ERROR")) {
                        showToast("Conexion exitosa! Respuesta: ${result.take(50)}")
                    } else {
                        showToast("Error: ${result.take(80)}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast("Error de conexion: ${e.message}")
                }
            }
        }.start()
    }

    // ==================== UI HELPERS ====================

    private fun createTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_ACCENT)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
    }

    private fun createInfoText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT_DIM)
            textSize = 11f
            setPadding(8, 4, 8, 4)
        }
    }

    private fun createSpacer(heightPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
