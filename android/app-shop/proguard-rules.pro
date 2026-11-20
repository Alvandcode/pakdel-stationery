# قواعد ProGuard/R8 — پاکدل استیشنری (فروشگاه)

-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.pakdel.**$$serializer { *; }
-keepclasseswithmembers class com.pakdel.** { kotlinx.serialization.KSerializer serializer(...); }

-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

-keep class * extends androidx.room.RoomDatabase { <init>(); }
