package com.nubiaagent.execution.skills.nubiacore

import android.content.Context
import android.util.Log
import com.nubiaagent.cognitive.memory.MemoryManager
import com.nubiaagent.cognitive.memory.db.Fact
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * UILearning: Sistema de aprendizaje de pistas UI para NubiaAgent.
 *
 * Aprende y recuerda cómo encontrar elementos específicos de la interfaz
 * en diferentes aplicaciones. Cada "pista" (hint) asocia una acción (como
 * "enviar_mensaje" o "buscar") con un elemento UI específico (xpath, bounds,
 * resource-id), permitiendo que el agente encuentre elementos rápidamente
 * sin tener que escanear toda la pantalla cada vez.
 *
 * ARQUITECTURA:
 * ```
 *  ┌───────────────────────────────────────────────────────────────────┐
 *  │                        UILearning                                 │
 *  │                                                                   │
 *  │  saveUIHint()      ──→ MemoryManager.storeFact(category="ui_hint")│
 *  │  findElement()     ──→ MemoryManager.getFactDao().search()       │
 *  │  updateHint*()     ──→ MemoryManager.getFactDao().update()       │
 *  │  learnFromScreen() ──→ Diff de elementos → aprendizaje automático│
 *  │                                                                   │
 *  │  Sistema de confianza:                                            │
 *  │  ┌────────────────────────────────────────────────────┐           │
 *  │  │ éxito  → confidence += 0.1, successCount++         │           │
 *  │  │ fallo  → confidence -= 0.15, failCount++           │           │
 *  │  │ confianza < 0.2 → pista degradada, probar fallback│           │
 *  │  │ confianza < 0.1 → eliminar pista automaticamente  │           │
 *  │  └────────────────────────────────────────────────────┘           │
 *  │                                                                   │
 *  │  Fallback de xpaths:                                              │
 *  │  Si el xpath principal falla, se prueban xpaths alternativos     │
 *  │  almacenados en el campo fallbackXpaths del hint.                 │
 *  └───────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ALMACENAMIENTO EN MEMORIA:
 * Los hints se almacenan como Facts en el MemoryManager con:
 * - category = "ui_hint"
 * - content = JSON con todos los datos del hint
 * - importance = confidence del hint (0.0 - 1.0)
 * - source = "ui_learning"
 *
 * FORMATO DEL JSON EN content:
 * ```json
 * {
 *   "appPackage": "com.whatsapp",
 *   "action": "enviar_mensaje",
 *   "elementHint": "Botón enviar",
 *   "xpath": "//node[@text='Enviar']",
 *   "bounds": "[960,1680][1080,1800]",
 *   "confidence": 0.9,
 *   "successCount": 8,
 *   "failCount": 1,
 *   "lastUpdated": 1703123456789,
 *   "fallbackXpaths": [
 *     "//node[@content-desc='Enviar']",
 *     "//node[@resource-id='com.whatsapp:id/send']"
 *   ]
 * }
 * ```
 *
 * @property context Contexto de la aplicación
 * @property memoryManager Sistema de memoria persistente del agente
 */
class UILearning(
    private val context: Context,
    private val memoryManager: MemoryManager
) {

    companion object {
        private const val TAG = "NubiaAgent/UILearning"

        /** Categoría de hechos para pistas UI. */
        private const val HINT_CATEGORY = "ui_hint"

        /** Fuente de los hechos de UI learning. */
        private const val HINT_SOURCE = "ui_learning"

        // ─── Umbrales de confianza ───

        /** Incremento de confianza tras éxito. */
        private const val CONFIDENCE_INCREMENT = 0.1f

        /** Decremento de confianza tras fallo. */
        private const val CONFIDENCE_DECREMENT = 0.15f

        /** Confianza máxima. */
        private const val CONFIDENCE_MAX = 1.0f

        /** Confianza mínima antes de degradar a fallback. */
        private const val CONFIDENCE_DEGRADED = 0.2f

        /** Confianza mínima antes de eliminar la pista. */
        private const val CONFIDENCE_REMOVE = 0.1f

        /** Confianza inicial para nuevas pistas. */
        private const val CONFIDENCE_INITIAL = 0.5f

        // ─── Campos JSON ───

        private const val KEY_APP_PACKAGE = "appPackage"
        private const val KEY_ACTION = "action"
        private const val KEY_ELEMENT_HINT = "elementHint"
        private const val KEY_XPATH = "xpath"
        private const val KEY_BOUNDS = "bounds"
        private const val KEY_CONFIDENCE = "confidence"
        private const val KEY_SUCCESS_COUNT = "successCount"
        private const val KEY_FAIL_COUNT = "failCount"
        private const val KEY_LAST_UPDATED = "lastUpdated"
        private const val KEY_FALLBACK_XPATHS = "fallbackXpaths"
        private const val KEY_FACT_ID = "factId"
    }

    // ─── Scope de corrutinas ───

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASS: UIHint
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Pista de UI para encontrar un elemento en una aplicación.
     *
     * @property appPackage Nombre del paquete de la aplicación
     * @property action Acción que se realiza con este elemento (e.g. "enviar_mensaje")
     * @property elementHint Descripción legible del elemento (e.g. "Botón enviar")
     * @property xpath XPath del elemento en el árbol de accesibilidad
     * @property bounds Coordenadas del elemento (e.g. "[960,1680][1080,1800]")
     * @property confidence Nivel de confianza de la pista (0.0 - 1.0)
     * @property successCount Número de veces que la pista funcionó correctamente
     * @property failCount Número de veces que la pista falló
     * @property lastUpdated Timestamp de la última actualización
     * @property factId ID del Fact en la base de datos (0 si no está almacenado)
     * @property fallbackXpaths XPaths alternativos para probar si el principal falla
     */
    data class UIHint(
        val appPackage: String,
        val action: String,
        var elementHint: String,
        var xpath: String,
        var bounds: String,
        var confidence: Float = CONFIDENCE_INITIAL,
        var successCount: Int = 0,
        var failCount: Int = 0,
        var lastUpdated: Long = System.currentTimeMillis(),
        var factId: Long = 0,
        val fallbackXpaths: MutableList<String> = mutableListOf()
    ) {
        /**
         * Calcula la tasa de éxito de esta pista.
         */
        val successRate: Float
            get() {
                val total = successCount + failCount
                return if (total > 0) successCount.toFloat() / total else 0f
            }

        /**
         * Determina si la pista es confiable (confidence >= UMBRAL_DEGRADADO).
         */
        val isReliable: Boolean
            get() = confidence >= CONFIDENCE_DEGRADED

        /**
         * Determina si la pista debería eliminarse.
         */
        val shouldRemove: Boolean
            get() = confidence < CONFIDENCE_REMOVE
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API PÚBLICA
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Guarda una pista de UI para encontrar un elemento.
     *
     * Si ya existe una pista para la misma combinación appPackage+action,
     * la actualiza en lugar de crear una duplicada. Si el xpath es diferente
     * al existente, lo añade como fallback.
     *
     * @param appPackage Nombre del paquete de la aplicación
     * @param action Acción asociada al elemento (e.g. "enviar_mensaje")
     * @param elementHint Descripción legible del elemento
     * @param xpath XPath del elemento en el árbol de accesibilidad
     * @param bounds Coordenadas del elemento
     */
    fun saveUIHint(
        appPackage: String,
        action: String,
        elementHint: String,
        xpath: String,
        bounds: String
    ) {
        scope.launch {
            try {
                // Buscar si ya existe una pista para esta app+acción
                val existingHint = findHintInMemory(appPackage, action)

                if (existingHint != null) {
                    // Actualizar pista existente
                    val oldXpath = existingHint.xpath

                    if (oldXpath != xpath) {
                        // El xpath cambió — la app actualizó su UI
                        // Mover el xpath viejo a fallback y usar el nuevo como principal
                        if (!existingHint.fallbackXpaths.contains(oldXpath)) {
                            existingHint.fallbackXpaths.add(oldXpath)
                        }
                        existingHint.xpath = xpath
                        Log.i(TAG, "UI actualizada en $appPackage/$action: xpath cambió, " +
                                "viejo movido a fallback")
                    }

                    existingHint.elementHint = elementHint
                    existingHint.bounds = bounds
                    existingHint.lastUpdated = System.currentTimeMillis()

                    // Guardar hint actualizado
                    updateHintInMemory(existingHint)
                    Log.d(TAG, "Pista UI actualizada: $appPackage/$action " +
                            "(confianza=${existingHint.confidence})")

                } else {
                    // Crear nueva pista
                    val newHint = UIHint(
                        appPackage = appPackage,
                        action = action,
                        elementHint = elementHint,
                        xpath = xpath,
                        bounds = bounds,
                        confidence = CONFIDENCE_INITIAL,
                        successCount = 0,
                        failCount = 0,
                        lastUpdated = System.currentTimeMillis(),
                        fallbackXpaths = mutableListOf()
                    )

                    saveHintToMemory(newHint)
                    Log.i(TAG, "Nueva pista UI guardada: $appPackage/$action → $elementHint")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error guardando pista UI para $appPackage/$action", e)
            }
        }
    }

    /**
     * Busca la pista de UI para encontrar un elemento específico.
     *
     * Busca en memoria la pista asociada a la combinación appPackage+action.
     * Si la pista principal tiene baja confianza, incluye los xpaths de fallback.
     *
     * @param appPackage Nombre del paquete de la aplicación
     * @param action Acción que se quiere realizar
     * @return Result con el UIHint encontrado, o null si no existe
     */
    fun findElement(appPackage: String, action: String): Result<UIHint?> {
        return try {
            // Búsqueda síncrona usando runBlocking en el scope
            val hint = runBlocking {
                findHintInMemory(appPackage, action)
            }

            if (hint != null) {
                if (hint.shouldRemove) {
                    Log.w(TAG, "Pista $appPackage/$action tiene confianza muy baja " +
                            "(${hint.confidence}) — eliminando")
                    runBlocking { removeHintFromMemory(hint) }
                    return Result.success(null)
                }

                Log.d(TAG, "Pista encontrada: $appPackage/$action " +
                        "(confianza=${hint.confidence}, éxito=${hint.successCount}, " +
                        "fallo=${hint.failCount})")
                Result.success(hint)
            } else {
                Log.d(TAG, "No se encontró pista para: $appPackage/$action")
                Result.success(null)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error buscando pista UI para $appPackage/$action", e)
            Result.failure(e)
        }
    }

    /**
     * Marca una pista como exitosa tras encontrar el elemento correctamente.
     *
     * Incrementa el contador de éxitos y aumenta la confianza de la pista.
     *
     * @param appPackage Nombre del paquete de la aplicación
     * @param action Acción que se realizó exitosamente
     */
    fun updateHintAfterSuccess(appPackage: String, action: String) {
        scope.launch {
            try {
                val hint = findHintInMemory(appPackage, action)
                if (hint != null) {
                    hint.successCount++
                    hint.confidence = (hint.confidence + CONFIDENCE_INCREMENT).coerceAtMost(CONFIDENCE_MAX)
                    hint.lastUpdated = System.currentTimeMillis()

                    updateHintInMemory(hint)
                    Log.d(TAG, "Pista exitosa: $appPackage/$action " +
                            "(confianza=${hint.confidence}, éxitos=${hint.successCount})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando pista tras éxito", e)
            }
        }
    }

    /**
     * Marca una pista como fallida tras no encontrar el elemento.
     *
     * Decrementa la confianza y aumenta el contador de fallos.
     * Si la confianza cae por debajo del umbral de eliminación,
     * la pista se elimina automáticamente.
     *
     * @param appPackage Nombre del paquete de la aplicación
     * @param action Acción que falló
     */
    fun updateHintAfterFailure(appPackage: String, action: String) {
        scope.launch {
            try {
                val hint = findHintInMemory(appPackage, action)
                if (hint != null) {
                    hint.failCount++
                    hint.confidence = (hint.confidence - CONFIDENCE_DECREMENT).coerceAtLeast(0f)
                    hint.lastUpdated = System.currentTimeMillis()

                    if (hint.shouldRemove) {
                        // Confianza demasiado baja — eliminar pista
                        removeHintFromMemory(hint)
                        Log.w(TAG, "Pista eliminada por baja confianza: " +
                                "$appPackage/$action (confianza=${hint.confidence})")
                    } else if (hint.confidence < CONFIDENCE_DEGRADED) {
                        // Pista degradada — probar fallbacks en el próximo intento
                        Log.w(TAG, "Pista degradada: $appPackage/$action " +
                                "(confianza=${hint.confidence}) — se usarán fallbacks")
                        updateHintInMemory(hint)
                    } else {
                        updateHintInMemory(hint)
                        Log.d(TAG, "Pista fallida: $appPackage/$action " +
                                "(confianza=${hint.confidence}, fallos=${hint.failCount})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error actualizando pista tras fallo", e)
            }
        }
    }

    /**
     * Aprende de un cambio de pantalla detectando qué cambió.
     *
     * Compara los elementos antes y después de una acción para detectar:
     * - Elementos nuevos que aparecieron (posibles resultados de la acción)
     * - Elementos que desaparecieron (posibles triggers de la acción)
     * - Cambios de estado en elementos existentes
     *
     * Los aprendizajes se almacenan como nuevas pistas o actualizan
     * las existentes.
     *
     * @param appPackage Nombre del paquete de la aplicación
     * @param beforeElements Lista de descripciones de elementos antes del cambio
     * @param afterElements Lista de descripciones de elementos después del cambio
     */
    fun learnFromScreenChange(
        appPackage: String,
        beforeElements: List<String>,
        afterElements: List<String>
    ) {
        scope.launch {
            try {
                // Detectar elementos nuevos (aparecieron después de la acción)
                val appeared = afterElements.filter { it !in beforeElements }
                // Detectar elementos que desaparecieron
                val disappeared = beforeElements.filter { it !in afterElements }
                // Elementos que persistieron (no cambiaron)
                val persisted = afterElements.filter { it in beforeElements }

                Log.d(TAG, "Cambio de pantalla en $appPackage: " +
                        "${appeared.size} aparecidos, ${disappeared.size} desaparecidos, " +
                        "${persisted.size} persistentes")

                // Aprender de elementos que aparecieron
                for (element in appeared) {
                    val parsed = parseElementDescription(element)
                    if (parsed != null) {
                        val (text, xpath, bounds) = parsed

                        // Inferir la acción del contexto del elemento
                        val action = inferActionFromElement(text, appPackage)

                        if (action.isNotBlank()) {
                            // Guardar como pista nueva o actualizar existente
                            val existing = findHintInMemory(appPackage, action)
                            if (existing == null) {
                                saveUIHint(appPackage, action, text, xpath, bounds)
                                Log.i(TAG, "Aprendido de cambio de pantalla: " +
                                        "$appPackage/$action → $text")
                            }
                        }
                    }
                }

                // Almacenar el patrón de cambio como hecho en memoria
                if (appeared.isNotEmpty() || disappeared.isNotEmpty()) {
                    val changeDescription = buildString {
                        append("Cambio UI en $appPackage: ")
                        if (appeared.isNotEmpty()) {
                            append("apareció [${appeared.joinToString(", ")}] ")
                        }
                        if (disappeared.isNotEmpty()) {
                            append("desapareció [${disappeared.joinToString(", ")}]")
                        }
                    }

                    memoryManager.storeFact(
                        content = changeDescription,
                        category = "ui_change",
                        importance = 0.3f,
                        source = HINT_SOURCE
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error aprendiendo de cambio de pantalla", e)
            }
        }
    }

    /**
     * Obtiene todas las pistas de UI para una aplicación específica.
     *
     * @param appPackage Nombre del paquete de la aplicación
     * @return Lista de UIHints para la aplicación, ordenados por confianza
     */
    fun getAppHints(appPackage: String): List<UIHint> {
        return try {
            runBlocking {
                val facts = memoryManager.getFactDao().getByCategory(HINT_CATEGORY)
                facts.filter { fact ->
                    try {
                        val json = JSONObject(fact.content)
                        json.optString(KEY_APP_PACKAGE) == appPackage
                    } catch (e: Exception) {
                        false
                    }
                }.mapNotNull { fact ->
                    parseHintFromFact(fact)
                }.sortedByDescending { it.confidence }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo pistas para $appPackage", e)
            emptyList()
        }
    }

    /**
     * Limpia recursos del sistema de aprendizaje.
     */
    fun destroy() {
        scope.cancel()
        Log.i(TAG, "UILearning destruido")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MÉTODOS PRIVADOS — ALMACENAMIENTO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Busca una pista en la memoria por appPackage y action.
     *
     * @param appPackage Nombre del paquete
     * @param action Acción a buscar
     * @return UIHint si se encuentra, null si no
     */
    private suspend fun findHintInMemory(appPackage: String, action: String): UIHint? {
        return try {
            // Buscar en hechos de categoría ui_hint
            val facts = memoryManager.getFactDao().getByCategory(HINT_CATEGORY)

            for (fact in facts) {
                try {
                    val json = JSONObject(fact.content)
                    if (json.optString(KEY_APP_PACKAGE) == appPackage &&
                        json.optString(KEY_ACTION) == action
                    ) {
                        return parseHintFromFact(fact)
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            // Fallback: buscar por texto
            val searchResults = memoryManager.getFactDao().search(
                "$appPackage:$action", 5
            )

            for (fact in searchResults) {
                if (fact.category == HINT_CATEGORY) {
                    try {
                        val json = JSONObject(fact.content)
                        if (json.optString(KEY_APP_PACKAGE) == appPackage &&
                            json.optString(KEY_ACTION) == action
                        ) {
                            return parseHintFromFact(fact)
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }

            null

        } catch (e: Exception) {
            Log.e(TAG, "Error buscando pista en memoria", e)
            null
        }
    }

    /**
     * Guarda un UIHint como Fact en la memoria.
     *
     * @param hint Pista a guardar
     */
    private suspend fun saveHintToMemory(hint: UIHint) {
        val json = hintToJson(hint)

        memoryManager.storeFact(
            content = json.toString(),
            category = HINT_CATEGORY,
            importance = hint.confidence,
            source = HINT_SOURCE
        )
    }

    /**
     * Actualiza un UIHint existente en la memoria.
     *
     * @param hint Pista con datos actualizados
     */
    private suspend fun updateHintInMemory(hint: UIHint) {
        try {
            if (hint.factId > 0) {
                val json = hintToJson(hint)
                val fact = Fact(
                    id = hint.factId,
                    content = json.toString(),
                    category = HINT_CATEGORY,
                    importance = hint.confidence,
                    source = HINT_SOURCE,
                    lastAccessed = System.currentTimeMillis(),
                    accessCount = hint.successCount + hint.failCount
                )
                memoryManager.getFactDao().update(fact)
            } else {
                // No tenemos el factId, buscar y actualizar
                val existing = findHintInMemory(hint.appPackage, hint.action)
                if (existing != null && existing.factId > 0) {
                    hint.factId = existing.factId
                    updateHintInMemory(hint)
                } else {
                    // Guardar como nuevo
                    saveHintToMemory(hint)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando pista en memoria", e)
            // Fallback: guardar como nueva
            saveHintToMemory(hint)
        }
    }

    /**
     * Elimina un UIHint de la memoria.
     *
     * @param hint Pista a eliminar
     */
    private suspend fun removeHintFromMemory(hint: UIHint) {
        try {
            if (hint.factId > 0) {
                memoryManager.getFactDao().deleteById(hint.factId)
                Log.d(TAG, "Pista eliminada: ${hint.appPackage}/${hint.action}")
            } else {
                // Buscar por contenido y eliminar
                val facts = memoryManager.getFactDao().getByCategory(HINT_CATEGORY)
                for (fact in facts) {
                    try {
                        val json = JSONObject(fact.content)
                        if (json.optString(KEY_APP_PACKAGE) == hint.appPackage &&
                            json.optString(KEY_ACTION) == hint.action
                        ) {
                            memoryManager.getFactDao().deleteById(fact.id)
                            Log.d(TAG, "Pista eliminada por búsqueda: ${hint.appPackage}/${hint.action}")
                            break
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando pista de memoria", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MÉTODOS PRIVADOS — SERIALIZACIÓN
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convierte un UIHint a JSONObject para almacenamiento.
     */
    private fun hintToJson(hint: UIHint): JSONObject {
        return JSONObject().apply {
            put(KEY_APP_PACKAGE, hint.appPackage)
            put(KEY_ACTION, hint.action)
            put(KEY_ELEMENT_HINT, hint.elementHint)
            put(KEY_XPATH, hint.xpath)
            put(KEY_BOUNDS, hint.bounds)
            put(KEY_CONFIDENCE, hint.confidence)
            put(KEY_SUCCESS_COUNT, hint.successCount)
            put(KEY_FAIL_COUNT, hint.failCount)
            put(KEY_LAST_UPDATED, hint.lastUpdated)
            put(KEY_FACT_ID, hint.factId)

            // Fallback xpaths como JSONArray
            val fallbackArray = JSONArray()
            hint.fallbackXpaths.forEach { fallbackArray.put(it) }
            put(KEY_FALLBACK_XPATHS, fallbackArray)
        }
    }

    /**
     * Parsea un Fact de la base de datos a un UIHint.
     *
     * @param fact Fact con contenido JSON del hint
     * @return UIHint parseado, o null si el formato es inválido
     */
    private fun parseHintFromFact(fact: Fact): UIHint? {
        return try {
            val json = JSONObject(fact.content)

            val fallbackXpaths = mutableListOf<String>()
            val fallbackArray = json.optJSONArray(KEY_FALLBACK_XPATHS)
            if (fallbackArray != null) {
                for (i in 0 until fallbackArray.length()) {
                    fallbackArray.optString(i)?.let { fallbackXpaths.add(it) }
                }
            }

            UIHint(
                appPackage = json.optString(KEY_APP_PACKAGE, ""),
                action = json.optString(KEY_ACTION, ""),
                elementHint = json.optString(KEY_ELEMENT_HINT, ""),
                xpath = json.optString(KEY_XPATH, ""),
                bounds = json.optString(KEY_BOUNDS, ""),
                confidence = json.optDouble(KEY_CONFIDENCE, CONFIDENCE_INITIAL.toDouble()).toFloat(),
                successCount = json.optInt(KEY_SUCCESS_COUNT, 0),
                failCount = json.optInt(KEY_FAIL_COUNT, 0),
                lastUpdated = json.optLong(KEY_LAST_UPDATED, System.currentTimeMillis()),
                factId = json.optLong(KEY_FACT_ID, fact.id),
                fallbackXpaths = fallbackXpaths
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando hint desde Fact id=${fact.id}", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MÉTODOS PRIVADOS — ANÁLISIS DE ELEMENTOS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Parsea la descripción de un elemento de accesibilidad.
     *
     * Formato esperado: "texto|xpath|bounds"
     * Ejemplo: "Enviar|//node[@text='Enviar']|[960,1680][1080,1800]"
     *
     * @param description Descripción del elemento
     * @return Triple (text, xpath, bounds) o null si no se puede parsear
     */
    private fun parseElementDescription(description: String): Triple<String, String, String>? {
        val parts = description.split("|")
        return when {
            parts.size >= 3 -> Triple(parts[0], parts[1], parts[2])
            parts.size == 2 -> Triple(parts[0], parts[1], "")
            parts.size == 1 -> Triple(parts[0], "", "")
            else -> null
        }
    }

    /**
     * Infiere la acción que se puede realizar con un elemento UI
     * basándose en su texto y el contexto de la aplicación.
     *
     * @param elementText Texto visible del elemento
     * @param appPackage Nombre del paquete de la aplicación
     * @return Nombre de la acción inferida, o cadena vacía si no se puede inferir
     */
    private fun inferActionFromElement(elementText: String, appPackage: String): String {
        val text = elementText.lowercase()

        // Acciones genéricas basadas en texto del elemento
        return when {
            // Acciones de envío
            text.contains("enviar") || text.contains("mandar") -> "enviar"
            text.contains("send") -> "enviar"

            // Acciones de búsqueda
            text.contains("buscar") || text.contains("search") -> "buscar"

            // Acciones de edición
            text.contains("editar") || text.contains("edit") -> "editar"
            text.contains("escribir") || text.contains("write") -> "escribir"

            // Acciones de navegación
            text.contains("siguiente") || text.contains("next") -> "siguiente"
            text.contains("anterior") || text.contains("back") -> "anterior"
            text.contains("inicio") || text.contains("home") -> "inicio"

            // Acciones de confirmación
            text.contains("aceptar") || text.contains("ok") ||
            text.contains("confirmar") || text.contains("confirm") -> "confirmar"

            // Acciones de cancelación
            text.contains("cancelar") || text.contains("cancel") -> "cancelar"

            // Acciones de eliminación
            text.contains("eliminar") || text.contains("borrar") ||
            text.contains("delete") -> "eliminar"

            // Acciones de guardado
            text.contains("guardar") || text.contains("save") -> "guardar"

            // Acciones de llamada
            text.contains("llamar") || text.contains("call") -> "llamar"

            // Acciones de adjuntar
            text.contains("adjuntar") || text.contains("attach") -> "adjuntar"

            // Acciones de compartir
            text.contains("compartir") || text.contains("share") -> "compartir"

            // Acciones de configuración
            text.contains("ajustes") || text.contains("configuración") ||
            text.contains("settings") -> "ajustes"

            // Acciones de WhatsApp específicas
            appPackage.contains("whatsapp") && text.contains("cámara") -> "abrir_camara"
            appPackage.contains("whatsapp") && text.contains("emoji") -> "abrir_emoji"

            // Acciones de teléfono específicas
            appPackage.contains("phone") || appPackage.contains("dialer") -> "marcar"

            // No se puede inferir
            else -> ""
        }
    }
}
