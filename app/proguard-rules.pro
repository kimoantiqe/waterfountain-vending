# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ===================================
# WATER FOUNTAIN VENDING MACHINE
# ProGuard/R8 Security Configuration
# ===================================

# Keep line numbers for crash reporting (Crashlytics)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Remove AppLog debug methods in release
-assumenosideeffects class com.waterfountainmachine.app.utils.AppLog {
    public static *** d(...);
    public static *** v(...);
}

# ===================================
# Firebase
# ===================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Firebase Crashlytics
-keepattributes LineNumberTable,SourceFile
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**

# Firebase Functions
-keep class com.google.firebase.functions.** { *; }
-keep interface com.google.firebase.functions.** { *; }

# ===================================
# Security - Certificate & Crypto
# ===================================
# Keep security classes for certificate authentication
-keep class com.waterfountainmachine.app.security.** { *; }
-keep class com.waterfountainmachine.app.admin.AdminPinManager { *; }

# Keep certificate and key management
-keep class androidx.security.crypto.** { *; }
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# ===================================
# Data Models & API
# ===================================
# Keep authentication data models
-keep class com.waterfountainmachine.app.auth.** { *; }

# Keep admin models
-keep class com.waterfountainmachine.app.admin.models.** { *; }

# ===================================
# Hardware SDK (Vendor)
# ===================================
# Keep vendor SDK classes (SerialPortUtils)
-keep class com.yishengkj.** { *; }
-keep class com.yy.** { *; }
-keep interface com.yishengkj.** { *; }
-keep interface com.yy.** { *; }

# ===================================
# Kotlin & Coroutines
# ===================================
-keepclassmembers class kotlinx.coroutines.** { *; }
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ===================================
# QR Code (ZXing)
# ===================================
-keep class com.google.zxing.** { *; }
-keep interface com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ===================================
# Confetti Animation
# ===================================
-keep class nl.dionsegijn.konfetti.** { *; }

# ===================================
# Android Components
# ===================================
# Keep Activities, Fragments, Services
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep View constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep ViewBinding classes
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater);
    public static *** bind(android.view.View);
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===================================
# Optimization Settings
# ===================================
# Enable aggressive optimization
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Allow obfuscation of package names
-repackageclasses ''
-allowaccessmodification

# ===================================
# Debugging (only in release)
# ===================================
# Remove System.out and System.err
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void println(**);
}

# ===================================
# Warnings to ignore
# ===================================
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**