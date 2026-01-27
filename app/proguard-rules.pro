-keep class com.umbra.hooks.MainHook { *; }
-keep class com.umbra.hooks.apps.** { *; }
-keep class com.umbra.hooks.utils.** { *; }
-keep class com.umbra.hooks.BuildConfig { *; }

-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

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