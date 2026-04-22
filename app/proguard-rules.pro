# Proguard rules for TG WS Proxy
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Keep OkHttp WebSocket
-keep class okhttp3.internal.ws.** { *; }
-keep class okio.** { *; }

# Keep crypto classes
-keep class javax.crypto.** { *; }

# Keep proxy core classes
-keep class com.github.tgwsproxy.** { *; }