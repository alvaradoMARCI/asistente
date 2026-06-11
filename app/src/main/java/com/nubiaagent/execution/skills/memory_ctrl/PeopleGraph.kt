package com.nubiaagent.execution.skills.memory_ctrl

import com.nubiaagent.cognitive.memory.MemoryManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Habilidad de grafo de relaciones personales para NubiaAgent.
 * Gestiona un grafo de personas, sus relaciones, datos biográficos
 * y frecuencia de mención. Utiliza MemoryManager para persistencia.
 *
 * Toda la información se almacena bajo la categoría "person" en MemoryManager.
 */
class PeopleGraph(private val memoryManager: MemoryManager) {

    companion object {
        private const val TAG = "PeopleGraph"
        private const val CATEGORY = "person"
        private const val KEY_PEOPLE_INDEX = "people_index"

        // ─── Heurísticas de detección de nombres ───────────────────────────

        /** Palabras comunes que NO son nombres propios (español) */
        private val STOP_WORDS_ES = setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas",
            "de", "del", "al", "a", "en", "por", "para", "con", "sin", "sobre",
            "entre", "hacia", "hasta", "desde", "según", "contra",
            "y", "o", "pero", "ni", "sino", "aunque", "porque", "si", "cuando",
            "que", "quien", "quienes", "cual", "cuales", "cuyo", "cuya",
            "yo", "tú", "él", "ella", "nosotros", "nosotras",
            "vosotros", "vosotras", "ellos", "ellas",
            "me", "te", "se", "nos", "os", "le", "les", "lo", "la", "los", "las",
            "mí", "ti", "sí", "mío", "mía", "tuyo", "tuya", "suyo", "suya",
            "este", "esta", "esto", "estos", "estas",
            "ese", "esa", "eso", "esos", "esas",
            "aquel", "aquella", "aquello", "aquellos", "aquellas",
            "muy", "mucho", "mucha", "muchos", "muchas",
            "poco", "poca", "pocos", "pocas",
            "tan", "tanto", "tanta", "tantos", "tantas",
            "más", "menos", "mejor", "peor", "mayor", "menor",
            "bien", "mal", "aquí", "allí", "ahí", "allá",
            "hoy", "ayer", "mañana", "siempre", "nunca", "tarde", "temprano",
            "sí", "no", "tal", "vez", "cada", "todo", "toda", "todos", "todas",
            "otro", "otra", "otros", "otras", "mismo", "misma", "mismos", "mismas",
            "algo", "alguien", "nada", "nadie", "cualquiera",
            "donde", "cuando", "como", "cuanto",
            "también", "además", "incluso", "solo", "sólo",
            "ya", "ahora", "entonces", "después", "antes", "mientras",
            "puede", "puedo", "puedes", "podemos", "pueden",
            "ser", "es", "son", "soy", "eres", "somos", "sois",
            "estar", "está", "están", "estoy", "estás", "estamos", "estáis",
            "tener", "tiene", "tienen", "tengo", "tienes", "tenemos", "tenéis",
            "hacer", "hace", "hacen", "hago", "haces", "hacemos", "hacéis",
            "decir", "dice", "dicen", "digo", "dices", "decimos", "decís",
            "ir", "va", "van", "voy", "vas", "vamos", "vais",
            "ver", "ve", "ven", "veo", "ves", "vemos", "veis",
            "dar", "da", "dan", "doy", "das", "damos", "dais",
            "saber", "sabe", "saben", "sé", "sabes", "sabemos", "sabéis",
            "querer", "quiere", "quieren", "quiero", "quieres", "queremos",
            "poder", "deber", "parecer", "quedar", "haber",
            "ha", "han", "he", "has", "hemos", "habéis", "hay",
            "fue", "fueron", "era", "fueron", "será", "serán",
            "estuvo", "estuvieron", "estaba", "estaban",
            "tuvo", "tuvieron", "tenía", "tenían",
            "hizo", "hicieron", "hacía", "hacían",
            "dijo", "dijeron", "decía", "decían",
            "fui", "fuiste", "fue", "fuimos", "fueron",
            "estuve", "estuviste", "estuvo", "estuvimos", "estuvieron",
            "tuve", "tuviste", "tuvo", "tuvimos", "tuvieron",
            "hice", "hiciste", "hizo", "hicimos", "hicieron",
            "dije", "dijiste", "dijo", "dijimos", "dijeron",
            "cosa", "cosas", "manera", "forma", "vez", "veces",
            "día", "días", "año", "años", "vez", "veces",
            "hora", "horas", "minuto", "minutos", "momento",
            "hombre", "mujer", "persona", "gente", "chico", "chica",
            "amigo", "amiga", "amigos", "amigas",
            "papá", "mamá", "padre", "madre", "hijo", "hija",
            "hermano", "hermana", "primo", "prima",
            "esposo", "esposa", "marido", "mujer", "novio", "novia",
            "jefe", "jefa", "compañero", "compañera", "colega",
            "doctor", "doctora", "profesor", "profesora",
            "señor", "señora", "señorita",
            "viejo", "vieja", "nuevo", "nueva", "grande", "pequeño", "pequeña",
            "bueno", "buena", "malo", "mala", "bonito", "bonita",
            "casa", "trabajo", "escuela", "ciudad", "país", "mundo",
            "telefono", "teléfono", "mensaje", "llamada", "correo",
            "dinero", "cuenta", "banco", "tarjeta", "pago",
            "problema", "solución", "idea", "plan", "proyecto",
            "gracias", "por", "favor", "hola", "adiós", "adios",
            "buenos", "buenas", "tardes", "noches", "días"
        )

        /** Patrones de relaciones comunes en español */
        private val RELATION_PATTERNS = mapOf(
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:espos[oa]|marido|mujer)", RegexOption.IGNORE_CASE) to "esposo/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+novi[oa]", RegexOption.IGNORE_CASE) to "pareja",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:papá|mamá|padre|madre)", RegexOption.IGNORE_CASE) to "padre/madre",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:hij[oa])", RegexOption.IGNORE_CASE) to "hijo/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+herman[oa]", RegexOption.IGNORE_CASE) to "hermano/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+prim[oa]", RegexOption.IGNORE_CASE) to "primo/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:tí[oa]|tío|tía)", RegexOption.IGNORE_CASE) to "tío/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:abuel[oa])", RegexOption.IGNORE_CASE) to "abuelo/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:suegr[oa])", RegexOption.IGNORE_CASE) to "suegro/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:cuñad[oa])", RegexOption.IGNORE_CASE) to "cuñado/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:amig[oa])", RegexOption.IGNORE_CASE) to "amigo/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:jef[ea])", RegexOption.IGNORE_CASE) to "jefe/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:compañer[oa]|colega)", RegexOption.IGNORE_CASE) to "compañero/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:vecin[oa])", RegexOption.IGNORE_CASE) to "vecino/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:profesor[oa]|profe)", RegexOption.IGNORE_CASE) to "profesor/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:doctor[oa]|doctora|médic[oa])", RegexOption.IGNORE_CASE) to "doctor/a",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:cliente)", RegexOption.IGNORE_CASE) to "cliente",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:compañer[oa]\\s+de\\s+trabajo)", RegexOption.IGNORE_CASE) to "compañero de trabajo",
            Regex("(?:mi|su|nuestro|nuestra)\\s+(?:compañer[oa]\\s+de\\s+clase)", RegexOption.IGNORE_CASE) to "compañero de clase"
        )

        /** Patrones de hechos sobre personas */
        private val FACT_PATTERNS = listOf(
            Regex("(?:trabaja|labora)\\s+(?:en|para|como)\\s+(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:estudia|estudia)\\s+(?:en|para|el|la|)\\s+(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:vive|reside)\\s+(?:en)\\s+(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:cumple|tiene)\\s+(\\d+)\\s+años", RegexOption.IGNORE_CASE),
            Regex("(?:es|está)\\s+(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:le gusta|le encantan|disfruta)\\s+(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:no le gusta|odia|detesta)\\s+(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:teléfono|número|celular)\\s*(?::|es|:)\\s*(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:correo|email|mail)\\s*(?::|es|:)\\s*(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE),
            Regex("(?:cumpleaños|cumple)\\s*(?::|es|el)\\s*(.+?)(?:\\.|,|;|$)", RegexOption.IGNORE_CASE)
        )

        /** Patrones contextuales de apps de mensajería */
        private val CHAT_APP_PATTERNS = mapOf(
            "whatsapp" to Regex("(?:en\\s+WhatsApp|por\\s+WhatsApp|por\\s+wa)", RegexOption.IGNORE_CASE),
            "telegram" to Regex("(?:en\\s+Telegram|por\\s+Telegram)", RegexOption.IGNORE_CASE),
            "messenger" to Regex("(?:en\\s+Messenger|por\\s+Messenger)", RegexOption.IGNORE_CASE),
            "signal" to Regex("(?:en\\s+Signal|por\\s+Signal)", RegexOption.IGNORE_CASE),
            "sms" to Regex("(?:por\\s+SMS|por\\s+mensaje\\s+de\\s+texto)", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Nodo que representa a una persona en el grafo de relaciones.
     */
    data class PersonNode(
        val name: String,
        val relations: Map<String, String>,   // nombre_otra_persona -> tipo_relación
        val facts: List<String>,
        val lastMentioned: Long,              // timestamp millis
        val mentionCount: Long
    )

    // ─── Operaciones CRUD ──────────────────────────────────────────────────

    /**
     * Añade una persona al grafo de relaciones.
     * Si ya existe, actualiza su conteo de menciones y timestamp.
     *
     * @param name Nombre de la persona
     * @param relation Relación con el usuario (ej: "amigo/a", "compañero de trabajo")
     * @param facts Hechos conocidos sobre la persona
     */
    fun addPerson(name: String, relation: String, facts: List<String>) {
        val normalizedName = normalizeName(name)
        android.util.Log.i(TAG, "Añadiendo persona: $normalizedName, relación: $relation")

        val existing = loadPersonNode(normalizedName)
        val now = System.currentTimeMillis()

        val updatedNode = if (existing != null) {
            // Persona existente: actualizar
            val updatedFacts = (existing.facts + facts).distinct()
            val updatedRelations = if (relation.isNotBlank() && !existing.relations.containsKey("yo")) {
                        existing.relations + ("yo" to relation)
            } else {
                existing.relations
            }
            existing.copy(
                relations = updatedRelations,
                facts = updatedFacts,
                lastMentioned = now,
                mentionCount = existing.mentionCount + 1
            )
        } else {
            // Persona nueva
            val initialRelations = if (relation.isNotBlank()) {
                mapOf("yo" to relation)
            } else {
                emptyMap()
            }
            PersonNode(
                name = normalizedName,
                relations = initialRelations,
                facts = facts.distinct(),
                lastMentioned = now,
                mentionCount = 1
            )
        }

        savePersonNode(updatedNode)
        addToPeopleIndex(normalizedName)

        android.util.Log.i(
            TAG,
            "Persona guardada: $normalizedName, menciones: ${updatedNode.mentionCount}, " +
            "hechos: ${updatedNode.facts.size}, relaciones: ${updatedNode.relations.size}"
        )
    }

    /**
     * Recupera la información de una persona del grafo.
     * @param name Nombre de la persona a buscar
     * @return Result con PersonNode o null si no se encuentra
     */
    fun getPerson(name: String): Result<PersonNode?> {
        val normalizedName = normalizeName(name)
        val node = loadPersonNode(normalizedName)

        return if (node != null) {
            android.util.Log.d(TAG, "Persona encontrada: $normalizedName")
            Result.success(node)
        } else {
            android.util.Log.d(TAG, "Persona no encontrada: $normalizedName")
            Result.success(null)
        }
    }

    /**
     * Establece una relación entre dos personas del grafo.
     * La relación se almacena bidireccionalmente.
     *
     * @param person1 Nombre de la primera persona
     * @param person2 Nombre de la segunda persona
     * @param relationType Tipo de relación (ej: "hermano/a", "colega")
     */
    fun addRelation(person1: String, person2: String, relationType: String) {
        val name1 = normalizeName(person1)
        val name2 = normalizeName(person2)

        android.util.Log.i(TAG, "Añadiendo relación: $name1 --[$relationType]-- $name2")

        // Asegurar que ambas personas existen
        val node1 = loadPersonNode(name1) ?: PersonNode(
            name = name1, relations = emptyMap(), facts = emptyList(),
            lastMentioned = System.currentTimeMillis(), mentionCount = 0
        )
        val node2 = loadPersonNode(name2) ?: PersonNode(
            name = name2, relations = emptyMap(), facts = emptyList(),
            lastMentioned = System.currentTimeMillis(), mentionCount = 0
        )

        // Añadir relación bidireccional
        val updatedNode1 = node1.copy(
            relations = node1.relations + (name2 to relationType),
            lastMentioned = System.currentTimeMillis()
        )
        val updatedNode2 = node2.copy(
            relations = node2.relations + (name1 to relationType),
            lastMentioned = System.currentTimeMillis()
        )

        savePersonNode(updatedNode1)
        savePersonNode(updatedNode2)

        addToPeopleIndex(name1)
        addToPeopleIndex(name2)

        android.util.Log.i(TAG, "Relación añadida: $name1 y $name2 son $relationType")
    }

    /**
     * Obtiene las personas relacionadas con una persona dada.
     * @param name Nombre de la persona
     * @return Result con lista de PersonNodes relacionados
     */
    fun getRelatedPeople(name: String): Result<List<PersonNode>> {
        val normalizedName = normalizeName(name)
        val node = loadPersonNode(normalizedName)

        if (node == null) {
            return Result.success(emptyList())
        }

        val relatedPeople = node.relations.keys.mapNotNull { relatedName ->
            if (relatedName == "yo") {
                // "yo" es el usuario mismo, no un nodo que buscar
                null
            } else {
                loadPersonNode(relatedName)
            }
        }

        android.util.Log.d(TAG, "Personas relacionadas con $normalizedName: ${relatedPeople.size}")
        return Result.success(relatedPeople)
    }

    /**
     * Añade un nuevo hecho sobre una persona.
     * @param name Nombre de la persona
     * @param fact Hecho a añadir
     */
    fun updatePersonFact(name: String, fact: String) {
        val normalizedName = normalizeName(name)
        val node = loadPersonNode(normalizedName)

        if (node == null) {
            // Crear la persona si no existe con este hecho
            addPerson(normalizedName, "", listOf(fact))
            return
        }

        if (node.facts.contains(fact)) {
            android.util.Log.d(TAG, "Hecho ya existente para $normalizedName: $fact")
            return
        }

        val updatedNode = node.copy(
            facts = node.facts + fact,
            lastMentioned = System.currentTimeMillis()
        )
        savePersonNode(updatedNode)

        android.util.Log.i(TAG, "Hecho añadido para $normalizedName: $fact")
    }

    // ─── Detección de personas en texto ────────────────────────────────────

    /**
     * Detecta nombres de personas en un texto usando heurísticas de NLP.
     * Detecta palabras capitalizadas que no sean stopwords, especialmente
     * después de palabras indicadoras de persona.
     *
     * @param text Texto a analizar
     * @return Lista de nombres detectados
     */
    fun detectPeopleInText(text: String): List<String> {
        val detectedNames = mutableSetOf<String>()

        // 1. Patrón: después de indicadores de relación
        val personIndicators = listOf(
            "mi", "su", "con", "de", "se llama", "llama", "es", "era",
            "conocí", "conoció", "presentó", "presente", "me presentaron",
            "hablé", "habló", "vi", "vio", "encontré", "encontró",
            "amigo", "amiga", "colega", "compañero", "compañera",
            "vecino", "vecina", "jefe", "jefa", "cliente",
            "hermano", "hermana", "primo", "prima", "tío", "tía",
            "papá", "mamá", "padre", "madre", "hijo", "hija"
        )

        // 2. Buscar nombres capitalizados
        val wordPattern = Regex("\\b([A-ZÁÉÍÓÚÑ][a-záéíóúñ]+(?:\\s+[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+)*)\\b")
        wordPattern.findAll(text).forEach { match ->
            val candidate = match.groupValues[1].trim()
            val words = candidate.split("\\s+".toRegex())

            // Verificar que no sea una stop word y tenga formato de nombre
            val isValidName = words.all { word ->
                word.length >= 2 &&
                word[0].isUpperCase() &&
                !STOP_WORDS_ES.contains(word.lowercase()) &&
                !isCommonNounOrAdjective(word)
            }

            if (isValidName && words.isNotEmpty()) {
                detectedNames.add(candidate)
            }
        }

        // 3. Patrón: "se llama X" o "llamarse X"
        val nameCallPatterns = listOf(
            Regex("(?:se\\s+llama|llamarse|se\\s+llamaba|llamaba)\\s+([A-ZÁÉÍÓÚÑ][a-záéíóúñ]+(?:\\s+[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+)*)", RegexOption.IGNORE_CASE),
            Regex("(?:nombre\\s+(?:es|era))\\s+([A-ZÁÉÍÓÚÑ][a-záéíóúñ]+(?:\\s+[A-ZÁÉÍÓÚÑ][a-záéíóúñ]+)*)", RegexOption.IGNORE_CASE)
        )

        nameCallPatterns.forEach { pattern ->
            pattern.findAll(text).forEach { match ->
                val name = match.groupValues[1].trim()
                if (name.isNotBlank()) {
                    detectedNames.add(name)
                }
            }
        }

        // 4. Filtrar nombres que ya conocemos
        val knownPeople = getPeopleIndex()
        val allNames = detectedNames + knownPeople.filter { name ->
            text.contains(name, ignoreCase = true)
        }

        val filtered = allNames.filter { name ->
            name.length >= 2 && !STOP_WORDS_ES.contains(name.lowercase())
        }.distinct()

        android.util.Log.d(TAG, "Personas detectadas en texto: $filtered")
        return filtered
    }

    /**
     * Procesa un mensaje de chat para extraer personas y hechos.
     * Detecta nombres, relaciones y hechos sobre personas mencionadas.
     *
     * @param chatText Texto del mensaje de chat
     * @param app Nombre de la aplicación de mensajería (whatsapp, telegram, etc.)
     */
    fun processChatForPeople(chatText: String, app: String) {
        android.util.Log.i(TAG, "Procesando chat de $app para extraer personas")

        // 1. Detectar personas en el texto
        val detectedPeople = detectPeopleInText(chatText)

        // 2. Detectar relaciones
        val detectedRelations = detectRelations(chatText)

        // 3. Detectar hechos
        val detectedFacts = detectFacts(chatText)

        // 4. Añadir personas detectadas
        for (name in detectedPeople) {
            val relation = detectedRelations[name] ?: ""
            val facts = detectedFacts[name] ?: emptyList()

            addPerson(name, relation, facts)

            // Registrar el contexto de la app
            updatePersonFact(name, "Mencionado en $app el ${formatDate(System.currentTimeMillis())}")
        }

        // 5. Añadir relaciones entre personas detectadas
        if (detectedPeople.size >= 2) {
            for (i in detectedPeople.indices) {
                for (j in i + 1 until detectedPeople.size) {
                    val relType = detectRelationBetween(chatText, detectedPeople[i], detectedPeople[j])
                    if (relType != null) {
                        addRelation(detectedPeople[i], detectedPeople[j], relType)
                    }
                }
            }
        }

        android.util.Log.i(
            TAG,
            "Chat procesado: ${detectedPeople.size} personas, " +
            "${detectedRelations.size} relaciones, ${detectedFacts.size} conjuntos de hechos"
        )
    }

    // ─── Consultas avanzadas ───────────────────────────────────────────────

    /**
     * Obtiene todas las personas registradas en el grafo.
     */
    fun getAllPeople(): Result<List<PersonNode>> {
        val index = getPeopleIndex()
        val nodes = index.mapNotNull { name -> loadPersonNode(name) }
        return Result.success(nodes)
    }

    /**
     * Busca personas cuyo nombre o hechos contengan el texto dado.
     */
    fun searchPeople(query: String): Result<List<PersonNode>> {
        val normalizedQuery = query.lowercase().trim()
        val allPeople = getAllPeople().getOrDefault(emptyList())

        val results = allPeople.filter { node ->
            node.name.lowercase().contains(normalizedQuery) ||
            node.facts.any { it.lowercase().contains(normalizedQuery) } ||
            node.relations.any { it.key.lowercase().contains(normalizedQuery) || it.value.lowercase().contains(normalizedQuery) }
        }

        return Result.success(results)
    }

    /**
     * Obtiene las personas mencionadas más recientemente.
     */
    fun getRecentlyMentioned(limit: Int = 10): Result<List<PersonNode>> {
        val allPeople = getAllPeople().getOrDefault(emptyList())
        val sorted = allPeople.sortedByDescending { it.lastMentioned }
        return Result.success(sorted.take(limit))
    }

    /**
     * Obtiene las personas más mencionadas.
     */
    fun getMostMentioned(limit: Int = 10): Result<List<PersonNode>> {
        val allPeople = getAllPeople().getOrDefault(emptyList())
        val sorted = allPeople.sortedByDescending { it.mentionCount }
        return Result.success(sorted.take(limit))
    }

    /**
     * Genera un resumen en español del grafo de relaciones.
     */
    fun getSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("👥 Grafo de Personas")
        sb.appendLine("═══════════════════════════════════")

        val allPeople = getAllPeople().getOrDefault(emptyList())

        if (allPeople.isEmpty()) {
            sb.appendLine("\n  No hay personas registradas aún.")
            return sb.toString()
        }

        sb.appendLine("\n📋 Personas registradas: ${allPeople.size}")

        allPeople.sortedByDescending { it.mentionCount }.forEach { person ->
            sb.appendLine("\n  🧑 ${person.name}")
            sb.appendLine("     Menciones: ${person.mentionCount}")
            sb.appendLine("     Última mención: ${formatDate(person.lastMentioned)}")

            if (person.relations.isNotEmpty()) {
                sb.appendLine("     Relaciones:")
                person.relations.forEach { (other, relType) ->
                    sb.appendLine("       → $other: $relType")
                }
            }

            if (person.facts.isNotEmpty()) {
                sb.appendLine("     Datos conocidos:")
                person.facts.take(5).forEach { fact ->
                    sb.appendLine("       • $fact")
                }
                if (person.facts.size > 5) {
                    sb.appendLine("       ... y ${person.facts.size - 5} más")
                }
            }
        }

        return sb.toString()
    }

    /**
     * Elimina una persona del grafo.
     */
    fun removePerson(name: String): Result<Boolean> {
        val normalizedName = normalizeName(name)
        val removed = runBlocking {
            val facts = memoryManager.getFactDao().getByCategory(CATEGORY)
            val personFact = facts.find { it.source == "person_$normalizedName" }
            if (personFact != null) {
                memoryManager.getFactDao().deleteById(personFact.id)
                true
            } else {
                false
            }
        }

        if (removed) {
            // Eliminar del índice
            val index = getPeopleIndex().toMutableList()
            index.remove(normalizedName)
            savePeopleIndex(index)

            // Eliminar referencias en otras personas
            val allPeople = getAllPeople().getOrDefault(emptyList())
            allPeople.forEach { person ->
                if (person.relations.containsKey(normalizedName)) {
                    val updatedRelations = person.relations - normalizedName
                    savePersonNode(person.copy(relations = updatedRelations))
                }
            }

            android.util.Log.i(TAG, "Persona eliminada: $normalizedName")
        }

        return Result.success(removed)
    }

    // ─── Métodos privados de detección ─────────────────────────────────────

    /**
     * Detecta relaciones mencionadas en el texto.
     * Devuelve un mapa de nombre -> tipo de relación con el usuario.
     */
    private fun detectRelations(text: String): Map<String, String> {
        val relations = mutableMapOf<String, String>()

        RELATION_PATTERNS.forEach { (pattern, relationType) ->
            pattern.find(text)?.let { match ->
                // Buscar un nombre cerca de la mención de relación
                val nearbyText = text.substring(
                    maxOf(0, match.range.first - 50),
                    minOf(text.length, match.range.last + 100)
                )
                val names = detectPeopleInText(nearbyText)
                if (names.isNotEmpty()) {
                    relations[names.first()] = relationType
                }
            }
        }

        return relations
    }

    /**
     * Detecta hechos sobre personas en el texto.
     * Devuelve un mapa de nombre -> lista de hechos.
     */
    private fun detectFacts(text: String): Map<String, List<String>> {
        val facts = mutableMapOf<String, MutableList<String>>()
        val detectedNames = detectPeopleInText(text)

        for (name in detectedNames) {
            val nameFacts = mutableListOf<String>()

            // Buscar hechos cerca del nombre
            val nameOccurrences = Regex(Regex.escape(name), RegexOption.IGNORE_CASE).findAll(text)
            nameOccurrences.forEach { occurrence ->
                val start = maxOf(0, occurrence.range.first - 30)
                val end = minOf(text.length, occurrence.range.last + 150)
                val context = text.substring(start, end)

                FACT_PATTERNS.forEach { pattern ->
                    pattern.find(context)?.let { factMatch ->
                        val fact = factMatch.groupValues[1]?.trim()
                        if (fact != null && fact.isNotBlank() && fact.length > 2) {
                            nameFacts.add(fact)
                        }
                    }
                }
            }

            if (nameFacts.isNotEmpty()) {
                facts[name] = nameFacts.distinct().toMutableList()
            }
        }

        return facts
    }

    /**
     * Detecta la relación entre dos personas mencionadas en el texto.
     */
    private fun detectRelationBetween(text: String, person1: String, person2: String): String? {
        val p1Idx = text.indexOf(person1, ignoreCase = true)
        val p2Idx = text.indexOf(person2, ignoreCase = true)

        if (p1Idx < 0 || p2Idx < 0) return null

        val start = minOf(p1Idx, p2Idx)
        val end = maxOf(p1Idx + person1.length, p2Idx + person2.length)
        val context = text.substring(maxOf(0, start - 50), minOf(text.length, end + 50))

        // Buscar patrones de relación en el contexto
        val betweenPatterns = mapOf(
            Regex("(?:herman[oa]s|herman[oa])", RegexOption.IGNORE_CASE) to "hermano/a",
            Regex("(?:prim[oa]s|prim[oa])", RegexOption.IGNORE_CASE) to "primo/a",
            Regex("(?:amig[oa]s|amig[oa])", RegexOption.IGNORE_CASE) to "amigo/a",
            Regex("(?:colegas|colega)", RegexOption.IGNORE_CASE) to "colega",
            Regex("(?:compañer[oa]s|compañer[oa])", RegexOption.IGNORE_CASE) to "compañero/a",
            Regex("(?:vecin[oa]s|vecin[oa])", RegexOption.IGNORE_CASE) to "vecino/a",
            Regex("(?:espos[oa]s|espos[oa])", RegexOption.IGNORE_CASE) to "esposo/a",
            Regex("(?:novi[oa]s|novi[oa])", RegexOption.IGNORE_CASE) to "pareja",
            Regex("(?:padre|madre|hij[oa])", RegexOption.IGNORE_CASE) to "familia",
            Regex("(?:trabajan\\s+juntos|compañeros\\s+de\\s+trabajo)", RegexOption.IGNORE_CASE) to "compañero de trabajo",
            Regex("(?:estudian\\s+juntos|compañeros\\s+de\\s+clase)", RegexOption.IGNORE_CASE) to "compañero de clase"
        )

        for ((pattern, relType) in betweenPatterns) {
            if (pattern.containsMatchIn(context)) {
                return relType
            }
        }

        return null
    }

    /**
     * Verifica si una palabra es un sustantivo o adjetivo común que no debería
     * ser tratado como nombre propio.
     */
    private fun isCommonNounOrAdjective(word: String): Boolean {
        val commonWords = setOf(
            "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo",
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre",
            "España", "México", "Argentina", "Colombia", "Perú", "Chile",
            "Madrid", "Barcelona", "Bogotá", "Lima", "Buenos", "Aires",
            "WhatsApp", "Telegram", "Facebook", "Instagram", "Twitter", "Google",
            "Android", "iPhone", "Nubia", "ZTE",
            "Hola", "Gracias", "Buenos", "Buenas", "Noche", "Tarde",
            "Hoy", "Ayer", "Mañana", "Siempre", "Nunca",
            "Nada", "Algo", "Todo", "Todos", "Todas",
            "Primero", "Segundo", "Tercero", "Último",
            "Principal", "Importante", "Necesario", "Posible",
            "Nuevo", "Nueva", "Viejo", "Vieja", "Grande", "Pequeño",
            "Bueno", "Buena", "Malo", "Mala", "Mejor", "Peor",
            "Verdad", "Falso", "Claro", "Seguro", "Perfecto",
            "Total", "Parcial", "General", "Especial", "Normal",
            "Norte", "Sur", "Este", "Oeste"
        )
        return commonWords.contains(word)
    }

    // ─── Persistencia con MemoryManager ────────────────────────────────────

    private fun normalizeName(name: String): String {
        return name.trim().split("\\s+".toRegex())
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    private fun loadPersonNode(name: String): PersonNode? {
        val normalizedName = normalizeName(name)
        val json = runBlocking {
            val facts = memoryManager.getFactDao().getByCategory(CATEGORY)
            facts.find { it.source == "person_$normalizedName" }?.content
        } ?: return null

        return try {
            parsePersonNode(json)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error cargando nodo de persona '$name': ${e.message}")
            null
        }
    }

    private fun savePersonNode(node: PersonNode) {
        val normalizedName = normalizeName(node.name)
        val json = serializePersonNode(node)
        runBlocking {
            val facts = memoryManager.getFactDao().getByCategory(CATEGORY)
            val existing = facts.find { it.source == "person_$normalizedName" }
            if (existing != null) {
                memoryManager.getFactDao().update(
                    existing.copy(content = json)
                )
            } else {
                memoryManager.storeFact(
                    content = json,
                    category = CATEGORY,
                    importance = 0.5f,
                    source = "person_$normalizedName"
                )
            }
        }
    }

    private fun serializePersonNode(node: PersonNode): String {
        val json = JSONObject().apply {
            put("name", node.name)
            put("lastMentioned", node.lastMentioned)
            put("mentionCount", node.mentionCount)

            val relationsObj = JSONObject()
            node.relations.forEach { (name, type) ->
                relationsObj.put(name, type)
            }
            put("relations", relationsObj)

            val factsArray = JSONArray()
            node.facts.forEach { factsArray.put(it) }
            put("facts", factsArray)
        }
        return json.toString()
    }

    private fun parsePersonNode(json: String): PersonNode {
        val obj = JSONObject(json)
        val relations = mutableMapOf<String, String>()
        val relationsObj = obj.optJSONObject("relations")
        if (relationsObj != null) {
            val keys = relationsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                relations[key] = relationsObj.getString(key)
            }
        }

        val facts = mutableListOf<String>()
        val factsArray = obj.optJSONArray("facts")
        if (factsArray != null) {
            for (i in 0 until factsArray.length()) {
                facts.add(factsArray.getString(i))
            }
        }

        return PersonNode(
            name = obj.getString("name"),
            relations = relations,
            facts = facts,
            lastMentioned = obj.optLong("lastMentioned", System.currentTimeMillis()),
            mentionCount = obj.optLong("mentionCount", 1)
        )
    }

    private fun getPeopleIndex(): List<String> {
        val indexJson = runBlocking {
            val facts = memoryManager.getFactDao().getByCategory(CATEGORY)
            facts.find { it.source == KEY_PEOPLE_INDEX }?.content
        } ?: return emptyList()
        return try {
            val array = JSONArray(indexJson)
            (0 until array.length()).map { i -> array.getString(i) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePeopleIndex(index: List<String>) {
        val array = JSONArray()
        index.forEach { array.put(it) }
        runBlocking {
            val facts = memoryManager.getFactDao().getByCategory(CATEGORY)
            val existing = facts.find { it.source == KEY_PEOPLE_INDEX }
            if (existing != null) {
                memoryManager.getFactDao().update(
                    existing.copy(content = array.toString())
                )
            } else {
                memoryManager.storeFact(
                    content = array.toString(),
                    category = CATEGORY,
                    importance = 0.5f,
                    source = KEY_PEOPLE_INDEX
                )
            }
        }
    }

    private fun addToPeopleIndex(name: String) {
        val index = getPeopleIndex().toMutableList()
        if (!index.contains(name)) {
            index.add(name)
            savePeopleIndex(index)
        }
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))
        return sdf.format(java.util.Date(timestamp))
    }
}
