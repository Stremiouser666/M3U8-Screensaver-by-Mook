# ProGuard Rules for M3U8 Screensaver (Standard Flavor)

# ======================
# GENERAL RULES
# ======================

# Keep all classes in your app package (with obfuscation)
-keep class com.livescreensaver.tv.** { *; }
-keep class com.livescreensaver.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable classes
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ======================
# LIBRARY RULES
# ======================

# Mozilla Rhino (JavaScript engine)
-dontwarn org.mozilla.javascript.**
-dontwarn sun.reflect.**
-keep class org.mozilla.javascript.** { *; }
-keep interface org.mozilla.javascript.** { *; }
-keepclassmembers class org.mozilla.javascript.JavaToJSONConverters { *; }

# NewPipe Extractor
-dontwarn org.schabi.newpipe.**
-keep class org.schabi.newpipe.** { *; }
-keep interface org.schabi.newpipe.** { *; }

# OkHttp3
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlinx Coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# AndroidX Media3 (ExoPlayer)
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** {
    public <methods>;
}

# AndroidX Preferences
-keep class androidx.preference.** { *; }
-keep interface androidx.preference.** { *; }

# AndroidX Leanback
-keep class androidx.leanback.** { *; }
-keep interface androidx.leanback.** { *; }

# JSON
-dontwarn org.json.**
-keep class org.json.** { *; }

# ======================
# ANDROID FRAMEWORK
# ======================

# Keep Android framework classes
-keep class android.** { *; }
-keep interface android.** { *; }

# Keep service, activity, fragment, etc.
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep view constructors (for inflation)
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ======================
# KEEP ANNOTATIONS
# ======================

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class androidx.annotation.** { *; }

# ======================
# DEBUGGING
# ======================

# Keep line numbers for crash reports
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ======================
# OPTIMIZATION
# ======================

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Allow aggressive optimizations
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-optimizationpasses 5

# ======================
# WARNINGS
# ======================

# Suppress warnings for libraries
-dontwarn sun.misc.**
-dontwarn sun.reflect.**
-dontwarn java.lang.invoke.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn com.google.common.**
