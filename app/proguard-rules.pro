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

# Two optional code paths reference classes the platform only has at other API levels, and R8
# treats that as an error. The Liquid Glass library's shader tier names android.graphics.Runtime*
# (API 33+; the Android 11 tier never loads those paths), and snakeyaml's bean introspector names
# java.beans.* (only reached for a JavaBean-style addon, which ours are not). Absent on this
# target, never executed — the honest answer is to say so, not to keep a phantom class.
-dontwarn android.graphics.RuntimeColorFilter
-dontwarn android.graphics.RuntimeXfermode
-dontwarn java.beans.**
