# Modern LibXposed API API 101+
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# DexKit 2.1.0 (Required for Native JNI bindings)
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# CRITICAL: Keep Module Entry Point exactly as declared in java_init.list
-keep class com.umbra.hooks.MainHook { *; }

# Keep internal hook structures intact to prevent aggressive R8 stripping
-keep class com.umbra.hooks.core.** { *; }
-keep class com.umbra.hooks.apps.** { *; }
-keep class com.umbra.hooks.utils.** { *; }
-keep class com.umbra.hooks.BuildConfig { *; }

# Preserve standard annotations and metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Strip Android Logs in Release Build
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Aggressive Optimization (Safe because of the keep rules above)
-repackageclasses 'a'
-allowaccessmodification
-optimizationpasses 5