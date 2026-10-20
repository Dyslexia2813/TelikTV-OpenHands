# Keep media3 internals referenced via reflection / native binding.
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Keep our entry point.
-keep class live.telik.tv.MainActivity { *; }
-keep class live.telik.tv.StreamResolver { *; }
