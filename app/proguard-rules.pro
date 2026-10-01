# MSLX Console ProGuard rules.

# Keep Gson model fields used by the API / SignalR JSON payloads.
-keep class com.wludy.rolithax.launcher.data.model.** { *; }

# SignalR Java client (uses Gson reflection).
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
