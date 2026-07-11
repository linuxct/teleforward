# TeleForward ProGuard rules.
# Minification is disabled by default (see app/build.gradle.kts). These rules
# exist so a future release build with R8 enabled keeps serialization + Retrofit
# metadata intact.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class space.linuxct.teleforward.data.telegram.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class space.linuxct.teleforward.data.telegram.dto.**$$serializer { *; }
-keepclassmembers class space.linuxct.teleforward.data.telegram.dto.** {
    *** Companion;
}

# Retrofit
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
