# Release builds only (debug is unminified). Everything the app reaches reflectively is
# data classes and View subclasses used through XML-free code, so no extra keeps are needed
# beyond the Android defaults AGP already applies.
-keep class com.scenix.tv.core.** { *; }
-keep class com.scenix.tv.plugins.** { *; }

# DefaultMediaSourceFactory builds the media source for a URL by name (Reflector), so the HLS and
# DASH factories must survive shrinking: an .m3u8 live channel crashes without them.
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }

# The standalone P2P engine is a JNI library: native code binds to Java callbacks by name at class
# load (the SWIG directors), so shrinking it does not rename something private, it makes the engine
# die at `libtorrent_jni` init with a NoSuchMethodError — every torrent source then answers
# "could not prepare" and a standalone TV can play nothing at all.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# The Liquid Glass library's API-33 shader tier names Runtime* classes that the Android 11 tier
# never loads. R8 still sees those symbols, so allow only these optional platform references.
-dontwarn android.graphics.RuntimeColorFilter
-dontwarn android.graphics.RuntimeXfermode
# GeckoView brings SnakeYAML 2.2. On Android, SnakeYAML's PlatformFeatureDetector selects its
# field-based path before Java Bean introspection, but its desktop-only implementation still refers
# to these Java SE classes. Android does not ship java.beans; suppress only the five exact optional
# references reported by R8 rather than keeping or silencing the whole library.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
