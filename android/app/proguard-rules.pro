# PhotographerCamera release hardening.
# The GLSL shader strings, baked LUT builders, and JSON profiles must NOT be stripped.

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep the GPU renderer + profile model (kotlinx.serialization relies on metadata).
-keep class com.photographercamera.core.** { *; }
-keep class com.photographercamera.app.** { *; }

# kotlinx.serialization
-keepclassmembers class kotlin.Metadata { *; }
-keep,includedescriptorclasses class com.photographercamera.core.profile.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }

# GL surface / EGL / GLES are system APIs; nothing to keep there.
-dontwarn org.jetbrains.annotations.**
