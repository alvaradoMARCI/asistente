package com.nubiaagent.ui.liveisland

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.nubiaagent.cognitive.persona.PersonaProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LiveIsland2: Notificaciones dinámicas en la parte superior de la pantalla.
 *
 * Inspirado en el Dynamic Island del iPhone, pero adaptado al
 * diseño Mecha Futurista del Nubia Neo 3 5G.
 *
 * ARQUITECTURA:
 *
 * ```
 * LiveIsland2
 *     │
 *     ├── Modo Compacto (barra superior)
 *     │   ├── Ancho: ~40% de la pantalla
 *     │   ├── Alto: 28dp
 *     │   ├── Posición: Top center
 *     │   └── Muestra: Icono + Texto corto + Progreso
 *     │
 *     ├── Modo Expandido (panel desplegable)
 *     │   ├── Ancho: ~90% de la pantalla
 *     │   ├── Alto: Variable (hasta 200dp)
 *     │   ├── Se activa con tap en modo compacto
 *     │   └── Muestra: Detalles completos de la tarea
 *     │
 *     └── Animaciones
 *         ├── Expand/collapse suave (300ms)
 *         ├── Progreso animado (barra cyan)
 *         ├── Pulse cuando hay actualización
 *         └── Auto-dismiss cuando la tarea completa
 * ```
 *
 * CASOS DE USO:
 * - "Curando memoria..." con barra de progreso
 * - "Enviando mensaje a Sarah..." con estado
 * - "Análisis de seguridad..." con resultados
 * - "Descargando modelo 3B..." con progreso
 * - "Briefing listo ◆" con tap para ver
 *
 * ESTÉTICA:
 * - Fondo: Shadow Black (#0D0D0D) con blur
 * - Borde: Cyber Silver sutil
 * - Acentos: Cyan Neon para progreso
 * - Tipografía: Monospace compacta
 */
class LiveIsland2 : Service() {

    companion object {
        private const val TAG = "NubiaAgent/LiveIsland"
        private const val CHANNEL_ID = "nubia_live_island"

        // Colores Mecha
        private const val COLOR_SHADOW_BLACK = 0xFF0D0D0D.toInt()
        private const val COLOR_CYBER_SILVER = 0xFFC0C0C0.toInt()
        private const val COLOR_CYAN_NEON = 0xFF00F0FF.toInt()
        private const val COLOR_GREEN_NEON = 0xFF00FF66.toInt()
        private const val COLOR_MAGENTA_NEON = 0xFFFF0066.toInt()
    }

    private var windowManager: WindowManager? = null
    private var compactView: View? = null
    private var expandedView: View? = null

    private val _currentTask = MutableStateFlow<LiveIslandTask?>(null)
    val currentTask: StateFlow<LiveIslandTask?> = _currentTask.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // Dismiss automático
    private var dismissRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1001, createForegroundNotification())
        Log.i(TAG, "Live Island 2.0 creado")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        removeViews()
        Log.i(TAG, "Live Island destruido")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW_TASK" -> {
                val title = intent.getStringExtra("title") ?: ""
                val subtitle = intent.getStringExtra("subtitle") ?: ""
                val progress = intent.getIntExtra("progress", -1)
                val type = intent.getStringExtra("type") ?: "info"
                showTask(title, subtitle, progress, TaskType.valueOf(type))
            }
            "UPDATE_PROGRESS" -> {
                val progress = intent.getIntExtra("progress", 0)
                val subtitle = intent.getStringExtra("subtitle") ?: ""
                updateProgress(progress, subtitle)
            }
            "DISMISS" -> dismissTask()
            "EXPAND" -> expand()
        }
        return START_STICKY
    }

    /**
     * Muestra una tarea en el Live Island.
     *
     * @param title Título de la tarea (ej: "Curando memoria")
     * @param subtitle Subtítulo o detalle (ej: "Procesando 42 hechos...")
     * @param progress Progreso 0-100, o -1 si es indeterminado
     * @param type Tipo de tarea (afecta color e icono)
     */
    @SuppressLint("SetTextI18n")
    fun showTask(
        title: String,
        subtitle: String = "",
        progress: Int = -1,
        type: TaskType = TaskType.INFO
    ) {
        _currentTask.value = LiveIslandTask(title, subtitle, progress, type)
        showCompactView(title, subtitle, progress, type)

        // Auto-dismiss después de 5 segundos si no hay progreso
        dismissRunnable?.let { handler.removeCallbacks(it) }
        if (progress < 0) {
            dismissRunnable = Runnable { dismissTask() }
            handler.postDelayed(dismissRunnable!!, 5000)
        }
    }

    /**
     * Actualiza el progreso de la tarea activa.
     */
    fun updateProgress(progress: Int, subtitle: String = "") {
        val task = _currentTask.value ?: return
        _currentTask.value = task.copy(progress = progress, subtitle = subtitle)

        // Actualizar vista compacta
        (compactView as? FrameLayout)?.let { container ->
            val subtitleView = container.findViewWithTag<TextView>("subtitle")
            subtitleView?.text = if (subtitle.isNotBlank()) subtitle else "${progress}%"

            val progressView = container.findViewWithTag<View>("progress")
            progressView?.let {
                val density = resources.displayMetrics.density
                val maxWidth = (140 * density).toInt()
                val params = it.layoutParams
                params.width = (maxWidth * progress / 100)
                it.layoutParams = params
            }
        }

        // Auto-dismiss al completar
        if (progress >= 100) {
            dismissRunnable = Runnable { dismissTask() }
            handler.postDelayed(dismissRunnable!!, 2000)
        }
    }

    /**
     * Descarta la tarea actual y oculta el Live Island.
     */
    fun dismissTask() {
        _currentTask.value = null
        removeViews()
    }

    /**
     * Muestra la vista compacta del Live Island.
     */
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun showCompactView(
        title: String,
        subtitle: String,
        progress: Int,
        type: TaskType
    ) {
        removeViews()

        val density = resources.displayMetrics.density
        val width = (160 * density).toInt()
        val height = (28 * density).toInt()

        val accentColor = when (type) {
            TaskType.PROCESSING -> COLOR_CYAN_NEON
            TaskType.COMMUNICATION -> COLOR_MAGENTA_NEON
            TaskType.SECURITY -> COLOR_CYAN_NEON
            TaskType.DOWNLOAD -> COLOR_GREEN_NEON
            TaskType.SUCCESS -> COLOR_GREEN_NEON
            TaskType.INFO -> COLOR_CYBER_SILVER
        }

        val icon = when (type) {
            TaskType.PROCESSING -> "◆"
            TaskType.COMMUNICATION -> "◉"
            TaskType.SECURITY -> "◈"
            TaskType.DOWNLOAD -> "▼"
            TaskType.SUCCESS -> "✓"
            TaskType.INFO -> "◇"
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(COLOR_SHADOW_BLACK)
            setPadding(
                (10 * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
                (4 * density).toInt()
            )
            background = createIslandBackground(width, height, accentColor)
        }

        // Icono
        val iconView = TextView(this).apply {
            text = icon
            setTextColor(accentColor)
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        container.addView(iconView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        })

        // Texto principal
        val titleView = TextView(this).apply {
            text = title.take(18)
            setTextColor(COLOR_CYBER_SILVER)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            tag = "title"
        }
        container.addView(titleView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = (18 * density).toInt()
        })

        // Progreso (barra)
        if (progress >= 0) {
            val progressBg = View(this).apply {
                setBackgroundColor(0x20C0C0C0.toInt())
            }
            val bgWidth = (140 * density).toInt()
            container.addView(progressBg, FrameLayout.LayoutParams(
                bgWidth, (3 * density).toInt()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (2 * density).toInt()
            })

            val progressFill = View(this).apply {
                setBackgroundColor(accentColor)
                tag = "progress"
            }
            container.addView(progressFill, FrameLayout.LayoutParams(
                (bgWidth * progress / 100), (3 * density).toInt()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (2 * density).toInt()
            })

            // Porcentaje
            val progressText = TextView(this).apply {
                text = "$progress%"
                setTextColor(accentColor)
                textSize = 8f
                typeface = Typeface.MONOSPACE
                tag = "subtitle"
            }
            container.addView(progressText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            })
        }

        // Tap para expandir
        container.setOnClickListener { expand() }

        val params = WindowManager.LayoutParams(
            width,
            height + (8 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
        }

        windowManager?.addView(container, params)
        compactView = container
    }

    /**
     * Expande el Live Island para mostrar detalles.
     */
    @SuppressLint("SetTextI18n")
    private fun expand() {
        val task = _currentTask.value ?: return
        val density = resources.displayMetrics.density

        val width = (320 * density).toInt()
        val height = (140 * density).toInt()

        val container = FrameLayout(this).apply {
            setBackgroundColor(COLOR_SHADOW_BLACK)
            background = createIslandBackground(width, height, COLOR_CYAN_NEON)
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
        }

        // Título expandido
        val titleView = TextView(this).apply {
            text = "◆ ${task.title}"
            setTextColor(COLOR_CYAN_NEON)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setShadowLayer(4f, 0f, 0f, COLOR_CYAN_NEON)
        }
        container.addView(titleView)

        // Detalle
        val detailView = TextView(this).apply {
            text = task.subtitle.ifBlank { if (task.progress >= 0) "Progreso: ${task.progress}%" else "En progreso..." }
            setTextColor(COLOR_CYBER_SILVER)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        container.addView(detailView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (24 * density).toInt()
        })

        // Barra de progreso expandida
        if (task.progress >= 0) {
            val progressBg = View(this).apply {
                setBackgroundColor(0x20C0C0C0.toInt())
            }
            container.addView(progressBg, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, (4 * density).toInt()
            ).apply {
                topMargin = (52 * density).toInt()
            })

            val progressFill = View(this).apply {
                setBackgroundColor(COLOR_CYAN_NEON)
            }
            container.addView(progressFill, FrameLayout.LayoutParams(
                (width * task.progress / 100), (4 * density).toInt()
            ).apply {
                topMargin = (52 * density).toInt()
            })
        }

        // Botón cerrar
        val closeBtn = TextView(this).apply {
            text = "[ CERRAR ]"
            setTextColor(COLOR_CYBER_SILVER)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setOnClickListener {
                collapse()
            }
        }
        container.addView(closeBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
        })

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
        }

        // Ocultar vista compacta
        compactView?.let { windowManager?.removeView(it) }

        windowManager?.addView(container, params)
        expandedView = container
    }

    private fun collapse() {
        expandedView?.let { windowManager?.removeView(it) }
        expandedView = null

        // Restaurar vista compacta
        val task = _currentTask.value
        if (task != null) {
            showCompactView(task.title, task.subtitle, task.progress, task.type)
        }
    }

    /**
     * Crea el fondo del Live Island con bordes redondeados y efecto Mecha.
     */
    private fun createIslandBackground(width: Int, height: Int, accentColor: Int): android.graphics.drawable.Drawable {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = 14f

        // Fondo oscuro
        val bgPaint = Paint().apply {
            color = COLOR_SHADOW_BLACK
            alpha = 230
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bgPaint)

        // Borde sutil
        val borderPaint = Paint().apply {
            color = accentColor
            alpha = 60
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat() - 1, height.toFloat() - 1, radius, radius, borderPaint)

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun removeViews() {
        compactView?.let { windowManager?.removeView(it); compactView = null }
        expandedView?.let { windowManager?.removeView(it); expandedView = null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Island",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificaciones dinámicas del agente"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NubiaAgent")
            .setContentText("Live Island activo")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}

/**
 * Tarea mostrada en el Live Island.
 */
data class LiveIslandTask(
    val title: String,
    val subtitle: String,
    val progress: Int,       // -1 = indeterminado
    val type: TaskType
)

/**
 * Tipos de tarea con colores asociados.
 */
enum class TaskType {
    PROCESSING,      // Cyan Neon — procesamiento en curso
    COMMUNICATION,   // Magenta Neon — enviando/recibiendo
    SECURITY,        // Cyan Neon — análisis de seguridad
    DOWNLOAD,        // Green Neon — descargando
    SUCCESS,         // Green Neon — tarea completada
    INFO             // Cyber Silver — información
}

private val Typeface.Companion.MONOSPACE get() = Typeface.MONOSPACE
