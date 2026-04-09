-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# CRITICAL: Preserve Module Entry Point
-keep class com.umbra.hooks.MainHook { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

-repackageclasses 'a'
-allowaccessmodification
-optimizationpasses 5

-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable