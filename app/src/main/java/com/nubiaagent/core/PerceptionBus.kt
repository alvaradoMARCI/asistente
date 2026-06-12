package com.nubiaagent.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PerceptionEvent: Representa cualquier evento capturado por la Capa de Percepción.
 *
 * Tipos de eventos que fluyen a través del bus:
 * - WAKE_WORD_DETECTED: El usuario dijo "Hey Nubia"
 * - VOICE_COMMAND: Comando de voz transcrito
 * - SCREEN_CHANGED: La pantalla del dispositivo cambió
 * - UI_ELEMENTS_READY: Árbol de accesibilidad filtrado listo
 * - SCREENSHOT_READY: Captura de pantalla lista para análisis visual
 * - NOTIFICATION_RECEIVED: Notificación interceptada
 * - HARDWARE_STATE_UPDATE: Actualización de telemetría del dispositivo
 */
sealed class PerceptionEvent {
    abstract val timestamp: Long

    data class WakeWordDetected(
        override val timestamp: Long = System.currentTimeMillis(),
        val confidence: Float,
        val audioSnippet: ByteArray? = null
    ) : PerceptionEvent()

    data class VoiceCommand(
        override val timestamp: Long = System.currentTimeMillis(),
        val transcript: String,
        val confidence: Float,
        val isFinal: Boolean
    ) : PerceptionEvent()

    data class ScreenChanged(
        override val timestamp: Long = System.currentTimeMillis(),
        val packageName: String,
        val activityName: String
    ) : PerceptionEvent()

    data class UiElementsReady(
        override val timestamp: Long = System.currentTimeMillis(),
        val elements: List<UiElement>,
        val packageName: String,
        val rawXmlSize: Int
    ) : PerceptionEvent()

    data class ScreenshotReady(
        override val timestamp: Long = System.currentTimeMillis(),
        val imagePath: String,
        val width: Int,
        val height: Int
    ) : PerceptionEvent()

    data class NotificationReceived(
        override val timestamp: Long = System.currentTimeMillis(),
        val packageName: String,
        val title: String,
        val text: String,
        val category: NotificationCategory,
        val priority: Int,
        val isUrgent: Boolean
    ) : PerceptionEvent()

    data class HardwareStateUpdate(
        override val timestamp: Long = System.currentTimeMillis(),
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isBypassCharging: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val currentActivity: UserActivity,
        val stepCount: Long
    ) : PerceptionEvent()

    /**
     * Respuesta generada por el agente (no es un comando del usuario).
     * Se emite después de que el AgentLoop procesa un VoiceCommand.
     */
    data class AgentResponse(
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val isSuccessful: Boolean
    ) : PerceptionEvent()

    /**
     * Trazabilidad del razonamiento del agente.
     * Se emite en puntos clave del AgentLoop (PENSAR, ACTUAR, OBSERVAR)
     * para que los componentes UI puedan mostrar el proceso de pensamiento.
     */
    data class AgentThought(
        override val timestamp: Long = System.currentTimeMillis(),
        val logs: String
    ) : PerceptionEvent()
}

/**
 * Representación compacta de un elemento interactivo de la UI.
 * Solo se extraen los campos relevantes para el LLM.
 */
data class UiElement(
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: String,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean?,
    val viewType: UiViewType
)

enum class UiViewType {
    BUTTON, TEXT_FIELD, LIST, IMAGE, SWITCH, CHECKBOX,
    TAB, DIALOG, TOAST, CONTAINER, TEXT, UNKNOWN
}

enum class NotificationCategory {
    MESSAGE_URGENT,    // WhatsApp/Telegram/SMS con palabra clave urgente
    MESSAGE_NORMAL,    // Mensaje de mensajería sin urgencia
    SOCIAL,            // Redes sociales
    EMAIL,             // Correo electrónico
    SYSTEM,            // Notificación del sistema
    REMINDER,          // Recordatorio/alarma
    UNKNOWN            // No clasificado
}

enum class UserActivity {
    STILL,           // Inactivo
    WALKING,         // Caminando
    RUNNING,         // Corriendo
    DRIVING,         // Conduciendo
    CYCLING,         // En bicicleta
    UNKNOWN          // No determinado
}

/**
 * PerceptionBus: Bus de eventos central para la Capa de Percepción.
 *
 * Todos los módulos de percepción publican eventos aquí.
 * La Capa Cognitiva (Agent Loop) se suscribe para recibir entradas.
 *
 * Diseñado con SharedFlow para:
 * - Múltiples suscriptores (cognición, logging, UI)
 * - Backpressure controlada
 * - Sin retención de eventos antiguos (replay=0)
 */
object PerceptionBus {

    private val _events = MutableSharedFlow<PerceptionEvent>(
        extraBufferCapacity = 64,
        replay = 0
    )

    val events: SharedFlow<PerceptionEvent> = _events.asSharedFlow()

    /**
     * Publica un evento en el bus.
     * Llamado desde cada módulo de percepción.
     */
    suspend fun emit(event: PerceptionEvent) {
        _events.emit(event)
    }

    /**
     * Publica un evento sin suspender (para callbacks que no son coroutine-aware).
     * Usa tryEmit que descarta si el buffer está lleno.
     */
    fun tryEmit(event: PerceptionEvent): Boolean {
        return _events.tryEmit(event)
    }
}
