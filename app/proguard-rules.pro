# AlbionPlayersRadar ProGuard Rules

# Keep Photon packet classes
-keep class com.albionplayersradar.parser.** { *; }
-keep class com.albionplayersradar.data.** { *; }

# Kotlin
-keepattributes *Annotation*
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
