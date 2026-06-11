package com.nubiaagent.execution.skills.security_ctrl

import android.content.Context
import android.util.Log
import java.net.IDN
import java.util.regex.Pattern

/**
 * Habilidad de detección de estafas y phishing para NubiaAgent.
 * Analiza notificaciones, SMS y textos en busca de indicadores de fraude
 * usando un sistema basado en reglas (sin API en la nube).
 *
 * Tipos de estafa detectados:
 * - Phishing (suplantación de identidad)
 * - Estafa de paquetes/entregas
 * - Estafa romántica
 * - Estafa de inversión
 * - Estafa general
 */
class ScamDetector(private val context: Context) {

    companion object {
        private const val TAG = "ScamDetector"

        // ─── Dominios sospechosos ──────────────────────────────────────────

        /** Dominios frecuentemente usados en estafas de phishing */
        val SUSPICIOUS_DOMAINS = listOf(
            // Bancos falsos - variaciones con errores ortográficos
            "banc0", "ban-co", "bancol", "bbvanet", "bbva-seguro",
            "santander-seguro", "caixaseguro", "caixa-verificar",
            "bankia-seguro", "sabadell-seguro",
            // Servicios falsos
            "corre0s", "corr3os", "seur-seguro", "mrw-seguro", "dhl-seguro",
            "fedex-seguro", "ups-seguro", "ninja-seguro",
            // Plataformas falsas
            "amaz0n", "amazom", "amzn-verify", "amazon-seguro",
            "netflix-seguro", "spotify-premium", "apple-id-verify",
            "google-verify", "microsoft-seguro",
            // Proveedores falsos
            "movistar-premio", "vodafone-premio", "orange-premio",
            "yoigo-premio", "telefonica-premio",
            // Genéricos sospechosos
            "verify-account", "secure-login", "account-confirm",
            "update-info", "confirm-identity", "security-alert",
            "login-secure", "verify-now", "urgent-verify",
            "premio-ganador", "loteria", "premio-seguro",
            "ganar-dinero", "dinero-facil", "oferta-especial"
        )

        // ─── Frases de urgencia ────────────────────────────────────────────

        /** Frases que indican urgencia artificial, típicas de estafas */
        val URGENCY_PHRASES = listOf(
            // Urgencia temporal
            "actúe ahora", "actue ahora", "hazlo ya", "hazlo ahora",
            "urgente", "urgentemente", "con urgencia",
            "inmediatamente", "de inmediato", "ya mismo",
            "no espere", "no esperes", "no esperes más", "no espere mas",
            "límite de tiempo", "limite de tiempo", "tiempo limitado",
            "expira hoy", "expira mañana", "caduca hoy", "vence hoy",
            "última oportunidad", "ultima oportunidad",
            "último aviso", "ultimo aviso",
            "solo por hoy", "sólo por hoy", "solo hoy", "solo ahora",
            "antes de que sea tarde", "no pierdas esta oportunidad",
            "oferta por tiempo limitado", "oferta exclusiva",
            "si no actúas", "si no actúa", "si no respondes", "si no responde",
            "su cuenta será", "su cuenta sera", "su cuenta será cerrada",
            "será suspendida", "sera suspendida", "será cancelada", "sera cancelada",
            "será bloqueada", "sera bloqueada",
            "en las próximas 24 horas", "en las proximas 24 horas",
            "en las próximas 48 horas", "en las proximas 48 horas",
            "en las próximas horas", "en las proximas horas",
            "antes de 24 horas", "antes de 48 horas",
            "tiene 24 horas", "tienes 24 horas",
            "últimas horas", "ultimas horas",
            "mañana será tarde", "manana sera tarde",
            // Urgencia financiera
            "cobro pendiente", "cargo pendiente", "pago pendiente",
            "deuda pendiente", "saldo deudor", "saldo negativo",
            "cargo no autorizado", "movimiento sospechoso",
            "transferencia urgente", "pago inmediato",
            // Amenazas
            "será reportado", "sera reportado", "acciones legales",
            "demanda", "juicio", "embargo",
            "sanción", "sancion", "multa", "penalidad"
        )

        // ─── Solicitudes de información personal ────────────────────────────

        /** Patrones que solicitan información personal sensible */
        val PERSONAL_INFO_REQUESTS = listOf(
            // Credenciales
            "verifique su contraseña", "verifique su contrasena",
            "confirme su contraseña", "confirme su contrasena",
            "actualice su contraseña", "actualice su contrasena",
            "cambie su contraseña", "cambie su contrasena",
            "ingrese su contraseña", "ingrese su contrasena",
            "introduzca su contraseña", "introduzca su contrasena",
            "su usuario y contraseña", "su usuario y contrasena",
            "datos de acceso", "credenciales",
            // Datos bancarios
            "número de tarjeta", "numero de tarjeta",
            "fecha de vencimiento", "cvv", "cvc",
            "número de cuenta", "numero de cuenta",
            "IBAN", "SWIFT", "número de cuenta bancaria",
            "datos bancarios", "información bancaria", "informacion bancaria",
            "clave bancaria", "código bancario", "codigo bancario",
            "coordenadas bancarias",
            // Identidad
            "DNI", "número de identidad", "numero de identidad",
            "número de documento", "numero de documento",
            "número de seguro social", "numero de seguro social",
            "pasaporte", "licencia de conducir",
            "fecha de nacimiento", "lugar de nacimiento",
            "nombre completo", "dirección completa", "direccion completa",
            // Contacto
            "número de teléfono", "numero de telefono",
            "correo electrónico", "correo electronico",
            "dirección", "direccion",
            // PIN y códigos
            "código de verificación", "codigo de verificacion",
            "PIN", "código de seguridad", "codigo de seguridad",
            "código de confirmación", "codigo de confirmacion",
            "token", "código temporal", "codigo temporal",
            // Solicitud genérica
            "confirme sus datos", "verifique sus datos",
            "actualice sus datos", "complete sus datos",
            "complete el formulario", "complete la información",
            "complete la informacion", "verifique su identidad",
            "confirme su identidad", "actualice su información",
            "actualice su informacion"
        )

        // ─── Indicadores de phishing ────────────────────────────────────────

        private val PHISHING_INDICATORS = listOf(
            // Suplantación de entidades
            Regex("(?:banco|bank|entidad|entidad financiera)\\s+(?:le|te)\\s+(?:informa|comunica|escribe)", RegexOption.IGNORE_CASE),
            Regex("(?:seguridad|departamento\\s+de\\s+seguridad)\\s+del\\s+(?:banco|sistema)", RegexOption.IGNORE_CASE),
            Regex("(?:equipo|centro)\\s+de\\s+(?:seguridad|soporte|verificación)", RegexOption.IGNORE_CASE),
            // Enlaces sospechosos
            Regex("https?://[^\\s]*(?:bit\\.ly|tinyurl|t\\.co|ow\\.ly|is\\.gd|rb\\.gy)[^\\s]*", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s]*\\d{4,}[^\\s]*", RegexOption.IGNORE_CASE),  // URL con muchos números
            Regex("(?:clic|click|pulse|pulsar|pulse\\s+aquí|pulse\\s+aquí|pincha)\\s+(?:aquí|aqui|en\\s+el\\s+enlace|en\\s+el\\s+link)", RegexOption.IGNORE_CASE),
            Regex("(?:abra|abre|ve\\s+a)\\s+(?:este|el)\\s+(?:enlace|link|vínculo)", RegexOption.IGNORE_CASE),
            // Inconsistencias
            Regex("(?:estimado|querido)\\s+(?:cliente|usuario|socio|miembro)", RegexOption.IGNORE_CASE),
            Regex("(?:no\\s+responda|no\\s+responder)\\s+(?:a|este)\\s+(?:correo|mensaje|email)", RegexOption.IGNORE_CASE),
            // Verificación falsa
            Regex("(?:verificar|verificación|confirmar|confirmación|validar|validación)\\s+(?:su|tu)\\s+(?:cuenta|perfil|identidad)", RegexOption.IGNORE_CASE),
            Regex("(?:ha\\s+sido|sido)\\s+(?:bloqueada|suspendida|restringida|comprometida)", RegexOption.IGNORE_CASE),
            Regex("(?:inicio\\s+de\\s+sesión|acceso|ingreso)\\s+(?:no\\s+autorizado|sospechoso|desde\\s+nuevo\\s+dispositivo)", RegexOption.IGNORE_CASE)
        )

        // ─── Indicadores de estafa de paquetes ─────────────────────────────

        private val DELIVERY_SCAM_INDICATORS = listOf(
            Regex("(?:paquete|envío|envio|paquet|bulto|mercancía|mercancia)\\s+(?:retenido|retenido|retenido\\s+en\\s+aduanas|en\\s+aduanas)", RegexOption.IGNORE_CASE),
            Regex("(?:aduanas|aduana)\\s+(?:requiere|solicita|pide|necesita)\\s+(?:pago|tarifa|tasa|impuesto|arancel)", RegexOption.IGNORE_CASE),
            Regex("(?:pagar|pague|abone)\\s+(?:tasa|tarifa|impuesto|arancel|cuota|cantidad|importe)\\s+(?:de\\s+)?(?:aduanas|liberación|liberacion|entrega)", RegexOption.IGNORE_CASE),
            Regex("(?:número|numero|no\\.)\\s+(?:de\\s+)?(?:segumiento|rastreo|tracking)", RegexOption.IGNORE_CASE),
            Regex("(?:Correos|SEUR|MRW|DHL|FedEx|UPS|Nacex|InPost|GLS)\\s+(?:le|te)\\s+(?:informa|comunica|escribe)", RegexOption.IGNORE_CASE),
            Regex("(?:entrega|reparto)\\s+(?:fallida|no\\s+realizada|pendiente|no\\s+completada)", RegexOption.IGNORE_CASE),
            Regex("(?:no\\s+pudimos|imposible)\\s+(?:entregar|realizar\\s+la\\s+entrega|completar\\s+la\\s+entrega)", RegexOption.IGNORE_CASE),
            Regex("(?:recoger|recoge|reclamar)\\s+(?:su|tu)\\s+(?:paquete|envío|envio)", RegexOption.IGNORE_CASE),
            Regex("pag[ao]\\s+\\d+[,.]?\\d*\\s*(?:€|EUR|euro)", RegexOption.IGNORE_CASE),  // Pago específico
            Regex("(?:pendiente\\s+de\\s+pago|pago\\s+pendiente|debe\\s+abonar|debes\\s+pagar)", RegexOption.IGNORE_CASE)
        )

        // ─── Indicadores de estafa romántica ────────────────────────────────

        private val ROMANCE_SCAM_INDICATORS = listOf(
            Regex("(?:te\\s+amo|te\\s+quiero|mi\\s+amor|cariño|corazón|príncipe|princesa|alma\\s+gemela)", RegexOption.IGNORE_CASE),
            Regex("(?:heredero|heredera|herencia|fortuna|petrolero|militar|soldado|general|doctor[a]?)\\s+(?:de|en)\\s+(?:África|Nigeria|Ghana|Costa\\s+de\\s+Marfil|Senegal|Sierra\\s+Leona)", RegexOption.IGNORE_CASE),
            Regex("(?:enviar|transferir|mandar|envíame|mandame)\\s+(?:dinero|fondos|ayuda\\s+económica|ayuda\\s+economica|préstamo|prestamo)", RegexOption.IGNORE_CASE),
            Regex("(?:tarjeta\\s+de\\s+regalo|gift\\s+card|tarjeta\\s+Steam|tarjeta\\s+Google\\s+Play|tarjeta\\s+iTunes|tarjeta\\s+Amazon)", RegexOption.IGNORE_CASE),
            Regex("(?:conocernos|conocerte|verte|estar\\s+contigo|juntos|por\\s+siempre)\\s+(?:pero|sin\\s+embargo)\\s+(?:necesito|necesitaría|preciso|requiero)", RegexOption.IGNORE_CASE),
            Regex("(?:situación|situacion|problema|emergencia|dificultad)\\s+(?:económica|economica|financiera)", RegexOption.IGNORE_CASE),
            Regex("(?:no\\s+puedo\\s+(?:acceder|entrar)|bloqueado|congelado)\\s+(?:a\\s+)?(?:mi\\s+)?(?:cuenta|banco|dinero|fondos)", RegexOption.IGNORE_CASE),
            Regex("(?:video\\s+llamada|videollamada|cámara|camara)\\s+(?:rota|dañada|no\\s+funciona|averiada)", RegexOption.IGNORE_CASE),
            Regex("(?:estoy|me\\s+encuentro)\\s+(?:en\\s+el\\s+)?(?:extranjero|África|Nigeria|Ghana|Siria|zona\\s+de\\s+conflicto)", RegexOption.IGNORE_CASE),
            Regex("(?:prometo|te\\s+prometo|juro|te\\s+juro)\\s+(?:devolver|pagar|compensar|recompensar)", RegexOption.IGNORE_CASE)
        )

        // ─── Indicadores de estafa de inversión ────────────────────────────

        private val INVESTMENT_SCAM_INDICATORS = listOf(
            Regex("(?:retorno|rendimiento|rentabilidad|ganancia|beneficio|interés|interes)\\s+(?:garantizado|asegurado|del\\s+\\d+%|del\\s+\\d+\\s+por\\s+ciento)", RegexOption.IGNORE_CASE),
            Regex("(?:duplicar|triplicar|multiplicar)\\s+(?:su|tu)\\s+(?:dinero|inversión|inversion|capital|ahorro)", RegexOption.IGNORE_CASE),
            Regex("(?:criptomoneda|crypto|bitcoin|btc|ethereum|eth|binance|coinbase|trading|forex)\\s+(?:oportunidad|rentable|seguro|garantizado)", RegexOption.IGNORE_CASE),
            Regex("(?:inversión|inversion|oportunidad)\\s+(?:exclusiva|única|unica|limitada|vip|especial|privilegiada)", RegexOption.IGNORE_CASE),
            Regex("(?:sin\\s+riesgo|cero\\s+riesgo|riesgo\\s+cero|totalmente\\s+seguro|100%\\s+seguro|seguro\\s+al\\s+100%)", RegexOption.IGNORE_CASE),
            Regex("(?:señal|signal|tip|recomendación|recomendacion)\\s+(?:de\\s+)?(?:trading|inversión|inversion|crypto|cripto)", RegexOption.IGNORE_CASE),
            Regex("(?:robot|bot|algoritmo|inteligencia\\s+artificial|ia|ai)\\s+(?:que\\s+)?(?:opera|invierte|negocia|hace\\s+trading|genera)", RegexOption.IGNORE_CASE),
            Regex("(?:esquema|plan|sistema|método|metodo|estrategia)\\s+(?:piramidal|ponzi|de\\s+inversión|de\\s+inversion)", RegexOption.IGNORE_CASE),
            Regex("(?:referidos|referir|invitar|reclutar|afiliar|multinivel|mlm|networking)\\s+(?:y\\s+gana|para\\s+ganar|comisiones)", RegexOption.IGNORE_CASE),
            Regex("(?:retiro|retirada|withdrawal|sacar\\s+dinero)\\s+(?:mínimo|minimo|inmediato|instantáneo|instantaneo|sin\\s+espera)", RegexOption.IGNORE_CASE),
            Regex("(?:depósito|deposito|inversión|inversion|aporta|aportación|aportacion)\\s+(?:mínimo|minimo)\\s+de\\s+\\d+", RegexOption.IGNORE_CASE)
        )

        // ─── Patrones de URLs sospechosas ──────────────────────────────────

        private val SUSPICIOUS_URL_PATTERNS = listOf(
            Regex("https?://[^\\s]*@(?:\\d{1,3}\\.){3}\\d{1,3}", RegexOption.IGNORE_CASE),  // IP como host
            Regex("https?://[^\\s]*\\.[a-z]{6,}/", RegexOption.IGNORE_CASE),  // TLD inusualmente largo
            Regex("https?://[^\\s]*(?:-secure|-verify|-login|-account|-update|-confirm)[^\\s]*\\.(?!com|es|org|net|edu|gov)[a-z]{2,}", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s]*(?:\\d{8,})[^\\s]*", RegexOption.IGNORE_CASE)  // Números largos en URL
        )

        // ─── Patrones de números de seguimiento falsos ─────────────────────

        private val FAKE_TRACKING_PATTERN = Regex(
            "(?:número|numero|no\\.?|ref\\.?|código|codigo)\\s+(?:de\\s+)?(?:segumiento|rastreo|tracking|referencia)\\s*:?\\s*[A-Z]{2}\\d{9,18}",
            RegexOption.IGNORE_CASE
        )

        // ─── Puntuación por indicador ──────────────────────────────────────

        private const val SCORE_URGENCY = 0.15f
        private const val SCORE_PERSONAL_INFO = 0.20f
        private const val SCORE_PHISHING = 0.18f
        private const val SCORE_DELIVERY = 0.18f
        private const val SCORE_ROMANCE = 0.18f
        private const val SCORE_INVESTMENT = 0.18f
        private const val SCORE_SUSPICIOUS_DOMAIN = 0.22f
        private const val SCORE_SUSPICIOUS_URL = 0.20f
        private const val SCORE_FAKE_TRACKING = 0.25f
        private const val SCORE_IMPERSONATION = 0.16f
    }

    /**
     * Resultado del análisis de estafa.
     */
    data class ScamAnalysis(
        val isScam: Boolean,
        val confidence: Float,       // 0.0 a 1.0
        val scamType: String,        // "phishing", "estafa_paquetes", "estafa_romántica", "estafa_inversión", "estafa_general", "legítimo"
        val indicators: List<String>, // Descripción de los indicadores encontrados
        val recommendation: String    // Recomendación para el usuario
    )

    // ─── Análisis de notificaciones ────────────────────────────────────────

    /**
     * Analiza una notificación del sistema en busca de indicadores de estafa.
     *
     * @param title Título de la notificación
     * @param text Texto/cuerpo de la notificación
     * @param packageName Nombre del paquete de la app que envió la notificación
     * @return Result con ScamAnalysis
     */
    fun analyzeNotification(title: String, text: String, packageName: String): Result<ScamAnalysis> {
        val fullText = "$title $text"
        val indicators = mutableListOf<String>()
        var totalScore = 0f
        var dominantType = "estafa_general"

        Log.i(TAG, "Analizando notificación de $packageName")

        // 1. Verificar urgencia
        val urgencyMatches = checkUrgency(fullText)
        if (urgencyMatches.isNotEmpty()) {
            totalScore += SCORE_URGENCY * minOf(urgencyMatches.size, 3)
            indicators.addAll(urgencyMatches.map { "⚠️ Lenguaje urgente: «$it»" })
        }

        // 2. Verificar solicitudes de información personal
        val personalInfoMatches = checkPersonalInfo(fullText)
        if (personalInfoMatches.isNotEmpty()) {
            totalScore += SCORE_PERSONAL_INFO * minOf(personalInfoMatches.size, 3)
            indicators.addAll(personalInfoMatches.map { "🔒 Solicitud de datos personales: «$it»" })
        }

        // 3. Verificar indicadores de phishing
        val phishingMatches = checkPhishing(fullText)
        if (phishingMatches.isNotEmpty()) {
            totalScore += SCORE_PHISHING * minOf(phishingMatches.size, 3)
            indicators.addAll(phishingMatches.map { "🎣 Indicador de phishing: «$it»" })
            dominantType = "phishing"
        }

        // 4. Verificar estafa de paquetes
        val deliveryMatches = checkDelivery(fullText)
        if (deliveryMatches.isNotEmpty()) {
            totalScore += SCORE_DELIVERY * minOf(deliveryMatches.size, 3)
            indicators.addAll(deliveryMatches.map { "📦 Indicador de estafa de paquetes: «$it»" })
            if (dominantType == "estafa_general") dominantType = "estafa_paquetes"
        }

        // 5. Verificar estafa romántica
        val romanceMatches = checkRomance(fullText)
        if (romanceMatches.isNotEmpty()) {
            totalScore += SCORE_ROMANCE * minOf(romanceMatches.size, 3)
            indicators.addAll(romanceMatches.map { "💕 Indicador de estafa romántica: «$it»" })
            if (dominantType == "estafa_general" || totalScore > 0.5f) dominantType = "estafa_romántica"
        }

        // 6. Verificar estafa de inversión
        val investmentMatches = checkInvestment(fullText)
        if (investmentMatches.isNotEmpty()) {
            totalScore += SCORE_INVESTMENT * minOf(investmentMatches.size, 3)
            indicators.addAll(investmentMatches.map { "📈 Indicador de estafa de inversión: «$it»" })
            if (dominantType == "estafa_general" || totalScore > 0.5f) dominantType = "estafa_inversión"
        }

        // 7. Verificar dominios sospechosos
        val domainMatches = checkSuspiciousDomains(fullText)
        if (domainMatches.isNotEmpty()) {
            totalScore += SCORE_SUSPICIOUS_DOMAIN * minOf(domainMatches.size, 2)
            indicators.addAll(domainMatches.map { "🌐 Dominio sospechoso: «$it»" })
            if (dominantType == "estafa_general") dominantType = "phishing"
        }

        // 8. Verificar URLs sospechosas
        val urlMatches = checkSuspiciousUrls(fullText)
        if (urlMatches.isNotEmpty()) {
            totalScore += SCORE_SUSPICIOUS_URL * minOf(urlMatches.size, 2)
            indicators.addAll(urlMatches.map { "🔗 URL sospechosa: «$it»" })
            if (dominantType == "estafa_general") dominantType = "phishing"
        }

        // 9. Verificar suplantación por nombre de paquete
        val impersonationMatch = checkImpersonation(packageName, title)
        if (impersonationMatch != null) {
            totalScore += SCORE_IMPERSONATION
            indicators.add("🎭 Posible suplantación: $impersonationMatch")
            if (dominantType == "estafa_general") dominantType = "phishing"
        }

        // Normalizar puntuación
        val confidence = minOf(totalScore, 1.0f)
        val isScam = confidence >= 0.4f

        if (isScam && confidence < 0.6f && dominantType == "estafa_general") {
            dominantType = "estafa_general"
        } else if (!isScam) {
            dominantType = "legítimo"
        }

        val recommendation = generateRecommendation(isScam, dominantType, confidence, indicators)

        Log.i(
            TAG,
            "Análisis completado: estafa=$isScam, confianza=${"%.2f".format(confidence)}, " +
            "tipo=$dominantType, indicadores=${indicators.size}"
        )

        return Result.success(ScamAnalysis(
            isScam = isScam,
            confidence = confidence,
            scamType = dominantType,
            indicators = indicators,
            recommendation = recommendation
        ))
    }

    // ─── Análisis de SMS ──────────────────────────────────────────────────

    /**
     * Analiza un mensaje SMS en busca de indicadores de estafa.
     *
     * @param sender Número o nombre del remitente
     * @param body Cuerpo del mensaje SMS
     * @return Result con ScamAnalysis
     */
    fun analyzeSms(sender: String, body: String): Result<ScamAnalysis> {
        val indicators = mutableListOf<String>()
        var totalScore = 0f
        var dominantType = "estafa_general"

        Log.i(TAG, "Analizando SMS de remitente: $sender")

        // Los SMS de estafa suelen tener características adicionales
        val fullText = "$sender $body"

        // 1. Verificar urgencia
        val urgencyMatches = checkUrgency(fullText)
        if (urgencyMatches.isNotEmpty()) {
            totalScore += SCORE_URGENCY * minOf(urgencyMatches.size, 3)
            indicators.addAll(urgencyMatches.map { "⚠️ Lenguaje urgente: «$it»" })
        }

        // 2. Verificar solicitudes de información personal
        val personalInfoMatches = checkPersonalInfo(fullText)
        if (personalInfoMatches.isNotEmpty()) {
            totalScore += SCORE_PERSONAL_INFO * minOf(personalInfoMatches.size, 3)
            indicators.addAll(personalInfoMatches.map { "🔒 Solicitud de datos personales: «$it»" })
        }

        // 3. Verificar indicadores de phishing
        val phishingMatches = checkPhishing(fullText)
        if (phishingMatches.isNotEmpty()) {
            totalScore += SCORE_PHISHING * minOf(phishingMatches.size, 3)
            indicators.addAll(phishingMatches.map { "🎣 Indicador de phishing: «$it»" })
            dominantType = "phishing"
        }

        // 4. Verificar estafa de paquetes (muy común por SMS)
        val deliveryMatches = checkDelivery(fullText)
        if (deliveryMatches.isNotEmpty()) {
            totalScore += SCORE_DELIVERY * minOf(deliveryMatches.size, 3) * 1.2f  // Bonus SMS
            indicators.addAll(deliveryMatches.map { "📦 Indicador de estafa de paquetes: «$it»" })
            if (dominantType == "estafa_general") dominantType = "estafa_paquetes"
        }

        // 5. Verificar estafa de inversión
        val investmentMatches = checkInvestment(fullText)
        if (investmentMatches.isNotEmpty()) {
            totalScore += SCORE_INVESTMENT * minOf(investmentMatches.size, 3)
            indicators.addAll(investmentMatches.map { "📈 Indicador de estafa de inversión: «$it»" })
            if (dominantType == "estafa_general") dominantType = "estafa_inversión"
        }

        // 6. Verificar dominios sospechosos y URLs
        val domainMatches = checkSuspiciousDomains(fullText)
        if (domainMatches.isNotEmpty()) {
            totalScore += SCORE_SUSPICIOUS_DOMAIN * minOf(domainMatches.size, 2)
            indicators.addAll(domainMatches.map { "🌐 Dominio sospechoso: «$it»" })
        }

        val urlMatches = checkSuspiciousUrls(fullText)
        if (urlMatches.isNotEmpty()) {
            totalScore += SCORE_SUSPICIOUS_URL * minOf(urlMatches.size, 2)
            indicators.addAll(urlMatches.map { "🔗 URL sospechosa: «$it»" })
        }

        // 7. Número de seguimiento falso
        val trackingMatches = checkFakeTracking(fullText)
        if (trackingMatches.isNotEmpty()) {
            totalScore += SCORE_FAKE_TRACKING
            indicators.addAll(trackingMatches.map { "📮 Número de seguimiento sospechoso: «$it»" })
            if (dominantType == "estafa_general") dominantType = "estafa_paquetes"
        }

        // 8. Verificar remitente sospechoso
        val senderScore = checkSuspiciousSender(sender)
        if (senderScore > 0f) {
            totalScore += senderScore
            indicators.add("📱 Remitente sospechoso: «$sender»")
        }

        // 9. Verificar enlaces acortados (muy común en SMS de estafa)
        val shortenedLinks = checkShortenedLinks(fullText)
        if (shortenedLinks.isNotEmpty()) {
            totalScore += 0.12f * minOf(shortenedLinks.size, 2)
            indicators.addAll(shortenedLinks.map { "🔗 Enlace acortado sospechoso: «$it»" })
        }

        val confidence = minOf(totalScore, 1.0f)
        val isScam = confidence >= 0.35f  // Umbral ligeramente más bajo para SMS

        if (!isScam) dominantType = "legítimo"

        val recommendation = generateRecommendation(isScam, dominantType, confidence, indicators)

        Log.i(
            TAG,
            "Análisis SMS completado: estafa=$isScam, confianza=${"%.2f".format(confidence)}, tipo=$dominantType"
        )

        return Result.success(ScamAnalysis(
            isScam = isScam,
            confidence = confidence,
            scamType = dominantType,
            indicators = indicators,
            recommendation = recommendation
        ))
    }

    // ─── Análisis de captura de pantalla ───────────────────────────────────

    /**
     * Analiza una captura de pantalla en busca de indicadores de estafa.
     * NOTA: La funcionalidad de visión requiere integración con el modelo de visión.
     * Actualmente extrae texto si es posible y realiza el análisis basado en reglas.
     *
     * @param screenshotPath Ruta al archivo de captura de pantalla
     * @return Result con ScamAnalysis
     */
    fun analyzeScreenshot(screenshotPath: String): Result<ScamAnalysis> {
        Log.i(TAG, "Analizando captura de pantalla: $screenshotPath")

        // TODO: Integrar con modelo de visión para OCR + análisis visual
        // Por ahora, devolver un análisis placeholder que indica que
        // la funcionalidad completa requiere el modelo de visión

        return Result.success(ScamAnalysis(
            isScam = false,
            confidence = 0f,
            scamType = "análisis_pendiente",
            indicators = listOf(
                "🔍 La captura de pantalla requiere análisis con modelo de visión",
                "📸 Archivo: $screenshotPath",
                "ℹ️ El análisis visual se habilitará cuando el modelo de visión esté disponible"
            ),
            recommendation = "El análisis de capturas de pantalla requiere el modelo de visión. " +
                "Por ahora, puede copiar el texto sospechoso y usar el análisis de notificación " +
                "o SMS para verificar si es una estafa."
        ))
    }

    // ─── Métodos de verificación internos ──────────────────────────────────

    private fun checkUrgency(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (phrase in URGENCY_PHRASES) {
            if (text.contains(phrase, ignoreCase = true)) {
                matches.add(phrase)
            }
        }
        return matches
    }

    private fun checkPersonalInfo(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (request in PERSONAL_INFO_REQUESTS) {
            if (text.contains(request, ignoreCase = true)) {
                matches.add(request)
            }
        }
        return matches
    }

    private fun checkPhishing(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (pattern in PHISHING_INDICATORS) {
            pattern.find(text)?.let { match ->
                matches.add(match.value)
            }
        }
        return matches.distinct()
    }

    private fun checkDelivery(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (pattern in DELIVERY_SCAM_INDICATORS) {
            pattern.find(text)?.let { match ->
                matches.add(match.value)
            }
        }
        return matches.distinct()
    }

    private fun checkRomance(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (pattern in ROMANCE_SCAM_INDICATORS) {
            pattern.find(text)?.let { match ->
                matches.add(match.value)
            }
        }
        return matches.distinct()
    }

    private fun checkInvestment(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (pattern in INVESTMENT_SCAM_INDICATORS) {
            pattern.find(text)?.let { match ->
                matches.add(match.value)
            }
        }
        return matches.distinct()
    }

    private fun checkSuspiciousDomains(text: String): List<String> {
        val matches = mutableListOf<String>()
        val urlPattern = Regex("https?://([^\\s/]+)", RegexOption.IGNORE_CASE)

        urlPattern.findAll(text).forEach { match ->
            val domain = match.groupValues[1].lowercase()
            for (suspicious in SUSPICIOUS_DOMAINS) {
                if (domain.contains(suspicious.lowercase())) {
                    matches.add(match.value)
                    break
                }
            }
        }

        // También verificar en texto sin URL
        for (suspicious in SUSPICIOUS_DOMAINS) {
            if (text.contains(suspicious, ignoreCase = true) &&
                !matches.any { it.contains(suspicious, ignoreCase = true) }) {
                matches.add(suspicious)
            }
        }

        return matches.distinct()
    }

    private fun checkSuspiciousUrls(text: String): List<String> {
        val matches = mutableListOf<String>()
        for (pattern in SUSPICIOUS_URL_PATTERNS) {
            pattern.findAll(text).forEach { match ->
                matches.add(match.value)
            }
        }
        return matches.distinct()
    }

    private fun checkFakeTracking(text: String): List<String> {
        val matches = mutableListOf<String>()
        FAKE_TRACKING_PATTERN.findAll(text).forEach { match ->
            matches.add(match.value)
        }
        return matches.distinct()
    }

    private fun checkImpersonation(packageName: String, title: String): String? {
        val knownApps = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.facebook.katana" to "Facebook",
            "com.instagram.android" to "Instagram",
            "com.twitter.android" to "Twitter/X",
            "com.google.android.gm" to "Gmail",
            "com.android.vending" to "Google Play",
            "com.banco" to "App Bancaria",
            "com.paypal.android.p2pmobile" to "PayPal",
            "com.ibercaja.ibercaja" to "Ibercaja",
            "com.bbva.bbvacontigo" to "BBVA",
            "es.bancosantander" to "Santander",
            "com.caixabank" to "CaixaBank"
        )

        // Verificar si el paquete coincide con una app conocida
        // pero el título no parece legítimo
        for ((pkg, appName) in knownApps) {
            if (packageName.startsWith(pkg.substringBefore("."))) {
                // El paquete coincide parcialmente, verificar si el título es consistente
                val titleLower = title.lowercase()
                val suspiciousTitlePatterns = listOf(
                    "verify", "confirm", "update", "secure", "alert",
                    "verificar", "confirmar", "actualizar", "seguro", "alerta",
                    "urgente", "bloqueado", "suspendido"
                )
                for (suspicious in suspiciousTitlePatterns) {
                    if (titleLower.contains(suspicious)) {
                        return "Notificación de '$appName' con título sospechoso: «$title»"
                    }
                }
            }
        }

        // Verificar paquetes completamente desconocidos con títulos alarmantes
        val alarmKeywords = listOf("urgente", "bloqueo", "seguridad", "verificación", "pago")
        val hasAlarmingTitle = alarmKeywords.any { title.contains(it, ignoreCase = true) }
        val isUnknownPackage = !knownApps.keys.any { packageName.startsWith(it.substringBefore(".")) }

        if (isUnknownPackage && hasAlarmingTitle) {
            return "App desconocida ($packageName) con título alarmante: «$title»"
        }

        return null
    }

    private fun checkSuspiciousSender(sender: String): Float {
        var score = 0f

        // Remitentes con solo números y longitud inusual
        if (sender.matches(Regex("\\d+"))) {
            when {
                sender.length <= 5 -> score += 0.10f  // Códigos cortos legítimos suelen ser 5 dígitos
                sender.length > 10 -> score += 0.15f  // Números muy largos son sospechosos
            }
        }

        // Remitentes con mezcla extraña de caracteres
        if (sender.matches(Regex(".*[a-zA-Z].*\\d.*[a-zA-Z].*"))) {
            score += 0.12f
        }

        // Remitentes que intentan parecer una empresa
        val fakeCompanyPatterns = listOf(
            Regex("(?:Banco|Bank|Caixa|BBVA|Santander|PayPal|Amazon|Netflix)", RegexOption.IGNORE_CASE),
            Regex("(?:Correos|SEUR|DHL|FedEx|UPS|MRW)", RegexOption.IGNORE_CASE)
        )
        for (pattern in fakeCompanyPatterns) {
            if (pattern.containsMatchIn(sender)) {
                // Compañías legítimas no suelen enviar SMS desde números que contienen su nombre
                if (sender.matches(Regex(".*\\d.*"))) {
                    score += 0.18f
                }
            }
        }

        return score
    }

    private fun checkShortenedLinks(text: String): List<String> {
        val matches = mutableListOf<String>()
        val shortUrlPattern = Regex(
            "https?://(?:bit\\.ly|tinyurl\\.com|t\\.co|ow\\.ly|is\\.gd|rb\\.gy|cutt\\.ly|shorturl\\.at|goo\\.gl)/[^\\s]+",
            RegexOption.IGNORE_CASE
        )
        shortUrlPattern.findAll(text).forEach { match ->
            matches.add(match.value)
        }
        return matches
    }

    // ─── Generación de recomendaciones ─────────────────────────────────────

    private fun generateRecommendation(
        isScam: Boolean,
        scamType: String,
        confidence: Float,
        indicators: List<String>
    ): String {
        if (!isScam) {
            return if (indicators.isEmpty()) {
                "✅ No se detectaron indicadores de estafa. El mensaje parece legítimo."
            } else {
                "⚠️ Se detectaron algunos indicadores leves, pero no suficientes para considerar " +
                "que sea una estafa. Mantenga la precaución habitual."
            }
        }

        val baseRecommendation = when (scamType) {
            "phishing" -> buildString {
                appendLine("🚨 POSIBLE INTENTO DE PHISHING DETECTADO")
                appendLine()
                appendLine("Recomendaciones:")
                appendLine("• NO haga clic en ningún enlace del mensaje")
                appendLine("• NO proporcione datos personales ni contraseñas")
                appendLine("• NO llame a ningún número incluido en el mensaje")
                appendLine("• Verifique directamente con la entidad usando sus canales oficiales")
                appendLine("• Acceda a su cuenta mediante la app oficial o sitio web conocido")
                appendLine("• Si ya proporcionó datos, cambie sus contraseñas inmediatamente")
            }
            "estafa_paquetes" -> buildString {
                appendLine("🚨 POSIBLE ESTAFA DE PAQUETES DETECTADA")
                appendLine()
                appendLine("Recomendaciones:")
                appendLine("• NO pague ninguna tasa de aduanas o liberación desde un enlace SMS")
                appendLine("• Verifique sus pedidos directamente en la web del vendedor")
                appendLine("• Las empresas de mensajería legítimas no solicitan pagos por SMS")
                appendLine("• Compruebe el número de seguimiento en el sitio oficial de la empresa")
                appendLine("• Si no espera ningún paquete, ignore completamente el mensaje")
                appendLine("• Correos y otras empresas nunca le pedirán pagar por enlaces SMS")
            }
            "estafa_romántica" -> buildString {
                appendLine("🚨 POSIBLE ESTAFA ROMÁNTICA DETECTADA")
                appendLine()
                appendLine("Recomendaciones:")
                appendLine("• NO envíe dinero a personas que solo conoce por internet")
                appendLine("• Desconfíe de declaraciones rápidas de amor o afecto intenso")
                appendLine("• Verifique la identidad con videollamada antes de confiar")
                appendLine("• Las historias de emergencias financieras son un clásico de estafa")
                appendLine("• NUNCA comparta datos bancarios con alguien conocido online")
                appendLine("• Las tarjetas de regalo nunca son un método de pago legítimo")
            }
            "estafa_inversión" -> buildString {
                appendLine("🚨 POSIBLE ESTAFA DE INVERSIÓN DETECTADA")
                appendLine()
                appendLine("Recomendaciones:")
                appendLine("• NUNCA invierta basándose en mensajes no solicitados")
                appendLine("• Desconfíe de promesas de retornos garantizados o sin riesgo")
                appendLine("• Verifique que la plataforma esté registrada en la CNMV")
                appendLine("• Las inversiones legítimas siempre conllevan riesgo")
                appendLine("• NO descargue apps de trading desde enlaces de mensajes")
                appendLine("• Consulte con un asesor financiero profesional antes de invertir")
            }
            else -> buildString {
                appendLine("🚨 POSIBLE ESTAFA DETECTADA")
                appendLine()
                appendLine("Recomendaciones generales:")
                appendLine("• NO proporcione información personal ni financiera")
                appendLine("• NO haga clic en enlaces sospechosos")
                appendLine("• Verifique la fuente del mensaje por canales oficiales")
                appendLine("• Ante la duda, ignore y elimine el mensaje")
                appendLine("• Reporte el mensaje como spam si la app lo permite")
            }
        }

        val confidenceLabel = when {
            confidence >= 0.8f -> "ALTA"
            confidence >= 0.6f -> "MEDIA-ALTA"
            confidence >= 0.4f -> "MEDIA"
            else -> "BAJA"
        }

        return "$baseRecommendation\nNivel de confianza: $confidenceLabel (${(confidence * 100).toInt()}%)"
    }

    // ─── Utilidades ────────────────────────────────────────────────────────

    /**
     * Devuelve un resumen en español del análisis de estafa.
     */
    fun formatAnalysisResult(analysis: ScamAnalysis): String {
        val sb = StringBuilder()

        val emoji = when {
            !analysis.isScam -> "✅"
            analysis.confidence >= 0.7f -> "🚨"
            analysis.confidence >= 0.5f -> "⚠️"
            else -> "⚡"
        }

        sb.appendLine("$emoji Análisis de Seguridad")
        sb.appendLine("═══════════════════════════════════")

        val tipoLabel = when (analysis.scamType) {
            "phishing" -> "Phishing (Suplantación)"
            "estafa_paquetes" -> "Estafa de Paquetes"
            "estafa_romántica" -> "Estafa Romántica"
            "estafa_inversión" -> "Estafa de Inversión"
            "estafa_general" -> "Estafa General"
            "legítimo" -> "Legítimo"
            "análisis_pendiente" -> "Análisis Pendiente"
            else -> analysis.scamType
        }

        sb.appendLine("Resultado: ${if (analysis.isScam) "ESTAFA DETECTADA" else "No se detectó estafa"}")
        sb.appendLine("Tipo: $tipoLabel")
        sb.appendLine("Confianza: ${(analysis.confidence * 100).toInt()}%")

        if (analysis.indicators.isNotEmpty()) {
            sb.appendLine("\nIndicadores encontrados:")
            analysis.indicators.forEach { indicator ->
                sb.appendLine("  • $indicator")
            }
        }

        sb.appendLine("\n${analysis.recommendation}")

        return sb.toString()
    }
}
