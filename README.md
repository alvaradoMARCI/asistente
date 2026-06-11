# NubiaAgent

**Asistente de IA Personal, Privado y Autónomo para ZTE Nubia Neo 3 5G**

## Arquitectura de 2 Capas Implementadas

NubiaAgent es un agente cognitivo que opera bajo un ciclo **Pensar → Actuar → Observar** (Agent Loop). Se compone de capas modulares que se comunican vía `PerceptionBus`.

---

## Capa 1: Percepción — Captura del Entorno

| Módulo | Clase | Función |
|--------|-------|---------|
| **Ear** | `WakeWordService` | Detección de "Hey Nubia" 100% offline via Vosk + ForegroundService con buffer circular |
| **Vision** | `ScreenObserver` | Percepción de UI via AccessibilityService + filtrado inteligente a ~40 elementos + screenshot fallback |
| **Events** | `NotificationInterceptor` | Interceptación y clasificación de notificaciones con pipeline Extraer → Clasificar → Decidir |
| **Hardware** | `HardwareStateCollector` | Telemetría: batería, Bypass Charging, GPS, actividad física, pasos |

---

## Capa 2: Cognitiva — Razonamiento y Memoria

### 2.1 Motor de Inferencia Local

| Componente | Clase | Función |
|------------|-------|---------|
| **CognitiveEngine** | `CognitiveEngine.kt` | ForegroundService con modelo GGUF cargado en RAM, inferencia via llama.cpp JNI, streaming de tokens |
| **ModelManager** | `ModelManager.kt` | Descarga, verificación (GGUF magic), selección de modelo óptimo según RAM disponible |
| **InferenceConfig** | `InferenceConfig.kt` | Configuración adaptativa: ajusta threads/ctx/batch según batería y Bypass Charging |
| **IdentityManager** | `IdentityManager.kt` | Carga SOUL.md (instrucciones maestras), construye system prompt en capas, formato ChatML |
| **SOUL.md** | `assets/soul/SOUL.md` | Identidad del agente: filosofía Pensar→Actuar→Observar, restricciones de privacidad, tono |

**Modelos soportados (GGUF ARM64):**

| Modelo | Tamaño (Q4_K_M) | RAM | Vel. aprox (T8300) |
|--------|-----------------|-----|---------------------|
| Llama 3.2 1B | ~800 MB | ~2 GB | ~25 tok/s |
| Llama 3.2 3B | ~2 GB | ~4 GB | ~12 tok/s |
| Gemma 2 2B | ~1.5 GB | ~3 GB | ~18 tok/s |
| Qwen 2.5 3B | ~2 GB | ~4 GB | ~12 tok/s |

### 2.2 Agent Loop y Herramientas

| Componente | Clase | Función |
|------------|-------|---------|
| **AgentLoop** | `AgentLoop.kt` | Ciclo Pensar→Actuar→Observar con re-planificación (máx 10 iter), contexto acumulativo, integración con memoria |
| **ToolRegistry** | `ToolRegistry.kt` | 17 herramientas con schemas JSON, categorías por riesgo (READ/WRITE_SAFE/WRITE_DESTRUCTIVE) |
| **ToolExecutor** | `ToolExecutor.kt` | Ejecución real de herramientas via Android APIs (SmsManager, Intent, AccessibilityService) |
| **AutonomyProfile** | `AutonomyProfile.kt` | 3 niveles: Cauto (confirma todo), Balanceado (lecturas auto), Full Auto (ejecución total) |

**Herramientas disponibles:**

| Categoría | Herramientas |
|-----------|-------------|
| Comunicaciones | `sms.send`, `whatsapp.send`, `call.make` |
| Aplicaciones | `app.launch`, `app.close` |
| Pantalla | `screen.read`, `screen.tap`, `screen.type`, `screen.scroll` |
| Memoria | `memory.recall`, `memory.store`, `memory.forget` |
| Calendario | `calendar.read`, `calendar.create` |
| Notificaciones | `notification.send`, `notification.read` |
| Sistema | `system.settings`, `system.status` |

### 2.3 Sistema de Memoria Persistente (3 Capas)

| Capa | Componente | Almacenamiento | Latencia | Capacidad |
|------|-----------|----------------|----------|-----------|
| **1. Living Profile** | `IdentityManager` | Archivo local | <1ms | ~3,500 tokens |
| **2. Rolling Context** | `InteractionDao` (Room/SQLite) | Base de datos local | <10ms | Últimas 100 interacciones |
| **3. Deep Archive** | `DeepArchive` + `FactDao` | Vector store binario + Room | <100ms | Ilimitada |

**Entidades Room:**
- `Interaction` — Conversaciones usuario-asistente
- `Fact` — Hechos extraídos con embeddings para búsqueda semántica
- `FrequentContact` — Contactos frecuentes con métricas
- `UserPattern` — Patrones de comportamiento detectados

**CurationAgent** — Procesamiento en segundo plano:
- Se activa con Bypass Charging
- Extrae hechos de conversaciones usando el LLM
- Detecta patrones temporales y de comportamiento
- Actualiza el Living Profile consolidando información nueva
- Limpia memoria duplicada y obsoleta

---

## Estructura del Proyecto

```
app/src/main/java/com/nubiaagent/
├── core/
│   ├── PerceptionBus.kt          # Bus de eventos central (SharedFlow)
│   ├── BootReceiver.kt           # Auto-inicio al encender
│   └── NubiaAgentApp.kt          # Application class
├── perception/
│   ├── ear/WakeWordService.kt    # Escucha offline
│   ├── vision/ScreenObserver.kt  # Visión de sistema
│   ├── events/NotificationInterceptor.kt  # Percepción de eventos
│   └── hardware/HardwareStateCollector.kt  # Telemetría
├── cognitive/
│   ├── engine/
│   │   ├── CognitiveEngine.kt    # Motor de inferencia LLM
│   │   ├── ModelManager.kt       # Gestión de modelos GGUF
│   │   └── InferenceConfig.kt    # Configuración adaptativa
│   ├── identity/
│   │   └── IdentityManager.kt    # SOUL.md + system prompt
│   ├── agent/
│   │   ├── AgentLoop.kt          # Ciclo Pensar→Actuar→Observar
│   │   ├── ToolRegistry.kt       # Definición de herramientas
│   │   └── ToolExecutor.kt       # Ejecución de herramientas
│   └── memory/
│       ├── MemoryManager.kt      # Orquestador de 3 capas
│       ├── DeepArchive.kt        # Vector store local
│       ├── CurationAgent.kt      # Curación en segundo plano
│       └── db/
│           └── NubiaDatabase.kt  # Room DB (entities + DAOs)
└── MainActivity.kt

app/src/main/assets/soul/
└── SOUL.md                       # Instrucciones maestras del agente
```

---

## Restricción de Seguridad

⚠️ **Todo el procesamiento es LOCAL-FIRST. Sin conexiones externas para datos del usuario.**
- Inferencia LLM: 100% en-device (llama.cpp)
- Embeddings: Calculados localmente (ONNX Runtime / TFLite)
- Memoria: Room + vector store local, sin sincronización cloud
- La única conexión de red permitida es la descarga inicial de modelos GGUF

## Hardware Objetivo

- **Dispositivo:** ZTE Nubia Neo 3 5G
- **Procesador:** Unisoc T8300 + NeoTurbo (2xA76 + 6xA55)
- **RAM:** 20 GB dinámica
- **Feature:** Bypass Charging para sesiones de IA prolongadas sin degradar batería
