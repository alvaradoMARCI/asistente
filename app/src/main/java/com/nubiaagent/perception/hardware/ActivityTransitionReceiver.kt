package com.nubiaagent.perception.hardware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nubiaagent.core.PerceptionBus
import com.nubiaagent.core.PerceptionEvent

/**
 * ActivityTransitionReceiver: Recibe transiciones de actividad física.
 *
 * Captura eventos del ActivityRecognitionClient de Google Play Services
 * cuando el usuario cambia de estado (quieto → caminando → en vehículo, etc.)
 * y los emite al PerceptionBus para que el agente pueda adaptar su
 * comportamiento según el contexto de movimiento del usuario.
 *
 * Optimizado para el Unisoc T8300: Las transiciones se procesan con
 * prioridad baja para no interferir con la inferencia del LLM.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NubiaAgent/ActivityTransition"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.nubiaagent.ACTION_ACTIVITY_TRANSITION") {
            val activityType = intent.getIntExtra("activity_type", -1)
            val transitionType = intent.getIntExtra("transition_type", -1)

            val activityName = when (activityType) {
                0 -> "IN_VEHICLE"
                1 -> "ON_BICYCLE"
                2 -> "ON_FOOT"
                3 -> "STILL"
                4 -> "UNKNOWN"
                5 -> "TILTING"
                7 -> "WALKING"
                8 -> "RUNNING"
                else -> "UNKNOWN($activityType)"
            }

            val transitionName = when (transitionType) {
                0 -> "ENTER"
                1 -> "EXIT"
                else -> "UNKNOWN($transitionType)"
            }

            Log.d(TAG, "Transición de actividad: $transitionName $activityName")

            // Emitir evento al PerceptionBus
            try {
                PerceptionBus.emit(
                    PerceptionEvent.HardwareStateUpdate(
                        batteryLevel = -1,
                        isCharging = false,
                        isBypassCharging = false,
                        latitude = null,
                        longitude = null,
                        currentActivity = when (activityName) {
                            "IN_VEHICLE" -> com.nubiaagent.core.UserActivity.DRIVING
                            "ON_BICYCLE" -> com.nubiaagent.core.UserActivity.CYCLING
                            "WALKING" -> com.nubiaagent.core.UserActivity.WALKING
                            "RUNNING" -> com.nubiaagent.core.UserActivity.RUNNING
                            "STILL" -> com.nubiaagent.core.UserActivity.STILL
                            else -> com.nubiaagent.core.UserActivity.UNKNOWN
                        },
                        stepCount = 0
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error emitiendo evento de actividad", e)
            }
        }
    }
}
