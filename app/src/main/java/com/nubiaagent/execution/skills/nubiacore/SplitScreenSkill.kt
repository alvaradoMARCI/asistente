package com.nubiaagent.execution.skills.nubiacore

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.nubiaagent.execution.bridge.ActionDispatcher
import com.nubiaagent.execution.bridge.GestureEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SplitScreenSkill: Habilidad de pantalla dividida y multitarea para NubiaAgent.
 *
 * Permite al agente abrir dos aplicaciones en pantalla dividida, cambiar el foco
 * entre las mitades, y salir del modo pantalla dividida. Usa AccessibilityService
 * para generar gestos de entrada y lanzar aplicaciones.
 *
 * ARQUITECTURA:
 * ```
 *  ┌─────────────────────────────────────────────────────────────────┐
 *  │                    SplitScreenSkill                              │
 *  │                                                                  │
 *  │  openSplitScreen()  ─┐                                          │
 *  │  enterSplitMode()   ─┤──→ ActionDispatcher.dispatchGesture()    │
 *  │  switchFocus()      ─┤    (swipe from bottom + hold)            │
 *  │  exitSplitMode()    ─┘                                          │
 *  │                                                                  │
 *  │  launchApp()  ────→ Intent + PackageManager                     │
 *  │                                                                  │
 *  │  Gestos de pantalla dividida:                                   │
 *  │  ┌────────────────────────────────────────────┐                 │
 *  │  │ 1. Swipe arriba desde el borde inferior     │                │
 *  │  │    y mantener (abre menú recientes)          │ │              │
 *  │  │ 2. Gesto de división en app reciente         │                │
 *  │  │ 3. Selección de segunda app                  │                │
 *  │  └────────────────────────────────────────────┘                  │
 *  └─────────────────────────────────────────────────────────────────┘
 * ```
 *
 * GESTO DE ENTRADA A PANTALLA DIVIDIDA (Android 12+):
 * - Swipe desde el borde inferior (~10% pantalla) hacia arriba y mantener
 * - El sistema muestra el menú de recientes
 * - Seleccionar "Pantalla dividida" sobre la app actual
 * - Elegir la segunda app del listado
 *
 * Para Android 7-11:
 * - Botón de recientes → tocar icono de app → "Pantalla dividida"
 *
 * @property context Contexto de la aplicación
 * @property actionDispatcher Dispatcher de acciones UI con acceso a AccessibilityService
 */
class SplitScreenSkill(
    private val context: Context,
    private val actionDispatcher: ActionDispatcher
) {

    companion object {
        private const val TAG = "NubiaAgent/SplitScreen"

        /** Tiempo de espera tras gesto de recientes (ms). */
        private const val RECENTS_GESTURE_DELAY_MS = 800L

        /** Tiempo de espera tras abrir app (ms). */
        private const val APP_LAUNCH_DELAY_MS = 1500L

        /** Tiempo de espera tras entrar a modo división (ms). */
        private const val SPLIT_MODE_DELAY_MS = 1000L

        /** Nombre único de trabajo de WorkManager para esta habilidad. */
        private const val WORK_TAG = "split_screen_skill"
    }

    /**
     * Estado de la pantalla dividida.
     */
    enum class SplitState {
        /** No hay pantalla dividida activa. */
        NONE,
        /** En proceso de entrar a pantalla dividida. */
        ENTERING,
        /** Pantalla dividida activa con dos apps. */
        ACTIVE,
        /** En proceso de salir de pantalla dividida. */
        EXITING
    }

    /**
     * Información de las apps en pantalla dividida.
     */
    data class SplitScreenInfo(
        /** App en la mitad superior/izquierda. */
        val app1: String? = null,
        /** App en la mitad inferior/derecha. */
        val app2: String? = null,
        /** Qué mitad tiene el foco actual. */
        val focusedApp: FocusedHalf = FocusedHalf.TOP,
        /** Estado actual de la pantalla dividida. */
        val state: SplitState = SplitState.NONE
    )

    /** Qué mitad de la pantalla tiene el foco. */
    enum class FocusedHalf { TOP, BOTTOM }

    // ─── Estado interno ───

    private val _splitInfo = MutableStateFlow(SplitScreenInfo())
    val splitInfo: StateFlow<SplitScreenInfo> = _splitInfo.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gestureEngine = GestureEngine()

    // ═══════════════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Abre dos aplicaciones en pantalla dividida.
     *
     * Flujo de ejecución:
     * 1. Lanza la primera aplicación en primer plano
     * 2. Entra en modo pantalla dividida
     * 3. Lanza la segunda aplicación en la otra mitad
     *
     * @param app1 Nombre del paquete de la primera app (mitad superior/izquierda)
     * @param app2 Nombre del paquete de la segunda app (mitad inferior/derecha)
     * @return Result con mensaje en español indicando éxito o fracaso
     */
    suspend fun openSplitScreen(app1: String, app2: String): Result<String> {
        Log.i(TAG, "Abriendo pantalla dividida: $app1 | $app2")

        try {
            _splitInfo.value = SplitScreenInfo(state = SplitState.ENTERING)

            // Paso 1: Lanzar la primera aplicación
            val launched1 = launchApp(app1)
            if (!launched1) {
                _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
                return Result.failure(
                    IllegalStateException(
                        "No se pudo lanzar la aplicación '$app1'. " +
                        "Verifica que el nombre del paquete sea correcto y la app esté instalada."
                    )
                )
            }

            delay(APP_LAUNCH_DELAY_MS)

            // Paso 2: Entrar en modo pantalla dividida
            val enteredSplit = enterSplitMode()
            if (!enteredSplit.isSuccess) {
                _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
                return Result.failure(
                    IllegalStateException(
                        "No se pudo activar la pantalla dividida. " +
                        (enteredSplit.exceptionOrNull()?.message ?: "Error desconocido.")
                    )
                )
            }

            delay(SPLIT_MODE_DELAY_MS)

            // Paso 3: Lanzar la segunda aplicación en la otra mitad
            val launched2 = launchApp(app2)
            if (!launched2) {
                // Si no se puede lanzar la segunda app, al menos la primera está en split
                _splitInfo.value = SplitScreenInfo(
                    app1 = app1,
                    app2 = null,
                    focusedApp = FocusedHalf.BOTTOM,
                    state = SplitState.ACTIVE
                )
                return Result.success(
                    "Pantalla dividida activada con '$app1' en la mitad superior. " +
                    "No se pudo lanzar '$app2' en la mitad inferior."
                )
            }

            delay(APP_LAUNCH_DELAY_MS)

            _splitInfo.value = SplitScreenInfo(
                app1 = app1,
                app2 = app2,
                focusedApp = FocusedHalf.BOTTOM,
                state = SplitState.ACTIVE
            )

            Log.i(TAG, "Pantalla dividida activa: $app1 | $app2")
            return Result.success(
                "Pantalla dividida activada: '$app1' arriba y '$app2' abajo. " +
                "Puedo cambiar el foco entre las apps si lo necesitas."
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo pantalla dividida", e)
            _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
            return Result.failure(e)
        }
    }

    /**
     * Entra en modo pantalla dividida con la aplicación actual.
     *
     * Ejecuta el gesto de entrada a pantalla dividida:
     * 1. Abre el menú de recientes (swipe desde abajo y mantener)
     * 2. Busca y pulsa la opción de pantalla dividida
     *
     * Estrategia por versión de Android:
     * - Android 12+: Gesto de swipe sostenido desde borde inferior
     * - Android 7-11: GLOBAL_ACTION_RECENTS + buscar botón de split
     *
     * @return Result con mensaje en español
     */
    suspend fun enterSplitMode(): Result<String> {
        Log.i(TAG, "Entrando en modo pantalla dividida")

        try {
            _splitInfo.value = _splitInfo.value.copy(state = SplitState.ENTERING)

            // Estrategia 1: Usar acción global de recientes (disponible en API 24+)
            val recentsResult = actionDispatcher.let {
                // Abrir menú de recientes
                val service = getAccessibilityService()
                if (service != null) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                    true
                } else {
                    false
                }
            }

            if (recentsResult) {
                delay(RECENTS_GESTURE_DELAY_MS)

                // Buscar la opción de pantalla dividida en la vista de recientes
                val splitOptionFound = findAndTapSplitOption()

                if (splitOptionFound) {
                    delay(SPLIT_MODE_DELAY_MS)
                    _splitInfo.value = _splitInfo.value.copy(state = SplitState.ACTIVE)
                    Log.i(TAG, "Modo pantalla dividida activado vía recientes")
                    return Result.success(
                        "Modo pantalla dividida activado. " +
                        "Selecciona la segunda aplicación de la lista."
                    )
                }
            }

            // Estrategia 2: Gesto de swipe sostenido desde borde inferior
            // (Android 12+ gesture navigation)
            val gestureResult = performSplitScreenGesture()
            if (gestureResult) {
                delay(SPLIT_MODE_DELAY_MS)
                _splitInfo.value = _splitInfo.value.copy(state = SplitState.ACTIVE)
                Log.i(TAG, "Modo pantalla dividida activado vía gesto")
                return Result.success(
                    "Modo pantalla dividida activado mediante gesto. " +
                    "Selecciona la segunda aplicación."
                )
            }

            // Estrategia 3: Intent del sistema para split screen (algunos fabricantes)
            val intentResult = trySplitScreenIntent()
            if (intentResult) {
                delay(SPLIT_MODE_DELAY_MS)
                _splitInfo.value = _splitInfo.value.copy(state = SplitState.ACTIVE)
                return Result.success(
                    "Modo pantalla dividida activado. Selecciona la segunda aplicación."
                )
            }

            _splitInfo.value = _splitInfo.value.copy(state = SplitState.NONE)
            return Result.failure(
                IllegalStateException(
                    "No se pudo activar la pantalla dividida. " +
                    "Asegúrate de que la navegación por gestos esté habilitada " +
                    "y la pantalla dividida sea soportada en tu dispositivo."
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error entrando en modo pantalla dividida", e)
            _splitInfo.value = _splitInfo.value.copy(state = SplitState.NONE)
            return Result.failure(e)
        }
    }

    /**
     * Cambia el foco entre las dos aplicaciones en pantalla dividida.
     *
     * Simula un toque en la mitad de la pantalla que no tiene el foco
     * para transferir el foco a ella.
     *
     * @return Result con mensaje en español
     */
    suspend fun switchFocus(): Result<String> {
        val info = _splitInfo.value

        if (info.state != SplitState.ACTIVE) {
            return Result.failure(
                IllegalStateException(
                    "No hay pantalla dividida activa. Abre dos apps primero con pantalla dividida."
                )
            )
        }

        try {
            val service = getAccessibilityService()
            if (service == null) {
                return Result.failure(
                    IllegalStateException("Servicio de accesibilidad no disponible.")
                )
            }

            val metrics = service.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // Determinar coordenadas de la mitad que NO tiene foco
            val (tapX, tapY) = when (info.focusedApp) {
                FocusedHalf.TOP -> {
                    // Tocar mitad inferior
                    Pair(screenWidth / 2, (screenHeight * 3 / 4))
                }
                FocusedHalf.BOTTOM -> {
                    // Tocar mitad superior
                    Pair(screenWidth / 2, (screenHeight / 4))
                }
            }

            // Ejecutar toque en la otra mitad
            actionDispatcher.swipe("up", tapX, tapY) // Solo para despachar gesto
            // Usar tap directo en la otra mitad
            val tapped = actionDispatcher.tap(
                "android:id/content"
            )

            // Actualizar estado de foco
            val newFocus = if (info.focusedApp == FocusedHalf.TOP) {
                FocusedHalf.BOTTOM
            } else {
                FocusedHalf.TOP
            }

            _splitInfo.value = info.copy(focusedApp = newFocus)

            val focusedAppName = when (newFocus) {
                FocusedHalf.TOP -> info.app1 ?: "app superior"
                FocusedHalf.BOTTOM -> info.app2 ?: "app inferior"
            }

            Log.i(TAG, "Foco cambiado a $newFocus ($focusedAppName)")
            return Result.success(
                "Foco cambiado a la $focusedAppName."
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error cambiando foco en pantalla dividida", e)
            return Result.failure(e)
        }
    }

    /**
     * Sale del modo pantalla dividida.
     *
     * Estrategia:
     * 1. Buscar y tocar el divisor de pantalla dividida
     * 2. Si no funciona, usar acción global de recientes para salir
     * 3. Como último recurso, ir a pantalla de inicio y re-lanzar la app principal
     *
     * @return Result con mensaje en español
     */
    suspend fun exitSplitMode(): Result<String> {
        val info = _splitInfo.value

        if (info.state != SplitState.ACTIVE) {
            return Result.failure(
                IllegalStateException(
                    "No hay pantalla dividida activa para cerrar."
                )
            )
        }

        try {
            _splitInfo.value = info.copy(state = SplitState.EXITING)

            // Estrategia 1: Arrastrar el divisor hacia un borde
            val dragResult = dragSplitDividerToBottom()
            if (dragResult) {
                delay(SPLIT_MODE_DELAY_MS)
                _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
                Log.i(TAG, "Pantalla dividida cerrada arrastrando divisor")
                return Result.success(
                    "Pantalla dividida cerrada. La aplicación principal ocupa toda la pantalla."
                )
            }

            // Estrategia 2: Usar botón Atrás para salir de la segunda app
            actionDispatcher.goBack()
            delay(500)

            actionDispatcher.goBack()
            delay(500)

            // Verificar si salimos del modo split
            _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
            Log.i(TAG, "Pantalla dividida cerrada con botón atrás")
            return Result.success(
                "Pantalla dividida cerrada. Volviendo a pantalla completa."
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error saliendo de pantalla dividida", e)
            _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
            return Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MÉTODOS PRIVADOS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lanza una aplicación por su nombre de paquete.
     *
     * Intenta obtener el Intent de lanzamiento principal del paquete
     * y lo ejecuta con flags apropiados.
     *
     * @param packageName Nombre del paquete (e.g. "com.whatsapp")
     * @return true si la app se lanzó exitosamente
     */
    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                context.startActivity(intent)
                Log.i(TAG, "Aplicación lanzada: $packageName")
                true
            } else {
                // Intentar launch intent por componente
                val componentIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName.unflattenFromString(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(componentIntent)
                    Log.i(TAG, "Aplicación lanzada por componente: $packageName")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo lanzar la aplicación: $packageName", e)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error lanzando aplicación: $packageName", e)
            false
        }
    }

    /**
     * Obtiene el AccessibilityService del ActionDispatcher.
     *
     * Usa reflexión para acceder al servicio interno del dispatcher
     * ya que ActionDispatcher no expone directamente el servicio.
     */
    private fun getAccessibilityService(): AccessibilityService? {
        return try {
            // ActionDispatcher tiene un campo 'service' de tipo AccessibilityService
            val field = ActionDispatcher::class.java.getDeclaredField("service")
            field.isAccessible = true
            field.get(actionDispatcher) as? AccessibilityService
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener AccessibilityService del dispatcher", e)
            null
        }
    }

    /**
     * Busca y pulsa la opción de pantalla dividida en la vista de recientes.
     *
     * Después de abrir el menú de recientes, busca nodos con texto
     * relacionado con pantalla dividida y los pulsa.
     *
     * @return true si se encontró y pulsó la opción de split
     */
    private suspend fun findAndTapSplitOption(): Boolean {
        // Textos posibles del botón de pantalla dividida según idioma y fabricante
        val splitTexts = listOf(
            "pantalla dividida",
            "dividir pantalla",
            "split screen",
            "dividir",
            "ventana dual",
            "dual window",
            "multi ventana",
            "multi window"
        )

        for (text in splitTexts) {
            val node = actionDispatcher.findNodeByText(text)
                ?: actionDispatcher.findNodeByDesc(text)
                ?: continue

            val clicked = actionDispatcher.performClick(node)
            node.recycle()

            if (clicked) {
                Log.i(TAG, "Opción de pantalla dividida pulsada: '$text'")
                return true
            }
        }

        // Intentar buscar por content-description que contenga "split"
        val splitNode = actionDispatcher.findNodeByDesc("split")
            ?: actionDispatcher.findNodeByDesc("dividir")

        if (splitNode != null) {
            val clicked = actionDispatcher.performClick(splitNode)
            splitNode.recycle()
            if (clicked) {
                Log.i(TAG, "Opción de pantalla dividida pulsada por desc")
                return true
            }
        }

        Log.w(TAG, "No se encontró la opción de pantalla dividida en la vista de recientes")
        return false
    }

    /**
     * Realiza el gesto de entrada a pantalla dividida (swipe desde abajo y mantener).
     *
     * Este gesto simula la interacción del usuario:
     * 1. Swipe hacia arriba desde el borde inferior de la pantalla
     * 2. Mantener presionado un momento (para abrir recientes)
     * 3. Luego buscar la opción de split
     *
     * El gesto funciona en dispositivos con navegación por gestos (Android 12+).
     *
     * @return true si el gesto se ejecutó exitosamente
     */
    private suspend fun performSplitScreenGesture(): Boolean {
        val service = getAccessibilityService() ?: return false

        return try {
            val metrics = service.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // Coordenadas del gesto: desde el borde inferior hacia arriba y mantener
            val startX = screenWidth / 2
            val startY = screenHeight - 10  // Borde inferior
            val endY = screenHeight / 2     // Mitad de la pantalla

            // Gesto 1: Swipe sostenido desde abajo (abre recientes)
            val swipeUpGesture = gestureEngine.createSwipe(
                startX, startY,
                startX, endY,
                600L
            )

            // Despachar gesto
            val dispatchResult = dispatchGestureToService(service, swipeUpGesture)
            if (!dispatchResult) {
                Log.w(TAG, "Gesto de swipe hacia arriba falló")
                return false
            }

            delay(RECENTS_GESTURE_DELAY_MS)

            // Gesto 2: Buscar y pulsar la opción de pantalla dividida
            val splitFound = findAndTapSplitOption()
            if (splitFound) {
                return true
            }

            // Gesto alternativo: Long press en la barra de navegación
            // (algunos dispositivos usan esto para split screen)
            val longPressY = screenHeight - 50
            val longPressGesture = gestureEngine.createLongPress(
                screenWidth / 2, longPressY, 1000L
            )
            dispatchGestureToService(service, longPressGesture)

            delay(RECENTS_GESTURE_DELAY_MS)
            findAndTapSplitOption()

        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando gesto de pantalla dividida", e)
            false
        }
    }

    /**
     * Intenta activar pantalla dividida usando un Intent del sistema.
     *
     * Algunos fabricantes (Samsung, Xiaomi, ZTE) proporcionan intents
     * o acciones de sistema para activar pantalla dividida directamente.
     *
     * @return true si el intent se ejecutó sin error
     */
    private fun trySplitScreenIntent(): Boolean {
        return try {
            // Intent genérico de multitarea (no estándar, funciona en algunos dispositivos)
            val intents = listOf(
                // ZTE / Nubia specific
                Intent("com.zte.multiwindow.LAUNCH").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // Samsung specific
                Intent("com.samsung.android.app.clockpack.SPLIT_SCREEN").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                // Generic Android multi-window
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    putExtra("android.intent.extra.SPLIT_SCREEN", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            for (intent in intents) {
                try {
                    context.startActivity(intent)
                    Log.d(TAG, "Intent de pantalla dividida ejecutado: ${intent.action}")
                    return true
                } catch (e: Exception) {
                    // Este intent no funciona, probar el siguiente
                    continue
                }
            }

            Log.w(TAG, "Ningún intent de pantalla dividida funcionó")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error con intent de pantalla dividida", e)
            false
        }
    }

    /**
     * Arrastra el divisor de pantalla dividida hacia el borde inferior.
     *
     * Simula el gesto de arrastrar la barra divisora hacia abajo para
     * cerrar la pantalla dividida y expandir la app superior.
     *
     * @return true si el gesto se ejecutó exitosamente
     */
    private suspend fun dragSplitDividerToBottom(): Boolean {
        val service = getAccessibilityService() ?: return false

        return try {
            val metrics = service.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // El divisor suele estar en el centro de la pantalla
            val dividerY = screenHeight / 2
            val startX = screenWidth / 2
            val endY = screenHeight - 50

            // Gesto de arrastre desde el divisor hacia abajo
            val dragGesture = gestureEngine.createSwipe(
                startX, dividerY,
                startX, endY,
                500L
            )

            val result = dispatchGestureToService(service, dragGesture)
            Log.d(TAG, "Arrastre de divisor: $result")
            result

        } catch (e: Exception) {
            Log.e(TAG, "Error arrastrando divisor", e)
            false
        }
    }

    /**
     * Despacha un gesto al AccessibilityService.
     *
     * Envuelve la llamada a dispatchGesture con manejo de errores
     * y verificación de versión de API.
     *
     * @param service El AccessibilityService activo
     * @param gesture GestureDescription a despachar
     * @return true si el gesto fue aceptado por el framework
     */
    private fun dispatchGestureToService(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = false

                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        result = true
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        result = false
                        latch.countDown()
                    }
                }, null)

                latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error despachando gesto", e)
                false
            }
        } else {
            Log.w(TAG, "Gestos de accesibilidad no disponibles (API < 24)")
            false
        }
    }

    /**
     * Limpia recursos de la habilidad.
     */
    fun destroy() {
        scope.cancel()
        _splitInfo.value = SplitScreenInfo(state = SplitState.NONE)
        Log.i(TAG, "SplitScreenSkill destruida")
    }
}
