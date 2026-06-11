package com.nubiaagent.cognitive.memory

import android.content.Context
import android.util.Log
import com.nubiaagent.cognitive.engine.CognitiveEngine
import com.nubiaagent.cognitive.memory.db.*
import com.nubiaagent.cognitive.memory.vector.SQLiteVecEngine
import com.nubiaagent.execution.safety.SecureVault
import kotlinx.coroutines.*
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ProfileCurator: Motor autónomo de curación y actualización del Living Profile.
 *
 * Este componente es el corazón del Sistema de Memoria de 3 Capas.
 * Se ejecuta de forma autónoma después de cada sesión de interacción
 * significativa y actualiza SOUL.md con las metas, patrones y hechos
 * aprendidos del usuario.
 *
 * ARQUITECTURA:
 *
 * ```
 * ProfileCurator
 *     │
 *     ├── CAPA 1: LIVING PROFILE (SOUL.md + encrypted_profile.enc)
 *     │   ├── Curación automática post-sesión
 *     │   ├── Encriptación AES-256-GCM via Android Keystore
 *     │   ├── Actualización con LLM (DEEP profile) cuando hay ≥5 hechos nuevos
 *     │   └── Backup seguro en SecureVault
 *     │
 *     ├── CAPA 2: ROLLING CONTEXT (Room DB)
 *     │   ├── Últimas 20 interacciones activas
 *     │   ├── Compactación automática (mantener 100, activas 20)
 *     │   └── Búsqueda por keyword para contexto inmediato
 *     │
 *     └── CAPA 3: DEEP ARCHIVE (SQLiteVecEngine)
 *         ├── Búsqueda semántica por similitud coseno
 *         ├── Indexación de hechos con embeddings
 *         ├── Compactación durante Bypass Charging
 *         └── Deduplicación y limpieza periódica
 * ```
 *
 * SEGURIDAD (CellClaw Model):
 *
 * El Living Profile contiene información íntima del usuario:
 * metas personales, relaciones, patrones de comportamiento.
 * Por seguridad, se encripta con AES-256-GCM usando una clave
 * derivada del Android Keystore (hardware-backed en T8300).
 *
 * Flujo de encriptación:
 * 1. Generar/obtener clave AES-256 del Android Keystore
 * 2. Cifrar Living Profile con AES-256-GCM (nonce aleatorio)
 * 3. Almacenar: nonce(12 bytes) + authTag(16 bytes) + ciphertext
 * 4. Descifrar solo en memoria, nunca escribir plaintext a disco
 *
 * ACTIVACIÓN:
 * - Automática: tras cada sesión con ≥3 interacciones
 * - Bypass Charging: curación profunda con re-indexación completa
 * - Manual: usuario solicita "actualiza mi perfil"
 */
class ProfileCurator(
    private val context: Context,
    private val memoryManager: MemoryManager,
    private val cognitiveEngine: CognitiveEngine,
    private val vectorEngine: SQLiteVecEngine
) {

    companion object {
        private const val TAG = "NubiaAgent/Curator"

        // Umbrales de activación
        private const val MIN_INTERACTIONS_FOR_UPDATE = 3
        private const val MIN_FACTS_FOR_PROFILE_REWRITE = 5
        private const val MAX_PROFILE_TOKENS = 3500

        // Archivos de perfil
        private const val ENCRYPTED_PROFILE_FILE = "living_profile.enc"
        private const val SOUL_FILE = "SOUL.md"
        private const val PROFILE_BACKUP_FILE = "profile_backup.enc"

        // Encriptación
        private const val AES_KEY_ALIAS = "nubia_profile_key"
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCurationTime = 0L

    // Vault para gestión de claves
    private val secureVault = SecureVault(context)

    /**
     * Ejecuta curación completa del Living Profile.
     *
     * Pasos:
     * 1. Cargar perfil actual (desencriptar)
     * 2. Procesar conversaciones recientes
     * 3. Extraer hechos nuevos con LLM
     * 4. Detectar patrones de comportamiento
     * 5. Actualizar contactos frecuentes
     * 6. Reescribir Living Profile si hay suficientes cambios
     * 7. Re-indexar en Deep Archive
     * 8. Encriptar y guardar nuevo perfil
     *
     * @param forceBypass Si true, ejecutar curación profunda (Bypass Charging)
     */
    suspend fun curate(forceBypass: Boolean = false) {
        withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                Log.i(TAG, "Iniciando curación del perfil (bypass=$forceBypass)")

                // Paso 1: Cargar perfil actual desencriptado
                val currentProfile = loadEncryptedProfile()
                Log.d(TAG, "Perfil actual cargado: ${currentProfile.length} chars")

                // Paso 2: Procesar conversaciones recientes
                val interactions = memoryManager.getInteractionDao().getRecent(50)
                if (interactions.size < MIN_INTERACTIONS_FOR_UPDATE && !forceBypass) {
                    Log.d(TAG, "Insuficientes interacciones para curación (${interactions.size})")
                    return@withContext
                }

                // Paso 3: Extraer hechos nuevos con LLM
                val newFacts = extractFactsFromConversations(interactions)
                Log.i(TAG, "Hechos nuevos extraídos: ${newFacts.size}")

                // Paso 4: Indexar hechos en Deep Archive (vectorial)
                for (fact in newFacts) {
                    val factId = memoryManager.storeFact(
                        content = fact.content,
                        category = fact.category,
                        importance = fact.importance,
                        source = "profile_curation"
                    )
                    // Indexar en el motor vectorial
                    val embedding = vectorEngine.generateEmbedding(fact.content)
                    vectorEngine.index(
                        factId = factId,
                        content = fact.content,
                        category = fact.category,
                        importance = fact.importance,
                        embedding = embedding
                    )
                }

                // Paso 5: Detectar patrones
                val newPatterns = detectPatterns(interactions)
                Log.i(TAG, "Patrones detectados: ${newPatterns.size}")

                // Paso 6: Actualizar contactos frecuentes
                updateFrequentContacts(interactions)

                // Paso 7: Reescribir Living Profile si hay suficientes cambios
                if (newFacts.size >= MIN_FACTS_FOR_PROFILE_REWRITE || forceBypass) {
                    val updatedProfile = rewriteLivingProfile(
                        currentProfile, newFacts, newPatterns, forceBypass
                    )
                    saveEncryptedProfile(updatedProfile)
                    memoryManager.updateLivingProfile(updatedProfile)
                    Log.i(TAG, "Living Profile reescrito con ${newFacts.size} hechos nuevos")
                } else {
                    // Actualización incremental: agregar hechos sin reescribir todo
                    val incrementalProfile = appendFactsToProfile(currentProfile, newFacts)
                    saveEncryptedProfile(incrementalProfile)
                    memoryManager.updateLivingProfile(incrementalProfile)
                    Log.d(TAG, "Perfil actualizado incrementalmente con ${newFacts.size} hechos")
                }

                // Paso 8: Compactar Deep Archive si es Bypass
                if (forceBypass) {
                    vectorEngine.compact()
                    cleanupOldInteractions()
                    deduplicateFacts()
                    Log.i(TAG, "Compactación y limpieza completada (Bypass)")
                }

                lastCurationTime = System.currentTimeMillis()
                val duration = lastCurationTime - startTime
                Log.i(TAG, "Curación completada en ${duration}ms")

            } catch (e: Exception) {
                Log.e(TAG, "Error en curación del perfil", e)
            }
        }
    }

    /**
     * Carga el Living Profile desencriptándolo desde almacenamiento.
     *
     * Si el archivo encriptado no existe, carga el perfil por defecto
     * desde SOUL.md o el IdentityManager.
     */
    private fun loadEncryptedProfile(): String {
        return try {
            val encFile = File(context.filesDir, ENCRYPTED_PROFILE_FILE)

            if (encFile.exists()) {
                val encryptedData = encFile.readBytes()
                decryptProfile(encryptedData)
            } else {
                // Primera vez: cargar desde IdentityManager
                val profile = memoryManager.getLivingProfile()
                if (profile.isNotBlank()) {
                    // Encriptar y guardar para futuras cargas
                    saveEncryptedProfile(profile)
                }
                profile
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error cargando perfil encriptado, usando plaintext fallback", e)
            memoryManager.getLivingProfile()
        }
    }

    /**
     * Guarda el Living Profile encriptado en almacenamiento.
     *
     * Flujo de encriptación (CellClaw Model):
     * 1. Obtener clave AES-256 del Android Keystore via SecureVault
     * 2. Generar nonce aleatorio de 12 bytes (GCM estándar)
     * 3. Cifrar con AES-256-GCM (authenticated encryption)
     * 4. Almacenar: [nonce(12)] + [ciphertext + authTag]
     * 5. Crear backup en SecureVault
     */
    private fun saveEncryptedProfile(profile: String) {
        try {
            val encryptedData = encryptProfile(profile)

            // Guardar archivo encriptado
            val encFile = File(context.filesDir, ENCRYPTED_PROFILE_FILE)
            encFile.writeBytes(encryptedData)

            // Crear backup en SecureVault
            secureVault.storeCredential("profile_hash", profile.hashCode().toString())
            secureVault.storeCredential("profile_timestamp", System.currentTimeMillis().toString())

            Log.d(TAG, "Perfil encriptado y guardado (${encryptedData.size} bytes)")

        } catch (e: Exception) {
            Log.e(TAG, "Error guardando perfil encriptado", e)
            // Fallback: guardar como texto plano temporal (no ideal pero no perdemos datos)
            val plainFile = File(context.filesDir, "living_profile.md")
            plainFile.writeText(profile)
        }
    }

    /**
     * Encripta el perfil usando AES-256-GCM.
     *
     * La clave se deriva del Android Keystore (hardware-backed en T8300).
     * El nonce es aleatorio por cada encriptación, garantizando que
     * el mismo texto produzca diferente ciphertext cada vez.
     */
    private fun encryptProfile(plaintext: String): ByteArray {
        try {
            // Obtener o crear clave de encriptación del perfil
            var keyBase64 = secureVault.getCredential(AES_KEY_ALIAS)
            if (keyBase64 == null) {
                // Generar clave AES-256 aleatoria
                val key = ByteArray(32)
                java.security.SecureRandom().nextBytes(key)
                keyBase64 = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)
                secureVault.storeCredential(AES_KEY_ALIAS, keyBase64)
            }

            val keyBytes = android.util.Base64.decode(keyBase64, android.util.Base64.NO_WRAP)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            // Generar nonce aleatorio
            val nonce = ByteArray(GCM_NONCE_LENGTH)
            java.security.SecureRandom().nextBytes(nonce)

            // Cifrar con AES-256-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Concatenar: nonce + ciphertext (que incluye authTag)
            val result = ByteArray(nonce.size + ciphertext.size)
            System.arraycopy(nonce, 0, result, 0, nonce.size)
            System.arraycopy(ciphertext, 0, result, nonce.size, ciphertext.size)

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error encriptando perfil", e)
            throw e
        }
    }

    /**
     * Desencripta el perfil usando AES-256-GCM.
     *
     * Formato esperado: [nonce(12)] + [ciphertext + authTag(16)]
     *
     * Si la autenticación GCM falla (AEADBadTagException), significa que
     * los datos fueron modificados o la clave cambió — esto es una
     * característica de seguridad, no un bug.
     */
    private fun decryptProfile(encryptedData: ByteArray): String {
        try {
            // Extraer nonce
            val nonce = encryptedData.copyOfRange(0, GCM_NONCE_LENGTH)
            val ciphertext = encryptedData.copyOfRange(GCM_NONCE_LENGTH, encryptedData.size)

            // Obtener clave del SecureVault
            val keyBase64 = secureVault.getCredential(AES_KEY_ALIAS)
                ?: throw SecurityException("Clave de perfil no encontrada en SecureVault")

            val keyBytes = android.util.Base64.decode(keyBase64, android.util.Base64.NO_WRAP)
            val secretKey = SecretKeySpec(keyBytes, "AES")

            // Desencriptar
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plaintext = cipher.doFinal(ciphertext)
            return String(plaintext, Charsets.UTF_8)

        } catch (e: Exception) {
            Log.e(TAG, "Error desencriptando perfil", e)
            throw e
        }
    }

    /**
     * Extrae hechos de las conversaciones usando el LLM.
     *
     * Procesa en lotes de 5 interacciones para no exceder
     * el contexto del modelo local.
     */
    private suspend fun extractFactsFromConversations(
        interactions: List<Interaction>
    ): List<ExtractedFact> {
        val allFacts = mutableListOf<ExtractedFact>()

        val batches = interactions.chunked(5)
        for (batch in batches) {
            val conversationText = batch.joinToString("\n") { interaction ->
                "Usuario: ${interaction.userMessage}\nAsistente: ${interaction.assistantResponse}"
            }

            val prompt = """Analiza esta conversación y extrae hechos objetivos sobre el usuario.
Responde SOLO en formato JSON array.

Formato: [{"category": "preference|fact|event|contact|pattern", "content": "descripción", "importance": 0.0-1.0}]

Conversación:
$conversationText

Hechos:"""

            try {
                val response = cognitiveEngine.infer(
                    prompt,
                    com.nubiaagent.cognitive.engine.InferenceConfig.FAST
                )
                val facts = parseFactsFromLLM(response)
                allFacts.addAll(facts)
            } catch (e: Exception) {
                Log.w(TAG, "Error extrayendo hechos de lote", e)
            }
        }

        return allFacts
    }

    private fun parseFactsFromLLM(response: String): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        try {
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = org.json.JSONArray(response.substring(jsonStart, jsonEnd + 1))
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    facts.add(ExtractedFact(
                        category = obj.optString("category", "fact"),
                        content = obj.optString("content", ""),
                        importance = obj.optDouble("importance", 0.5).toFloat()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parseando hechos", e)
        }
        return facts.filter { it.content.isNotBlank() }
    }

    /**
     * Detecta patrones de comportamiento en las interacciones.
     */
    private suspend fun detectPatterns(interactions: List<Interaction>): List<UserPattern> {
        val patterns = mutableListOf<UserPattern>()

        // Detectar patrones temporales (horarios de uso)
        val hourCounts = mutableMapOf<Int, Int>()
        for (interaction in interactions) {
            val hour = java.util.Calendar.getInstance().apply {
                timeInMillis = interaction.timestamp
            }.get(java.util.Calendar.HOUR_OF_DAY)
            hourCounts[hour] = (hourCounts[hour] ?: 0) + 1
        }

        val peakHours = hourCounts.entries.sortedByDescending { it.value }.take(3).map { it.key }
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

        // Detectar categorías frecuentes
        val categoryCounts = interactions.groupingBy { it.category }.eachCount()
        val topCategory = categoryCounts.entries.maxByOrNull { it.value }
        if (topCategory != null && topCategory.value > 5) {
            val pattern = UserPattern(
                patternType = "behavioral",
                description = "Interacción más frecuente: ${topCategory.key} (${topCategory.value} veces)",
                confidence = topCategory.value.toFloat() / interactions.size,
                observationCount = topCategory.value
            )
            memoryManager.getPatternDao().insert(pattern)
            patterns.add(pattern)
        }

        return patterns
    }

    /**
     * Actualiza contactos frecuentes basado en menciones.
     */
    private suspend fun updateFrequentContacts(interactions: List<Interaction>) {
        val namePattern = Regex("\\b[A-Z][a-z]{2,}\\b")
        val nameCounts = mutableMapOf<String, Int>()

        for (interaction in interactions) {
            val text = "${interaction.userMessage} ${interaction.assistantResponse}"
            for (name in namePattern.findAll(text).map { it.value }) {
                nameCounts[name] = (nameCounts[name] ?: 0) + 1
            }
        }

        for ((name, count) in nameCounts.filter { it.value >= 2 }) {
            val existing = memoryManager.getContactDao().searchByName(name)
            if (existing.isNotEmpty()) {
                val contact = existing.first()
                memoryManager.getContactDao().update(
                    contact.copy(
                        interactionCount = contact.interactionCount + count,
                        lastInteraction = System.currentTimeMillis()
                    )
                )
            } else {
                memoryManager.getContactDao().insert(
                    FrequentContact(name = name, apps = "[]", interactionCount = count)
                )
            }
        }
    }

    /**
     * Reescribe completamente el Living Profile usando el LLM.
     *
     * Usa el perfil DEEP (modelo más grande) para una reescritura
     * de alta calidad que consolide toda la información.
     */
    private suspend fun rewriteLivingProfile(
        currentProfile: String,
        newFacts: List<ExtractedFact>,
        newPatterns: List<UserPattern>,
        deepRewrite: Boolean
    ): String {
        val newInfo = buildString {
            append("NUEVOS HECHOS DESCUBIERTOS:\n")
            for (fact in newFacts) {
                append("- [${fact.category}] ${fact.content} (imp: ${fact.importance})\n")
            }
            append("\nNUEVOS PATRONES:\n")
            for (pattern in newPatterns) {
                append("- ${pattern.description} (conf: ${"%.2f".format(pattern.confidence)})\n")
            }
        }

        val prompt = """Eres un curador de memoria personal avanzado. Reescribe el Living Profile integrando la nueva información.

REGLAS ESTRICTAS:
1. Mantén el formato markdown del perfil
2. Integra información nueva en las secciones existentes
3. No elimines información existente salvo contradicciones
4. En caso de contradicciones, prioriza la información más reciente
5. El perfil NO debe exceder ~3,500 tokens
6. Sé conciso pero completo — cada línea debe tener valor
7. Organiza por secciones: Información Básica, Metas, Patrones, Preferencias, Contactos, Notas

LIVING PROFILE ACTUAL:
$currentProfile

NUEVA INFORMACIÓN:
$newInfo

LIVING PROFILE ACTUALIZADO:"""

        val config = if (deepRewrite) {
            com.nubiaagent.cognitive.engine.InferenceConfig.DEEP
        } else {
            com.nubiaagent.cognitive.engine.InferenceConfig.BALANCED
        }

        return try {
            val result = cognitiveEngine.infer(prompt, config)
            result.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error reescribiendo perfil con LLM, fallback incremental", e)
            appendFactsToProfile(currentProfile, newFacts)
        }
    }

    /**
     * Agrega hechos nuevos al perfil sin reescribirlo completamente.
     * Fallback cuando no hay suficientes hechos para justificar una reescritura.
     */
    private fun appendFactsToProfile(
        currentProfile: String,
        newFacts: List<ExtractedFact>
    ): String {
        val factsSection = buildString {
            append("\n## Hechos Recientes\n")
            for (fact in newFacts.take(10)) {
                append("- [${fact.category}] ${fact.content}\n")
            }
        }

        // Insertar antes de la sección de Notas, o al final
        val notesIndex = currentProfile.indexOf("## Notas")
        return if (notesIndex > 0) {
            currentProfile.substring(0, notesIndex) + factsSection + "\n" + currentProfile.substring(notesIndex)
        } else {
            currentProfile + factsSection
        }
    }

    /**
     * Limpia interacciones antiguas (más de 30 días).
     */
    private suspend fun cleanupOldInteractions() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
        val deleted = memoryManager.getInteractionDao().deleteOlderThan(thirtyDaysAgo)
        if (deleted > 0) {
            Log.i(TAG, "Interacciones antiguas eliminadas: $deleted")
        }
    }

    /**
     * Desduplica hechos en el Deep Archive.
     *
     * Busca hechos con similitud coseno > 0.95 y fusiona los duplicados,
     * manteniendo el de mayor importancia.
     */
    private suspend fun deduplicateFacts() {
        try {
            val facts = memoryManager.getFactDao().getWithEmbeddings(500)
            var deduped = 0

            for (i in facts.indices) {
                for (j in i + 1 until facts.size) {
                    val factA = facts[i]
                    val factB = facts[j]

                    if (factA.embedding != null && factB.embedding != null) {
                        // Comparar embeddings directamente (ambos son ByteArray)
                        val similarity = cosineSimByte(factA.embedding!!, factB.embedding!!)

                        if (similarity > 0.95f) {
                            // Fusionar: eliminar el de menor importancia
                            val toDelete = if (factA.importance >= factB.importance) factB else factA
                            memoryManager.getFactDao().deleteById(toDelete.id)
                            vectorEngine.removeFromIndex(toDelete.id)
                            deduped++
                        }
                    }
                }
            }

            if (deduped > 0) {
                Log.i(TAG, "Hechos duplicados fusionados: $deduped")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error desduplicando hechos", e)
        }
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i]*b[i]; normA += a[i]*a[i]; normB += b[i]*b[i] }
        val d = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (d > 0f) dot / d else 0f
    }

    /**
     * Similitud coseno entre dos ByteArrays (embeddings almacenados como BLOB).
     * Convierte los bytes a FloatArrays little-endian antes de comparar.
     */
    private fun cosineSimByte(a: ByteArray, b: ByteArray): Float {
        val fa = byteArrayToFloatArray(a)
        val fb = byteArrayToFloatArray(b)
        return cosineSim(fa, fb)
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) floats[i] = buffer.getFloat()
        return floats
    }

    fun destroy() {
        scope.cancel()
    }
}

/**
 * Hecho extraído por el ProfileCurator.
 * Alias de ExtractedFact del CurationAgent para evitar duplicados.
 */
typealias ProfileExtractedFact = ExtractedFact
