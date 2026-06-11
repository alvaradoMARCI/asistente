package com.nubiaagent.execution.skills.communication

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * SmsPhoneSkill: Habilidad de SMS y llamadas telefónicas.
 *
 * Proporciona funciones nativas de comunicación para enviar/leer SMS,
 * realizar llamadas telefónicas y consultar el registro de llamadas.
 * A diferencia de [CrossAppMessenger] y [EmailSkill], esta habilidad
 * NO utiliza AccessibilityService sino APIs nativas de Android.
 *
 * ARQUITECTURA:
 * ```
 *  Cognitive Layer → SmsPhoneSkill → Android Framework APIs
 *                          │
 *                    SmsManager (envío SMS)
 *                    ContentResolver (lectura SMS / contactos / registro)
 *                    Intent.ACTION_CALL (llamadas)
 *                    ContactsContract (resolución de nombres)
 * ```
 *
 * PERMISOS REQUERIDOS:
 *   - SEND_SMS: Enviar mensajes SMS
 *   - READ_SMS: Leer mensajes SMS
 *   - CALL_PHONE: Realizar llamadas telefónicas
 *   - READ_CALL_LOG: Consultar registro de llamadas
 *   - READ_CONTACTS: Resolver nombres de contacto a números
 *
 * @param context Contexto de la aplicación para acceder a APIs del sistema
 */
class SmsPhoneSkill(
    private val context: Context
) {

    companion object {
        private const val TAG = "NubiaAgent/SmsPhone"

        /** Máximo de SMS a leer por defecto */
        private const val DEFAULT_SMS_LIMIT = 20

        /** Máximo de entradas del registro de llamadas por defecto */
        private const val DEFAULT_CALL_LOG_LIMIT = 20
    }

    // ═══════════════════════════════════════════════════════════
    // TIPOS DE DATOS
    // ═══════════════════════════════════════════════════════════

    /**
     * Representa un mensaje SMS.
     *
     * @property sender Número o nombre del remitente
     * @property body Contenido del mensaje
     * @property date Marca temporal del mensaje (epoch millis)
     * @property isRead Si el mensaje ha sido leído
     */
    data class SmsMessage(
        val sender: String,
        val body: String,
        val date: Long,
        val isRead: Boolean
    )

    /**
     * Representa una entrada del registro de llamadas.
     *
     * @property number Número de teléfono
     * @property name Nombre del contacto (si está en la agenda), null si desconocido
     * @property duration Duración de la llamada en segundos
     * @property date Marca temporal de la llamada (epoch millis)
     * @property type Tipo de llamada (ENTRANTE, SALIENTE, PERDIDA)
     */
    data class CallLogEntry(
        val number: String,
        val name: String?,
        val duration: Long,
        val date: Long,
        val type: CallType
    )

    /**
     * Tipo de llamada telefónica.
     */
    enum class CallType {
        /** Llamada entrante (recibida) */
        INCOMING,
        /** Llamada saliente (realizada) */
        OUTGOING,
        /** Llamada perdida (no contestada) */
        MISSED
    }

    // ═══════════════════════════════════════════════════════════
    // ENVÍO DE SMS
    // ═══════════════════════════════════════════════════════════

    /**
     * Envía un mensaje SMS a un número de teléfono.
     *
     * Utiliza [SmsManager] para enviar el mensaje directamente sin
     * interacción del usuario. El mensaje se divide automáticamente
     * en múltiples partes si excede el límite de 160 caracteres.
     *
     * @param phoneNumber Número de teléfono del destinatario
     * @param message Texto del mensaje a enviar
     * @return Result.success con confirmación o Result.failure con el error
     */
    fun sendSms(phoneNumber: String, message: String): Result<String> {
        Log.i(TAG, "sendSms(número=\"${phoneNumber.takeLast(4)}***\", mensaje=\"${message.take(40)}...\")")

        // Verificar permiso
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Log.e(TAG, "Permiso SEND_SMS no concedido")
            return Result.failure(Exception("Permiso de envío de SMS no concedido. Habilítalo en la configuración."))
        }

        return try {
            val smsManager: SmsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            // Dividir mensaje si es largo
            val parts = smsManager.divideMessage(message)

            if (parts.size > 1) {
                // Mensaje multipart
                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    null,
                    null
                )
                Log.i(TAG, "SMS multipart enviado a ${phoneNumber.takeLast(4)}*** (${parts.size} partes)")
            } else {
                // Mensaje simple
                smsManager.sendTextMessage(
                    phoneNumber,
                    null,
                    message,
                    null,
                    null
                )
                Log.i(TAG, "SMS enviado a ${phoneNumber.takeLast(4)}***")
            }

            Result.success("SMS enviado a $phoneNumber")

        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Número de teléfono inválido: $phoneNumber", e)
            Result.failure(Exception("Número de teléfono inválido: $phoneNumber"))
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al enviar SMS", e)
            Result.failure(Exception("No se tiene permiso para enviar SMS"))
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado al enviar SMS", e)
            Result.failure(Exception("Error al enviar SMS: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LECTURA DE SMS
    // ═══════════════════════════════════════════════════════════

    /**
     * Lee los mensajes SMS más recientes.
     *
     * Consulta [Telephony.Sms] vía ContentResolver para obtener los
     * mensajes ordenados por fecha descendente. Opcionalmente filtra
     * por nombre de contacto resolviendo el nombre a número.
     *
     * @param contactName Nombre del contacto para filtrar, o null para todos
     * @param limit Número máximo de mensajes a leer
     * @return Result.success con lista de [SmsMessage] o Result.failure con el error
     */
    fun readSms(contactName: String? = null, limit: Int = DEFAULT_SMS_LIMIT): Result<List<SmsMessage>> {
        Log.i(TAG, "readSms(contacto=${contactName ?: "todos"}, límite=$limit)")

        // Verificar permiso
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            Log.e(TAG, "Permiso READ_SMS no concedido")
            return Result.failure(Exception("Permiso de lectura de SMS no concedido. Habilítalo en la configuración."))
        }

        return try {
            val messages = mutableListOf<SmsMessage>()
            val contentResolver = context.contentResolver

            // Construir consulta
            val uri: Uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE
            )

            // Si se proporciona nombre de contacto, resolver a número
            val phoneNumber = contactName?.let { resolveContactName(it) }
            val selection = if (phoneNumber != null) {
                "${Telephony.Sms.ADDRESS} = ?"
            } else {
                null
            }
            val selectionArgs = if (phoneNumber != null) {
                arrayOf(phoneNumber)
            } else {
                null
            }
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val cursor = contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1

                    messages.add(SmsMessage(
                        sender = address,
                        body = body,
                        date = date,
                        isRead = read
                    ))
                    count++
                }
            }

            // Si se proporcionó nombre de contacto pero no se resolvió el número,
            // intentar buscar por nombre en el campo de dirección
            if (contactName != null && phoneNumber == null && messages.isEmpty()) {
                val fallbackMessages = searchSmsByContactName(contactName, limit)
                Log.i(TAG, "Leídos ${fallbackMessages.size} SMS para contacto \"$contactName\" (búsqueda por nombre)")
                return Result.success(fallbackMessages)
            }

            Log.i(TAG, "Leídos ${messages.size} SMS")
            Result.success(messages)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al leer SMS", e)
            Result.failure(Exception("No se tiene permiso para leer SMS"))
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado al leer SMS", e)
            Result.failure(Exception("Error al leer SMS: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LLAMADA TELEFÓNICA
    // ═══════════════════════════════════════════════════════════

    /**
     * Realiza una llamada telefónica al número especificado.
     *
     * Usa [Intent.ACTION_CALL] para iniciar la llamada directamente
     * sin pasar por el marcador (requiere permiso CALL_PHONE).
     * Si no se tiene el permiso, usa ACTION_DIAL como fallback
     * (abre el marcador pero no llama automáticamente).
     *
     * @param phoneNumber Número de teléfono a llamar
     * @return Result.success con confirmación o Result.failure con el error
     */
    fun makeCall(phoneNumber: String): Result<String> {
        Log.i(TAG, "makeCall(número=\"${phoneNumber.takeLast(4)}***\")")

        return try {
            val hasCallPermission = hasPermission(Manifest.permission.CALL_PHONE)

            val intent = if (hasCallPermission) {
                // Llamada directa
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                // Fallback: abrir marcador
                Log.w(TAG, "Permiso CALL_PHONE no concedido, abriendo marcador")
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            context.startActivity(intent)

            if (hasCallPermission) {
                Log.i(TAG, "Llamada iniciada a ${phoneNumber.takeLast(4)}***")
                Result.success("Llamada iniciada a $phoneNumber")
            } else {
                Log.i(TAG, "Marcador abierto para ${phoneNumber.takeLast(4)}***")
                Result.success("Marcador abierto para $phoneNumber (se requiere permiso de llamada para marcar automáticamente)")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al realizar llamada", e)
            Result.failure(Exception("No se tiene permiso para realizar llamadas"))
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado al realizar llamada", e)
            Result.failure(Exception("Error al realizar llamada: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REGISTRO DE LLAMADAS
    // ═══════════════════════════════════════════════════════════

    /**
     * Obtiene el registro de llamadas más recientes.
     *
     * Consulta [CallLog.Calls] vía ContentResolver para obtener
     * las llamadas ordenadas por fecha descendente.
     *
     * @param limit Número máximo de entradas a devolver
     * @return Result.success con lista de [CallLogEntry] o Result.failure con el error
     */
    fun getCallLog(limit: Int = DEFAULT_CALL_LOG_LIMIT): Result<List<CallLogEntry>> {
        Log.i(TAG, "getCallLog(límite=$limit)")

        // Verificar permiso
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            Log.e(TAG, "Permiso READ_CALL_LOG no concedido")
            return Result.failure(Exception("Permiso de lectura del registro de llamadas no concedido. Habilítalo en la configuración."))
        }

        return try {
            val entries = mutableListOf<CallLogEntry>()
            val contentResolver = context.contentResolver

            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE
            )

            val sortOrder = "${CallLog.Calls.DATE} DESC"

            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                    val name = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    val typeInt = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))

                    val callType = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        else -> {
                            Log.d(TAG, "Tipo de llamada desconocido: $typeInt, tratando como ENTRANTE")
                            CallType.INCOMING
                        }
                    }

                    entries.add(CallLogEntry(
                        number = number,
                        name = name,
                        duration = duration,
                        date = date,
                        type = callType
                    ))
                    count++
                }
            }

            Log.i(TAG, "Obtenidas ${entries.size} entradas del registro de llamadas")
            Result.success(entries)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al leer registro de llamadas", e)
            Result.failure(Exception("No se tiene permiso para leer el registro de llamadas"))
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado al leer registro de llamadas", e)
            Result.failure(Exception("Error al leer registro de llamadas: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // RESOLUCIÓN DE CONTACTOS
    // ═══════════════════════════════════════════════════════════

    /**
     * Resuelve un nombre de contacto a su número de teléfono.
     *
     * Busca en [ContactsContract] el contacto cuyo nombre coincida
     * (parcial, case-insensitive) y retorna su número de teléfono
     * principal. Si hay múltiples números, retorna el primero
     * encontrado (prioriza móvil sobre fijo).
     *
     * @param contactName Nombre del contacto a buscar
     * @return Número de teléfono, o null si no se encontró
     */
    fun resolveContactName(contactName: String): String? {
        Log.d(TAG, "resolveContactName(nombre=\"$contactName\")")

        // Verificar permiso
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            Log.w(TAG, "Permiso READ_CONTACTS no concedido, no se puede resolver contacto")
            return null
        }

        var phoneNumber: String? = null
        var mobileNumber: String? = null

        try {
            val contentResolver = context.contentResolver

            // Paso 1: Buscar el contacto por nombre
            val contactUri = ContactsContract.Contacts.CONTENT_URI
            val contactProjection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME
            )
            val contactSelection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val contactSelectionArgs = arrayOf("%$contactName%")

            val contactCursor = contentResolver.query(
                contactUri,
                contactProjection,
                contactSelection,
                contactSelectionArgs,
                null
            )

            var contactId: Long = -1

            contactCursor?.use {
                if (it.moveToFirst()) {
                    contactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    val foundName = it.getString(it.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    Log.d(TAG, "Contacto encontrado: $foundName (ID: $contactId)")
                }
            }

            if (contactId == -1L) {
                // Intento alternativo: búsqueda exacta
                val exactCursor = contentResolver.query(
                    contactUri,
                    contactProjection,
                    "${ContactsContract.Contacts.DISPLAY_NAME} = ?",
                    arrayOf(contactName),
                    null
                )

                exactCursor?.use {
                    if (it.moveToFirst()) {
                        contactId = it.getLong(it.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    }
                }
            }

            if (contactId == -1L) {
                Log.w(TAG, "Contacto \"$contactName\" no encontrado en la agenda")
                return null
            }

            // Paso 2: Obtener el número de teléfono del contacto
            val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val phoneSelection = "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
            val phoneSelectionArgs = arrayOf(contactId.toString())

            val phoneCursor = contentResolver.query(
                phoneUri,
                phoneProjection,
                phoneSelection,
                phoneSelectionArgs,
                null
            )

            phoneCursor?.use {
                while (it.moveToNext()) {
                    val number = it.getString(
                        it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    )
                    val type = it.getInt(
                        it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    )

                    // Priorizar número móvil
                    if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                        mobileNumber = number
                    }
                    // Guardar primer número como fallback
                    if (phoneNumber == null) {
                        phoneNumber = number
                    }
                }
            }

            // Retornar móvil si existe, si no el primero encontrado
            val resolved = mobileNumber ?: phoneNumber
            if (resolved != null) {
                Log.d(TAG, "Número resuelto para \"$contactName\": ${resolved.takeLast(4)}***")
            } else {
                Log.w(TAG, "No se encontró número de teléfono para contacto \"$contactName\"")
            }

            return resolved

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException al resolver contacto", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado al resolver contacto \"$contactName\"", e)
            return null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UTILIDADES INTERNAS
    // ═══════════════════════════════════════════════════════════

    /**
     * Verifica si se tiene un permiso específico.
     *
     * @param permission Permiso a verificar (e.g. Manifest.permission.SEND_SMS)
     * @return true si el permiso está concedido
     */
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Busca mensajes SMS que contengan el nombre del contacto
     * en el campo de dirección.
     *
     * Fallback para cuando [resolveContactName] no encuentra un número.
     * Busca coincidencias parciales del nombre en los campos ADDRESS
     * de la base de datos de SMS.
     *
     * @param contactName Nombre del contacto a buscar en direcciones SMS
     * @param limit Número máximo de mensajes
     * @return Lista de [SmsMessage] que coinciden
     */
    private fun searchSmsByContactName(contactName: String, limit: Int): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()

        try {
            val contentResolver = context.contentResolver
            val uri: Uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            )
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val cursor = contentResolver.query(uri, projection, null, null, sortOrder)

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: ""
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                    val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                    val read = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.READ)) == 1

                    // Verificar si la dirección contiene el nombre del contacto
                    // (algunos operadores usan nombres en lugar de números)
                    if (address.contains(contactName, ignoreCase = true)) {
                        messages.add(SmsMessage(
                            sender = address,
                            body = body,
                            date = date,
                            isRead = read
                        ))
                        count++
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error en búsqueda alternativa de SMS por nombre", e)
        }

        return messages
    }

    /**
     * Formatea una duración en segundos a formato legible.
     *
     * Ejemplos: "5 min 30 s", "1 h 15 min", "45 s"
     *
     * @param seconds Duración en segundos
     * @return Cadena formateada en español
     */
    fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> {
                val min = seconds / 60
                val sec = seconds % 60
                if (sec > 0) "$min min $sec s" else "$min min"
            }
            else -> {
                val hours = seconds / 3600
                val min = (seconds % 3600) / 60
                if (min > 0) "$hours h $min min" else "$hours h"
            }
        }
    }

    /**
     * Formatea un tipo de llamada a texto legible en español.
     *
     * @param type Tipo de llamada
     * @return Texto descriptivo del tipo
     */
    fun formatCallType(type: CallType): String {
        return when (type) {
            CallType.INCOMING -> "Entrante"
            CallType.OUTGOING -> "Saliente"
            CallType.MISSED -> "Perdida"
        }
    }

    /**
     * Formatea una marca temporal epoch a fecha legible.
     *
     * @param epochMillis Marca temporal en milisegundos
     * @return Fecha formateada en estilo "dd/MM/yyyy HH:mm"
     */
    fun formatDate(epochMillis: Long): String {
        val date = java.util.Date(epochMillis)
        val format = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("es", "ES"))
        return format.format(date)
    }
}
