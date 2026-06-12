package com.nubiaagent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.cognitive.engine.SettingsActivity
import java.io.File

/**
 * MainActivity: Punto de entrada del usuario a Dayana.
 *
 * Responsabilidades:
 * 1. Solicitar permisos necesarios al usuario
 * 2. Verificar que los servicios estén habilitados
 * 3. Proporcionar controles directos para activar servicios especiales
 * 4. Mostrar estado de la Capa de Percepción
 * 5. Botón de configuración del motor de IA
 *
 * OPTIMIZACIÓN PARA NUBIA/MyOS:
 * - Los dispositivos Nubia tienen un sistema de seguridad que bloquea
 *   automáticamente permisos sensibles (SMS, Accesibilidad, Notificaciones)
 * - Se incluyen intents directos y guías específicas para Nubia
 * - Se intentan múltiples rutas de configuración para maximizar compatibilidad
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Dayana/Main"
    }

    private var statusText: TextView? = null
    private var accessibilityStatus: TextView? = null
    private var notificationStatus: TextView? = null
    private var overlayStatus: TextView? = null
    private var accessibilityButton: Button? = null
    private var notificationButton: Button? = null
    private var overlayButton: Button? = null
    private var nubiaWarningText: TextView? = null

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
    private val COLOR_YELLOW = 0xFFFFEB3B.toInt()

    // Detectar si es un dispositivo Nubia/ZTE
    private val isNubiaDevice: Boolean by lazy {
        Build.MANUFACTURER.equals("nubia", ignoreCase = true) ||
        Build.MANUFACTURER.equals("zte", ignoreCase = true) ||
        Build.BRAND.equals("nubia", ignoreCase = true) ||
        Build.BRAND.equals("zte", ignoreCase = true) ||
        Build.MODEL.contains("nubia", ignoreCase = true) ||
        Build.MODEL.contains("Neo", ignoreCase = true)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            updateStatus()
        } else {
            // En Nubia, algunos permisos se bloquean automáticamente
            // Mostrar guía específica
            val deniedPerms = permissions.filter { !it.value }.keys
            val hasSmsDenied = deniedPerms.any {
                it.contains("SMS") || it.contains("CALL") || it.contains("PHONE")
            }
            if (hasSmsDenied && isNubiaDevice) {
                showNubiaPermissionGuide("SMS/Llamadas")
            }
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
        rootLayout.addView(createSpacer(16))

        // ===== AVISO NUBIA (si aplica) =====
        if (isNubiaDevice) {
            nubiaWarningText = TextView(this).apply {
                text = "⚠ DISPOSITIVO NUBIA DETECTADO\nTu teléfono tiene seguridad extra que puede bloquear permisos. Usa los botones de abajo para activar cada permiso manualmente."
                setTextColor(COLOR_YELLOW)
                textSize = 12f
                setPadding(16, 12, 16, 12)
                setBackgroundColor(0xFF332200.toInt())
            }
            rootLayout.addView(nubiaWarningText)
            rootLayout.addView(createSpacer(16))
        }

        // ===== PERMISOS ESPECIALES (SECCIÓN CRÍTICA) =====
        rootLayout.addView(createSectionTitle("PERMISOS ESPECIALES"))
        rootLayout.addView(createInfoText(
            "Estos permisos NO aparecen en Ajustes → App → Permisos.\n" +
            "Se activan desde Ajustes del Sistema (Accesibilidad / Notificaciones)."
        ))
        rootLayout.addView(createSpacer(12))

        // --- Accesibilidad ---
        rootLayout.addView(createLabel("1. Servicio de Accesibilidad"))
        rootLayout.addView(createInfoText("Permite a Dayana ver la pantalla y ejecutar acciones"))
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
        rootLayout.addView(createInfoText("Permite a Dayana leer y clasificar notificaciones"))
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
        rootLayout.addView(createInfoText("Permite a Dayana mostrar respuestas flotantes"))
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
        rootLayout.addView(createInfoText("Micrófono, GPS, Cámara, SMS, Contactos, etc."))
        rootLayout.addView(createSpacer(4))
        val normalPermsButton = Button(this).apply {
            text = "OTORGAR TODOS LOS PERMISOS"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(COLOR_SURFACE)
            setPadding(24, 20, 24, 20)
            textSize = 13f
            setOnClickListener { checkAndRequestNormalPermissions() }
        }
        rootLayout.addView(normalPermsButton)

        // Botón especial para Nubia: abrir ajustes de permisos del sistema
        if (isNubiaDevice) {
            rootLayout.addView(createSpacer(8))
            val nubiaPermButton = Button(this).apply {
                text = "🔧 AJUSTES DE PERMISOS NUBIA"
                setTextColor(COLOR_YELLOW)
                setBackgroundColor(0xFF332200.toInt())
                setPadding(24, 20, 24, 20)
                textSize = 13f
                setOnClickListener { openNubiaPermissionSettings() }
            }
            rootLayout.addView(nubiaPermButton)
        }
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

        // Botón de guía Nubia
        if (isNubiaDevice) {
            val guideButton = Button(this).apply {
                text = "📖 GUÍA DE PERMISOS NUBIA"
                setTextColor(COLOR_YELLOW)
                setBackgroundColor(0xFF332200.toInt())
                setPadding(24, 16, 24, 16)
                textSize = 13f
                setOnClickListener { showNubiaFullGuide() }
            }
            rootLayout.addView(guideButton)
            rootLayout.addView(createSpacer(12))
        }

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
     * Estrategia multi-nivel para Nubia/MyOS:
     * 1. Intent directo a los detalles del servicio
     * 2. Intent a accesibilidad general con fragment_args
     * 3. Fallback a accesibilidad general
     */
    private fun openAccessibilitySettings() {
        // Estrategia 1: Intent directo al servicio de accesibilidad de Dayana
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // En algunos dispositivos, esto abre directamente el servicio
            val componentName = ComponentName(this, ScreenObserver::class.java)
            val flatName = componentName.flattenToString()

            // Intentar con settings fragment args (funciona en algunos dispositivos)
            intent.putExtra(":settings:fragment_args_key", flatName)
            intent.putExtra(":settings:show_fragment_args", flatName)

            startActivity(intent)
            Toast.makeText(this,
                "Busca 'Dayana - Visión de Pantalla' en la lista y actívalo",
                Toast.LENGTH_LONG
            ).show()
            return
        } catch (_: Exception) { }

        // Estrategia 2: Accesibilidad general
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this,
                "Busca 'Dayana' en Servicios de Accesibilidad y actívalo",
                Toast.LENGTH_LONG
            ).show()
        } catch (e2: Exception) {
            Toast.makeText(this,
                "No se pudo abrir configuración de accesibilidad",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Abre la configuración de acceso a notificaciones.
     * Intenta primero el intent directo, luego el general.
     */
    private fun openNotificationListenerSettings() {
        // Estrategia 1: Intent directo a los detalles del NotificationListener
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Intentar abrir directamente en el servicio de Dayana
            val componentName = ComponentName(this, NotificationInterceptor::class.java)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())

            startActivity(intent)
            Toast.makeText(this,
                "Busca 'Dayana - Notificaciones' y actívalo",
                Toast.LENGTH_LONG
            ).show()
            return
        } catch (_: Exception) { }

        // Estrategia 2: General
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this,
                "Busca 'Dayana' y actíva el acceso a notificaciones",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this,
                "No se pudo abrir configuración de notificaciones",
                Toast.LENGTH_LONG
            ).show()
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

    /**
     * Abre los ajustes de permisos específicos de Nubia/MyOS.
     * En dispositivos Nubia, hay un gestor de permisos adicional
     * que puede bloquear permisos sensibles automáticamente.
     */
    private fun openNubiaPermissionSettings() {
        // Estrategia 1: Intent directo a los detalles de la app en Ajustes
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this,
                "Revisa TODOS los permisos. En Nubia debes ir a:\n" +
                "Ajustes → Apps → Dayana → Permisos",
                Toast.LENGTH_LONG
            ).show()
            return
        } catch (_: Exception) { }

        // Estrategia 2: Ajustes de apps generales
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Abre Ajustes → Apps → Dayana manualmente", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== GUÍAS NUBIA ====================

    /**
     * Muestra una guía específica para desbloquear permisos en Nubia/MyOS.
     */
    private fun showNubiaPermissionGuide(permType: String) {
        AlertDialog.Builder(this)
            .setTitle("Permiso bloqueado: $permType")
            .setMessage(
                "Tu Nubia bloquea automáticamente permisos sensibles.\n\n" +
                "Para permitir el acceso a $permType:\n\n" +
                "1. Ve a Ajustes → Apps → Dayana\n" +
                "2. Toca 'Permisos'\n" +
                "3. Busca '$permType'\n" +
                "4. Cambia a 'Permitir' (no 'Automático')\n\n" +
                "Si no ves la opción, ve a:\n" +
                "Ajustes → Seguridad → Gestor de permisos\n" +
                "y busca Dayana en la lista."
            )
            .setPositiveButton("Abrir Ajustes de App") { _, _ ->
                openNubiaPermissionSettings()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /**
     * Muestra la guía completa de permisos para dispositivos Nubia.
     */
    private fun showNubiaFullGuide() {
        AlertDialog.Builder(this)
            .setTitle("📖 Guía de Permisos para Nubia")
            .setMessage(
                "GUÍA COMPLETA PARA NUBIA NEO 3 5G\n" +
                "════════════════════════════════\n\n" +
                "PASO 1: ACCESIBILIDAD\n" +
                "• Ajustes → Accesibilidad\n" +
                "• Busca 'Servicios descargados' o 'Downloaded services'\n" +
                "• Toca 'Dayana - Visión de Pantalla'\n" +
                "• Activa el interruptor\n" +
                "• Confirma en el diálogo del sistema\n\n" +
                "PASO 2: NOTIFICACIONES\n" +
                "• Ajustes → Apps → Acceso especial a apps\n" +
                "• Toca 'Acceso a notificaciones'\n" +
                "• Busca 'Dayana - Notificaciones'\n" +
                "• Activa el interruptor\n" +
                "• Confirma en el diálogo del sistema\n\n" +
                "PASO 3: OVERLAY\n" +
                "• Ajustes → Apps → Acceso especial a apps\n" +
                "• Toca 'Mostrar sobre otras apps'\n" +
                "• Busca 'Dayana' y activa\n\n" +
                "PASO 4: SMS Y LLAMADAS\n" +
                "• Ajustes → Apps → Dayana → Permisos\n" +
                "• Para cada permiso (SMS, Teléfono, Contactos):\n" +
                "  - Cambia de 'Automático' a 'Permitir'\n" +
                "  - El sistema puede mostrar una advertencia, acepta\n\n" +
                "PASO 5: INICIO AUTOMÁTICO\n" +
                "• Ajustes → Apps → Dayana\n" +
                "• Busca 'Inicio automático' o 'Autostart'\n" +
                "• Activa para que Dayana funcione al reiniciar\n\n" +
                "PASO 6: BATERÍA\n" +
                "• Ajustes → Batería → Dayana\n" +
                "• Selecciona 'Sin restricciones'\n" +
                "• Esto evita que el sistema cierre Dayana\n\n" +
                "════════════════════════════════\n" +
                "Si un permiso no aparece en la lista,\n" +
                "reinicia el teléfono e intenta de nuevo."
            )
            .setPositiveButton("Entendido") { _, _ ->
                // Abrir accesibilidad como primer paso
                openAccessibilitySettings()
            }
            .setNeutralButton("Abrir Ajustes de App") { _, _ ->
                openNubiaPermissionSettings()
            }
            .show()
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
        if (!hasPermission(Manifest.permission.RECEIVE_SMS)) {
            neededPermissions.add(Manifest.permission.RECEIVE_SMS)
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
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            neededPermissions.add(Manifest.permission.WRITE_CALENDAR)
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

        // Multimedia (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (!hasPermission(Manifest.permission.READ_MEDIA_AUDIO)) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }

        // Body sensors
        if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
            neededPermissions.add(Manifest.permission.BODY_SENSORS)
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
            text = if (isAccessibilityOn) "✓ ACTIVADO" else "✗ NO ACTIVADO — Toca el botón para activar"
            setTextColor(if (isAccessibilityOn) COLOR_GREEN else COLOR_RED)
        }
        notificationStatus?.apply {
            text = if (isNotificationOn) "✓ ACTIVADO" else "✗ NO ACTIVADO — Toca el botón para activar"
            setTextColor(if (isNotificationOn) COLOR_GREEN else COLOR_RED)
        }
        overlayStatus?.apply {
            text = if (isOverlayOn) "✓ ACTIVADO" else "✗ NO ACTIVADO — Toca el botón para activar"
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
            if (isNubiaDevice) {
                appendLine("Modo: Nubia/MyOS (ajustes especiales)")
            }
            appendLine()
            if (allSpecial) {
                appendLine("=== TODOS LOS PERMISOS LISTOS ===")
                appendLine("Di \"Hey Dayana\" para interactuar")
            } else {
                appendLine("!!! FALTAN PERMISOS ESPECIALES !!!")
                if (isNubiaDevice) {
                    appendLine("En Nubia: usa el botón 📖 GUÍA")
                }
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
        // Verificar que los modelos Vosk existen antes de iniciar la escucha
        if (!verificarModelosVosk()) {
            Log.e(TAG, "Modelos Vosk no encontrados — no se pueden iniciar los servicios de voz")
            invocarDownloadManager()
            return
        }

        // Iniciar servicio de escucha (WakeWordService)
        if (!WakeWordService.isRunning()) {
            WakeWordService.start(this)
        }

        // Iniciar motor cognitivo en primer plano (CognitiveEngine)
        // Esto inicializa el orquestador que conecta PerceptionBus → AgentLoop
        CognitiveEngine.start(this)
    }

    /**
     * Verifica si el directorio del modelo completo de Vosk existe.
     * El modelo grande (vosk-model-es-0.42, ~1.3GB) es requerido para
     * la transcripción de comandos. Sin él, WakeWordService se iniciaría
     * pero fallaría al transcribir.
     *
     * @return true si el directorio del modelo existe, false si no
     */
    private fun verificarModelosVosk(): Boolean {
        val modelsDir = File(filesDir, "models")
        val fullModelDir = File(modelsDir, "vosk-model-es-0.42")

        if (!fullModelDir.exists()) {
            Log.w(TAG, "Modelo Vosk completo NO encontrado: ${fullModelDir.absolutePath}")
            return false
        }

        // Verificar que tenga al menos los archivos esenciales
        val amFile = File(fullModelDir, "am/final.mdl")
        val confFile = File(fullModelDir, "conf/model.conf")
        val hasEssentialFiles = amFile.exists() || confFile.exists()

        if (!hasEssentialFiles) {
            Log.w(TAG, "Directorio del modelo Vosk existe pero parece incompleto: ${fullModelDir.absolutePath}")
            return false
        }

        Log.i(TAG, "Modelo Vosk completo verificado: ${fullModelDir.absolutePath}")
        return true
    }

    /**
     * Stub del gestor de descarga de modelos Vosk.
     * Cuando el modelo de 1.3GB no está disponible en el dispositivo,
     * este método se invoca para prevenir un crash en WakeWordService.
     *
     * TODO: Implementar descarga real con DownloadManager o ForegroundService
     *       que descargue vosk-model-es-0.42 desde un mirror/hosting.
     */
    private fun invocarDownloadManager() {
        Log.w(TAG, "invocarDownloadManager() llamado — modelos Vosk faltantes")

        // Mostrar aviso al usuario
        Toast.makeText(
            this,
            "Se requiere descargar el modelo de voz (1.3 GB). " +
                    "Funcionalidad de descarga en desarrollo.",
            Toast.LENGTH_LONG
        ).show()

        // TODO: Implementar descarga con DownloadManager:
        // 1. Crear DownloadManager.Request con URL del modelo
        // 2. Descargar a filesDir/models/vosk-model-es-0.42.zip
        // 3. Descomprimir en filesDir/models/vosk-model-es-0.42/
        // 4. Al completar, re-intentar startPerceptionServices()
    }

    // ==================== VERIFICACIONES ====================

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // Método 1: Verificar vía Settings.Secure
        try {
            val serviceName = packageName + "/" +
                    ScreenObserver::class.java.canonicalName
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            if (enabledServices.contains(serviceName)) return true

            // Algunos dispositivos usan formato diferente
            val flatComponent = ComponentName(this, ScreenObserver::class.java).flattenToString()
            if (enabledServices.contains(flatComponent)) return true
        } catch (_: Exception) { }

        // Método 2: Verificar si la instancia del servicio está activa
        return ScreenObserver.isRunning()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        // Método 1: Verificar vía Settings.Secure
        try {
            val serviceName = packageName + "/" +
                    NotificationInterceptor::class.java.canonicalName
            val enabledListeners = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            if (enabledListeners.contains(serviceName)) return true

            // Algunos dispositivos usan formato diferente
            val flatComponent = ComponentName(this, NotificationInterceptor::class.java).flattenToString()
            if (enabledListeners.contains(flatComponent)) return true
        } catch (_: Exception) { }

        return false
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
