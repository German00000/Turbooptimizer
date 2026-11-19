# Shizuku instancia el UserService por reflexión en un proceso separado.
-keep class com.turbooptimizer.core.shizuku.ShellUserService { <init>(...); *; }
-keep class com.turbooptimizer.core.shizuku.IShellService { *; }
-keep class com.turbooptimizer.core.shizuku.IShellService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# kotlinx.serialization (registro de operaciones)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.turbooptimizer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.turbooptimizer.**$$serializer { *; }
