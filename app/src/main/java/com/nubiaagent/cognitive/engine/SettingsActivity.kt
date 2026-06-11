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

/**
 * SettingsActivity: Pantalla de configuración del motor de inferencia cloud.
 *
 * Permite al usuario configurar:
 * - Proveedor de API (OpenAI, Anthropic, OpenRouter, Custom)
 * - API Key (almacenada con AES-256-GCM en EncryptedSharedPreferences)
 * - Modelo a usar
 * - URL custom (para LM Studio, Ollama, etc.)
 * - Habilitar/deshabilitar el motor cloud
 *
 * Esta pantalla se crea programáticamente sin XML para mantener
 * la estética Shadow Black / Cyber Silver del NubiaAgent.
 *
 * COSTOS ESTIMADOS (se muestran en la UI):
 * - GPT-4o-mini: ~$0.15/1M input + $0.60/1M output
 * - Claude Haiku: ~$0.25/1M input + $1.25/1M output
 * - OpenRouter: Varía, algunos modelos gratuitos
 */
class SettingsActivity : Activity() {

    // Colores Mecha Futurista
    private val COLOR_BG = 0xFF1A1A2E.toInt()
    private val COLOR_SURFACE = 0xFF16213E.toInt()
    private val COLOR_ACCENT = 0xFFE94560.toInt()
    private val COLOR_TEXT = 0xFFC0C0C0.toInt()
    private val COLOR_TEXT_DIM = 0xFF808080.toInt()
    private val COLOR_INPUT_BG = 0xFF0F0F1A.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        layout.addView(createTitle("CONFIGURACION DEL MOTOR"))

        layout.addView(createSpacer(24))

        // ===== SWITCH HABILITAR =====
        val enabledSwitch = Switch(this).apply {
            text = "  Motor Cloud habilitado"
            setTextColor(COLOR_TEXT)
            textSize = 16f
            isChecked = CloudInferenceEngine.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                val prefs = CloudInferenceEngine.getSecurePrefs(this@SettingsActivity)
                val apiKey = prefs.getString("cloud_api_key", null)
                prefs.edit().putBoolean("cloud_enabled", isChecked).apply()
                if (isChecked && apiKey.isNullOrEmpty()) {
                    isChecked = false
                    showToast("Configura tu API Key primero")
                }
            }
        }
        layout.addView(enabledSwitch)

        layout.addView(createSpacer(16))

        // ===== PROVEEDOR =====
        layout.addView(createLabel("Proveedor de API"))

        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                listOf("OpenAI", "Anthropic (Claude)", "OpenRouter", "Custom / Local")
            )
            setSelection(when (CloudInferenceEngine.getProvider(this@SettingsActivity)) {
                CloudInferenceEngine.PROVIDER_OPENAI -> 0
                CloudInferenceEngine.PROVIDER_ANTHROPIC -> 1
                CloudInferenceEngine.PROVIDER_OPENROUTER -> 2
                CloudInferenceEngine.PROVIDER_CUSTOM -> 3
                else -> 0
            })
        }
        layout.addView(providerSpinner)

        layout.addView(createSpacer(12))

        // ===== API KEY =====
        layout.addView(createLabel("API Key"))

        val apiKeyInput = EditText(this).apply {
            hint = "sk-... / sk-ant-... / sk-or-..."
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_TEXT_DIM)
            setBackgroundColor(COLOR_INPUT_BG)
            setPadding(24, 16, 24, 16)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            val existingKey = CloudInferenceEngine.getApiKey(this@SettingsActivity)
            if (!existingKey.isNullOrEmpty()) {
                setText("••••••••••••••••")
            }
        }
        layout.addView(apiKeyInput)

        layout.addView(createSpacer(8))

        layout.addView(createInfoText(
            "Tu API Key se almacena encriptada con AES-256-GCM usando Android Keystore. " +
            "Nunca se comparte con terceros."
        ))

        layout.addView(createSpacer(12))

        // ===== MODELO =====
        layout.addView(createLabel("Modelo"))

        val modelInput = EditText(this).apply {
            hint = "gpt-4o-mini"
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
                    0 -> CloudInferenceEngine.PROVIDER_OPENAI
                    1 -> CloudInferenceEngine.PROVIDER_ANTHROPIC
                    2 -> CloudInferenceEngine.PROVIDER_OPENROUTER
                    3 -> CloudInferenceEngine.PROVIDER_CUSTOM
                    else -> CloudInferenceEngine.PROVIDER_OPENAI
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
                saveConfig(providerSpinner, apiKeyInput, modelInput, customUrlInput, enabledSwitch)
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

        layout.addView(createInfoText(
            "Tokens consumidos: ${String.format("%,d", tokensUsed)}\n" +
            "Costo estimado: $${String.format("%.2f", costDollars)} USD\n\n" +
            "Referencia de precios (por 1M tokens):\n" +
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
        providerSpinner: Spinner,
        apiKeyInput: EditText,
        modelInput: EditText,
        customUrlInput: EditText,
        enabledSwitch: Switch
    ) {
        val provider = when (providerSpinner.selectedItemPosition) {
            0 -> CloudInferenceEngine.PROVIDER_OPENAI
            1 -> CloudInferenceEngine.PROVIDER_ANTHROPIC
            2 -> CloudInferenceEngine.PROVIDER_OPENROUTER
            3 -> CloudInferenceEngine.PROVIDER_CUSTOM
            else -> CloudInferenceEngine.PROVIDER_OPENAI
        }

        val apiKeyRaw = apiKeyInput.text.toString()
        // Si el usuario no cambió la key (muestra puntos), mantener la existente
        val apiKey = if (apiKeyRaw.startsWith("•••")) {
            CloudInferenceEngine.getApiKey(this) ?: ""
        } else {
            apiKeyRaw
        }

        if (apiKey.isBlank()) {
            showToast("Ingresa tu API Key")
            return
        }

        val model = modelInput.text.toString().ifBlank {
            CloudInferenceEngine.DEFAULT_MODELS[provider] ?: "gpt-4o-mini"
        }

        val customUrl = customUrlInput.text.toString()

        CloudInferenceEngine.saveConfig(
            context = this,
            apiKey = apiKey,
            provider = provider,
            model = model,
            customUrl = customUrl,
            enabled = enabledSwitch.isChecked
        )

        showToast("Configuracion guardada")
    }

    private fun testConnection() {
        if (!CloudInferenceEngine.isConfigured(this)) {
            showToast("Configura tu API Key primero")
            return
        }

        showToast("Probando conexion...")

        Thread {
            try {
                val engine = CloudInferenceEngine(this)
                // Usar un thread con runBlocking para la prueba
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
