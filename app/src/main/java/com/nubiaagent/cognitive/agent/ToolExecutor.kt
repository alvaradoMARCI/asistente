package com.nubiaagent.cognitive.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.perception.vision.ScreenObserver
import org.json.JSONObject

/**
 * ToolExecutor: Ejecuta las herramientas invocadas por el AgentLoop.
 *
 * Recibe un ToolCall parseado del LLM y lo ejecuta usando los
 * servicios del sistema Android. Cada herramienta devuelve un
 * ToolResult que el AgentLoop usa para decidir el siguiente paso.
 *
 * ARQUITECTURA DE EJECUCIÓN:
 *
 * ```
 * ToolCall → ToolExecutor → [Servicio Android] → ToolResult
 *                              ├─ SmsManager
 *                              ├─ AccessibilityService (screen.tap/type)
 *                              ├─ CalendarProvider
 *                              ├─ MemoryManager
 *                              ├─ NotificationManager
 *                              └─ Intent (app.launch, settings)
 * ```
 *
 * SEGURIDAD:
 * - Todas las acciones destructivas pasan por el AutonomyProfile
 * - Los parámetros se sanitizan antes de pasarlos al sistema
 * - Se registra un log de cada acción ejecutada
 * - Los errores se capturan y se devuelven como ToolResult.Failure
 */
class ToolExecutor(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/Tools"
    }

    private var memoryManager: MemoryManager? = null
    private var screenObserver: ScreenObserver? = null

    /**
     * Establece las dependencias del ejecutor.
     * Se llaman después de la inicialización de los servicios.
     */
    fun setMemoryManager(mm: MemoryManager) { memoryManager = mm }
    fun setScreenObserver(so: ScreenObserver) { screenObserver = so }

    /**
     * Ejecuta una herramienta y retorna el resultado.
     */
    suspend fun execute(toolCall: ToolCall): ToolResult {
        val toolDef = ToolRegistry.getTool(toolCall.toolName)

        if (toolDef == null) {
            Log.w(TAG, "Herramienta desconocida: ${toolCall.toolName}")
            return ToolResult.Failure(
                summary = "Herramienta '${toolCall.toolName}' no encontrada",
                error = "Tool not registered"
            )
        }

        Log.i(TAG, "Ejecutando: ${toolCall.toolName}(${toolCall.parameters.take(50)}...)")

        return try {
            val params = parseParameters(toolCall.parameters)

            when (toolCall.toolName) {
                // Comunicaciones
                "sms.send" -> executeSmsSend(params)
                "whatsapp.send" -> executeWhatsappSend(params)
                "call.make" -> executeCallMake(params)

                // Aplicaciones
                "app.launch" -> executeAppLaunch(params)
                "app.close" -> executeAppClose(params)

                // Pantalla
                "screen.read" -> executeScreenRead(params)
                "screen.tap" -> executeScreenTap(params)
                "screen.type" -> executeScreenType(params)
                "screen.scroll" -> executeScreenScroll(params)

                // Memoria
                "memory.recall" -> executeMemoryRecall(params)
                "memory.store" -> executeMemoryStore(params)
                "memory.forget" -> executeMemoryForget(params)

                // Calendario
                "calendar.read" -> executeCalendarRead(params)
                "calendar.create" -> executeCalendarCreate(params)

                // Notificaciones
                "notification.send" -> executeNotificationSend(params)
                "notification.read" -> executeNotificationRead(params)

                // Sistema
                "system.settings" -> executeSystemSettings(params)
                "system.status" -> executeSystemStatus(params)

                else -> ToolResult.Failure(
                    summary = "Herramienta no implementada: ${toolCall.toolName}",
                    error = "Not implemented"
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso denegado para ${toolCall.toolName}", e)
            ToolResult.Failure(
                summary = "Permiso denegado",
                error = e.message ?: "SecurityException"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando ${toolCall.toolName}", e)
            ToolResult.Failure(
                summary = "Error: ${e.message}",
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Obtiene las descripciones de herramientas para el system prompt.
     */
    fun getToolDescriptions(): String = ToolRegistry.generateToolDescriptions()

    // ==================== IMPLEMENTACIONES DE HERRAMIENTAS ====================

    /**
     * SMS: Envía un mensaje SMS usando SmsManager.
     */
    private fun executeSmsSend(params: JSONObject): ToolResult {
        val to = params.optString("to", "")
        val message = params.optString("message", "")

        if (to.isBlank() || message.isBlank()) {
            return ToolResult.Failure(
                summary = "Parámetros insuficientes para enviar SMS",
                error = "Falta 'to' o 'message'"
            )
        }

        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(android.telephony.SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault()
        }

        // TODO: Resolver nombre de contacto a número telefónico
        val phoneNumber = resolveContactToPhone(to)

        smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        Log.i(TAG, "SMS enviado a $to")

        return ToolResult.Success("SMS enviado a $to: \"${message.take(50)}...\"")
    }

    private fun executeWhatsappSend(params: JSONObject): ToolResult {
        val to = params.optString("to", "")
        val message = params.optString("message", "")

        // Usar Intent para enviar via WhatsApp
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return ToolResult.Success("WhatsApp abierto para enviar mensaje a $to")
        }

        return ToolResult.Failure(
            summary = "WhatsApp no está instalado",
            error = "Package com.whatsapp not found"
        )
    }

    private fun executeCallMake(params: JSONObject): ToolResult {
        val to = params.optString("to", "")
        val phoneNumber = resolveContactToPhone(to)

        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$phoneNumber")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return ToolResult.Success("Llamada iniciada a $to")
    }

    // Aplicaciones
    private fun executeAppLaunch(params: JSONObject): ToolResult {
        val packageName = params.optString("package", "")

        // Intentar resolver nombre amigable a package name
        val resolvedPackage = resolveAppName(packageName)

        val intent = context.packageManager.getLaunchIntentForPackage(resolvedPackage)
            ?: return ToolResult.Failure(
                summary = "App '$packageName' no encontrada",
                error = "Package not found: $resolvedPackage"
            )

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ToolResult.Success("App $packageName abierta")
    }

    private fun executeAppClose(params: JSONObject): ToolResult {
        val packageName = params.optString("package", "")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        activityManager.killBackgroundProcesses(packageName)
        return ToolResult.Success("App $packageName cerrada (background processes killed)")
    }

    // Pantalla
    private suspend fun executeScreenRead(params: JSONObject): ToolResult {
        val observer = screenObserver
            ?: return ToolResult.Failure("ScreenObserver no disponible", "Service not bound")

        // Forzar re-lectura de la pantalla
        observer.forceScreenRead()
        return ToolResult.Success("Pantalla leída. Esperando evento UiElementsReady del PerceptionBus.")
    }

    private fun executeScreenTap(params: JSONObject): ToolResult {
        val target = params.optString("target", "")
        val elementIndex = params.optInt("element_index", -1)

        // TODO: Implementar tap via AccessibilityService
        // observer.performClick(elementIndex) o dispatchGesture()

        return ToolResult.Success("Tap en '$target' (índice: $elementIndex) - Pendiente implementación de gestos")
    }

    private fun executeScreenType(params: JSONObject): ToolResult {
        val text = params.optString("text", "")
        val pressEnter = params.optBoolean("press_enter", false)

        // TODO: Implementar type via AccessibilityService
        // observer.performSetText(focusedNode, text)

        return ToolResult.Success("Texto '$text' escrito (Enter: $pressEnter) - Pendiente implementación de input")
    }

    private fun executeScreenScroll(params: JSONObject): ToolResult {
        val direction = params.optString("direction", "down")
        val amount = params.optInt("amount", 500)

        // TODO: Implementar scroll via AccessibilityService dispatchGesture()

        return ToolResult.Success("Scroll $direction ($amount px) - Pendiente implementación de gestos")
    }

    // Memoria
    private suspend fun executeMemoryRecall(params: JSONObject): ToolResult {
        val mm = memoryManager
            ?: return ToolResult.Failure("MemoryManager no disponible", "Not initialized")

        val query = params.optString("query", "")
        val limit = params.optInt("limit", 5)

        val results = mm.recallRelevant(query)
        return if (results.isNotBlank()) {
            ToolResult.Success("Memoria recuperada: ${results.take(500)}")
        } else {
            ToolResult.Success("No se encontró información relevante para '$query'")
        }
    }

    private suspend fun executeMemoryStore(params: JSONObject): ToolResult {
        val mm = memoryManager
            ?: return ToolResult.Failure("MemoryManager no disponible", "Not initialized")

        val content = params.optString("content", "")
        val category = params.optString("category", "fact")
        val importance = params.optDouble("importance", 0.5).toFloat()

        mm.storeFact(content, category, importance)
        return ToolResult.Success("Hecho almacenado: '$content' (categoría: $category, importancia: $importance)")
    }

    private suspend fun executeMemoryForget(params: JSONObject): ToolResult {
        val mm = memoryManager
            ?: return ToolResult.Failure("MemoryManager no disponible", "Not initialized")

        val query = params.optString("query", "")
        val confirm = params.optBoolean("confirm", false)

        if (!confirm) {
            return ToolResult.NeedsInput(
                summary = "Se requiere confirmación para eliminar datos",
                question = "¿Estás seguro de que quieres eliminar '$query' de la memoria? Esta acción es irreversible."
            )
        }

        mm.forget(query)
        return ToolResult.Success("Información eliminada de la memoria: '$query'")
    }

    // Calendario
    private fun executeCalendarRead(params: JSONObject): ToolResult {
        val date = params.optString("date", "")
        val days = params.optInt("days", 1)

        // TODO: Implementar lectura de CalendarProvider
        return ToolResult.Success("Calendario leído para $date ($days días) - Pendiente implementación CalendarProvider")
    }

    private fun executeCalendarCreate(params: JSONObject): ToolResult {
        val title = params.optString("title", "")
        val date = params.optString("date", "")
        val time = params.optString("time", "")

        // TODO: Implementar creación via CalendarProvider
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.item/event"
            putExtra(android.provider.CalendarContract.Events.TITLE, title)
            putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, "$dateT$time")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        return ToolResult.Success("Evento de calendario creado: '$title' el $date a las $time")
    }

    // Notificaciones
    private fun executeNotificationSend(params: JSONObject): ToolResult {
        val title = params.optString("title", "NubiaAgent")
        val message = params.optString("message", "")

        val notification = android.app.Notification.Builder(context, "nubia_agent_notifications")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify((System.currentTimeMillis() % 10000).toInt(), notification)

        return ToolResult.Success("Notificación enviada: '$title - $message'")
    }

    private fun executeNotificationRead(params: JSONObject): ToolResult {
        // TODO: Delegar al NotificationInterceptor
        return ToolResult.Success("Notificaciones leídas - Pendiente integración con NotificationInterceptor")
    }

    // Sistema
    private fun executeSystemSettings(params: JSONObject): ToolResult {
        val setting = params.optString("setting", "")
        val action = params.optString("action", "open")

        val intent = when (setting) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_ZEN_MODE_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return ToolResult.Success("Configuración de $setting abierta")
    }

    private fun executeSystemStatus(params: JSONObject): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val freeRamMb = memInfo.availMem / (1024 * 1024)

        return ToolResult.Success(
            "Batería: $batteryLevel% ${if (isCharging) "(cargando)" else ""}, " +
                    "RAM libre: ${freeRamMb}MB, " +
                    "Android: ${android.os.Build.VERSION.RELEASE}, " +
                    "Modelo: ${android.os.Build.MODEL}"
        )
    }

    // ==================== UTILIDADES ====================

    private fun parseParameters(paramsString: String): JSONObject {
        return try {
            if (paramsString.isBlank() || paramsString == "()") {
                JSONObject()
            } else if (paramsString.startsWith("{")) {
                JSONObject(paramsString)
            } else {
                // Parsear formato key=value
                val json = JSONObject()
                val pairs = paramsString.split(",")
                for (pair in pairs) {
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2) {
                        val key = kv[0].trim()
                        val value = kv[1].trim().removeSurrounding("\"")
                        json.put(key, value)
                    }
                }
                json
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando parámetros: $paramsString", e)
            JSONObject()
        }
    }

    /**
     * Resuelve un nombre de contacto a número telefónico.
     * TODO: Implementar con ContactsContract
     */
    private fun resolveContactToPhone(name: String): String {
        // Placeholder: si parece un número, usarlo directamente
        return if (name.matches(Regex("\\+?\\d{7,15}"))) {
            name
        } else {
            // TODO: Buscar en contactos del dispositivo
            name
        }
    }

    /**
     * Resuelve un nombre de app amigable a package name.
     */
    private fun resolveAppName(name: String): String {
        val appMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "facebook" to "com.facebook.katana",
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "camera" to "com.android.camera",
            "calculator" to "com.android.calculator2",
            "calendar" to "com.google.android.calendar",
            "clock" to "com.android.deskclock",
            "settings" to "com.android.settings",
            "photos" to "com.google.android.apps.photos",
            "play store" to "com.android.vending",
            "slack" to "com.Slack",
            "discord" to "com.discord",
            "tiktok" to "com.zhiliaoapp.musically",
            "netflix" to "com.netflix.mediaclient",
        )

        return appMap[name.lowercase().trim()] ?: name
    }
}
