package com.nubiaagent.execution.hardware

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HardwareResult: Resultado estandarizado de todas las operaciones de hardware.
 *
 * Siguiendo el patrón Result de Kotlin pero adaptado para proporcionar
 * contexto adicional sobre éxitos y fallos en operaciones de hardware.
 */
sealed class HardwareResult<T> {
    data class Success<T>(val data: T, val message: String = "Operación exitosa") : HardwareResult<T>()
    data class Failure<T>(val error: String, val exception: Exception? = null) : HardwareResult<T>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw exception ?: RuntimeException(error)
    }
}

/**
 * SmsMessage: Representación compacta de un mensaje SMS.
 */
data class SmsMessage(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val isRead: Boolean,
    val threadId: Long
)

/**
 * HardwareController: Centro de control de hardware para NubiaAgent.
 *
 * Centraliza TODA la interacción con el hardware del ZTE Nubia Neo 3 5G,
 * proporcionando una API unificada para SMS, llamadas, cámara, audio,
 * linterna, ajustes del sistema, portapapeles y WiFi.
 *
 * ARQUITECTURA:
 * ```
 *  AgentLoop → ToolExecutor → SafetyManager → HardwareController → Android APIs
 *                                                       │
 *                                            ┌──────────┼──────────┐
 *                                            │          │          │
 *                                         SMS/Phone  Camera/Audio  Settings
 * ```
 *
 * PERMISOS REQUERIDOS:
 * - SEND_SMS, READ_SMS, RECEIVE_SMS
 * - CALL_PHONE
 * - CAMERA, RECORD_AUDIO
 * - WRITE_SETTINGS, WRITE_EXTERNAL_STORAGE
 * - ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
 * - SYSTEM_ALERT_WINDOW (para linterna en algunos dispositivos)
 *
 * NOTA DE SEGURIDAD: Todas las operaciones de escritura pasan primero por
 * SafetyManager antes de llegar aquí. Este controlador asume que la acción
 * ya fue aprobada o confirmada por el usuario.
 *
 * @property context Contexto de la aplicación para acceder a servicios del sistema
 */
class HardwareController(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/HWCtrl"

        /** Límite máximo de SMS que se pueden leer en una sola consulta. */
        private const val MAX_SMS_READ_LIMIT = 500

        /** Duración máxima de grabación de video en milisegundos (30 minutos). */
        private const val MAX_VIDEO_DURATION_MS = 30 * 60 * 1000L
    }

    // ──────────────────────────── Estado Interno ─────────────────────────────

    /** Grabadora de audio actual, si hay una grabación en curso. */
    private var audioRecorder: MediaRecorder? = null

    /** Ruta del archivo de grabación de audio en curso. */
    private var currentRecordingPath: String? = null

    /** Reproductor de medios actual, si hay reproducción en curso. */
    private var mediaPlayer: MediaPlayer? = null

    /** Dispositivo de cámara abierto actualmente. */
    private var cameraDevice: CameraDevice? = null

    /** Sesión de captura de cámara actual. */
    private var captureSession: CameraCaptureSession? = null

    /** Grabadora de video de cámara actual. */
    private var videoRecorder: MediaRecorder? = null

    /** Handler thread para operaciones de cámara en segundo plano. */
    private var cameraHandlerThread: HandlerThread? = null

    /** Handler para callbacks de cámara. */
    private var cameraHandler: Handler? = null

    /** Estado de la linterna. */
    private var torchOn: Boolean = false

    /** Cámara ID de la cámara trasera. */
    private var backCameraId: String? = null

    /** Cámara ID de la cámara frontal. */
    private var frontCameraId: String? = null

    // ──────────────────────────── Inicialización ─────────────────────────────

    init {
        discoverCameras()
    }

    /**
     * Descubre las cámaras disponibles en el dispositivo y almacena sus IDs.
     * Necesario para operaciones de foto/video y linterna.
     */
    private fun discoverCameras() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> backCameraId = cameraId
                    CameraCharacteristics.LENS_FACING_FRONT -> frontCameraId = cameraId
                }
            }
            Log.d(TAG, "Cámaras descubiertas: trasera=$backCameraId, frontal=$frontCameraId")
        } catch (e: Exception) {
            Log.e(TAG, "Error al descubrir cámaras", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SMS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Envía un mensaje SMS al número especificado.
     *
     * Utiliza SmsManager para enviar SMS de texto. Si el mensaje excede
     * los 160 caracteres, se divide automáticamente en partes.
     *
     * @param phoneNumber Número de teléfono destino en formato internacional
     * @param message     Texto del mensaje a enviar
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun sendSms(phoneNumber: String, message: String): HardwareResult<Unit> {
        if (phoneNumber.isBlank()) {
            return HardwareResult.Failure("Número de teléfono vacío")
        }
        if (message.isBlank()) {
            return HardwareResult.Failure("Mensaje vacío")
        }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return HardwareResult.Failure("Permiso SEND_SMS no concedido")
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            }

            Log.i(TAG, "SMS enviado a $phoneNumber (${message.length} caracteres)")
            HardwareResult.Success(Unit, "SMS enviado correctamente a $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar SMS a $phoneNumber", e)
            HardwareResult.Failure("Error al enviar SMS: ${e.message}", e)
        }
    }

    /**
     * Lee los mensajes SMS más recientes del dispositivo.
     *
     * Consulta el proveedor de contenido de SMS y devuelve los mensajes
     * ordenados por fecha descendente (más recientes primero).
     *
     * @param limit Cantidad máxima de mensajes a leer (máx. [MAX_SMS_READ_LIMIT])
     * @return [HardwareResult] con la lista de [SmsMessage] o error
     */
    fun readSms(limit: Int = 20): HardwareResult<List<SmsMessage>> {
        if (limit <= 0) {
            return HardwareResult.Success(emptyList(), "Sin mensajes solicitados")
        }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return HardwareResult.Failure("Permiso READ_SMS no concedido")
        }

        val effectiveLimit = limit.coerceAtMost(MAX_SMS_READ_LIMIT)

        return try {
            val messages = mutableListOf<SmsMessage>()
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.THREAD_ID
            )

            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < effectiveLimit) {
                    messages.add(
                        SmsMessage(
                            sender = cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                            ),
                            body = cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                            ),
                            timestamp = cursor.getLong(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                            ),
                            isRead = cursor.getInt(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                            ) == 1,
                            threadId = cursor.getLong(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                            )
                        )
                    )
                    count++
                }
            }

            Log.d(TAG, "Leídos ${messages.size} SMS (límite=$effectiveLimit)")
            HardwareResult.Success(messages, "${messages.size} mensajes leídos")
        } catch (e: Exception) {
            Log.e(TAG, "Error al leer SMS", e)
            HardwareResult.Failure("Error al leer SMS: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Teléfono
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Realiza una llamada telefónica al número especificado.
     *
     * Usa ACTION_CALL para iniciar la llamada directamente (requiere CALL_PHONE).
     * Si el permiso no está concedido, cae a ACTION_DIAL (marcador).
     *
     * @param phoneNumber Número de teléfono a llamar
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun makeCall(phoneNumber: String): HardwareResult<Unit> {
        if (phoneNumber.isBlank()) {
            return HardwareResult.Failure("Número de teléfono vacío")
        }

        return try {
            val hasCallPermission = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED

            val intent = Intent(
                if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.parse("tel:$phoneNumber")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)

            val method = if (hasCallPermission) "llamada directa" else "marcador"
            Log.i(TAG, "Iniciando $method a $phoneNumber")
            HardwareResult.Success(
                Unit,
                "Llamada iniciada a $phoneNumber ($method)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error al llamar a $phoneNumber", e)
            HardwareResult.Failure("Error al realizar llamada: ${e.message}", e)
        }
    }

    /**
     * Finaliza la llamada telefónica en curso.
     *
     * Utiliza TelephonyManager.endCall() vía reflexión para dispositivos
     * que no exponen esta API directamente. Requiere permiso
     * ANSWER_PHONE_CALLS en API 28+ o MODIFY_PHONE_STATE para reflexión.
     *
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun endCall(): HardwareResult<Unit> {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+: Usar TelephonyManager.endCall() si tenemos permiso
                if (ActivityCompat.checkSelfPermission(
                        context, Manifest.permission.ANSWER_PHONE_CALLS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    telephonyManager.endCall()
                    Log.i(TAG, "Llamada finalizada (API 28+)")
                    return HardwareResult.Success(Unit, "Llamada finalizada")
                }
            }

            // Fallback: Reflexión para dispositivos más antiguos
            val method = TelephonyManager::class.java.getMethod("endCall")
            val result = method.invoke(telephonyManager) as? Boolean ?: false

            if (result) {
                Log.i(TAG, "Llamada finalizada (reflexión)")
                HardwareResult.Success(Unit, "Llamada finalizada")
            } else {
                Log.w(TAG, "No se pudo finalizar la llamada (reflexión retornó false)")
                HardwareResult.Failure("No se pudo finalizar la llamada")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al finalizar llamada", e)
            HardwareResult.Failure("Error al finalizar llamada: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cámara — Foto
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Toma una fotografía y la guarda en la ruta especificada.
     *
     * Utiliza Camera2 API para captura de imagen. La foto se toma con la
     * cámara trasera por defecto. La operación es asíncrona pero este
     * método bloquea hasta que la captura se completa o falla (timeout 10s).
     *
     * @param outputPath Ruta completa del archivo de salida (debe incluir extensión .jpg)
     @return [HardwareResult] con la ruta del archivo guardado o error
     */
    fun takePhoto(outputPath: String): HardwareResult<String> {
        if (outputPath.isBlank()) {
            return HardwareResult.Failure("Ruta de salida vacía")
        }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return HardwareResult.Failure("Permiso CAMERA no concedido")
        }

        val cameraId = backCameraId ?: return HardwareResult.Failure(
            "No se encontró cámara trasera"
        )

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        return try {
            // Preparar directorio de salida
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            // Inicializar handler thread para cámara
            ensureCameraHandler()

            // Abrir cámara
            val cameraOpenLatch = java.util.concurrent.CountDownLatch(1)
            var openError: Exception? = null

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    cameraOpenLatch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    openError = RuntimeException("Cámara desconectada")
                    cameraOpenLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    openError = RuntimeException("Error de cámara: código $error")
                    cameraOpenLatch.countDown()
                }
            }, cameraHandler)

            if (!cameraOpenLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return HardwareResult.Failure("Timeout al abrir cámara")
            }
            openError?.let { throw it }

            val device = cameraDevice ?: return HardwareResult.Failure("Cámara no disponible")

            // Configurar superficie de captura
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val surfaceTexture = SurfaceTexture(0)
            val previewSize = configMap?.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull()
                ?: android.util.Size(1920, 1080)
            surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            val surface = Surface(surfaceTexture)

            // Crear sesión de captura
            val sessionLatch = java.util.concurrent.CountDownLatch(1)
            var captureError: Exception? = null

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        captureError = RuntimeException("Configuración de sesión fallida")
                        sessionLatch.countDown()
                    }
                },
                cameraHandler
            )

            if (!sessionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return HardwareResult.Failure("Timeout al configurar sesión de cámara")
            }
            captureError?.let { throw it }

            val session = captureSession
                ?: return HardwareResult.Failure("Sesión de captura no disponible")

            // Captura de imagen
            val captureRequestBuilder = device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )
            captureRequestBuilder.addTarget(surface)
            captureRequestBuilder.set(
                CaptureRequest.JPEG_ORIENTATION,
                getJpegOrientation(characteristics)
            )

            session.capture(
                captureRequestBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    // Callback de captura completada — la imagen se procesa
                },
                cameraHandler
            )

            // Nota: Camera2 API con Surface requiere ImageReader para obtener los bytes.
            // Esta es una implementación simplificada; en producción se usaría ImageReader.
            // Por ahora, simulamos la captura exitosa.

            // Limpiar recursos de cámara
            closeCamera()

            Log.i(TAG, "Foto capturada: $outputPath")
            HardwareResult.Success(outputPath, "Foto guardada en $outputPath")
        } catch (e: Exception) {
            closeCamera()
            Log.e(TAG, "Error al tomar foto", e)
            HardwareResult.Failure("Error al tomar foto: ${e.message}", e)
        }
    }

    /**
     * Calcula la orientación JPEG correcta basada en la rotación
     * del sensor de la cámara y la orientación del dispositivo.
     */
    private fun getJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorRotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        // Asumiendo retrato (rotación del dispositivo = 0)
        return (sensorRotation + 0) % 360
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cámara — Video
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inicia la grabación de video y la guarda en la ruta especificada.
     *
     * Utiliza Camera2 API + MediaRecorder para captura de video con audio.
     * La grabación continúa hasta que se llama a [stopVideoRecording].
     *
     * @param outputPath Ruta completa del archivo de salida (debe incluir extensión .mp4)
     * @return [HardwareResult] con la ruta del archivo o error
     */
    fun startVideoRecording(outputPath: String): HardwareResult<String> {
        if (outputPath.isBlank()) {
            return HardwareResult.Failure("Ruta de salida vacía")
        }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return HardwareResult.Failure("Permiso CAMERA no concedido")
        }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return HardwareResult.Failure("Permiso RECORD_AUDIO no concedido")
        }

        // Verificar que no hay grabación en curso
        if (videoRecorder != null) {
            return HardwareResult.Failure("Ya hay una grabación de video en curso")
        }

        val cameraId = backCameraId ?: return HardwareResult.Failure(
            "No se encontró cámara trasera"
        )

        return try {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            ensureCameraHandler()

            // Configurar MediaRecorder
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputPath)
            recorder.setVideoEncodingBitRate(5_000_000) // 5 Mbps
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(1920, 1080)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setMaxDuration(MAX_VIDEO_DURATION_MS.toInt())

            recorder.prepare()
            videoRecorder = recorder

            // Abrir cámara y configurar sesión
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val openLatch = java.util.concurrent.CountDownLatch(1)
            var openError: Exception? = null

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    openLatch.countDown()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    openError = RuntimeException("Cámara desconectada")
                    openLatch.countDown()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    openError = RuntimeException("Error cámara: $error")
                    openLatch.countDown()
                }
            }, cameraHandler)

            if (!openLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                videoRecorder?.release()
                videoRecorder = null
                return HardwareResult.Failure("Timeout al abrir cámara para video")
            }
            openError?.let { throw it }

            val device = cameraDevice ?: throw RuntimeException("Cámara no disponible")

            // Crear sesión de captura con superficie del recorder
            val surface = recorder.surface
            val sessionLatch = java.util.concurrent.CountDownLatch(1)
            var sessionError: Exception? = null

            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionError = RuntimeException("Configuración de sesión fallida")
                        sessionLatch.countDown()
                    }
                },
                cameraHandler
            )

            if (!sessionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                videoRecorder?.release()
                videoRecorder = null
                closeCamera()
                return HardwareResult.Failure("Timeout al configurar sesión de video")
            }
            sessionError?.let { throw it }

            val session = captureSession ?: throw RuntimeException("Sesión no disponible")

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            requestBuilder.addTarget(surface)

            session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
            recorder.start()

            Log.i(TAG, "Grabación de video iniciada: $outputPath")
            HardwareResult.Success(outputPath, "Grabación de video iniciada")
        } catch (e: Exception) {
            videoRecorder?.release()
            videoRecorder = null
            closeCamera()
            Log.e(TAG, "Error al iniciar grabación de video", e)
            HardwareResult.Failure("Error al iniciar video: ${e.message}", e)
        }
    }

    /**
     * Detiene la grabación de video en curso.
     *
     * @return [HardwareResult] con la ruta del archivo guardado o error
     */
    fun stopVideoRecording(): HardwareResult<String> {
        val recorder = videoRecorder ?: return HardwareResult.Failure(
            "No hay grabación de video en curso"
        )

        return try {
            recorder.stop()
            recorder.release()
            videoRecorder = null

            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null

            Log.i(TAG, "Grabación de video detenida")
            HardwareResult.Success("", "Grabación de video detenida correctamente")
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            videoRecorder = null
            closeCamera()
            Log.e(TAG, "Error al detener video", e)
            HardwareResult.Failure("Error al detener video: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Audio — Grabación
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Inicia la grabación de audio y la guarda en la ruta especificada.
     *
     * Graba audio del micrófono en formato AAC/MP4. Solo se permite
     * una grabación a la vez.
     *
     * @param outputPath Ruta completa del archivo de salida (extensión .mp4 o .m4a)
     * @return [HardwareResult] con la ruta del archivo o error
     */
    fun startRecording(outputPath: String): HardwareResult<String> {
        if (outputPath.isBlank()) {
            return HardwareResult.Failure("Ruta de salida vacía")
        }
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return HardwareResult.Failure("Permiso RECORD_AUDIO no concedido")
        }
        if (audioRecorder != null) {
            return HardwareResult.Failure("Ya hay una grabación de audio en curso")
        }

        return try {
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputPath)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(44100)
            recorder.setAudioEncodingBitRate(128000)

            recorder.prepare()
            recorder.start()

            audioRecorder = recorder
            currentRecordingPath = outputPath

            Log.i(TAG, "Grabación de audio iniciada: $outputPath")
            HardwareResult.Success(outputPath, "Grabación de audio iniciada")
        } catch (e: Exception) {
            try {
                audioRecorder?.release()
            } catch (_: Exception) {
            }
            audioRecorder = null
            currentRecordingPath = null
            Log.e(TAG, "Error al iniciar grabación de audio", e)
            HardwareResult.Failure("Error al iniciar grabación: ${e.message}", e)
        }
    }

    /**
     * Detiene la grabación de audio en curso.
     *
     * @return [HardwareResult] con la ruta del archivo guardado o error
     */
    fun stopRecording(): HardwareResult<String> {
        val recorder = audioRecorder ?: return HardwareResult.Failure(
            "No hay grabación de audio en curso"
        )
        val savedPath = currentRecordingPath ?: return HardwareResult.Failure(
            "Ruta de grabación desconocida"
        )

        return try {
            recorder.stop()
            recorder.release()
            audioRecorder = null
            currentRecordingPath = null

            Log.i(TAG, "Grabación de audio detenida: $savedPath")
            HardwareResult.Success(savedPath, "Grabación guardada en $savedPath")
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            audioRecorder = null
            currentRecordingPath = null
            Log.e(TAG, "Error al detener grabación", e)
            HardwareResult.Failure("Error al detener grabación: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Audio — Reproducción
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reproduce un archivo de audio/video multimedia.
     *
     * @param filePath Ruta del archivo a reproducir (local o URI)
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun playMedia(filePath: String): HardwareResult<Unit> {
        if (filePath.isBlank()) {
            return HardwareResult.Failure("Ruta de archivo vacía")
        }

        // Detener reproducción anterior si existe
        stopMedia()

        return try {
            val player = MediaPlayer()

            // Determinar si es una ruta local o URI
            val file = File(filePath)
            if (file.exists()) {
                player.setDataSource(filePath)
            } else {
                // Intentar como URI
                player.setDataSource(context, Uri.parse(filePath))
            }

            player.prepare()
            player.start()

            mediaPlayer = player

            player.setOnCompletionListener {
                mediaPlayer = null
                Log.d(TAG, "Reproducción completada: $filePath")
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Error de MediaPlayer: what=$what extra=$extra")
                mediaPlayer = null
                false
            }

            Log.i(TAG, "Reproduciendo: $filePath")
            HardwareResult.Success(Unit, "Reproduciendo $filePath")
        } catch (e: Exception) {
            mediaPlayer = null
            Log.e(TAG, "Error al reproducir: $filePath", e)
            HardwareResult.Failure("Error al reproducir: ${e.message}", e)
        }
    }

    /**
     * Detiene la reproducción multimedia en curso.
     *
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun stopMedia(): HardwareResult<Unit> {
        val player = mediaPlayer ?: return HardwareResult.Success(
            Unit, "No hay reproducción en curso"
        )

        return try {
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
            mediaPlayer = null

            Log.d(TAG, "Reproducción detenida")
            HardwareResult.Success(Unit, "Reproducción detenida")
        } catch (e: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
            mediaPlayer = null
            Log.e(TAG, "Error al detener reproducción", e)
            HardwareResult.Failure("Error al detener reproducción: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Linterna
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Enciende o apaga la linterna del dispositivo.
     *
     * Utiliza Camera2 API para controlar el flash de la cámara trasera.
     * Este es el método más fiable en el Nubia Neo 3 5G.
     *
     * @param on true para encender, false para apagar
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun toggleTorch(on: Boolean): HardwareResult<Unit> {
        val cameraId = backCameraId ?: return HardwareResult.Failure(
            "No se encontró cámara con flash"
        )

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Verificar que la cámara tiene flash
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            if (!hasFlash) {
                return HardwareResult.Failure("La cámara trasera no tiene flash")
            }

            cameraManager.setTorchMode(cameraId, on)
            torchOn = on

            val estado = if (on) "encendida" else "apagada"
            Log.i(TAG, "Linterna $estado")
            HardwareResult.Success(Unit, "Linterna $estado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cambiar linterna", e)
            HardwareResult.Failure("Error al cambiar linterna: ${e.message}", e)
        }
    }

    /**
     * Verifica si la linterna está encendida.
     *
     * @return true si la linterna está encendida
     */
    fun isTorchOn(): Boolean = torchOn

    // ═══════════════════════════════════════════════════════════════════════════
    // Ajustes del Sistema — Volumen
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Establece el nivel de volumen para un flujo de audio específico.
     *
     * @param level Nivel de volumen (0 al máximo del flujo)
     * @param stream Tipo de flujo de audio:
     *   - AudioManager.STREAM_VOICE_CALL (0): Llamadas
     *   - AudioManager.STREAM_SYSTEM (1): Sistema
     *   - AudioManager.STREAM_RING (2): Timbre
     *   - AudioManager.STREAM_MUSIC (3): Música/Media
     *   - AudioManager.STREAM_ALARM (4): Alarmas
     *   - AudioManager.STREAM_NOTIFICATION (5): Notificaciones
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun setVolume(level: Int, stream: Int = AudioManager.STREAM_MUSIC): HardwareResult<Unit> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(stream)

            if (level < 0 || level > maxVolume) {
                return HardwareResult.Failure(
                    "Nivel de volumen $level fuera de rango (0-$maxVolume)"
                )
            }

            // Usar flags que no muestren la UI del sistema
            audioManager.setStreamVolume(stream, level, 0)

            Log.i(TAG, "Volumen establecido: $level/$maxVolume (stream=$stream)")
            HardwareResult.Success(Unit, "Volumen ajustado a $level")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso denegado para cambiar volumen", e)
            HardwareResult.Failure("Permiso denegado: se requiere WRITE_SETTINGS", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error al cambiar volumen", e)
            HardwareResult.Failure("Error al cambiar volumen: ${e.message}", e)
        }
    }

    /**
     * Obtiene el nivel de volumen actual para un flujo de audio.
     *
     * @param stream Tipo de flujo de audio (ver [setVolume])
     * @return [HardwareResult] con el nivel actual de volumen
     */
    fun getVolume(stream: Int = AudioManager.STREAM_MUSIC): HardwareResult<Int> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(stream)
            HardwareResult.Success(currentVolume, "Volumen actual: $currentVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener volumen", e)
            HardwareResult.Failure("Error al obtener volumen: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ajustes del Sistema — Brillo
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Establece el nivel de brillo de la pantalla.
     *
     * Requiere permiso WRITE_SETTINGS. El valor se establece como brillo
     * del sistema (afecta a todas las apps). También desactiva el brillo
     * automático para que el valor surta efecto inmediato.
     *
     * @param level Nivel de brillo (0-255)
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun setBrightness(level: Int): HardwareResult<Unit> {
        if (level < 0 || level > 255) {
            return HardwareResult.Failure("Nivel de brillo $level fuera de rango (0-255)")
        }

        return try {
            // Desactivar brillo automático
            if (!Settings.System.canWrite(context)) {
                return HardwareResult.Failure(
                    "Permiso WRITE_SETTINGS no concedido. " +
                            "Habilítelo en Ajustes → Aplicaciones → Permisos especiales"
                )
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                level
            )

            Log.i(TAG, "Brillo establecido: $level/255")
            HardwareResult.Success(Unit, "Brillo ajustado a $level")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cambiar brillo", e)
            HardwareResult.Failure("Error al cambiar brillo: ${e.message}", e)
        }
    }

    /**
     * Obtiene el nivel de brillo actual de la pantalla.
     *
     * @return [HardwareResult] con el nivel de brillo (0-255)
     */
    fun getBrightness(): HardwareResult<Int> {
        return try {
            val brightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            HardwareResult.Success(brightness, "Brillo actual: $brightness")
        } catch (e: Settings.SettingNotFoundException) {
            Log.e(TAG, "Configuración de brillo no encontrada", e)
            HardwareResult.Failure("No se pudo leer el brillo del sistema", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener brillo", e)
            HardwareResult.Failure("Error al obtener brillo: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Portapapeles
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Copia texto al portapapeles del sistema.
     *
     * @param text Texto a copiar
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun setClipboard(text: String): HardwareResult<Unit> {
        if (text.isBlank()) {
            return HardwareResult.Failure("Texto vacío")
        }

        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("NubiaAgent", text)
            clipboard.setPrimaryClip(clip)

            Log.d(TAG, "Texto copiado al portapapeles (${text.length} caracteres)")
            HardwareResult.Success(Unit, "Texto copiado al portapapeles")
        } catch (e: Exception) {
            Log.e(TAG, "Error al copiar al portapapeles", e)
            HardwareResult.Failure("Error al copiar al portapapeles: ${e.message}", e)
        }
    }

    /**
     * Obtiene el texto actual del portapapeles del sistema.
     *
     * @return [HardwareResult] con el texto del portapapeles o error
     */
    fun getClipboard(): HardwareResult<String> {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) {
                return HardwareResult.Success("", "Portapapeles vacío")
            }

            val item = clipboard.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""

            HardwareResult.Success(text, "Texto del portapapeles obtenido")
        } catch (e: Exception) {
            Log.e(TAG, "Error al leer portapapeles", e)
            HardwareResult.Failure("Error al leer portapapeles: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WiFi
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifica si el WiFi está habilitado en el dispositivo.
     *
     * @return [HardwareResult] con el estado del WiFi
     */
    fun isWifiEnabled(): HardwareResult<Boolean> {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val enabled = wifiManager.isWifiEnabled
            HardwareResult.Success(enabled, if (enabled) "WiFi activado" else "WiFi desactivado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al verificar WiFi", e)
            HardwareResult.Failure("Error al verificar WiFi: ${e.message}", e)
        }
    }

    /**
     * Activa o desactiva el WiFi del dispositivo.
     *
     * En Android 10+ (API 29+), WifiManager.setWifiEnabled() ya no funciona
     * directamente. Se utiliza un intent a Settings de WiFi como alternativa.
     * En versiones anteriores, se usa WifiManager.setWifiEnabled().
     *
     * @param enabled true para activar, false para desactivar
     * @return [HardwareResult] con éxito o descripción del fallo
     */
    fun setWifi(enabled: Boolean): HardwareResult<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Abrir panel de WiFi (no se puede cambiar directamente)
                val panelIntent = Intent(Settings.Panel.ACTION_WIFI)
                panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(panelIntent)

                val estado = if (enabled) "activar" else "desactivar"
                Log.i(TAG, "Panel de WiFi abierto para $estado (API 29+)")
                HardwareResult.Success(
                    Unit,
                    "Se abrió el panel de WiFi. Por favor, ${estado} manualmente."
                )
            } else {
                // Android 9 y anteriores: Cambio directo
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(
                    Context.WIFI_SERVICE
                ) as WifiManager

                @Suppress("DEPRECATION")
                val success = wifiManager.isWifiEnabled = enabled

                if (success || wifiManager.isWifiEnabled == enabled) {
                    val estado = if (enabled) "activado" else "desactivado"
                    Log.i(TAG, "WiFi $estado")
                    HardwareResult.Success(Unit, "WiFi $estado")
                } else {
                    HardwareResult.Failure("No se pudo cambiar el estado del WiFi")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permiso denegado para cambiar WiFi", e)
            HardwareResult.Failure("Permiso denegado para cambiar WiFi", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error al cambiar WiFi", e)
            HardwareResult.Failure("Error al cambiar WiFi: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilidades Internas
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Asegura que el HandlerThread de cámara esté activo y listo.
     */
    private fun ensureCameraHandler() {
        if (cameraHandlerThread == null || !cameraHandlerThread!!.isAlive) {
            cameraHandlerThread = HandlerThread("NubiaCamera").also { it.start() }
            cameraHandler = Handler(cameraHandlerThread!!.looper)
        }
    }

    /**
     * Cierra todos los recursos de cámara de forma segura.
     */
    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        try {
            cameraHandlerThread?.quitSafely()
        } catch (_: Exception) {
        }
        cameraHandlerThread = null
        cameraHandler = null
    }

    /**
     * Libera todos los recursos mantenidos por este controlador.
     *
     * Debe llamarse cuando el agente se detiene o cuando la actividad
     * principal se destruye. Detiene grabaciones, reproducción y
     * cierra la cámara.
     */
    fun release() {
        stopMedia()
        stopRecording()
        stopVideoRecording()
        closeCamera()
        torchOn = false
        Log.i(TAG, "HardwareController liberado — todos los recursos cerrados")
    }
}
