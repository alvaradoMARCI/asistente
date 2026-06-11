# NubiaAgent

**Asistente de IA Personal, Privado y Autónomo para ZTE Nubia Neo 3 5G**

## Capa de Percepción - Arquitectura

NubiaAgent es un agente cognitivo que opera bajo un ciclo **Pensar → Actuar → Observar** (Agent Loop). Esta es la implementación de la **Capa de Percepción**, responsable de capturar todas las entradas del entorno del dispositivo.

### Módulos

| Módulo | Clase | Función |
|--------|-------|---------|
| **Ear** | `WakeWordService` | Detección de "Hey Nubia" 100% offline via Vosk + ForegroundService con buffer circular |
| **Vision** | `ScreenObserver` | Percepción de UI via AccessibilityService + filtrado inteligente a ~40 elementos + screenshot fallback |
| **Events** | `NotificationInterceptor` | Interceptación y clasificación de notificaciones con pipeline Extractar → Clasificar → Decidir |
| **Hardware** | `HardwareStateCollector` | Telemetría: batería, Bypass Charging, GPS, actividad física, pasos |

### Bus de Eventos Central

Todos los módulos publican eventos al `PerceptionBus` (SharedFlow), que es consumido por la Capa Cognitiva (Agent Loop).

### Restricción de Seguridad

⚠️ **Todo el procesamiento de datos de esta capa es LOCAL-FIRST. No se permite ninguna conexión externa.**

### Estructura del Proyecto

```
app/src/main/java/com/nubiaagent/
├── core/
│   ├── PerceptionBus.kt          # Bus de eventos central (SharedFlow)
│   ├── BootReceiver.kt           # Auto-inicio al encender
│   └── NubiaAgentApp.kt          # Application class
├── perception/
│   ├── ear/
│   │   └── WakeWordService.kt    # Módulo de escucha offline
│   ├── vision/
│   │   └── ScreenObserver.kt     # Módulo de visión de sistema
│   ├── events/
│   │   └── NotificationInterceptor.kt  # Módulo de percepción de eventos
│   └── hardware/
│       └── HardwareStateCollector.kt    # Módulo de telemetría
└── MainActivity.kt               # Activity principal (permisos + estado)

scripts/
└── test_accessibility_tree.py    # Script de prueba para ADB
```

### Configuración

1. Clonar el repositorio
2. Abrir en Android Studio
3. Descargar modelo Vosk español: `vosk-model-small-es-0.42`
4. Colocar modelo en `app/src/main/assets/models/vosk-model-small-es-0.42/`
5. Compilar e instalar en Nubia Neo 3 5G
6. Habilitar servicios de Accesibilidad y Notificaciones

### Hardware Objetivo

- **Dispositivo:** ZTE Nubia Neo 3 5G
- **Procesador:** Unisoc T8300 + NeoTurbo
- **RAM:** 20 GB
- **Feature:** Bypass Charging para sesiones de IA prolongadas
