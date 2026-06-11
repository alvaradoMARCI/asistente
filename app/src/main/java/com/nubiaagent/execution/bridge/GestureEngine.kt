package com.nubiaagent.execution.bridge

import android.accessibilityservice.AccessibilityService.GestureDescription
import android.accessibilityservice.AccessibilityService.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * GestureEngine: Generador de Gestos Naturales para NubiaAgent.
 *
 * Produce trazos de gesto que imitan el comportamiento de un usuario humano
 * real, evitando patrones detectables por sistemas anti-bot. Todos los gestos
 * se generan como [GestureDescription] listos para inyectar vía
 * AccessibilityService.dispatchGesture().
 *
 * TÉCNICAS DE HUMANIZACIÓN:
 *
 *   1. **Micro-jitters**: Desviaciones aleatorias sub-píxel (±2px) en cada
 *      punto de control para simular el temblor natural del dedo.
 *
 *   2. **Interpolación Bézier**: Los swipes siguen curvas cúbicas de Bézier
 *      con puntos de control desplazados aleatoriamente, evitando líneas
 *      perfectamente rectas que son detectables como automatización.
 *
 *   3. **Timing Variable**: Cada paso del gesto incluye offsets aleatorios
 *      de ±30ms para evitar patrones temporales predecibles.
 *
 *   4. **Aceleración/Desaceleración**: Los swipes simulan la física natural
 *      del dedo: aceleran al inicio y desaceleran al final (easing).
 *
 *   5. **Arco Natural**: createNaturalSwipe() genera swipes con una curva
 *      ligera pero perceptible, como un dedo real que no traza en línea recta.
 *
 * FORMATO DE GESTO:
 * ```
 *  GestureDescription
 *    └── StrokeDescription(path, startTime, duration)
 *          └── Path (android.graphics.Path)
 *                └── moveTo / lineTo / quadTo / cubicTo
 * ```
 *
 * LIMITACIONES DE API:
 *   - GestureDescription requiere API 24+ (Android 7.0)
 *   - StrokeDescription con continúación requiere API 26+
 *   - Todas las operaciones son asíncronas via dispatchGesture()
 */
class GestureEngine {

    companion object {
        private const val TAG = "NubiaAgent/GestureEng"

        /** Desviación máxima de micro-jitter en píxeles */
        private const val JITTER_AMPLITUDE = 2.0f

        /** Offset máximo de timing en ms para cada paso */
        private const val TIMING_JITTER_MS = 30L

        /** Número mínimo de puntos intermedios en un swipe */
        private const val MIN_SWIPE_SEGMENTS = 8

        /** Número máximo de puntos intermedios en un swipe */
        private const val MAX_SWIPE_SEGMENTS = 16

        /** Duración por defecto de un tap en ms */
        private const val DEFAULT_TAP_DURATION_MS = 50L

        /** Duración por defecto de un long press en ms */
        private const val DEFAULT_LONG_PRESS_DURATION_MS = 600L

        /** Duración por defecto de un swipe en ms */
        private const val DEFAULT_SWIPE_DURATION_MS = 300L

        /** Factor de curvatura para swipes naturales (0.0 = recto, 1.0 = muy curvo) */
        private const val NATURAL_CURVE_FACTOR = 0.08f

        /** Distancia mínima de swipe en píxeles */
        private const val MIN_SWIPE_DISTANCE = 100

        /** Fracción de la pantalla usada para swipes direccionales (0-1) */
        private const val SWIPE_SCREEN_FRACTION = 0.4f
    }

    // ─────────────────────────────────────────────────────────────
    // GESTOS BÁSICOS
    // ─────────────────────────────────────────────────────────────

    /**
     * Crea un gesto de tap (toque corto) en las coordenadas especificadas.
     *
     * El tap consiste en:
     *   1. Presionar en (x, y) con micro-jitter aplicado
     *   2. Mantener por [DEFAULT_TAP_DURATION_MS] + jitter aleatorio
     *   3. Liberar
     *
     * El micro-jitter desplaza el punto de toque ±2px en ambas dimensiones
     * para simular la imprecisión natural del dedo humano.
     *
     * @param x Coordenada X del centro del toque
     * @param y Coordenada Y del centro del toque
     * @return GestureDescription listo para dispatchGesture()
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun createTap(x: Int, y: Int): GestureDescription {
        val jitterX = applyJitter(x.toFloat())
        val jitterY = applyJitter(y.toFloat())

        val path = Path().apply {
            moveTo(jitterX, jitterY)
            // Pequeña pausa en el mismo punto (simula presión del dedo)
            val microX = jitterX + Random.nextFloat() * 0.5f - 0.25f
            val microY = jitterY + Random.nextFloat() * 0.5f - 0.25f
            lineTo(microX, microY)
        }

        val duration = DEFAULT_TAP_DURATION_MS + applyTimingJitter()
        val stroke = StrokeDescription(path, 0L, duration)

        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }

    /**
     * Crea un gesto de long press (toque sostenido) en las coordenadas especificadas.
     *
     * El long press consiste en:
     *   1. Presionar en (x, y)
     *   2. Mantener presionado por [durationMs] con micro-movimientos sutiles
     *      (el dedo humano nunca permanece perfectamente estático)
     *   3. Liberar
     *
     * Durante la presión sostenida, se generan 3-5 micro-movimientos
     * aleatorios (±1px) para simular el temblor fisiológico del dedo.
     *
     * @param x Coordenada X del centro del toque
     * @param y Coordenada Y del centro del toque
     * @param durationMs Duración de la presión sostenida en ms
     * @return GestureDescription listo para dispatchGesture()
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun createLongPress(x: Int, y: Int, durationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS): GestureDescription {
        val startX = applyJitter(x.toFloat())
        val startY = applyJitter(y.toFloat())

        val path = Path().apply {
            moveTo(startX, startY)

            // Generar micro-movimientos durante la presión sostenida
            // El dedo humano nunca permanece perfectamente estático
            val microMoveCount = Random.nextInt(3, 6)
            val segmentDuration = durationMs.toFloat() / microMoveCount

            var currentX = startX
            var currentY = startY

            for (i in 0 until microMoveCount) {
                // Micro-desplazamiento de ±1px
                currentX += Random.nextFloat() * 2f - 1f
                currentY += Random.nextFloat() * 2f - 1f
                lineTo(currentX, currentY)
            }

            // Volver ligeramente al centro (no exactamente al punto original)
            lineTo(startX + Random.nextFloat() * 0.5f - 0.25f,
                   startY + Random.nextFloat() * 0.5f - 0.25f)
        }

        val totalDuration = durationMs + applyTimingJitter()
        val stroke = StrokeDescription(path, 0L, totalDuration)

        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }

    /**
     * Crea un gesto de swipe entre dos coordenadas con trayectoria recta.
     *
     * El swipe incluye:
     *   - Interpolación con easing (aceleración/desaceleración)
     *   - Micro-jitters en cada punto intermedio
     *   - Timing variable entre segmentos
     *
     * Para un swipe más natural con curva, usar [createNaturalSwipe].
     *
     * @param startX Coordenada X de inicio
     * @param startY Coordenada Y de inicio
     * @param endX Coordenada X de fin
     * @param endY Coordenada Y de fin
     * @param durationMs Duración del swipe en ms (0 = auto-calcular basado en distancia)
     * @return GestureDescription listo para dispatchGesture()
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun createSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long = 0L
    ): GestureDescription {
        val jStartX = applyJitter(startX.toFloat())
        val jStartY = applyJitter(startY.toFloat())
        val jEndX = applyJitter(endX.toFloat())
        val jEndY = applyJitter(endY.toFloat())

        val distance = distance(jStartX, jStartY, jEndX, jEndY)
        val segmentCount = calculateSegmentCount(distance)
        val duration = if (durationMs > 0) durationMs else calculateSwipeDuration(distance)

        val points = generateSwipePoints(
            jStartX, jStartY, jEndX, jEndY,
            segmentCount, applyEasing = true
        )

        val path = buildPathFromPoints(points)

        val totalDuration = duration + applyTimingJitter()
        val stroke = StrokeDescription(path, 0L, totalDuration)

        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }

    /**
     * Crea un gesto de swipe en una dirección cardinal.
     *
     * Calcula automáticamente las coordenadas de inicio y fin basándose
     * en las dimensiones de la pantalla y la dirección especificada.
     * El swipe se origina desde el centro de la pantalla (o coordenadas
     * opcionales) y se extiende un 40% de la dimensión relevante.
     *
     * Direcciones soportadas:
     *   - "up": Swipe hacia arriba (scroll hacia abajo en contenido)
     *   - "down": Swipe hacia abajo (scroll hacia arriba en contenido)
     *   - "left": Swipe hacia la izquierda
     *   - "right": Swipe hacia la derecha
     *
     * @param direction Dirección cardinal del swipe
     * @param screenWidth Ancho de la pantalla en píxeles
     * @param screenHeight Alto de la pantalla en píxeles
     * @param originX Coordenada X de inicio (-1 = centro horizontal)
     * @param originY Coordenada Y de inicio (-1 = centro vertical)
     * @return GestureDescription listo para dispatchGesture()
     * @throws IllegalArgumentException si la dirección no es reconocida
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun createSwipeDirection(
        direction: String,
        screenWidth: Int,
        screenHeight: Int,
        originX: Int = -1,
        originY: Int = -1
    ): GestureDescription {
        val startX = if (originX >= 0) originX else screenWidth / 2
        val startY = if (originY >= 0) originY else screenHeight / 2

        val swipeDistanceH = (screenWidth * SWIPE_SCREEN_FRACTION).toInt()
        val swipeDistanceV = (screenHeight * SWIPE_SCREEN_FRACTION).toInt()

        // Agregar variación aleatoria a la distancia (±15%)
        val distanceVariation = Random.nextFloat() * 0.3f - 0.15f // -0.15 a +0.15

        val (endX, endY) = when (direction.lowercase()) {
            "up" -> {
                val dist = (swipeDistanceV * (1 + distanceVariation)).toInt()
                Pair(startX + Random.nextInt(-20, 21), startY - dist)
            }
            "down" -> {
                val dist = (swipeDistanceV * (1 + distanceVariation)).toInt()
                Pair(startX + Random.nextInt(-20, 21), startY + dist)
            }
            "left" -> {
                val dist = (swipeDistanceH * (1 + distanceVariation)).toInt()
                Pair(startX - dist, startY + Random.nextInt(-20, 21))
            }
            "right" -> {
                val dist = (swipeDistanceH * (1 + distanceVariation)).toInt()
                Pair(startX + dist, startY + Random.nextInt(-20, 21))
            }
            else -> {
                Log.w(TAG, "Dirección no reconocida: $direction, usando 'up'")
                Pair(startX, startY - swipeDistanceV)
            }
        }

        // Usar swipe natural (con curva) para swipes direccionales
        return createNaturalSwipe(startX, startY, endX, endY)
    }

    /**
     * Crea un gesto de swipe con curva natural, simulando un dedo real.
     *
     * A diferencia de [createSwipe] que genera una trayectoria recta con jitters,
     * este método genera una curva Bézier cúbica con un punto de control
     * desplazado perpendicularmente a la línea de swipe. La magnitud del
     * desplazamiento es proporcional a la distancia del swipe, controlada
     * por [NATURAL_CURVE_FACTOR].
     *
     * El resultado es un swipe que sigue una ligera curva convexa o cóncava
     * (aleatoria), como lo haría un dedo humano que no puede trazar una
     * línea perfectamente recta.
     *
     * @param startX Coordenada X de inicio
     * @param startY Coordenada Y de inicio
     * @param endX Coordenada X de fin
     * @param endY Coordenada Y de fin
     * @param durationMs Duración del swipe en ms (0 = auto-calcular)
     * @return GestureDescription con curva Bézier natural
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun createNaturalSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long = 0L
    ): GestureDescription {
        val jStartX = applyJitter(startX.toFloat())
        val jStartY = applyJitter(startY.toFloat())
        val jEndX = applyJitter(endX.toFloat())
        val jEndY = applyJitter(endY.toFloat())

        val dist = distance(jStartX, jStartY, jEndX, jEndY)
        val duration = if (durationMs > 0) durationMs else calculateSwipeDuration(dist)

        // Calcular vector del swipe
        val dx = jEndX - jStartX
        val dy = jEndY - jStartY

        // Vector perpendicular normalizado (para desplazar el punto de control)
        val length = sqrt(dx * dx + dy * dy)
        val perpX = if (length > 0) -dy / length else 0f
        val perpY = if (length > 0) dx / length else 0f

        // Desplazamiento del punto de control (curvatura)
        // Signo aleatorio para curva convexa/cóncava
        val curveSign = if (Random.nextBoolean()) 1f else -1f
        val curveMagnitude = dist * NATURAL_CURVE_FACTOR * curveSign

        // Punto de control para Bézier cuadrática
        val midX = (jStartX + jEndX) / 2f + perpX * curveMagnitude
        val midY = (jStartY + jEndY) / 2f + perpY * curveMagnitude

        // Generar puntos a lo largo de la curva Bézier con easing
        val segmentCount = calculateSegmentCount(dist)
        val points = mutableListOf<PointF>()

        for (i in 0..segmentCount) {
            val rawT = i.toFloat() / segmentCount
            val t = applyEasing(rawT)

            // Interpolación cuadrática de Bézier: B(t) = (1-t)²P0 + 2(1-t)tP1 + t²P2
            val oneMinusT = 1f - t
            val px = oneMinusT * oneMinusT * jStartX + 2f * oneMinusT * t * midX + t * t * jEndX
            val py = oneMinusT * oneMinusT * jStartY + 2f * oneMinusT * t * midY + t * t * jEndY

            // Aplicar micro-jitter
            points.add(PointF(applyJitter(px), applyJitter(py)))
        }

        val path = buildPathFromPoints(points)

        val totalDuration = duration + applyTimingJitter()
        val stroke = StrokeDescription(path, 0L, totalDuration)

        return GestureDescription.Builder()
            .addStroke(stroke)
            .build()
    }

    /**
     * Crea una secuencia de gestos para escribir texto carácter por carácter.
     *
     * NOTA: Este método NO usa GestureDescription para tecleo individual (no es
     * posible generar eventos de teclado via dispatchGesture). En su lugar,
     * retorna un [TextGesturePlan] que contiene:
     *   - El texto a escribir (para ACTION_SET_TEXT o portapapeles)
     *   - Coordenadas opcionales del campo de texto para hacer tap primero
     *   - Estrategia recomendada de entrada
     *
     * El [ActionDispatcher] usará esta información para ejecutar la entrada
     * de texto a través de los mecanismos apropiados de AccessibilityService.
     *
     * Estrategias de entrada (en orden de preferencia):
     *   1. **ACTION_SET_TEXT**: Establece el texto directamente. Más rápido y confiable.
     *   2. **Clipboard paste**: Copia al portapapeles y pega. Funciona en WebViews.
     *   3. **Individual key events**: Simula teclas individuales (no implementado aquí,
     *      requiere InputConnection que no está disponible via AccessibilityService).
     *
     * @param text Texto a escribir
     * @param fieldX Coordenada X del campo de texto (para tap previo)
     * @param fieldY Coordenada Y del campo de texto (para tap previo)
     * @return TextGesturePlan con la estrategia y datos de entrada
     */
    fun createTypeText(
        text: String,
        fieldX: Int = -1,
        fieldY: Int = -1
    ): TextGesturePlan {
        // Decidir estrategia de entrada
        val strategy = when {
            text.length <= 3 -> InputStrategy.CLIPBOARD_PASTE
            text.contains("\n") -> InputStrategy.CLIPBOARD_PASTE
            else -> InputStrategy.ACTION_SET_TEXT
        }

        // Crear gesto de tap en el campo si se proporcionaron coordenadas
        val tapGesture: GestureDescription? = if (fieldX >= 0 && fieldY >= 0) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createTap(fieldX, fieldY)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo crear gesto de tap para campo", e)
                null
            }
        } else null

        return TextGesturePlan(
            text = text,
            inputStrategy = strategy,
            preTapGesture = tapGesture,
            fieldX = fieldX,
            fieldY = fieldY
        )
    }

    // ─────────────────────────────────────────────────────────────
    // GENERACIÓN DE PUNTOS DE SWIPE
    // ─────────────────────────────────────────────────────────────

    /**
     * Genera puntos intermedios para un swipe con easing aplicado.
     *
     * Divide la trayectoria en [segmentCount] segmentos y aplica
     * una función de easing (ease-in-out) para simular la aceleración
     * y desaceleración natural del dedo.
     *
     * @param startX Coordenada X de inicio
     * @param startY Coordenada Y de inicio
     * @param endX Coordenada X de fin
     * @param endY Coordenada Y de fin
     * @param segmentCount Número de segmentos intermedios
     * @param applyEasing Si true, aplica ease-in-out al progreso
     * @return Lista de puntos (PointF) incluyendo inicio y fin
     */
    private fun generateSwipePoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        segmentCount: Int,
        applyEasing: Boolean = true
    ): List<PointF> {
        val points = mutableListOf<PointF>()

        for (i in 0..segmentCount) {
            val rawT = i.toFloat() / segmentCount
            val t = if (applyEasing) applyEasing(rawT) else rawT

            val px = startX + (endX - startX) * t
            val py = startY + (endY - startY) * t

            // Aplicar micro-jitter
            points.add(PointF(applyJitter(px), applyJitter(py)))
        }

        return points
    }

    /**
     * Construye un [Path] de Android a partir de una lista de puntos.
     *
     * El path se construye con moveTo al primer punto y lineTo
     * a cada punto subsiguiente. Para mayor suavidad con muchos puntos,
     * se podría usar quadTo/cubicTo, pero con suficientes segmentos
     * lineTo produce resultados visualmente indistinguibles.
     *
     * @param points Lista de puntos PointF ordenados
     * @return Path de Android listo para StrokeDescription
     */
    private fun buildPathFromPoints(points: List<PointF>): Path {
        val path = Path()

        if (points.isEmpty()) return path

        path.moveTo(points[0].x, points[0].y)

        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
        }

        return path
    }

    // ─────────────────────────────────────────────────────────────
    // FUNCIONES DE HUMANIZACIÓN
    // ─────────────────────────────────────────────────────────────

    /**
     * Aplica micro-jitter a una coordenada.
     *
     * Añade una desviación aleatoria uniforme en el rango
     * [-JITTER_AMPLITUDE, +JITTER_AMPLITUDE] píxeles.
     *
     * El jitter simula el temblor fisiológico del dedo humano,
     * que produce desviaciones sub-píxel incluso en toques
     * deliberados y precisos.
     *
     * @param coord Coordenada original
     * @return Coordenada con jitter aplicado
     */
    private fun applyJitter(coord: Float): Float {
        return coord + (Random.nextFloat() * 2f - 1f) * JITTER_AMPLITUDE
    }

    /**
     * Aplica jitter de timing aleatorio.
     *
     * Retorna un offset en milisegundos en el rango
     * [-TIMING_JITTER_MS, +TIMING_JITTER_MS] para variar
     * la duración de los gestos y evitar patrones temporales predecibles.
     *
     * @return Offset de timing en ms (puede ser negativo)
     */
    private fun applyTimingJitter(): Long {
        return Random.nextLong(-TIMING_JITTER_MS, TIMING_JITTER_MS + 1)
    }

    /**
     * Aplica función de easing ease-in-out a un parámetro t ∈ [0, 1].
     *
     * La función de easing simula la aceleración y desaceleración natural
     * del dedo humano durante un swipe:
     *   - Al inicio (t≈0): el dedo acelera desde reposo
     *   - En el medio (t≈0.5): velocidad máxima
     *   - Al final (t≈1): el dedo desacelera para detenerse
     *
     * Se usa la función smoothstep estándar: t² × (3 - 2t)
     *
     * @param t Parámetro de progreso lineal [0, 1]
     * @return Parámetro de progreso con easing aplicado [0, 1]
     */
    private fun applyEasing(t: Float): Float {
        // Smoothstep: 3t² - 2t³
        return t * t * (3f - 2f * t)
    }

    /**
     * Calcula el número de segmentos para un swipe basándose en la distancia.
     *
     * Más segmentos = trayectoria más suave pero más eventos de input.
     * Menos segmentos = trayectoria más angular pero mejor rendimiento.
     *
     * La fórmula escala linealmente con la distancia, limitada
     * por [MIN_SWIPE_SEGMENTS] y [MAX_SWIPE_SEGMENTS].
     *
     * @param distance Distancia del swipe en píxeles
     * @return Número de segmentos intermedios
     */
    private fun calculateSegmentCount(distance: Float): Int {
        // Aproximadamente 1 segmento por cada 50px de distancia
        val count = (distance / 50f).toInt().coerceIn(MIN_SWIPE_SEGMENTS, MAX_SWIPE_SEGMENTS)
        // Agregar variación aleatoria ±2
        return (count + Random.nextInt(-2, 3)).coerceIn(MIN_SWIPE_SEGMENTS, MAX_SWIPE_SEGMENTS)
    }

    /**
     * Calcula la duración de un swipe basándose en la distancia.
     *
     * Simula la velocidad variable del dedo humano:
     *   - Swipes cortos (< 200px): más lentos, ~400ms
     *   - Swipes medios (200-500px): velocidad media, ~300ms
     *   - Swipes largos (> 500px): más rápidos proporcionalmente
     *
     * Se añade un jitter de ±50ms para variación natural.
     *
     * @param distance Distancia del swipe en píxeles
     * @return Duración en milisegundos
     */
    private fun calculateSwipeDuration(distance: Float): Long {
        val baseDuration = when {
            distance < 200 -> 400L
            distance < 500 -> 300L
            distance < 1000 -> 250L
            else -> 200L
        }

        // Escalar ligeramente con la distancia
        val scaledDuration = baseDuration + (distance / 10f).toLong()

        // Jitter de ±50ms
        val jitter = Random.nextLong(-50, 51)
        return (scaledDuration + jitter).coerceAtLeast(100L)
    }

    /**
     * Calcula la distancia euclidiana entre dos puntos.
     *
     * @param x1 Coordenada X del punto 1
     * @param y1 Coordenada Y del punto 1
     * @param x2 Coordenada X del punto 2
     * @param y2 Coordenada Y del punto 2
     * @return Distancia en píxeles
     */
    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}

// ─────────────────────────────────────────────────────────────
// TIPOS AUXILIARES
// ─────────────────────────────────────────────────────────────

/**
 * Estrategia de entrada de texto para [GestureEngine.createTypeText].
 */
enum class InputStrategy {
    /** Establece texto directamente via ACTION_SET_TEXT (más rápido, confiable) */
    ACTION_SET_TEXT,

    /** Copia al portapapeles y pega via ACTION_PASTE (funciona en WebViews) */
    CLIPBOARD_PASTE,

    /** Simula teclas individuales via InputConnection (no disponible via AccessibilityService) */
    INDIVIDUAL_KEYS
}

/**
 * Plan de gestos para entrada de texto.
 *
 * Contiene la información necesaria para que [ActionDispatcher] ejecute
 * la entrada de texto usando la estrategia más apropiada.
 *
 * @property text Texto a escribir
 * @property inputStrategy Estrategia de entrada recomendada
 * @property preTapGesture Gesto de tap opcional para activar el campo antes de escribir
 * @property fieldX Coordenada X del campo (-1 si no se proporcionó)
 * @property fieldY Coordenada Y del campo (-1 si no se proporcionó)
 */
data class TextGesturePlan(
    val text: String,
    val inputStrategy: InputStrategy,
    val preTapGesture: GestureDescription?,
    val fieldX: Int,
    val fieldY: Int
)
