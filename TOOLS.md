# TOOLS.md — Catálogo de Habilidades de NubiaAgent

**33+ habilidades que el asistente puede invocar a través del ActionDispatcher.**
Cada habilidad tiene un schema JSON para que el LLM sepa cómo llamarla.

---

## Módulo 1: Comunicación y Mensajería

### sms.send
Envía un mensaje SMS a un contacto.
```json
{
  "name": "sms.send",
  "params": {"to": "string (número o nombre)", "message": "string"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "sms.send(to=\"Sarah\", message=\"Llego en 10 minutos\")"
}
```

### sms.read
Lee mensajes SMS recientes, opcionalmente filtrados por contacto.
```json
{
  "name": "sms.read",
  "params": {"contact": "string? (nombre del contacto)", "limit": "int? (default: 10)"},
  "risk": "READ",
  "example": "sms.read(contact=\"Sarah\", limit=5)"
}
```

### phone.call
Realiza una llamada telefónica.
```json
{
  "name": "phone.call",
  "params": {"to": "string (número o nombre)"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "phone.call(to=\"Mamá\")"
}
```

### phone.callLog
Obtiene el registro de llamadas recientes.
```json
{
  "name": "phone.callLog",
  "params": {"limit": "int? (default: 10)"},
  "risk": "READ",
  "example": "phone.callLog(limit=5)"
}
```

### whatsapp.send
Envía un mensaje por WhatsApp a un contacto.
```json
{
  "name": "whatsapp.send",
  "params": {"contact": "string (nombre del contacto)", "message": "string"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "whatsapp.send(contact=\"Sarah\", message=\"¿Vamos al cine?\")"
}
```

### whatsapp.read
Lee los últimos mensajes de un chat de WhatsApp.
```json
{
  "name": "whatsapp.read",
  "params": {"contact": "string (nombre del contacto)", "limit": "int? (default: 10)"},
  "risk": "READ",
  "example": "whatsapp.read(contact=\"Sarah\", limit=5)"
}
```

### telegram.send
Envía un mensaje por Telegram.
```json
{
  "name": "telegram.send",
  "params": {"contact": "string (nombre del contacto)", "message": "string"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "telegram.send(contact=\"Carlos\", message=\"Revisión completada\")"
}
```

### telegram.read
Lee mensajes de un chat de Telegram.
```json
{
  "name": "telegram.read",
  "params": {"contact": "string (nombre del contacto)", "limit": "int? (default: 10)"},
  "risk": "READ",
  "example": "telegram.read(contact=\"Carlos\", limit=5)"
}
```

### instagram.send
Envía un mensaje directo por Instagram.
```json
{
  "name": "instagram.send",
  "params": {"username": "string (nombre de usuario)", "message": "string"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "instagram.send(username=\"@maria\", message=\"¡Hola!\")"
}
```

### email.search
Busca correos en Gmail.
```json
{
  "name": "email.search",
  "params": {"query": "string (término de búsqueda)", "limit": "int? (default: 5)"},
  "risk": "READ",
  "example": "email.search(query=\"factura electricidad\", limit=3)"
}
```

### email.read
Lee el contenido de un correo electrónico.
```json
{
  "name": "email.read",
  "params": {"index": "int (posición del correo)"},
  "risk": "READ",
  "example": "email.read(index=0)"
}
```

### email.compose
Redacta y envía un correo electrónico.
```json
{
  "name": "email.compose",
  "params": {"to": "string", "subject": "string", "body": "string"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "email.compose(to=\"jefe@empresa.com\", subject=\"Reporte\", body=\"Reporte adjunto...\")"
}
```

### email.reply
Responde al correo actualmente abierto.
```json
{
  "name": "email.reply",
  "params": {"text": "string (texto de la respuesta)"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "email.reply(text=\"Confirmado, gracias.\")"
}
```

---

## Módulo 2: Control de Hardware y Sensores

### camera.snap
Toma una foto usando la cámara trasera (Neovision AI).
```json
{
  "name": "camera.snap",
  "params": {"output": "string? (ruta de salida, default: auto)"},
  "risk": "WRITE_SAFE",
  "example": "camera.snap()"
}
```

### camera.record
Inicia o detiene la grabación de video.
```json
{
  "name": "camera.record",
  "params": {"action": "string (start|stop)", "output": "string? (ruta de salida)"},
  "risk": "WRITE_SAFE",
  "example": "camera.record(action=\"start\")"
}
```

### audio.record
Inicia o detiene la grabación de audio.
```json
{
  "name": "audio.record",
  "params": {"action": "string (start|stop)", "output": "string? (ruta de salida)"},
  "risk": "WRITE_SAFE",
  "example": "audio.record(action=\"start\")"
}
```

### media.play
Reproduce un archivo de audio/video.
```json
{
  "name": "media.play",
  "params": {"file": "string (ruta del archivo)"},
  "risk": "WRITE_SAFE",
  "example": "media.play(file=\"/storage/nota_voz.mp3\")"
}
```

### sensor.read
Lee datos de un sensor del dispositivo.
```json
{
  "name": "sensor.read",
  "params": {"type": "string (accelerometer|gyroscope|proximity|light|step_counter|magnetic|barometer)"},
  "risk": "READ",
  "example": "sensor.read(type=\"proximity\")"
}
```

### location.get
Obtiene la ubicación GPS actual.
```json
{
  "name": "location.get",
  "params": {"precise": "boolean? (default: true)"},
  "risk": "READ",
  "example": "location.get(precise=true)"
}
```

### torch.toggle
Enciende o apaga la linterna.
```json
{
  "name": "torch.toggle",
  "params": {"on": "boolean"},
  "risk": "WRITE_SAFE",
  "example": "torch.toggle(on=true)"
}
```

### settings.volume
Ajusta el volumen del dispositivo.
```json
{
  "name": "settings.volume",
  "params": {"level": "int (0-15)", "stream": "string? (ring|music|alarm|notification, default: ring)"},
  "risk": "WRITE_SAFE",
  "example": "settings.volume(level=10, stream=\"music\")"
}
```

### settings.brightness
Ajusta el brillo de la pantalla.
```json
{
  "name": "settings.brightness",
  "params": {"level": "int (0-255)"},
  "risk": "WRITE_SAFE",
  "example": "settings.brightness(level=128)"
}
```

### clipboard.set
Copia texto al portapapeles.
```json
{
  "name": "clipboard.set",
  "params": {"text": "string"},
  "risk": "WRITE_SAFE",
  "example": "clipboard.set(text=\"Código: 8472\")"
}
```

### clipboard.get
Lee el contenido del portapapeles.
```json
{
  "name": "clipboard.get",
  "params": {},
  "risk": "READ",
  "example": "clipboard.get()"
}
```

### wifi.toggle
Activa o desactiva el WiFi.
```json
{
  "name": "wifi.toggle",
  "params": {"enabled": "boolean"},
  "risk": "WRITE_SAFE",
  "example": "wifi.toggle(enabled=true)"
}
```

---

## Módulo 3: Memoria y Conocimiento Personal

### memory.store
Guarda un hecho o información en la memoria personal.
```json
{
  "name": "memory.store",
  "params": {"content": "string", "category": "string? (preference|fact|event|person|task)", "importance": "float? (0-1)"},
  "risk": "WRITE_SAFE",
  "example": "memory.store(content=\"A Sarah le gusta el sushi\", category=\"preference\")"
}
```

### memory.recall
Busca información en la memoria personal por significado.
```json
{
  "name": "memory.recall",
  "params": {"query": "string (qué buscar)", "limit": "int? (default: 5)"},
  "risk": "READ",
  "example": "memory.recall(query=\"preferencias de comida de Sarah\")"
}
```

### memory.forget
Elimina información de la memoria (requiere confirmación).
```json
{
  "name": "memory.forget",
  "params": {"query": "string", "confirm": "boolean (debe ser true)"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "memory.forget(query=\"datos de Juan\", confirm=true)"
}
```

### people.add
Agrega una persona al grafo de relaciones.
```json
{
  "name": "people.add",
  "params": {"name": "string", "relation": "string? (ej: amigo, hermano, colega)", "facts": "string[]?"},
  "risk": "WRITE_SAFE",
  "example": "people.add(name=\"Sarah\", relation=\"amiga\", facts=[\"Le gusta el cine\"])"
}
```

### people.get
Obtiene información sobre una persona del grafo.
```json
{
  "name": "people.get",
  "params": {"name": "string"},
  "risk": "READ",
  "example": "people.get(name=\"Sarah\")"
}
```

### people.relate
Establece una relación entre dos personas.
```json
{
  "name": "people.relate",
  "params": {"person1": "string", "person2": "string", "relation": "string"},
  "risk": "WRITE_SAFE",
  "example": "people.relate(person1=\"Sarah\", person2=\"Carlos\", relation=\"pareja\")"
}
```

### briefing.generate
Genera un resumen matutino personalizado.
```json
{
  "name": "briefing.generate",
  "params": {},
  "risk": "READ",
  "example": "briefing.generate()"
}
```

### briefing.schedule
Programa el briefing matutino a una hora específica.
```json
{
  "name": "briefing.schedule",
  "params": {"hour": "int (0-23)", "minute": "int (0-59)"},
  "risk": "WRITE_SAFE",
  "example": "briefing.schedule(hour=8, minute=0)"
}
```

---

## Módulo 4: Seguridad y Utilidades IA

### scam.analyze
Analiza texto para detectar estafas, phishing o fraudes.
```json
{
  "name": "scam.analyze",
  "params": {"text": "string", "source": "string? (notification|sms|email|screen)", "sender": "string?"},
  "risk": "READ",
  "example": "scam.analyze(text=\"Su paquete está retenido, pague $5...\", source=\"sms\")"
}
```

### vibe.generate
Genera un archivo HTML/JS funcional y lo muestra en Canvas.
```json
{
  "name": "vibe.generate",
  "params": {"prompt": "string (descripción de la herramienta a generar)"},
  "risk": "WRITE_SAFE",
  "example": "vibe.generate(prompt=\"calculadora científica\")"
}
```

### translate.text
Traduce texto entre español e inglés (offline).
```json
{
  "name": "translate.text",
  "params": {"text": "string", "from": "string? (es|en, default: auto-detect)", "to": "string (es|en)"},
  "risk": "READ",
  "example": "translate.text(text=\"Hello, how are you?\", to=\"es\")"
}
```

### secure.store
Guarda una credencial de forma segura (Android Keystore, AES-256-GCM).
```json
{
  "name": "secure.store",
  "params": {"key": "string (identificador)", "value": "string (secreto a guardar)"},
  "risk": "WRITE_SAFE",
  "example": "secure.store(key=\"api_key_openai\", value=\"sk-xxx\")"
}
```

### secure.get
Recupera una credencial almacenada de forma segura.
```json
{
  "name": "secure.get",
  "params": {"key": "string (identificador)"},
  "risk": "READ",
  "example": "secure.get(key=\"api_key_openai\")"
}
```

---

## Módulo 5: Optimización Nubia Core

### bypass.activate
Activa el Bypass Charging para tareas de larga duración.
```json
{
  "name": "bypass.activate",
  "params": {"task": "string (descripción de la tarea)", "duration_minutes": "int?"},
  "risk": "WRITE_SAFE",
  "example": "bypass.activate(task=\"inferencia de modelo\", duration_minutes=30)"
}
```

### bypass.deactivate
Desactiva el Bypass Charging.
```json
{
  "name": "bypass.deactivate",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "bypass.deactivate()"
}
```

### bypass.status
Obtiene el estado actual de carga y Bypass Charging.
```json
{
  "name": "bypass.status",
  "params": {},
  "risk": "READ",
  "example": "bypass.status()"
}
```

### screen.split
Abre dos aplicaciones en modo pantalla dividida.
```json
{
  "name": "screen.split",
  "params": {"app1": "string (primera app)", "app2": "string (segunda app)"},
  "risk": "WRITE_SAFE",
  "example": "screen.split(app1=\"WhatsApp\", app2=\"Notas\")"
}
```

### screen.tap
Toca un elemento de la pantalla.
```json
{
  "name": "screen.tap",
  "params": {"target": "string (descripción del elemento o coordenadas)"},
  "risk": "WRITE_SAFE",
  "example": "screen.tap(target=\"Botón Enviar\")"
}
```

### screen.type
Escribe texto en el campo activo de la pantalla.
```json
{
  "name": "screen.type",
  "params": {"text": "string", "press_enter": "boolean? (default: false)"},
  "risk": "WRITE_SAFE",
  "example": "screen.type(text=\"Hola, ¿cómo estás?\", press_enter=true)"
}
```

### screen.scroll
Desplaza la pantalla en una dirección.
```json
{
  "name": "screen.scroll",
  "params": {"direction": "string (up|down|left|right)", "amount": "int? (píxeles)"},
  "risk": "READ",
  "example": "screen.scroll(direction=\"down\")"
}
```

### screen.read
Lee los elementos interactivos de la pantalla actual.
```json
{
  "name": "screen.read",
  "params": {},
  "risk": "READ",
  "example": "screen.read()"
}
```

### app.launch
Abre una aplicación específica.
```json
{
  "name": "app.launch",
  "params": {"package": "string (nombre de la app o paquete)"},
  "risk": "WRITE_SAFE",
  "example": "app.launch(package=\"WhatsApp\")"
}
```

### notification.send
Muestra una notificación local al usuario.
```json
{
  "name": "notification.send",
  "params": {"title": "string", "message": "string", "priority": "string? (low|default|high|urgent)"},
  "risk": "WRITE_SAFE",
  "example": "notification.send(title=\"Recordatorio\", message=\"Reunión en 15 min\")"
}
```

### calendar.read
Lee eventos del calendario.
```json
{
  "name": "calendar.read",
  "params": {"date": "string? (YYYY-MM-DD, default: hoy)", "days": "int? (default: 1)"},
  "risk": "READ",
  "example": "calendar.read(date=\"2025-01-20\", days=3)"
}
```

### calendar.create
Crea un evento en el calendario.
```json
{
  "name": "calendar.create",
  "params": {"title": "string", "date": "string (YYYY-MM-DD)", "time": "string? (HH:MM)", "duration": "int? (minutos)"},
  "risk": "WRITE_SAFE",
  "example": "calendar.create(title=\"Reunión\", date=\"2025-01-20\", time=\"15:00\")"
}
```

---

**Total: 37 habilidades documentadas**

Clasificación por riesgo:
- **READ** (20): Lectura sin impacto — auto-aprobadas en todos los perfiles
- **WRITE_SAFE** (13): Acciones reversibles — auto-aprobadas en Balanceado y Full Auto
- **WRITE_DESTRUCTIVE** (4): Acciones irreversibles — siempre requieren confirmación (biométrica en Full Auto)
