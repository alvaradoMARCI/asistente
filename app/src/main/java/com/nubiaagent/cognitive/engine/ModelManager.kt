package com.nubiaagent.cognitive.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * ModelManager: Gestiona el ciclo de vida de los modelos GGUF en el dispositivo.
 *
 * Responsabilidades:
 * 1. Descarga y verificación de modelos GGUF
 * 2. Gestión de almacenamiento (almacenamiento interno protegido)
 * 3. Selección de modelo óptimo según RAM disponible
 * 4. Mantenimiento del modelo en memoria (keep-alive)
 * 5. Limpieza de modelos antiguos para liberar espacio
 *
 * MODELOS SOPORTADOS (formato GGUF, cuantizados para ARM64):
 *
 * | Modelo | Tamaño (Q4_K_M) | RAM Necesaria | Velocidad (tok/s) | Calidad |
 * |--------|-----------------|---------------|-------------------|---------|
 * | Llama 3.2 1B | ~800 MB | ~2 GB | ~25 tok/s | Básica |
 * | Llama 3.2 3B | ~2 GB | ~4 GB | ~12 tok/s | Buena |
 * | Gemma 2 2B | ~1.5 GB | ~3 GB | ~18 tok/s | Buena |
 * | Phi-3 Mini 3.8B | ~2.3 GB | ~5 GB | ~10 tok/s | Muy buena |
 * | Qwen2.5 3B | ~2 GB | ~4 GB | ~12 tok/s | Muy buena |
 *
 * Con 20 GB de RAM dinámica, el Nubia Neo 3 5G puede cómodamente
 * cargar modelos de hasta 7B parámetros en Q4_K_M.
 *
 * ESTRATEGIA DE ALMACENAMIENTO:
 * - Modelos en: /data/data/com.nubiaagent/models/
 * - Cache de contexto en: /data/data/com.nubiaagent/cache/llm/
 * - Los modelos se almacenan en almacenamiento interno (no removable)
 * para evitar pérdida si se extrae la SD.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/ModelMgr"
        private const val MODELS_DIR = "models"
        private const val CACHE_DIR = "llm_cache"

        // Modelos disponibles con sus especificaciones
        val AVAILABLE_MODELS = mapOf(
            "llama-3.2-1b-instruct-Q4_K_M" to ModelSpec(
                name = "Llama 3.2 1B Instruct",
                filename = "llama-3.2-1b-instruct-Q4_K_M.gguf",
                sizeMb = 800,
                ramRequiredMb = 2000,
                contextTokens = 4096,
                isVision = false,
                url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
            ),
            "llama-3.2-3b-instruct-Q4_K_M" to ModelSpec(
                name = "Llama 3.2 3B Instruct",
                filename = "llama-3.2-3b-instruct-Q4_K_M.gguf",
                sizeMb = 2000,
                ramRequiredMb = 4000,
                contextTokens = 4096,
                isVision = false,
                url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
            ),
            "gemma-2-2b-it-Q5_K_M" to ModelSpec(
                name = "Gemma 2 2B IT",
                filename = "gemma-2-2b-it-Q5_K_M.gguf",
                sizeMb = 1800,
                ramRequiredMb = 3500,
                contextTokens = 8192,
                isVision = false,
                url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q5_K_M.gguf"
            ),
            "qwen2.5-3b-instruct-Q4_K_M" to ModelSpec(
                name = "Qwen 2.5 3B Instruct",
                filename = "qwen2.5-3b-instruct-Q4_K_M.gguf",
                sizeMb = 2000,
                ramRequiredMb = 4000,
                contextTokens = 32768,
                isVision = false,
                url = "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf"
            ),
            "llama-3.2-11b-vision-instruct-Q4_K_M" to ModelSpec(
                name = "Llama 3.2 11B Vision",
                filename = "llama-3.2-11b-vision-instruct-Q4_K_M.gguf",
                sizeMb = 6400,
                ramRequiredMb = 12000,
                contextTokens = 4096,
                isVision = true,
                url = "https://huggingface.co/bartowski/Llama-3.2-11B-Vision-Instruct-GGUF/resolve/main/Llama-3.2-11B-Vision-Instruct-Q4_K_M.gguf"
            )
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _modelState = MutableStateFlow(ModelState.IDLE)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _activeModel = MutableStateFlow<String?>(null)
    val activeModel: StateFlow<String?> = _activeModel.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private var activeModelPath: String? = null

    enum class ModelState {
        IDLE,           // Sin modelo cargado
        DOWNLOADING,    // Descargando modelo
        VERIFYING,      // Verificando integridad
        LOADING,        // Cargando en RAM
        READY,          // Listo para inferencia
        INFERRING,      // Procesando inferencia
        ERROR           // Error
    }

    data class ModelSpec(
        val name: String,
        val filename: String,
        val sizeMb: Int,
        val ramRequiredMb: Int,
        val contextTokens: Int,
        val isVision: Boolean,
        val url: String
    )

    /**
     * Obtiene el directorio de modelos en almacenamiento interno.
     */
    fun getModelsDir(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Obtiene el directorio de cache del LLM.
     */
    fun getCacheDir(): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Verifica qué modelos ya están descargados en el dispositivo.
     */
    fun getDownloadedModels(): List<Pair<String, ModelSpec>> {
        val modelsDir = getModelsDir()
        return AVAILABLE_MODELS.entries
            .filter { File(modelsDir, it.value.filename).exists() }
            .map { it.key to it.value }
    }

    /**
     * Selecciona el mejor modelo según la RAM disponible.
     * Prioriza modelos más grandes si hay suficiente RAM.
     */
    fun selectBestModelForDevice(): String? {
        val freeRamMb = getAvailableRamMb()
        val downloaded = getDownloadedModels()

        if (downloaded.isEmpty()) {
            Log.w(TAG, "No hay modelos descargados")
            return null
        }

        // Ordenar por tamaño (más grande primero) y seleccionar el primero que quepa
        val sorted = downloaded.sortedByDescending { it.second.ramRequiredMb }
        val best = sorted.firstOrNull { it.second.ramRequiredMb < freeRamMb * 0.6 }

        if (best != null) {
            Log.i(TAG, "Modelo seleccionado: ${best.second.name} " +
                    "(RAM necesaria: ${best.second.ramRequiredMb}MB, libre: ${freeRamMb}MB)")
        } else {
            Log.w(TAG, "Ningún modelo descargado cabe en la RAM disponible (${freeRamMb}MB)")
        }

        return best?.first
    }

    /**
     * Descarga un modelo desde HuggingFace.
     * El progreso se reporta via downloadProgress flow.
     *
     * NOTA: Esta es la ÚNICA operación de red permitida en NubiaAgent.
     * Solo se usa para la descarga inicial del modelo, nunca para
     * enviar datos del usuario.
     */
    fun downloadModel(modelId: String): Deferred<File?> {
        val spec = AVAILABLE_MODELS[modelId]
            ?: throw IllegalArgumentException("Modelo desconocido: $modelId")

        return scope.async {
            try {
                _modelState.value = ModelState.DOWNLOADING
                _downloadProgress.value = 0f

                val targetFile = File(getModelsDir(), spec.filename)
                if (targetFile.exists()) {
                    Log.i(TAG, "Modelo ${spec.name} ya existe, verificando integridad")
                    if (verifyModelIntegrity(targetFile, spec.sizeMb)) {
                        _downloadProgress.value = 1f
                        _modelState.value = ModelState.IDLE
                        return@async targetFile
                    } else {
                        targetFile.delete()
                    }
                }

                Log.i(TAG, "Descargando ${spec.name} (${spec.sizeMb}MB)...")

                val connection = URL(spec.url).openConnection()
                connection.connect()
                val totalSize = connection.contentLengthLong

                connection.getInputStream().buffered().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Long = 0
                        var lastProgress = 0f

                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesRead += read

                            val progress = bytesRead.toFloat() / totalSize.toFloat()
                            if (progress - lastProgress > 0.01f) {
                                _downloadProgress.value = progress
                                lastProgress = progress
                                Log.d(TAG, "Descarga: ${(progress * 100).toInt()}%")
                            }
                        }
                    }
                }

                _downloadProgress.value = 1f
                _modelState.value = ModelState.VERIFYING

                if (verifyModelIntegrity(targetFile, spec.sizeMb)) {
                    Log.i(TAG, "Modelo ${spec.name} descargado y verificado exitosamente")
                    _modelState.value = ModelState.IDLE
                    targetFile
                } else {
                    Log.e(TAG, "Verificación fallida para ${spec.name}")
                    targetFile.delete()
                    _modelState.value = ModelState.ERROR
                    null
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Descarga cancelada")
                File(getModelsDir(), spec.filename).let { if (it.exists()) it.delete() }
                _modelState.value = ModelState.IDLE
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error descargando modelo", e)
                _modelState.value = ModelState.ERROR
                null
            }
        }
    }

    /**
     * Verifica la integridad básica de un archivo GGUF.
     * Comprueba la magia number "GGUF" al inicio del archivo
     * y que el tamaño sea razonable.
     */
    private fun verifyModelIntegrity(file: File, expectedSizeMb: Int): Boolean {
        if (!file.exists()) return false

        // Verificar tamaño (tolerancia del 20% por diferencias de cuantización)
        val sizeMb = file.length() / (1024 * 1024)
        val tolerance = expectedSizeMb * 0.2f
        if (sizeMb < expectedSizeMb - tolerance) {
            Log.w(TAG, "Tamaño insuficiente: ${sizeMb}MB vs ${expectedSizeMb}MB esperados")
            return false
        }

        // Verificar magia number GGUF (0x46475547 en little-endian)
        try {
            file.inputStream().use { stream ->
                val magic = ByteArray(4)
                stream.read(magic)
                val magicString = String(magic, Charsets.US_ASCII)
                if (magicString != "GGUF") {
                    Log.e(TAG, "Magia number inválido: '$magicString' (esperado: 'GGUF')")
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo header del modelo", e)
            return false
        }

        return true
    }

    /**
     * Establece el modelo activo para inferencia.
     */
    fun setActiveModel(modelId: String, modelPath: String) {
        _activeModel.value = modelId
        activeModelPath = modelPath
    }

    /**
     * Obtiene la ruta del modelo activo.
     */
    fun getActiveModelPath(): String? = activeModelPath

    /**
     * Elimina un modelo del almacenamiento local.
     */
    fun deleteModel(modelId: String): Boolean {
        val spec = AVAILABLE_MODELS[modelId] ?: return false
        val file = File(getModelsDir(), spec.filename)
        return if (file.exists()) {
            val deleted = file.delete()
            if (deleted) Log.i(TAG, "Modelo ${spec.name} eliminado")
            deleted
        } else {
            false
        }
    }

    /**
     * Obtiene la RAM disponible en MB.
     */
    private fun getAvailableRamMb(): Long {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Limpia la cache del LLM para liberar espacio.
     */
    fun clearCache() {
        getCacheDir().listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Cache del LLM limpiada")
    }

    fun destroy() {
        scope.cancel()
    }
}
