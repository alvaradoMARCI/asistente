package com.nubiaagent.cognitive.agent

import android.util.Log

/**
 * AutonomyProfile: Define los niveles de autonomía del agente.
 *
 * Los perfiles controlan qué acciones puede ejecutar el agente
 * sin confirmación explícita del usuario.
 *
 * CLASIFICACIÓN DE ACCIONES:
 *
 * Lectura (READ): No modifican el estado del dispositivo ni envían datos.
 *   Ej: Leer pantalla, consultar memoria, ver notificaciones, consultar hora.
 *
 * Escritura segura (WRITE_SAFE): Modifican estado pero son reversibles.
 *   Ej: Abrir una app, establecer un recordatorio, guardar nota.
 *
 * Escritura destructiva (WRITE_DESTRUCTIVE): No son fácilmente reversibles
 *   o tienen impacto en comunicaciones.
 *   Ej: Enviar SMS, publicar en redes, borrar datos, realizar llamadas.
 *
 * | Acción               | Cauto    | Balanceado | Full Auto |
 * |----------------------|----------|------------|-----------|
 * | Leer pantalla        | Confirmar| Auto       | Auto      |
 * | Consultar memoria    | Confirmar| Auto       | Auto      |
 * | Ver notificaciones   | Confirmar| Auto       | Auto      |
 * | Abrir app            | Confirmar| Confirmar  | Auto      |
 * | Crear recordatorio   | Confirmar| Confirmar  | Auto      |
 * | Guardar nota         | Confirmar| Auto       | Auto      |
 * | Enviar SMS/WhatsApp  | Confirmar| Confirmar  | Confirmar*|
 * | Realizar llamada     | Confirmar| Confirmar  | Confirmar*|
 * | Borrar datos         | Confirmar| Confirmar  | Confirmar*|
 * | Acciones financieras | Confirmar| Confirmar  | Confirmar |
 *
 * * Incluso en Full Auto, acciones con alto impacto requieren confirmación
 *   si involucran comunicaciones o finanzas.
 */
enum class AutonomyProfile(
    val displayName: String,
    val description: String
) {
    CAUTO(
        displayName = "Cauto",
        description = "Pide confirmación antes de CADA acción. Ideal para primeros usos o cuando quieres control total."
    ),
    BALANCEADO(
        displayName = "Balanceado",
        description = "Ejecuta lecturas automáticamente. Pide confirmación para acciones destructivas. Recomendado para uso diario."
    ),
    FULL_AUTO(
        displayName = "Full Auto",
        description = "Ejecución autónoma total. Solo confirma acciones financieras y de emergencia. Bajo tu responsabilidad."
    );

    /**
     * Determina si una acción puede ejecutarse sin confirmación del usuario.
     *
     * @param toolName Nombre de la herramienta (ej: "sms.send")
     * @param isDestructive Si la acción es destructiva según ToolRegistry
     * @return true si puede ejecutarse automáticamente
     */
    fun canExecute(toolName: String, isDestructive: Boolean): Boolean {
        return when (this) {
            CAUTO -> false  // Todo requiere confirmación
            BALANCEADO -> !isDestructive  // Solo lecturas y escrituras seguras
            FULL_AUTO -> {
                // Incluso en Full Auto, algunas acciones requieren confirmación
                toolName !in ALWAYS_REQUIRE_CONFIRMATION
            }
        }
    }

    companion object {
        // Acciones que SIEMPRE requieren confirmación, incluso en Full Auto
        private val ALWAYS_REQUIRE_CONFIRMATION = setOf(
            "finance.pay",
            "finance.transfer",
            "call.emergency",
            "data.delete_permanent",
            "system.factory_reset"
        )

        fun fromName(name: String): AutonomyProfile {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: BALANCEADO
        }
    }
}

/**
 * Clasificación de herramientas por riesgo.
 */
enum class ToolRisk {
    READ,               // Solo lectura, sin impacto
    WRITE_SAFE,         // Escritura reversible
    WRITE_DESTRUCTIVE   // Acción destructiva o irreversible
}

/**
 * ToolRegistry: Registro central de herramientas disponibles para el agente.
 *
 * Cada herramienta tiene:
 * - Nombre único (ej: "sms.send")
 * - Descripción para el LLM (qué hace y cuándo usarla)
 * - Schema de parámetros (formato JSON)
 * - Clasificación de riesgo
 * - Ejemplo de uso
 *
 * El ToolRegistry genera las descripciones que se incluyen en el
 * system prompt del LLM para que el modelo sepa qué herramientas
 * tiene disponibles y cómo invocarlas.
 */
object ToolRegistry {

    private val tools = mutableMapOf<String, ToolDefinition>()

    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: String,     // JSON schema
        val risk: ToolRisk,
        val example: String,
        val category: String
    )

    init {
        // ============ COMUNICACIONES ============
        register(ToolDefinition(
            name = "sms.send",
            description = "Enviar un mensaje SMS a un contacto. Requiere número de teléfono y contenido del mensaje.",
            parameters = """{"to": "string (número o nombre del contacto)", "message": "string (contenido del SMS)"}""",
            risk = ToolRisk.WRITE_DESTRUCTIVE,
            example = "sms.send(to=\"Sarah\", message=\"Llego en 10 minutos\")",
            category = "comunicaciones"
        ))

        register(ToolDefinition(
            name = "whatsapp.send",
            description = "Enviar un mensaje por WhatsApp a un contacto o grupo.",
            parameters = """{"to": "string (nombre del contacto)", "message": "string (contenido del mensaje)"}""",
            risk = ToolRisk.WRITE_DESTRUCTIVE,
            example = "whatsapp.send(to=\"Mamá\", message=\"Ya llegué a casa\")",
            category = "comunicaciones"
        ))

        register(ToolDefinition(
            name = "call.make",
            description = "Realizar una llamada telefónica a un contacto.",
            parameters = """{"to": "string (número o nombre del contacto)"}""",
            risk = ToolRisk.WRITE_DESTRUCTIVE,
            example = "call.make(to=\"Sarah\")",
            category = "comunicaciones"
        ))

        // ============ APLICACIONES ============
        register(ToolDefinition(
            name = "app.launch",
            description = "Abrir una aplicación específica en el dispositivo.",
            parameters = """{"package": "string (nombre del paquete o nombre de la app)", "action": "string? (action específica, opcional)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "app.launch(package=\"WhatsApp\")",
            category = "aplicaciones"
        ))

        register(ToolDefinition(
            name = "app.close",
            description = "Cerrar una aplicación activa.",
            parameters = """{"package": "string (nombre del paquete)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "app.close(package=\"com.instagram.android\")",
            category = "aplicaciones"
        ))

        // ============ PANTALLA ============
        register(ToolDefinition(
            name = "screen.read",
            description = "Leer los elementos interactivos de la pantalla actual. Retorna una lista de botones, textos y campos visibles.",
            parameters = """{}""",
            risk = ToolRisk.READ,
            example = "screen.read()",
            category = "pantalla"
        ))

        register(ToolDefinition(
            name = "screen.tap",
            description = "Tocar un elemento específico de la pantalla por su posición o descripción.",
            parameters = """{"target": "string (descripción del elemento o coordenadas)", "element_index": "int? (índice del elemento de screen.read, opcional)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "screen.tap(target=\"Botón Enviar\")",
            category = "pantalla"
        ))

        register(ToolDefinition(
            name = "screen.type",
            description = "Escribir texto en el campo de texto activo de la pantalla actual.",
            parameters = """{"text": "string (texto a escribir)", "press_enter": "boolean? (presionar Enter después, default: false)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "screen.type(text=\"Hola, ¿cómo estás?\", press_enter=true)",
            category = "pantalla"
        ))

        register(ToolDefinition(
            name = "screen.scroll",
            description = "Desplazar la pantalla hacia arriba o abajo.",
            parameters = """{"direction": "string (up|down)", "amount": "int? (píxeles a desplazar, default: 500)"}""",
            risk = ToolRisk.READ,
            example = "screen.scroll(direction=\"down\")",
            category = "pantalla"
        ))

        // ============ MEMORIA ============
        register(ToolDefinition(
            name = "memory.recall",
            description = "Buscar información en la memoria personal del asistente. Usa búsqueda semántica para encontrar información por significado.",
            parameters = """{"query": "string (qué buscar)", "limit": "int? (máximo resultados, default: 5)"}""",
            risk = ToolRisk.READ,
            example = "memory.recall(query=\"preferencias de restaurante de Sarah\")",
            category = "memoria"
        ))

        register(ToolDefinition(
            name = "memory.store",
            description = "Guardar un hecho o información en la memoria personal del asistente.",
            parameters = """{"content": "string (información a guardar)", "category": "string? (categoría: preference|fact|event|contact)", "importance": "float? (0-1, importancia del dato)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "memory.store(content=\"A Sarah le gusta el sushi\", category=\"preference\")",
            category = "memoria"
        ))

        register(ToolDefinition(
            name = "memory.forget",
            description = "Eliminar información de la memoria personal del asistente.",
            parameters = """{"query": "string (información a eliminar)", "confirm": "boolean (debe ser true para confirmar)"}""",
            risk = ToolRisk.WRITE_DESTRUCTIVE,
            example = "memory.forget(query=\"datos de contacto de Juan\", confirm=true)",
            category = "memoria"
        ))

        // ============ CALENDARIO ============
        register(ToolDefinition(
            name = "calendar.read",
            description = "Leer eventos del calendario para una fecha específica.",
            parameters = """{"date": "string? (fecha en formato YYYY-MM-DD, default: hoy)", "days": "int? (número de días a leer, default: 1)"}""",
            risk = ToolRisk.READ,
            example = "calendar.read(date=\"2025-01-20\", days=3)",
            category = "calendario"
        ))

        register(ToolDefinition(
            name = "calendar.create",
            description = "Crear un nuevo evento en el calendario.",
            parameters = """{"title": "string (título del evento)", "date": "string (fecha YYYY-MM-DD)", "time": "string? (hora HH:MM)", "duration_minutes": "int? (duración en minutos, default: 60)", "location": "string? (ubicación)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "calendar.create(title=\"Reunión con Sarah\", date=\"2025-01-20\", time=\"15:00\")",
            category = "calendario"
        ))

        // ============ NOTIFICACIONES ============
        register(ToolDefinition(
            name = "notification.send",
            description = "Mostrar una notificación local al usuario.",
            parameters = """{"title": "string (título)", "message": "string (contenido)", "priority": "string? (low|default|high|urgent, default: default)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "notification.send(title=\"Recordatorio\", message=\"Reunión en 15 minutos\")",
            category = "notificaciones"
        ))

        register(ToolDefinition(
            name = "notification.read",
            description = "Leer las notificaciones pendientes del dispositivo.",
            parameters = """{"package": "string? (filtrar por app, opcional)", "limit": "int? (máximo a leer, default: 10)"}""",
            risk = ToolRisk.READ,
            example = "notification.read(package=\"com.whatsapp\")",
            category = "notificaciones"
        ))

        // ============ SISTEMA ============
        register(ToolDefinition(
            name = "system.settings",
            description = "Abrir o modificar una configuración del sistema.",
            parameters = """{"setting": "string (wifi|bluetooth|brightness|volume|airplane|dnd)", "action": "string (open|toggle|set)", "value": "string? (valor para set)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "system.settings(setting=\"wifi\", action=\"toggle\")",
            category = "sistema"
        ))

        register(ToolDefinition(
            name = "system.status",
            description = "Obtener el estado actual del dispositivo (batería, almacenamiento, conectividad, Bypass Charging).",
            parameters = """{}""",
            risk = ToolRisk.READ,
            example = "system.status()",
            category = "sistema"
        ))

        // ============ PERSONAS Y VOZ ============
        register(ToolDefinition(
            name = "persona.switch",
            description = "Cambiar la personalidad del asistente entre 6 personas: Hestia (hogar), Metis (estrategia), Argus (seguridad), Athena (sabiduría), Selene (noche), Iris (social).",
            parameters = """{"persona": "string (hestia|metis|argus|athena|selene|iris)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "persona.switch(persona=\"metis\")",
            category = "identidad"
        ))

        register(ToolDefinition(
            name = "persona.list",
            description = "Listar las 6 personas disponibles con su descripción y estado activo.",
            parameters = """{}""",
            risk = ToolRisk.READ,
            example = "persona.list()",
            category = "identidad"
        ))

        register(ToolDefinition(
            name = "voice.speak",
            description = "Sintetizar texto a voz usando Piper TTS (offline) u OpenAI/ElevenLabs (cloud). Usa la voz de la persona activa.",
            parameters = """{"text": "string (texto a hablar)", "persona": "string? (persona específica, opcional)", "mode": "string? (offline|cloud|auto, default: auto)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "voice.speak(text=\"Buenos días, tu briefing está listo\")",
            category = "voz"
        ))

        register(ToolDefinition(
            name = "voice.set_mode",
            description = "Cambiar el modo de voz: offline (Piper 100% privado), cloud (OpenAI/ElevenLabs máxima naturalidad), auto (según conexión).",
            parameters = """{"mode": "string (offline|cloud|auto)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "voice.set_mode(mode=\"offline\")",
            category = "voz"
        ))

        // ============ INTERFAZ HÍBRIDA ============
        register(ToolDefinition(
            name = "canvas.show",
            description = "Mostrar el Canvas en pantalla completa para tareas visuales: Vibe Coder (código), visualizaciones, Morning Briefing, editor de SOUL.md.",
            parameters = """{"mode": "string (coder|visualizer|briefing|editor)", "content": "string? (HTML o código a mostrar, opcional)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "canvas.show(mode=\"coder\", content=\"<h1>Hola</h1>\")",
            category = "interfaz"
        ))

        register(ToolDefinition(
            name = "canvas.hide",
            description = "Ocultar el Canvas y volver a la vista normal.",
            parameters = """{}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "canvas.hide()",
            category = "interfaz"
        ))

        register(ToolDefinition(
            name = "smartcast.project",
            description = "Proyectar el Canvas a una pantalla externa via Z-SmartCast. Modo mirror (duplicar) o extend (extender).",
            parameters = """{"mode": "string? (mirror|extend, default: mirror)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "smartcast.project(mode=\"mirror\")",
            category = "interfaz"
        ))

        register(ToolDefinition(
            name = "smartcast.stop",
            description = "Detener la proyección a pantalla externa.",
            parameters = """{}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "smartcast.stop()",
            category = "interfaz"
        ))

        register(ToolDefinition(
            name = "splitscreen.enter",
            description = "Activar pantalla dividida. El Canvas se muestra en una mitad y la app actual en la otra.",
            parameters = """{"ratio": "float? (0.4|0.5|0.6, proporción del Canvas, default: 0.5)", "canvas_on_top": "boolean? (Canvas arriba, default: true)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "splitscreen.enter(ratio=0.5, canvas_on_top=true)",
            category = "interfaz"
        ))

        register(ToolDefinition(
            name = "splitscreen.exit",
            description = "Salir del modo pantalla dividida.",
            parameters = """{}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "splitscreen.exit()",
            category = "interfaz"
        ))

        // ============ ORQUESTACIÓN NUBIA ============
        register(ToolDefinition(
            name = "bypass.curate",
            description = "Forzar la curación de memoria (indexación pesada). Se recomienda solo durante Bypass Charging para no estresar la batería.",
            parameters = """{}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "bypass.curate()",
            category = "nubia"
        ))

        register(ToolDefinition(
            name = "bypass.status",
            description = "Verificar si el Bypass Charging está activo y si hay curación en progreso.",
            parameters = """{}""",
            risk = ToolRisk.READ,
            example = "bypass.status()",
            category = "nubia"
        ))

        register(ToolDefinition(
            name = "camera.snap",
            description = "Tomar una foto con la cámara usando Neovision AI del Nubia Neo 3.",
            parameters = """{"camera": "string? (back|front, default: back)", "flash": "boolean? (usar flash, default: false)"}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "camera.snap(camera=\"back\", flash=false)",
            category = "nubia"
        ))

        register(ToolDefinition(
            name = "scam.detect",
            description = "Analizar un mensaje o notificación para detectar si es un scam/phishing. Argus lo ejecuta automáticamente en notificaciones.",
            parameters = """{"text": "string (texto a analizar)", "source": "string? (sms|email|whatsapp|unknown, default: unknown)"}""",
            risk = ToolRisk.READ,
            example = "scam.detect(text=\"Ha ganado un premio...\", source=\"sms\")",
            category = "seguridad"
        ))

        register(ToolDefinition(
            name = "translate",
            description = "Traducir texto entre idiomas. Funciona offline con modelo local cuando no hay internet.",
            parameters = """{"text": "string (texto a traducir)", "from": "string (idioma origen, ej: en)", "to": "string (idioma destino, ej: es)"}""",
            risk = ToolRisk.READ,
            example = "translate(text=\"Hello world\", from=\"en\", to=\"es\")",
            category = "utilidades"
        ))

        register(ToolDefinition(
            name = "memory.briefing",
            description = "Generar el Morning Briefing: resumen del día, calendario, mensajes pendientes, clima, tareas. Se puede hablar con voz.",
            parameters = """{"speak": "boolean? (leer en voz alta, default: true)", "persona": "string? (persona para la voz, default: activa)"}""",
            risk = ToolRisk.READ,
            example = "memory.briefing(speak=true, persona=\"hestia\")",
            category = "memoria"
        ))

        register(ToolDefinition(
            name = "memory.people",
            description = "Consultar el People Graph: relaciones, contactos frecuentes, interacciones recientes.",
            parameters = """{"name": "string? (nombre del contacto, opcional)", "depth": "int? (profundidad del grafo, default: 1)"}""",
            risk = ToolRisk.READ,
            example = "memory.people(name=\"Sarah\", depth=2)",
            category = "memoria"
        ))

        register(ToolDefinition(
            name = "profile.encrypt",
            description = "Encriptar el Living Profile con AES-256-GCM. Se ejecuta automáticamente, pero el usuario puede forzarlo.",
            parameters = """{}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "profile.encrypt()",
            category = "seguridad"
        ))

        register(ToolDefinition(
            name = "profile.backup",
            description = "Crear backup encriptado del Living Profile en SecureVault.",
            parameters = """{}""",
            risk = ToolRisk.WRITE_SAFE,
            example = "profile.backup()",
            category = "seguridad"
        ))
    }

    fun register(tool: ToolDefinition) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): ToolDefinition? = tools[name]

    fun getAllTools(): Collection<ToolDefinition> = tools.values

    fun isDestructive(toolName: String): Boolean {
        return tools[toolName]?.risk == ToolRisk.WRITE_DESTRUCTIVE
    }

    /**
     * Genera las descripciones de herramientas para el system prompt del LLM.
     * Formato compacto para minimizar tokens.
     */
    fun generateToolDescriptions(): String {
        return tools.entries.groupBy { it.value.category }.entries.joinToString("\n\n") { (category, entries) ->
            val categoryHeader = "### ${category.replaceFirstChar { it.uppercase() }}"
            val toolList = entries.joinToString("\n") { (_, tool) ->
                "- ${tool.name}(${tool.parameters}): ${tool.description} [${tool.risk.name}]"
            }
            "$categoryHeader\n$toolList"
        }
    }

    /**
     * Genera descripciones de herramientas para un contexto comprimido.
     */
    fun generateCompactToolDescriptions(): String {
        return tools.values.joinToString(", ") { tool ->
            "${tool.name}(${tool.parameters})"
        }
    }
}
