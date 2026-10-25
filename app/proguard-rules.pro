# Release builds only (debug is unminified). Everything the app reaches reflectively is
# data classes and View subclasses used through XML-free code, so no extra keeps are needed
# beyond the Android defaults AGP already applies.
-keep class com.scenix.tv.core.** { *; }
-keep class com.scenix.tv.plugins.** { *; }

# DefaultMediaSourceFactory builds the media source for a URL by name (Reflector), so the HLS and
# DASH factories must survive shrinking: an .m3u8 live channel crashes without them.
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
