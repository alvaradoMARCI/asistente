package com.nubiaagent.ui.canvas

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CanvasController: Modo de pantalla completa (WebView) para tareas visuales complejas.
 *
 * ACTIVACIÓN:
 * El Canvas se activa para:
 * - Vibe Coder: Edición de código HTML/JS en pantalla completa
 * - Gráficos y visualizaciones complejas
 * - Resúmenes largos que necesitan formato rico
 * - Visualización de datos del People Graph
 * - Editor de system prompts (SOUL.md)
 * - Presentación del Morning Briefing extendido
 *
 * ARQUITECTURA:
 *
 * ```
 * CanvasController
 *     │
 *     ├── WebView (pantalla completa)
 *     │   ├── HTML/CSS/JS injection
 *     │   ├── Estética Mecha Futurista (Shadow Black / Cyber Silver)
 *     │   ├── Comunicación bidireccional: JS ↔ Kotlin
 *     │   └── Renderizado de contenido dinámico
 *     │
 *     ├── CanvasState (estado actual del canvas)
 *     │   ├── mode: CODER | VISUALIZER | BRIEFING | EDITOR
 *     │   ├── content: HTML actual
 *     │   └── isDirty: cambios sin guardar
 *     │
 *     └── JS Bridge (interfaz Kotlin-JavaScript)
 *         ├── NubiaBridge.sendToAgent(action, data)
 *         ├── NubiaBridge.getMemory(query)
 *         └── NubiaBridge.speak(text)
 * ```
 *
 * ESTÉTICA MECHA FUTURISTA:
 * - Fondo: Shadow Black (#0D0D0D) con grid sutil
 * - Texto: Cyber Silver (#C0C0C0) con acentos Cyan Neon (#00F0FF)
 * - Tipografía: Monospace para código, Sans para texto
 * - Bordes: Líneas finas Cyber Silver con esquinas HUD
 * - Animaciones: Scan lines, pulse effects, fade transitions
 *
 * Z-SMARTCAST:
 * El Canvas puede proyectarse a una pantalla externa usando
 * el motor de proyección del Nubia Neo 3 (Z-SmartCast).
 * Ver `ZSmartCastBridge.kt` para la implementación.
 */
class CanvasController : Service() {

    companion object {
        private const val TAG = "NubiaAgent/Canvas"

        // Colores Mecha
        private const val COLOR_SHADOW_BLACK = "#0D0D0D"
        private const val COLOR_CYBER_SILVER = "#C0C0C0"
        private const val COLOR_CYAN_NEON = "#00F0FF"
        private const val COLOR_MAGENTA_NEON = "#FF0066"
        private const val COLOR_GREEN_NEON = "#00FF66"

        // Plantilla HTML base con estética Mecha
        private val BASE_HTML_TEMPLATE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
    * { margin: 0; padding: 0; box-sizing: border-box; }

    body {
        background: $COLOR_SHADOW_BLACK;
        color: $COLOR_CYBER_SILVER;
        font-family: 'Roboto Mono', 'Courier New', monospace;
        padding: 16px;
        min-height: 100vh;
        position: relative;
        overflow-x: hidden;
    }

    /* Grid de fondo (efecto HUD) */
    body::before {
        content: '';
        position: fixed;
        top: 0; left: 0; right: 0; bottom: 0;
        background-image:
            linear-gradient(rgba(0, 240, 255, 0.03) 1px, transparent 1px),
            linear-gradient(90deg, rgba(0, 240, 255, 0.03) 1px, transparent 1px);
        background-size: 40px 40px;
        pointer-events: none;
        z-index: 0;
    }

    /* Scan line animada */
    body::after {
        content: '';
        position: fixed;
        top: 0; left: 0; right: 0;
        height: 2px;
        background: linear-gradient(90deg, transparent, $COLOR_CYAN_NEON, transparent);
        opacity: 0.3;
        animation: scanline 4s linear infinite;
        pointer-events: none;
        z-index: 9999;
    }

    @keyframes scanline {
        0% { top: -2px; }
        100% { top: 100vh; }
    }

    .header {
        border-bottom: 1px solid rgba(192, 192, 192, 0.3);
        padding-bottom: 8px;
        margin-bottom: 16px;
        position: relative;
        z-index: 1;
    }

    .header h1 {
        color: $COLOR_CYAN_NEON;
        font-size: 14px;
        text-transform: uppercase;
        letter-spacing: 3px;
        text-shadow: 0 0 8px $COLOR_CYAN_NEON;
    }

    .header .mode-badge {
        display: inline-block;
        padding: 2px 8px;
        border: 1px solid $COLOR_CYAN_NEON;
        color: $COLOR_CYAN_NEON;
        font-size: 10px;
        margin-left: 8px;
        letter-spacing: 1px;
    }

    .content {
        position: relative;
        z-index: 1;
        line-height: 1.6;
        font-size: 14px;
    }

    .content h2 {
        color: $COLOR_CYAN_NEON;
        font-size: 13px;
        margin: 16px 0 8px;
        letter-spacing: 2px;
    }

    .content p {
        margin-bottom: 8px;
    }

    .content code {
        background: rgba(0, 240, 255, 0.1);
        padding: 1px 4px;
        border-radius: 2px;
        color: $COLOR_CYAN_NEON;
        font-family: 'Roboto Mono', monospace;
    }

    .content pre {
        background: rgba(0, 0, 0, 0.5);
        border: 1px solid rgba(192, 192, 192, 0.2);
        border-left: 2px solid $COLOR_CYAN_NEON;
        padding: 12px;
        margin: 8px 0;
        overflow-x: auto;
        font-size: 12px;
    }

    .accent { color: $COLOR_CYAN_NEON; }
    .warning { color: $COLOR_MAGENTA_NEON; }
    .success { color: $COLOR_GREEN_NEON; }

    /* Corner decorations (HUD) */
    .hud-corner {
        position: fixed;
        width: 20px;
        height: 20px;
        z-index: 2;
        pointer-events: none;
    }
    .hud-corner.tl { top: 4px; left: 4px; border-top: 1px solid $COLOR_CYAN_NEON; border-left: 1px solid $COLOR_CYAN_NEON; }
    .hud-corner.tr { top: 4px; right: 4px; border-top: 1px solid $COLOR_CYAN_NEON; border-right: 1px solid $COLOR_CYAN_NEON; }
    .hud-corner.bl { bottom: 4px; left: 4px; border-bottom: 1px solid $COLOR_CYAN_NEON; border-left: 1px solid $COLOR_CYAN_NEON; }
    .hud-corner.br { bottom: 4px; right: 4px; border-bottom: 1px solid $COLOR_CYAN_NEON; border-right: 1px solid $COLOR_CYAN_NEON; }

    .footer {
        position: fixed;
        bottom: 0;
        left: 0;
        right: 0;
        padding: 4px 16px;
        background: rgba(13, 13, 13, 0.9);
        border-top: 1px solid rgba(192, 192, 192, 0.2);
        font-size: 10px;
        color: rgba(192, 192, 192, 0.5);
        z-index: 10;
    }

    .footer .status-dot {
        display: inline-block;
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: $COLOR_CYAN_NEON;
        margin-right: 4px;
        animation: pulse 2s infinite;
    }

    @keyframes pulse {
        0%, 100% { opacity: 1; }
        50% { opacity: 0.3; }
    }
</style>
</head>
<body>
    <div class="hud-corner tl"></div>
    <div class="hud-corner tr"></div>
    <div class="hud-corner bl"></div>
    <div class="hud-corner br"></div>

    <div class="header">
        <h1>◆ NUBIA AGENT ◆ <span class="mode-badge" id="mode-badge">CANVAS</span></h1>
    </div>

    <div class="content" id="content">
        <!-- Contenido dinámico -->
    </div>

    <div class="footer">
        <span class="status-dot"></span>
        NUBIA AGENT v4.0.0 | LOCAL-FIRST | AES-256-GCM
    </div>

    <script>
        // Bridge JavaScript ↔ Kotlin
        const NubiaBridge = {
            sendToAgent: function(action, data) {
                if (window.AndroidBridge) {
                    AndroidBridge.onJsAction(action, JSON.stringify(data));
                }
            },
            getMemory: function(query) {
                if (window.AndroidBridge) {
                    return AndroidBridge.getMemory(query);
                }
                return null;
            },
            speak: function(text) {
                if (window.AndroidBridge) {
                    AndroidBridge.speak(text);
                }
            },
            updateContent: function(html) {
                document.getElementById('content').innerHTML = html;
            },
            setMode: function(mode) {
                document.getElementById('mode-badge').textContent = mode;
            }
        };
    </script>
</body>
</html>
"""
    }

    private var windowManager: WindowManager? = null
    private var webView: WebView? = null
    private var isCanvasVisible = false

    private val _canvasMode = MutableStateFlow(CanvasMode.CODER)
    val canvasMode: StateFlow<CanvasMode> = _canvasMode.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "CanvasController creado")
    }

    override fun onDestroy() {
        super.onDestroy()
        hideCanvas()
        scope.cancel()
        Log.i(TAG, "CanvasController destruido")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SHOW" -> showCanvas()
            "HIDE" -> hideCanvas()
            "SET_MODE" -> {
                val mode = intent.getStringExtra("mode") ?: "CODER"
                setCanvasMode(CanvasMode.valueOf(mode))
            }
            "LOAD_CONTENT" -> {
                val html = intent.getStringExtra("html") ?: ""
                loadContent(html)
            }
            "LOAD_CODE" -> {
                val code = intent.getStringExtra("code") ?: ""
                loadVibeCoder(code)
            }
            "LOAD_BRIEFING" -> {
                val briefingHtml = intent.getStringExtra("briefing") ?: ""
                loadBriefing(briefingHtml)
            }
        }
        return START_STICKY
    }

    /**
     * Muestra el Canvas en pantalla completa.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showCanvas() {
        if (isCanvasVisible) return

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false  // Seguridad
            settings.allowContentAccess = false

            webViewClient = CanvasWebViewClient()
            webChromeClient = WebChromeClient()

            // Bridge JS ↔ Kotlin
            addJavascriptInterface(CanvasJsBridge(), "AndroidBridge")

            // Cargar plantilla base
            loadDataWithBaseURL(
                null,
                BASE_HTML_TEMPLATE,
                "text/html",
                "UTF-8",
                null
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager?.addView(webView, params)
        isCanvasVisible = true
        Log.i(TAG, "Canvas mostrado en pantalla completa")

        // Notificar al PerceptionBus
        com.nubiaagent.core.PerceptionBus.tryEmit(
            com.nubiaagent.core.PerceptionEvent.ScreenChanged(
                packageName = "com.nubiaagent.canvas",
                activityName = "CanvasController"
            )
        )
    }

    /**
     * Oculta el Canvas.
     */
    fun hideCanvas() {
        webView?.let {
            windowManager?.removeView(it)
            webView = null
        }
        isCanvasVisible = false
        Log.i(TAG, "Canvas ocultado")
    }

    /**
     * Establece el modo del Canvas.
     */
    fun setCanvasMode(mode: CanvasMode) {
        _canvasMode.value = mode
        webView?.evaluateJavascript("NubiaBridge.setMode('$mode');", null)
        Log.i(TAG, "Canvas modo: $mode")
    }

    /**
     * Carga contenido HTML en el Canvas.
     */
    fun loadContent(html: String) {
        val escapedHtml = html.replace("'", "\\'").replace("\n", "\\n")
        webView?.evaluateJavascript(
            "NubiaBridge.updateContent('$escapedHtml');",
            null
        )
        _isDirty.value = true
    }

    /**
     * Carga el modo Vibe Coder con editor de código.
     */
    fun loadVibeCoder(initialCode: String = "") {
        setCanvasMode(CanvasMode.CODER)

        val coderHtml = """
        <div style="margin-bottom: 8px; color: $COLOR_CYAN_NEON; font-size: 11px;">
            ◆ VIBE CODER — Editor de Código Local
        </div>
        <textarea id="code-editor" style="
            width: 100%;
            height: calc(100vh - 160px);
            background: rgba(0,0,0,0.6);
            color: $COLOR_CYAN_NEON;
            border: 1px solid rgba(192,192,192,0.2);
            border-left: 2px solid $COLOR_CYAN_NEON;
            padding: 12px;
            font-family: 'Roboto Mono', monospace;
            font-size: 13px;
            line-height: 1.5;
            resize: none;
            outline: none;
        " oninput="NubiaBridge.sendToAgent('code_changed', {content: this.value})">$initialCode</textarea>
        <div style="margin-top: 8px;">
            <button onclick="NubiaBridge.sendToAgent('run_code', {content: document.getElementById('code-editor').value})"
                style="background: rgba(0,240,255,0.15); color: $COLOR_CYAN_NEON; border: 1px solid $COLOR_CYAN_NEON; padding: 6px 16px; font-family: monospace; cursor: pointer;">
                ▶ EJECUTAR
            </button>
            <button onclick="NubiaBridge.sendToAgent('preview_code', {content: document.getElementById('code-editor').value})"
                style="background: rgba(0,255,102,0.15); color: $COLOR_GREEN_NEON; border: 1px solid $COLOR_GREEN_NEON; padding: 6px 16px; margin-left: 8px; font-family: monospace; cursor: pointer;">
                ◉ PREVIEW
            </button>
        </div>
        """

        loadContent(coderHtml)
    }

    /**
     * Carga el Morning Briefing en el Canvas.
     */
    fun loadBriefing(briefingHtml: String) {
        setCanvasMode(CanvasMode.BRIEFING)

        val briefingContent = """
        <h2>☀ MORNING BRIEFING</h2>
        <p style="color: rgba(192,192,192,0.5); margin-bottom: 16px;">
            Generado el ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}
        </p>
        $briefingHtml
        """

        loadContent(briefingContent)
    }

    /**
     * Carga una visualización del People Graph.
     */
    fun loadPeopleGraph(graphData: String) {
        setCanvasMode(CanvasMode.VISUALIZER)

        val graphHtml = """
        <h2>◉ PEOPLE GRAPH</h2>
        <p style="color: rgba(192,192,192,0.5); margin-bottom: 16px;">
            Relaciones e interacciones del usuario
        </p>
        <div id="graph-container" style="width: 100%; height: 400px; background: rgba(0,0,0,0.3); border: 1px solid rgba(192,192,192,0.1);">
            <!-- Canvas para D3.js o Chart.js -->
            <p style="text-align: center; padding-top: 180px; color: rgba(192,192,192,0.3);">
                Visualización del grafo de contactos y relaciones
            </p>
        </div>
        """

        loadContent(graphHtml)
    }

    // ==================== CLASES INTERNAS ====================

    /**
     * WebViewClient personalizado para el Canvas.
     */
    private class CanvasWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            Log.d(TAG, "Canvas HTML cargado")
        }
    }

    /**
     * Bridge JavaScript ↔ Kotlin.
     *
     * Permite que el JavaScript del Canvas invoque acciones
     * del agente nativo de Android.
     */
    private inner class CanvasJsBridge {
        @android.webkit.JavascriptInterface
        fun onJsAction(action: String, data: String) {
            Log.d(TAG, "JS Action: $action → $data")
            when (action) {
                "code_changed" -> _isDirty.value = true
                "run_code" -> {
                    // TODO: Ejecutar código en WebView sandbox
                }
                "preview_code" -> {
                    // TODO: Previsualizar HTML/JS
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun getMemory(query: String): String {
            // TODO: Consultar MemoryManager
            return ""
        }

        @android.webkit.JavascriptInterface
        fun speak(text: String) {
            // TODO: Invocar VoiceEngine
        }
    }
}

/**
 * Modos de operación del Canvas.
 */
enum class CanvasMode {
    CODER,          // Editor de código (Vibe Coder)
    VISUALIZER,     // Gráficos y visualizaciones
    BRIEFING,       // Morning Briefing extendido
    EDITOR          // Editor de SOUL.md / prompts
}
