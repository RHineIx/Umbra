# Modern LibXposed API API 101+
-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# DexKit 2.1.0 (Required for Native JNI bindings stability)
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# CRITICAL: Keep Module Entry Point exactly as declared in java_init.list
-keep class com.umbra.hooks.MainHook { *; }

# Preserve standard annotations and metadata required by reflection
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

# Extreme Optimization & Obfuscation for DEX Shrinking
-repackageclasses 'a'
-allowaccessmodification
-optimizationpasses 5

# Strip SourceFile attributes and line numbers to further compress DEX
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable