package com.nubiaagent.execution.skills.security_ctrl

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Habilidad de generación y visualización de código HTML/JS para NubiaAgent.
 * Permite crear mini-aplicaciones (calculadora, temporizador, bloc de notas, etc.)
 * y mostrarlas en un WebView dentro de la aplicación.
 *
 * El flujo de trabajo es:
 * 1. El usuario describe lo que quiere (prompt)
 * 2. Se genera el código HTML/JS correspondiente
 * 3. Se guarda como archivo en almacenamiento interno
 * 4. Se muestra en una actividad WebView
 */
class VibeCoder(private val context: Context) {

    companion object {
        private const val TAG = "VibeCoder"
        private const val SNIPPETS_DIR = "vibe_snippets"
        private const val FILE_EXTENSION = ".html"
        private const val CHARSET = "UTF-8"

        // Acción del Intent para abrir la WebView
        const val ACTION_SHOW_CANVAS = "com.nubiaagent.SHOW_CANVAS"
        const val EXTRA_HTML_PATH = "html_path"
        const val EXTRA_HTML_CONTENT = "html_content"
        const val EXTRA_TITLE = "canvas_title"
    }

    /**
     * Directorio de almacenamiento para los fragmentos de código.
     */
    private val snippetsDir: File by lazy {
        File(context.filesDir, SNIPPETS_DIR).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    // ─── Plantillas de código ──────────────────────────────────────────────

    /**
     * Plantillas de mini-aplicaciones disponibles.
     * Cada plantilla es una función que genera HTML completo.
     */
    private val codeTemplates: Map<String, () -> String> = mapOf(
        "calculadora" to { CalculatorTemplate.generate() },
        "calculator" to { CalculatorTemplate.generate() },
        "temporizador" to { TimerTemplate.generate() },
        "timer" to { TimerTemplate.generate() },
        "cronómetro" to { TimerTemplate.generate() },
        "notepad" to { NotepadTemplate.generate() },
        "bloc de notas" to { NotepadTemplate.generate() },
        "bloc" to { NotepadTemplate.generate() },
        "notas" to { NotepadTemplate.generate() },
        "todo" to { TodoTemplate.generate() },
        "tareas" to { TodoTemplate.generate() },
        "lista de tareas" to { TodoTemplate.generate() },
        "lista de quehaceres" to { TodoTemplate.generate() },
        "color picker" to { ColorPickerTemplate.generate() },
        "selector de color" to { ColorPickerTemplate.generate() },
        "colores" to { ColorPickerTemplate.generate() },
        "paleta" to { ColorPickerTemplate.generate() }
    )

    // ─── Generación y visualización ────────────────────────────────────────

    /**
     * Genera código HTML/JS basado en un prompt, lo guarda y lo muestra en WebView.
     * Este es el método principal de la habilidad.
     *
     * @param prompt Descripción en lenguaje natural de lo que se desea crear
     * @return Result con la ruta del archivo HTML generado
     */
    fun generateAndShow(prompt: String): Result<String> {
        Log.i(TAG, "Generando y mostrando canvas para: $prompt")

        val generateResult = generateCode(prompt)
        if (generateResult.isFailure) {
            return Result.failure(generateResult.exceptionOrNull()
                ?: Exception("Error desconocido generando código"))
        }

        val htmlContent = generateResult.getOrDefault("")

        val saveResult = saveSnippet(sanitizeFileName(prompt), htmlContent)
        if (saveResult.isFailure) {
            return Result.failure(saveResult.exceptionOrNull()
                ?: Exception("Error guardando fragmento"))
        }

        val filePath = saveResult.getOrDefault("")

        val showResult = showInCanvas(htmlContent)
        if (showResult.isFailure) {
            Log.w(TAG, "No se pudo mostrar en canvas, pero el archivo se guardó en: $filePath")
        }

        return Result.success(filePath)
    }

    /**
     * Genera código HTML/JS basado en un prompt descriptivo.
     * Primero busca una plantilla coincidente; si no la encuentra,
     * delega al generador de LLM (placeholder).
     *
     * @param prompt Descripción de la mini-aplicación a generar
     * @return Result con el código HTML generado
     */
    fun generateCode(prompt: String): Result<String> {
        Log.i(TAG, "Generando código para prompt: $prompt")

        val normalizedPrompt = prompt.lowercase().trim()

        // 1. Buscar coincidencia exacta con plantillas
        val exactMatch = codeTemplates[normalizedPrompt]
        if (exactMatch != null) {
            Log.i(TAG, "Plantilla encontrada para: $normalizedPrompt")
            return Result.success(exactMatch())
        }

        // 2. Buscar coincidencia parcial con plantillas
        for ((key, template) in codeTemplates) {
            if (normalizedPrompt.contains(key) || key.contains(normalizedPrompt)) {
                Log.i(TAG, "Plantilla parcial encontrada: $key para prompt: $normalizedPrompt")
                return Result.success(template())
            }
        }

        // 3. Búsqueda por palabras clave
        val keywordMatch = findTemplateByKeywords(normalizedPrompt)
        if (keywordMatch != null) {
            Log.i(TAG, "Plantilla por palabras clave encontrada: $keywordMatch")
            return Result.success(codeTemplates[keywordMatch]!!())
        }

        // 4. Generación por LLM (placeholder)
        Log.i(TAG, "No se encontró plantilla, delegando a LLM")
        return generateWithLLM(prompt)
    }

    /**
     * Muestra contenido HTML en una actividad WebView.
     *
     * @param htmlContent Contenido HTML a mostrar
     * @return Result con la ruta del archivo temporal creado
     */
    fun showInCanvas(htmlContent: String): Result<String> {
        return try {
            // Guardar en archivo temporal para que WebView pueda cargarlo
            val tempFile = File(context.cacheDir, "canvas_temp${FILE_EXTENSION}")
            FileWriter(tempFile, false).use { writer ->
                writer.write(htmlContent)
            }

            // Lanzar la actividad WebView
            val intent = Intent(ACTION_SHOW_CANVAS).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_HTML_PATH, tempFile.absolutePath)
                putExtra(EXTRA_HTML_CONTENT, htmlContent)
                putExtra(EXTRA_TITLE, "NubiaAgent Canvas")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            Log.i(TAG, "Canvas mostrado: ${tempFile.absolutePath}")
            Result.success(tempFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error mostrando canvas: ${e.message}")
            Result.failure(Exception("No se pudo mostrar el canvas: ${e.message}"))
        }
    }

    // ─── Gestión de fragmentos guardados ───────────────────────────────────

    /**
     * Guarda un fragmento de código HTML en el almacenamiento de la app.
     *
     * @param name Nombre del fragmento (se sanitizará para uso como nombre de archivo)
     * @param html Contenido HTML a guardar
     * @return Result con la ruta del archivo guardado
     */
    fun saveSnippet(name: String, html: String): Result<String> {
        return try {
            val fileName = sanitizeFileName(name) + FILE_EXTENSION
            val file = File(snippetsDir, fileName)

            FileWriter(file, false).use { writer ->
                writer.write(html)
            }

            Log.i(TAG, "Fragmento guardado: ${file.absolutePath}")
            Result.success(file.absolutePath)
        } catch (e: IOException) {
            Log.e(TAG, "Error guardando fragmento: ${e.message}")
            Result.failure(Exception("Error al guardar el fragmento: ${e.message}"))
        }
    }

    /**
     * Lista todos los fragmentos guardados.
     *
     * @return Result con lista de nombres de fragmentos
     */
    fun listSnippets(): Result<List<String>> {
        return try {
            if (!snippetsDir.exists()) {
                return Result.success(emptyList())
            }

            val snippets = snippetsDir.listFiles()
                ?.filter { it.name.endsWith(FILE_EXTENSION) }
                ?.map { it.name.removeSuffix(FILE_EXTENSION) }
                ?.sorted()
                ?: emptyList()

            Log.d(TAG, "Fragmentos encontrados: ${snippets.size}")
            Result.success(snippets)
        } catch (e: Exception) {
            Log.e(TAG, "Error listando fragmentos: ${e.message}")
            Result.failure(Exception("Error al listar fragmentos: ${e.message}"))
        }
    }

    /**
     * Carga un fragmento guardado por su nombre.
     *
     * @param name Nombre del fragmento a cargar
     * @return Result con el contenido HTML del fragmento
     */
    fun loadSnippet(name: String): Result<String> {
        return try {
            val fileName = sanitizeFileName(name) + FILE_EXTENSION
            val file = File(snippetsDir, fileName)

            if (!file.exists()) {
                return Result.failure(Exception("Fragmento no encontrado: $name"))
            }

            val content = file.readText(CHARSET)
            Log.d(TAG, "Fragmento cargado: $name (${content.length} caracteres)")
            Result.success(content)
        } catch (e: IOException) {
            Log.e(TAG, "Error cargando fragmento: ${e.message}")
            Result.failure(Exception("Error al cargar el fragmento: ${e.message}"))
        }
    }

    /**
     * Elimina un fragmento guardado.
     */
    fun deleteSnippet(name: String): Result<Boolean> {
        return try {
            val fileName = sanitizeFileName(name) + FILE_EXTENSION
            val file = File(snippetsDir, fileName)

            if (!file.exists()) {
                return Result.success(false)
            }

            val deleted = file.delete()
            Log.i(TAG, "Fragmento eliminado: $name (eliminado=$deleted)")
            Result.success(deleted)
        } catch (e: Exception) {
            Result.failure(Exception("Error al eliminar fragmento: ${e.message}"))
        }
    }

    // ─── Generación por LLM (placeholder) ──────────────────────────────────

    /**
     * Genera código HTML/JS usando un modelo de lenguaje.
     * PLACEHOLDER: La integración con el LLM se implementará cuando el
     * motor de inferencia local esté disponible.
     *
     * @param prompt Descripción de lo que se desea generar
     * @return Result con el código HTML generado
     */
    private fun generateWithLLM(prompt: String): Result<String> {
        Log.i(TAG, "Solicitando generación por LLM para: $prompt")

        // TODO: Integrar con motor de inferencia local o API de LLM
        // El flujo será:
        // 1. Construir prompt del sistema con instrucciones de generación HTML/JS
        // 2. Enviar prompt del usuario al LLM
        // 3. Extraer el código HTML de la respuesta
        // 4. Validar que el código es HTML bien formado
        // 5. Devolver el código

        // Por ahora, generar un canvas placeholder informativo
        val placeholderHtml = generatePlaceholderCanvas(prompt)

        return Result.success(placeholderHtml)
    }

    /**
     * Genera un canvas placeholder cuando no hay plantilla ni LLM disponible.
     */
    private fun generatePlaceholderCanvas(prompt: String): String {
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))
            .format(Date())

        return """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Canvas - \${escapeHtml(prompt)}</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
            color: #e0e0e0;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }

        .container {
            max-width: 500px;
            width: 100%;
            background: rgba(255, 255, 255, 0.05);
            border-radius: 20px;
            padding: 30px;
            backdrop-filter: blur(10px);
            border: 1px solid rgba(255, 255, 255, 0.1);
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
        }

        .icon {
            font-size: 64px;
            text-align: center;
            margin-bottom: 20px;
        }

        h1 {
            font-size: 22px;
            font-weight: 600;
            text-align: center;
            color: #fff;
            margin-bottom: 10px;
        }

        .prompt {
            background: rgba(255, 255, 255, 0.08);
            border-radius: 12px;
            padding: 14px 18px;
            margin: 16px 0;
            font-style: italic;
            color: #a0c4ff;
            text-align: center;
            border-left: 3px solid #a0c4ff;
        }

        .status {
            text-align: center;
            margin: 20px 0;
            line-height: 1.6;
            color: #b0b0b0;
        }

        .badge {
            display: inline-block;
            background: rgba(255, 193, 7, 0.2);
            color: #ffc107;
            padding: 4px 12px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: 500;
            margin-top: 8px;
        }

        .timestamp {
            text-align: center;
            font-size: 12px;
            color: #666;
            margin-top: 20px;
        }

        .templates {
            margin-top: 24px;
            text-align: center;
        }

        .templates h3 {
            font-size: 14px;
            color: #888;
            margin-bottom: 12px;
        }

        .template-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 8px;
        }

        .template-btn {
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid rgba(255, 255, 255, 0.12);
            color: #d0d0d0;
            padding: 10px;
            border-radius: 10px;
            cursor: pointer;
            font-size: 13px;
            transition: all 0.2s ease;
        }

        .template-btn:hover {
            background: rgba(255, 255, 255, 0.15);
            border-color: rgba(255, 255, 255, 0.25);
            color: #fff;
        }

        .template-btn:active {
            transform: scale(0.97);
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="icon">🎨</div>
        <h1>Canvas Generado</h1>
        <div class="prompt">"\${escapeHtml(prompt)}"</div>
        <div class="status">
            La generación personalizada con IA estará disponible próximamente.<br>
            <span class="badge">Generación por LLM pendiente</span>
        </div>
        <div class="templates">
            <h3>Plantillas disponibles</h3>
            <div class="template-grid">
                <div class="template-btn">🔢 Calculadora</div>
                <div class="template-btn">⏱️ Temporizador</div>
                <div class="template-btn">📝 Bloc de Notas</div>
                <div class="template-btn">✅ Lista de Tareas</div>
                <div class="template-btn">🎨 Selector de Color</div>
                <div class="template-btn">🔄 Más pronto...</div>
            </div>
        </div>
        <div class="timestamp">Generado el $timestamp</div>
    </div>
</body>
</html>
        """.trimIndent()
    }

    // ─── Búsqueda por palabras clave ───────────────────────────────────────

    private fun findTemplateByKeywords(prompt: String): String? {
        val keywordMap = mapOf(
            "calculadora" to listOf("calcul", "sumar", "restar", "multiplicar", "dividir", "math", "operacion", "aritmetica"),
            "temporizador" to listOf("tiempo", "timer", "cuenta", "atrás", "atras", "countdown", "alarma", "cronometro", "cronómetro"),
            "notepad" to listOf("nota", "escribir", "texto", "editor", "apuntar", "memorizar", "bloc"),
            "todo" to listOf("tarea", "pendiente", "hacer", "quehacer", "checklist", "lista", "completar"),
            "color picker" to listOf("color", "rgb", "hex", "paleta", "tono", "hsl", "seleccionar color")
        )

        for ((templateKey, keywords) in keywordMap) {
            for (keyword in keywords) {
                if (prompt.contains(keyword)) {
                    return templateKey
                }
            }
        }
        return null
    }

    // ─── Utilidades ────────────────────────────────────────────────────────

    private fun sanitizeFileName(name: String): String {
        return name.lowercase()
            .replace(Regex("[^a-záéíóúñü0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(50)
            .ifBlank { "snippet_${System.currentTimeMillis()}" }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Devuelve un resumen en español de las plantillas disponibles y fragmentos guardados.
     */
    fun getStatusSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("🎨 VibeCoder - Generador de Canvas")
        sb.appendLine("═══════════════════════════════════")

        sb.appendLine("\n📋 Plantillas disponibles:")
        sb.appendLine("  🔢 Calculadora")
        sb.appendLine("  ⏱️ Temporizador/Cronómetro")
        sb.appendLine("  📝 Bloc de Notas")
        sb.appendLine("  ✅ Lista de Tareas")
        sb.appendLine("  🎨 Selector de Color")

        val snippets = listSnippets().getOrDefault(emptyList())
        sb.appendLine("\n💾 Fragmentos guardados: ${snippets.size}")
        snippets.forEach { name ->
            sb.appendLine("  • $name")
        }

        return sb.toString()
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// PLANTILLAS DE CÓDIGO HTML/JS
// Cada plantilla genera una mini-aplicación completa y autocontenida.
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Plantilla de calculadora con diseño moderno.
 */
private object CalculatorTemplate {
    fun generate(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Calculadora</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #0c0c1d 0%, #1a1a2e 100%);
            display: flex; align-items: center; justify-content: center;
            min-height: 100vh; padding: 16px;
        }
        .calc {
            width: 100%; max-width: 340px;
            background: rgba(255,255,255,0.05); border-radius: 24px;
            padding: 24px; backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.08);
            box-shadow: 0 12px 40px rgba(0,0,0,0.4);
        }
        .display {
            background: rgba(0,0,0,0.3); border-radius: 16px;
            padding: 20px; margin-bottom: 20px; text-align: right;
            min-height: 90px; display: flex; flex-direction: column;
            justify-content: flex-end;
        }
        .expression { color: #888; font-size: 16px; margin-bottom: 8px; min-height: 20px; word-break: break-all; }
        .result { color: #fff; font-size: 36px; font-weight: 300; word-break: break-all; }
        .grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
        .btn {
            border: none; border-radius: 14px; font-size: 20px;
            padding: 18px 0; cursor: pointer; transition: all 0.15s;
            font-weight: 500; -webkit-tap-highlight-color: transparent;
        }
        .btn:active { transform: scale(0.93); }
        .btn-num { background: rgba(255,255,255,0.08); color: #fff; }
        .btn-num:hover { background: rgba(255,255,255,0.14); }
        .btn-op { background: rgba(100,140,255,0.2); color: #8ab4ff; }
        .btn-op:hover { background: rgba(100,140,255,0.3); }
        .btn-func { background: rgba(255,255,255,0.04); color: #aaa; }
        .btn-func:hover { background: rgba(255,255,255,0.1); }
        .btn-eq { background: linear-gradient(135deg, #4f6fff, #6c5ce7); color: #fff; }
        .btn-eq:hover { background: linear-gradient(135deg, #5e7fff, #7b6cf7); }
        .btn-span2 { grid-column: span 2; }
    </style>
</head>
<body>
    <div class="calc">
        <div class="display">
            <div class="expression" id="expr"></div>
            <div class="result" id="result">0</div>
        </div>
        <div class="grid">
            <button class="btn btn-func" onclick="clearAll()">C</button>
            <button class="btn btn-func" onclick="toggleSign()">±</button>
            <button class="btn btn-func" onclick="percent()">%</button>
            <button class="btn btn-op" onclick="addOp('/')">÷</button>
            <button class="btn btn-num" onclick="addNum('7')">7</button>
            <button class="btn btn-num" onclick="addNum('8')">8</button>
            <button class="btn btn-num" onclick="addNum('9')">9</button>
            <button class="btn btn-op" onclick="addOp('*')">×</button>
            <button class="btn btn-num" onclick="addNum('4')">4</button>
            <button class="btn btn-num" onclick="addNum('5')">5</button>
            <button class="btn btn-num" onclick="addNum('6')">6</button>
            <button class="btn btn-op" onclick="addOp('-')">−</button>
            <button class="btn btn-num" onclick="addNum('1')">1</button>
            <button class="btn btn-num" onclick="addNum('2')">2</button>
            <button class="btn btn-num" onclick="addNum('3')">3</button>
            <button class="btn btn-op" onclick="addOp('+')">+</button>
            <button class="btn btn-num btn-span2" onclick="addNum('0')">0</button>
            <button class="btn btn-num" onclick="addDot()">.</button>
            <button class="btn btn-eq" onclick="calculate()">=</button>
        </div>
    </div>
    <script>
        let expr = '';
        let displayExpr = '';
        let justCalculated = false;

        function updateDisplay() {
            document.getElementById('expr').textContent = displayExpr;
            document.getElementById('result').textContent = expr || '0';
        }

        function addNum(n) {
            if (justCalculated) { expr = ''; displayExpr = ''; justCalculated = false; }
            expr += n;
            displayExpr += n;
            updateDisplay();
        }

        function addDot() {
            if (justCalculated) { expr = '0'; displayExpr = '0'; justCalculated = false; }
            expr += '.';
            displayExpr += '.';
            updateDisplay();
        }

        function addOp(op) {
            justCalculated = false;
            const symbols = {'*':'×', '/':'÷', '+':'+', '-':'−'};
            expr += op;
            displayExpr += ' ' + (symbols[op] || op) + ' ';
            updateDisplay();
        }

        function clearAll() {
            expr = ''; displayExpr = '';
            justCalculated = false;
            updateDisplay();
        }

        function toggleSign() {
            try {
                let val = eval(expr);
                expr = String(-val);
                displayExpr = expr;
                updateDisplay();
            } catch(e) {}
        }

        function percent() {
            try {
                let val = eval(expr);
                expr = String(val / 100);
                displayExpr = expr;
                updateDisplay();
            } catch(e) {}
        }

        function calculate() {
            try {
                let val = eval(expr);
                if (typeof val === 'number' && !isFinite(val)) {
                    document.getElementById('result').textContent = 'Error';
                    return;
                }
                let rounded = Math.round(val * 1e10) / 1e10;
                displayExpr = displayExpr + ' =';
                expr = String(rounded);
                justCalculated = true;
                updateDisplay();
            } catch(e) {
                document.getElementById('result').textContent = 'Error';
            }
        }
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Plantilla de temporizador/countdown con diseño moderno.
 */
private object TimerTemplate {
    fun generate(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Temporizador</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #0c0c1d 0%, #1a1a2e 100%);
            display: flex; align-items: center; justify-content: center;
            min-height: 100vh; padding: 16px; color: #fff;
        }
        .container {
            width: 100%; max-width: 360px; text-align: center;
            background: rgba(255,255,255,0.05); border-radius: 24px;
            padding: 32px 24px; backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.08);
            box-shadow: 0 12px 40px rgba(0,0,0,0.4);
        }
        h1 { font-size: 20px; font-weight: 500; margin-bottom: 24px; color: #8ab4ff; }
        .timer-display {
            font-size: 56px; font-weight: 200; margin: 20px 0;
            font-variant-numeric: tabular-nums; letter-spacing: 2px;
        }
        .timer-label { color: #666; font-size: 14px; margin-bottom: 20px; }
        .time-inputs { display: flex; gap: 10px; justify-content: center; margin-bottom: 24px; }
        .time-input-group { display: flex; flex-direction: column; align-items: center; }
        .time-input-group label { font-size: 11px; color: #888; margin-top: 4px; }
        .time-input {
            width: 64px; background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.12);
            border-radius: 12px; color: #fff; font-size: 24px; text-align: center;
            padding: 10px 4px; outline: none;
        }
        .time-input:focus { border-color: #4f6fff; }
        .presets { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; margin-bottom: 24px; }
        .preset-btn {
            background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1);
            color: #ccc; padding: 8px 14px; border-radius: 20px; font-size: 13px;
            cursor: pointer; transition: all 0.15s;
        }
        .preset-btn:hover { background: rgba(79,111,255,0.2); color: #8ab4ff; border-color: rgba(79,111,255,0.3); }
        .controls { display: flex; gap: 12px; justify-content: center; }
        .ctrl-btn {
            border: none; border-radius: 50%; width: 64px; height: 64px;
            font-size: 24px; cursor: pointer; transition: all 0.15s;
            display: flex; align-items: center; justify-content: center;
        }
        .ctrl-btn:active { transform: scale(0.9); }
        .btn-start { background: linear-gradient(135deg, #00b894, #00a070); color: #fff; }
        .btn-pause { background: linear-gradient(135deg, #fdcb6e, #e17055); color: #fff; }
        .btn-reset { background: rgba(255,255,255,0.08); color: #aaa; }
        .btn-reset:hover { background: rgba(255,255,255,0.15); }
        .progress-ring { margin: 0 auto 16px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>⏱️ Temporizador</h1>
        <svg class="progress-ring" width="180" height="180" viewBox="0 0 180 180">
            <circle cx="90" cy="90" r="80" stroke="rgba(255,255,255,0.06)" stroke-width="6" fill="none"/>
            <circle id="progress" cx="90" cy="90" r="80" stroke="#4f6fff" stroke-width="6" fill="none"
                stroke-dasharray="502.65" stroke-dashoffset="0" stroke-linecap="round"
                transform="rotate(-90 90 90)" style="transition: stroke-dashoffset 0.5s;"/>
        </svg>
        <div class="timer-display" id="display">00:00:00</div>
        <div class="timer-label" id="statusLabel">Configure el tiempo</div>
        <div class="time-inputs" id="inputs">
            <div class="time-input-group">
                <input type="number" class="time-input" id="hours" min="0" max="23" value="0">
                <label>Horas</label>
            </div>
            <div class="time-input-group">
                <input type="number" class="time-input" id="minutes" min="0" max="59" value="5">
                <label>Minutos</label>
            </div>
            <div class="time-input-group">
                <input type="number" class="time-input" id="seconds" min="0" max="59" value="0">
                <label>Segundos</label>
            </div>
        </div>
        <div class="presets">
            <button class="preset-btn" onclick="setPreset(1,0)">1 min</button>
            <button class="preset-btn" onclick="setPreset(3,0)">3 min</button>
            <button class="preset-btn" onclick="setPreset(5,0)">5 min</button>
            <button class="preset-btn" onclick="setPreset(10,0)">10 min</button>
            <button class="preset-btn" onclick="setPreset(25,0)">25 min</button>
            <button class="preset-btn" onclick="setPreset(0,30)">30 seg</button>
        </div>
        <div class="controls">
            <button class="ctrl-btn btn-reset" onclick="resetTimer()">↺</button>
            <button class="ctrl-btn btn-start" id="startBtn" onclick="toggleTimer()">▶</button>
        </div>
    </div>
    <script>
        let totalSeconds = 0, remaining = 0, interval = null, running = false;
        const CIRC = 2 * Math.PI * 80;

        function setPreset(m, s) {
            if (running) return;
            document.getElementById('hours').value = 0;
            document.getElementById('minutes').value = m;
            document.getElementById('seconds').value = s;
            updateFromInputs();
        }

        function updateFromInputs() {
            const h = parseInt(document.getElementById('hours').value) || 0;
            const m = parseInt(document.getElementById('minutes').value) || 0;
            const s = parseInt(document.getElementById('seconds').value) || 0;
            totalSeconds = h * 3600 + m * 60 + s;
            remaining = totalSeconds;
            updateDisplay();
        }

        function formatTime(sec) {
            const h = Math.floor(sec / 3600);
            const m = Math.floor((sec % 3600) / 60);
            const s = sec % 60;
            return [h,m,s].map(v => String(v).padStart(2,'0')).join(':');
        }

        function updateDisplay() {
            document.getElementById('display').textContent = formatTime(remaining);
            const pct = totalSeconds > 0 ? (1 - remaining / totalSeconds) : 0;
            document.getElementById('progress').style.strokeDashoffset = CIRC * (1 - pct);
            if (remaining <= 10 && remaining > 0 && running) {
                document.getElementById('display').style.color = '#ff6b6b';
            } else {
                document.getElementById('display').style.color = '#fff';
            }
        }

        function toggleTimer() {
            if (running) {
                clearInterval(interval); running = false;
                document.getElementById('startBtn').innerHTML = '▶';
                document.getElementById('startBtn').className = 'ctrl-btn btn-start';
                document.getElementById('statusLabel').textContent = 'Pausado';
            } else {
                if (remaining <= 0) updateFromInputs();
                if (remaining <= 0) return;
                running = true;
                document.getElementById('startBtn').innerHTML = '⏸';
                document.getElementById('startBtn').className = 'ctrl-btn btn-pause';
                document.getElementById('inputs').style.opacity = '0.3';
                document.getElementById('statusLabel').textContent = 'En curso...';
                interval = setInterval(() => {
                    remaining--;
                    updateDisplay();
                    if (remaining <= 0) {
                        clearInterval(interval); running = false;
                        document.getElementById('startBtn').innerHTML = '▶';
                        document.getElementById('startBtn').className = 'ctrl-btn btn-start';
                        document.getElementById('statusLabel').textContent = '¡Tiempo terminado!';
                        document.getElementById('display').style.color = '#00b894';
                        try { new Audio('data:audio/wav;base64,UklGRnoGAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQoGAACBhYqFbF1fdH2JkZeWl5CRi4J7dXV0cYGJlJqboKGJhIB5dnZ1eICOmKCmsLe+raCah4F6eHd4fYKTnaWrtca9').play(); } catch(e){}
                    }
                }, 1000);
            }
        }

        function resetTimer() {
            clearInterval(interval); running = false;
            document.getElementById('startBtn').innerHTML = '▶';
            document.getElementById('startBtn').className = 'ctrl-btn btn-start';
            document.getElementById('inputs').style.opacity = '1';
            document.getElementById('statusLabel').textContent = 'Configure el tiempo';
            updateFromInputs();
        }

        updateFromInputs();
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Plantilla de bloc de notas con persistencia local.
 */
private object NotepadTemplate {
    fun generate(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Bloc de Notas</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #0c0c1d 0%, #1a1a2e 100%);
            min-height: 100vh; padding: 16px; color: #fff;
        }
        .container {
            max-width: 500px; margin: 0 auto;
            background: rgba(255,255,255,0.05); border-radius: 24px;
            padding: 24px; backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.08);
            box-shadow: 0 12px 40px rgba(0,0,0,0.4);
        }
        h1 { font-size: 20px; font-weight: 500; margin-bottom: 16px; color: #8ab4ff; }
        .toolbar {
            display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap;
        }
        .tool-btn {
            background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.1);
            color: #ccc; padding: 6px 12px; border-radius: 8px; font-size: 13px;
            cursor: pointer; transition: all 0.15s;
        }
        .tool-btn:hover { background: rgba(79,111,255,0.2); color: #8ab4ff; }
        .tool-btn.active { background: rgba(79,111,255,0.3); color: #8ab4ff; border-color: rgba(79,111,255,0.4); }
        .char-count { font-size: 12px; color: #666; margin-bottom: 8px; text-align: right; }
        textarea {
            width: 100%; height: 350px; background: rgba(0,0,0,0.3);
            border: 1px solid rgba(255,255,255,0.1); border-radius: 14px;
            color: #e0e0e0; font-size: 16px; padding: 16px; outline: none;
            resize: vertical; line-height: 1.6;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
        }
        textarea:focus { border-color: rgba(79,111,255,0.5); }
        .saved-indicator {
            text-align: center; font-size: 12px; color: #555; margin-top: 10px;
            transition: color 0.3s;
        }
        .saved-indicator.saved { color: #00b894; }
    </style>
</head>
<body>
    <div class="container">
        <h1>📝 Bloc de Notas</h1>
        <div class="toolbar">
            <button class="tool-btn" onclick="clearNote()">🗑️ Limpiar</button>
            <button class="tool-btn" onclick="copyNote()">📋 Copiar</button>
            <button class="tool-btn" onclick="selectAll()">📑 Seleccionar todo</button>
            <button class="tool-btn" id="fontBtn" onclick="toggleFont()">🔤 Fuente</button>
        </div>
        <div class="char-count" id="charCount">0 caracteres · 0 palabras</div>
        <textarea id="notepad" placeholder="Escribe aquí..." oninput="onInput()"></textarea>
        <div class="saved-indicator" id="savedIndicator">Los cambios se guardan automáticamente</div>
    </div>
    <script>
        const STORAGE_KEY = 'nubia_notepad_content';
        const FONT_KEY = 'nubia_notepad_font';
        const notepad = document.getElementById('notepad');
        const fonts = ['sans-serif', 'serif', 'monospace'];
        let fontIdx = parseInt(localStorage.getItem(FONT_KEY) || '0');
        let saveTimeout;

        function loadContent() {
            const saved = localStorage.getItem(STORAGE_KEY);
            if (saved) notepad.value = saved;
            notepad.style.fontFamily = fonts[fontIdx];
            updateCount();
        }

        function onInput() {
            updateCount();
            clearTimeout(saveTimeout);
            const indicator = document.getElementById('savedIndicator');
            indicator.textContent = 'Guardando...';
            indicator.className = 'saved-indicator';
            saveTimeout = setTimeout(() => {
                localStorage.setItem(STORAGE_KEY, notepad.value);
                indicator.textContent = '✓ Guardado';
                indicator.className = 'saved-indicator saved';
                setTimeout(() => {
                    indicator.textContent = 'Los cambios se guardan automáticamente';
                    indicator.className = 'saved-indicator';
                }, 2000);
            }, 500);
        }

        function updateCount() {
            const text = notepad.value;
            const chars = text.length;
            const words = text.trim() ? text.trim().split(/\s+/).length : 0;
            document.getElementById('charCount').textContent = chars + ' caracteres · ' + words + ' palabras';
        }

        function clearNote() {
            if (notepad.value && confirm('¿Borrar todo el contenido?')) {
                notepad.value = '';
                localStorage.setItem(STORAGE_KEY, '');
                updateCount();
            }
        }

        function copyNote() {
            navigator.clipboard.writeText(notepad.value).then(() => {
                const indicator = document.getElementById('savedIndicator');
                indicator.textContent = '✓ Copiado al portapapeles';
                indicator.className = 'saved-indicator saved';
                setTimeout(() => {
                    indicator.textContent = 'Los cambios se guardan automáticamente';
                    indicator.className = 'saved-indicator';
                }, 2000);
            });
        }

        function selectAll() { notepad.select(); }

        function toggleFont() {
            fontIdx = (fontIdx + 1) % fonts.length;
            notepad.style.fontFamily = fonts[fontIdx];
            localStorage.setItem(FONT_KEY, fontIdx);
        }

        loadContent();
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Plantilla de lista de tareas con persistencia local.
 */
private object TodoTemplate {
    fun generate(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Lista de Tareas</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #0c0c1d 0%, #1a1a2e 100%);
            min-height: 100vh; padding: 16px; color: #fff;
        }
        .container {
            max-width: 440px; margin: 0 auto;
            background: rgba(255,255,255,0.05); border-radius: 24px;
            padding: 24px; backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.08);
            box-shadow: 0 12px 40px rgba(0,0,0,0.4);
        }
        h1 { font-size: 20px; font-weight: 500; margin-bottom: 16px; color: #8ab4ff; }
        .stats { display: flex; gap: 12px; margin-bottom: 16px; }
        .stat {
            flex: 1; text-align: center; background: rgba(255,255,255,0.04);
            border-radius: 12px; padding: 10px;
        }
        .stat-num { font-size: 22px; font-weight: 600; color: #fff; }
        .stat-label { font-size: 11px; color: #888; margin-top: 2px; }
        .input-row { display: flex; gap: 8px; margin-bottom: 16px; }
        .input-row input {
            flex: 1; background: rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.1);
            border-radius: 12px; color: #fff; padding: 12px 16px; font-size: 15px; outline: none;
        }
        .input-row input:focus { border-color: rgba(79,111,255,0.5); }
        .input-row input::placeholder { color: #555; }
        .add-btn {
            background: linear-gradient(135deg, #4f6fff, #6c5ce7); border: none;
            color: #fff; border-radius: 12px; padding: 0 20px; font-size: 22px;
            cursor: pointer; transition: all 0.15s;
        }
        .add-btn:active { transform: scale(0.95); }
        .filters { display: flex; gap: 6px; margin-bottom: 14px; }
        .filter-btn {
            background: rgba(255,255,255,0.04); border: 1px solid transparent;
            color: #888; padding: 6px 14px; border-radius: 16px; font-size: 12px;
            cursor: pointer; transition: all 0.15s;
        }
        .filter-btn.active { background: rgba(79,111,255,0.15); color: #8ab4ff; border-color: rgba(79,111,255,0.3); }
        .todo-list { list-style: none; }
        .todo-item {
            display: flex; align-items: center; gap: 12px; padding: 14px;
            background: rgba(255,255,255,0.03); border-radius: 12px;
            margin-bottom: 8px; transition: all 0.2s;
        }
        .todo-item:hover { background: rgba(255,255,255,0.06); }
        .todo-item.done { opacity: 0.5; }
        .todo-item.done .todo-text { text-decoration: line-through; color: #666; }
        .checkbox {
            width: 22px; height: 22px; border-radius: 50%;
            border: 2px solid rgba(255,255,255,0.2); cursor: pointer;
            display: flex; align-items: center; justify-content: center;
            transition: all 0.2s; flex-shrink: 0;
        }
        .checkbox.checked { background: #00b894; border-color: #00b894; }
        .checkbox.checked::after { content: '✓'; font-size: 13px; color: #fff; }
        .todo-text { flex: 1; font-size: 15px; }
        .delete-btn {
            background: none; border: none; color: #555; font-size: 18px;
            cursor: pointer; padding: 4px 8px; border-radius: 8px;
            transition: all 0.15s; opacity: 0;
        }
        .todo-item:hover .delete-btn { opacity: 1; }
        .delete-btn:hover { color: #ff6b6b; background: rgba(255,107,107,0.1); }
        .empty { text-align: center; color: #555; padding: 40px 0; font-size: 14px; }
    </style>
</head>
<body>
    <div class="container">
        <h1>✅ Lista de Tareas</h1>
        <div class="stats">
            <div class="stat"><div class="stat-num" id="totalStat">0</div><div class="stat-label">Total</div></div>
            <div class="stat"><div class="stat-num" id="doneStat">0</div><div class="stat-label">Hechas</div></div>
            <div class="stat"><div class="stat-num" id="pendingStat">0</div><div class="stat-label">Pendientes</div></div>
        </div>
        <div class="input-row">
            <input type="text" id="todoInput" placeholder="Nueva tarea..." onkeydown="if(event.key==='Enter')addTodo()">
            <button class="add-btn" onclick="addTodo()">+</button>
        </div>
        <div class="filters">
            <button class="filter-btn active" onclick="setFilter('all', this)">Todas</button>
            <button class="filter-btn" onclick="setFilter('pending', this)">Pendientes</button>
            <button class="filter-btn" onclick="setFilter('done', this)">Completadas</button>
        </div>
        <ul class="todo-list" id="todoList"></ul>
    </div>
    <script>
        const STORAGE_KEY = 'nubia_todos';
        let todos = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
        let filter = 'all';

        function save() { localStorage.setItem(STORAGE_KEY, JSON.stringify(todos)); }

        function addTodo() {
            const input = document.getElementById('todoInput');
            const text = input.value.trim();
            if (!text) return;
            todos.unshift({ id: Date.now(), text, done: false });
            input.value = '';
            save(); render();
        }

        function toggleTodo(id) {
            const todo = todos.find(t => t.id === id);
            if (todo) todo.done = !todo.done;
            save(); render();
        }

        function deleteTodo(id) {
            todos = todos.filter(t => t.id !== id);
            save(); render();
        }

        function setFilter(f, btn) {
            filter = f;
            document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            render();
        }

        function render() {
            const list = document.getElementById('todoList');
            const filtered = todos.filter(t => {
                if (filter === 'done') return t.done;
                if (filter === 'pending') return !t.done;
                return true;
            });

            list.innerHTML = filtered.length ? filtered.map(t => `
                <li class="todo-item \${t.done ? 'done' : ''}">
                    <div class="checkbox \${t.done ? 'checked' : ''}" onclick="toggleTodo(\${t.id})"></div>
                    <span class="todo-text">\${escapeHtml(t.text)}</span>
                    <button class="delete-btn" onclick="deleteTodo(\${t.id})">✕</button>
                </li>
            `).join('') : '<div class="empty">No hay tareas que mostrar</div>';

            const done = todos.filter(t => t.done).length;
            document.getElementById('totalStat').textContent = todos.length;
            document.getElementById('doneStat').textContent = done;
            document.getElementById('pendingStat').textContent = todos.length - done;
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        render();
    </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Plantilla de selector de color con vista previa y copia de valores.
 */
private object ColorPickerTemplate {
    fun generate(): String = """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Selector de Color</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: linear-gradient(135deg, #0c0c1d 0%, #1a1a2e 100%);
            min-height: 100vh; padding: 16px; color: #fff;
            display: flex; align-items: center; justify-content: center;
        }
        .container {
            width: 100%; max-width: 400px;
            background: rgba(255,255,255,0.05); border-radius: 24px;
            padding: 24px; backdrop-filter: blur(10px);
            border: 1px solid rgba(255,255,255,0.08);
            box-shadow: 0 12px 40px rgba(0,0,0,0.4);
        }
        h1 { font-size: 20px; font-weight: 500; margin-bottom: 20px; color: #8ab4ff; }
        .preview {
            width: 100%; height: 140px; border-radius: 18px;
            margin-bottom: 20px; transition: background 0.2s;
            display: flex; align-items: center; justify-content: center;
            font-size: 28px; font-weight: 600;
            text-shadow: 0 1px 4px rgba(0,0,0,0.3);
            border: 2px solid rgba(255,255,255,0.1);
        }
        .slider-group { margin-bottom: 14px; }
        .slider-label {
            display: flex; justify-content: space-between; margin-bottom: 6px;
            font-size: 13px; color: #aaa;
        }
        .slider-label span:last-child { color: #fff; font-weight: 500; }
        input[type="range"] {
            width: 100%; height: 8px; -webkit-appearance: none;
            border-radius: 4px; outline: none; cursor: pointer;
        }
        input[type="range"]::-webkit-slider-thumb {
            -webkit-appearance: none; width: 22px; height: 22px;
            border-radius: 50%; background: #fff; cursor: pointer;
            box-shadow: 0 2px 8px rgba(0,0,0,0.3);
        }
        #rSlider { background: linear-gradient(to right, #000, #ff0000); }
        #gSlider { background: linear-gradient(to right, #000, #00ff00); }
        #bSlider { background: linear-gradient(to right, #000, #0000ff); }
        .values {
            background: rgba(0,0,0,0.3); border-radius: 14px;
            padding: 16px; margin-top: 16px;
        }
        .value-row {
            display: flex; justify-content: space-between; align-items: center;
            padding: 8px 0; border-bottom: 1px solid rgba(255,255,255,0.05);
        }
        .value-row:last-child { border-bottom: none; }
        .value-label { color: #888; font-size: 13px; }
        .value-text { color: #fff; font-size: 14px; font-family: monospace; }
        .copy-btn {
            background: rgba(255,255,255,0.08); border: none; color: #aaa;
            padding: 4px 10px; border-radius: 6px; font-size: 11px;
            cursor: pointer; margin-left: 8px;
        }
        .copy-btn:hover { background: rgba(79,111,255,0.2); color: #8ab4ff; }
        .native-picker { margin-top: 14px; text-align: center; }
        .native-picker input[type="color"] {
            width: 48px; height: 48px; border: none; border-radius: 12px;
            cursor: pointer; background: none;
        }
        .palette { margin-top: 16px; }
        .palette-label { font-size: 12px; color: #888; margin-bottom: 8px; }
        .palette-colors { display: flex; gap: 6px; flex-wrap: wrap; }
        .palette-swatch {
            width: 36px; height: 36px; border-radius: 10px; cursor: pointer;
            border: 2px solid transparent; transition: all 0.15s;
        }
        .palette-swatch:hover { border-color: #fff; transform: scale(1.1); }
    </style>
</head>
<body>
    <div class="container">
        <h1>🎨 Selector de Color</h1>
        <div class="preview" id="preview">#4F6FFF</div>
        <div class="slider-group">
            <div class="slider-label"><span>Rojo</span><span id="rVal">79</span></div>
            <input type="range" id="rSlider" min="0" max="255" value="79" oninput="updateFromSliders()">
        </div>
        <div class="slider-group">
            <div class="slider-label"><span>Verde</span><span id="gVal">111</span></div>
            <input type="range" id="gSlider" min="0" max="255" value="111" oninput="updateFromSliders()">
        </div>
        <div class="slider-group">
            <div class="slider-label"><span>Azul</span><span id="bVal">255</span></div>
            <input type="range" id="bSlider" min="0" max="255" value="255" oninput="updateFromSliders()">
        </div>
        <div class="native-picker">
            <input type="color" id="nativeColor" value="#4f6fff" onchange="updateFromNative(this.value)">
        </div>
        <div class="values">
            <div class="value-row">
                <span class="value-label">HEX</span>
                <span><span class="value-text" id="hexVal">#4F6FFF</span><button class="copy-btn" onclick="copyValue('hexVal')">Copiar</button></span>
            </div>
            <div class="value-row">
                <span class="value-label">RGB</span>
                <span><span class="value-text" id="rgbVal">rgb(79, 111, 255)</span><button class="copy-btn" onclick="copyValue('rgbVal')">Copiar</button></span>
            </div>
            <div class="value-row">
                <span class="value-label">HSL</span>
                <span><span class="value-text" id="hslVal">hsl(229, 100%, 65%)</span><button class="copy-btn" onclick="copyValue('hslVal')">Copiar</button></span>
            </div>
        </div>
        <div class="palette">
            <div class="palette-label">Paleta rápida</div>
            <div class="palette-colors" id="palette"></div>
        </div>
    </div>
    <script>
        const presetColors = [
            '#ff6b6b','#ee5a24','#ffc048','#ffeaa7','#00b894','#55efc4',
            '#00cec9','#0984e3','#6c5ce7','#a29bfe','#fd79a8','#e84393',
            '#2d3436','#636e72','#b2bec3','#dfe6e9','#ffffff','#000000'
        ];

        function initPalette() {
            const container = document.getElementById('palette');
            presetColors.forEach(color => {
                const swatch = document.createElement('div');
                swatch.className = 'palette-swatch';
                swatch.style.background = color;
                swatch.onclick = () => updateFromNative(color);
                container.appendChild(swatch);
            });
        }

        function rgbToHex(r, g, b) {
            return '#' + [r,g,b].map(v => v.toString(16).padStart(2,'0')).join('').toUpperCase();
        }

        function rgbToHsl(r, g, b) {
            r /= 255; g /= 255; b /= 255;
            const max = Math.max(r,g,b), min = Math.min(r,g,b);
            let h, s, l = (max + min) / 2;
            if (max === min) { h = s = 0; }
            else {
                const d = max - min;
                s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
                switch(max) {
                    case r: h = ((g - b) / d + (g < b ? 6 : 0)) / 6; break;
                    case g: h = ((b - r) / d + 2) / 6; break;
                    case b: h = ((r - g) / d + 4) / 6; break;
                }
            }
            return { h: Math.round(h * 360), s: Math.round(s * 100), l: Math.round(l * 100) };
        }

        function updateColor(r, g, b) {
            const hex = rgbToHex(r, g, b);
            const hsl = rgbToHsl(r, g, b);
            const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;

            document.getElementById('preview').style.background = hex;
            document.getElementById('preview').style.color = luminance > 0.5 ? '#000' : '#fff';
            document.getElementById('preview').textContent = hex;

            document.getElementById('rSlider').value = r;
            document.getElementById('gSlider').value = g;
            document.getElementById('bSlider').value = b;
            document.getElementById('rVal').textContent = r;
            document.getElementById('gVal').textContent = g;
            document.getElementById('bVal').textContent = b;

            document.getElementById('hexVal').textContent = hex;
            document.getElementById('rgbVal').textContent = 'rgb(' + r + ', ' + g + ', ' + b + ')';
            document.getElementById('hslVal').textContent = 'hsl(' + hsl.h + ', ' + hsl.s + '%, ' + hsl.l + '%)';

            document.getElementById('nativeColor').value = hex;
        }

        function updateFromSliders() {
            updateColor(
                parseInt(document.getElementById('rSlider').value),
                parseInt(document.getElementById('gSlider').value),
                parseInt(document.getElementById('bSlider').value)
            );
        }

        function updateFromNative(hex) {
            const r = parseInt(hex.substr(1,2), 16);
            const g = parseInt(hex.substr(3,2), 16);
            const b = parseInt(hex.substr(5,2), 16);
            updateColor(r, g, b);
        }

        function copyValue(id) {
            const text = document.getElementById(id).textContent;
            navigator.clipboard.writeText(text).then(() => {
                const el = document.getElementById(id);
                const orig = el.textContent;
                el.textContent = '¡Copiado!';
                setTimeout(() => { el.textContent = orig; }, 1000);
            });
        }

        initPalette();
    </script>
</body>
</html>
    """.trimIndent()
}
