package com.nubiaagent.cognitive.identity

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * IdentityManager: Gestiona la identidad y el System Prompt (SOUL) del agente.
 *
 * Carga las instrucciones maestras desde el archivo SOUL.md que define:
 * - Personalidad y tono del asistente
 * - Directrices de acción (Pensar → Actuar → Observar)
 * - Perfiles de autonomía
 * - Restricciones absolutas (privacidad, seguridad)
 * - Reglas de uso de herramientas
 *
 * ARQUITECTURA DE IDENTIDAD:
 *
 * El System Prompt se construye en capas:
 * 1. SOUL.md (base): Instrucciones inmutables del agente
 * 2. Living Profile (dinámico): Preferencias del usuario, metas, patrones
 * 3. Contexto de sesión: Estado actual del agente y la conversación
 * 4. Tool schemas: Descripción de herramientas disponibles
 *
 * El prompt final tiene esta estructura:
 * ```
 * <|system|>
 * [SOUL.md completo]
 * 
 * ## PERFIL DEL USUARIO
 * [Living Profile resumido]
 * 
 * ## HERRAMIENTAS DISPONIBLES
 * [Lista de tools con schemas]
 * 
 * ## ESTADO ACTUAL
 * [Hardware, actividad, última acción]
 * <|user|>
 * [Mensaje del usuario]
 * <|assistant|]
 * ```
 *
 * OPTIMIZACIÓN DE TOKENS:
 * El SOUL.md completo pesa ~1500 tokens. Con el Living Profile
 * (~3500 tokens) y herramientas (~500 tokens), el system prompt
 * total es ~5500 tokens. Con un contexto de 4096, se truncaría.
 * Por eso se prioriza el contenido esencial y se usan templates
 * comprimidos cuando el contexto es limitado.
 */
class IdentityManager(private val context: Context) {

    companion object {
        private const val TAG = "Dayana/Identity"
        private const val SOUL_FILE = "SOUL.md"
        private const val PROFILE_FILE = "living_profile.md"

        // Nombres disponibles del asistente
        private const val PRIMARY_NAME = "Dayana"
        val ASSISTANT_NAMES = listOf("Dayana", "Marcia", "Nubia")

        // Nombre activo (se puede cambiar en configuración)
        private const val KEY_ASSISTANT_NAME = "assistant_name"

        // Plantilla comprimida para contextos pequeños
        private const val COMPRESSED_SOUL = """Eres Dayana, asistente personal inteligente. Ciclo: Pensar→Actuar→Observar. Perfil: {{AUTONOMY}}. Reglas: 1)Privacidad absoluta 2)Confirmar acciones destructivas 3)Consultar memoria antes de responder 4)Ser concisa y amigable. Responde al nombre Dayana. Herramientas: {{TOOLS}}."""

        // Living Profile por defecto (primera ejecución)
        private const val DEFAULT_LIVING_PROFILE = """
# Perfil del Usuario

## Información Básica
- Nombre: (por determinar)
- Idioma preferido: Español
- Zona horaria: (por determinar)

## Metas y Objetivos
- (El asistente aprenderá las metas del usuario con el tiempo)

## Patrones Observados
- (El Curation Agent registrará patrones aquí)

## Preferencias de Comunicación
- Tono: Directo y conciso
- Formato: Texto, no listas largas
- Horarios activos: (por determinar)

## Contactos Frecuentes
- (Se registrarán automáticamente los contactos más mencionados)

## Notas
- Este perfil se actualiza automáticamente después de conversaciones importantes.
- El Curation Agent extrae hechos nuevos y los integra aquí.
"""
    }

    private var soulContent: String = ""
    private var livingProfileContent: String = ""
    private var isLoaded = false

    /**
     * Carga el SOUL.md desde assets al almacenamiento interno.
     * En la primera ejecución, copia desde assets. En ejecuciones
     * posteriores, lee desde almacenamiento interno (donde puede
     * haber sido modificado por el Curation Agent).
     */
    suspend fun loadSoul() {
        withContext(Dispatchers.IO) {
            try {
                // Verificar si hay una versión modificada en almacenamiento interno
                val internalFile = File(context.filesDir, SOUL_FILE)

                if (internalFile.exists()) {
                    soulContent = internalFile.readText()
                    Log.i(TAG, "SOUL cargado desde almacenamiento interno")
                } else {
                    // Primera ejecución: copiar desde assets
                    soulContent = context.assets.open("soul/$SOUL_FILE")
                        .bufferedReader()
                        .use { it.readText() }

                    // Guardar copia en almacenamiento interno
                    internalFile.writeText(soulContent)
                    Log.i(TAG, "SOUL copiado desde assets a almacenamiento interno")
                }

                // Cargar Living Profile si existe
                val profileFile = File(context.filesDir, PROFILE_FILE)
                if (profileFile.exists()) {
                    livingProfileContent = profileFile.readText()
                    Log.i(TAG, "Living Profile cargado (${livingProfileContent.length} chars)")
                } else {
                    livingProfileContent = DEFAULT_LIVING_PROFILE
                    profileFile.writeText(livingProfileContent)
                    Log.i(TAG, "Living Profile por defecto creado")
                }

                isLoaded = true
                Log.i(TAG, "Identidad cargada exitosamente")

            } catch (e: Exception) {
                Log.e(TAG, "Error cargando identidad", e)
                soulContent = COMPRESSED_SOUL
                livingProfileContent = DEFAULT_LIVING_PROFILE
                isLoaded = true
            }
        }
    }

    /**
     * Construye el System Prompt completo para el LLM.
     *
     * @param toolDescriptions Descripción de las herramientas disponibles
     * @param autonomyLevel Nivel de autonomía actual
     * @param hardwareSummary Resumen del estado del hardware (opcional)
     * @param compressed Si true, usa versión comprimida para ahorrar tokens
     * @return El system prompt completo
     */
    fun buildSystemPrompt(
        toolDescriptions: String = "",
        autonomyLevel: String = "BALANCEADO",
        hardwareSummary: String = "",
        compressed: Boolean = false
    ): String {
        if (!isLoaded) return COMPRESSED_SOUL

        if (compressed) {
            return COMPRESSED_SOUL
                .replace("{{AUTONOMY}}", autonomyLevel)
                .replace("{{TOOLS}}", toolDescriptions.ifBlank { "ninguna" })
        }

        return buildString {
            // Capa 1: SOUL.md (instrucciones maestras)
            append(soulContent)
            append("\n\n")

            // Capa 2: Living Profile (conocimiento del usuario)
            if (livingProfileContent.isNotBlank()) {
                append("## PERFIL DEL USUARIO\n\n")
                append(livingProfileContent)
                append("\n\n")
            }

            // Capa 3: Herramientas disponibles
            if (toolDescriptions.isNotBlank()) {
                append("## HERRAMIENTAS DISPONIBLES\n\n")
                append(toolDescriptions)
                append("\n\n")
            }

            // Capa 4: Estado del hardware
            if (hardwareSummary.isNotBlank()) {
                append("## ESTADO ACTUAL DEL DISPOSITIVO\n\n")
                append(hardwareSummary)
                append("\n\n")
            }

            // Directiva de autonomía activa
            append("## PERFIL DE AUTONOMÍA ACTIVO: $autonomyLevel\n\n")
            append(when (autonomyLevel) {
                "CAUTO" -> "Debes pedir confirmación ANTES de cada acción. Muestra qué vas a hacer y espera aprobación explícita."
                "BALANCEADO" -> "Puedes ejecutar lecturas automáticamente. Para acciones destructivas (enviar mensajes, borrar datos, abrir apps), pide confirmación."
                "FULL AUTO" -> "Ejecuta todas las acciones autónomamente. Notifica al usuario DESPUÉS de actuar. El usuario asume responsabilidad total."
                else -> "Perfil desconocido. Operar en modo CAUTO por seguridad."
            })
        }
    }

    /**
     * Actualiza el Living Profile con nueva información del usuario.
     * Llamado por el Curation Agent después de conversaciones importantes.
     */
    suspend fun updateLivingProfile(newProfile: String) {
        withContext(Dispatchers.IO) {
            livingProfileContent = newProfile
            val profileFile = File(context.filesDir, PROFILE_FILE)
            profileFile.writeText(newProfile)
            Log.i(TAG, "Living Profile actualizado (${newProfile.length} chars)")
        }
    }

    /**
     * Obtiene el contenido actual del Living Profile.
     */
    fun getLivingProfile(): String = livingProfileContent

    /**
     * Obtiene el nombre de la persona del asistente.
     */
    fun getPersonaName(): String {
        val prefs = context.getSharedPreferences("dayana_identity_prefs", Context.MODE_PRIVATE)
        return prefs.getString(KEY_ASSISTANT_NAME, PRIMARY_NAME) ?: PRIMARY_NAME
    }

    fun setPersonaName(name: String) {
        val prefs = context.getSharedPreferences("dayana_identity_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ASSISTANT_NAME, name).apply()
        Log.i(TAG, "Nombre del asistente cambiado a: $name")
    }

    /**
     * Obtiene el contenido del SOUL.md.
     */
    fun getSoulContent(): String = soulContent

    /**
     * Actualiza el SOUL.md (usado solo por el Curation Agent
     * con aprobación explícita del usuario).
     */
    suspend fun updateSoul(newSoul: String) {
        withContext(Dispatchers.IO) {
            soulContent = newSoul
            val soulFile = File(context.filesDir, SOUL_FILE)
            soulFile.writeText(newSoul)
            Log.i(TAG, "SOUL.md actualizado")
        }
    }

    /**
     * Formato de chat para modelos Instruct.
     * Genera el formato correcto según el modelo activo.
     */
    fun formatChatPrompt(
        systemPrompt: String,
        conversationHistory: List<Pair<String, String>>,
        currentMessage: String
    ): String {
        return buildString {
            // Formato ChatML (usado por Llama 3.2, Qwen, etc.)
            append("<|im_start|>system\n")
            append(systemPrompt)
            append("<|im_end|>\n")

            for ((role, content) in conversationHistory) {
                append("<|im_start|>$role\n")
                append(content)
                append("<|im_end|>\n")
            }

            append("<|im_start|>user\n")
            append(currentMessage)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

}
