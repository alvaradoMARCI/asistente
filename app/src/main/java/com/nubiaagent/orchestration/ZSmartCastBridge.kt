package com.nubiaagent.orchestration

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.os.Build
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ZSmartCastBridge: Proyección del Canvas a pantalla externa via Z-SmartCast.
 *
 * El Nubia Neo 3 5G incluye Z-SmartCast, la tecnología de proyección
 * inalámbrica de ZTE que permite duplicar o extender la pantalla
 * del dispositivo a una TV, monitor o proyector compatible.
 *
 * FUNCIONALIDAD:
 * - Proyectar el Canvas del agente a pantalla externa
 * - Modo duplicado: lo que se ve en el teléfono se ve en la TV
 * - Modo extendido: Canvas en TV, controles en teléfono
 * - Detección automática de displays disponibles
 * - Control de la proyección desde la burbuja flotante
 *
 * CASOS DE USO:
 * - "Proyecta mi briefing en la TV"
 * - "Muestra el código en el monitor"
 * - "Presenta el People Graph en pantalla grande"
 *
 * ARQUITECTURA:
 *
 * ```
 * ZSmartCastBridge
 *     │
 *     ├── MediaRouter (Android nativo)
 *     │   ├── Detectar displays externos
 *     │   ├── Seleccionar ruta de salida
 *     │   └── Callback de conexión/desconexión
 *     │
 *     ├── DisplayManager (Android nativo)
 *     │   ├── Obtener displays disponibles
 *     │   ├── Crear Presentation para display externo
 *     │   └── Gestionar ciclo de vida de la proyección
 *     │
 *     └── Z-SmartCast (ZTE específico)
 *         ├── Intent: com.zte.smartcast.LAUNCH
 *         ├── Detección de Miracast / DLNA
 *         └── Configuración de proyección
 * ```
 */
class ZSmartCastBridge(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/SmartCast"

        // Intents de Z-SmartCast
        private const val ZTE_SMARTCAST_LAUNCH = "com.zte.smartcast.LAUNCH"
        private const val ZTE_SMARTCAST_SETTINGS = "com.zte.smartcast.SETTINGS"

        // Tipos de proyección
        const val MODE_MIRROR = "mirror"      // Duplicar pantalla
        const val MODE_EXTEND = "extend"      // Pantalla extendida
    }

    private var mediaRouter: MediaRouter? = null
    private var isProjecting = false
    private var projectionMode = MODE_MIRROR

    /**
     * Inicializa el bridge de Z-SmartCast.
     */
    fun initialize() {
        try {
            mediaRouter = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter

            // Registrar callback para detectar displays
            mediaRouter?.addCallback(
                MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
                object : MediaRouter.Callback() {
                    override fun onRouteAdded(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
                        Log.i(TAG, "Display externo detectado: ${info?.name}")
                    }

                    override fun onRouteRemoved(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
                        Log.i(TAG, "Display externo desconectado: ${info?.name}")
                        if (isProjecting) {
                            stopProjection()
                        }
                    }

                    override fun onRouteSelected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) {
                        Log.i(TAG, "Ruta seleccionada: ${info?.name}")
                    }

                    override fun onRouteUnselected(router: MediaRouter?, type: Int, info: MediaRouter.RouteInfo?) {
                        Log.i(TAG, "Ruta deseleccionada: ${info?.name}")
                        if (isProjecting) {
                            isProjecting = false
                        }
                    }
                }
            )

            Log.i(TAG, "Z-SmartCast Bridge inicializado")

        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando Z-SmartCast Bridge", e)
        }
    }

    /**
     * Inicia la proyección del Canvas a pantalla externa.
     *
     * @param mode Modo de proyección (mirror o extend)
     */
    suspend fun startProjection(mode: String = MODE_MIRROR): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                if (isProjecting) {
                    Log.w(TAG, "Proyección ya en curso")
                    return@withContext true
                }

                projectionMode = mode

                // Intentar lanzar Z-SmartCast nativo
                val smartCastIntent = Intent(ZTE_SMARTCAST_LAUNCH).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("mode", mode)
                    putExtra("source", "com.nubiaagent")
                }

                if (smartCastIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(smartCastIntent)
                    isProjecting = true
                    Log.i(TAG, "Z-SmartCast iniciado en modo: $mode")
                    return@withContext true
                }

                // Fallback: usar MediaRouter nativo de Android
                val selectedRoute = mediaRouter?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
                if (selectedRoute != null && selectedRoute.name != "Phone") {
                    selectedRoute.select()
                    isProjecting = true
                    Log.i(TAG, "Proyección via MediaRouter: ${selectedRoute.name}")
                    return@withContext true
                }

                // Último recurso: abrir settings de proyección
                val settingsIntent = Intent(android.provider.Settings.ACTION_CAST_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (settingsIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(settingsIntent)
                    Log.i(TAG, "Abriendo settings de proyección (no se encontró display externo)")
                }

                false

            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando proyección", e)
                false
            }
        }
    }

    /**
     * Detiene la proyección actual.
     */
    fun stopProjection() {
        try {
            if (!isProjecting) return

            // Desconectar via MediaRouter: seleccionar la ruta por defecto
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRouter?.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
            } else {
                val defaultRoute = mediaRouter?.getDefaultRoute()
                defaultRoute?.select()
            }

            isProjecting = false
            Log.i(TAG, "Proyección detenida")

        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo proyección", e)
        }
    }

    /**
     * Lista los displays externos disponibles.
     */
    fun listAvailableDisplays(): List<DisplayInfo> {
        val displays = mutableListOf<DisplayInfo>()

        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val externalDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

            for (display in externalDisplays) {
                displays.add(DisplayInfo(
                    id = display.displayId,
                    name = display.name,
                    width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) display.mode?.physicalWidth ?: 0 else 0,
                    height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) display.mode?.physicalHeight ?: 0 else 0,
                    isAvailable = display.state == Display.STATE_ON
                ))
            }

            // También verificar rutas del MediaRouter
            val router = mediaRouter
            if (router != null) {
                val routeCount = router.routeCount
                for (i in 0 until routeCount) {
                    val route = router.getRouteAt(i)
                    if (route.name != "Phone" && route.isEnabled) {
                        displays.add(DisplayInfo(
                            id = -1,
                            name = route.name.toString(),
                            width = 0,
                            height = 0,
                            isAvailable = true
                        ))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error listando displays", e)
        }

        return displays
    }

    /**
     * Verifica si hay un display externo conectado.
     */
    fun hasExternalDisplay(): Boolean {
        return listAvailableDisplays().isNotEmpty()
    }

    fun isProjecting(): Boolean = isProjecting

    fun destroy() {
        stopProjection()
        mediaRouter = null
    }
}

/**
 * Información de un display externo.
 */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isAvailable: Boolean
)
