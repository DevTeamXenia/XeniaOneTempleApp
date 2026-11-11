-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# SignalR / SLF4J rules for release minification
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }
-keep class com.microsoft.signalr.** { *; }

# Razorpay SDK rules
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-dontwarn com.google.android.apps.nbu.paisa.**
-dontwarn proguard.annotation.**

