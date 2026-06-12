package com.nubiaagent.perception.ear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nubiaagent.MainActivity
import com.nubiaagent.R
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * VoskModelDownloader: Servicio en primer plano que descarga los modelos Vosk
 * desde alphacephei.com y los descomprime en el almacenamiento interno.
 *
 * Flujo:
 * 1. Descarga vosk-model-small-es-0.42.zip (~50MB) → modelo de wake word
 * 2. Descomprime en filesDir/models/vosk-model-small-es-0.42/
 * 3. Descarga vosk-model-es-0.42.zip (~1.3GB) → modelo de comandos
 * 4. Descomprime en filesDir/models/vosk-model-es-0.42/
 * 5. Limpia ZIPs y notifica a MainActivity para reiniciar servicios
 *
 * Nota: Se usa ForegroundService para que Android no mate la descarga
 * (el modelo grande pesa 1.3GB y puede tardar varios minutos).
 */
class VoskModelDownloader : Service() {

    companion object {
        private const val TAG = "VoskModelDownloader"
        private const val CHANNEL_ID = "vosk_download_channel"
        private const val NOTIFICATION_ID = 2001

        // URLs oficiales de modelos Vosk en español
        private const val SMALL_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        private const val FULL_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-es-0.42.zip"

        // Nombres de directorios destino
        private const val SMALL_MODEL_DIR = "vosk-model-small-es-0.42"
        private const val FULL_MODEL_DIR = "vosk-model-es-0.42"

        // Acciones del servicio
        const val ACTION_START_DOWNLOAD = "com.nubiaagent.action.START_VOSK_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.nubiaagent.action.CANCEL_VOSK_DOWNLOAD"

        // Broadcast de progreso
        const val BROADCAST_PROGRESS = "com.nubiaagent.VOSK_DOWNLOAD_PROGRESS"
        const val EXTRA_PHASE = "phase"           // "small_download", "small_extract", "full_download", "full_extract", "complete", "error"
        const val EXTRA_PROGRESS = "progress"     // 0-100
        const val EXTRA_MESSAGE = "message"

        // Estado compartido para que MainActivity consulte
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoskModelDownloader::class.java).apply {
                action = ACTION_START_DOWNLOAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoskModelDownloader::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }

        /**
         * Verifica si AMBOS modelos Vosk existen y están completos.
         * Revisa la existencia de archivos esenciales dentro de cada directorio.
         */
        fun modelosDisponibles(context: Context): Boolean {
            return modeloDisponible(context, SMALL_MODEL_DIR) &&
                    modeloDisponible(context, FULL_MODEL_DIR)
        }

        /**
         * Verifica un modelo individual.
         * Un modelo Vosk válido contiene al menos: am/final.mdl o conf/model.conf
         */
        fun modeloDisponible(context: Context, modelDir: String): Boolean {
            val dir = File(context.filesDir, "models/$modelDir")
            if (!dir.exists() || !dir.isDirectory) return false

            // Verificar archivos esenciales del modelo Vosk
            val amFile = File(dir, "am/final.mdl")
            val confFile = File(dir, "conf/model.conf")
            val graphFile = File(dir, "graph/HCLr.fst")

            return amFile.exists() || confFile.exists() || graphFile.exists()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                cancelled = true
                downloadJob?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_DOWNLOAD -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparando descarga...", 0))
                startDownload()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        serviceScope.cancel()
        isRunning = false
    }

    // ==================== LÓGICA DE DESCARGA ====================

    private fun startDownload() {
        downloadJob = serviceScope.launch {
            try {
                val modelsDir = File(filesDir, "models")
                if (!modelsDir.exists()) modelsDir.mkdirs()

                // ── FASE 1: Modelo pequeño (wake word, ~50MB) ──
                if (modeloDisponible(this@VoskModelDownloader, SMALL_MODEL_DIR)) {
                    Log.i(TAG, "Modelo small ya existe, saltando descarga")
                    broadcastProgress("small_download", 100, "Modelo wake word ya instalado")
                } else {
                    broadcastProgress("small_download", 0, "Descargando modelo de wake word (≈50 MB)...")
                    updateNotification("Descargando modelo de wake word...", 0)

                    val smallZip = File(modelsDir, "$SMALL_MODEL_DIR.zip")
                    downloadFile(SMALL_MODEL_URL, smallZip) { progress ->
                        broadcastProgress("small_download", progress, "Descargando modelo wake: $progress%")
                        updateNotification("Descargando modelo de wake word... $progress%", progress)
                    }

                    if (cancelled) return@launch

                    broadcastProgress("small_extract", 0, "Descomprimiendo modelo de wake word...")
                    updateNotification("Descomprimiendo modelo de wake word...", 50)
                    extractZip(smallZip, modelsDir)
                    smallZip.delete()
                    broadcastProgress("small_extract", 100, "Modelo de wake word instalado")
                }

                // ── FASE 2: Modelo completo (comandos, ~1.3GB) ──
                if (modeloDisponible(this@VoskModelDownloader, FULL_MODEL_DIR)) {
                    Log.i(TAG, "Modelo completo ya existe, saltando descarga")
                    broadcastProgress("full_download", 100, "Modelo de comandos ya instalado")
                } else {
                    broadcastProgress("full_download", 0, "Descargando modelo de comandos (≈1.3 GB)...")
                    updateNotification("Descargando modelo de comandos... (1.3 GB)", 0)

                    val fullZip = File(modelsDir, "$FULL_MODEL_DIR.zip")
                    downloadFile(FULL_MODEL_URL, fullZip) { progress ->
                        broadcastProgress("full_download", progress, "Descargando modelo comandos: $progress%")
                        // Mapear progreso a rango 0-85% (dejamos 85-100% para extracción)
                        val mapped = (progress * 0.85).toInt()
                        updateNotification("Descargando modelo de comandos... $progress%", mapped)
                    }

                    if (cancelled) return@launch

                    broadcastProgress("full_extract", 0, "Descomprimiendo modelo de comandos...")
                    updateNotification("Descomprimiendo modelo de comandos...", 90)
                    extractZip(fullZip, modelsDir)
                    fullZip.delete()
                    broadcastProgress("full_extract", 100, "Modelo de comandos instalado")
                }

                // ── FASE 3: Completado ──
                broadcastProgress("complete", 100, "Modelos de voz instalados correctamente")
                updateNotification("Modelos instalados. Reiniciando servicios...", 100)

                // Esperar 2 segundos para que el usuario vea la notificación
                delay(2000)

                // Reiniciar servicios de percepción
                restartPerceptionServices()

            } catch (e: CancellationException) {
                Log.w(TAG, "Descarga cancelada por el usuario")
                updateNotification("Descarga cancelada", 0)
            } catch (e: Exception) {
                Log.e(TAG, "Error en descarga de modelos Vosk", e)
                broadcastProgress("error", 0, "Error: ${e.message}")
                updateNotification("Error al descargar modelos: ${e.message}", 0)
            } finally {
                stopSelf()
            }
        }
    }

    /**
     * Descarga un archivo desde URL con callback de progreso.
     * Usa HttpURLConnection directamente (sin dependencias adicionales).
     */
    private fun downloadFile(urlStr: String, destFile: File, onProgress: (Int) -> Unit) {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        val totalSize = connection.contentLengthLong
        if (totalSize <= 0L) {
            Log.w(TAG, "Servidor no reportó Content-Length para $urlStr")
        }

        val input: InputStream = BufferedInputStream(connection.inputStream)
        val output = FileOutputStream(destFile)

        val buffer = ByteArray(8192)
        var downloaded = 0L
        var lastProgress = -1

        try {
            while (true) {
                if (cancelled) {
                    connection.disconnect()
                    destFile.delete()
                    throw CancellationException("Descarga cancelada")
                }

                val read = input.read(buffer)
                if (read == -1) break

                output.write(buffer, 0, read)
                downloaded += read

                if (totalSize > 0) {
                    val progress = ((downloaded * 100) / totalSize).toInt().coerceIn(0, 100)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
            }
        } finally {
            output.flush()
            output.close()
            input.close()
            connection.disconnect()
        }

        Log.i(TAG, "Descarga completada: ${destFile.name} (${downloaded / 1024 / 1024} MB)")
    }

    /**
     * Descomprime un archivo ZIP en el directorio destino.
     * Los modelos Vosk tienen la estructura: vosk-model-xxx/graph/, am/, conf/, ivector/
     */
    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(8192)
            var extractedFiles = 0

            while (entry != null && !cancelled) {
                val outputFile = File(destDir, entry.name)

                // Seguridad: verificar que no haya path traversal
                if (!outputFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    Log.w(TAG, "Path traversal detectado, saltando: ${entry.name}")
                    entry = zis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    outputFile.mkdirs()
                } else {
                    // Crear directorio padre si no existe
                    outputFile.parentFile?.mkdirs()

                    FileOutputStream(outputFile).use { fos ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read == -1) break
                            fos.write(buffer, 0, read)
                        }
                    }
                    extractedFiles++
                }

                entry = zis.nextEntry
            }

            Log.i(TAG, "Extracción completada: $extractedFiles archivos desde ${zipFile.name}")
        }
    }

    /**
     * Reinicia los servicios de percepción llamando a MainActivity.
     */
    private fun restartPerceptionServices() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("restart_services", true)
        }
        startActivity(intent)
    }

    // ==================== NOTIFICACIONES ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Descarga de Modelos de Voz",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descarga de modelos Vosk para reconocimiento de voz offline"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String, progress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dayana - Modelos de Voz")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setProgress(100, progress, progress == 0 && message.contains("Preparando"))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(message: String, progress: Int) {
        val notification = buildNotification(message, progress)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ==================== BROADCAST DE PROGRESO ====================

    private fun broadcastProgress(phase: String, progress: Int, message: String) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "[$phase] $progress% — $message")
    }
}
