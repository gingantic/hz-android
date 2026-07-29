# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Ignore missing JDK/system classes referenced by EdDSA / SSHJ (SFTP library)
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.**
# Bouncy Castle rules (required for SFTP/SSHJ security algorithms and SMB/jcifs-ng crypto MD4)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn com.jcraft.jsch.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# jcifs-ng (SMB) rules to prevent obfuscation/shrinking of class files and reflection paths
-keep class jcifs.** { *; }
-dontwarn jcifs.**

# SSHJ (SFTP) rules to prevent dynamic algorithm loading failure
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.**

# Apache Commons Net (FTP) rules
-keep class org.apache.commons.net.** { *; }
-dontwarn org.apache.commons.net.**

# FlagKit — country flag ImageVectors resolved at runtime via FlagKit.getFlag()
-keep class dev.carlsen.flagkit.** { *; }

# WebView & JavaScript Interfaces (Required for Release builds with minification)
-keepattributes AnnotationDefault, *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve JNI native method signatures for Rust/C dynamic linkage
-keepclasseswithmembernames class * {
    native <methods>;
}

# FFmpeg software decode fallback (libffcodec.so): the JNI bridge resolves
# these non-native members by name (GetMethodID/GetFieldID), so R8 must not
# rename or strip them.
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer { *; }
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
-keepclassmembers class com.rhnxdev.hzplayer.data.datasource.player.ffmpeg.FfmpegAudioDecoder {
    java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# Preserve AdBlocker engine, rule models, and JS bridge
-keep class com.rhnxdev.hzplayer.browser.adblock.** { *; }
-keep class com.rhnxdev.hzplayer.browser.media.MediaSnifferBridge { *; }
-keep class com.rhnxdev.hzplayer.browser.media.MediaSnifferEngine { *; }
-keep class com.rhnxdev.hzplayer.browser.media.MediaStreamDecoder { *; }

# Keep custom WebViewClient / WebChromeClient callbacks intact
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }