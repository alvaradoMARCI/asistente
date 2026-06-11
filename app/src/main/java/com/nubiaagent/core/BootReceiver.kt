package com.nubiaagent.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nubiaagent.perception.ear.WakeWordService

/**
 * BootReceiver: Inicia los servicios de percepción al encender el dispositivo.
 *
 * Inicia automáticamente:
 * 1. WakeWordService - Para escuchar "Hey Nubia" desde el arranque
 * 2. HardwareStateCollector - Para telemetría desde el inicio
 *
 * NOTA: NotificationInterceptor y ScreenObserver se inician automáticamente
 * por el sistema Android al habilitarlos en Configuración.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NubiaAgent/Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Boot detectado, iniciando servicios de percepción")

                // Iniciar servicio de escucha
                WakeWordService.start(context)

                // El HardwareStateCollector se inicia desde NubiaAgentApp
            }
        }
    }
}
