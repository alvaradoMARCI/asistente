package com.nubiaagent.orchestration

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * SplitScreenController: Control de pantalla dividida del Nubia Neo 3 5G.
 *
 * FUNCIONALIDAD:
 * - Activar modo pantalla dividida automáticamente
 * - Abrir el Canvas del agente en una mitad y la app del usuario en la otra
 * - Detectar gesto de 3 dedos hacia arriba (Split Screen nativo de Nubia)
 * - Controlar la posición y tamaño de las ventanas divididas
 *
 * CASOS DE USO:
 * - "Abre YouTube en pantalla dividida con mi Canvas"
 * - "Muestra el código arriba y la preview abajo"
 * - "Pon el briefing en la mitad superior"
 * - Gesto de 3 dedos hacia arriba → activar split con Canvas
 *
 * ARQUITECTURA:
 *
 * ```
 * SplitScreenController
 *     │
 *     ├── Detección de Gesto (3 dedos ↑)
 *     │   ├── AccessibilityService captura gestos
 *     │   ├── Filtrar gesto de 3 dedos vertical
 *     │   └── Activar split screen automáticamente
 *     │
 *     ├── Activación de Split Screen
 *     │   ├── Android 12+: WindowManager.splitScreen
 *     │   ├── Nubia nativo: Intent com.zte.split
 *     │   └── Fallback: ActivityOptions con launchBounds
 *     │
 *     └── Posicionamiento del Canvas
 *         ├── Top half: Canvas (50% superior)
 *         ├── Bottom half: App del usuario (50% inferior)
 *         └── Ajustable: 40/60, 50/50, 60/40
 * ```
 */
class SplitScreenController(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/SplitScreen"

        // Configuraciones de split
        const val SPLIT_50_50 = 0.5f
        const val SPLIT_40_60 = 0.4f    // Canvas arriba 40%
        const val SPLIT_60_40 = 0.6f    // Canvas arriba 60%

        // Intents Nubia
        private const val ZTE_SPLIT_ACTION = "com.zte.split.ENTER"
    }

    private var isSplitActive = false
    private var currentRatio = SPLIT_50_50

    /**
     * Activa el modo pantalla dividida con el Canvas del agente.
     *
     * @param ratio Proporción del Canvas (SPLIT_50_50, SPLIT_40_60, SPLIT_60_40)
     * @param canvasOnTop true = Canvas arriba, false = Canvas abajo
     */
    fun enterSplitScreen(
        ratio: Float = SPLIT_50_50,
        canvasOnTop: Boolean = true
    ): Boolean {
        if (isSplitActive) {
            Log.d(TAG, "Split screen ya activo")
            return true
        }

        currentRatio = ratio

        try {
            // Intentar Nubia nativo primero
            val zteIntent = Intent(ZTE_SPLIT_ACTION).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("ratio", ratio)
                putExtra("position", if (canvasOnTop) "top" else "bottom")
            }

            if (zteIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(zteIntent)
                isSplitActive = true
                Log.i(TAG, "Split screen via ZTE nativo (ratio: $ratio)")
                return true
            }

            // Fallback: Usar ActivityOptions con launchBounds
            return enterSplitScreenFallback(ratio, canvasOnTop)

        } catch (e: Exception) {
            Log.e(TAG, "Error activando split screen", e)
            return false
        }
    }

    /**
     * Fallback para activar split screen usando launchBounds.
     */
    private fun enterSplitScreenFallback(ratio: Float, canvasOnTop: Boolean): Boolean {
        try {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val splitY = (screenHeight * ratio).toInt()

            // Bounds del Canvas
            val canvasBounds = if (canvasOnTop) {
                Rect(0, 0, screenWidth, splitY)
            } else {
                Rect(0, splitY, screenWidth, screenHeight)
            }

            // Lanzar Canvas en su bounds
            val canvasIntent = Intent(context, com.nubiaagent.ui.canvas.CanvasController::class.java).apply {
                action = "SHOW"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val launcherBounds = canvasBounds
                canvasIntent.putExtra(android.window.SizeClass_bounds, launcherBounds)
            }

            context.startActivity(canvasIntent)
            isSplitActive = true
            Log.i(TAG, "Split screen via launchBounds (ratio: $ratio)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error en fallback de split screen", e)
            return false
        }
    }

    /**
     * Sale del modo pantalla dividida.
     */
    fun exitSplitScreen() {
        if (!isSplitActive) return

        try {
            // Intentar Nubia nativo
            val exitIntent = Intent("com.zte.split.EXIT").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (exitIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(exitIntent)
            }

            isSplitActive = false
            Log.i(TAG, "Split screen desactivado")

        } catch (e: Exception) {
            Log.e(TAG, "Error saliendo de split screen", e)
        }
    }

    /**
     * Detecta si el gesto de 3 dedos hacia arriba fue realizado.
     *
     * Se llama desde el AccessibilityService cuando detecta un patrón
     * de touch que coincide con 3 puntos de contacto simultáneos
     * moviéndose hacia arriba.
     *
     * @param touchPoints Lista de puntos de contacto
     * @return true si el gesto fue detectado
     */
    fun detectThreeFingerSwipeUp(touchPoints: List<TouchPoint>): Boolean {
        if (touchPoints.size != 3) return false

        // Verificar que los 3 dedos se mueven hacia arriba
        val allMovingUp = touchPoints.all { it.deltaY < -100 }  // Al menos 100px hacia arriba
        val horizontallySpread = touchPoints.map { it.x }.let { xs ->
            (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f) > 150f  // Al menos 150px de separación horizontal
        }

        return allMovingUp && horizontallySpread
    }

    /**
     * Ajusta la proporción del split screen.
     */
    fun adjustRatio(newRatio: Float) {
        if (!isSplitActive) return
        currentRatio = newRatio
        // TODO: Reconfigurar bounds de las ventanas
        Log.d(TAG, "Ratio ajustado: $newRatio")
    }

    /**
     * Verifica si el dispositivo soporta pantalla dividida.
     */
    fun isSplitScreenSupported(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            am.isLowRamDevice.not()
        } else {
            true
        }
    }

    fun isActive(): Boolean = isSplitActive

    fun destroy() {
        exitSplitScreen()
    }
}

/**
 * Representación de un punto de contacto para detección de gestos.
 */
data class TouchPoint(
    val x: Float,
    val y: Float,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)
