# ============================================================
# UMBRA - PRODUCTION RULES
# Target: Clean, Small, Secure
# ============================================================

# --- 1. CORE ENTRY POINTS (Essential for Xposed) ---
-keep class com.umbra.hooks.MainHook { *; }
-keep class com.umbra.hooks.apps.** { *; }
-keep class com.umbra.hooks.utils.ConfigProvider { *; }
-keep class com.umbra.hooks.utils.** { *; }

# --- 2. DEPENDENCIES (Xposed & DexKit) ---
# Xposed Bridge
-keep class de.robv.android.xposed.** { *; }

# DexKit (Native Bridge needs these classes intact)
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

-repackageclasses 'a'

-allowaccessmodification

-renamesourcefileattribute ""
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

-optimizationpasses 5

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

-dontwarn com.google.android.material.**
-dontwarn androidx.**
-ignorewarnings

-keepclassmembers class * implements kotlin.coroutines.Continuation {
    public <init>(...);
}