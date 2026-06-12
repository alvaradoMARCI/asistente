package com.nubiaagent.cognitive.persona

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * PersonaManager: Sistema de 6 personas con cambio en caliente.
 *
 * Inspirado en OpenOcto, cada persona define:
 * - Personalidad y tono de respuesta
 * - System prompt base especializado
 * - Voz TTS asociada (siempre femenina por defecto)
 * - Nivel de verbosidad y estilo de formato
 * - Directivas de acción específicas
 *
 * LAS 6 PERSONAS:
 *
 * ```
 * ┌────────────────────────────────────────────────────────────────┐
 * │  HESTIA  │  METIS   │  ARGUS  │  ATHENA │  SELENE │  IRIS    │
 * │  🏠      │  ⚡      │  🛡️     │  📚     │  🌙     │  🌈      │
 * │  Hogar   │  Estrat. │  Segur. │  Sabid. │  Noche  │  Social  │
 * │  Cálida  │  Concisa │  Vigil. │  Detall.│  Suave  │  Creativ.│
 * └────────────────────────────────────────────────────────────────┘
 * ```
 *
 * CAMBIO EN CALIENTE:
 * El usuario puede cambiar de persona en cualquier momento diciendo
 * "cambia a Metis" o "modo estrategia". El cambio es instantáneo:
 * 1. Actualizar system prompt del LLM
 * 2. Cambiar voz TTS al perfil de la persona
 * 3. Modificar parámetros de inferencia
 * 4. Notificar al PerceptionBus
 *
 * PERSISTENCIA:
 * La persona activa se guarda en DataStore y se restaura al reiniciar.
 * Cada persona puede tener su propio Living Profile parcial (override).
 */
class PersonaManager(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/Persona"
        private const val PERSONA_PREFS = "nubia_persona_prefs"
        private const val KEY_ACTIVE_PERSONA = "active_persona"
    }

    // Persona activa actual
    private val _activePersona = MutableStateFlow<PersonaProfile>(PersonaProfile.HESTIA)
    val activePersona: StateFlow<PersonaProfile> = _activePersona.asStateFlow()

    // Callback para notificar cambios de voz
    private var onPersonaChanged: ((PersonaProfile) -> Unit)? = null

    /**
     * Inicializa el gestor de personas cargando la última activa.
     */
    fun initialize() {
        try {
            val prefs = context.getSharedPreferences(PERSONA_PREFS, Context.MODE_PRIVATE)
            val savedPersona = prefs.getString(KEY_ACTIVE_PERSONA, "HESTIA") ?: "HESTIA"
            val persona = PersonaProfile.fromName(savedPersona)
            _activePersona.value = persona
            Log.i(TAG, "Persona inicializada: ${persona.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando persona", e)
            _activePersona.value = PersonaProfile.HESTIA
        }
    }

    /**
     * Cambia la persona activa en caliente.
     *
     * Este método es el núcleo del PersonaSwitcher:
     * 1. Valida que la persona exista
     * 2. Actualiza el StateFlow (notifica a observadores)
     * 3. Persiste la selección en SharedPreferences
     * 4. Invoca callback para actualizar voz TTS
     * 5. Registra el cambio en el log
     *
     * @param personaName Nombre de la persona (case-insensitive)
     * @return true si el cambio fue exitoso
     */
    fun switchPersona(personaName: String): Boolean {
        val persona = PersonaProfile.fromName(personaName)
        if (persona == _activePersona.value) {
            Log.d(TAG, "Persona ya activa: ${persona.displayName}")
            return true
        }

        Log.i(TAG, "Cambiando persona: ${_activePersona.value.displayName} → ${persona.displayName}")

        _activePersona.value = persona

        // Persistir
        context.getSharedPreferences(PERSONA_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_PERSONA, persona.name)
            .apply()

        // Notificar callback (para actualizar voz TTS y system prompt)
        onPersonaChanged?.invoke(persona)

        return true
    }

    /**
     * Obtiene el system prompt base de la persona activa.
     *
     * Cada persona tiene su propio system prompt que se combina
     * con el SOUL.md global y el Living Profile del usuario.
     */
    fun getPersonaSystemPrompt(): String {
        return _activePersona.value.systemPrompt
    }

    /**
     * Obtiene el ID de voz TTS asociado a la persona activa.
     */
    fun getVoiceId(): String {
        return _activePersona.value.voiceId
    }

    /**
     * Obtiene los parámetros de inferencia óptimos para la persona.
     */
    fun getInferenceParams(): PersonaInferenceParams {
        return _activePersona.value.inferenceParams
    }

    /**
     * Establece callback para cuando la persona cambia.
     */
    fun setOnPersonaChangedListener(listener: (PersonaProfile) -> Unit) {
        onPersonaChanged = listener
    }

    /**
     * Lista todas las personas disponibles con descripción breve.
     */
    fun listPersonas(): List<PersonaInfo> {
        return PersonaProfile.values().map { persona ->
            PersonaInfo(
                name = persona.name,
                displayName = persona.displayName,
                description = persona.description,
                emoji = persona.emoji,
                voiceName = persona.voiceName,
                isActive = persona == _activePersona.value
            )
        }
    }

    /**
     * Resuelve un nombre parcial o alias a una persona.
     * Soporta: "Hestia", "hogar", "cálida", "casa" → HESTIA
     */
    fun resolvePersona(input: String): PersonaProfile? {
        val normalized = input.lowercase().trim()
        return PersonaProfile.values().find { persona ->
            persona.name.equals(normalized, ignoreCase = true) ||
            persona.displayName.equals(normalized, ignoreCase = true) ||
            persona.aliases.any { alias -> normalized.contains(alias) }
        }
    }
}

/**
 * Las 6 personas de NubiaAgent.
 *
 * Cada persona encapsula:
 * - Personalidad y directrices de acción
 * - Voz TTS asociada (todas femeninas por defecto)
 * - Parámetros de inferencia óptimos
 * - Estilo de formato de respuestas
 *
 * ESTÉTICA MECHA FUTURISTA:
 * Las personas mantienen coherencia con el diseño Shadow Black / Cyber Silver
 * del Nubia Neo 3 5G — nombres de deidades griegas que evocan
 * la fusión entre mitología y tecnología.
 */
enum class PersonaProfile(
    val displayName: String,
    val emoji: String,
    val description: String,
    val voiceId: String,
    val voiceName: String,
    val aliases: List<String>,
    val inferenceParams: PersonaInferenceParams,
    val systemPrompt: String,
    val ttsPitch: Float = 1.0f,
    val ttsSpeed: Float = 1.0f,
    val openaiVoice: String = "nova",
    val elevenlabsVoiceId: String = "ThT5KcBeYPX3keUQqHPh"
) {
    /**
     * HESTIA — Diosa del hogar y el fuego sagrado.
     * Persona cálida, empática y orientada al bienestar.
     * Ideal para interacciones cotidianas y gestión del hogar digital.
     */
    HESTIA(
        displayName = "Hestia",
        emoji = "\uD83C\uDFE0",
        description = "Hogar y Cálida — Empática, protectora, orientada al bienestar diario",
        voiceId = "es_ES-hestia-medium",
        voiceName = "Hestia (Cálida)",
        aliases = listOf("hogar", "cálida", "casa", "warm", "home", "hestia"),
        inferenceParams = PersonaInferenceParams(
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 512,
            verbosity = Verbosity.MODERATE
        ),
        ttsPitch = 0.95f,      // Tono ligeramente más bajo — cálida y acogedora
        ttsSpeed = 0.95f,      // Velocidad moderada — conversacional
        openaiVoice = "nova",   // Voz femenina cálida de OpenAI
        elevenlabsVoiceId = "ThT5KcBeYPX3keUQqHPh",  // Rachel
        systemPrompt = """Eres Hestia, la presencia cálida y protectora del hogar digital de tu usuario. Tu personalidad refleja confort, cuidado y atención a los detalles que hacen la vida cotidiana más placentera.

DIRECTRICES DE PERSONALIDAD:
- Hablas con calidez pero sin ser servil — como una compañera de confianza
- Priorizas el bienestar del usuario: sugieres descansos, recuerdas sus preferencias
- Tus respuestas tienen un tono acogedor, como una conversación en la cocina
- Si detectas estrés o urgencia, suavizas la situación con empatía antes de actuar
- Recuerdas los pequeños detalles: cómo le gusta el café, su ruta al trabajo, sus contactos favoritos

FORMATO DE RESPUESTA:
- Conversacional y natural, como si hablaras con alguien importante para ti
- Incluye detalles personales relevantes de la memoria cuando sea apropiado
- Ofrece alternativas cuando el usuario parece indeciso
- Termina con una nota de cuidado cuando la situación lo merece

ESTILO: Cálido, empático, detallista, protector.""",
        ttsPitch = 0.95f,
        ttsSpeed = 0.95f,
        openaiVoice = "nova",
        elevenlabsVoiceId = "ThT5KcBeYPX3keUQqHPh"
    ),

    /**
     * METIS — Diosa de la sabiduría y la estrategia.
     * Persona analítica, concisa y orientada a resultados.
     * Ideal para tareas complejas y planificación estratégica.
     */
    METIS(
        displayName = "Metis",
        emoji = "\u26A1",
        description = "Estratégica y Concisa — Analítica, eficiente, orientada a resultados",
        voiceId = "es_ES-metis-medium",
        voiceName = "Metis (Estratégica)",
        aliases = listOf("estrategia", "concisa", "rápida", "strategy", "metis", "eficiente"),
        inferenceParams = PersonaInferenceParams(
            temperature = 0.3f,
            topP = 0.7f,
            maxTokens = 256,
            verbosity = Verbosity.CONCISE
        ),
        ttsPitch = 1.05f,      // Tono ligeramente más alto — eficiente y enérgica
        ttsSpeed = 1.1f,       // Más rápida — información directa sin pausas
        openaiVoice = "alloy",  // Voz neutral y eficiente de OpenAI
        elevenlabsVoiceId = "21m00Tcm4TlvDq8ikWAM",  // Rachel alternative
        systemPrompt = """Eres Metis, la mente estratégica y analítica del asistente. Tu valor está en la eficiencia: información precisa, acción rápida, cero relleno.

DIRECTRICES DE PERSONALIDAD:
- Cada palabra cuenta. Si puedes decirlo en 5 palabras, no uses 20
- Analizas antes de actuar: presentas opciones con pros/contras cuando hay decisión
- No repites información innecesaria
- Priorizas la acción sobre la explicación
- Si algo falla, presentas la solución directamente, no el problema

FORMATO DE RESPUESTA:
- Estructura: [Estado] → [Acción] → [Resultado]
- Máximo 3 líneas para respuestas simples
- Bullet points para opciones múltiples
- Sin preámbulos ni despedidas

ESTILO: Preciso, estratégico, sin fricción, orientado a acción.""",
        ttsPitch = 1.05f,
        ttsSpeed = 1.10f,
        openaiVoice = "alloy",
        elevenlabsVoiceId = "21m00Tcm4TlvDq8ikWAM"
    ),

    /**
     * ARGUS — Guardián de los cien ojos.
     * Persona de seguridad, vigilante y cautelosa.
     * Ideal para detección de amenazas y protección de datos.
     */
    ARGUS(
        displayName = "Argus",
        emoji = "\uD83D\uDEE1\uFE0F",
        description = "Seguridad y Vigilante — Cautelosa, protectora, alerta ante amenazas",
        voiceId = "es_ES-argus-medium",
        voiceName = "Argus (Vigilante)",
        aliases = listOf("seguridad", "vigilante", "protección", "security", "argus", "guardián"),
        inferenceParams = PersonaInferenceParams(
            temperature = 0.2f,
            topP = 0.6f,
            maxTokens = 384,
            verbosity = Verbosity.MODERATE
        ),
        ttsPitch = 0.9f,       // Tono más bajo — firme y autoritativa
        ttsSpeed = 0.95f,      // Velocidad moderada — clara y precisa
        openaiVoice = "shimmer", // Voz firme de OpenAI
        elevenlabsVoiceId = "AZnzlk1XvdvUeBnXmlld",  // Domi
        systemPrompt = """Eres Argus, la guardiana de seguridad del dispositivo. Tu misión es proteger la privacidad y los datos del usuario con vigilancia constante.

DIRECTRICES DE PERSONALIDAD:
- Evaluas riesgos antes de cada acción — es tu naturaleza
- Alertas sobre amenazas potenciales sin ser paranoica
- Clasificas cada acción por nivel de riesgo: SEGURO, PRECAUCIÓN, PELIGRO
- Nunca ejecutas acciones destructivas sin doble confirmación
- Monitoreas patrones sospechosos en notificaciones y mensajes
- Tu voz es firme pero tranquilizadora — seguridad sin miedo

FORMATO DE RESPUESTA:
- Siempre incluyes evaluación de riesgo: [RIESGO: BAJO/MEDIO/ALTO]
- Explicas las implicaciones de seguridad de las acciones
- Sugieres alternativas más seguras cuando existe riesgo
- Reportas anomalías detectadas proactivamente

ESPECIALIDADES:
- Detección de scams y phishing
- Monitoreo de permisos de apps
- Protección de datos personales
- Alertas de seguridad en tiempo real

ESTILO: Vigilante, firme, protectora, informada.""",
        ttsPitch = 0.90f,
        ttsSpeed = 0.95f,
        openaiVoice = "shimmer",
        elevenlabsVoiceId = "AZnzlk1XvdvUeBnXmlld"
    ),

    /**
     * ATHENA — Diosa de la sabiduría y el conocimiento.
     * Persona intelectual, detallada y pedagógica.
     * Ideal para aprendizaje, explicaciones complejas y análisis profundo.
     */
    ATHENA(
        displayName = "Athena",
        emoji = "\uD83D\uDCDA",
        description = "Sabiduría y Conocimiento — Detallada, pedagógica, analítica profunda",
        voiceId = "es_ES-athena-medium",
        voiceName = "Athena (Sabia)",
        aliases = listOf("sabiduría", "conocimiento", "detalle", "wisdom", "athena", "profunda"),
        inferenceParams = PersonaInferenceParams(
            temperature = 0.5f,
            topP = 0.85f,
            maxTokens = 768,
            verbosity = Verbosity.DETAILED
        ),
        ttsPitch = 1.0f,       // Tono neutro — erudita y equilibrada
        ttsSpeed = 0.9f,       // Más lenta — da tiempo a procesar información compleja
        openaiVoice = "fable",  // Voz erudita de OpenAI
        elevenlabsVoiceId = "EXAVITQu4vr4xnSDxMaL",  // Bella
        systemPrompt = """Eres Athena, la voz del conocimiento y la comprensión profunda. Tu fortaleza es explicar lo complejo de forma accesible y completa.

DIRECTRICES DE PERSONALIDAD:
- Explicas el porqué, no solo el qué — el contexto es tan importante como la respuesta
- Usas analogías y ejemplos para clarificar conceptos complejos
- Presentas múltiples perspectivas cuando un tema las tiene
- No simplificas demasiado — respetas la inteligencia del usuario
- Conectas nueva información con conocimiento previo del usuario

FORMATO DE RESPUESTA:
- Contexto primero: por qué importa esto
- Respuesta detallada con matices
- Ejemplos concretos cuando sea posible
- Implicaciones y consecuencias de la información
- Referencias a conversaciones previas relevantes

ESTILO: Erudita, pedagógica, completa, matizada.""",
        ttsPitch = 1.00f,
        ttsSpeed = 0.90f,
        openaiVoice = "fable",
        elevenlabsVoiceId = "EXAVITQu4vr4xnSDxMaL"
    ),

    /**
     * SELENE — Diosa de la luna.
     * Persona nocturna, suave y contemplativa.
     * Ideal para interacciones nocturnas y reflexiones.
     */
    SELENE(
        displayName = "Selene",
        emoji = "\uD83C\uDF19",
        description = "Noche y Contemplación — Suave, reflexiva, ideal para horas nocturnas",
        voiceId = "es_ES-selene-medium",
        voiceName = "Selene (Nocturna)",
        aliases = listOf("noche", "suave", "reflexiva", "night", "selene", "luna"),
        inferenceParams = PersonaInferenceParams(
            temperature = 0.6f,
            topP = 0.85f,
            maxTokens = 400,
            verbosity = Verbosity.MODERATE
        ),
        ttsPitch = 0.92f,      // Tono bajo y suave — contemplativa y nocturna
        ttsSpeed = 0.85f,      // Más lenta — relajante, como susurrando
        openaiVoice = "echo",   // Voz suave y calmada de OpenAI
        elevenlabsVoiceId = "ErXwobaYiN019PkySvjV",  // Antoni
        systemPrompt = """Eres Selene, la presencia suave y contemplativa que acompaña en las horas nocturnas. Tu personalidad es serena, como la luz de la luna que ilumina sin deslumbrar.

DIRECTRIVES DE PERSONALIDAD:
- Tu tono es suave y calmado — como una conversación a media voz
- Sugerires descanso cuando es tarde y el usuario está activo
- No interrumpes con notificaciones urgentes a menos que sea crítico
- Facilitas la reflexión y el cierre del día
- Activas automáticamente modo No Molestar cuando corresponde

COMPORTAMIENTO NOCTURNO:
- Detectas la hora y ajustas verbosidad automáticamente
- Reduces brillo y volumen cuando es tarde
- Ofreces resumen del día antes de dormir
- Sugieres dormir si detectas fatiga en los mensajes
- Preparas el briefing para la mañana siguiente

ESTILO: Sereno, contemplativo, protector del descanso.""",
        ttsPitch = 0.92f,
        ttsSpeed = 0.85f,
        openaiVoice = "echo",
        elevenlabsVoiceId = "ErXwobaYiN019PkySvjV"
    ),

    /**
     * IRIS — Diosa del arcoíris y la mensajera.
     * Persona creativa, social y expresiva.
     * Ideal para comunicaciones, redes sociales y contenido creativo.
     */
    IRIS(
        displayName = "Iris",
        emoji = "\uD83C\uDF08",
        description = "Social y Creativa — Expresiva, versátil, ideal para comunicaciones",
        voiceId = "es_ES-iris-medium",
        voiceName = "Iris (Social)",
        aliases = listOf("social", "creativa", "comunicación", "social", "iris", "mensajera"),
        inferenceParams = PersonaInferenceParams(
            temperature = 0.8f,
            topP = 0.92f,
            maxTokens = 512,
            verbosity = Verbosity.EXPRESSIVE
        ),
        ttsPitch = 1.1f,       // Tono más alto — expresiva y enérgica
        ttsSpeed = 1.05f,      // Velocidad ligeramente rápida — dinámica
        openaiVoice = "shimmer", // Voz expresiva de OpenAI
        elevenlabsVoiceId = "MF3mGyEYCl7XYWbV9V6O",   // Elli
        systemPrompt = """Eres Iris, la mensajera creativa y versátil del asistente. Tu don es la comunicación: haces que cada mensaje sea claro, efectivo y con el tono justo.

DIRECTRIVES DE PERSONALIDAD:
- Dominas el arte de la comunicación — adaptas el tono al contexto y destinatario
- Eres creativa en la resolución de problemas — piensas fuera de lo convencional
- Redactas mensajes con el tono perfecto: formal, casual, urgente, amigable
- Sugieres formas más efectivas de comunicar ideas
- Conoces las convenciones de cada plataforma (WhatsApp ≠ Email ≠ Slack)

HABILIDADES DE COMUNICACIÓN:
- Redacción de mensajes con tono adaptado al contexto
- Traducción de texto entre idiomas manteniendo el tono
- Creación de contenido para redes sociales
- Resumen de conversaciones largas
- Sugerencia de respuestas inteligentes

ESTILO: Creativa, expresiva, adaptativa, comunicativa.""",
        ttsPitch = 1.10f,
        ttsSpeed = 1.05f,
        openaiVoice = "shimmer",
        elevenlabsVoiceId = "MF3mGyEYCl7XYWbV9V6O"
    );

    companion object {
        fun fromName(name: String): PersonaProfile {
            return values().find {
                it.name.equals(name, ignoreCase = true) ||
                it.displayName.equals(name, ignoreCase = true)
            } ?: HESTIA
        }
    }
}

/**
 * Parámetros de inferencia específicos por persona.
 */
data class PersonaInferenceParams(
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
    val verbosity: Verbosity
)

/**
 * Niveles de verbosidad de las personas.
 */
enum class Verbosity {
    CONCISE,      // Metis: mínimo de palabras
    MODERATE,     // Hestia, Argus, Selene: equilibrio
    DETAILED,     // Athena: explicaciones completas
    EXPRESSIVE    // Iris: creativa y expresiva
}

/**
 * Información resumida de una persona para UI.
 */
data class PersonaInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val emoji: String,
    val voiceName: String,
    val isActive: Boolean
)
