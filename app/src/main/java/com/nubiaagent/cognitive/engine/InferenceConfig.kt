package com.nubiaagent.cognitive.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * InferenceConfig: Configuración de inferencia para el motor local.
 *
 * Optimizado para el Unisoc T8300 con 20GB de RAM dinámica.
 * Los parámetros se ajustan según el modelo cargado y el estado
 * del hardware (bypass charging, batería, etc.)
 *
 * ESTRATEGIA DE MEMORIA:
 * - Modelos Q4_K_M (~2GB): Carga completa en RAM, inferencia rápida
 * - Modelos Q5_K_M (~3GB): Carga completa, buen balance calidad/velocidad
 * - Modelos Q8_0 (~5GB): Carga completa si hay >12GB libres
 * - El modelo se mantiene en RAM mientras el servicio está activo
 * - Se descarga solo cuando el sistema reclama memoria o el usuario lo solicita
 */
data class InferenceConfig(
    /** Número de hilos para inferencia. T8300 tiene 8 cores (2xA76 + 6xA55).
     *  Usamos 4 hilos para dejar cores libres para la Capa de Percepción. */
    val nThreads: Int = 4,

    /** Tamaño del contexto en tokens. Más contexto = más RAM pero mejor comprensión.
     *  Con 20GB de RAM, podemos permitirnos 4096 tokens de contexto. */
    val contextSize: Int = 4096,

    /** Batch size para procesamiento por lotes. */
    val batchSize: Int = 512,

    /** Temperatura para generación. 0.7 = balance creatividad/coherencia. */
    val temperature: Float = 0.7f,

    /** Top-p (nucleus sampling). */
    val topP: Float = 0.9f,

    /** Top-k para sampling. */
    val topK: Int = 40,

    /** Penalización por repetición. */
    val repeatPenalty: Float = 1.1f,

    /** Máximo de tokens a generar por respuesta. */
    val maxTokens: Int = 1024,

    /** Si usar mmap para cargar el modelo (más eficiente en RAM). */
    val useMmap: Boolean = true,

    /** Si usar mlock para evitar que el OS swappee el modelo. */
    val useMlock: Boolean = true,

    /** Si mantener el modelo en caché entre inferencias. */
    val keepModelLoaded: Boolean = true,

    /** GPU layers a offload a NPU (0 = solo CPU por ahora).
     *  El T8300 no tiene GPU/NPU compatible con llama.cpp directamente,
     *  pero se puede usar el motor NeoTurbo para operaciones específicas. */
    val gpuLayers: Int = 0
) {
    /**
     * Ajusta la configuración según el estado del hardware.
     * Si la batería está baja, reduce threads y contexto.
     * Si bypass charging está activo, puede usar máxima potencia.
     */
    fun adaptToHardware(batteryLevel: Int, isBypassCharging: Boolean, freeRamMb: Long): InferenceConfig {
        return when {
            isBypassCharging -> {
                // Máximo rendimiento: bypass charging = sin límite de batería
                copy(
                    nThreads = 6,
                    contextSize = if (freeRamMb > 8000) 8192 else 4096,
                    batchSize = 512,
                    useMlock = true
                )
            }
            batteryLevel < 15 -> {
                // Ahorro extremo
                copy(
                    nThreads = 2,
                    contextSize = 2048,
                    batchSize = 256,
                    useMlock = false
                )
            }
            batteryLevel < 30 -> {
                // Ahorro moderado
                copy(
                    nThreads = 3,
                    contextSize = 3072,
                    batchSize = 256
                )
            }
            else -> {
                // Normal
                copy(
                    nThreads = 4,
                    contextSize = 4096,
                    batchSize = 512
                )
            }
        }
    }

    companion object {
        /** Perfil de inferencia rápido (menos calidad, más velocidad). */
        val FAST = InferenceConfig(
            nThreads = 4,
            contextSize = 2048,
            temperature = 0.5f,
            maxTokens = 256,
            topP = 0.8f,
            repeatPenalty = 1.05f
        )

        /** Perfil balanceado (default). */
        val BALANCED = InferenceConfig()

        /** Perfil profundo (más calidad, más lento). */
        val DEEP = InferenceConfig(
            nThreads = 6,
            contextSize = 8192,
            temperature = 0.8f,
            maxTokens = 2048,
            topP = 0.95f
        )
    }
}
