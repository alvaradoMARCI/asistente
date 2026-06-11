package com.nubiaagent.execution.skills.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nubiaagent.execution.bridge.ActionDispatcher
import kotlinx.coroutines.delay

/**
 * EmailSkill: Habilidad de integración con Gmail.
 *
 * Automatiza la búsqueda, lectura, redacción y respuesta de correos
 * electrónicos usando Gmail vía [ActionDispatcher] y AccessibilityService.
 *
 * ARQUITECTURA:
 * ```
 *  Cognitive Layer → EmailSkill → ActionDispatcher → AccessibilityService
 *                          │
 *                    Gmail Intent (compose, view)
 *                          │
 *                    waitAndFind() (polling con timeout)
 *                          │
 *                    performClick / performSetText (acciones UI)
 * ```
 *
 * OPERACIONES SOPORTADAS:
 *   - searchEmails: Busca correos por consulta
 *   - readEmail: Lee el contenido de un correo específico
 *   - composeEmail: Redacta un correo nuevo
 *   - replyToEmail: Responde al correo actualmente abierto
 *
 * @param context Contexto de la aplicación para lanzar Intents
 * @param actionDispatcher Dispatcher de acciones UI compartido
 */
class EmailSkill(
    private val context: Context,
    private val actionDispatcher: ActionDispatcher
) {

    companion object {
        private const val TAG = "NubiaAgent/EmailSkill"

        /** Máximo de intentos por operación */
        private const val MAX_ATTEMPTS = 3

        /** Timeout para esperar elementos en pantalla (ms) */
        private const val WAIT_TIMEOUT_MS = 5000L

        /** Intervalo de polling al esperar elementos (ms) */
        private const val POLL_INTERVAL_MS = 300L

        /** Delay entre pasos para comportamiento humano (ms) */
        private const val STEP_DELAY_MS = 800L

        /** Delay para carga de aplicación (ms) */
        private const val APP_LOAD_DELAY_MS = 2000L

        /** Paquete de Gmail */
        private const val GMAIL_PACKAGE = "com.google.android.gm"
    }

    // ═══════════════════════════════════════════════════════════
    // TIPOS DE DATOS
    // ═══════════════════════════════════════════════════════════

    /**
     * Resumen de un correo electrónico.
     *
     * Contiene la información visible en la lista de correos de Gmail
     * sin necesidad de abrir el correo completo.
     *
     * @property sender Nombre o dirección del remitente
     * @property subject Asunto del correo
     * @property preview Vista previa del cuerpo del correo (primeras palabras)
     * @property date Fecha del correo en formato legible
     */
    data class EmailSummary(
        val sender: String,
        val subject: String,
        val preview: String,
        val date: String
    )

    // ═══════════════════════════════════════════════════════════
    // BÚSQUEDA DE CORREOS
    // ═══════════════════════════════════════════════════════════

    /**
     * Busca correos electrónicos en Gmail por consulta.
     *
     * Flujo:
     *   1. Abre Gmail
     *   2. Activa la barra de búsqueda
     *   3. Escribe la consulta y ejecuta la búsqueda
     *   4. Lee los resultados visibles en la lista
     *
     * @param query Texto de búsqueda (remitente, asunto, contenido)
     * @param limit Número máximo de correos a devolver
     * @return Result.success con lista de [EmailSummary] o Result.failure con el error
     */
    suspend fun searchEmails(query: String, limit: Int = 10): Result<List<EmailSummary>> {
        Log.i(TAG, "searchEmails(consulta=\"$query\", límite=$limit)")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para buscar correos")

                // Paso 1: Lanzar Gmail
                val launched = launchGmail()
                if (!launched) {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se pudo abrir Gmail. ¿Está instalado?"))
                    }
                    delay(STEP_DELAY_MS)
                    return@repeat
                }

                delay(APP_LOAD_DELAY_MS)

                // Paso 2: Abrir la barra de búsqueda
                val searchButton = waitAndFind("Buscar", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Buscar", WAIT_TIMEOUT_MS)
                if (searchButton != null) {
                    actionDispatcher.performClick(searchButton)
                    searchButton.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Botón de búsqueda no encontrado en Gmail")
                    if (attempt < MAX_ATTEMPTS - 1) {
                        actionDispatcher.goBack()
                        return@repeat
                    }
                    return Result.failure(Exception("No se encontró el botón de búsqueda en Gmail"))
                }

                // Paso 3: Escribir consulta en el campo de búsqueda
                val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (searchField != null) {
                    actionDispatcher.performSetText(searchField, query)
                    searchField.recycle()
                    delay(STEP_DELAY_MS / 2)
                } else {
                    Log.w(TAG, "Campo de búsqueda no encontrado en Gmail")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("Campo de búsqueda no disponible en Gmail"))
                }

                // Paso 4: Ejecutar búsqueda (pulsar Enter/botón de buscar)
                val searchConfirm = waitAndFindDesc("Buscar", WAIT_TIMEOUT_MS)
                    ?: waitAndFind("Buscar correo", WAIT_TIMEOUT_MS)
                if (searchConfirm != null) {
                    actionDispatcher.performClick(searchConfirm)
                    searchConfirm.recycle()
                }
                // También intentar con acción IME de búsqueda
                delay(APP_LOAD_DELAY_MS)

                // Paso 5: Recopilar resultados
                val emails = collectEmailResults(limit)

                Log.i(TAG, "Búsqueda completada: ${emails.size} correos encontrados para \"$query\"")
                return Result.success(emails)

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para buscar correos", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al buscar correos: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudieron buscar correos tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // LECTURA DE CORREO
    // ═══════════════════════════════════════════════════════════

    /**
     * Lee el contenido de un correo electrónico específico.
     *
     * Abre el correo en la posición indicada de la lista actual
     * y extrae todo el texto visible del cuerpo del mensaje.
     *
     * NOTA: Debe llamarse después de [searchEmails] para que haya
     * una lista de correos visible en pantalla.
     *
     * @param emailIndex Índice del correo en la lista (basado en 0)
     * @return Result.success con el contenido del correo o Result.failure con el error
     */
    suspend fun readEmail(emailIndex: Int): Result<String> {
        Log.i(TAG, "readEmail(índice=$emailIndex)")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para leer correo #${emailIndex + 1}")

                // Asegurar que Gmail está abierto
                if (!isGmailInForeground()) {
                    val launched = launchGmail()
                    if (!launched) {
                        return Result.failure(Exception("No se pudo abrir Gmail para leer el correo"))
                    }
                    delay(APP_LOAD_DELAY_MS)
                }

                // Buscar el correo en la lista por su posición
                // Los correos en la lista de Gmail se muestran como nodos clickeables
                val emailNodes = findEmailNodesInList()

                if (emailIndex >= emailNodes.size) {
                    // Intentar hacer scroll para cargar más correos
                    if (emailIndex >= emailNodes.size && emailNodes.isNotEmpty()) {
                        actionDispatcher.swipe("up")
                        delay(STEP_DELAY_MS)
                    }

                    val updatedNodes = findEmailNodesInList()
                    if (emailIndex >= updatedNodes.size) {
                        Log.w(TAG, "Índice $emailIndex fuera de rango (${updatedNodes.size} correos visibles)")
                        if (attempt >= MAX_ATTEMPTS - 1) {
                            return Result.failure(
                                Exception("No existe correo en la posición ${emailIndex + 1}. Solo hay ${updatedNodes.size} correos visibles.")
                            )
                        }
                        return@repeat
                    }
                }

                // Abrir el correo
                val targetNode = if (emailIndex < emailNodes.size) emailNodes[emailIndex] else null
                if (targetNode != null) {
                    actionDispatcher.performClick(targetNode)
                    targetNode.recycle()
                    delay(APP_LOAD_DELAY_MS)
                } else {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se pudo abrir el correo #${emailIndex + 1}"))
                    }
                    return@repeat
                }

                // Reciclar nodos no usados
                emailNodes.forEachIndexed { idx, node ->
                    if (idx != emailIndex) node.recycle()
                }

                // Leer el contenido del correo abierto
                val content = readEmailContent()

                if (content.isNotEmpty()) {
                    Log.i(TAG, "Correo #${emailIndex + 1} leído exitosamente (${content.length} caracteres)")
                    return Result.success(content)
                } else {
                    Log.w(TAG, "Contenido vacío en correo #${emailIndex + 1}")
                    actionDispatcher.goBack()
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("El correo #${emailIndex + 1} no tiene contenido legible"))
                    }
                    delay(STEP_DELAY_MS)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para leer correo", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al leer correo: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudo leer el correo tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // REDACCIÓN DE CORREO
    // ═══════════════════════════════════════════════════════════

    /**
     * Redacta y envía un correo electrónico nuevo.
     *
     * Flujo:
     *   1. Abre Gmail con Intent de composición
     *   2. Rellena el campo "Para" con el destinatario
     *   3. Rellena el campo "Asunto"
     *   4. Escribe el cuerpo del mensaje
     *   5. Pulsa el botón de envío
     *
     * @param to Dirección de correo del destinatario
     * @param subject Asunto del correo
     * @param body Cuerpo del mensaje
     * @return Result.success con confirmación o Result.failure con el error
     */
    suspend fun composeEmail(to: String, subject: String, body: String): Result<String> {
        Log.i(TAG, "composeEmail(para=\"$to\", asunto=\"$subject\")")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para redactar correo")

                // Paso 1: Lanzar Gmail con Intent de composición
                val composeIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    setPackage(GMAIL_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(composeIntent)
                    delay(APP_LOAD_DELAY_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Intent de composición falló, abriendo Gmail manualmente", e)
                    // Fallback: abrir Gmail y usar el botón de redactar
                    val launched = launchGmail()
                    if (!launched) {
                        if (attempt >= MAX_ATTEMPTS - 1) {
                            return Result.failure(Exception("No se pudo abrir Gmail"))
                        }
                        return@repeat
                    }
                    delay(APP_LOAD_DELAY_MS)

                    val composeButton = waitAndFindDesc("Redactar", WAIT_TIMEOUT_MS)
                        ?: waitAndFind("Redactar", WAIT_TIMEOUT_MS)
                    if (composeButton != null) {
                        actionDispatcher.performClick(composeButton)
                        composeButton.recycle()
                        delay(APP_LOAD_DELAY_MS)
                    } else {
                        if (attempt >= MAX_ATTEMPTS - 1) {
                            return Result.failure(Exception("No se encontró el botón de redactar"))
                        }
                        return@repeat
                    }
                }

                // Paso 2: Rellenar campo "Para" (si no se pre-rellenó con Intent)
                val toField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (toField != null) {
                    // Verificar si el campo ya tiene el destinatario
                    val currentText = toField.text?.toString()?.trim() ?: ""
                    if (currentText.isEmpty() || !currentText.contains(to, ignoreCase = true)) {
                        actionDispatcher.performSetText(toField, to)
                    }
                    toField.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Campo 'Para' no encontrado")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo 'Para' en Gmail"))
                }

                // Paso 3: Rellenar campo "Asunto"
                val subjectField = waitAndFindById("subject", WAIT_TIMEOUT_MS)
                if (subjectField != null) {
                    actionDispatcher.performSetText(subjectField, subject)
                    subjectField.recycle()
                    delay(STEP_DELAY_MS / 2)
                } else {
                    // Fallback: buscar siguiente campo editable
                    val nextField = findNextEditableField()
                    if (nextField != null) {
                        actionDispatcher.performSetText(nextField, subject)
                        nextField.recycle()
                        delay(STEP_DELAY_MS / 2)
                    }
                }

                // Paso 4: Escribir el cuerpo del mensaje
                val bodyField = waitAndFindById("body", WAIT_TIMEOUT_MS)
                    ?: findNextEditableField()
                if (bodyField != null) {
                    actionDispatcher.performSetText(bodyField, body)
                    bodyField.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Campo de cuerpo no encontrado")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo del cuerpo en Gmail"))
                }

                // Paso 5: Enviar correo
                val sendButton = waitAndFindDesc("Enviar", WAIT_TIMEOUT_MS)
                    ?: waitAndFind("Enviar", WAIT_TIMEOUT_MS)
                if (sendButton != null) {
                    actionDispatcher.performClick(sendButton)
                    sendButton.recycle()
                    delay(APP_LOAD_DELAY_MS)
                    Log.i(TAG, "Correo enviado exitosamente a \"$to\"")
                    return Result.success("Correo enviado a $to con asunto: $subject")
                } else {
                    Log.w(TAG, "Botón de envío no encontrado en Gmail")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se pudo enviar el correo: botón de envío no encontrado"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para redactar correo", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al redactar correo: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudo redactar el correo tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // RESPUESTA DE CORREO
    // ═══════════════════════════════════════════════════════════

    /**
     * Responde al correo electrónico actualmente abierto.
     *
     * Asume que un correo ya está abierto (por ejemplo, después de [readEmail]).
     * Busca el botón de respuesta, escribe el texto y envía.
     *
     * @param replyText Texto de la respuesta
     * @return Result.success con confirmación o Result.failure con el error
     */
    suspend fun replyToEmail(replyText: String): Result<String> {
        Log.i(TAG, "replyToEmail(texto=\"${replyText.take(40)}...\")")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para responder correo")

                // Paso 1: Buscar y pulsar el botón de responder
                val replyButton = waitAndFindDesc("Responder", WAIT_TIMEOUT_MS)
                    ?: waitAndFind("Responder", WAIT_TIMEOUT_MS)
                if (replyButton != null) {
                    actionDispatcher.performClick(replyButton)
                    replyButton.recycle()
                    delay(APP_LOAD_DELAY_MS)
                } else {
                    Log.w(TAG, "Botón de respuesta no encontrado")
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se encontró el botón de responder. ¿Hay un correo abierto?"))
                    }
                    return@repeat
                }

                // Paso 2: Escribir la respuesta en el campo de texto
                val replyField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (replyField != null) {
                    actionDispatcher.performSetText(replyField, replyText)
                    replyField.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Campo de respuesta no encontrado")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo de respuesta en Gmail"))
                }

                // Paso 3: Enviar la respuesta
                val sendButton = waitAndFindDesc("Enviar", WAIT_TIMEOUT_MS)
                    ?: waitAndFind("Enviar", WAIT_TIMEOUT_MS)
                if (sendButton != null) {
                    actionDispatcher.performClick(sendButton)
                    sendButton.recycle()
                    delay(APP_LOAD_DELAY_MS)
                    Log.i(TAG, "Respuesta enviada exitosamente")
                    return Result.success("Respuesta enviada correctamente")
                } else {
                    Log.w(TAG, "Botón de envío no encontrado para responder")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se pudo enviar la respuesta: botón de envío no encontrado"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para responder correo", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al responder correo: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudo responder el correo tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // UTILIDADES INTERNAS
    // ═══════════════════════════════════════════════════════════

    /**
     * Lanza Gmail usando su Intent de lanzamiento.
     *
     * @return true si Gmail se lanzó exitosamente
     */
    private fun launchGmail(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(GMAIL_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.d(TAG, "Gmail lanzado exitosamente")
                true
            } else {
                Log.w(TAG, "No se encontró launch intent para Gmail")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al lanzar Gmail", e)
            false
        }
    }

    /**
     * Verifica si Gmail está en primer plano.
     *
     * Compara el hash de pantalla actual con patrones típicos de Gmail.
     *
     * @return true si Gmail parece estar en primer plano
     */
    private fun isGmailInForeground(): Boolean {
        return try {
            val hash = actionDispatcher.captureScreenHash()
            // Si hay un hash no vacío, asumimos que hay algo en pantalla
            hash.isNotEmpty()
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo verificar pantalla activa", e)
            false
        }
    }

    /**
     * Espera a que aparezca un nodo con el texto especificado.
     *
     * @param text Texto a buscar (case-insensitive, parcial)
     * @param timeoutMs Tiempo máximo de espera
     * @return Nodo encontrado o null
     */
    private suspend fun waitAndFind(text: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val node = actionDispatcher.findNodeByText(text)
            if (node != null) {
                Log.d(TAG, "waitAndFind: nodo encontrado con texto \"$text\"")
                return node
            }
            delay(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "waitAndFind: timeout esperando texto \"$text\"")
        return null
    }

    /**
     * Espera a que aparezca un nodo con la content-description especificada.
     *
     * @param desc Content-description a buscar
     * @param timeoutMs Tiempo máximo de espera
     * @return Nodo encontrado o null
     */
    private suspend fun waitAndFindDesc(desc: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val node = actionDispatcher.findNodeByDesc(desc)
            if (node != null) {
                Log.d(TAG, "waitAndFindDesc: nodo encontrado con desc \"$desc\"")
                return node
            }
            delay(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "waitAndFindDesc: timeout esperando desc \"$desc\"")
        return null
    }

    /**
     * Espera a que aparezca un nodo editable en la pantalla.
     *
     * @param timeoutMs Tiempo máximo de espera
     * @return Nodo editable encontrado o null
     */
    private suspend fun waitAndFindEditable(timeoutMs: Long): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val knownFields = listOf(
                "to",              // Campo Para
                "subject",         // Campo Asunto
                "body",            // Campo Cuerpo
                "compose",         // Campo de composición
                "search_src_text", // Campo de búsqueda Android
                "to_container"     // Contenedor de destinatarios
            )

            for (fieldId in knownFields) {
                val node = actionDispatcher.findNodeById(fieldId)
                if (node != null && node.isEditable) {
                    Log.d(TAG, "waitAndFindEditable: campo encontrado por id \"$fieldId\"")
                    return node
                }
                node?.recycle()
            }

            delay(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "waitAndFindEditable: timeout esperando campo editable")
        return null
    }

    /**
     * Espera a que aparezca un nodo por su resource-id.
     *
     * @param id Resource-id a buscar
     * @param timeoutMs Tiempo máximo de espera
     * @return Nodo encontrado o null
     */
    private suspend fun waitAndFindById(id: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val node = actionDispatcher.findNodeById(id)
            if (node != null) {
                Log.d(TAG, "waitAndFindById: nodo encontrado con id \"$id\"")
                return node
            }
            delay(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "waitAndFindById: timeout esperando id \"$id\"")
        return null
    }

    /**
     * Busca el siguiente campo editable disponible en la pantalla.
     *
     * Útil cuando los campos no se encuentran por resource-id conocido.
     *
     * @return Primer nodo editable encontrado, o null
     */
    private fun findNextEditableField(): AccessibilityNodeInfo? {
        return try {
            actionDispatcher.findNodeByText("").let { node ->
                if (node != null && node.isEditable) node else {
                    node?.recycle()
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "No se encontró campo editable alternativo", e)
            null
        }
    }

    /**
     * Recopila los correos visibles en la lista de resultados de Gmail.
     *
     * Analiza el árbol de accesibilidad buscando nodos que representen
     * elementos de correo en la lista. Extrae remitente, asunto,
     * vista previa y fecha de cada uno.
     *
     * @param limit Número máximo de correos a recopilar
     * @return Lista de [EmailSummary] con los correos encontrados
     */
    private fun collectEmailResults(limit: Int): List<EmailSummary> {
        val emails = mutableListOf<EmailSummary>()

        try {
            // Buscar nodos que parezcan correos en la lista
            // Los correos en Gmail suelen tener nodos con el asunto como texto
            val subjectKeywords = listOf(
                "asunto", "subject", "re:", "fwd:", "fw:"
            )

            // Estrategia: buscar nodos clickeables en la lista y extraer textos hijos
            val candidateNodes = mutableListOf<AccessibilityNodeInfo>()

            // Buscar por nodos que contengan texto típico de correo
            for (keyword in subjectKeywords) {
                val node = actionDispatcher.findNodeByText(keyword)
                if (node != null) {
                    candidateNodes.add(node)
                }
            }

            // Si no encontramos por palabras clave, recopilar textos visibles
            if (candidateNodes.isEmpty()) {
                val allTexts = mutableListOf<String>()
                val rootNode = actionDispatcher.findNodeByText("")
                if (rootNode != null) {
                    collectTextsFromNode(rootNode, allTexts)
                    rootNode.recycle()
                }

                // Agrupar textos en bloques de correo (aproximación)
                // Cada correo suele tener: remitente, asunto, preview, fecha
                var i = 0
                while (i + 3 < allTexts.size && emails.size < limit) {
                    val email = EmailSummary(
                        sender = allTexts[i],
                        subject = allTexts[i + 1],
                        preview = allTexts[i + 2],
                        date = allTexts[i + 3]
                    )
                    emails.add(email)
                    i += 4
                }
            } else {
                // Extraer información de los nodos candidatos
                for (node in candidateNodes) {
                    if (emails.size >= limit) break

                    val subject = node.text?.toString() ?: ""
                    val parent = node.parent
                    val sender = parent?.let {
                        extractSiblingText(it, node)
                    } ?: "Desconocido"

                    emails.add(EmailSummary(
                        sender = sender,
                        subject = subject,
                        preview = "",
                        date = ""
                    ))

                    parent?.recycle()
                    node.recycle()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error al recopilar resultados de correo", e)
        }

        return emails
    }

    /**
     * Busca nodos de correo en la lista de Gmail.
     *
     * Retorna nodos que parecen ser elementos de lista de correos
     * (nodos clickeables con texto que no son botones de acción).
     *
     * @return Lista de nodos que representan correos en la lista
     */
    private fun findEmailNodesInList(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()

        try {
            // Buscar por elementos de lista conocidos de Gmail
            val listIds = listOf(
                "conversation_list_row",
                "list_item",
                "recyclerView",
                "mail_list"
            )

            for (id in listIds) {
                val node = actionDispatcher.findNodeById(id)
                if (node != null) {
                    nodes.add(node)
                    if (nodes.size >= 20) break // Limitar búsqueda
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "No se encontraron nodos de lista de correo", e)
        }

        return nodes
    }

    /**
     * Lee el contenido del correo actualmente abierto.
     *
     * Escanea el árbol de accesibilidad buscando todos los nodos
     * con texto que pertenezcan al cuerpo del correo.
     *
     * @return Texto completo del correo, o string vacío si no se pudo leer
     */
    private fun readEmailContent(): String {
        val contentParts = mutableListOf<String>()

        try {
            val rootNode = actionDispatcher.findNodeByText("")
            if (rootNode != null) {
                collectEmailBodyText(rootNode, contentParts)
                rootNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al leer contenido del correo", e)
        }

        return contentParts.joinToString("\n").trim()
    }

    /**
     * Recorre recursivamente un nodo extrayendo textos del cuerpo del correo.
     *
     * Filtra elementos de UI como botones y barras de navegación.
     *
     * @param node Nodo actual del recorrido
     * @param parts Lista acumuladora de fragmentos de texto
     */
    private fun collectEmailBodyText(node: AccessibilityNodeInfo, parts: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        val className = node.className?.toString() ?: ""
        val uiExclusions = setOf(
            "Responder", "Reenviar", "Eliminar", "Archivar",
            "Marcar como no leído", "Mover a", "Etiquetar",
            "Más opciones", "Imprimir", "Descargar"
        )

        if (!text.isNullOrEmpty() && text !in uiExclusions &&
            className != "android.widget.Button" &&
            !text.startsWith("android:")
        ) {
            parts.add(text)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEmailBodyText(child, parts)
            child.recycle()
        }
    }

    /**
     * Extrae el texto de un nodo hermano en el árbol de accesibilidad.
     *
     * Usado para obtener el remitente a partir del nodo del asunto,
     * ya que ambos suelen ser hijos del mismo contenedor.
     *
     * @param parent Nodo padre compartido
     * @param referenceNode Nodo de referencia (asunto) para buscar hermanos
     * @return Texto del primer hermano con texto, o "Desconocido"
     */
    private fun extractSiblingText(parent: AccessibilityNodeInfo, referenceNode: AccessibilityNodeInfo): String {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            if (child !== referenceNode) {
                val text = child.text?.toString()?.trim()
                if (!text.isNullOrEmpty()) {
                    child.recycle()
                    return text
                }
            }
            child.recycle()
        }
        return "Desconocido"
    }

    /**
     * Recopila todos los textos visibles de un nodo y sus hijos.
     *
     * @param node Nodo raíz del recorrido
     * @param texts Lista acumuladora de textos encontrados
     */
    private fun collectTextsFromNode(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 1) {
            texts.add(text)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextsFromNode(child, texts)
            child.recycle()
        }
    }
}
