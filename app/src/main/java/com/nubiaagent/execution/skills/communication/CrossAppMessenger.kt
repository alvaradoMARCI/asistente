package com.nubiaagent.execution.skills.communication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nubiaagent.execution.bridge.ActionDispatcher
import kotlinx.coroutines.delay

/**
 * CrossAppMessenger: Habilidad de mensajería unificada para apps de terceros.
 *
 * Automatiza el envío y lectura de mensajes en WhatsApp, Telegram e Instagram
 * utilizando [ActionDispatcher] como puente de interacción con la UI vía
 * AccessibilityService.
 *
 * ARQUITECTURA DE EJECUCIÓN:
 * ```
 *  Cognitive Layer → CrossAppMessenger → ActionDispatcher → AccessibilityService
 *                          │
 *                    launchApp() (Intent)
 *                          │
 *                    waitAndFind() (polling con timeout)
 *                          │
 *                    performClick / performSetText (acciones UI)
 * ```
 *
 * FLUJO POR PASOS (por cada método):
 *   1. Lanzar la aplicación objetivo con Intent
 *   2. Esperar a que cargue la pantalla principal (waitAndFind)
 *   3. Buscar el contacto/chat en la lista o barra de búsqueda
 *   4. Abrir la conversación
 *   5. Escribir el mensaje en el campo de texto
 *   6. Pulsar el botón de envío
 *
 * REINTENTOS:
 *   Cada método reintenta hasta MAX_ATTEMPTS veces. Si un paso falla,
 *   se vuelve atrás (goBack) y se reintenta desde el paso 2.
 *
 * @param context Contexto de la aplicación para lanzar Intents
 * @param actionDispatcher Dispatcher de acciones UI compartido
 */
class CrossAppMessenger(
    private val context: Context,
    private val actionDispatcher: ActionDispatcher
) {

    companion object {
        private const val TAG = "NubiaAgent/CrossAppMsg"

        /** Máximo de intentos por operación */
        private const val MAX_ATTEMPTS = 3

        /** Timeout para esperar que aparezca un elemento en pantalla (ms) */
        private const val WAIT_TIMEOUT_MS = 5000L

        /** Intervalo de polling al esperar elementos (ms) */
        private const val POLL_INTERVAL_MS = 300L

        /** Delay entre pasos para simular comportamiento humano (ms) */
        private const val STEP_DELAY_MS = 800L

        /** Delay extendido para carga de app (ms) */
        private const val APP_LOAD_DELAY_MS = 2000L

        // ─────────────────────────────────────────────────────────
        // Paquetes de aplicaciones
        // ─────────────────────────────────────────────────────────
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }

    // ═══════════════════════════════════════════════════════════
    // WHATSAPP
    // ═══════════════════════════════════════════════════════════

    /**
     * Envía un mensaje de texto por WhatsApp a un contacto.
     *
     * Flujo:
     *   1. Abre WhatsApp vía Intent con URI de mensaje directo
     *   2. Espera a que cargue la pantalla del chat
     *   3. Escribe el mensaje en el campo de texto
     *   4. Pulsa el botón de envío
     *
     * @param contactName Nombre del contacto tal como aparece en WhatsApp
     * @param message Texto del mensaje a enviar
     * @return Result.success con confirmación o Result.failure con el error
     */
    suspend fun sendWhatsAppMessage(contactName: String, message: String): Result<String> {
        Log.i(TAG, "sendWhatsAppMessage(contacto=\"$contactName\", mensaje=\"${message.take(40)}...\")")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para enviar WhatsApp")

                // Paso 1: Lanzar WhatsApp
                val launched = launchApp(WHATSAPP_PACKAGE)
                if (!launched) {
                    Log.w(TAG, "No se pudo lanzar WhatsApp en intento ${attempt + 1}")
                    if (attempt < MAX_ATTEMPTS - 1) {
                        delay(STEP_DELAY_MS)
                        return@repeat
                    }
                    return Result.failure(Exception("No se pudo abrir WhatsApp. ¿Está instalado?"))
                }

                delay(APP_LOAD_DELAY_MS)

                // Paso 2: Abrir búsqueda de contactos
                val searchTab = waitAndFind("Buscar", WAIT_TIMEOUT_MS)
                if (searchTab != null) {
                    actionDispatcher.performClick(searchTab)
                    searchTab.recycle()
                    delay(STEP_DELAY_MS)

                    // Escribir nombre del contacto en la barra de búsqueda
                    val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                    if (searchField != null) {
                        actionDispatcher.performSetText(searchField, contactName)
                        searchField.recycle()
                        delay(APP_LOAD_DELAY_MS)
                    } else {
                        Log.w(TAG, "Campo de búsqueda no encontrado en WhatsApp")
                        actionDispatcher.goBack()
                        if (attempt < MAX_ATTEMPTS - 1) return@repeat
                        return Result.failure(Exception("No se encontró el campo de búsqueda en WhatsApp"))
                    }
                } else {
                    // Fallback: usar Intent directo con URI
                    Log.d(TAG, "Usando Intent directo para WhatsApp")
                    val uri = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage(WHATSAPP_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    delay(APP_LOAD_DELAY_MS)
                }

                // Paso 3: Buscar y abrir el chat del contacto
                val contactNode = waitAndFind(contactName, WAIT_TIMEOUT_MS)
                if (contactNode != null) {
                    actionDispatcher.performClick(contactNode)
                    contactNode.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Contacto \"$contactName\" no encontrado en WhatsApp")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("Contacto \"$contactName\" no encontrado en WhatsApp"))
                }

                // Paso 4: Escribir mensaje en el campo de texto
                val messageField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (messageField != null) {
                    actionDispatcher.performSetText(messageField, message)
                    messageField.recycle()
                    delay(STEP_DELAY_MS / 2)
                } else {
                    Log.w(TAG, "Campo de mensaje no encontrado en WhatsApp")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo de mensaje en WhatsApp"))
                }

                // Paso 5: Pulsar botón de envío
                val sendButton = waitAndFind("Enviar", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Enviar", WAIT_TIMEOUT_MS)
                if (sendButton != null) {
                    actionDispatcher.performClick(sendButton)
                    sendButton.recycle()
                    delay(STEP_DELAY_MS)
                    Log.i(TAG, "Mensaje de WhatsApp enviado exitosamente a \"$contactName\"")
                    return Result.success("Mensaje enviado a $contactName por WhatsApp")
                } else {
                    Log.w(TAG, "Botón de envío no encontrado en WhatsApp")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se pudo enviar el mensaje: botón de envío no encontrado"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para enviar WhatsApp", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al enviar WhatsApp: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudo enviar el mensaje por WhatsApp tras $MAX_ATTEMPTS intentos"))
    }

    /**
     * Lee los mensajes recientes de un chat de WhatsApp.
     *
     * Flujo:
     *   1. Abre WhatsApp
     *   2. Busca el contacto y abre el chat
     *   3. Lee los nodos de texto de la conversación
     *
     * @param contactName Nombre del contacto cuyo chat leer
     * @param limit Número máximo de mensajes a leer
     * @return Result.success con lista de mensajes o Result.failure con el error
     */
    suspend fun readWhatsAppMessages(contactName: String, limit: Int = 10): Result<List<String>> {
        Log.i(TAG, "readWhatsAppMessages(contacto=\"$contactName\", límite=$limit)")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para leer WhatsApp")

                // Paso 1: Lanzar WhatsApp
                val launched = launchApp(WHATSAPP_PACKAGE)
                if (!launched) {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se pudo abrir WhatsApp"))
                    }
                    delay(STEP_DELAY_MS)
                    return@repeat
                }

                delay(APP_LOAD_DELAY_MS)

                // Paso 2: Buscar contacto y abrir chat
                val searchTab = waitAndFind("Buscar", WAIT_TIMEOUT_MS)
                if (searchTab != null) {
                    actionDispatcher.performClick(searchTab)
                    searchTab.recycle()
                    delay(STEP_DELAY_MS)

                    val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                    if (searchField != null) {
                        actionDispatcher.performSetText(searchField, contactName)
                        searchField.recycle()
                        delay(APP_LOAD_DELAY_MS)
                    }
                }

                val contactNode = waitAndFind(contactName, WAIT_TIMEOUT_MS)
                if (contactNode != null) {
                    actionDispatcher.performClick(contactNode)
                    contactNode.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    actionDispatcher.goBack()
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("Contacto \"$contactName\" no encontrado"))
                    }
                    return@repeat
                }

                // Paso 3: Leer mensajes del chat
                val messages = collectVisibleMessages()
                val limitedMessages = messages.takeLast(limit)

                Log.i(TAG, "Leídos ${limitedMessages.size} mensajes de WhatsApp con \"$contactName\"")
                return Result.success(limitedMessages)

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para leer WhatsApp", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al leer WhatsApp: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudieron leer mensajes de WhatsApp tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // TELEGRAM
    // ═══════════════════════════════════════════════════════════

    /**
     * Envía un mensaje de texto por Telegram a un contacto.
     *
     * Flujo:
     *   1. Abre Telegram
     *   2. Usa la barra de búsqueda para encontrar el chat
     *   3. Abre la conversación
     *   4. Escribe y envía el mensaje
     *
     * @param contactName Nombre del contacto o nombre de usuario de Telegram
     * @param message Texto del mensaje a enviar
     * @return Result.success con confirmación o Result.failure con el error
     */
    suspend fun sendTelegramMessage(contactName: String, message: String): Result<String> {
        Log.i(TAG, "sendTelegramMessage(contacto=\"$contactName\", mensaje=\"${message.take(40)}...\")")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para enviar Telegram")

                // Paso 1: Lanzar Telegram
                val launched = launchApp(TELEGRAM_PACKAGE)
                if (!launched) {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se pudo abrir Telegram. ¿Está instalado?"))
                    }
                    delay(STEP_DELAY_MS)
                    return@repeat
                }

                delay(APP_LOAD_DELAY_MS)

                // Paso 2: Abrir búsqueda
                val searchButton = waitAndFind("Buscar", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Buscar", WAIT_TIMEOUT_MS)
                if (searchButton != null) {
                    actionDispatcher.performClick(searchButton)
                    searchButton.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    // Fallback: intentar escribir directamente en la barra de búsqueda
                    val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                    if (searchField == null) {
                        Log.w(TAG, "No se encontró campo de búsqueda en Telegram")
                        actionDispatcher.goBack()
                        if (attempt < MAX_ATTEMPTS - 1) return@repeat
                        return Result.failure(Exception("No se encontró la búsqueda en Telegram"))
                    }
                }

                // Paso 3: Escribir nombre del contacto en búsqueda
                val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (searchField != null) {
                    actionDispatcher.performSetText(searchField, contactName)
                    searchField.recycle()
                    delay(APP_LOAD_DELAY_MS)
                } else {
                    Log.w(TAG, "Campo de búsqueda no encontrado en Telegram")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("Campo de búsqueda no disponible en Telegram"))
                }

                // Paso 4: Seleccionar el chat del contacto
                val contactNode = waitAndFind(contactName, WAIT_TIMEOUT_MS)
                if (contactNode != null) {
                    actionDispatcher.performClick(contactNode)
                    contactNode.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Contacto \"$contactName\" no encontrado en Telegram")
                    actionDispatcher.goBack()
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("Contacto \"$contactName\" no encontrado en Telegram"))
                }

                // Paso 5: Escribir mensaje
                val messageField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (messageField != null) {
                    actionDispatcher.performSetText(messageField, message)
                    messageField.recycle()
                    delay(STEP_DELAY_MS / 2)
                } else {
                    Log.w(TAG, "Campo de mensaje no encontrado en Telegram")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo de mensaje en Telegram"))
                }

                // Paso 6: Enviar mensaje
                val sendButton = waitAndFind("Enviar", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Enviar", WAIT_TIMEOUT_MS)
                if (sendButton != null) {
                    actionDispatcher.performClick(sendButton)
                    sendButton.recycle()
                    delay(STEP_DELAY_MS)
                    Log.i(TAG, "Mensaje de Telegram enviado exitosamente a \"$contactName\"")
                    return Result.success("Mensaje enviado a $contactName por Telegram")
                } else {
                    Log.w(TAG, "Botón de envío no encontrado en Telegram")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se pudo enviar el mensaje: botón de envío no encontrado en Telegram"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para enviar Telegram", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al enviar Telegram: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudo enviar el mensaje por Telegram tras $MAX_ATTEMPTS intentos"))
    }

    /**
     * Lee los mensajes recientes de un chat de Telegram.
     *
     * @param contactName Nombre del contacto o nombre de usuario
     * @param limit Número máximo de mensajes a leer
     * @return Result.success con lista de mensajes o Result.failure con el error
     */
    suspend fun readTelegramMessages(contactName: String, limit: Int = 10): Result<List<String>> {
        Log.i(TAG, "readTelegramMessages(contacto=\"$contactName\", límite=$limit)")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para leer Telegram")

                // Paso 1: Lanzar Telegram
                val launched = launchApp(TELEGRAM_PACKAGE)
                if (!launched) {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se pudo abrir Telegram"))
                    }
                    delay(STEP_DELAY_MS)
                    return@repeat
                }

                delay(APP_LOAD_DELAY_MS)

                // Paso 2: Buscar y abrir chat
                val searchButton = waitAndFind("Buscar", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Buscar", WAIT_TIMEOUT_MS)
                if (searchButton != null) {
                    actionDispatcher.performClick(searchButton)
                    searchButton.recycle()
                    delay(STEP_DELAY_MS)
                }

                val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (searchField != null) {
                    actionDispatcher.performSetText(searchField, contactName)
                    searchField.recycle()
                    delay(APP_LOAD_DELAY_MS)
                }

                val contactNode = waitAndFind(contactName, WAIT_TIMEOUT_MS)
                if (contactNode != null) {
                    actionDispatcher.performClick(contactNode)
                    contactNode.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    actionDispatcher.goBack()
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("Contacto \"$contactName\" no encontrado en Telegram"))
                    }
                    return@repeat
                }

                // Paso 3: Leer mensajes
                val messages = collectVisibleMessages()
                val limitedMessages = messages.takeLast(limit)

                Log.i(TAG, "Leídos ${limitedMessages.size} mensajes de Telegram con \"$contactName\"")
                return Result.success(limitedMessages)

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para leer Telegram", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al leer Telegram: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudieron leer mensajes de Telegram tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // INSTAGRAM
    // ═══════════════════════════════════════════════════════════

    /**
     * Envía un mensaje directo (DM) por Instagram.
     *
     * Flujo:
     *   1. Abre Instagram Direct mediante Intent o navegación UI
     *   2. Busca el usuario
     *   3. Abre la conversación
     *   4. Escribe y envía el mensaje
     *
     * @param username Nombre de usuario de Instagram (sin @)
     * @param message Texto del mensaje a enviar
     * @return Result.success con confirmación o Result.failure con el error
     */
    suspend fun sendInstagramDM(username: String, message: String): Result<String> {
        Log.i(TAG, "sendInstagramDM(usuario=\"$username\", mensaje=\"${message.take(40)}...\")")

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                Log.d(TAG, "Intento ${attempt + 1}/$MAX_ATTEMPTS para enviar DM de Instagram")

                // Paso 1: Lanzar Instagram
                val launched = launchApp(INSTAGRAM_PACKAGE)
                if (!launched) {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        return Result.failure(Exception("No se pudo abrir Instagram. ¿Está instalado?"))
                    }
                    delay(STEP_DELAY_MS)
                    return@repeat
                }

                delay(APP_LOAD_DELAY_MS)

                // Paso 2: Navegar a la sección de DMs
                val dmButton = waitAndFind("Mensajes", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Mensajes directos", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Notificaciones", WAIT_TIMEOUT_MS)
                if (dmButton != null) {
                    actionDispatcher.performClick(dmButton)
                    dmButton.recycle()
                    delay(APP_LOAD_DELAY_MS)
                } else {
                    // Fallback: usar Intent directo a DMs
                    Log.d(TAG, "Botón de DMs no encontrado, usando Intent directo")
                    try {
                        val dmIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://instagram.com/direct/new/")
                            setPackage(INSTAGRAM_PACKAGE)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(dmIntent)
                        delay(APP_LOAD_DELAY_MS)
                    } catch (ie: Exception) {
                        Log.w(TAG, "No se pudo abrir Instagram Direct por Intent", ie)
                        if (attempt >= MAX_ATTEMPTS - 1) {
                            return Result.failure(Exception("No se pudo acceder a los mensajes de Instagram"))
                        }
                        return@repeat
                    }
                }

                // Paso 3: Buscar el usuario
                val searchField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (searchField != null) {
                    actionDispatcher.performSetText(searchField, username)
                    searchField.recycle()
                    delay(APP_LOAD_DELAY_MS)
                } else {
                    Log.w(TAG, "Campo de búsqueda no encontrado en Instagram DMs")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo de búsqueda en Instagram"))
                }

                // Paso 4: Seleccionar el usuario
                val userNode = waitAndFind(username, WAIT_TIMEOUT_MS)
                if (userNode != null) {
                    actionDispatcher.performClick(userNode)
                    userNode.recycle()
                    delay(STEP_DELAY_MS)
                } else {
                    Log.w(TAG, "Usuario \"$username\" no encontrado en Instagram")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("Usuario \"$username\" no encontrado en Instagram"))
                }

                // Paso 5: Confirmar selección de chat (botón "Siguiente" o "Chat")
                val nextButton = waitAndFind("Siguiente", WAIT_TIMEOUT_MS)
                    ?: waitAndFind("Chat", WAIT_TIMEOUT_MS)
                if (nextButton != null) {
                    actionDispatcher.performClick(nextButton)
                    nextButton.recycle()
                    delay(STEP_DELAY_MS)
                }

                // Paso 6: Escribir mensaje
                val messageField = waitAndFindEditable(WAIT_TIMEOUT_MS)
                if (messageField != null) {
                    actionDispatcher.performSetText(messageField, message)
                    messageField.recycle()
                    delay(STEP_DELAY_MS / 2)
                } else {
                    Log.w(TAG, "Campo de mensaje no encontrado en Instagram DM")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se encontró el campo de mensaje en Instagram"))
                }

                // Paso 7: Enviar mensaje
                val sendButton = waitAndFind("Enviar", WAIT_TIMEOUT_MS)
                    ?: waitAndFindDesc("Enviar", WAIT_TIMEOUT_MS)
                if (sendButton != null) {
                    actionDispatcher.performClick(sendButton)
                    sendButton.recycle()
                    delay(STEP_DELAY_MS)
                    Log.i(TAG, "DM de Instagram enviado exitosamente a \"$username\"")
                    return Result.success("Mensaje directo enviado a @$username por Instagram")
                } else {
                    Log.w(TAG, "Botón de envío no encontrado en Instagram")
                    actionDispatcher.goBack()
                    if (attempt < MAX_ATTEMPTS - 1) return@repeat
                    return Result.failure(Exception("No se pudo enviar el DM: botón de envío no encontrado en Instagram"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en intento ${attempt + 1} para enviar DM de Instagram", e)
                if (attempt >= MAX_ATTEMPTS - 1) {
                    return Result.failure(Exception("Error al enviar DM de Instagram: ${e.message}", e))
                }
                delay(STEP_DELAY_MS)
            }
        }

        return Result.failure(Exception("No se pudo enviar el DM por Instagram tras $MAX_ATTEMPTS intentos"))
    }

    // ═══════════════════════════════════════════════════════════
    // UTILIDADES INTERNAS
    // ═══════════════════════════════════════════════════════════

    /**
     * Lanza una aplicación por su nombre de paquete.
     *
     * Obtiene el Intent de lanzamiento principal del paquete y lo ejecuta
     * con flags NEW_TASK para poder iniciarlo desde un Service.
     *
     * @param packageName Nombre del paquete de la aplicación
     * @return true si la aplicación se lanzó exitosamente, false si no
     */
    private fun launchApp(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                Log.d(TAG, "Aplicación lanzada: $packageName")
                true
            } else {
                Log.w(TAG, "No se encontró launch intent para: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al lanzar aplicación: $packageName", e)
            false
        }
    }

    /**
     * Espera a que aparezca un nodo con el texto especificado en la pantalla.
     *
     * Realiza polling cada [POLL_INTERVAL_MS] hasta encontrar un nodo cuyo
     * texto contenga el string buscado (case-insensitive) o hasta agotar
     * el timeout.
     *
     * @param text Texto a buscar en los nodos de accesibilidad
     * @param timeoutMs Tiempo máximo de espera en milisegundos
     * @return AccessibilityNodeInfo del nodo encontrado, o null si no aparece
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

        Log.w(TAG, "waitAndFind: timeout (${timeoutMs}ms) esperando texto \"$text\"")
        return null
    }

    /**
     * Espera a que aparezca un nodo con la content-description especificada.
     *
     * Similar a [waitAndFind] pero busca en contentDescription en lugar de text.
     * Útil para botones que solo tienen descripción de accesibilidad.
     *
     * @param desc Content-description a buscar
     * @param timeoutMs Tiempo máximo de espera en milisegundos
     * @return AccessibilityNodeInfo del nodo encontrado, o null si no aparece
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

        Log.w(TAG, "waitAndFindDesc: timeout (${timeoutMs}ms) esperando desc \"$desc\"")
        return null
    }

    /**
     * Espera a que aparezca un nodo editable (campo de texto) en la pantalla.
     *
     * Busca repetidamente un nodo con la propiedad isEditable=true,
     * usada para campos de entrada de texto en las apps de mensajería.
     *
     * @param timeoutMs Tiempo máximo de espera en milisegundos
     * @return AccessibilityNodeInfo del campo editable encontrado, o null
     */
    private suspend fun waitAndFindEditable(timeoutMs: Long): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // Buscar campo de texto por resource-id conocido
            val knownFields = listOf(
                "entry",           // WhatsApp / Telegram campo de texto
                "message_input",   // Variante de campo de mensaje
                "compose_message", // Campo de composición
                "text_input",      // Campo genérico
                "search",          // Campo de búsqueda
                "search_src_text"  // Campo de búsqueda Android estándar
            )

            for (fieldId in knownFields) {
                val node = actionDispatcher.findNodeById(fieldId)
                if (node != null && node.isEditable) {
                    Log.d(TAG, "waitAndFindEditable: campo editable encontrado por id \"$fieldId\"")
                    return node
                }
                node?.recycle()
            }

            // Fallback: buscar cualquier nodo editable
            val searchField = actionDispatcher.findNodeByText("")
            if (searchField != null) {
                if (searchField.isEditable) {
                    Log.d(TAG, "waitAndFindEditable: campo editable encontrado por búsqueda genérica")
                    return searchField
                }
                searchField.recycle()
            }

            delay(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "waitAndFindEditable: timeout (${timeoutMs}ms) esperando campo editable")
        return null
    }

    /**
     * Recopila todos los textos visibles en la pantalla actual que parecen
     * ser mensajes de chat.
     *
     * Escanea el árbol de accesibilidad buscando nodos con texto que tengan
     * las características de mensajes (no son etiquetas de UI, no son botones).
     * Filtra nodos con texto muy corto o que parecen ser elementos de navegación.
     *
     * @return Lista de textos de mensajes encontrados en la pantalla
     */
    private fun collectVisibleMessages(): List<String> {
        val messages = mutableListOf<String>()
        val uiLabels = setOf(
            "Buscar", "Enviar", "Adjuntar", "Llamar", "Videollamada",
            "Nuevo chat", "Más opciones", "Estado", "Llamadas",
            "Chats", "Configuración", "Cámara", "Fotos", "Siguiente"
        )

        try {
            val rootNode = actionDispatcher.findNodeByText("") ?: return emptyList()

            // Recorrer el árbol buscando nodos con texto
            collectTextFromNode(rootNode, messages, uiLabels)
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error al recopilar mensajes visibles", e)
        }

        return messages
    }

    /**
     * Recorre recursivamente un nodo y sus hijos, extrayendo textos
     * que parecen ser mensajes de chat.
     *
     * Criterios de filtrado:
     *   - Texto no vacío y mayor a 1 carácter
     *   - No es una etiqueta de UI conocida
     *   - No es un nodo de tipo botón
     *
     * @param node Nodo actual del recorrido
     * @param messages Lista acumuladora de mensajes
     * @param uiLabels Conjunto de etiquetas de UI a excluir
     */
    private fun collectTextFromNode(
        node: AccessibilityNodeInfo,
        messages: MutableList<String>,
        uiLabels: Set<String>
    ) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length > 1) {
            // Excluir etiquetas de UI y textos muy cortos de navegación
            if (text !in uiLabels && node.className?.toString() != "android.widget.Button") {
                messages.add(text)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextFromNode(child, messages, uiLabels)
            child.recycle()
        }
    }
}
