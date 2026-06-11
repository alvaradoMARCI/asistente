package com.nubiaagent.cognitive.memory.db

import androidx.room.*

/**
 * Entidad: Interacción del usuario con el agente.
 * Almacena las últimas 20 interacciones para contexto inmediato.
 * Capa 2: Rolling Context.
 */
@Entity(tableName = "interactions")
data class Interaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val userMessage: String,
    val assistantResponse: String,
    val toolsUsed: String,  // JSON array: ["sms.send", "memory.store"]
    val category: String,   // "command", "question", "notification_response", "proactive"
    val sentiment: Float = 0f,  // -1.0 (negativo) a 1.0 (positivo)
    val wasSuccessful: Boolean = true
)

@Dao
interface InteractionDao {
    @Query("SELECT * FROM interactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<Interaction>

    @Query("SELECT * FROM interactions WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getSince(since: Long): List<Interaction>

    @Query("SELECT * FROM interactions WHERE userMessage LIKE '%' || :query || '%' OR assistantResponse LIKE '%' || :query || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 10): List<Interaction>

    @Insert
    suspend fun insert(interaction: Interaction): Long

    @Query("DELETE FROM interactions WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM interactions")
    suspend fun count(): Int

    @Query("DELETE FROM interactions WHERE id NOT IN (SELECT id FROM interactions ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun keepOnlyLast(keepCount: Int = 100): Int
}

/**
 * Entidad: Hecho extraído por el Curation Agent.
 * Almacena conocimientos sobre el usuario.
 * Capa 3: Deep Archive (metadata).
 */
@Entity(tableName = "facts")
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val content: String,
    val category: String,       // "preference", "fact", "event", "contact", "pattern"
    val importance: Float = 0.5f,
    val source: String,         // "conversation", "notification", "observation", "user_input"
    val sourceId: Long = 0,     // ID de la interacción de origen
    val embedding: ByteArray? = null,  // Vector embedding para búsqueda semántica
    val isVerified: Boolean = false,   // Si el usuario confirmó este hecho
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 0
)

@Dao
interface FactDao {
    @Query("SELECT * FROM facts WHERE category = :category ORDER BY importance DESC, timestamp DESC")
    suspend fun getByCategory(category: String): List<Fact>

    @Query("SELECT * FROM facts WHERE content LIKE '%' || :query || '%' ORDER BY importance DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 10): List<Fact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: Fact): Long

    @Update
    suspend fun update(fact: Fact)

    @Delete
    suspend fun delete(fact: Fact)

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Query("SELECT * FROM facts WHERE importance >= :minImportance ORDER BY lastAccessed DESC LIMIT :limit")
    suspend fun getImportant(minImportance: Float = 0.7f, limit: Int = 20): List<Fact>

    @Query("UPDATE facts SET lastAccessed = :timestamp, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun markAccessed(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM facts WHERE embedding IS NOT NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getWithEmbeddings(limit: Int = 100): List<Fact>
}

/**
 * Entidad: Contacto frecuente del usuario.
 * Derivado de notificaciones y mensajes.
 */
@Entity(tableName = "contacts")
data class FrequentContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String? = null,
    val apps: String,           // JSON array: ["whatsapp", "telegram"]
    val lastInteraction: Long = System.currentTimeMillis(),
    val interactionCount: Int = 1,
    val isPriority: Boolean = false,
    val notes: String = ""
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE name LIKE '%' || :name || '%' LIMIT 5")
    suspend fun searchByName(name: String): List<FrequentContact>

    @Query("SELECT * FROM contacts WHERE isPriority = 1 ORDER BY lastInteraction DESC")
    suspend fun getPriorityContacts(): List<FrequentContact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: FrequentContact): Long

    @Update
    suspend fun update(contact: FrequentContact)

    @Query("SELECT * FROM contacts ORDER BY interactionCount DESC LIMIT :limit")
    suspend fun getMostFrequent(limit: Int = 10): List<FrequentContact>
}

/**
 * Entidad: Patrones del usuario.
 * Detectados por el Curation Agent (ej: "siempre revisa WhatsApp a las 8am").
 */
@Entity(tableName = "patterns")
data class UserPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patternType: String,    // "temporal", "behavioral", "communication", "app_usage"
    val description: String,
    val confidence: Float = 0f, // 0-1, se fortalece con cada observación
    val observationCount: Int = 1,
    val firstObserved: Long = System.currentTimeMillis(),
    val lastObserved: Long = System.currentTimeMillis()
)

@Dao
interface PatternDao {
    @Query("SELECT * FROM patterns WHERE patternType = :type ORDER BY confidence DESC")
    suspend fun getByType(type: String): List<UserPattern>

    @Query("SELECT * FROM patterns WHERE confidence >= :minConfidence ORDER BY confidence DESC")
    suspend fun getStrong(minConfidence: Float = 0.7f): List<UserPattern>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pattern: UserPattern): Long

    @Update
    suspend fun update(pattern: UserPattern)

    @Query("SELECT * FROM patterns WHERE description LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<UserPattern>
}

/**
 * Room Database para NubiaAgent.
 * Almacena las capas 2 (Rolling Context) y 3 (Deep Archive metadata).
 */
@Database(
    entities = [Interaction::class, Fact::class, FrequentContact::class, UserPattern::class],
    version = 1,
    exportSchema = true
)
abstract class NubiaDatabase : RoomDatabase() {
    abstract fun interactionDao(): InteractionDao
    abstract fun factDao(): FactDao
    abstract fun contactDao(): ContactDao
    abstract fun patternDao(): PatternDao
}
