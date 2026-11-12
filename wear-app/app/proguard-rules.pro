# Tile and complication services are referenced from the manifest only.
-keep class io.github.fatahilahuda.muarakas.wear.** extends android.app.Service { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
