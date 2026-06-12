package com.nubiaagent.execution.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.nubiaagent.MainActivity
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AgentStatus: Estados posibles del agente, mostrados en la burbuja.
 *
 * Cada estado tiene un texto descriptivo (visible al usuario) y un color
 * asociado que se refleja en la burbuja flotante.
 */
enum class AgentStatus(
    val displayText: String,
    val bubbleColor: Int
) {
    /** El agente está escuchando al usuario. */
    LISTENING("Escuchando...", 0xFF4CAF50.toInt()),       // Verde

    /** El agente está analizando la pantalla del dispositivo. */
    ANALYZING_SCREEN("Analizando pantalla...", 0xFF2196F3.toInt()), // Azul

    /** El agente está escribiendo un mensaje. */
    WRITING_MESSAGE("Escribiendo mensaje...", 0xFF2196F3.toInt()),  // Azul

    /** El agente está procesando / pensando. */
    THINKING("Pensando...", 0xFF2196F3.toInt()),           // Azul

    /** El agente está inactivo, esperando instrucciones. */
    IDLE("Inactivo", 0xFF4CAF50.toInt()),                  // Verde

    /** Ocurrió un error. */
    ERROR("Error", 0xFFF44336.toInt()),                    // Rojo

    /** El agente espera confirmación del usuario. */
    WAITING_CONFIRMATION("Esperando confirmación...", 0xFFFFC107.toInt()), // Amarillo

    /** El agente está hablando la respuesta. */
    SPEAKING("Hablando...", 0xFF9C27B0.toInt()),           // Púrpura

    /** El agente está ejecutando una acción. */
    EXECUTING("Ejecutando...", 0xFF2196F3.toInt()),        // Azul

    /** El agente está grabando audio o video. */
    RECORDING("Grabando...", 0xFFF44336.toInt());          // Rojo

    companion object {
        /** Convierte un nombre de estado a AgentStatus, con fallback a IDLE. */
        fun fromName(name: String): AgentStatus {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: IDLE
        }
    }
}

/**
 * KillSwitchListener: Interfaz para notificar cuando el kill switch se activa.
 *
 * Cualquier componente que necesite reaccionar al kill switch (detener
 * automatización, cancelar tareas, etc.) debe implementar esta interfaz
 * y registrarse con [OverlayService.addKillSwitchListener].
 */
interface KillSwitchListener {
    /**
     * Llamado cuando el usuario activa el kill switch (tap en la burbuja).
     * La implementación debe detener TODA la automatización inmediatamente.
     */
    fun onKillSwitchActivated()
}

/**
 * OverlayService: Servicio de burbuja flotante de estado del agente.
 *
 * Muestra una burbuja circular de 48dp que indica visualmente el estado
 * actual del agente NubiaAgent. La burbuja flota sobre todas las aplicaciones
 * usando SYSTEM_ALERT_WINDOW.
 *
 * INTERACCIONES:
 * ```
 *  ┌─────────────┬──────────────────────────────────────────────┐
 *  │ Interacción  │ Acción                                       │
 *  ├─────────────┼──────────────────────────────────────────────┤
 *  │ Tap simple   │ KILL SWITCH — detiene toda automatización    │
 *  │ Pulsación    │ Expandir panel de estado detallado           │
 *  │ larga        │                                              │
 *  │ Arrastrar    │ Mover burbuja a cualquier borde de pantalla  │
 *  └─────────────┴──────────────────────────────────────────────┘
 * ```
 *
 * COLORES DE LA BURBUJA:
 * - 🟢 Verde: Inactivo / Escuchando (estado pasivo)
 * - 🔵 Azul: Procesando / Pensando / Escribiendo (estado activo)
 * - 🔴 Rojo: Error / Grabando (estado de alerta)
 * - 🟡 Amarillo: Esperando confirmación del usuario
 *
 * PANEL DETALLADO (pulsación larga):
 * - Estado actual del agente
 * - Última acción ejecutada
 * - Nivel de batería y estado de bypass
 * - Tiempo de actividad de la sesión
 * - Botón de kill switch redundante (por seguridad)
 *
 * NOTA TÉCNICA:
 * - Requiere permiso SYSTEM_ALERT_WINDOW (otorgado por el usuario)
 * - Funciona como servicio en primer plano (notification channel obligatorio API 26+)
 * - Mínimo API 26 (Android 8.0)
 *
 * @see AgentStatus
 * @see KillSwitchListener
 */
@RequiresApi(Build.VERSION_CODES.O)
class OverlayService : Service() {

    companion object {
        private const val TAG = "NubiaAgent/Overlay"

        // ──────────────────────── Constantes de UI ──────────────────────────

        /** Diámetro de la burbuja en píxeles. */
        private const val BUBBLE_SIZE_DP = 48

        /** Tamaño del texto dentro de la burbuja (emoji/icono). */
        private const val BUBBLE_TEXT_SIZE_SP = 18f

        /** Radio de la burbuja circular. */
        private const val BUBBLE_RADIUS_DP = 24f

        /** Margen desde el borde de la pantalla al soltar. */
        private const val EDGE_MARGIN_DP = 8

        /** Umbral de movimiento para distinguir tap de drag (en píxeles). */
        private const val TOUCH_SLOP_DP = 10

        /** Duración de la pulsación larga para expandir panel (ms). */
        private const val LONG_PRESS_TIMEOUT_MS = 500L

        /** Duración de la animación de snap al borde (ms). */
        private const val SNAP_ANIMATION_DURATION_MS = 200L

        // ──────────────────────── Notificaciones ────────────────────────────

        private const val NOTIFICATION_CHANNEL_ID = "nubia_agent_overlay"
        private const val NOTIFICATION_CHANNEL_NAME = "Estado del Agente Nubia"
        private const val NOTIFICATION_ID = 2001

        // ──────────────────────── Intent Actions ────────────────────────────

        const val ACTION_SHOW = "com.nubiaagent.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.nubiaagent.action.HIDE_OVERLAY"
        const val ACTION_UPDATE_STATUS = "com.nubiaagent.action.UPDATE_STATUS"
        const val ACTION_KILL_SWITCH = "com.nubiaagent.action.KILL_SWITCH"
        const val EXTRA_STATUS = "agent_status"
        const val EXTRA_DETAIL = "status_detail"

        // ──────────────────────── Instancia Estática ────────────────────────

        /**
         * Referencia estática a la instancia activa del servicio.
         * Permite a otros componentes interactuar con el overlay
         * sin necesidad de binding.
         */
        @Volatile
        private var instance: OverlayService? = null

        /**
         * Actualiza el estado del agente en el overlay.
         * Si el servicio no está activo, lo inicia automáticamente.
         */
        fun setStatus(context: Context, status: AgentStatus, detail: String = "") {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status.name)
                putExtra(EXTRA_DETAIL, detail)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Muestra el overlay. Inicia el servicio si no está activo.
         */
        fun show(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Oculta el overlay y detiene el servicio.
         */
        fun hide(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        /**
         * Registra un listener para el kill switch.
         */
        fun addKillSwitchListener(listener: KillSwitchListener) {
            instance?.killSwitchListeners?.add(listener)
        }

        /**
         * Desregistra un listener del kill switch.
         */
        fun removeKillSwitchListener(listener: KillSwitchListener) {
            instance?.killSwitchListeners?.remove(listener)
        }

        /**
         * Activa el kill switch programáticamente.
         */
        fun triggerKillSwitch() {
            instance?.activateKillSwitch()
        }
    }

    // ──────────────────────────── Estado Interno ─────────────────────────────

    /** Estado actual del agente. */
    private val _currentStatus = MutableStateFlow(AgentStatus.IDLE)
    val currentStatus: StateFlow<AgentStatus> = _currentStatus.asStateFlow()

    /** Detalle adicional del estado actual. */
    private val _statusDetail = MutableStateFlow("")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    /** Lista de listeners del kill switch. */
    private val killSwitchListeners = mutableListOf<KillSwitchListener>()

    /** WindowManager para agregar/remover vistas de overlay. */
    private var windowManager: WindowManager? = null

    /** Vista de la burbuja flotante. */
    private var bubbleView: View? = null

    /** Vista del panel de estado detallado. */
    private var detailPanelView: View? = null

    /** Si el panel detallado está visible. */
    private var detailPanelVisible = false

    /** Parámetros de layout de la burbuja. */
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Handler principal para animaciones y callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Job de suscripción al PerceptionBus. */
    private var perceptionJob: Job? = null

    /** Scope para corrutinas. */
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Timestamp de inicio de la sesión. */
    private val sessionStartTime = System.currentTimeMillis()

    /** Posición inicial del toque (para distinguir tap de drag). */
    private var touchDownX = 0f
    private var touchDownY = 0f

    /** Si se está arrastrando la burbuja. */
    private var isDragging = false

    /** Si se detectó pulsación larga. */
    private var isLongPress = false

    /** Runnable para detectar pulsación larga. */
    private var longPressRunnable: Runnable? = null

    /** Si el kill switch ya fue activado (evitar activaciones múltiples). */
    private var killSwitchActivated = false

    // ──────────────────────── Conversiones DP ────────────────────────────────

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ciclo de Vida del Servicio
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        instance = this

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        subscribeToPerceptionBus()

        Log.i(TAG, "OverlayService creado — burbuja de estado lista")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showBubble()
            ACTION_HIDE -> hideBubbleAndStop()
            ACTION_UPDATE_STATUS -> {
                val statusName = intent.getStringExtra(EXTRA_STATUS) ?: AgentStatus.IDLE.name
                val detail = intent.getStringExtra(EXTRA_DETAIL) ?: ""
                updateStatus(AgentStatus.fromName(statusName), detail)
                if (bubbleView == null) showBubble()
            }
            ACTION_KILL_SWITCH -> activateKillSwitch()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeBubble()
        removeDetailPanel()
        perceptionJob?.cancel()
        killSwitchListeners.clear()
        instance = null
        super.onDestroy()
        Log.i(TAG, "OverlayService destruido")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Notificación de Primer Plano
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Crea el canal de notificación para el servicio en primer plano.
     * Requerido en API 26+.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Muestra el estado del agente NubiaAgent"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Crea la notificación persistente del servicio en primer plano.
     */
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val killIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).apply {
                action = ACTION_KILL_SWITCH
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NubiaAgent Activo")
            .setContentText("Toca la burbuja para detener la automatización")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "DETENER TODO",
                killIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Burbuja Flotante
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Muestra la burbuja flotante en la pantalla.
     *
     * Posición inicial: borde derecho, centrada verticalmente.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubbleView != null) return // Ya visible

        val bubbleSize = dpToPx(BUBBLE_SIZE_DP)

        // Crear la vista de la burbuja
        bubbleView = createBubbleView()

        // Configurar parámetros de layout
        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Posición inicial: borde derecho, centro vertical
            x = resources.displayMetrics.widthPixels - bubbleSize - dpToPx(EDGE_MARGIN_DP)
            y = resources.displayMetrics.heightPixels / 2 - bubbleSize / 2
        }

        try {
            windowManager?.addView(bubbleView, bubbleParams)
            Log.i(TAG, "Burbuja de estado mostrada")
        } catch (e: Exception) {
            Log.e(TAG, "Error al mostrar burbuja — ¿permiso SYSTEM_ALERT_WINDOW concedido?", e)
            bubbleView = null
            bubbleParams = null
        }

        // Configurar touch listener para arrastre, tap y pulsación larga
        bubbleView?.setOnTouchListener(BubbleTouchListener())
    }

    /**
     * Crea la vista visual de la burbuja flotante.
     *
     * Es un FrameLayout circular con un TextView que muestra
     * un indicador del estado actual.
     */
    private fun createBubbleView(): View {
        val container = FrameLayout(this)
        val size = dpToPx(BUBBLE_SIZE_DP)

        container.layoutParams = FrameLayout.LayoutParams(size, size)

        // Fondo circular con color del estado actual
        updateBubbleAppearance(container)

        // Texto indicador (emoji/icono simplificado)
        val statusIcon = TextView(this).apply {
            text = getStatusIcon(_currentStatus.value)
            textSize = BUBBLE_TEXT_SIZE_SP
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        container.addView(statusIcon)

        // Etiqueta de estado (aparece al mantener presionado brevemente)
        val statusLabel = TextView(this).apply {
            id = View.generateViewId()
            text = _currentStatus.value.displayText
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dpToPx(2), 0, dpToPx(2), dpToPx(2))
            maxWidth = dpToPx(120)
        }

        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dpToPx(2)
        }

        container.addView(statusLabel, labelParams)

        return container
    }

    /**
     * Actualiza la apariencia visual de la burbuja según el estado.
     */
    private fun updateBubbleAppearance(bubble: View) {
        val color = _currentStatus.value.bubbleColor
        bubble.background = createCircleDrawable(color, dpToPx(BUBBLE_RADIUS_DP).toFloat())
    }

    /**
     * Crea un drawable circular con color sólido.
     */
    private fun createCircleDrawable(color: Int, radius: Float): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            cornerRadius = radius
        }
    }

    /**
     * Obtiene el ícono/emoji correspondiente al estado del agente.
     */
    private fun getStatusIcon(status: AgentStatus): String {
        return when (status) {
            AgentStatus.LISTENING -> "🎤"
            AgentStatus.ANALYZING_SCREEN -> "👁"
            AgentStatus.WRITING_MESSAGE -> "✏️"
            AgentStatus.THINKING -> "🧠"
            AgentStatus.IDLE -> "●"
            AgentStatus.ERROR -> "✕"
            AgentStatus.WAITING_CONFIRMATION -> "❓"
            AgentStatus.SPEAKING -> "🔊"
            AgentStatus.EXECUTING -> "⚡"
            AgentStatus.RECORDING -> "⏺"
        }
    }

    /**
     * Remueve la burbuja flotante de la pantalla.
     */
    private fun removeBubble() {
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error al remover burbuja", e)
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    /**
     * Actualiza el estado del agente y refresca la burbuja.
     */
    private fun updateStatus(status: AgentStatus, detail: String = "") {
        val previous = _currentStatus.value
        _currentStatus.value = status
        _statusDetail.value = detail

        if (previous != status) {
            Log.d(TAG, "Estado cambiado: ${previous.displayText} → ${status.displayText}")
            refreshBubbleUI()

            // Actualizar notificación
            updateNotification(status)

            // Actualizar panel detallado si está visible
            if (detailPanelVisible) {
                refreshDetailPanel()
            }
        }
    }

    /**
     * Refresca la apariencia de la burbuja en el hilo principal.
     */
    private fun refreshBubbleUI() {
        mainHandler.post {
            bubbleView?.let { bubble ->
                updateBubbleAppearance(bubble)

                // Actualizar ícono
                if (bubble is FrameLayout) {
                    val iconView = bubble.getChildAt(0) as? TextView
                    iconView?.text = getStatusIcon(_currentStatus.value)

                    // Actualizar etiqueta
                    val labelView = bubble.getChildAt(1) as? TextView
                    labelView?.text = _currentStatus.value.displayText
                }
            }
        }
    }

    /**
     * Actualiza el texto de la notificación persistente.
     */
    private fun updateNotification(status: AgentStatus) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Touch Listener — Arrastre, Tap y Pulsación Larga
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * TouchListener personalizado para manejar las tres interacciones
     * de la burbuja: arrastre, tap (kill switch) y pulsación larga (panel).
     */
    private inner class BubbleTouchListener : View.OnTouchListener {

        private val touchSlop = dpToPx(TOUCH_SLOP_DP)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            val wm = windowManager ?: return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isDragging = false
                    isLongPress = false

                    // Iniciar detección de pulsación larga
                    longPressRunnable = Runnable {
                        isLongPress = true
                        toggleDetailPanel()
                    }
                    mainHandler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT_MS)

                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY

                    // Verificar si el movimiento excede el umbral (drag vs tap)
                    if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        isDragging = true
                        // Cancelar detección de pulsación larga
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }

                        // Ocultar panel si estaba visible
                        if (detailPanelVisible) {
                            removeDetailPanel()
                        }
                    }

                    if (isDragging) {
                        params.x = (event.rawX - dpToPx(BUBBLE_SIZE_DP) / 2f).toInt()
                        params.y = (event.rawY - dpToPx(BUBBLE_SIZE_DP) / 2f).toInt()
                        wm.updateViewLayout(view, params)
                    }

                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // Cancelar detección de pulsación larga
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }

                    if (!isDragging && !isLongPress) {
                        // Tap simple = KILL SWITCH
                        activateKillSwitch()
                    } else if (isDragging) {
                        // Snap al borde más cercano
                        snapToEdge(params, view)
                    }

                    isDragging = false
                    isLongPress = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    isDragging = false
                    isLongPress = false
                    return true
                }
            }

            return false
        }
    }

    /**
     * Anima la burbuja hacia el borde más cercano (izquierdo o derecho).
     */
    private fun snapToEdge(params: WindowManager.LayoutParams, view: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleSize = dpToPx(BUBBLE_SIZE_DP)
        val margin = dpToPx(EDGE_MARGIN_DP)
        val currentX = params.x

        val targetX = if (currentX + bubbleSize / 2 < screenWidth / 2) {
            // Más cerca del borde izquierdo
            margin
        } else {
            // Más cerca del borde derecho
            screenWidth - bubbleSize - margin
        }

        // Animación simple paso a paso
        val steps = 10
        val stepDuration = SNAP_ANIMATION_DURATION_MS / steps
        val dx = (targetX - currentX) / steps

        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                if (step <= steps) {
                    params.x += dx.toInt()
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    mainHandler.postDelayed(this, stepDuration)
                }
            }
        }
        mainHandler.postDelayed(runnable, stepDuration)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Panel de Estado Detallado
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Alterna la visibilidad del panel de estado detallado.
     */
    private fun toggleDetailPanel() {
        if (detailPanelVisible) {
            removeDetailPanel()
        } else {
            showDetailPanel()
        }
    }

    /**
     * Muestra el panel de estado detallado junto a la burbuja.
     */
    @SuppressLint("RtlHardcoded")
    private fun showDetailPanel() {
        if (detailPanelView != null) return

        val params = bubbleParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleSize = dpToPx(BUBBLE_SIZE_DP)

        // Determinar posición del panel (lado opuesto al borde)
        val onLeftEdge = params.x < screenWidth / 2

        detailPanelView = createDetailPanelView()

        val panelWidth = dpToPx(240)
        val panelHeight = dpToPx(320)

        val panelParams = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onLeftEdge) {
                params.x + bubbleSize + dpToPx(4)
            } else {
                params.x - panelWidth - dpToPx(4)
            }
            y = params.y
        }

        try {
            windowManager?.addView(detailPanelView, panelParams)
            detailPanelVisible = true

            // Mostrar etiqueta en la burbuja
            showBubbleLabel()

            Log.d(TAG, "Panel de estado detallado mostrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al mostrar panel detallado", e)
            detailPanelView = null
        }
    }

    /**
     * Crea la vista del panel de estado detallado.
     */
    private fun createDetailPanelView(): View {
        val panelPadding = dpToPx(16)
        val cornerRadius = dpToPx(16).toFloat()

        val container = ScrollView(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xDD222222.toInt()) // Fondo oscuro semi-transparente
                setCornerRadius(cornerRadius)
            }
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Título ──
        layout.addView(TextView(this).apply {
            text = "NubiaAgent"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(8))
        })

        // ── Estado Actual ──
        layout.addView(createDetailRow("Estado", _currentStatus.value.displayText))

        // ── Detalle ──
        if (_statusDetail.value.isNotBlank()) {
            layout.addView(createDetailRow("Detalle", _statusDetail.value))
        }

        // ── Tiempo de Sesión ──
        val sessionDuration = formatDuration(System.currentTimeMillis() - sessionStartTime)
        layout.addView(createDetailRow("Sesión", sessionDuration))

        // ── Hora Actual ──
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        layout.addView(createDetailRow("Hora", timeFormat.format(Date())))

        // ── Separador ──
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
            ).apply { topMargin = dpToPx(8); bottomMargin = dpToPx(8) }
            setBackgroundColor(0x44FFFFFF)
        })

        // ── Instrucciones ──
        layout.addView(TextView(this).apply {
            text = "TAP = Detener todo\nMantener = Este panel\nArrastrar = Mover"
            textSize = 11f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(0, 0, 0, dpToPx(8))
        })

        // ── Botón Kill Switch ──
        layout.addView(TextView(this).apply {
            text = "🛑 DETENER AUTOMATIZACIÓN"
            textSize = 14f
            setTextColor(0xFFFF4444.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(12), dpToPx(8), dpToPx(8))

            setOnClickListener {
                activateKillSwitch()
            }
        })

        container.addView(layout)
        return container
    }

    /**
     * Crea una fila de información para el panel detallado.
     */
    private fun createDetailRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        row.addView(TextView(this).apply {
            text = "$label: "
            textSize = 13f
            setTextColor(0xAAFFFFFF.toInt())
        })

        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        })

        return row
    }

    /**
     * Refresca el contenido del panel detallado.
     */
    private fun refreshDetailPanel() {
        mainHandler.post {
            if (detailPanelVisible) {
                removeDetailPanel()
                showDetailPanel()
            }
        }
    }

    /**
     * Remueve el panel de estado detallado.
     */
    private fun removeDetailPanel() {
        detailPanelView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error al remover panel detallado", e)
            }
        }
        detailPanelView = null
        detailPanelVisible = false

        hideBubbleLabel()
    }

    /**
     * Muestra la etiqueta de texto en la burbuja.
     */
    private fun showBubbleLabel() {
        mainHandler.post {
            (bubbleView as? FrameLayout)?.let { bubble ->
                if (bubble.childCount >= 2) {
                    bubble.getChildAt(1)?.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Oculta la etiqueta de texto en la burbuja.
     */
    private fun hideBubbleLabel() {
        mainHandler.post {
            (bubbleView as? FrameLayout)?.let { bubble ->
                if (bubble.childCount >= 2) {
                    bubble.getChildAt(1)?.visibility = View.GONE
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Kill Switch
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Activa el kill switch — detiene TODA la automatización inmediatamente.
     *
     * Este es el mecanismo de seguridad principal del overlay. Un solo tap
     * en la burbuja detiene cualquier acción que el agente esté ejecutando.
     *
     * Efectos:
     * 1. Notifica a todos los [KillSwitchListener] registrados
     * 2. Cambia el estado visual a ERROR (rojo)
     * 3. Actualiza la notificación persistente
     * 4. Publica evento en PerceptionBus
     */
    private fun activateKillSwitch() {
        if (killSwitchActivated) {
            Log.w(TAG, "Kill switch ya activado — ignorando activación duplicada")
            return
        }

        killSwitchActivated = true
        Log.w(TAG, "🛑 KILL SWITCH ACTIVADO — deteniendo toda automatización")

        // Cambiar estado visual
        updateStatus(AgentStatus.ERROR, "Automatización detenida por el usuario")

        // Notificar listeners
        val listeners = killSwitchListeners.toList() // Copia para evitar ConcurrentModification
        for (listener in listeners) {
            try {
                listener.onKillSwitchActivated()
            } catch (e: Exception) {
                Log.e(TAG, "Error en KillSwitchListener", e)
            }
        }

        // Publicar en PerceptionBus
        scope.launch {
            PerceptionBus.emit(
                PerceptionEvent.HardwareStateUpdate(
                    batteryLevel = 0,
                    isCharging = false,
                    isBypassCharging = false,
                    latitude = null,
                    longitude = null,
                    currentActivity = com.nubiaagent.core.UserActivity.STILL,
                    stepCount = 0
                )
            )
        }

        // Feedback visual: la burbuja se queda en rojo con "✕"
        // Se resetea después de 5 segundos
        mainHandler.postDelayed({
            killSwitchActivated = false
            updateStatus(AgentStatus.IDLE, "")
        }, 5000)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PerceptionBus
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Se suscribe al PerceptionBus para reaccionar a eventos del agente.
     */
    private fun subscribeToPerceptionBus() {
        perceptionJob = scope.launch {
            PerceptionBus.events.collect { event ->
                when (event) {
                    is PerceptionEvent.WakeWordDetected -> {
                        updateStatus(AgentStatus.LISTENING, "Wake word detectada (conf: ${String.format("%.0f", event.confidence * 100)}%)")
                    }

                    is PerceptionEvent.VoiceCommand -> {
                        if (event.isFinal) {
                            updateStatus(AgentStatus.THINKING, "Comando: \"${event.transcript.take(50)}\"")
                        }
                    }

                    is PerceptionEvent.ScreenChanged -> {
                        updateStatus(AgentStatus.ANALYZING_SCREEN, "${event.packageName}/${event.activityName}")
                    }

                    is PerceptionEvent.ScreenshotReady -> {
                        updateStatus(AgentStatus.ANALYZING_SCREEN, "Captura: ${event.imagePath.take(40)}")
                    }

                    is PerceptionEvent.UiElementsReady -> {
                        updateStatus(AgentStatus.THINKING, "${event.elements.size} elementos UI en ${event.packageName}")
                    }

                    is PerceptionEvent.NotificationReceived -> {
                        if (event.isUrgent) {
                            updateStatus(AgentStatus.WAITING_CONFIRMATION, "Notificación urgente de ${event.packageName}")
                        }
                    }

                    is PerceptionEvent.HardwareStateUpdate -> {
                        // No cambiar estado por actualización de hardware,
                        // solo actualizar si el panel detallado está visible
                        if (detailPanelVisible) {
                            refreshDetailPanel()
                        }
                    }

                    is PerceptionEvent.AgentResponse -> {
                        updateStatus(AgentStatus.SPEAKING, "Respondiendo: ${event.text.take(40)}...")
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilidades
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Formatea una duración en milisegundos a texto legible.
     */
    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Oculta la burbuja y detiene el servicio.
     */
    private fun hideBubbleAndStop() {
        removeBubble()
        removeDetailPanel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
