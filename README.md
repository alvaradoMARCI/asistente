# NubiaAgent

**Asistente de IA Personal, Privado y Autónomo para ZTE Nubia Neo 3 5G**

## Arquitectura de 3 Capas Implementadas

```
┌─────────────────────────────────────────────────────────┐
│                    CAPA 3: EJECUCIÓN                     │
│    ActionDispatcher │ Skills (37) │ SafetyManager        │
│    OverlayService   │ PowerManager │ BiometricGate       │
├─────────────────────────────────────────────────────────┤
│                    CAPA 2: COGNITIVA                     │
│    CognitiveEngine  │ AgentLoop   │ MemoryManager (3 capas)│
│    IdentityManager  │ ToolRegistry│ CurationAgent        │
├─────────────────────────────────────────────────────────┤
│                    CAPA 1: PERCEPCIÓN                    │
│    WakeWordService  │ ScreenObserver │ NotificationInterceptor│
│    HardwareStateCollector │ PerceptionBus               │
└─────────────────────────────────────────────────────────┘
```

---

## Capa 3: Ejecución — Acción y Automatización

### 3.1 Puente de Accesibilidad (UI Automation Bridge)

| Componente | Archivo | Función |
|-----------|---------|---------|
| **ActionDispatcher** | `bridge/ActionDispatcher.kt` | Ejecuta acciones táctiles via AccessibilityService: tap, longPress, typeText, swipe, goBack, goHome |
| **GestureEngine** | `bridge/GestureEngine.kt` | Genera gestos naturales con curvas Bézier, micro-jitters y timing humano para evadir anti-bot |

Características del ActionDispatcher:
- **Reintento inteligente (DroidClaw)**: Si una acción no cambia la pantalla en 3s, reintenta con ruta alternativa o re-lanza la app (máx 3 intentos)
- **Screen state hashing**: SHA-256 del árbol de accesibilidad para detectar cambios
- **3-tier click**: ACTION_CLICK → ancestro clickeable → gesture tap en coordenadas
- **SetText fallback**: ACTION_SET_TEXT → clipboard paste si falla

### 3.2 Controlador de Hardware (System Toolkit)

| Componente | Archivo | Función |
|-----------|---------|---------|
| **HardwareController** | `hardware/HardwareController.kt` | Hub central: SMS, llamadas, cámara, audio, linterna, volumen, brillo, portapapeles, WiFi |
| **PowerManager** | `hardware/PowerManager.kt` | Control de Bypass Charging para Nubia: activación via sysfs/ZTE broadcast, monitoreo térmico |

### 3.3 Guardián de Seguridad

| Componente | Archivo | Función |
|-----------|---------|---------|
| **SafetyManager** | `safety/SafetyManager.kt` | Middleware de validación: clasifica acciones por riesgo, rate limiting, lista negra |
| **BiometricGate** | `safety/BiometricGate.kt` | Verificación biométrica (huella/rostro) para acciones destructivas |
| **SecureVault** | `safety/SecureVault.kt` | Almacén seguro AES-256-GCM via Android Keystore para credenciales |

### 3.4 Interfaz de Feedback Visual

| Componente | Archivo | Función |
|-----------|---------|---------|
| **OverlayService** | `overlay/OverlayService.kt` | Burbuja flotante con estado en tiempo real y botón de parada de emergencia |

---

## Catálogo de 37 Habilidades (Skills)

Ver **[TOOLS.md](TOOLS.md)** para documentación completa con schemas JSON.

### Módulo 1: Comunicación y Mensajería (13 skills)
| Skill | Riesgo | Descripción |
|-------|--------|-------------|
| `sms.send` | DESTRUCTIVE | Enviar SMS |
| `sms.read` | READ | Leer SMS |
| `phone.call` | DESTRUCTIVE | Realizar llamada |
| `phone.callLog` | READ | Registro de llamadas |
| `whatsapp.send` | DESTRUCTIVE | Enviar WhatsApp |
| `whatsapp.read` | READ | Leer WhatsApp |
| `telegram.send` | DESTRUCTIVE | Enviar Telegram |
| `telegram.read` | READ | Leer Telegram |
| `instagram.send` | DESTRUCTIVE | Enviar DM Instagram |
| `email.search` | READ | Buscar correos |
| `email.read` | READ | Leer correo |
| `email.compose` | DESTRUCTIVE | Redactar correo |
| `email.reply` | DESTRUCTIVE | Responder correo |

### Módulo 2: Hardware y Sensores (12 skills)
| Skill | Riesgo | Descripción |
|-------|--------|-------------|
| `camera.snap` | SAFE | Tomar foto (Neovision AI) |
| `camera.record` | SAFE | Grabar video |
| `audio.record` | SAFE | Grabar audio |
| `media.play` | SAFE | Reproducir medio |
| `sensor.read` | READ | Leer sensores |
| `location.get` | READ | Obtener ubicación GPS |
| `torch.toggle` | SAFE | Linterna on/off |
| `settings.volume` | SAFE | Ajustar volumen |
| `settings.brightness` | SAFE | Ajustar brillo |
| `clipboard.set` | SAFE | Copiar al portapapeles |
| `clipboard.get` | READ | Leer portapapeles |
| `wifi.toggle` | SAFE | WiFi on/off |

### Módulo 3: Memoria y Conocimiento (8 skills)
| Skill | Riesgo | Descripción |
|-------|--------|-------------|
| `memory.store` | SAFE | Guardar hecho |
| `memory.recall` | READ | Buscar en memoria |
| `memory.forget` | DESTRUCTIVE | Eliminar de memoria |
| `people.add` | SAFE | Agregar persona al grafo |
| `people.get` | READ | Info de persona |
| `people.relate` | SAFE | Relacionar personas |
| `briefing.generate` | READ | Generar briefing matutino |
| `briefing.schedule` | SAFE | Programar briefing diario |

### Módulo 4: Seguridad y Utilidades IA (5 skills)
| Skill | Riesgo | Descripción |
|-------|--------|-------------|
| `scam.analyze` | READ | Detectar estafas/phishing |
| `vibe.generate` | SAFE | Generar app HTML/JS |
| `translate.text` | READ | Traducción offline es↔en |
| `secure.store` | SAFE | Guardar credencial (AES-256) |
| `secure.get` | READ | Recuperar credencial |

### Módulo 5: Optimización Nubia Core (9 skills)
| Skill | Riesgo | Descripción |
|-------|--------|-------------|
| `bypass.activate` | SAFE | Activar Bypass Charging |
| `bypass.deactivate` | SAFE | Desactivar Bypass |
| `bypass.status` | READ | Estado de carga |
| `screen.split` | SAFE | Pantalla dividida |
| `screen.tap` | SAFE | Tocar elemento |
| `screen.type` | SAFE | Escribir texto |
| `screen.scroll` | READ | Desplazar pantalla |
| `screen.read` | READ | Leer pantalla |
| `app.launch` | SAFE | Abrir aplicación |

---

## Estructura Completa del Proyecto

```
app/src/main/java/com/nubiaagent/
├── core/
│   ├── PerceptionBus.kt          # Bus de eventos central
│   ├── BootReceiver.kt           # Auto-inicio
│   └── NubiaAgentApp.kt          # Application
├── perception/
│   ├── ear/WakeWordService.kt    # Escucha offline
│   ├── vision/ScreenObserver.kt  # Visión de sistema
│   ├── events/NotificationInterceptor.kt
│   └── hardware/HardwareStateCollector.kt
├── cognitive/
│   ├── engine/{CognitiveEngine,ModelManager,InferenceConfig}.kt
│   ├── identity/IdentityManager.kt
│   ├── agent/{AgentLoop,ToolRegistry,ToolExecutor}.kt
│   └── memory/{MemoryManager,DeepArchive,CurationAgent,NubiaDatabase}.kt
├── execution/
│   ├── bridge/{ActionDispatcher,GestureEngine}.kt
│   ├── hardware/{HardwareController,PowerManager}.kt
│   ├── safety/{SafetyManager,BiometricGate,SecureVault}.kt
│   ├── overlay/OverlayService.kt
│   └── skills/
│       ├── communication/{CrossAppMessenger,EmailSkill,SmsPhoneSkill}.kt
│       ├── hardware_ctrl/SensorLocationSkill.kt
│       ├── memory_ctrl/{PeopleGraph,BriefingScheduler}.kt
│       ├── security_ctrl/{ScamDetector,VibeCoder,TranslatorSkill}.kt
│       └── nubiacore/{BypassChargingGuard,SplitScreenSkill,UILearning}.kt
└── MainActivity.kt

app/src/main/assets/soul/SOUL.md
TOOLS.md
```

---

## Restricción de Seguridad

⚠️ **Todo el procesamiento es LOCAL-FIRST. Sin conexiones externas para datos del usuario.**
- Inferencia LLM: 100% en-device (llama.cpp)
- Credenciales: Android Keystore + AES-256-GCM
- Biometría: Verificación en-device via TEE del T8300
- Acciones destructivas: Siempre requieren confirmación biométrica o visual
- Rate limiting: 5 acciones destructivas/min, 20 acciones/min total
- Kill switch: Un toque en la burbuja flotante detiene toda automatización

## Hardware Objetivo

- **Dispositivo:** ZTE Nubia Neo 3 5G
- **Procesador:** Unisoc T8300 + NeoTurbo (2xA76 + 6xA55)
- **RAM:** 20 GB dinámica
- **Feature:** Bypass Charging para sesiones de IA prolongadas sin degradar batería
- **Cámara:** Neovision AI para captura inteligente
