# TOOLS.md — Catálogo de Habilidades de NubiaAgent v4.0.0

**45+ habilidades que el asistente puede invocar a través del ActionDispatcher.**
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

### whatsapp.send
Envía un mensaje por WhatsApp a un contacto o grupo.
```json
{
  "name": "whatsapp.send",
  "params": {"to": "string (nombre del contacto)", "message": "string"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "whatsapp.send(to=\"Mamá\", message=\"Ya llegué a casa\")"
}
```

### call.make
Realiza una llamada telefónica a un contacto.
```json
{
  "name": "call.make",
  "params": {"to": "string (número o nombre)"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "call.make(to=\"Sarah\")"
}
```

---

## Módulo 2: Aplicaciones

### app.launch
Abre una aplicación específica en el dispositivo.
```json
{
  "name": "app.launch",
  "params": {"package": "string (nombre de la app o paquete)", "action": "string?"},
  "risk": "WRITE_SAFE",
  "example": "app.launch(package=\"WhatsApp\")"
}
```

### app.close
Cierra una aplicación activa.
```json
{
  "name": "app.close",
  "params": {"package": "string (nombre del paquete)"},
  "risk": "WRITE_SAFE",
  "example": "app.close(package=\"com.instagram.android\")"
}
```

---

## Módulo 3: Pantalla e Interacción

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

### screen.tap
Toca un elemento de la pantalla por descripción o coordenadas.
```json
{
  "name": "screen.tap",
  "params": {"target": "string", "element_index": "int?"},
  "risk": "WRITE_SAFE",
  "example": "screen.tap(target=\"Botón Enviar\")"
}
```

### screen.type
Escribe texto en el campo activo.
```json
{
  "name": "screen.type",
  "params": {"text": "string", "press_enter": "boolean?"},
  "risk": "WRITE_SAFE",
  "example": "screen.type(text=\"Hola, ¿cómo estás?\", press_enter=true)"
}
```

### screen.scroll
Desplaza la pantalla hacia arriba o abajo.
```json
{
  "name": "screen.scroll",
  "params": {"direction": "string (up|down)", "amount": "int?"},
  "risk": "READ",
  "example": "screen.scroll(direction=\"down\")"
}
```

---

## Módulo 4: Memoria Infinite (3 Capas)

### memory.recall
Busca información en la memoria personal usando búsqueda semántica.
```json
{
  "name": "memory.recall",
  "params": {"query": "string", "limit": "int? (default: 5)"},
  "risk": "READ",
  "example": "memory.recall(query=\"preferencias de restaurante de Sarah\")"
}
```

### memory.store
Guarda un hecho o información en la memoria personal.
```json
{
  "name": "memory.store",
  "params": {"content": "string", "category": "string? (preference|fact|event|contact)", "importance": "float? (0-1)"},
  "risk": "WRITE_SAFE",
  "example": "memory.store(content=\"A Sarah le gusta el sushi\", category=\"preference\")"
}
```

### memory.forget
Elimina información de la memoria personal.
```json
{
  "name": "memory.forget",
  "params": {"query": "string", "confirm": "boolean"},
  "risk": "WRITE_DESTRUCTIVE",
  "example": "memory.forget(query=\"datos de contacto de Juan\", confirm=true)"
}
```

### memory.briefing
Genera el Morning Briefing con resumen del día, calendario, mensajes y clima.
```json
{
  "name": "memory.briefing",
  "params": {"speak": "boolean? (default: true)", "persona": "string? (persona para la voz)"},
  "risk": "READ",
  "example": "memory.briefing(speak=true, persona=\"hestia\")"
}
```

### memory.people
Consulta el People Graph: relaciones, contactos frecuentes.
```json
{
  "name": "memory.people",
  "params": {"name": "string? (nombre del contacto)", "depth": "int? (profundidad, default: 1)"},
  "risk": "READ",
  "example": "memory.people(name=\"Sarah\", depth=2)"
}
```

---

## Módulo 5: Calendario

### calendar.read
Lee eventos del calendario.
```json
{
  "name": "calendar.read",
  "params": {"date": "string? (YYYY-MM-DD)", "days": "int?"},
  "risk": "READ",
  "example": "calendar.read(date=\"2025-01-20\", days=3)"
}
```

### calendar.create
Crea un evento en el calendario.
```json
{
  "name": "calendar.create",
  "params": {"title": "string", "date": "string", "time": "string?", "duration_minutes": "int?", "location": "string?"},
  "risk": "WRITE_SAFE",
  "example": "calendar.create(title=\"Reunión con Sarah\", date=\"2025-01-20\", time=\"15:00\")"
}
```

---

## Módulo 6: Notificaciones

### notification.send
Muestra una notificación local.
```json
{
  "name": "notification.send",
  "params": {"title": "string", "message": "string", "priority": "string?"},
  "risk": "WRITE_SAFE",
  "example": "notification.send(title=\"Recordatorio\", message=\"Reunión en 15 minutos\")"
}
```

### notification.read
Lee las notificaciones pendientes del dispositivo.
```json
{
  "name": "notification.read",
  "params": {"package": "string?", "limit": "int?"},
  "risk": "READ",
  "example": "notification.read(package=\"com.whatsapp\")"
}
```

---

## Módulo 7: Sistema

### system.settings
Abre o modifica configuración del sistema.
```json
{
  "name": "system.settings",
  "params": {"setting": "string (wifi|bluetooth|brightness|volume|airplane|dnd)", "action": "string", "value": "string?"},
  "risk": "WRITE_SAFE",
  "example": "system.settings(setting=\"wifi\", action=\"toggle\")"
}
```

### system.status
Obtiene el estado del dispositivo (batería, RAM, Bypass Charging).
```json
{
  "name": "system.status",
  "params": {},
  "risk": "READ",
  "example": "system.status()"
}
```

---

## Módulo 8: Personas e Identidad (NUEVO v4)

### persona.switch
Cambia la personalidad del asistente entre 6 personas.
```json
{
  "name": "persona.switch",
  "params": {"persona": "string (hestia|metis|argus|athena|selene|iris)"},
  "risk": "WRITE_SAFE",
  "example": "persona.switch(persona=\"metis\")"
}
```

**Personas disponibles:**
| Persona | Estilo | Ideal para | Voz |
|---------|--------|-----------|-----|
| Hestia | Hogar/Cálida | Interacciones cotidianas | Femenina cálida |
| Metis | Estratégica/Concisa | Planificación, eficiencia | Femenina neutra |
| Argus | Seguridad/Vigilante | Detección de amenazas | Femenina firme |
| Athena | Sabiduría/Detallada | Aprendizaje, explicaciones | Femenina erudita |
| Selene | Noche/Contemplativa | Horas nocturnas, reflexión | Femenina suave |
| Iris | Social/Creativa | Comunicaciones, redes | Femenina expresiva |

### persona.list
Lista las 6 personas disponibles.
```json
{
  "name": "persona.list",
  "params": {},
  "risk": "READ",
  "example": "persona.list()"
}
```

---

## Módulo 9: Voz TTS (NUEVO v4)

### voice.speak
Sintetiza texto a voz. Usa Piper TTS (offline) u OpenAI/ElevenLabs (cloud).
```json
{
  "name": "voice.speak",
  "params": {"text": "string", "persona": "string?", "mode": "string? (offline|cloud|auto)"},
  "risk": "WRITE_SAFE",
  "example": "voice.speak(text=\"Buenos días, tu briefing está listo\")"
}
```

### voice.set_mode
Cambia el modo de voz: offline (Piper 100% privado), cloud (máxima naturalidad), auto.
```json
{
  "name": "voice.set_mode",
  "params": {"mode": "string (offline|cloud|auto)"},
  "risk": "WRITE_SAFE",
  "example": "voice.set_mode(mode=\"offline\")"
}
```

---

## Módulo 10: Interfaz Híbrida (NUEVO v4)

### canvas.show
Muestra el Canvas en pantalla completa para tareas visuales complejas.
```json
{
  "name": "canvas.show",
  "params": {"mode": "string (coder|visualizer|briefing|editor)", "content": "string?"},
  "risk": "WRITE_SAFE",
  "example": "canvas.show(mode=\"coder\", content=\"<h1>Hola</h1>\")"
}
```

**Modos del Canvas:**
- `coder`: Vibe Coder — editor de código HTML/JS con preview
- `visualizer`: Gráficos y visualizaciones (People Graph, datos)
- `briefing`: Morning Briefing extendido con formato rico
- `editor`: Editor de SOUL.md y system prompts

### canvas.hide
Oculta el Canvas y vuelve a la vista normal.
```json
{
  "name": "canvas.hide",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "canvas.hide()"
}
```

### smartcast.project
Proyecta el Canvas a pantalla externa via Z-SmartCast.
```json
{
  "name": "smartcast.project",
  "params": {"mode": "string? (mirror|extend, default: mirror)"},
  "risk": "WRITE_SAFE",
  "example": "smartcast.project(mode=\"mirror\")"
}
```

### smartcast.stop
Detiene la proyección a pantalla externa.
```json
{
  "name": "smartcast.stop",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "smartcast.stop()"
}
```

### splitscreen.enter
Activa pantalla dividida. El Canvas se muestra en una mitad.
```json
{
  "name": "splitscreen.enter",
  "params": {"ratio": "float? (0.4|0.5|0.6)", "canvas_on_top": "boolean?"},
  "risk": "WRITE_SAFE",
  "example": "splitscreen.enter(ratio=0.5, canvas_on_top=true)"
}
```

### splitscreen.exit
Sale del modo pantalla dividida.
```json
{
  "name": "splitscreen.exit",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "splitscreen.exit()"
}
```

---

## Módulo 11: Orquestación Nubia (NUEVO v4)

### bypass.curate
Fuerza la curación de memoria (indexación pesada). Se recomienda solo durante Bypass Charging.
```json
{
  "name": "bypass.curate",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "bypass.curate()"
}
```

### bypass.status
Verifica si el Bypass Charging está activo y si hay curación en progreso.
```json
{
  "name": "bypass.status",
  "params": {},
  "risk": "READ",
  "example": "bypass.status()"
}
```

### camera.snap
Toma una foto con la cámara usando Neovision AI del Nubia Neo 3.
```json
{
  "name": "camera.snap",
  "params": {"camera": "string? (back|front)", "flash": "boolean?"},
  "risk": "WRITE_SAFE",
  "example": "camera.snap(camera=\"back\", flash=false)"
}
```

---

## Módulo 12: Seguridad (NUEVO v4)

### scam.detect
Analiza un mensaje para detectar si es un scam/phishing.
```json
{
  "name": "scam.detect",
  "params": {"text": "string", "source": "string? (sms|email|whatsapp|unknown)"},
  "risk": "READ",
  "example": "scam.detect(text=\"Ha ganado un premio...\", source=\"sms\")"
}
```

### profile.encrypt
Encripta el Living Profile con AES-256-GCM via Android Keystore.
```json
{
  "name": "profile.encrypt",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "profile.encrypt()"
}
```

### profile.backup
Crea backup encriptado del Living Profile en SecureVault.
```json
{
  "name": "profile.backup",
  "params": {},
  "risk": "WRITE_SAFE",
  "example": "profile.backup()"
}
```

---

## Módulo 13: Utilidades (NUEVO v4)

### translate
Traduce texto entre idiomas. Funciona offline cuando no hay internet.
```json
{
  "name": "translate",
  "params": {"text": "string", "from": "string (código idioma)", "to": "string (código idioma)"},
  "risk": "READ",
  "example": "translate(text=\"Hello world\", from=\"en\", to=\"es\")"
}
```

---

## Resumen de Herramientas por Módulo

| Módulo | Herramientas | Count |
|--------|-------------|-------|
| Comunicación | sms.send, whatsapp.send, call.make | 3 |
| Aplicaciones | app.launch, app.close | 2 |
| Pantalla | screen.read, screen.tap, screen.type, screen.scroll | 4 |
| Memoria | memory.recall, memory.store, memory.forget, memory.briefing, memory.people | 5 |
| Calendario | calendar.read, calendar.create | 2 |
| Notificaciones | notification.send, notification.read | 2 |
| Sistema | system.settings, system.status | 2 |
| Personas | persona.switch, persona.list | 2 |
| Voz | voice.speak, voice.set_mode | 2 |
| Interfaz | canvas.show, canvas.hide, smartcast.project, smartcast.stop, splitscreen.enter, splitscreen.exit | 6 |
| Nubia | bypass.curate, bypass.status, camera.snap | 3 |
| Seguridad | scam.detect, profile.encrypt, profile.backup | 3 |
| Utilidades | translate | 1 |
| **TOTAL** | | **37** |

---

## Esquema de la Base de Datos Vectorial (SQLiteVecEngine)

```
┌──────────────────────────────────────────────────────────────┐
│  nubia_vectors.db                                            │
│                                                              │
│  vec_embeddings                                              │
│  ├── id INTEGER PRIMARY KEY AUTOINCREMENT                    │
│  ├── fact_id INTEGER NOT NULL (ref a Room DB)               │
│  ├── content TEXT NOT NULL (texto original)                  │
│  ├── category TEXT NOT NULL DEFAULT 'fact'                   │
│  ├── importance REAL NOT NULL DEFAULT 0.5                    │
│  ├── embedding BLOB NOT NULL (float32[384])                  │
│  ├── timestamp INTEGER NOT NULL                              │
│  └── access_count INTEGER NOT NULL DEFAULT 0                 │
│                                                              │
│  vec_metadata                                                │
│  ├── id INTEGER PRIMARY KEY AUTOINCREMENT                    │
│  ├── fact_id INTEGER NOT NULL                                │
│  ├── key TEXT NOT NULL                                       │
│  └── value TEXT NOT NULL                                     │
│                                                              │
│  Índices:                                                    │
│  ├── idx_vec_fact_id ON vec_embeddings(fact_id)              │
│  ├── idx_vec_category ON vec_embeddings(category)            │
│  └── idx_vec_timestamp ON vec_embeddings(timestamp)          │
│                                                              │
│  En memoria (RAM): ~15MB por 10K vectores × 384 dims        │
│  Búsqueda: O(n) cosine similarity, <80ms en T8300            │
│  Cache LRU: 100 consultas frecuentes                        │
└──────────────────────────────────────────────────────────────┘
```

## Encriptación del Living Profile (CellClaw Model)

```
┌──────────────────────────────────────────────────────────────┐
│  Flujo de Encriptación AES-256-GCM                           │
│                                                              │
│  1. Clave: AES-256 del Android Keystore (TEE del T8300)     │
│     └── Almacenada en SecureVault (EncryptedSharedPreferences)│
│                                                              │
│  2. Encriptar:                                               │
│     plaintext → [nonce(12B)] + [AES-256-GCM(ciphertext)]    │
│     └── Nonce aleatorio por operación                        │
│     └── Auth tag de 128 bits integrado                       │
│                                                              │
│  3. Almacenar:                                               │
│     living_profile.enc → almacenamiento interno              │
│     profile_backup → SecureVault                             │
│                                                              │
│  4. Desencriptar:                                            │
│     [nonce] + [ciphertext+tag] → AES-256-GCM → plaintext    │
│     └── Solo en memoria, nunca escribir plaintext a disco    │
│                                                              │
│  Garantías:                                                  │
│  ├── Clave nunca sale del TEE                                │
│  ├── AEAD: integridad + confidencialidad                     │
│  ├── Resiste cold-boot attacks                               │
│  └── AEADBadTagException si datos fueron modificados         │
└──────────────────────────────────────────────────────────────┘
```
