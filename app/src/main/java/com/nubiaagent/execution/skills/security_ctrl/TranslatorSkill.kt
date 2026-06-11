package com.nubiaagent.execution.skills.security_ctrl

import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * TranslatorSkill: Traducción offline de texto para NubiaAgent.
 *
 * Implementa traducción local sin depender de APIs externas.
 * En la versión actual usa un diccionario estático para las
 * traducciones más comunes español↔inglés. En producción,
 * se integraría con un modelo de traducción local (como
 * NLLB o MarianMT via ONNX Runtime) o con el AI Translate
 * nativo del Nubia Neo 3 5G.
 *
 * Habilidades:
 * - translate(text, from, to): Traducción de texto
 * - detectLanguage(text): Detección de idioma
 * - getSupportedLanguages(): Lista de idiomas soportados
 *
 * RESTRICCIÓN: Sin conexiones externas. Todo el procesamiento es local.
 */
class TranslatorSkill(private val context: Context) {

    companion object {
        private const val TAG = "NubiaAgent/Traductor"
    }

    /**
     * Traduce texto entre idiomas soportados.
     */
    fun translate(text: String, from: String = "es", to: String = "en"): Result<String> {
        return try {
            if (from == to) return Result.success(text)

            val result = when ("$from-$to") {
                "es-en" -> translateEsToEn(text)
                "en-es" -> translateEnToEs(text)
                else -> {
                    Log.w(TAG, "Par de idiomas no soportado: $from → $to")
                    return Result.failure(UnsupportedOperationException(
                        "Traducción $from → $to no disponible. Idiomas soportados: es, en"
                    ))
                }
            }

            Log.d(TAG, "Traducido ($from→$to): '${text.take(30)}...' → '${result.take(30)}...'")
            Result.success(result)

        } catch (e: Exception) {
            Log.e(TAG, "Error traduciendo texto", e)
            Result.failure(e)
        }
    }

    /**
     * Detecta el idioma de un texto.
     */
    fun detectLanguage(text: String): Result<String> {
        val spanishMarkers = listOf(" el ", " la ", " los ", " las ", " de ", " en ", " que ", " por ", " con ", " para ", " una ", " como ")
        val englishMarkers = listOf(" the ", " is ", " are ", " and ", " or ", " for ", " in ", " on ", " with ", " this ", " that ", " have ")

        val lower = " $text ".lowercase()
        val esCount = spanishMarkers.count { lower.contains(it) }
        val enCount = englishMarkers.count { lower.contains(it) }

        return Result.success(when {
            esCount > enCount -> "es"
            enCount > esCount -> "en"
            else -> "desconocido"
        })
    }

    fun getSupportedLanguages(): List<LanguageInfo> {
        return listOf(
            LanguageInfo("es", "Español", "🇪🇸"),
            LanguageInfo("en", "Inglés", "🇺🇸")
        )
    }

    data class LanguageInfo(val code: String, val name: String, val flag: String)

    // ==================== DICCIONARIO ES→EN ====================

    private val esToEn = mapOf(
        // Saludos y cortesía
        "hola" to "hello", "buenos días" to "good morning", "buenas tardes" to "good afternoon",
        "buenas noches" to "good night", "adiós" to "goodbye", "gracias" to "thank you",
        "por favor" to "please", "perdón" to "sorry", "de nada" to "you're welcome",
        "sí" to "yes", "no" to "no",

        // Pronombres
        "yo" to "I", "tú" to "you", "él" to "he", "ella" to "she", "nosotros" to "we",
        "ellos" to "they", "mi" to "my", "tu" to "your", "su" to "his/her",

        // Verbos comunes
        "ser" to "be", "estar" to "be", "tener" to "have", "hacer" to "do/make",
        "poder" to "can", "decir" to "say", "ir" to "go", "ver" to "see",
        "dar" to "give", "saber" to "know", "querer" to "want", "llegar" to "arrive",
        "pasar" to "pass", "deber" to "should", "poner" to "put", "parecer" to "seem",
        "quedar" to "remain", "creer" to "believe", "hablar" to "speak", "llevar" to "carry",
        "escribir" to "write", "leer" to "read", "comer" to "eat", "beber" to "drink",
        "dormir" to "sleep", "vivir" to "live", "trabajar" to "work", "estudiar" to "study",
        "comprar" to "buy", "vender" to "sell", "enviar" to "send", "recibir" to "receive",
        "abrir" to "open", "cerrar" to "close", "empezar" to "start", "terminar" to "finish",
        "necesitar" to "need", "buscar" to "search", "encontrar" to "find", "pensar" to "think",
        "sentir" to "feel", "recordar" to "remember", "olvidar" to "forget", "ayudar" to "help",

        // Sustantivos comunes
        "tiempo" to "time", "año" to "year", "persona" to "person", "día" to "day",
        "cosa" to "thing", "mundo" to "world", "vida" to "life", "mano" to "hand",
        "parte" to "part", "lugar" to "place", "caso" to "case", "semana" to "week",
        "compañía" to "company", "sistema" to "system", "programa" to "program",
        "gobierno" to "government", "número" to "number", "noche" to "night",
        "punto" to "point", "casa" to "house", "agua" to "water", "ciudad" to "city",
        "familia" to "family", "amigo" to "friend", "trabajo" to "work/job",
        "escuela" to "school", "país" to "country", "problema" to "problem",
        "historia" to "history/story", "madre" to "mother", "padre" to "father",
        "hijo" to "son", "hija" to "daughter", "hermano" to "brother", "hermana" to "sister",
        "dinero" to "money", "comida" to "food", "salud" to "health",
        "teléfono" to "phone", "mensaje" to "message", "nombre" to "name",
        "pregunta" to "question", "respuesta" to "answer",

        // Adjetivos
        "grande" to "big", "pequeño" to "small", "bueno" to "good", "malo" to "bad",
        "nuevo" to "new", "viejo" to "old", "importante" to "important", "diferente" to "different",
        "posible" to "possible", "necesario" to "necessary", "difícil" to "difficult",
        "fácil" to "easy", "rápido" to "fast", "lento" to "slow", "caliente" to "hot",
        "frío" to "cold", "feliz" to "happy", "triste" to "sad", "rico" to "rich/tasty",

        // Tecnología
        "computadora" to "computer", "internet" to "internet", "aplicación" to "application",
        "datos" to "data", "archivo" to "file", "contraseña" to "password",
        "cuenta" to "account", "correo electrónico" to "email", "pantalla" to "screen",
        "batería" to "battery", "cargador" to "charger", "red" to "network",
        "seguridad" to "security", "privacidad" to "privacy", "inteligencia artificial" to "artificial intelligence",
        "asistente" to "assistant", "memoria" to "memory", "procesador" to "processor"
    )

    private val enToEs = esToEn.entries.associate { (k, v) -> v to k }

    // ==================== TRADUCCIÓN ====================

    private fun translateEsToEn(text: String): String {
        return translateWithDictionary(text, esToEn)
    }

    private fun translateEnToEs(text: String): String {
        return translateWithDictionary(text, enToEs)
    }

    /**
     * Traducción basada en diccionario con manejo de frases.
     * Prioriza frases largas antes que palabras individuales.
     */
    private fun translateWithDictionary(text: String, dictionary: Map<String, String>): String {
        var result = text.lowercase().trim()

        // Ordenar por longitud descendente para priorizar frases
        val sortedEntries = dictionary.entries.sortedByDescending { it.key.length }

        for ((source, target) in sortedEntries) {
            if (result.contains(source)) {
                result = result.replace(source, target)
            }
        }

        // Capitalizar primera letra
        return result.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }
}
