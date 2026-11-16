# Proguard rules for VideoHatak
-keep class com.videohatak.player.core.model.** { *; }
-keep class com.videohatak.player.core.vault.model.** { *; }
-keep class com.videohatak.player.core.playlist.model.** { *; }
-keep class com.videohatak.player.core.subtitle.model.** { *; }

# Google ML Kit Translation & Language ID
-keep class com.google.mlkit.** { *; }
-keepclassmembers class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Vosk Speech Recognition & JNA native interop
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** {
    native <methods>;
    *;
}
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* {
    public *;
}
-dontwarn com.sun.jna.**
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes Signature,InnerClasses,EnclosingMethod
