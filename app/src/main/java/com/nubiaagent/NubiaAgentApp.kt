package com.nubiaagent

import android.app.Application
import android.util.Log
import com.nubiaagent.cognitive.persona.PersonaManager
import com.nubiaagent.cognitive.voice.VoiceEngine
import com.nubiaagent.perception.hardware.HardwareStateCollector

/**
 * DayanaApp: Application class principal.
 *
 * Inicializa los componentes globales:
 * - PersonaManager: Sistema de 6 personas con cambio en caliente
 * - VoiceEngine: Motor de voz (Android TTS + OpenAI + ElevenLabs)
 * - HardwareStateCollector: Telemetría del dispositivo
 * - PerceptionBus: Bus de eventos central
 *
 * CICLO DE VIDA:
 * - onCreate(): Inicializa todos los componentes
 * - onTerminate(): Libera recursos de VoiceEngine
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

    // Sistema de personas
    lateinit var personaManager: PersonaManager
        private set

    // Motor de voz
    lateinit var voiceEngine: VoiceEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "Dayana iniciando en ${android.os.Build.MODEL} " +
                "(${android.os.Build.MANUFACTURER})")

        // Iniciar colector de hardware (siempre activo)
        hardwareCollector = HardwareStateCollector(this)
        hardwareCollector.start()

        // Inicializar sistema de personas
        personaManager = PersonaManager(this)
        personaManager.initialize()

        // Inicializar motor de voz (Android TTS + cloud providers)
        voiceEngine = VoiceEngine(this, personaManager)
        voiceEngine.initialize()

        Log.i(TAG, "Dayana lista — Persona: ${personaManager.activePersona.value.displayName}, " +
                "Voz: ${voiceEngine.voiceMode.value}")
    }

    override fun onTerminate() {
        super.onTerminate()

        // Liberar recursos del motor de voz
        try {
            voiceEngine.destroy()
            Log.i(TAG, "VoiceEngine destruido correctamente")
        } catch (e: Exception) {
            Log.w(TAG, "Error destruyendo VoiceEngine", e)
        }

        Log.i(TAG, "Dayana terminada")
    }
}
