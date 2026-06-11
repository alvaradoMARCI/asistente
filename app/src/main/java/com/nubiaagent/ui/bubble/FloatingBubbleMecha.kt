package com.nubiaagent.ui.bubble

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FloatingBubbleMecha: Burbuja flotante con estética Mecha Futurista.
 *
 * DISEÑO SHADOW BLACK / CYBER SILVER:
 *
 * La burbuja sigue la estética del hardware del Nubia Neo 3 5G:
 * - Color base: Shadow Black (#0D0D0D) con borde Cyber Silver (#C0C0C0)
 * - Animaciones pulsantes con acentos neón (#00F0FF = Cyan, #FF0066 = Magenta)
 * - Tipografía mecánica/futurista
 * - Efecto de escaneo (scan line) en la burbuja
 * - Transparencia variable según el estado
 *
 * ESTADOS VISUALES:
 *
 * ```
 * ┌──────────────────────────────────────────────────┐
 * │  IDLE        │  THINKING    │  LISTENING         │
 * │  ● Cyber     │  ● Pulsante │  ● Onda expansiva  │
 * │    Silver    │    Cyan      │    Magenta          │
 * │  Tranquilo   │  Procesando │  Escuchando voz     │
 * ├──────────────┼──────────────┼─────────────────────┤
 * │  SPEAKING    │  ERROR      │  BYPASS CHARGING    │
 * │  ● Glow      │  ● Rojo     │  ● Verde neón       │
 * │    White     │    tenue    │    pulsante          │
 * │  Respond.    │  Fallo      │  Curando memoria    │
 * └──────────────────────────────────────────────────┘
 * ```
 *
 * INTERACCIÓN:
 * - Arrastrar: Mover la burbuja por la pantalla
 * - Tap corto: Activar comando de voz
 * - Tap largo: Menú rápido (Personas, Canvas, Config)
 * - Doble tap: Expandir/colapsar panel de estado
 *
 * EMERGENCY STOP:
 * Botón rojo visible cuando el agente está en ejecución autónoma.
 * Un tap detiene inmediatamente cualquier automatización en curso.
 */
class FloatingBubbleMecha : Service() {

    companion object {
        private const val TAG = "NubiaAgent/BubbleMecha"

        // Colores Mecha Futurista
        private const val COLOR_SHADOW_BLACK = 0xFF0D0D0D.toInt()
        private const val COLOR_CYBER_SILVER = 0xFFC0C0C0.toInt()
        private const val COLOR_CYAN_NEON = 0xFF00F0FF.toInt()
        private const val COLOR_MAGENTA_NEON = 0xFFFF0066.toInt()
        private const val COLOR_GREEN_NEON = 0xFF00FF66.toInt()
        private const val COLOR_RED_WARNING = 0xFFFF3333.toInt()
        private const val COLOR_WHITE_GLOW = 0xFFFFFFFF.toInt()

        // Dimensiones
        private const val BUBBLE_SIZE_DP = 56
        private const val EXPANDED_WIDTH_DP = 280
        private const val EXPANDED_HEIGHT_DP = 180
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var expandedView: View? = null
    private var isExpanded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Estado del agente
    private val _agentStatus = MutableStateFlow(BubbleStatus.IDLE)
    val agentStatus: StateFlow<BubbleStatus> = _agentStatus.asStateFlow()

    // Coordenadas de la burbuja
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubbleView()
        subscribeToEvents()
        Log.i(TAG, "Burbuja Mecha Futurista creada")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        removeBubbleView()
        Log.i(TAG, "Burbuja destruida")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW" -> createBubbleView()
            "HIDE" -> removeBubbleView()
            "UPDATE_STATUS" -> {
                val status = intent.getStringExtra("status") ?: "IDLE"
                updateStatus(BubbleStatus.valueOf(status))
            }
            "EMERGENCY_STOP" -> emergencyStop()
        }
        return START_STICKY
    }

    /**
     * Crea la vista de la burbuja flotante con estética Mecha.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        if (bubbleView != null) return

        val density = resources.displayMetrics.density
        val bubbleSize = (BUBBLE_SIZE_DP * density).toInt()

        // Contenedor principal
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Burbuja circular con gradiente Mecha
        val bubble = ImageView(this).apply {
            setImageDrawable(createBubbleDrawable(bubbleSize))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        // Icono de estado (núcleo de la burbuja)
        val statusIcon = TextView(this).apply {
            text = "◆"  // Diamante mecánico
            setTextColor(COLOR_CYBER_SILVER)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, COLOR_CYAN_NEON)
        }

        // Label de estado (aparece al expandir)
        val statusLabel = TextView(this).apply {
            text = "IDLE"
            setTextColor(COLOR_CYAN_NEON)
            textSize = 9f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE)
            setShadowLayer(2f, 0f, 0f, COLOR_CYAN_NEON)
            visibility = View.GONE
        }

        container.addView(bubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        container.addView(statusIcon, FrameLayout.LayoutParams(bubbleSize, bubbleSize).apply {
            gravity = Gravity.CENTER
        })
        container.addView(statusLabel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = (4 * density).toInt()
        })

        // Parámetros del WindowManager
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize + (16 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - bubbleSize - (16 * density).toInt()
            y = resources.displayMetrics.heightPixels / 2
        }

        // Touch listener para arrastrar y taps
        var isDragging = false
        var touchStartTime = 0L
        var lastTapTime = 0L

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    touchStartTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(container, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        val elapsed = System.currentTimeMillis() - touchStartTime

                        if (elapsed < 200) {
                            // Tap corto
                            val timeSinceLastTap = System.currentTimeMillis() - lastTapTime
                            if (timeSinceLastTap < 300) {
                                // Doble tap → expandir/colapsar
                                toggleExpanded()
                            } else {
                                // Tap simple → comando de voz
                                onSingleTap()
                            }
                            lastTapTime = System.currentTimeMillis()
                        } else if (elapsed > 500) {
                            // Tap largo → menú rápido
                            onLongTap()
                        }
                    }
                    true
                }
            }
            false
        }

        windowManager?.addView(container, params)
        bubbleView = container

        // Animación de pulso inicial
        startPulseAnimation(bubble)
    }

    /**
     * Crea el drawable de la burbuja con gradiente Mecha Futurista.
     *
     * Efecto visual: Anillo externo Cyber Silver con núcleo
     * Shadow Black y borde sutil Cyan Neon.
     */
    private fun createBubbleDrawable(size: Int): android.graphics.drawable.Drawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f - 4f

        // Sombra exterior (glow cyan)
        val shadowPaint = Paint().apply {
            color = COLOR_CYAN_NEON
            alpha = 40
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy, radius, shadowPaint)

        // Fondo principal (Shadow Black con gradiente radial)
        val bgPaint = Paint().apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(0xFF1A1A2E.toInt(), COLOR_SHADOW_BLACK),
                floatArrayOf(0.3f, 1f),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Borde (Cyber Silver)
        val borderPaint = Paint().apply {
            color = COLOR_CYBER_SILVER
            alpha = 180
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Anillo interior decorativo (mecánico)
        val innerRingPaint = Paint().apply {
            color = COLOR_CYAN_NEON
            alpha = 60
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawCircle(cx, cy, radius * 0.7f, innerRingPaint)

        // Scan line horizontal (efecto HUD)
        val scanPaint = Paint().apply {
            color = COLOR_CYAN_NEON
            alpha = 30
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawLine(cx - radius * 0.5f, cy, cx + radius * 0.5f, cy, scanPaint)

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    /**
     * Inicia la animación de pulso de la burbuja.
     *
     * La animación cambia según el estado del agente:
     * - IDLE: Pulso suave Cyber Silver
     * - THINKING: Pulso rápido Cyan Neon
     * - LISTENING: Onda expansiva Magenta
     * - SPEAKING: Glow blanco
     * - ERROR: Rojo intermitente
     * - BYPASS: Verde neón pulsante
     */
    private fun startPulseAnimation(bubble: ImageView) {
        scope.launch {
            while (isActive) {
                val status = _agentStatus.value
                val color = when (status) {
                    BubbleStatus.IDLE -> COLOR_CYBER_SILVER
                    BubbleStatus.THINKING -> COLOR_CYAN_NEON
                    BubbleStatus.LISTENING -> COLOR_MAGENTA_NEON
                    BubbleStatus.SPEAKING -> COLOR_WHITE_GLOW
                    BubbleStatus.ERROR -> COLOR_RED_WARNING
                    BubbleStatus.BYPASS_CHARGING -> COLOR_GREEN_NEON
                }

                // Animar alpha del borde
                withContext(Dispatchers.Main) {
                    bubble.setImageDrawable(createBubbleDrawable(bubble.width.let { if (it > 0) it else 168 }))
                }

                delay(when (status) {
                    BubbleStatus.THINKING -> 400
                    BubbleStatus.LISTENING -> 600
                    BubbleStatus.BYPASS_CHARGING -> 800
                    else -> 1500
                })
            }
        }
    }

    /**
     * Actualiza el estado visual de la burbuja.
     */
    fun updateStatus(status: BubbleStatus) {
        _agentStatus.value = status
        Log.d(TAG, "Estado actualizado: $status")
    }

    /**
     * Toggle del panel expandido.
     */
    private fun toggleExpanded() {
        if (isExpanded) {
            collapsePanel()
        } else {
            expandPanel()
        }
        isExpanded = !isExpanded
    }

    /**
     * Expande el panel de estado de la burbuja.
     *
     * Muestra:
     * - Estado actual del agente
     * - Persona activa
     * - Última acción
     * - Botón de Emergency Stop
     */
    @SuppressLint("SetTextI18n")
    private fun expandPanel() {
        val density = resources.displayMetrics.density

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE60D0D0D.toInt())  // Shadow Black semi-transparente
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )

            // Borde Cyber Silver
            background = createPanelBackground(
                (EXPANDED_WIDTH_DP * density).toInt(),
                (EXPANDED_HEIGHT_DP * density).toInt()
            )
        }

        // Título
        val title = TextView(this).apply {
            text = "◆ NUBIA AGENT ◆"
            setTextColor(COLOR_CYAN_NEON)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, COLOR_CYAN_NEON)
        }
        panel.addView(title)

        // Línea separadora
        val separator = View(this).apply {
            setBackgroundColor(COLOR_CYBER_SILVER)
            alpha = 0.3f
        }
        panel.addView(separator, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ))

        // Estado actual
        val statusText = TextView(this).apply {
            text = "ESTADO: ${_agentStatus.value.name}"
            setTextColor(COLOR_CYBER_SILVER)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        panel.addView(statusText)

        // Persona activa
        val personaText = TextView(this).apply {
            text = "PERSONA: ---"
            setTextColor(COLOR_CYBER_SILVER)
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }
        panel.addView(personaText)

        // Botón Emergency Stop
        val emergencyBtn = TextView(this).apply {
            text = "[ EMERGENCY STOP ]"
            setTextColor(COLOR_RED_WARNING)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, 0)
            setShadowLayer(4f, 0f, 0f, COLOR_RED_WARNING)
            setOnClickListener {
                emergencyStop()
            }
        }
        panel.addView(emergencyBtn)

        val params = WindowManager.LayoutParams(
            (EXPANDED_WIDTH_DP * density).toInt(),
            (EXPANDED_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 400
        }

        windowManager?.addView(panel, params)
        expandedView = panel
    }

    /**
     * Crea el fondo del panel expandido con borde Mecha.
     */
    private fun createPanelBackground(width: Int, height: Int): android.graphics.drawable.Drawable {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fondo oscuro
        val bgPaint = Paint().apply {
            color = COLOR_SHADOW_BLACK
            alpha = 230
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Borde Cyber Silver
        val borderPaint = Paint().apply {
            color = COLOR_CYBER_SILVER
            alpha = 100
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRect(0f, 0f, width.toFloat() - 1, height.toFloat() - 1, borderPaint)

        // Esquinas decorativas (efecto HUD)
        val cornerPaint = Paint().apply {
            color = COLOR_CYAN_NEON
            alpha = 150
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val cornerLen = 15f
        // Top-left
        canvas.drawLine(0f, cornerLen, 0f, 0f, cornerPaint)
        canvas.drawLine(0f, 0f, cornerLen, 0f, cornerPaint)
        // Top-right
        canvas.drawLine(width - cornerLen, 0f, width.toFloat(), 0f, cornerPaint)
        canvas.drawLine(width.toFloat(), 0f, width.toFloat(), cornerLen, cornerPaint)
        // Bottom-left
        canvas.drawLine(0f, height - cornerLen, 0f, height.toFloat(), cornerPaint)
        canvas.drawLine(0f, height.toFloat(), cornerLen, height.toFloat(), cornerPaint)
        // Bottom-right
        canvas.drawLine(width - cornerLen, height.toFloat(), width.toFloat(), height.toFloat(), cornerPaint)
        canvas.drawLine(width.toFloat(), height - cornerLen, width.toFloat(), height.toFloat(), cornerPaint)

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun collapsePanel() {
        expandedView?.let {
            windowManager?.removeView(it)
            expandedView = null
        }
    }

    private fun onSingleTap() {
        Log.i(TAG, "Tap: Activando comando de voz")
        // Activar modo de escucha
        PerceptionBus.tryEmit(PerceptionEvent.WakeWordDetected(confidence = 1.0f))
    }

    private fun onLongTap() {
        Log.i(TAG, "Long tap: Menú rápido")
        // TODO: Mostrar menú de opciones (Personas, Canvas, Config)
    }

    /**
     * Detiene todas las automatizaciones en curso.
     * Botón de emergencia — ejecución inmediata.
     */
    private fun emergencyStop() {
        Log.w(TAG, "⚠ EMERGENCY STOP activado")
        updateStatus(BubbleStatus.IDLE)
        // Notificar al AgentLoop para detener ejecución
        PerceptionBus.tryEmit(PerceptionEvent.HardwareStateUpdate(
            batteryLevel = 0,
            isCharging = false,
            isBypassCharging = false,
            latitude = null,
            longitude = null,
            currentActivity = com.nubiaagent.core.UserActivity.STILL,
            stepCount = 0
        ))
    }

    private fun removeBubbleView() {
        bubbleView?.let {
            windowManager?.removeView(it)
            bubbleView = null
        }
        collapsePanel()
    }

    private fun subscribeToEvents() {
        scope.launch {
            PerceptionBus.events.collect { event ->
                when (event) {
                    is PerceptionEvent.WakeWordDetected ->
                        updateStatus(BubbleStatus.LISTENING)
                    is PerceptionEvent.VoiceCommand ->
                        updateStatus(BubbleStatus.THINKING)
                    is PerceptionEvent.HardwareStateUpdate ->
                        if (event.isBypassCharging) updateStatus(BubbleStatus.BYPASS_CHARGING)
                    else -> {}
                }
            }
        }
    }
}

/**
 * Estados visuales de la burbuja Mecha.
 */
enum class BubbleStatus {
    IDLE,               // Inactivo — Cyber Silver
    THINKING,           // Procesando — Cyan Neon pulsante
    LISTENING,          // Escuchando — Magenta Neon
    SPEAKING,           // Respondiendo — White Glow
    ERROR,              // Error — Rojo intermitente
    BYPASS_CHARGING     // Curación en progreso — Verde Neon
}
