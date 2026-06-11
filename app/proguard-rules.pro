# NubiaAgent ProGuard Rules
# Optimizado para Unisoc T8300 / NeoTurbo

# ==================== NATIVE LIBRARIES ====================

# Vosk native methods
-keep class com.alphacephei.vosk.** { *; }
-keep class org.vosk.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# ONNX Runtime
-keep class com.microsoft.onnxruntime.** { *; }

# llama.cpp JNI bridge
-keep class com.nubiaagent.cognitive.engine.CognitiveEngine$LlamaNative { *; }
-keepclassmembers class com.nubiaagent.cognitive.engine.CognitiveEngine$LlamaNative {
    native <methods>;
}

# ==================== ROOM DATABASE ====================

# Room entities
-keep class com.nubiaagent.cognitive.memory.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ==================== DATA MODELS ====================

# Accessibility node info
-keep class android.view.accessibility.AccessibilityNodeInfo { *; }

# Notification data classes
-keep class com.nubiaagent.perception.events.** { *; }

# Hardware state models
-keep class com.nubiaagent.perception.hardware.** { *; }

# Perception bus events
-keep class com.nubiaagent.core.PerceptionEvent { *; }
-keep class com.nubiaagent.core.PerceptionEvent$* { *; }

# Memory data classes
-keep class com.nubiaagent.cognitive.memory.** { *; }

# Persona data
-keep class com.nubiaagent.cognitive.persona.** { *; }

# Orchestration data
-keep class com.nubiaagent.orchestration.** { *; }

# ==================== KOTLIN & COROUTINES ====================

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlin serialization
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }

# ==================== GSON ====================

# Gson specific rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ==================== ANDROID COMPONENTS ====================

# Services declared in manifest
-keep class com.nubiaagent.perception.ear.WakeWordService { *; }
-keep class com.nubiaagent.perception.vision.ScreenObserver { *; }
-keep class com.nubiaagent.perception.events.NotificationInterceptor { *; }
-keep class com.nubiaagent.cognitive.engine.CognitiveEngine { *; }
-keep class com.nubiaagent.execution.overlay.OverlayService { *; }
-keep class com.nubiaagent.ui.bubble.FloatingBubbleMecha { *; }
-keep class com.nubiaagent.ui.canvas.CanvasController { *; }
-keep class com.nubiaagent.ui.liveisland.LiveIsland2 { *; }
-keep class com.nubiaagent.core.BootReceiver { *; }
-keep class com.nubiaagent.perception.hardware.ActivityTransitionReceiver { *; }
