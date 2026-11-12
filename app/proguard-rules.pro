# Line numbers and source file attributes for Crashlytics stacktraces
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,AnnotationDefault,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep public class * extends java.lang.Exception

# -----------------------------------------------------------------------------
# App Data Models & DTOs (Prevents Gson / R8 obfuscation & abstract class error)
# -----------------------------------------------------------------------------
-keep class com.xeniaone.templeapp.network.model.** { *; }
-keepclassmembers class com.xeniaone.templeapp.network.model.** { *; }

-keep class com.xeniaone.templeapp.model.** { *; }
-keepclassmembers class com.xeniaone.templeapp.model.** { *; }

-keep class com.xeniaone.templeapp.network.** { *; }
-keep class com.xeniaone.templeapp.pinelabs.** { *; }
-keepclassmembers class com.xeniaone.templeapp.pinelabs.** { *; }

# -----------------------------------------------------------------------------
# Gson & SerializedName Rules
# -----------------------------------------------------------------------------
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn sun.misc.**

# -----------------------------------------------------------------------------
# Retrofit Rules
# -----------------------------------------------------------------------------
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}

# -----------------------------------------------------------------------------
# SignalR / SLF4J
# -----------------------------------------------------------------------------
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
-keep class com.microsoft.signalr.** { *; }

# -----------------------------------------------------------------------------
# Razorpay SDK
# -----------------------------------------------------------------------------
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-dontwarn com.google.android.apps.nbu.paisa.**
-dontwarn proguard.annotation.**
-keepattributes JavascriptInterface
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# -----------------------------------------------------------------------------
# Firebase & Crashlytics
# -----------------------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
