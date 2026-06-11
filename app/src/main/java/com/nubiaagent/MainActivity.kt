package com.nubiaagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nubiaagent.perception.ear.WakeWordService
import com.nubiaagent.perception.vision.ScreenObserver

/**
 * MainActivity: Punto de entrada del usuario a NubiaAgent.
 *
 * Responsabilidades:
 * 1. Solicitar permisos necesarios al usuario
 * 2. Verificar que los servicios estén habilitados
 * 3. Proporcionar controles manuales (iniciar/detener escucha)
 * 4. Mostrar estado de la Capa de Percepción
 *
 * NOTA: Esta es una Activity temporal. En la versión final,
 * la UI será generada por el propio agente.
 */
class MainActivity : AppCompatActivity() {

    private var statusText: TextView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startPerceptionServices()
        } else {
            Toast.makeText(this, "Permisos requeridos para funcionar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout minimalista temporal
        statusText = TextView(this).apply {
            text = "NubiaAgent - Capa de Percepción\n\nInicializando..."
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        setContentView(statusText)

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    /**
     * Verifica y solicita todos los permisos necesarios.
     */
    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        // Permisos de audio
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Permisos de ubicación
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            neededPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Permisos de actividad
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
                neededPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

        // Permisos de notificaciones (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Permisos de almacenamiento
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        } else {
            // Verificar servicios del sistema
            checkSystemServices()
        }
    }

    private fun checkSystemServices() {
        // Verificar Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            showEnableAccessibilityDialog()
            return
        }

        // Verificar Notification Listener
        if (!isNotificationListenerEnabled()) {
            showEnableNotificationListenerDialog()
            return
        }

        // Verificar overlay
        if (!Settings.canDrawOverlays(this)) {
            showEnableOverlayDialog()
            return
        }

        // Todo listo, iniciar servicios
        startPerceptionServices()
    }

    private fun startPerceptionServices() {
        // Iniciar servicio de escucha
        WakeWordService.start(this)

        updateStatus()
        Toast.makeText(this, "NubiaAgent activo - Di \"Hey Nubia\"", Toast.LENGTH_LONG).show()
    }

    private fun updateStatus() {
        val status = buildString {
            appendLine("NubiaAgent - Capa de Percepción")
            appendLine()
            appendLine("📋 Estado de Módulos:")
            appendLine("  👂 Ear (Wake Word): ${if (WakeWordService.isRunning()) "✅ Activo" else "⬜ Inactivo"}")
            appendLine("  👁 Vision (Screen): ${if (ScreenObserver.isRunning()) "✅ Activo" else "⬜ Inactivo"}")
            appendLine("  📨 Events (Notif.): ${if (isNotificationListenerEnabled()) "✅ Activo" else "⬜ Inactivo"}")
            appendLine("  🔋 Hardware (Telem.): ✅ Activo")
            appendLine()
            appendLine("Dispositivo: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("RAM: ${getTotalRAM()} MB")
        }
        statusText?.text = status
    }

    // ==================== VERIFICACIONES ====================

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = packageName + "/" +
                com.nubiaagent.perception.vision.ScreenObserver::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val serviceName = packageName + "/" +
                com.nubiaagent.perception.events.NotificationInterceptor::class.java.canonicalName
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(serviceName)
    }

    // ==================== DIÁLOGOS DE HABILITACIÓN ====================

    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Activar Servicio de Accesibilidad")
            .setMessage("NubiaAgent necesita acceso de accesibilidad para ver la pantalla. " +
                    "Esto permite al asistente identificar elementos interactivos. " +
                    "Ningún dato sale de tu dispositivo.")
            .setPositiveButton("Activar") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    private fun showEnableNotificationListenerDialog() {
        AlertDialog.Builder(this)
            .setTitle("Activar Acceso a Notificaciones")
            .setMessage("NubiaAgent necesita leer notificaciones para clasificar mensajes " +
                    "urgentes y decidir cuándo interrumpirte. El contenido se procesa " +
                    "solo en tu dispositivo.")
            .setPositiveButton("Activar") { _, _ ->
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            .setCancelable(false)
            .show()
    }

    private fun showEnableOverlayDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permitir Superposición")
            .setMessage("NubiaAgent necesita permiso de superposición para " +
                    "mostrar respuestas sobre otras apps.")
            .setPositiveButton("Permitir") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setCancelable(false)
            .show()
    }

    private fun getTotalRAM(): Long {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }
}
