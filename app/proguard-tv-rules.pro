# =====================================
# ProGuard / R8 rules – ANDROID TV ONLY
# =====================================

#######################################
# APP CODE (TV ONLY)
#######################################

# Keep ONLY the TV package
-keep class com.livescreensaver.tv.** { *; }

#######################################
# ANDROID COMPONENTS (REQUIRED)
#######################################

# Activities, Services, Receivers
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Fragments actually used on TV
-keep public class * extends androidx.fragment.app.Fragment

# Views (inflation safety)
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

#######################################
# ANDROID TV / LEANBACK
#######################################

-keep class androidx.leanback.** { *; }
-keep interface androidx.leanback.** { *; }

#######################################
# MEDIA / PLAYER (MEDIA3 / EXOPLAYER)
#######################################

-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

#######################################
# NEWPIPE EXTRACTOR
#######################################

-dontwarn org.schabi.newpipe.**
-keep class org.schabi.newpipe.** { *; }
-keep interface org.schabi.newpipe.** { *; }

#######################################
# JAVASCRIPT (RHINO)
#######################################

-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }
-keep interface org.mozilla.javascript.** { *; }

#######################################
# NETWORKING
#######################################

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

#######################################
# KOTLIN / COROUTINES
#######################################

-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }

#######################################
# PARCELABLE / ENUM SAFETY
#######################################

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

#######################################
# REFLECTION / NATIVE
#######################################

-keepclasseswithmembernames class * {
    native <methods>;
}

#######################################
# ANNOTATIONS / DEBUG INFO
#######################################

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

#######################################
# LOG STRIPPING (SAFE FOR TV)
#######################################

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

#######################################
# OPTIMIZATION (ALLOW SHRINKING)
#######################################

-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

#######################################
# WARNINGS
#######################################

-dontwarn sun.misc.**
-dontwarn sun.reflect.**
-dontwarn java.lang.invoke.**
-dontwarn java.beans.**
-dontwarn javax.script.**
