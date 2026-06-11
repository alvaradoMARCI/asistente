package com.nubiaagent

import android.app.Application
import android.util.Log
import com.nubiaagent.perception.hardware.HardwareStateCollector

/**
 * DayanaApp: Application class principal.
 *
 * Inicializa los componentes globales de la Capa de Percepción:
 * - HardwareStateCollector: Telemetría del dispositivo
 * - PerceptionBus: Bus de eventos central
 *
 * NOTA: Los servicios de Ear, Vision y Events se inician
 * bajo demanda desde la UI o por el BootReceiver.
 */
class NubiaAgentApp : Application() {

    companion object {
        private const val TAG = "Dayana/App"
        lateinit var instance: NubiaAgentApp
            private set
    }

    // Componentes globales
    lateinit var hardwareCollector: HardwareStateCollector
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "Dayana iniciando en ${android.os.Build.MODEL} " +
                "(${android.os.Build.MANUFACTURER})")

        // Iniciar colector de hardware (siempre activo)
        hardwareCollector = HardwareStateCollector(this)
        hardwareCollector.start()

        Log.i(TAG, "Capa de Percepción inicializada")
    }
}
