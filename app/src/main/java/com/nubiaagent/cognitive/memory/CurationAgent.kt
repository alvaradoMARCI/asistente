package com.nubiaagent.cognitive.memory

import android.content.Context
import android.util.Log
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.cognitive.memory.db.*
import kotlinx.coroutines.*

/**
 * CurationAgent: Agente de curación que procesa la memoria en segundo plano.
 *
 * Se activa cuando el dispositivo está en Bypass Charging (óptimo para
 * procesamiento intensivo sin degradar la batería) y ejecuta:
 *
 * TAREAS DE CURACIÓN:
 *
 * 1. PROCESAMIENTO DE CONVERSACIONES DEL DÍA
 *    - Lee las interacciones del día desde Room DB
 *    - Usa el LLM para extraer hechos nuevos, preferencias y patrones
 *    - Almacena los hechos extraídos en el Deep Archive
 *
 * 2. ACTUALIZACIÓN DEL LIVING PROFILE (~3,500 tokens)
 *    - Si se detectaron cambios significativos en preferencias o patrones
 *    - Reescribe el Living Profile consolidando información nueva
 *    - El nuevo perfil se guarda localmente y se puede sincronizar via git
 *
 * 3. DETECCIÓN DE PATRONES
 *    - Analiza cuándo el usuario usa ciertas apps
 *    - Detecta patrones de comunicación (contactos frecuentes, horarios)
 *    - Identifica preferencias recurrentes
 *
 * 4. LIMPIEZA DE MEMORIA
 *    - Elimina hechos contradictorios o obsoletos
 *    - Fusiona hechos duplicados
 *    - Compacta el índice vectorial
 *
 * 5. ACTUALIZACIÓN DE CONTACTOS
 *    - Actualiza métricas de contacto frecuente
 *    - Detecta contactos nuevos que merecen seguimiento
 *
 * ACTIVACIÓN:
 * - Automática cuando Bypass Charging está activo (detectado via PerceptionBus)
 * - Manual desde la UI del asistente
 * - Programada: cada noche a las 2:00 AM si el dispositivo está cargando
 */
class CurationAgent(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val cognitiveEngine: CognitiveEngine
) {
    companion object {
        private const val TAG = "NubiaAgent/Curator"
        private const val MIN_INTERACTIONS_TO_PROCESS = 3
        private const val PROFILE_UPDATE_THRESHOLD = 5  // Nuevos hechos necesarios para actualizar perfil
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isRunning = false
    private var lastCurationTime = 0L

    /**
     * Ejecuta el ciclo completo de curación.
     *
     * Llamado cuando:
     * 1. Bypass Charging se activa
     * 2. El usuario lo solicita manualmente
     * 3. Horario nocturno programado
     */
    suspend fun runCuration() {
        if (isRunning) {
            Log.d(TAG, "Curación ya en progreso, omitiendo")
            return
        }

        isRunning = true
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "★ Iniciando ciclo de curación")

        try {
            // Paso 1: Procesar conversaciones del día
            val newFacts = processDailyConversations()
            Log.i(TAG, "Hechos nuevos extraídos: ${newFacts.size}")

            // Paso 2: Detectar patrones
            val newPatterns = detectPatterns()
            Log.i(TAG, "Patrones detectados: ${newPatterns.size}")

            // Paso 3: Actualizar contactos frecuentes
            updateFrequentContacts()

            // Paso 4: Actualizar Living Profile si hay suficientes cambios
            if (newFacts.size >= PROFILE_UPDATE_THRESHOLD) {
                updateLivingProfile(newFacts, newPatterns)
            }

            // Paso 5: Limpiar memoria
            cleanupMemory()

            // Paso 6: Compactar índice vectorial
            compactVectorIndex()

            lastCurationTime = System.currentTimeMillis()
            val duration = lastCurationTime - startTime
            Log.i(TAG, "★ Curación completada en ${duration}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Error en ciclo de curación", e)
        } finally {
            isRunning = false
        }
    }

    /**
     * Paso 1: Procesa las conversaciones del día y extrae hechos nuevos.
     *
     * Usa el LLM para analizar las interacciones y extraer:
     * - Preferencias del usuario (gustos, disgustos)
     * - Hechos sobre el usuario (trabajo, familia, hobbies)
     * - Eventos importantes (reuniones, citas, viajes)
     * - Información de contactos (nombres, relaciones)
     *
     * El prompt de extracción es:
     * "Dada esta conversación, extrae hechos objetivos sobre el usuario
     *  en formato JSON: [{category, content, importance}]"
     */
    private suspend fun processDailyConversations(): List<ExtractedFact> {
        val allFacts = mutableListOf<ExtractedFact>()

        try {
            val now = System.currentTimeMillis()
            val oneDayAgo = now - (24 * 60 * 60 * 1000)

            val interactions = memoryManager.getInteractionDao().getSince(oneDayAgo)

            if (interactions.size < MIN_INTERACTIONS_TO_PROCESS) {
                Log.d(TAG, "Muy pocas interacciones para procesar (${interactions.size})")
                return emptyList()
            }

            // Procesar en lotes de 5 interacciones
            val batches = interactions.chunked(5)

            for (batch in batches) {
                val conversationText = batch.joinToString("\n") { interaction ->
                    "Usuario: ${interaction.userMessage}\nAsistente: ${interaction.assistantResponse}"
                }

                // Usar el LLM para extraer hechos
                val extractionPrompt = buildExtractionPrompt(conversationText)
                val llmResponse = cognitiveEngine.infer(
                    extractionPrompt,
                    com.nubiaagent.cognitive.engine.InferenceConfig.FAST
                )

                // Parsear hechos extraídos
                val facts = parseExtractedFacts(llmResponse)
                allFacts.addAll(facts)

                // Almacenar cada hecho en la memoria
                for (fact in facts) {
                    memoryManager.storeFact(
                        content = fact.content,
                        category = fact.category,
                        importance = fact.importance,
                        source = "curation"
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error procesando conversaciones", e)
        }

        return allFacts
    }

    /**
     * Construye el prompt para extraer hechos de una conversación.
     */
    private fun buildExtractionPrompt(conversationText: String): String {
        return """Analiza esta conversación y extrae hechos objetivos sobre el usuario.
Responde SOLO en formato JSON array, sin explicaciones adicionales.

Formato de cada hecho:
{
  "category": "preference|fact|event|contact|pattern",
  "content": "descripción concisa del hecho",
  "importance": 0.0 a 1.0
}

Categorías:
- preference: Gustos, disgustos, preferencias del usuario
- fact: Información factual (trabajo, familia, datos personales)
- event: Eventos pasados o futuros mencionados
- contact: Información sobre personas mencionadas
- pattern: Patrones de comportamiento observados

Conversación:
$conversationText

Hechos extraídos:"""
    }

    /**
     * Parsea la respuesta del LLM para extraer hechos.
     */
    private fun parseExtractedFacts(llmResponse: String): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()

        try {
            // Intentar encontrar JSON array en la respuesta
            val jsonStart = llmResponse.indexOf('[')
            val jsonEnd = llmResponse.lastIndexOf(']')

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = llmResponse.substring(jsonStart, jsonEnd + 1)
                val jsonArray = org.json.JSONArray(jsonString)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    facts.add(ExtractedFact(
                        category = obj.optString("category", "fact"),
                        content = obj.optString("content", ""),
                        importance = obj.optDouble("importance", 0.5).toFloat()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando hechos extraídos", e)
        }

        return facts.filter { it.content.isNotBlank() }
    }

    /**
     * Paso 2: Detecta patrones de comportamiento.
     */
    private suspend fun detectPatterns(): List<UserPattern> {
        val patterns = mutableListOf<UserPattern>()

        try {
            val interactions = memoryManager.getInteractionDao().getRecent(100)

            if (interactions.size < 10) return emptyList()

            // Detectar patrones temporales (horarios de uso)
            val hourCounts = mutableMapOf<Int, Int>()
            for (interaction in interactions) {
                val hour = java.util.Calendar.getInstance().apply {
                    timeInMillis = interaction.timestamp
                }.get(java.util.Calendar.HOUR_OF_DAY)
                hourCounts[hour] = (hourCounts[hour] ?: 0) + 1
            }

            // Identificar horas pico
            val peakHours = hourCounts.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }

            if (peakHours.isNotEmpty()) {
                val pattern = UserPattern(
                    patternType = "temporal",
                    description = "Horas de mayor actividad: ${peakHours.joinToString(", ")}h",
                    confidence = (hourCounts[peakHours.first()]?.toFloat() ?: 0f) / interactions.size,
                    observationCount = interactions.size
                )
                memoryManager.getPatternDao().insert(pattern)
                patterns.add(pattern)
            }

            // Detectar categorías de interacción más frecuentes
            val categoryCounts = interactions.groupingBy { it.category }.eachCount()
            val topCategory = categoryCounts.entries.maxByOrNull { it.value }
            if (topCategory != null && topCategory.value > 5) {
                val pattern = UserPattern(
                    patternType = "behavioral",
                    description = "Tipo de interacción más frecuente: ${topCategory.key} (${topCategory.value} veces)",
                    confidence = topCategory.value.toFloat() / interactions.size,
                    observationCount = topCategory.value
                )
                memoryManager.getPatternDao().insert(pattern)
                patterns.add(pattern)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error detectando patrones", e)
        }

        return patterns
    }

    /**
     * Paso 3: Actualiza contactos frecuentes.
     */
    private suspend fun updateFrequentContacts() {
        try {
            // Buscar nombres de personas en las interacciones recientes
            val interactions = memoryManager.getInteractionDao().getRecent(50)
            val namePattern = Regex("\\b[A-Z][a-z]{2,}\\b")  // Nombres propios simples

            val nameCounts = mutableMapOf<String, Int>()
            for (interaction in interactions) {
                val combinedText = "${interaction.userMessage} ${interaction.assistantResponse}"
                val names = namePattern.findAll(combinedText).map { it.value }.toList()
                for (name in names) {
                    nameCounts[name] = (nameCounts[name] ?: 0) + 1
                }
            }

            // Registrar contactos que aparecen más de 2 veces
            for ((name, count) in nameCounts) {
                if (count >= 2) {
                    val existing = memoryManager.getContactDao().searchByName(name)
                    if (existing.isNotEmpty()) {
                        // Actualizar existente
                        val contact = existing.first()
                        memoryManager.getContactDao().update(
                            contact.copy(
                                interactionCount = contact.interactionCount + count,
                                lastInteraction = System.currentTimeMillis()
                            )
                        )
                    } else {
                        // Crear nuevo
                        memoryManager.getContactDao().insert(
                            FrequentContact(
                                name = name,
                                apps = "[]",
                                interactionCount = count
                            )
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando contactos", e)
        }
    }

    /**
     * Paso 4: Actualiza el Living Profile con información consolidada.
     *
     * Usa el LLM para reescribir el Living Profile integrando
     * los nuevos hechos y patrones detectados.
     */
    private suspend fun updateLivingProfile(newFacts: List<ExtractedFact>, newPatterns: List<UserPattern>) {
        try {
            val currentProfile = memoryManager.getLivingProfile()

            val newInfo = buildString {
                append("NUEVOS HECHOS DESCUBIERTOS:\n")
                for (fact in newFacts) {
                    append("- [${fact.category}] ${fact.content} (importancia: ${fact.importance})\n")
                }
                append("\nNUEVOS PATRONES:\n")
                for (pattern in newPatterns) {
                    append("- ${pattern.description} (confianza: ${pattern.confidence})\n")
                }
            }

            val updatePrompt = """Eres un curador de memoria personal. Dado el Living Profile actual y nueva información descubierta, reescribe el Living Profile integrando los nuevos datos.

REGLAS:
1. Mantén el formato markdown del perfil
2. Integra información nueva en las secciones existentes
3. No elimines información existente a menos que sea contradictoria
4. Si hay contradicciones, prioriza la información más reciente
5. El perfil no debe exceder ~3,500 tokens
6. Sé conciso pero completo

LIVING PROFILE ACTUAL:
$currentProfile

NUEVA INFORMACIÓN:
$newInfo

LIVING PROFILE ACTUALIZADO:"""

            val updatedProfile = cognitiveEngine.infer(
                updatePrompt,
                com.nubiaagent.cognitive.engine.InferenceConfig.DEEP
            )

            memoryManager.updateLivingProfile(updatedProfile.trim())
            Log.i(TAG, "Living Profile actualizado con ${newFacts.size} hechos nuevos")

        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando Living Profile", e)
        }
    }

    /**
     * Paso 5: Limpia memoria (elimina duplicados y hechos obsoletos).
     */
    private suspend fun cleanupMemory() {
        try {
            // Eliminar interacciones muy antiguas (más de 30 días)
            val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
            val deleted = memoryManager.getInteractionDao().deleteOlderThan(thirtyDaysAgo)
            if (deleted > 0) {
                Log.d(TAG, "Interacciones antiguas eliminadas: $deleted")
            }

            // TODO: Detectar y fusionar hechos duplicados
            // TODO: Eliminar hechos que ya no son relevantes

        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando memoria", e)
        }
    }

    /**
     * Paso 6: Compacta el índice vectorial.
     */
    private fun compactVectorIndex() {
        // TODO: Implementar compactación del índice
        Log.d(TAG, "Compactación del índice vectorial - pendiente")
    }

    fun destroy() {
        scope.cancel()
    }
}

/**
 * Hecho extraído por el Curation Agent.
 */
data class ExtractedFact(
    val category: String,
    val content: String,
    val importance: Float
)
