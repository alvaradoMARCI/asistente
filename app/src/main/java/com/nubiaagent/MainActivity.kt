package com.nubiaagent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nubiaagent.perception.ear.WakeWordService
import com.nubiaagent.perception.vision.ScreenObserver
import com.nubiaagent.perception.events.NotificationInterceptor
import com.nubiaagent.cognitive.engine.CloudInferenceEngine
import com.nubiaagent.cognitive.engine.SettingsActivity

/**
 * MainActivity: Punto de entrada del usuario a Dayana.
 *
 * Responsabilidades:
 * 1. Solicitar permisos necesarios al usuario
 * 2. Verificar que los servicios estén habilitados
 * 3. Proporcionar controles directos para activar servicios especiales
 * 4. Mostrar estado de la Capa de Percepción
 * 5. Botón de configuración del motor de IA
 */
class MainActivity : AppCompatActivity() {

    private var statusText: TextView? = null
    private var accessibilityStatus: TextView? = null
    private var notificationStatus: TextView? = null
    private var overlayStatus: TextView? = null
    private var accessibilityButton: Button? = null
    private var notificationButton: Button? = null
    private var overlayButton: Button? = null

    // Colores Mecha Futurista
    private val COLOR_BG = 0xFF1A1A2E.toInt()
    private val COLOR_SURFACE = 0xFF16213E.toInt()
    private val COLOR_ACCENT = 0xFFE94560.toInt()
    private val COLOR_CYAN = 0xFF00D4FF.toInt()
    private val COLOR_GREEN = 0xFF00C853.toInt()
    private val COLOR_ORANGE = 0xFFFF9800.toInt()
    private val COLOR_RED = 0xFFFF1744.toInt()
    private val COLOR_TEXT = 0xFFC0C0C0.toInt()
    private val COLOR_TEXT_DIM = 0xFF808080.toInt()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            updateStatus()
        } else {
            Toast.makeText(this, "Permisos requeridos para funcionar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar API keys preconfiguradas
        CloudInferenceEngine.initializeBuiltinKeys(this)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(32, 48, 32, 48)
        }

        // ===== TÍTULO =====
        rootLayout.addView(createTitle("DAYANA"))
        rootLayout.addView(createSubtitle("Asistente Inteligente v4"))
        rootLayout.addView(createSpacer(24))

        // ===== PERMISOS ESPECIALES (SECCIÓN CRÍTICA) =====
        rootLayout.addView(createSectionTitle("PERMISOS ESPECIALES"))
        rootLayout.addView(createInfoText("Estos permisos NO aparecen en Ajustes → App → Permisos.\nSe activan desde aquí directamente."))
        rootLayout.addView(createSpacer(12))

        // --- Accesibilidad ---
        rootLayout.addView(createLabel("1. Servicio de Accesibilidad"))
        accessibilityStatus = createStatusText("Verificando...")
        rootLayout.addView(accessibilityStatus)
        rootLayout.addView(createSpacer(4))
        accessibilityButton = createPermissionButton(
            "ACTIVAR ACCESIBILIDAD",
            COLOR_ACCENT
        ) { openAccessibilitySettings() }
        rootLayout.addView(accessibilityButton)
        rootLayout.addView(createSpacer(16))

        // --- Notificaciones ---
        rootLayout.addView(createLabel("2. Acceso a Notificaciones"))
        notificationStatus = createStatusText("Verificando...")
        rootLayout.addView(notificationStatus)
        rootLayout.addView(createSpacer(4))
        notificationButton = createPermissionButton(
            "ACTIVAR NOTIFICACIONES",
            COLOR_ORANGE
        ) { openNotificationListenerSettings() }
        rootLayout.addView(notificationButton)
        rootLayout.addView(createSpacer(16))

        // --- Overlay ---
        rootLayout.addView(createLabel("3. Superposición (Overlay)"))
        overlayStatus = createStatusText("Verificando...")
        rootLayout.addView(overlayStatus)
        rootLayout.addView(createSpacer(4))
        overlayButton = createPermissionButton(
            "ACTIVAR OVERLAY",
            COLOR_CYAN
        ) { openOverlaySettings() }
        rootLayout.addView(overlayButton)
        rootLayout.addView(createSpacer(24))

        // ===== PERMISOS NORMALES =====
        rootLayout.addView(createSectionTitle("PERMISOS DE LA APP"))
        val normalPermsButton = Button(this).apply {
            text = "OTORGAR PERMISOS (MIC, GPS, ETC)"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(COLOR_SURFACE)
            setPadding(24, 20, 24, 20)
            textSize = 13f
            setOnClickListener { checkAndRequestNormalPermissions() }
        }
        rootLayout.addView(normalPermsButton)
        rootLayout.addView(createSpacer(24))

        // ===== ESTADO DE MÓDULOS =====
        rootLayout.addView(createSectionTitle("ESTADO DE MODULOS"))
        statusText = TextView(this).apply {
            text = "Cargando..."
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(COLOR_SURFACE)
        }
        rootLayout.addView(statusText)
        rootLayout.addView(createSpacer(24))

        // ===== CONFIGURACIÓN =====
        rootLayout.addView(createSectionTitle("CONFIGURACION"))

        val settingsButton = Button(this).apply {
            text = "CONFIGURAR MOTOR DE IA"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(COLOR_ACCENT)
            setPadding(24, 20, 24, 20)
            textSize = 14f
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        rootLayout.addView(settingsButton)
        rootLayout.addView(createSpacer(12))

        val refreshButton = Button(this).apply {
            text = "REFRESCAR ESTADO"
            setTextColor(COLOR_TEXT)
            setBackgroundColor(COLOR_SURFACE)
            setPadding(24, 16, 24, 16)
            textSize = 13f
            setOnClickListener { updateStatus() }
        }
        rootLayout.addView(refreshButton)

        val scrollView = ScrollView(this).apply { addView(rootLayout) }
        setContentView(scrollView)

        // Solicitar permisos normales al inicio
        checkAndRequestNormalPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ==================== ABRIR CONFIGURACIONES ESPECIALES ====================

    /**
     * Abre la configuración de Accesibilidad.
     * Primero intenta abrir directamente el servicio de Dayana,
     * si no funciona, abre la lista general de accesibilidad.
     */
    private fun openAccessibilitySettings() {
        try {
            // Intent 1: Abrir directamente los detalles del servicio de accesibilidad
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Intentar abrir directamente la pantalla del servicio
            val componentName = ComponentName(this, ScreenObserver::class.java)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())

            startActivity(intent)
            Toast.makeText(this, "Busca 'Dayana' en la lista y actívalo", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            // Fallback: abrir accesibilidad general
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Busca 'Dayana' en Servicios de Accesibilidad y actívalo", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "No se pudo abrir configuración de accesibilidad", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Abre la configuración de acceso a notificaciones.
     */
    private fun openNotificationListenerSettings() {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "Busca 'Dayana' y actíva el acceso a notificaciones", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir configuración de notificaciones", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Abre la configuración de superposición (overlay).
     */
    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Activa 'Permitir superposición' para Dayana", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir configuración de overlay", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== PERMISOS NORMALES ====================

    private fun checkAndRequestNormalPermissions() {
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

        // Permisos de SMS y llamadas
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            neededPermissions.add(Manifest.permission.SEND_SMS)
        }
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            neededPermissions.add(Manifest.permission.READ_SMS)
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            neededPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            neededPermissions.add(Manifest.permission.CALL_PHONE)
        }
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            neededPermissions.add(Manifest.permission.READ_CALL_LOG)
        }

        // Contactos
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            neededPermissions.add(Manifest.permission.READ_CONTACTS)
        }
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) {
            neededPermissions.add(Manifest.permission.WRITE_CONTACTS)
        }

        // Calendario
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            neededPermissions.add(Manifest.permission.READ_CALENDAR)
        }

        // Cámara
        if (!hasPermission(Manifest.permission.CAMERA)) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        // Almacenamiento
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    // ==================== ACTUALIZAR ESTADO ====================

    private fun updateStatus() {
        val isAccessibilityOn = isAccessibilityServiceEnabled()
        val isNotificationOn = isNotificationListenerEnabled()
        val isOverlayOn = Settings.canDrawOverlays(this)

        // Actualizar estados de permisos especiales
        accessibilityStatus?.apply {
            text = if (isAccessibilityOn) "✓ ACTIVADO" else "✗ NO ACTIVADO — Requerido para ver la pantalla"
            setTextColor(if (isAccessibilityOn) COLOR_GREEN else COLOR_RED)
        }
        notificationStatus?.apply {
            text = if (isNotificationOn) "✓ ACTIVADO" else "✗ NO ACTIVADO — Requerido para leer notificaciones"
            setTextColor(if (isNotificationOn) COLOR_GREEN else COLOR_RED)
        }
        overlayStatus?.apply {
            text = if (isOverlayOn) "✓ ACTIVADO" else "✗ NO ACTIVADO — Requerido para mostrar respuestas"
            setTextColor(if (isOverlayOn) COLOR_GREEN else COLOR_RED)
        }

        // Cambiar texto de botones según estado
        accessibilityButton?.text = if (isAccessibilityOn) "ACCESIBILIDAD: ACTIVADO ✓" else "ACTIVAR ACCESIBILIDAD"
        accessibilityButton?.setBackgroundColor(if (isAccessibilityOn) COLOR_GREEN else COLOR_ACCENT)
        notificationButton?.text = if (isNotificationOn) "NOTIFICACIONES: ACTIVADO ✓" else "ACTIVAR NOTIFICACIONES"
        notificationButton?.setBackgroundColor(if (isNotificationOn) COLOR_GREEN else COLOR_ORANGE)
        overlayButton?.text = if (isOverlayOn) "OVERLAY: ACTIVADO ✓" else "ACTIVAR OVERLAY"
        overlayButton?.setBackgroundColor(if (isOverlayOn) COLOR_GREEN else COLOR_CYAN)

        // Estado de módulos
        val cloudStatus = if (CloudInferenceEngine.isConfigured(this)) {
            val provider = CloudInferenceEngine.getProvider(this)
            val model = CloudInferenceEngine.getModel(this)
            "✓ Configurado ($provider / $model)"
        } else {
            "✗ No configurado — Ve a Configurar Motor de IA"
        }

        val allSpecial = isAccessibilityOn && isNotificationOn && isOverlayOn

        val status = buildString {
            appendLine("  Ear (Wake Word):    ${if (WakeWordService.isRunning()) "✓ Activo" else "○ Inactivo"}")
            appendLine("  Vision (Pantalla):  ${if (ScreenObserver.isRunning()) "✓ Activo" else if (isAccessibilityOn) "○ Listo" else "✗ Sin permiso"}")
            appendLine("  Events (Notif.):    ${if (isNotificationOn) "✓ Activo" else "✗ Sin permiso"}")
            appendLine("  Hardware (Telem.):  ✓ Activo")
            appendLine()
            appendLine("Motor de Inferencia:")
            appendLine("  $cloudStatus")
            appendLine()
            appendLine("Dispositivo: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}")
            appendLine("RAM: ${getTotalRAM()} MB")
            appendLine()
            if (allSpecial) {
                appendLine("=== TODOS LOS PERMISOS LISTOS ===")
                appendLine("Di \"Hey Dayana\" para interactuar")
            } else {
                appendLine("!!! FALTAN PERMISOS ESPECIALES !!!")
                appendLine("Activa los 3 permisos de arriba")
            }
        }
        statusText?.text = status

        // Si todo está activo, iniciar servicios
        if (allSpecial) {
            startPerceptionServices()
        }
    }

    private fun startPerceptionServices() {
        if (!WakeWordService.isRunning()) {
            WakeWordService.start(this)
        }
    }

    // ==================== VERIFICACIONES ====================

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = packageName + "/" +
                ScreenObserver::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val serviceName = packageName + "/" +
                NotificationInterceptor::class.java.canonicalName
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(serviceName)
    }

    private fun getTotalRAM(): Long {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    // ==================== UI HELPERS ====================

    private fun createTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_ACCENT)
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
    }

    private fun createSubtitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT_DIM)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
    }

    private fun createSectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_CYAN)
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
    }

    private fun createLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
    }

    private fun createStatusText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT_DIM)
            textSize = 12f
            setPadding(8, 4, 8, 4)
        }
    }

    private fun createInfoText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT_DIM)
            textSize = 11f
            setPadding(8, 4, 8, 4)
        }
    }

    private fun createPermissionButton(text: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(color)
            setPadding(24, 20, 24, 20)
            textSize = 14f
            setOnClickListener { onClick() }
        }
    }

    private fun createSpacer(heightPx: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightPx
            )
        }
    }
}
