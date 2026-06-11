package com.nubiaagent.cognitive.memory

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.nubiaagent.cognitive.memory.db.*
import com.nubiaagent.cognitive.identity.IdentityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * MemoryManager: Sistema de Memoria Persistente de 3 capas para NubiaAgent.
 *
 * ARQUITECTURA DE 3 CAPAS (basada en OperatorBot):
 *
 * ┌──────────────────────────────────────────────────────────┐
 * │  CAPA 1: LIVING PROFILE (~3,500 tokens)                 │
 * │  Resumen dinámico: metas, patrones, preferencias        │
 * │  Se reescribe tras conversaciones importantes            │
 * │  Almacenamiento: Archivo local + git sync               │
 * │  Latencia: <1ms (en memoria siempre)                    │
 * ├──────────────────────────────────────────────────────────┤
 * │  CAPA 2: ROLLING CONTEXT (últimas 20 interacciones)     │
 * │  Base de datos Room (SQLite) local                      │
 * │  Para respuesta inmediata con contexto reciente          │
 * │  Latencia: <10ms (SQLite en-device)                     │
 * ├──────────────────────────────────────────────────────────┤
 * │  CAPA 3: DEEP ARCHIVE (vectorial)                       │
 * │  Búsqueda semántica en todo el historial                 │
 * │  LanceDB local para embeddings y similitud coseno       │
 * │  Latencia: <100ms (búsqueda vectorial local)            │
 * └──────────────────────────────────────────────────────────┘
 *
 * FLUJO DE CONSULTA:
 *
 * 1. Consulta llega → MemoryManager.recallRelevant(query)
 * 2. Buscar en Living Profile (Capa 1) - si hay match directo, responder
 * 3. Buscar en Rolling Context (Capa 2) - interacciones recientes
 * 4. Buscar en Deep Archive (Capa 3) - búsqueda semántica profunda
 * 5. Consolidar resultados y devolver contexto relevante
 *
 * FLUJO DE ALMACENAMIENTO:
 *
 * 1. Nueva interacción → Store en Capa 2 (Room DB)
 * 2. Hechos extraídos → Store en Capa 3 (Facts + embedding)
 * 3. Tras conversación importante → Actualizar Capa 1 (Living Profile)
 * 4. Curation Agent (background) → Procesar y actualizar las 3 capas
 *
 * RESTRICCIÓN DE PRIVACIDAD:
 * - Todas las capas de memoria son 100% locales
 * - Los embeddings se calculan en-device
 * - Los datos NUNCA se sincronizan con la nube
 * - El único sync es git push del Living Profile (texto, no embeddings)
 *   y solo cuando el usuario lo autoriza explícitamente
 */
class MemoryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/Memory"
        private const val DB_NAME = "nubia_agent_memory"

        @Volatile
        private var instance: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return instance ?: synchronized(this) {
                instance ?: MemoryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Base de datos Room
    private lateinit var database: NubiaDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Componentes de memoria
    private var identityManager: IdentityManager? = null
    private var deepArchive: DeepArchive? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * Inicializa todas las capas de memoria.
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                // Inicializar Room Database
                database = Room.databaseBuilder(
                    context,
                    NubiaDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()

                // Inicializar Deep Archive
                deepArchive = DeepArchive(context)

                Log.i(TAG, "Sistema de memoria inicializado (3 capas)")
                _isInitialized.value = true

            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando memoria", e)
            }
        }
    }

    // ==================== CAPA 1: LIVING PROFILE ====================

    /**
     * Obtiene el Living Profile actual (Capa 1).
     * Siempre en memoria, latencia <1ms.
     */
    fun getLivingProfile(): String {
        return identityManager?.getLivingProfile() ?: ""
    }

    /**
     * Actualiza el Living Profile con nueva información.
     * Llamado por el Curation Agent después de conversaciones importantes.
     */
    suspend fun updateLivingProfile(newProfile: String) {
        identityManager?.updateLivingProfile(newProfile)
        Log.i(TAG, "Living Profile actualizado")
    }

    // ==================== CAPA 2: ROLLING CONTEXT ====================

    /**
     * Almacena una interacción en el Rolling Context (Capa 2).
     * Mantiene las últimas 100 interacciones (20 para contexto inmediato).
     */
    suspend fun storeInteraction(
        userMessage: String,
        assistantResponse: String,
        toolsUsed: List<String> = emptyList()
    ) {
        withContext(Dispatchers.IO) {
            try {
                val category = classifyInteraction(userMessage, assistantResponse)
                val interaction = Interaction(
                    userMessage = userMessage,
                    assistantResponse = assistantResponse,
                    toolsUsed = JSONArray(toolsUsed).toString(),
                    category = category,
                    wasSuccessful = !assistantResponse.contains("[ERROR")
                )

                database.interactionDao().insert(interaction)

                // Mantener solo las últimas 100 interacciones
                database.interactionDao().keepOnlyLast(100)

                Log.d(TAG, "Interacción almacenada: ${category}")

            } catch (e: Exception) {
                Log.e(TAG, "Error almacenando interacción", e)
            }
        }
    }

    /**
     * Obtiene las últimas N interacciones para contexto inmediato (Capa 2).
     */
    suspend fun getRecentInteractions(limit: Int = 20): List<Interaction> {
        return withContext(Dispatchers.IO) {
            try {
                database.interactionDao().getRecent(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Error obteniendo interacciones recientes", e)
                emptyList()
            }
        }
    }

    // ==================== CAPA 3: DEEP ARCHIVE ====================

    /**
     * Almacena un hecho en el Deep Archive (Capa 3).
     * El hecho se almacena con su embedding para búsqueda semántica.
     */
    suspend fun storeFact(
        content: String,
        category: String = "fact",
        importance: Float = 0.5f,
        source: String = "conversation"
    ): Long {
        return withContext(Dispatchers.IO) {
            try {
                // Generar embedding para búsqueda semántica
                val embedding = deepArchive?.generateEmbedding(content)

                val fact = Fact(
                    content = content,
                    category = category,
                    importance = importance,
                    source = source,
                    embedding = embedding
                )

                val id = database.factDao().insert(fact)

                // También indexar en el vector store
                if (embedding != null) {
                    deepArchive?.index(id, embedding, content)
                }

                Log.d(TAG, "Hecho almacenado: '$content' (cat: $category, imp: $importance)")

                id
            } catch (e: Exception) {
                Log.e(TAG, "Error almacenando hecho", e)
                -1L
            }
        }
    }

    /**
     * Elimina información de la memoria.
     */
    suspend fun forget(query: String) {
        withContext(Dispatchers.IO) {
            try {
                // Buscar hechos que coincidan
                val facts = database.factDao().search(query)
                facts.forEach { fact ->
                    database.factDao().deleteById(fact.id)
                    deepArchive?.removeFromIndex(fact.id)
                }

                Log.i(TAG, "Eliminados ${facts.size} hechos que coinciden con '$query'")

            } catch (e: Exception) {
                Log.e(TAG, "Error eliminando de memoria", e)
            }
        }
    }

    // ==================== BÚSQUEDA CONSOLIDADA ====================

    /**
     * Busca información relevante en todas las capas de memoria.
     *
     * Flujo:
     * 1. Buscar en Living Profile (Capa 1) - keyword matching
     * 2. Buscar en Rolling Context (Capa 2) - keyword + recency
     * 3. Buscar en Deep Archive (Capa 3) - semantic search
     * 4. Consolidar y rankear resultados
     *
     * @param query La consulta en lenguaje natural
     * @return Texto consolidado con la información relevante
     */
    suspend fun recallRelevant(query: String): String {
        val results = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            try {
                // Capa 1: Living Profile
                val profile = getLivingProfile()
                if (profile.isNotBlank()) {
                    val profileMatch = keywordMatch(profile, query)
                    if (profileMatch.isNotBlank()) {
                        results.add("[PERFIL] $profileMatch")
                    }
                }

                // Capa 2: Rolling Context
                val recentInteractions = database.interactionDao().search(query, limit = 3)
                if (recentInteractions.isNotEmpty()) {
                    val contextResults = recentInteractions.joinToString("\n") { interaction ->
                        "- Usuario: ${interaction.userMessage.take(80)}\n  " +
                                "Asistente: ${interaction.assistantResponse.take(80)}"
                    }
                    results.add("[CONTEXTO RECIENTE]\n$contextResults")
                }

                // Capa 3: Deep Archive (búsqueda semántica)
                val queryEmbedding = deepArchive?.generateEmbedding(query)
                if (queryEmbedding != null) {
                    val similarFacts = deepArchive?.search(queryEmbedding, topK = 5)
                    if (!similarFacts.isNullOrEmpty()) {
                        val archiveResults = similarFacts.joinToString("\n") { (fact, score) ->
                            "- [${fact.category}] ${fact.content} (relevancia: ${(score * 100).toInt()}%)"
                        }
                        results.add("[MEMORIA PROFUNDA]\n$archiveResults")
                    }
                }

                // Fallback: búsqueda por keyword en hechos
                if (results.isEmpty()) {
                    val keywordFacts = database.factDao().search(query, limit = 5)
                    if (keywordFacts.isNotEmpty()) {
                        val factResults = keywordFacts.joinToString("\n") { fact ->
                            "- [${fact.category}] ${fact.content}"
                        }
                        results.add("[HECHOS ENCONTRADOS]\n$factResults")
                    } else {
                        // No keyword facts found
                    }
                } else {
                    // Results already found in other layers
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en recallRelevant", e)
            }
        }

        return if (results.isNotEmpty()) {
            results.joinToString("\n\n")
        } else {
            ""
        }
    }

    /**
     * Obtiene un resumen de la memoria para el briefing matutino.
     */
    suspend fun getMorningBriefingData(): MorningBriefingData {
        return withContext(Dispatchers.IO) {
            try {
                val recentInteractions = database.interactionDao().getRecent(5)
                val importantFacts = database.factDao().getImportant(0.7f, 10)
                val patterns = database.patternDao().getStrong(0.6f)
                val priorityContacts = database.contactDao().getPriorityContacts()

                MorningBriefingData(
                    recentInteractions = recentInteractions,
                    importantFacts = importantFacts,
                    patterns = patterns,
                    priorityContacts = priorityContacts
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error obteniendo datos de briefing", e)
                MorningBriefingData()
            }
        }
    }

    // ==================== UTILIDADES ====================

    private fun keywordMatch(text: String, query: String): String {
        val queryWords = query.lowercase().split(" ").filter { it.length > 3 }
        val lines = text.lines()
        val matchingLines = lines.filter { line ->
            queryWords.any { word -> line.lowercase().contains(word) }
        }
        return matchingLines.joinToString("\n")
    }

    private fun classifyInteraction(userMessage: String, assistantResponse: String): String {
        val msg = userMessage.lowercase()
        return when {
            msg.startsWith("envía") || msg.startsWith("manda") || msg.contains("llama a") ->
                "command"
            msg.contains("?") || msg.startsWith("qué") || msg.startsWith("cómo") || msg.startsWith("cuándo") ->
                "question"
            msg.contains("recuerda") || msg.contains("guarda") || msg.contains("nota") ->
                "memory_store"
            else -> "general"
        }
    }

    fun setIdentityManager(im: IdentityManager) {
        identityManager = im
    }

    fun getFactDao(): FactDao = database.factDao()
    fun getInteractionDao(): InteractionDao = database.interactionDao()
    fun getContactDao(): ContactDao = database.contactDao()
    fun getPatternDao(): PatternDao = database.patternDao()

    fun close() {
        scope.cancel()
        if (::database.isInitialized) {
            database.close()
        }
    }
}

/**
 * Datos para el briefing matutino.
 */
data class MorningBriefingData(
    val recentInteractions: List<Interaction> = emptyList(),
    val importantFacts: List<Fact> = emptyList(),
    val patterns: List<UserPattern> = emptyList(),
    val priorityContacts: List<FrequentContact> = emptyList()
)
