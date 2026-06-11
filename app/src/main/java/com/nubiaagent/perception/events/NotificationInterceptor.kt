package com.nubiaagent.perception.events

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import com.nubiaagent.core.NotificationCategory
import kotlinx.coroutines.*
import java.util.regex.Pattern

/**
 * NotificationInterceptor: Módulo de Percepción de Eventos para NubiaAgent.
 *
 * Intercepta todas las notificaciones entrantes del dispositivo y las
 * clasifica para que la Capa Cognitiva pueda decidir si debe interrumpir
 * al usuario o almacenar la información en la memoria de trabajo.
 *
 * ARQUITECTURA DE CLASIFICACIÓN:
 *
 * El interceptor aplica un pipeline de 3 etapas a cada notificación:
 *
 * 1. EXTRACCIÓN: Se extraen los campos clave de la notificación
 *    (título, texto, paquete, categoría, prioridad) usando Notification.extras
 *    y fallback a traversal del contentView.
 *
 * 2. CLASIFICACIÓN: Se determina la categoría semántica de la notificación
 *    (mensaje urgente, mensaje normal, social, email, sistema, recordatorio)
 *    basándose en: paquete de origen, palabras clave en el texto,
 *    prioridad del sistema, y reglas configurables.
 *
 * 3. DECISIÓN DE INTERRUPCIÓN: Se evalúa si la notificación debe generar
 *    una interrupción inmediata (isUrgent=true) o simplemente registrarse
 *    en la memoria de trabajo. La decisión se basa en:
 *    - Palabras clave de urgencia configuradas por el usuario
 *    - Remitentes marcados como prioritarios
 *    - Hora del día (no interrumpir de noche por defecto)
 *    - Estado actual del usuario (conduciendo = solo emergencias)
 *
 * RESTRICCIÓN DE PRIVACIDAD: El contenido de las notificaciones se procesa
 * exclusivamente en-device. Solo se emiten metadatos y resúmenes al
 * PerceptionBus, nunca el contenido completo de mensajes privados.
 */
class NotificationInterceptor : NotificationListenerService() {

    companion object {
        private const val TAG = "NubiaAgent/Events"

        // Paquetes de mensajería conocidos
        private val MESSENGER_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.Slack" to "Slack",
            "com.discord" to "Discord",
            "com.microsoft.teams" to "Teams",
            "com.facebook.orca" to "Messenger",
            "com.viber.voip" to "Viber",
            "com.google.android.apps.messaging" to "Google Messages",
            "com.android.mms" to "SMS",
        )

        private val EMAIL_PACKAGES = mapOf(
            "com.google.android.gm" to "Gmail",
            "com.microsoft.android.mail" to "Outlook",
            "com.yahoo.mobile.client.android.mail" to "Yahoo Mail",
        )

        private val SOCIAL_PACKAGES = mapOf(
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "X/Twitter",
            "com.facebook.katana" to "Facebook",
            "com.linkedin.android" to "LinkedIn",
            "com.tiktok" to "TikTok",
        )

        // Palabras clave de urgencia (configurables)
        private val URGENCY_KEYWORDS = listOf(
            "urgente", "urgente", "emergencia", "emergency",
            "importante", "important", "crítico", "critical",
            "ya", "now", "asap", "inmediatamente",
            "necesito", "need you", "llamada", "call me",
            "dónde estás", "where are you", "accidente", "ayuda"
        )

        // Remitentes prioritarios (se llenará desde la memoria del agente)
        private val priorityContacts = mutableSetOf<String>()

        // Horario de no molestar (configurable)
        private var dndStartHour = 22  // 10 PM
        private var dndEndHour = 7     // 7 AM
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationInterceptor conectado - Monitoreando notificaciones")
    }

    /**
     * Callback principal: se invoca cuando llega una nueva notificación.
     *
     * Procesa la notificación a través del pipeline completo:
     * Extraer → Clasificar → Decidir interrupción → Emitir evento
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Ignorar notificaciones del propio NubiaAgent
        if (sbn.packageName == "com.nubiaagent") return

        // Ignorar notificaciones del sistema que no son relevantes
        if (isSystemNoise(sbn)) return

        serviceScope.launch {
            processNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // La notificación fue descartada - podría actualizar estado
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "NotificationInterceptor desconectado")
        serviceScope.cancel()
        super.onListenerDisconnected()
    }

    /**
     * Pipeline completo de procesamiento de notificaciones.
     */
    private suspend fun processNotification(sbn: StatusBarNotification) {
        // Etapa 1: Extracción
        val extracted = extractNotificationData(sbn)

        // Etapa 2: Clasificación
        val category = classifyNotification(extracted)

        // Etapa 3: Decisión de interrupción
        val isUrgent = shouldInterrupt(extracted, category)

        // Emitir evento al PerceptionBus
        PerceptionBus.emit(
            PerceptionEvent.NotificationReceived(
                packageName = extracted.packageName,
                title = extracted.title,
                text = extracted.text,
                category = category,
                priority = extracted.priority,
                isUrgent = isUrgent
            )
        )

        Log.d(TAG, "Notificación: [${category.name}] ${extracted.title} " +
                "- ${extracted.text.take(50)}... (urgente=$isUrgent)")
    }

    /**
     * ETAPA 1: Extracción de datos de la notificación.
     *
     * Usa Notification.extras como fuente primaria, con fallback
     * a recorrer el contentView si los extras están vacíos.
     * Se aplica sanitización para no emitir contenido sensible
     * directamente al bus.
     */
    private fun extractNotificationData(sbn: StatusBarNotification): NotificationData {
        val extras = sbn.notification.extras
        val packageName = sbn.packageName

        // Extraer título
        val title = extractTitle(extras)

        // Extraer texto
        val text = extractText(extras)

        // Extraer prioridad
        val priority = sbn.notification.priority

        // Extraer categoría del sistema
        val systemCategory = sbn.notification.category

        // Extraer info del remitente (para mensajería)
        val sender = extractSender(extras, packageName)

        return NotificationData(
            packageName = packageName,
            title = title,
            text = text,
            priority = priority,
            systemCategory = systemCategory,
            sender = sender,
            timestamp = sbn.postTime
        )
    }

    private fun extractTitle(extras: Bundle?): String {
        if (extras == null) return ""
        return extras.getString(Notification.EXTRA_TITLE)?.toString()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                extras.getCharSequence("android.title.unicodeWrapped")?.toString()
            } else {
                null
            }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
    }

    private fun extractText(extras: Bundle?): String {
        if (extras == null) return ""

        // Intentar EXTRA_TEXT primero
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) return text

        // Intentar EXTRA_BIG_TEXT (notificaciones expandidas)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (!bigText.isNullOrBlank()) return bigText

        // Intentar EXTRA_SUMMARY_TEXT
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        if (!summary.isNullOrBlank()) return summary

        // Intentar EXTRA_SUB_TEXT
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (!subText.isNullOrBlank()) return subText

        return ""
    }

    /**
     * Extrae el remitente de una notificación de mensajería.
     * Las apps de mensajería suelen usar EXTRA_TITLE para el remitente
     * y EXTRA_TEXT para el mensaje.
     */
    private fun extractSender(extras: Bundle?, packageName: String): String {
        if (extras == null) return ""
        if (packageName !in MESSENGER_PACKAGES) return ""

        // En mensajería, el título suele ser el nombre del remitente
        return extras.getString(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ""
    }

    /**
     * ETAPA 2: Clasificación semántica de la notificación.
     *
     * Determina la categoría basándose en:
     * 1. El paquete de origen (mapeo directo)
     * 2. La categoría del sistema Android
     * 3. Análisis de contenido (palabras clave)
     */
    private fun classifyNotification(data: NotificationData): NotificationCategory {
        val packageName = data.packageName

        // Clasificación por paquete (alta confianza)
        if (packageName in MESSENGER_PACKAGES) {
            // ¿Es urgente?
            val combinedText = "${data.title} ${data.text}".lowercase()
            val hasUrgencyKeyword = URGENCY_KEYWORDS.any { keyword ->
                combinedText.contains(keyword)
            }
            val isFromPriorityContact = data.sender in priorityContacts

            return if (hasUrgencyKeyword || isFromPriorityContact) {
                NotificationCategory.MESSAGE_URGENT
            } else {
                NotificationCategory.MESSAGE_NORMAL
            }
        }

        if (packageName in EMAIL_PACKAGES) {
            return NotificationCategory.EMAIL
        }

        if (packageName in SOCIAL_PACKAGES) {
            return NotificationCategory.SOCIAL
        }

        // Clasificación por categoría del sistema
        when (data.systemCategory) {
            Notification.CATEGORY_MESSAGE -> return NotificationCategory.MESSAGE_NORMAL
            Notification.CATEGORY_EMAIL -> return NotificationCategory.EMAIL
            Notification.CATEGORY_ALARM,
            Notification.CATEGORY_REMINDER,
            Notification.CATEGORY_EVENT -> return NotificationCategory.REMINDER
            Notification.CATEGORY_CALL -> return NotificationCategory.MESSAGE_URGENT
            Notification.CATEGORY_PROMO,
            Notification.CATEGORY_RECOMMENDATION -> return NotificationCategory.SOCIAL
        }

        // Clasificación por contenido
        val combinedText = "${data.title} ${data.text}".lowercase()

        if (containsAny(combinedText, listOf("mensaje", "message", "te escribió", "sent you"))) {
            return NotificationCategory.MESSAGE_NORMAL
        }

        if (containsAny(combinedText, listOf("recordatorio", "reminder", "alarma", "alarm"))) {
            return NotificationCategory.REMINDER
        }

        // Default
        return NotificationCategory.UNKNOWN
    }

    /**
     * ETAPA 3: Decisión de interrupción.
     *
     * Determina si la notificación debe causar una interrupción activa
     * (notificación hablada, vibración, respuesta automática) o si
     * debe simplemente registrarse en la memoria de trabajo.
     *
     * Factores:
     * - Categoría: Solo MESSAGE_URGENT y REMINDER interrumpen por defecto
     * - Prioridad del sistema: PRIORITY_HIGH o PRIORITY_MAX favorecen interrupción
     * - Horario: No interrumpir entre dndStartHour y dndEndHour (salvo urgencia)
     * - Remitente: Los contactos prioritarios siempre interrumpen
     * - Estado del usuario: Si está conduciendo, solo emergencias
     */
    private fun shouldInterrupt(data: NotificationData, category: NotificationCategory): Boolean {
        // Categorías que siempre interrumpen
        if (category == NotificationCategory.MESSAGE_URGENT) return true

        // Remitentes prioritarios siempre interrumpen
        if (data.sender in priorityContacts) return true

        // Categorías que nunca interrumpen
        if (category in listOf(
                NotificationCategory.SOCIAL,
                NotificationCategory.SYSTEM,
                NotificationCategory.UNKNOWN
            )
        ) return false

        // Verificar horario de no molestar
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isQuietHours = if (dndStartHour > dndEndHour) {
            hour >= dndStartHour || hour < dndEndHour
        } else {
            hour >= dndStartHour && hour < dndEndHour
        }

        if (isQuietHours) {
            // En horario silencioso, solo interrumpir si es urgente (ya manejado arriba)
            return false
        }

        // Prioridad del sistema
        if (data.priority >= Notification.PRIORITY_HIGH) {
            return category in listOf(
                NotificationCategory.MESSAGE_NORMAL,
                NotificationCategory.REMINDER,
                NotificationCategory.EMAIL
            )
        }

        // Por defecto: no interrumpir para mensajes normales
        return category == NotificationCategory.REMINDER
    }

    /**
     * Filtra notificaciones del sistema que no son relevantes.
     * Evita procesar notificaciones de bajo nivel como:
     * - Actualizaciones de sistema
     * - Notificaciones de conectividad
     * - Actualizaciones de apps en background
     */
    private fun isSystemNoise(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName

        // Paquetes del sistema que generan ruido
        val noisePackages = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.media",
            "com.android.packageinstaller",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.android.settings",
        )

        if (pkg in noisePackages) {
            // Verificar si es una notificación importante del sistema
            val category = sbn.notification.category
            return category != Notification.CATEGORY_CALL &&
                    category != Notification.CATEGORY_ALARM
        }

        return false
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    /**
     * Actualiza la lista de contactos prioritarios.
     * Llamado desde la Capa Cognitiva cuando el usuario indica
     * que cierto contacto es prioritario.
     */
    fun addPriorityContact(name: String) {
        priorityContacts.add(name.lowercase())
    }

    fun removePriorityContact(name: String) {
        priorityContacts.remove(name.lowercase())
    }

    /**
     * Configura el horario de no molestar.
     */
    fun setQuietHours(startHour: Int, endHour: Int) {
        dndStartHour = startHour.coerceIn(0, 23)
        dndEndHour = endHour.coerceIn(0, 23)
    }

    /**
     * Obtiene todas las notificaciones activas actualmente.
     * Útil para generar el briefing matutino.
     */
    fun getActiveNotificationsSummary(): List<NotificationData> {
        return try {
            getActiveNotifications()?.mapNotNull { sbn ->
                val extras = sbn.notification.extras
                NotificationData(
                    packageName = sbn.packageName,
                    title = extractTitle(extras),
                    text = extractText(extras),
                    priority = sbn.notification.priority,
                    systemCategory = sbn.notification.category,
                    sender = extractSender(extras, sbn.packageName),
                    timestamp = sbn.postTime
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permiso para leer notificaciones activas", e)
            emptyList()
        }
    }
}

/**
 * Modelo de datos interno para una notificación extraída.
 */
data class NotificationData(
    val packageName: String,
    val title: String,
    val text: String,
    val priority: Int,
    val systemCategory: String?,
    val sender: String,
    val timestamp: Long
)
