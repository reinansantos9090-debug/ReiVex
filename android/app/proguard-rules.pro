# JNI uses exact binary class/method names. Keep the existing native
# download and advertisement-filtering interfaces intact when R8 shrinks the
# compact preview and release APKs.
-keep class com.reivex.browser.downloads.DownloadNativeCopy { *; }
-keep class io.github.edsuns.adblockclient.AdBlockClient { *; }
-keep class io.github.edsuns.adblockclient.MatchResult { *; }

# FFmpegKit loads and calls native libraries using JNI/reflection. This Java
# bridge is small compared with the native FFmpeg binary; retain the bridge
# rather than risk broken remuxing after obfuscation.
-keep class com.arthenica.ffmpegkit.** { *; }

# WebView bridge methods must remain callable from injected JavaScript,
# even if their only references are dynamic JavaScript strings.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
