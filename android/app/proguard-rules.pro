# JNA + UniFFI bindings are reached reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.rutger.fastify.core.** { *; }
-dontwarn java.awt.**
