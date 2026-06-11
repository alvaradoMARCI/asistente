package com.nubiaagent.perception.vision

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent
import com.nubiaagent.core.UiElement
import com.nubiaagent.core.UiViewType
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * ScreenObserver: Servicio de Visión de Sistema para NubiaAgent.
 *
 * Implementa un sistema de percepción visual dual que permite al agente
 * "ver" y comprender la interfaz del dispositivo en tiempo real:
 *
 * ESTRATEGIA PRIMARIA - Árbol de Accesibilidad:
 *   Utiliza AccessibilityService para obtener el árbol jerárquico de la UI
 *   en formato equivalente a uiautomator dump. Este método es extremadamente
 *   eficiente (XML ligero en lugar de imágenes) y proporciona semántica
 *   directa (texto, tipo de control, estado).
 *
 *   El filtrado inteligente reduce el árbol completo (que puede tener 500+
 *   nodos) a los ~40 elementos más relevantes para el LLM, eliminando:
 *   - Contenedores decorativos sin información
 *   - Elementos duplicados o redundantes
 *   - Nodos de layout puro sin interacción
 *
 * ESTRATEGIA FALLBACK - Screenshot + Computer Vision:
 *   Cuando el árbol de accesibilidad está vacío o incompleto (común en
 *   apps de Flutter, WebViews, juegos, o apps que no exponen nodos),
 *   se captura una screenshot que se prepara para análisis por un modelo
 *   de visión local como Llama 3.2 Vision. La captura se redimensiona
 *   a una resolución óptima para el modelo (ej. 672x672) aprovechando
 *   los 20GB de RAM del Nubia Neo 3 5G para el procesamiento.
 *
 * OPTIMIZACIÓN PARA UNISOC T8300 / NeoTurbo:
 *   - El filtrado del árbol XML se realiza en el hilo principal (rápido)
 *   - La captura y redimensionado de screenshots se hace en IO
 *   - El análisis por modelo de visión se delega a la Capa Cognitiva
 *   - Se usa throttling para no saturar el bus con eventos de cambio
 */
class ScreenObserver : AccessibilityService() {

    companion object {
        private const val TAG = "NubiaAgent/Vision"

        // Configuración de filtrado
        private const val MAX_UI_ELEMENTS = 40
        private const val MIN_NODE_IMPORTANCE = 0.3f

        // Throttling de eventos
        private const val MIN_EVENT_INTERVAL_MS = 500L

        // Screenshot fallback
        private const val SCREENSHOT_WIDTH = 672
        private const val SCREENSHOT_HEIGHT = 672
        private const val SCREENSHOT_QUALITY = 80

        // Paquetes donde el árbol de accesibilidad suele estar vacío
        private val FALLBACK_PACKAGES = setOf(
            "com.google.android.webview",
            "org.chromium.webview_shell",
            // Flutter apps típicamente no exponen bien el árbol
        )

        // Clases de vista que son interactivas/informativas
        private val INTERACTIVE_CLASSES = setOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "android.widget.EditText",
            "android.widget.CheckBox",
            "android.widget.Switch",
            "android.widget.RadioButton",
            "android.widget.Spinner",
            "android.widget.SeekBar",
            "android.widget.TextView",
            "android.widget.ImageView",
            "android.widget.ListView",
            "android.widget.RecyclerView",
            "androidx.recyclerview.widget.RecyclerView",
            "android.widget.TabHost",
            "android.widget.TabWidget",
            "androidx.viewpager.widget.ViewPager",
            "androidx.viewpager2.widget.ViewPager2",
            "com.google.android.material.textfield.TextInputEditText",
            "com.google.android.material.button.MaterialButton",
            "com.google.android.material.switchmaterial.SwitchMaterial",
            // WhatsApp
            "com.whatsapp.Conversation",
            // Telegram
            "org.telegram.messenger",
        )

        // Elementos que siempre se incluyen independientemente de su clase
        private val ALWAYS_INCLUDE_ACTIONS = setOf(
            AccessibilityNodeInfo.ACTION_CLICK,
            AccessibilityNodeInfo.ACTION_LONG_CLICK,
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            AccessibilityNodeInfo.ACTION_FOCUS,
            AccessibilityNodeInfo.ACTION_EXPAND,
            AccessibilityNodeInfo.ACTION_COLLAPSE,
        )

        fun isRunning(): Boolean = instance != null

        private var instance: ScreenObserver? = null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastEventTime = 0L
    private var lastPackageName = ""

    // Screenshot fallback
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Configurar el servicio para capturar todos los eventos relevantes
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_NODES or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        Log.i(TAG, "ScreenObserver conectado y configurado")
    }

    /**
     * Callback principal del AccessibilityService.
     *
     * Se invoca cuando cualquier elemento de la UI cambia en el dispositivo.
     * Aplica throttling para no saturar el PerceptionBus con eventos
     * demasiado frecuentes (mínimo 500ms entre eventos).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < MIN_EVENT_INTERVAL_MS) return

        val packageName = event.packageName?.toString() ?: return
        lastEventTime = currentTime

        // Solo procesar si cambió el paquete o pasó suficiente tiempo
        if (packageName == lastPackageName &&
            currentTime - lastEventTime < MIN_EVENT_INTERVAL_MS * 2) {
            return
        }

        lastPackageName = packageName

        serviceScope.launch {
            processScreen(packageName)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenObserver interrumpido")
    }

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        releaseScreenshotResources()
        super.onDestroy()
    }

    /**
     * Procesa la pantalla actual: primero intenta el árbol de accesibilidad,
     * y si está vacío, recurre al screenshot fallback.
     */
    private suspend fun processScreen(packageName: String) {
        // Emitir evento de cambio de pantalla
        PerceptionBus.emit(
            PerceptionEvent.ScreenChanged(
                packageName = packageName,
                activityName = "" // Se llenará desde el árbol
            )
        )

        // Intentar árbol de accesibilidad primero
        val uiElements = extractUiElements()

        if (uiElements.isNotEmpty()) {
            // Estrategia primaria exitosa
            val rawSize = getRawTreeSize()
            Log.d(TAG, "Árbol de accesibilidad: ${uiElements.size} elementos filtrados de ~${rawSize} nodos")

            PerceptionBus.emit(
                PerceptionEvent.UiElementsReady(
                    elements = uiElements,
                    packageName = packageName,
                    rawXmlSize = rawSize
                )
            )
        } else if (shouldUseScreenshotFallback(packageName)) {
            // Fallback: captura de pantalla
            Log.d(TAG, "Árbol vacío para $packageName, usando screenshot fallback")
            captureAndEmitScreenshot()
        }
    }

    /**
     * ESTRATEGIA PRIMARIA: Extrae y filtra elementos interactivos del árbol
     * de accesibilidad.
     *
     * El filtrado inteligente funciona en 3 pasadas:
     *
     * Pasada 1 - Recolección: Se recorre el árbol completo recolectando
     *   nodos con score de importancia basado en: interactividad,
     *   contenido textual, descripción, y tipo de elemento.
     *
     * Pasada 2 - Ordenamiento: Los nodos se ordenan por importancia
     *   descendente. Esto prioriza botones, campos de texto y elementos
     *   con contenido sobre contenedores vacíos.
     *
     * Pasada 3 - Truncación: Se toman los MAX_UI_ELEMENTS (40) más
     *   importantes. Este límite evita que el contexto enviado al LLM
     *   sea demasiado grande, lo que causaría alucinaciones y latencia.
     */
    private fun extractUiElements(): List<UiElement> {
        val rootNode = rootInActiveWindow ?: return emptyList()

        val candidates = mutableListOf<ScoredNode>()
        val totalNodes = mutableListOf<AccessibilityNodeInfo>()

        // Pasada 1: Recolectar todos los nodos con scoring
        collectNodesRecursive(rootNode, candidates, totalNodes)

        // Pasada 2: Ordenar por importancia
        candidates.sortByDescending { it.score }

        // Pasada 3: Tomar los top MAX_UI_ELEMENTS
        val selected = candidates.take(MAX_UI_ELEMENTS)

        // Mapear a UiElement
        val elements = selected.mapNotNull { scored ->
            mapToUiElement(scored.node)
        }

        // Limpiar referencias
        totalNodes.forEach { it.recycle() }

        return elements
    }

    /**
     * Recorre el árbol de accesibilidad recursivamente, asignando
     * un score de importancia a cada nodo.
     */
    private fun collectNodesRecursive(
        node: AccessibilityNodeInfo,
        candidates: MutableList<ScoredNode>,
        totalNodes: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        totalNodes.add(node)

        val score = calculateNodeImportance(node, depth)
        if (score >= MIN_NODE_IMPORTANCE) {
            candidates.add(ScoredNode(node, score))
        }

        // Recorrer hijos
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesRecursive(child, candidates, totalNodes, depth + 1)
        }
    }

    /**
     * Calcula la importancia de un nodo para el contexto del LLM.
     *
     * Factores positivos:
     * - Es interactivo (clickable, scrollable, editable): +0.4
     * - Tiene texto visible: +0.3
     * - Tiene contentDescription: +0.2
     * - Es de una clase conocida (Button, EditText): +0.3
     * - Tiene acciones disponibles: +0.2
     *
     * Factores negativos:
     * - Es un contenedor sin texto ni interacción: -0.3
     * - Está muy profundo en la jerarquía (depth > 10): -0.1 por nivel
     * - No es visible para el usuario: -1.0 (descartado)
     */
    private fun calculateNodeImportance(node: AccessibilityNodeInfo, depth: Int): Float {
        // Descartar nodos no visibles
        if (!node.isVisibleToUser) return -1f

        var score = 0f

        // Interactividad
        if (node.isClickable) score += 0.25f
        if (node.isScrollable) score += 0.2f
        if (node.isEditable) score += 0.3f
        if (node.isCheckable) score += 0.15f
        if (node.isFocusable) score += 0.1f

        // Acciones disponibles
        val actions = node.actionList
        if (actions != null && actions.isNotEmpty()) {
            val hasImportantAction = actions.any { action ->
                action.id in ALWAYS_INCLUDE_ACTIONS
            }
            if (hasImportantAction) score += 0.2f
        }

        // Contenido textual
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) score += 0.3f

        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrBlank()) score += 0.2f

        // Clase conocida
        val className = node.className?.toString() ?: ""
        if (className in INTERACTIVE_CLASSES) score += 0.3f

        // Tipo de elemento
        val viewType = classifyViewType(node, className)
        if (viewType != UiViewType.CONTAINER && viewType != UiViewType.UNKNOWN) {
            score += 0.15f
        }

        // Penalizar nodos muy profundos (suelen ser decorativos)
        if (depth > 10) {
            score -= (depth - 10) * 0.05f
        }

        // Penalizar contenedores sin contenido
        if (className.contains("Layout") || className.contains("FrameLayout")) {
            if (text.isNullOrBlank() && contentDesc.isNullOrBlank() && !node.isClickable) {
                score -= 0.3f
            }
        }

        return score.coerceIn(-1f, 1f)
    }

    /**
     * Clasifica un nodo en su tipo de vista semántico.
     * Esto ayuda al LLM a entender qué tipo de elemento es
     * sin depender del nombre de clase exacto.
     */
    private fun classifyViewType(node: AccessibilityNodeInfo, className: String): UiViewType {
        return when {
            node.isEditable -> UiViewType.TEXT_FIELD
            className.contains("Button", ignoreCase = true) -> UiViewType.BUTTON
            className.contains("EditText", ignoreCase = true) -> UiViewType.TEXT_FIELD
            className.contains("Checkbox", ignoreCase = true) -> UiViewType.CHECKBOX
            className.contains("Switch", ignoreCase = true) -> UiViewType.SWITCH
            className.contains("Tab", ignoreCase = true) -> UiViewType.TAB
            className.contains("List", ignoreCase = true) ||
                    className.contains("RecyclerView", ignoreCase = true) -> UiViewType.LIST
            className.contains("ImageView", ignoreCase = true) -> UiViewType.IMAGE
            className.contains("TextView", ignoreCase = true) -> UiViewType.TEXT
            node.isScrollable -> UiViewType.LIST
            node.isClickable && node.isCheckable -> UiViewType.CHECKBOX
            node.isClickable -> UiViewType.BUTTON
            className.contains("Layout", ignoreCase = true) -> UiViewType.CONTAINER
            else -> UiViewType.UNKNOWN
        }
    }

    /**
     * Mapea un AccessibilityNodeInfo a un UiElement compacto.
     * Solo extrae los campos que el LLM necesita para tomar decisiones.
     */
    private fun mapToUiElement(node: AccessibilityNodeInfo): UiElement? {
        val className = node.className?.toString() ?: return null
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        return UiElement(
            className = className.substringAfterLast("."),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEditable = node.isEditable,
            isCheckable = node.isCheckable,
            isChecked = if (node.isCheckable) node.isChecked else null,
            viewType = classifyViewType(node, className)
        )
    }

    /**
     * Obtiene el tamaño estimado del árbol completo de accesibilidad.
     */
    private fun getRawTreeSize(): Int {
        val rootNode = rootInActiveWindow ?: return 0
        return countNodes(rootNode)
    }

    private fun countNodes(node: AccessibilityNodeInfo): Int {
        var count = 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += countNodes(child)
        }
        return count
    }

    /**
     * Determina si se debe usar el fallback de screenshot.
     *
     * Se activa cuando:
     * 1. El paquete está en la lista de fallback conocida
     * 2. El árbol de accesibilidad está vacío
     * 3. La clase del nodo raíz indica WebView o Flutter
     */
    private fun shouldUseScreenshotFallback(packageName: String): Boolean {
        if (packageName in FALLBACK_PACKAGES) return true

        val rootNode = rootInActiveWindow ?: return true
        val rootClass = rootNode.className?.toString() ?: ""

        // WebViews y Flutter no exponen bien el árbol
        return rootClass.contains("WebView", ignoreCase = true) ||
                rootClass.contains("FlutterView", ignoreCase = true) ||
                rootClass.contains("PlatformView", ignoreCase = true)
    }

    /**
     * ESTRATEGIA FALLBACK: Captura de pantalla para Computer Vision.
     *
     * Toma una screenshot del dispositivo y la prepara para ser analizada
     * por un modelo de visión local (ej. Llama 3.2 Vision).
     *
     * La imagen se redimensiona a SCREENSHOT_WIDTH x SCREENSHOT_HEIGHT (672x672)
     * que es la resolución de entrada óptima para modelos de visión pequeños.
     * La calidad JPEG se ajusta a 80% para equilibrar detalle y tamaño.
     *
     * NOTA: La captura de pantalla requiere permiso de MediaProjection que
     * debe ser otorgado por el usuario al inicio de la sesión.
     * Alternativamente, se puede usar el comando shell:
     *   screencap -p /path/to/screenshot.png
     * si el dispositivo tiene acceso root o ADB.
     */
    private suspend fun captureAndEmitScreenshot() {
        withContext(Dispatchers.IO) {
            try {
                val screenshotFile = captureScreenshot()
                if (screenshotFile != null) {
                    PerceptionBus.emit(
                        PerceptionEvent.ScreenshotReady(
                            imagePath = screenshotFile.absolutePath,
                            width = SCREENSHOT_WIDTH,
                            height = SCREENSHOT_HEIGHT
                        )
                    )
                    Log.d(TAG, "Screenshot capturada: ${screenshotFile.absolutePath}")
                } else {
                    // Fallback del fallback: usar screencap via shell (requiere ADB/root)
                    captureViaShell()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturando screenshot", e)
            }
        }
    }

    /**
     * Captura screenshot usando MediaProjection.
     * Requiere que el usuario haya otorgado permiso previamente.
     */
    private suspend fun captureScreenshot(): File? {
        if (mediaProjection == null) {
            Log.w(TAG, "MediaProjection no disponible, no se puede capturar screenshot")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(metrics)

                imageReader = ImageReader.newInstance(
                    metrics.widthPixels, metrics.heightPixels,
                    PixelFormat.RGBA_8888, 2
                )

                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "NubiaAgentScreenCapture",
                    metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null, mainHandler
                )

                imageReader?.setOnImageAvailableListener({ reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val bitmap = imageToBitmap(image)
                            image.close()

                            val resized = Bitmap.createScaledBitmap(
                                bitmap, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true
                            )

                            val file = saveBitmap(resized)
                            reader.close()
                            continuation.resume(file) {}
                        }
                    } catch (e: Exception) {
                        continuation.resume(null) {}
                    }
                }, mainHandler)

            } catch (e: Exception) {
                continuation.resume(null) {}
            }
        }
    }

    /**
     * Fallback alternativo: captura via shell.
     * Solo funciona si el dispositivo tiene acceso root o ADB debug.
     */
    private suspend fun captureViaShell() {
        withContext(Dispatchers.IO) {
            try {
                val file = File(cacheDir, "nubia_screenshot_${System.currentTimeMillis()}.png")
                val process = Runtime.getRuntime().exec(
                    arrayOf("screencap", "-p", file.absolutePath)
                )
                process.waitFor()

                if (file.exists() && file.length() > 0) {
                    PerceptionBus.emit(
                        PerceptionEvent.ScreenshotReady(
                            imagePath = file.absolutePath,
                            width = SCREENSHOT_WIDTH,
                            height = SCREENSHOT_HEIGHT
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screencap via shell falló", e)
            }
        }
    }

    /**
     * Convierte Image del ImageReader a Bitmap.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * Guarda un Bitmap como JPEG comprimido en el directorio de cache.
     */
    private fun saveBitmap(bitmap: Bitmap): File {
        val file = File(cacheDir, "nubia_screenshot_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, fos)
        }
        return file
    }

    /**
     * Establece el MediaProjection para capturas de pantalla.
     * Debe ser llamado desde la Activity principal después de que
     * el usuario otorga el permiso.
     */
    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
        Log.i(TAG, "MediaProjection configurado para screenshot fallback")
    }

    /**
     * Libera recursos de captura de pantalla.
     */
    private fun releaseScreenshotResources() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    /**
     * Fuerza una re-lectura de la pantalla actual.
     * Útil cuando el agente necesita "mirar" la pantalla
     * sin esperar a un evento de cambio.
     */
    fun forceScreenRead() {
        val packageName = lastPackageName
        if (packageName.isNotBlank()) {
            serviceScope.launch {
                processScreen(packageName)
            }
        }
    }

    /**
     * Serializa la lista de UiElements a un formato JSON compacto
     * listo para ser incluido en el prompt del LLM.
     *
     * Formato optimizado para minimizar tokens:
     *   [{"t":"BUTTON","txt":"Enviar","click":true,"pos":[100,200,300,350]},...]
     */
    fun serializeForLLM(elements: List<UiElement>): String {
        val jsonArray = org.json.JSONArray()

        for (elem in elements) {
            val json = JSONObject().apply {
                put("t", elem.viewType.name)
                if (!elem.text.isNullOrBlank()) put("txt", elem.text)
                if (!elem.contentDescription.isNullOrBlank()) put("desc", elem.contentDescription)
                put("click", elem.isClickable)
                if (elem.isScrollable) put("scroll", true)
                if (elem.isEditable) put("edit", true)
                if (elem.isCheckable) {
                    put("check", true)
                    elem.isChecked?.let { put("checked", it) }
                }
                put("pos", elem.bounds)
            }
            jsonArray.put(json)
        }

        return jsonArray.toString()
    }

    // Clase auxiliar para scoring de nodos
    private data class ScoredNode(
        val node: AccessibilityNodeInfo,
        val score: Float
    )
}
