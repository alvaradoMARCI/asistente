# NubiaAgent ProGuard Rules
# Optimizado para Unisoc T8300 / NeoTurbo

# Vosk native methods
-keep class com.alphacephei.vosk.** { *; }
-keep class org.vosk.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# Accessibility node info
-keep class android.view.accessibility.AccessibilityNodeInfo { *; }

# Notification data classes
-keep class com.nubiaagent.perception.events.** { *; }

# Hardware state models
-keep class com.nubiaagent.perception.hardware.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
