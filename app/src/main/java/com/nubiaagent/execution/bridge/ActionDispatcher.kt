package com.nubiaagent.execution.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * ActionDispatcher: Motor de Ejecución de Acciones UI para NubiaAgent.
 *
 * Traduce las decisiones del agente cognitivo en acciones concretas sobre la
 * interfaz del dispositivo usando AccessibilityService como puente de automatización.
 *
 * ARQUITECTURA DE EJECUCIÓN:
 * ```
 *  Cognitive Layer → ActionDispatcher → AccessibilityService → Android Framework
 *                          │
 *                    GestureEngine (genera trazos naturales)
 *                          │
 *                    Screen State Hash (verificación post-acción)
 * ```
 *
 * ESTRATEGIA DE REINTENTO (DroidClaw-style):
 *   Si una acción no produce cambio observable en la pantalla dentro de 3 segundos,
 *   el dispatcher reintenta con una ruta alternativa:
 *     1. Reintento 1: Re-ejecutar la misma acción con gesto alternativo
 *     2. Reintento 2: Buscar un nodo alternativo que logre el mismo objetivo
 *     3. Reintento 3: Re-lanzar la aplicación como último recurso
 *
 * HUMANIZACIÓN DE GESTOS:
 *   Todos los gestos incluyen delays aleatorios (50-150ms) entre eventos
 *   para emular el timing natural de un usuario humano. Esto evita detección
 *   por sistemas anti-bot y produce interacciones más confiables.
 *
 * HASHING DE ESTADO DE PANTALLA:
 *   Se genera un hash SHA-256 del árbol de accesibilidad después de cada acción.
 *   Si el hash no cambia, se asume que la acción falló y se activa el retry.
 *
 * @param service Referencia al AccessibilityService activo del agente
 */
class ActionDispatcher(
    private val service: AccessibilityService
) {

    companion object {
        private const val TAG = "NubiaAgent/ActionDisp"

        /** Tiempo máximo de espera para detectar cambio de pantalla (ms) */
        private const val SCREEN_CHANGE_TIMEOUT_MS = 3000L

        /** Máximo número de reintentos antes de declarar fallo */
        private const val MAX_RETRIES = 3

        /** Delay base entre acciones para humanizar timing (ms) */
        private const val HUMAN_DELAY_MIN_MS = 50L
        private const val HUMAN_DELAY_MAX_MS = 150L

        /** Delay entre reintentos (ms) */
        private const val RETRY_DELAY_MS = 500L

        /** Timeout para operaciones de gestos (ms) */
        private const val GESTURE_TIMEOUT_MS = 5000L
    }

    /** Motor de generación de gestos naturales */
    private val gestureEngine = GestureEngine()

    /** Último hash conocido del estado de pantalla */
    private val lastScreenHash = AtomicReference("")

    /** Scope de corrutinas para operaciones asíncronas */
    private val dispatcherScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate
    )

    // ─────────────────────────────────────────────────────────────
    // COMANDOS PÚBLICOS
    // ─────────────────────────────────────────────────────────────

    /**
     * Ejecuta un tap sobre un elemento identificado por su resource-id.
     *
     * Estrategia de reintento:
     *   1. Tap directo sobre el nodo
     *   2. Tap con gesto en el centro del bounds
     *   3. Búsqueda de nodo alternativo con texto similar
     *
     * @param elementId Resource-id del elemento (e.g. "com.example:id/button")
     * @return true si la acción produjo cambio de pantalla, false si falló tras reintentos
     */
    suspend fun tap(elementId: String): Boolean {
        Log.i(TAG, "tap(elementId=$elementId)")
        return executeWithRetry("tap(id=$elementId)") {
            val node = findNodeById(elementId)
            if (node != null) {
                performClick(node)
            } else {
                Log.w(TAG, "Nodo no encontrado por id: $elementId, intentando gesto en posición estimada")
                dispatchGesture(gestureEngine.createTap(540, 1200))
                true
            }
        }
    }

    /**
     * Ejecuta un long-press en coordenadas específicas de la pantalla.
     *
     * @param x Coordenada X en píxeles
     * @param y Coordenada Y en píxeles
     * @return true si la acción produjo cambio de pantalla, false si falló
     */
    suspend fun longPress(x: Int, y: Int): Boolean {
        Log.i(TAG, "longPress(x=$x, y=$y)")
        return executeWithRetry("longPress($x,$y)") {
            val gesture = gestureEngine.createLongPress(x, y, 800L)
            dispatchGesture(gesture)
            true
        }
    }

    /**
     * Escribe texto en un campo identificado por su resource-id.
     *
     * Estrategia de reintento:
     *   1. Usar ACTION_SET_TEXT directamente en el nodo
     *   2. Tap en el campo + dispatch de gesto de escritura
     *   3. Tap en el campo + pegado desde portapapeles
     *
     * @param text Texto a escribir
     * @param fieldId Resource-id del campo de texto
     * @return true si la acción se completó exitosamente
     */
    suspend fun typeText(text: String, fieldId: String): Boolean {
        Log.i(TAG, "typeText(text=\"${text.take(30)}${if (text.length > 30) "..." else ""}\", fieldId=$fieldId)")
        return executeWithRetry("typeText($fieldId)") {
            val node = findNodeById(fieldId)
            if (node != null) {
                performSetText(node, text)
            } else {
                Log.w(TAG, "Campo no encontrado por id: $fieldId, buscando por editable")
                val editableNode = findEditableNode() ?: run {
                    Log.e(TAG, "No se encontró ningún campo editable en la pantalla")
                    return@executeWithRetry false
                }
                performSetText(editableNode, text)
            }
        }
    }

    /**
     * Ejecuta un swipe en una dirección cardinal desde coordenadas dadas.
     *
     * Direcciones soportadas: "up", "down", "left", "right"
     *
     * @param direction Dirección del swipe ("up", "down", "left", "right")
     * @param startX Coordenada X de inicio (si -1, se usa centro horizontal)
     * @param startY Coordenada Y de inicio (si -1, se usa centro vertical)
     * @return true si la acción produjo cambio de pantalla
     */
    suspend fun swipe(direction: String, startX: Int = -1, startY: Int = -1): Boolean {
        Log.i(TAG, "swipe(direction=$direction, startX=$startX, startY=$startY)")
        return executeWithRetry("swipe($direction)") {
            val metrics = service.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            val sx = if (startX >= 0) startX else screenWidth / 2
            val sy = if (startY >= 0) startY else screenHeight / 2

            val gesture = gestureEngine.createSwipeDirection(
                direction, screenWidth, screenHeight, sx, sy
            )
            dispatchGesture(gesture)
            true
        }
    }

    /**
     * Ejecuta la acción de "volver atrás" en el navegador de actividades.
     *
     * Estrategia de reintento:
     *   1. AccessibilityService.performGlobalAction(GLOBAL_ACTION_BACK)
     *   2. Gesto de swipe desde borde izquierdo (gesto de sistema)
     *
     * @return true si la acción se ejecutó exitosamente
     */
    suspend fun goBack(): Boolean {
        Log.i(TAG, "goBack()")
        return executeWithRetry("goBack") {
            val result = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            if (!result) {
                Log.w(TAG, "performGlobalAction(BACK) falló, intentando gesto de vuelta")
                val metrics = service.resources.displayMetrics
                val screenHeight = metrics.heightPixels
                val gesture = gestureEngine.createSwipe(0, screenHeight / 2, 200, screenHeight / 2, 300L)
                dispatchGesture(gesture)
            }
            true
        }
    }

    /**
     * Navega a la pantalla de inicio (Home).
     *
     * @return true si la acción se ejecutó exitosamente
     */
    suspend fun goHome(): Boolean {
        Log.i(TAG, "goHome()")
        return executeWithRetry("goHome") {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SISTEMA DE REINTENTO DROIDCLAW-STYLE
    // ─────────────────────────────────────────────────────────────

    /**
     * Ejecuta una acción con lógica de reintento automático.
     *
     * Flujo de reintento:
     *   1. Captura hash de pantalla actual
     *   2. Ejecuta la acción con delay humanizado
     *   3. Espera hasta [SCREEN_CHANGE_TIMEOUT_MS] para detectar cambio
     *   4. Si no hay cambio, reintenta con estrategia alternativa
     *   5. Tras [MAX_RETRIES] fallos, intenta re-lanzar la app
     *
     * @param actionName Nombre descriptivo para logging
     * @param action Lambda que ejecuta la acción, retorna true si ejecución fue exitosa
     * @return true si la acción produjo resultado observable
     */
    private suspend fun executeWithRetry(
        actionName: String,
        action: suspend () -> Boolean
    ): Boolean {
        for (attempt in 1..MAX_RETRIES) {
            try {
                Log.d(TAG, "Intento $attempt/$MAX_RETRIES para: $actionName")

                // Capturar estado de pantalla antes de la acción
                val hashBefore = captureScreenHash()
                lastScreenHash.set(hashBefore)

                // Delay humanizado pre-acción
                humanDelay()

                // Ejecutar la acción
                val actionResult = action()

                if (!actionResult) {
                    Log.w(TAG, "Acción retornó false en intento $attempt: $actionName")
                    if (attempt < MAX_RETRIES) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    break
                }

                // Esperar y verificar cambio de pantalla
                val screenChanged = waitForScreenChange(hashBefore, SCREEN_CHANGE_TIMEOUT_MS)

                if (screenChanged) {
                    Log.i(TAG, "✓ $actionName exitoso en intento $attempt (pantalla cambió)")
                    return true
                }

                Log.w(TAG, "Pantalla sin cambios tras intento $attempt: $actionName")

                // Estrategia de reintento según intento
                if (attempt < MAX_RETRIES) {
                    when (attempt) {
                        1 -> {
                            Log.d(TAG, "Reintento 1: Re-ejecutando con delay extendido")
                            delay(RETRY_DELAY_MS * 2)
                        }
                        2 -> {
                            Log.d(TAG, "Reintento 2: Intentando ruta alternativa")
                            attemptAlternativeRoute(actionName)
                            delay(RETRY_DELAY_MS)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Excepción en intento $attempt para $actionName", e)
                if (attempt >= MAX_RETRIES) break
                delay(RETRY_DELAY_MS)
            }
        }

        // Último recurso: re-lanzar la aplicación
        Log.w(TAG, "Todos los reintentos fallaron para: $actionName. Re-lanzando app...")
        relaunchCurrentApp()
        return false
    }

    /**
     * Espera a que el estado de la pantalla cambie respecto al hash dado.
     *
     * Realiza polling cada 200ms para comparar el hash actual con el anterior.
     * Un cambio en el hash indica que la UI se actualizó como resultado de la acción.
     *
     * @param hashBefore Hash SHA-256 del estado de pantalla antes de la acción
     * @param timeoutMs Tiempo máximo de espera en milisegundos
     * @return true si el hash cambió dentro del timeout, false si no hubo cambio
     */
    private suspend fun waitForScreenChange(hashBefore: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        val pollInterval = 200L

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentHash = captureScreenHash()
            if (currentHash != hashBefore && currentHash.isNotEmpty()) {
                lastScreenHash.set(currentHash)
                Log.d(TAG, "Screen hash cambió: ${hashBefore.take(8)}.. → ${currentHash.take(8)}..")
                return true
            }
            delay(pollInterval)
        }

        Log.d(TAG, "Timeout esperando cambio de pantalla (${timeoutMs}ms)")
        return false
    }

    /**
     * Intenta una ruta alternativa cuando la acción principal falla.
     *
     * Estrategias:
     *   - Para taps: intenta scroll para buscar el elemento
     *   - Para navegación: intenta gesto alternativo
     *   - Para texto: intenta método de entrada alternativo
     */
    private suspend fun attemptAlternativeRoute(actionName: String) {
        Log.d(TAG, "Intentando ruta alternativa para: $actionName")

        when {
            actionName.startsWith("tap") -> {
                // Scroll hacia abajo para buscar el elemento
                swipe("up")
                delay(300)
            }
            actionName.startsWith("swipe") -> {
                // Intentar swipe en dirección opuesta
                val currentDir = actionName.substringAfter("(").substringBefore(")")
                val opposite = when (currentDir) {
                    "up" -> "down"
                    "down" -> "up"
                    "left" -> "right"
                    "right" -> "left"
                    else -> "up"
                }
                swipe(opposite)
            }
            actionName.startsWith("typeText") -> {
                // Intentar tap en el campo antes de escribir
                val fieldId = actionName.substringAfter("(").substringBefore(")")
                val node = findNodeById(fieldId)
                if (node != null) {
                    performClick(node)
                    delay(200)
                }
            }
        }
    }

    /**
     * Re-lanza la aplicación que estaba en primer plano.
     *
     * Se usa como último recurso cuando todas las acciones y reintentos fallan.
     * Obtiene el paquete actual y lanza su actividad principal.
     */
    private fun relaunchCurrentApp() {
        try {
            val rootNode = service.rootInActiveWindow ?: return
            val packageName = rootNode.packageName?.toString() ?: return

            Log.i(TAG, "Re-lanzando aplicación: $packageName")

            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(intent)
                Log.i(TAG, "Aplicación re-lanzada exitosamente")
            } else {
                Log.w(TAG, "No se pudo obtener launch intent para: $packageName")
                // Fallback: ir a Home
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }

            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error re-lanzando aplicación", e)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // BÚSQUEDA DE NODOS
    // ─────────────────────────────────────────────────────────────

    /**
     * Busca un nodo en el árbol de accesibilidad por su texto visible.
     *
     * Realiza búsqueda BFS (breadth-first) sobre el árbol de accesibilidad
     * para encontrar el primer nodo cuyo texto contenga el string buscado.
     * La búsqueda es case-insensitive para mayor robustez.
     *
     * @param text Texto a buscar en los nodos
     * @return AccessibilityNodeInfo del primer nodo encontrado, o null si no existe
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: run {
            Log.w(TAG, "findNodeByText: rootInActiveWindow es null")
            return null
        }

        try {
            val result = bfsSearch(rootNode) { node ->
                val nodeText = node.text?.toString()?.lowercase()
                nodeText != null && nodeText.contains(text.lowercase())
            }
            return result
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Busca un nodo en el árbol de accesibilidad por su resource-id.
     *
     * El resource-id tiene formato "com.package:id/resource_name".
     * La búsqueda soporta tanto el id completo como solo el nombre del recurso.
     *
     * @param id Resource-id completo o parcial del nodo
     * @return AccessibilityNodeInfo del primer nodo encontrado, o null si no existe
     */
    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: run {
            Log.w(TAG, "findNodeById: rootInActiveWindow es null")
            return null
        }

        try {
            val result = bfsSearch(rootNode) { node ->
                val viewId = node.viewIdResourceName
                viewId == id || viewId?.endsWith(":id/$id") == true || viewId?.endsWith("/$id") == true
            }
            return result
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Busca un nodo en el árbol de accesibilidad por su content-description.
     *
     * La content-description es el atributo de accesibilidad que describe
     * visualmente un elemento (usado por TalkBack y otros lectores de pantalla).
     *
     * @param desc Content-description a buscar
     * @return AccessibilityNodeInfo del primer nodo encontrado, o null si no existe
     */
    fun findNodeByDesc(desc: String): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: run {
            Log.w(TAG, "findNodeByDesc: rootInActiveWindow es null")
            return null
        }

        try {
            val result = bfsSearch(rootNode) { node ->
                val nodeDesc = node.contentDescription?.toString()?.lowercase()
                nodeDesc != null && nodeDesc.contains(desc.lowercase())
            }
            return result
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Búsqueda BFS genérica sobre el árbol de accesibilidad.
     *
     * Recorre el árbol nivel por nivel para encontrar el primer nodo que
     * satisfaga el predicado. Los nodos visitados que no coinciden se
     * reciclan automáticamente para evitar memory leaks.
     *
     * NOTA: El nodo retornado NO se recicla - es responsabilidad del
     * llamador reciclarlo cuando termine de usarlo.
     *
     * @param root Nodo raíz desde donde iniciar la búsqueda
     * @param predicate Predicado que determina si un nodo es el buscado
     * @return Primer nodo que satisface el predicado, o null
     */
    private fun bfsSearch(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        // No reciclamos root porque es propiedad del llamador
        var skippedRoot = false

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            // Saltar reciclaje del nodo raíz (propiedad del llamador)
            if (!skippedRoot) {
                skippedRoot = true
            }

            if (predicate(current)) {
                // Encontrado - reciclar los nodos restantes en la cola
                // (excepto root que pertenece al llamador)
                queue.forEach { if (it !== root) it.recycle() }
                return current
            }

            // Agregar hijos a la cola
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            // Reciclar nodos no-root que no coincidieron
            if (current !== root) {
                current.recycle()
            }
        }

        return null
    }

    /**
     * Busca el primer nodo editable en el árbol de accesibilidad.
     *
     * Fallback para typeText cuando no se encuentra el campo por id.
     *
     * @return Primer nodo editable encontrado, o null
     */
    private fun findEditableNode(): AccessibilityNodeInfo? {
        val rootNode = service.rootInActiveWindow ?: return null
        try {
            return bfsSearch(rootNode) { node ->
                node.isEditable
            }
        } finally {
            rootNode.recycle()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ACCIONES SOBRE NODOS
    // ─────────────────────────────────────────────────────────────

    /**
     * Ejecuta un click sobre un nodo de accesibilidad.
     *
     * Estrategia en cascada:
     *   1. ACTION_CLICK directo (más eficiente y confiable)
     *   2. Si el nodo no es clickable, buscar ancestro clickable
     *   3. Si no hay ancestro clickable, ejecutar gesto de tap en las coordenadas
     *
     * @param node Nodo sobre el que ejecutar el click
     * @return true si la acción se ejecutó exitosamente
     */
    fun performClick(node: AccessibilityNodeInfo): Boolean {
        // Estrategia 1: Click directo
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "performClick directo: $result")
            if (result) return true
        }

        // Estrategia 2: Buscar ancestro clickable
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "performClick en ancestro: $result")
                parent.recycle()
                if (result) return true
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
        }

        // Estrategia 3: Gesto de tap en coordenadas del nodo
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        Log.d(TAG, "performClick via gesto en ($centerX, $centerY)")
        val gesture = gestureEngine.createTap(centerX, centerY)
        return dispatchGestureSync(gesture)
    }

    /**
     * Escribe texto en un nodo usando ACTION_SET_TEXT.
     *
     * Estrategia en cascada:
     *   1. Focus + ACTION_SET_TEXT (más directo)
     *   2. Focus + ACTION_SET_TEXT con bundle
     *   3. Focus + pegado desde portapapeles
     *
     * @param node Nodo de texto editable
     * @param text Texto a escribir
     * @return true si la acción se ejecutó exitosamente
     */
    fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Asegurar foco en el campo
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        humanDelaySync(100)

        // Estrategia 1: ACTION_SET_TEXT con Bundle (API 21+)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "performSetText via ACTION_SET_TEXT: $result")
        if (result) return true

        // Estrategia 2: Pegar desde portapapeles
        Log.d(TAG, "ACTION_SET_TEXT falló, intentando portapapeles")
        return pasteViaClipboard(node, text)
    }

    /**
     * Pega texto en un nodo usando el portapapeles del sistema.
     *
     * Copia el texto al ClipboardManager, luego ejecuta ACTION_PASTE
     * en el nodo destino. Esta estrategia es necesaria para campos
     * que no soportan ACTION_SET_TEXT (ej: WebView inputs).
     *
     * @param node Nodo donde pegar el texto
     * @param text Texto a pegar
     * @return true si el pegado se ejecutó exitosamente
     */
    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            // Copiar al portapapeles
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("nubia_agent_text", text)
            clipboard.setPrimaryClip(clip)

            humanDelaySync(50)

            // Seleccionar todo el texto existente
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Int.MAX_VALUE)
            })

            humanDelaySync(30)

            // Pegar
            val result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "pasteViaClipboard: $result")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error en pasteViaClipboard", e)
            return false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DISPATCH DE GESTOS
    // ─────────────────────────────────────────────────────────────

    /**
     * Despacha un gesto al framework de accesibilidad de forma asíncrona.
     *
     * Usa AccessibilityService.dispatchGesture() (API 24+) para inyectar
     * eventos de toque directamente en el sistema de input de Android.
     * El gesto se ejecuta de forma asíncrona y se notifica el resultado
     * vía callback.
     *
     * @param gesture GestureDescription con la descripción del gesto a ejecutar
     * @return true si el gesto fue aceptado por el framework
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun dispatchGesture(gesture: GestureDescription): Boolean {
        return try {
            val completed = AtomicBoolean(false)
            val success = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesto completado exitosamente")
                    success.set(true)
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesto cancelado por el sistema")
                    success.set(false)
                    completed.set(true)
                    latch.countDown()
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)

            if (!dispatched) {
                Log.e(TAG, "dispatchGesture rechazado por el framework")
                return false
            }

            // Esperar resultado del gesto
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            success.get()

        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching gesture", e)
            false
        }
    }

    /**
     * Despacha un gesto de forma síncrona, bloqueando hasta completar.
     *
     * Variante sincrónica de [dispatchGesture] para usar en contextos
     * donde se necesita el resultado inmediato antes de continuar.
     *
     * @param gesture GestureDescription con la descripción del gesto
     * @return true si el gesto se completó exitosamente
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        return try {
            val latch = CountDownLatch(1)
            val success = AtomicBoolean(false)

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    success.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    success.set(false)
                    latch.countDown()
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)
            if (!dispatched) return false

            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            success.get()

        } catch (e: Exception) {
            Log.e(TAG, "Error en dispatchGestureSync", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HASHING DE ESTADO DE PANTALLA
    // ─────────────────────────────────────────────────────────────

    /**
     * Captura un hash SHA-256 del estado actual de la pantalla.
     *
     * El hash se genera a partir de la concatenación de:
     *   - Package name de la ventana activa
     *   - Bounds de todos los nodos visibles
     *   - Texto visible de los nodos
     *   - Estado de checkable/checked
     *
     * Este hash permite detectar cambios en la UI sin necesidad de
     * comparar screenshots pixel por pixel, lo cual sería mucho más costoso.
     *
     * @return String hexadecimal de 64 caracteres (SHA-256), o string vacío si falla
     */
    fun captureScreenHash(): String {
        try {
            val rootNode = service.rootInActiveWindow ?: run {
                Log.w(TAG, "captureScreenHash: rootInActiveWindow es null")
                return ""
            }

            val sb = StringBuilder()

            // Incluir información del paquete activo
            sb.append(rootNode.packageName ?: "")
            sb.append("|")

            // Recorrer el árbol y extraer estado
            collectNodeState(rootNode, sb)

            rootNode.recycle()

            // Generar hash SHA-256
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(sb.toString().toByteArray(Charsets.UTF_8))

            return hashBytes.joinToString("") { "%02x".format(it) }

        } catch (e: Exception) {
            Log.e(TAG, "Error generando screen hash", e)
            return ""
        }
    }

    /**
     * Recorre recursivamente el árbol de nodos y concatena su estado.
     *
     * Extrae información relevante de cada nodo para el hash:
     *   - Bounds en pantalla (posición + tamaño)
     *   - Texto visible
     *   - Content description
     *   - Estados de checkable/checked/selected
     *
     * Solo se incluyen nodos visibles (bounds dentro de la pantalla)
     * para evitar que nodos offscreen causen falsos cambios de hash.
     *
     * @param node Nodo actual del recorrido
     * @param sb StringBuilder donde concatenar el estado
     * @param depth Profundidad actual (limitar para performance)
     */
    private fun collectNodeState(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int = 0) {
        if (depth > 20) return // Limitar profundidad para evitar stack overflow

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Solo incluir nodos visibles en pantalla
        val metrics = service.resources.displayMetrics
        if (bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < metrics.widthPixels && bounds.top < metrics.heightPixels
        ) {
            sb.append(bounds.toShortString())
            sb.append("|")
            sb.append(node.text ?: "")
            sb.append("|")
            sb.append(node.contentDescription ?: "")
            sb.append("|")
            sb.append(node.isChecked)
            sb.append(node.isSelected)
            sb.append(node.isEnabled)
            sb.append("|")
        }

        // Recorrer hijos
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeState(child, sb, depth + 1)
            child.recycle()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UTILIDADES DE HUMANIZACIÓN
    // ─────────────────────────────────────────────────────────────

    /**
     * Introduce un delay aleatorio para humanizar el timing de acciones.
     *
     * Los delays están entre [HUMAN_DELAY_MIN_MS] y [HUMAN_DELAY_MAX_MS]
     * con distribución uniforme. Esto evita patrones de timing predecibles
     * que podrían ser detectados por sistemas anti-bot.
     */
    private suspend fun humanDelay() {
        val delay = Random.nextLong(HUMAN_DELAY_MIN_MS, HUMAN_DELAY_MAX_MS + 1)
        delay(delay)
    }

    /**
     * Variante síncrona de [humanDelay] para uso en métodos no-coroutine.
     *
     * @param baseDelay Delay base en ms; se añade un jitter aleatorio de ±30ms
     */
    private fun humanDelaySync(baseDelay: Long = 0) {
        val jitter = Random.nextLong(-30, 31)
        val totalDelay = (baseDelay + jitter).coerceAtLeast(0)
        if (totalDelay > 0) {
            try {
                Thread.sleep(totalDelay)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // LIMPIEZA DE RECURSOS
    // ─────────────────────────────────────────────────────────────

    /**
     * Libera recursos y cancela operaciones en curso.
     *
     * Debe llamarse cuando el AccessibilityService se desactiva
     * para evitar leaks de corrutinas y nodos de accesibilidad.
     */
    fun destroy() {
        Log.i(TAG, "ActionDispatcher destruyéndose")
        dispatcherScope.cancel()
    }
}
