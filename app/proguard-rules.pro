-keep class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

-keep public class com.umbra.hooks.MainHook {
    public <init>();
}

# Keep ViewModel constructors safe for Jetpack Lifecycle
-keepclassmembers public class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Move all obfuscated classes into a single meaningless package
-repackageclasses 'x'
-allowaccessmodification
-optimizationpasses 5

# Remove standard Android Log calls to shrink APK (Xposed logs remain safe)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Strip debug info to make reverse engineering significantly harder
-renamesourcefileattribute SourceFile
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable