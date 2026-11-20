# قواعد ProGuard/R8 — پاکدل استیشنری

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pakdel.**$$serializer { *; }
-keepclassmembers class com.pakdel.** {
    *** Companion;
}
-keepclasseswithmembers class com.pakdel.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Supabase / Ktor
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
