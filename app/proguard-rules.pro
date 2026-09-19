# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 需求：安全加固（R8 已默认开启；此处保留反射入口，避免插件/序列化被误裁）
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class com.nekonyan.assistant.data.db.** { *; }
-keep class com.nekonyan.assistant.plugin.** { *; }

# 需求：StringFog / 反调试等加固在 release 由 CI 注入，此处留位
