# The tun bridge is reached through JNI, so its native methods must keep their names.
-keep class com.parsv2r.app.TunBridge { *; }

# The core module is plain Java with no reflection, but keeping it readable makes a stack trace
# from a user's device worth reading.
-keep class com.parsv2r.core.** { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
