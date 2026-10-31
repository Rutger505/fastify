# Shrink only: R8's optimization passes cost ~45 s per release on the 4-core VM, for ~3 MB of APK.
-dontoptimize

# JNA + UniFFI bindings are reached reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.rutger.fastify.core.** { *; }
-dontwarn java.awt.**
