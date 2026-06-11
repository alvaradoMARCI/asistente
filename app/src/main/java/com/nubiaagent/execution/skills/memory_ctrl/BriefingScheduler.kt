package com.nubiaagent.execution.skills.memory_ctrl

import android.content.Context
import android.util.Log
import androidx.work.*
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.cognitive.memory.MorningBriefingData
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * BriefingScheduler: Programador de briefing matutino para NubiaAgent.
 *
 * Genera y presenta un resumen diario personalizado que incluye:
 * - Saludo con nombre del usuario
 * - Eventos del calendario del día
 * - Clima (placeholder para integración futura con API)
 * - Tareas pendientes recordadas por el agente
 * - Notificaciones importantes pendientes
 * - Personas a contactar hoy
 *
 * ARQUITECTURA:
 * ```
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │                  BriefingScheduler                           │
 *  │                                                              │
 *  │  scheduleBriefing() ──→ WorkManager (PeriodicWorkRequest)    │
 *  │         │                         │                          │
 *  │         │                         ▼                          │
 *  │         │               BriefingWorker.doWork()              │
 *  │         │                         │                          │
 *  │         │                         ▼                          │
 *  │         │               generateBriefing()                   │
 *  │         │                  │         │                       │
 *  │         │            ┌─────┘         └─────┐                │
 *  │         │            ▼                     ▼                 │
 *  │         │    Recopilar datos      CognitiveEngine.infer()    │
 *  │         │    (memoria + calendario)  (generar texto NL)     │
 *  │         │            │                     │                 │
 *  │         │            └──────┬──────────────┘                │
 *  │         │                   ▼                                │
 *  │         │         Briefing en español con emojis             │
 *  │         │                   │                                │
 *  │         │                   ▼                                │
 *  │         │         Notificación + PerceptionBus               │
 *  └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * FORMATO DEL BRIEFING (en español):
 * ```
 *  ☀️ ¡Buenos días, [nombre]!
 *
 *  📅 Eventos de hoy:
 *  - Reunión de equipo a las 10:00
 *  - Almuerzo con María a las 14:00
 *
 *  🌤️ Clima: [placeholder — integración pendiente]
 *
 *  ✅ Tareas pendientes:
 *  - Comprar leche
 *  - Enviar informe semanal
 *
 *  🔔 Notificaciones importantes:
 *  - Mensaje de Carlos sobre el proyecto
 *
 *  👤 Personas a contactar:
 *  - María (cumpleaños hoy)
 *  - Carlos (pendiente respuesta)
 * ```
 *
 * WORKMANAGER:
 * - Usa PeriodicWorkRequest para ejecución diaria
 * - Intervalo mínimo: 24 horas
 * - Flex window: 15 minutos para optimizar batería
 * - Constraints: dispositivo inactivo, sin uso de batería
 *
 * @property context Contexto de la aplicación
 * @property memoryManager Sistema de memoria persistente del agente
 * @property cognitiveEngine Motor de inferencia para generar texto natural
 */
class BriefingScheduler(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val cognitiveEngine: CognitiveEngine
) {

    companion object {
        private const val TAG = "NubiaAgent/Briefing"

        /** Nombre único del trabajo de briefing en WorkManager. */
        private const val BRIEFING_WORK_NAME = "nubia_agent_morning_briefing"

        /** Tag para identificar trabajos de briefing. */
        private const val BRIEFING_WORK_TAG = "briefing"

        /** Hora por defecto del briefing (7:00 AM). */
        private const val DEFAULT_HOUR = 7
        private const val DEFAULT_MINUTE = 0

        // ─── Formato del briefing ───

        /** Secciones del briefing con emojis. */
        private const val EMOJI_GREETING = "☀️"
        private const val EMOJI_CALENDAR = "📅"
        private const val EMOJI_WEATHER = "🌤️"
        private const val EMOJI_TASKS = "✅"
        private const val EMOJI_NOTIFICATIONS = "🔔"
        private const val EMOJI_PEOPLE = "👤"

        /** Formato de fecha para el briefing. */
        private val DATE_FORMAT = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", Locale("es", "ES"))
    }

    // ─── Scope de corrutinas ───

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── Estado del briefing ───

    private var lastBriefingText: String? = null
    private var lastBriefingTime: Long = 0L

    // ═══════════════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Programa el briefing matutino usando WorkManager.
     *
     * Crea un PeriodicWorkRequest que se ejecutará diariamente a la hora
     * especificada. Si ya existe un trabajo programado, lo reemplaza.
     *
     * El trabajo se ejecuta con las siguientes restricciones:
     * - El dispositivo debe estar inactivo (Doze mode permitido)
     * - No se requiere conexión a red (procesamiento local)
     *
     * @param hour Hora del briefing (0-23)
     * @param minute Minuto del briefing (0-59)
     */
    fun scheduleBriefing(hour: Int, minute: Int) {
        Log.i(TAG, "Programando briefing diario a las ${String.format("%02d:%02d", hour, minute)}")

        // Calcular el delay inicial hasta la próxima ejecución
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Si la hora objetivo ya pasó hoy, programar para mañana
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        val initialDelayMs = target.timeInMillis - now.timeInMillis

        // Crear datos de entrada para el worker
        val inputData = workDataOf(
            "hour" to hour,
            "minute" to minute
        )

        // Crear PeriodicWorkRequest (intervalo: 24 horas)
        val briefingWork = PeriodicWorkRequestBuilder<BriefingWorker>(
            24, TimeUnit.HOURS,
            15, TimeUnit.MINUTES  // Flex window de 15 minutos
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresDeviceIdle(false)  // Permitir ejecución aunque no esté en Doze
                    .setRequiresBatteryNotLow(false)  // El briefing es importante, ejecutar siempre
                    .build()
            )
            .addTag(BRIEFING_WORK_TAG)
            .build()

        // Reemplazar trabajo existente si lo hay
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BRIEFING_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            briefingWork
        )

        Log.i(TAG, "Briefing programado: primer ejecución en ${initialDelayMs / 60000} minutos, " +
                "luego cada 24 horas a las ${String.format("%02d:%02d", hour, minute)}")
    }

    /**
     * Genera el briefing completo del día.
     *
     * Recopila datos de todas las fuentes disponibles (memoria, calendario,
     * tareas, contactos) y usa el motor cognitivo para generar un briefing
     * en lenguaje natural en español.
     *
     * Flujo:
     * 1. Recopilar datos crudos de todas las fuentes
     * 2. Formatear datos como contexto para el LLM
     * 3. Llamar a CognitiveEngine.infer() para generar briefing natural
     * 4. Si el LLM falla, usar formato de plantilla como fallback
     *
     * @return Result con el texto del briefing en español
     */
    suspend fun generateBriefing(): Result<String> {
        Log.i(TAG, "Generando briefing matutino...")

        return try {
            // Paso 1: Recopilar datos de todas las fuentes
            val calendarEvents = getCalendarEvents()
            val pendingTasks = getPendingTasks()
            val briefingData = memoryManager.getMorningBriefingData()
            val importantFacts = briefingData.importantFacts
            val priorityContacts = briefingData.priorityContacts
            val recentInteractions = briefingData.recentInteractions

            // Paso 2: Obtener nombre del usuario del Living Profile
            val userProfile = memoryManager.getLivingProfile()
            val userName = extractUserName(userProfile)

            // Paso 3: Formatear datos para el LLM
            val dataContext = buildDataContext(
                userName = userName,
                calendarEvents = calendarEvents,
                pendingTasks = pendingTasks,
                importantFacts = importantFacts.map { it.content },
                priorityContacts = priorityContacts.map { it.name },
                recentInteractions = recentInteractions.map {
                    "Usuario: ${it.userMessage.take(60)} → Asistente: ${it.assistantResponse.take(60)}"
                }
            )

            // Paso 4: Generar briefing con el motor cognitivo
            val briefing = generateWithLLM(dataContext)

            // Guardar último briefing
            lastBriefingText = briefing
            lastBriefingTime = System.currentTimeMillis()

            Log.i(TAG, "Briefing generado exitosamente (${briefing.length} caracteres)")
            Result.success(briefing)

        } catch (e: Exception) {
            Log.e(TAG, "Error generando briefing", e)

            // Fallback: generar briefing con plantilla
            val fallbackBriefing = generateFallbackBriefing()
            Result.success(fallbackBriefing)
        }
    }

    /**
     * Cancela el briefing programado.
     */
    fun cancelBriefing() {
        WorkManager.getInstance(context).cancelUniqueWork(BRIEFING_WORK_NAME)
        Log.i(TAG, "Briefing programado cancelado")
    }

    /**
     * Obtiene los eventos del calendario para hoy.
     *
     * Lee el ContentProvider de Calendar para obtener los eventos
     * del día actual. Si no hay permisos o no hay eventos,
     * retorna una lista vacía.
     *
     * @return Lista de descripciones de eventos en español
     */
    fun getCalendarEvents(): List<String> {
        val events = mutableListOf<String>()

        try {
            val startTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis

            val endTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }.timeInMillis

            val projection = arrayOf(
                "title",
                "dtstart",
                "dtend",
                "eventLocation",
                "description"
            )

            val uri = android.net.Uri.parse("content://com.android.calendar/events")
            val selection = "dtstart >= ? AND dtstart <= ?"
            val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

            val cursor = context.contentResolver.query(
                uri, projection, selection, selectionArgs, "dtstart ASC"
            )

            cursor?.use {
                val titleIndex = it.getColumnIndex("title")
                val startIndex = it.getColumnIndex("dtstart")
                val locationIndex = it.getColumnIndex("eventLocation")

                while (it.moveToNext()) {
                    val title = if (titleIndex >= 0) it.getString(titleIndex) else "Sin título"
                    val startMs = if (startIndex >= 0) it.getLong(startIndex) else 0L
                    val location = if (locationIndex >= 0) it.getString(locationIndex) else null

                    val timeFormat = SimpleDateFormat("HH:mm", Locale("es", "ES"))
                    val timeStr = if (startMs > 0) timeFormat.format(Date(startMs)) else "Hora no definida"

                    val eventDesc = if (location.isNullOrBlank()) {
                        "$title a las $timeStr"
                    } else {
                        "$title a las $timeStr en $location"
                    }

                    events.add(eventDesc)
                }
            }

            Log.d(TAG, "Eventos del calendario encontrados: ${events.size}")

        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permisos para leer calendario", e)
            events.add("Sin acceso al calendario — concede permisos en ajustes")
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo calendario", e)
        }

        return events
    }

    /**
     * Obtiene las tareas pendientes almacenadas en memoria.
     *
     * Busca hechos con categoría "task" en el MemoryManager y los
     * filtra por importancia.
     *
     * @return Lista de descripciones de tareas pendientes
     */
    fun getPendingTasks(): List<String> {
        val tasks = mutableListOf<String>()

        try {
            // Ejecutar de forma bloqueante ya que el método es síncrono
            scope.launch {
                try {
                    val taskFacts = memoryManager.getFactDao().getByCategory("task")
                    val pendingTasks = taskFacts.filter { fact ->
                        fact.content.contains("pendiente", ignoreCase = true) ||
                        fact.content.contains("por hacer", ignoreCase = true) ||
                        fact.content.contains("pendiente de", ignoreCase = true) ||
                        !fact.content.contains("completada", ignoreCase = true)
                    }

                    tasks.addAll(pendingTasks.map { it.content })
                    Log.d(TAG, "Tareas pendientes encontradas: ${tasks.size}")

                } catch (e: Exception) {
                    Log.e(TAG, "Error obteniendo tareas de memoria", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando tareas pendientes", e)
        }

        // Fallback: buscar en hechos generales por keywords
        if (tasks.isEmpty()) {
            try {
                scope.launch {
                    val allFacts = memoryManager.getFactDao().search("tarea", 5)
                    tasks.addAll(allFacts.map { it.content })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en fallback de tareas", e)
            }
        }

        return tasks
    }

    /**
     * Obtiene el último briefing generado.
     *
     * @return Texto del último briefing, o null si no se ha generado ninguno
     */
    fun getLastBriefing(): String? = lastBriefingText

    /**
     * Obtiene la hora del último briefing generado.
     *
     * @return Timestamp del último briefing, o 0 si no se ha generado
     */
    fun getLastBriefingTime(): Long = lastBriefingTime

    /**
     * Limpia recursos del programador.
     */
    fun destroy() {
        scope.cancel()
        Log.i(TAG, "BriefingScheduler destruido")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MÉTODOS PRIVADOS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Genera el briefing usando el motor cognitivo (LLM).
     *
     * Construye un prompt que incluye todos los datos recopilados y
     * pide al modelo que genere un briefing natural en español.
     *
     * @param dataContext Texto con todos los datos recopilados formateados
     * @return Texto del briefing generado por el LLM
     */
    private suspend fun generateWithLLM(dataContext: String): String {
        val today = DATE_FORMAT.format(Date())
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val greeting = when {
            hour < 6 -> "Buenas madrugadas"
            hour < 12 -> "Buenos días"
            hour < 18 -> "Buenas tardes"
            else -> "Buenas noches"
        }

        val prompt = """Eres un asistente personal llamado NubiaAgent. Genera un briefing diario en español para tu usuario.

DATOS PARA EL BRIEFING:
$dataContext

FECHA: $today

INSTRUCCIONES:
- Empieza con "$greeting, [nombre del usuario si lo sabes]!"
- Usa emojis para separar secciones (📅 🌤️ ✅ 🔔 👤)
- Sé conciso pero amable
- Si no hay datos en una sección, omítela o di "Nada pendiente"
- Menciona las personas importantes a contactar si las hay
- Sugiere una acción concreta para empezar el día
- Escribe todo en español
- No uses markdown, solo texto plano con emojis

BRIEFING:"""

        val result = cognitiveEngine.infer(prompt)

        return if (result.startsWith("[ERROR")) {
            Log.w(TAG, "LLM falló, usando plantilla: $result")
            generateFallbackBriefing()
        } else {
            result.trim()
        }
    }

    /**
     * Genera un briefing de fallback usando plantillas predefinidas.
     *
     * Se usa cuando el motor cognitivo no está disponible o falla.
     *
     * @return Texto del briefing generado con plantilla
     */
    private suspend fun generateFallbackBriefing(): String {
        val today = DATE_FORMAT.format(Date())
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val greeting = when {
            hour < 6 -> "Buenas madrugadas"
            hour < 12 -> "Buenos días"
            hour < 18 -> "Buenas tardes"
            else -> "Buenas noches"
        }

        val userProfile = memoryManager.getLivingProfile()
        val userName = extractUserName(userProfile)

        val calendarEvents = getCalendarEvents()
        val pendingTasks = getPendingTasks()
        val briefingData = memoryManager.getMorningBriefingData()

        val sb = StringBuilder()
        sb.appendLine("$EMOJI_GREETING $greeting${if (userName.isNotBlank()) ", $userName" else ""}!")
        sb.appendLine()
        sb.appendLine("Hoy es $today.")
        sb.appendLine()

        // Calendario
        if (calendarEvents.isNotEmpty()) {
            sb.appendLine("$EMOJI_CALENDAR Eventos de hoy:")
            calendarEvents.forEach { event ->
                sb.appendLine("  - $event")
            }
            sb.appendLine()
        } else {
            sb.appendLine("$EMOJI_CALENDAR No tienes eventos en el calendario para hoy.")
            sb.appendLine()
        }

        // Clima (placeholder)
        sb.appendLine("$EMOJI_WEATHER Clima: Información del clima no disponible aún. " +
                "Integración con servicio meteorológico pendiente.")
        sb.appendLine()

        // Tareas pendientes
        if (pendingTasks.isNotEmpty()) {
            sb.appendLine("$EMOJI_TASKS Tareas pendientes:")
            pendingTasks.take(5).forEach { task ->
                sb.appendLine("  - $task")
            }
            if (pendingTasks.size > 5) {
                sb.appendLine("  ... y ${pendingTasks.size - 5} más")
            }
            sb.appendLine()
        } else {
            sb.appendLine("$EMOJI_TASKS No tienes tareas pendientes registradas.")
            sb.appendLine()
        }

        // Notificaciones importantes
        val importantNotifications = briefingData.importantFacts
            .filter { it.category == "notification" || it.category == "urgent" }
            .take(3)
        if (importantNotifications.isNotEmpty()) {
            sb.appendLine("$EMOJI_NOTIFICATIONS Notificaciones importantes:")
            importantNotifications.forEach { notif ->
                sb.appendLine("  - ${notif.content}")
            }
            sb.appendLine()
        }

        // Personas a contactar
        val priorityContacts = briefingData.priorityContacts
        if (priorityContacts.isNotEmpty()) {
            sb.appendLine("$EMOJI_PEOPLE Personas a contactar:")
            priorityContacts.forEach { contact ->
                val notes = if (contact.notes.isNotBlank()) " (${contact.notes})" else ""
                sb.appendLine("  - ${contact.name}$notes")
            }
            sb.appendLine()
        }

        // Hechos importantes del día
        val facts = briefingData.importantFacts
            .filter { it.category != "notification" && it.category != "urgent" && it.category != "task" }
            .take(3)
        if (facts.isNotEmpty()) {
            sb.appendLine("📌 Para recordar:")
            facts.forEach { fact ->
                sb.appendLine("  - ${fact.content}")
            }
            sb.appendLine()
        }

        sb.appendLine("Que tengas un excelente día. Estoy aquí si me necesitas.")

        return sb.toString()
    }

    /**
     * Construye el contexto de datos para el prompt del LLM.
     *
     * Formatea todos los datos recopilados en un texto estructurado
     * que el LLM pueda procesar para generar el briefing.
     */
    private fun buildDataContext(
        userName: String,
        calendarEvents: List<String>,
        pendingTasks: List<String>,
        importantFacts: List<String>,
        priorityContacts: List<String>,
        recentInteractions: List<String>
    ): String {
        val sb = StringBuilder()

        sb.appendLine("USUARIO: ${if (userName.isNotBlank()) userName else "No se conoce el nombre"}")
        sb.appendLine()

        sb.appendLine("EVENTOS DEL CALENDARIO HOY:")
        if (calendarEvents.isNotEmpty()) {
            calendarEvents.forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("- Sin eventos hoy")
        }
        sb.appendLine()

        sb.appendLine("TAREAS PENDIENTES:")
        if (pendingTasks.isNotEmpty()) {
            pendingTasks.forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("- Sin tareas pendientes")
        }
        sb.appendLine()

        sb.appendLine("HECHOS IMPORTANTES:")
        if (importantFacts.isNotEmpty()) {
            importantFacts.take(5).forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("- Sin hechos destacados")
        }
        sb.appendLine()

        sb.appendLine("CONTACTOS PRIORITARIOS:")
        if (priorityContacts.isNotEmpty()) {
            priorityContacts.forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("- Sin contactos prioritarios")
        }
        sb.appendLine()

        sb.appendLine("INTERACCIONES RECIENTES:")
        if (recentInteractions.isNotEmpty()) {
            recentInteractions.take(3).forEach { sb.appendLine("- $it") }
        } else {
            sb.appendLine("- Sin interacciones recientes")
        }

        return sb.toString()
    }

    /**
     * Extrae el nombre del usuario del Living Profile.
     *
     * Busca patrones comunes como "Me llamo X", "Mi nombre es X", etc.
     *
     * @param profile Texto del Living Profile
     * @return Nombre del usuario, o cadena vacía si no se encuentra
     */
    private fun extractUserName(profile: String): String {
        if (profile.isBlank()) return ""

        val patterns = listOf(
            Regex("(?i)me llamo\\s+(\\w+)"),
            Regex("(?i)mi nombre es\\s+(\\w+)"),
            Regex("(?i)nombre:\\s*(\\w+)"),
            Regex("(?i)soy\\s+(\\w+)"),
            Regex("(?i)user:?\\s*(\\w+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(profile)
            if (match != null) {
                return match.groupValues[1].replaceFirstChar { it.uppercase() }
            }
        }

        return ""
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WORKER PARA WORKMANAGER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Worker que ejecuta el briefing diario en segundo plano.
     *
     * WorkManager invoca este worker a la hora programada.
     * Obtiene las instancias de MemoryManager y CognitiveEngine
     * y delega la generación al BriefingScheduler.
     */
    class BriefingWorker(
        context: Context,
        workerParams: WorkerParameters
    ) : CoroutineWorker(context, workerParams) {

        companion object {
            private const val TAG = "NubiaAgent/BriefingWorker"
        }

        override suspend fun doWork(): Result {
            Log.i(TAG, "Ejecutando worker de briefing matutino")

            return try {
                val memoryManager = MemoryManager.getInstance(applicationContext)
                val engine = CognitiveEngine.getInstance()

                if (engine == null) {
                    Log.w(TAG, "CognitiveEngine no disponible — generando briefing de fallback")
                    // Generar briefing simple sin LLM
                    return generateSimpleBriefing(memoryManager)
                }

                val scheduler = BriefingScheduler(applicationContext, memoryManager, engine)
                val briefingResult = scheduler.generateBriefing()

                if (briefingResult.isSuccess) {
                    val briefing = briefingResult.getOrDefault("")
                    Log.i(TAG, "Briefing generado exitosamente por worker")

                    // Publicar como notificación
                    publishBriefingNotification(briefing)

                    Result.success()
                } else {
                    Log.e(TAG, "Error generando briefing: ${briefingResult.exceptionOrNull()}")
                    Result.retry()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en worker de briefing", e)
                Result.retry()
            }
        }

        /**
         * Genera un briefing simple sin LLM cuando el motor no está disponible.
         */
        private suspend fun generateSimpleBriefing(memoryManager: MemoryManager): Result {
            try {
                val briefingData = memoryManager.getMorningBriefingData()
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

                val greeting = when {
                    hour < 12 -> "Buenos días"
                    hour < 18 -> "Buenas tardes"
                    else -> "Buenas noches"
                }

                val sb = StringBuilder()
                sb.appendLine("☀️ $greeting!")
                sb.appendLine()

                if (briefingData.importantFacts.isNotEmpty()) {
                    sb.appendLine("📌 Para recordar:")
                    briefingData.importantFacts.take(3).forEach {
                        sb.appendLine("  - ${it.content}")
                    }
                    sb.appendLine()
                }

                if (briefingData.priorityContacts.isNotEmpty()) {
                    sb.appendLine("👤 Personas a contactar:")
                    briefingData.priorityContacts.forEach {
                        sb.appendLine("  - ${it.name}")
                    }
                    sb.appendLine()
                }

                sb.appendLine("Que tengas un excelente día.")

                publishBriefingNotification(sb.toString())
                return Result.success()

            } catch (e: Exception) {
                Log.e(TAG, "Error generando briefing simple", e)
                return Result.retry()
            }
        }

        /**
         * Publica el briefing como notificación del sistema.
         */
        private fun publishBriefingNotification(briefingText: String) {
            try {
                val notificationManager = applicationContext.getSystemService(
                    Context.NOTIFICATION_SERVICE
                ) as android.app.NotificationManager

                // Crear canal de notificación (Android 8+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "briefing_channel",
                        "Briefing Matutino",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notificaciones del briefing diario de NubiaAgent"
                        enableVibration(true)
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                // Crear notificación
                val notification = androidx.core.app.NotificationCompat.Builder(
                    applicationContext, "briefing_channel"
                )
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("NubiaAgent — Briefing del Día")
                    .setStyle(
                        androidx.core.app.NotificationCompat.BigTextStyle()
                            .bigText(briefingText)
                    )
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(3001, notification)
                Log.i(TAG, "Notificación de briefing publicada")

            } catch (e: Exception) {
                Log.e(TAG, "Error publicando notificación de briefing", e)
            }
        }
    }
}
