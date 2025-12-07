# MostlyGoodMetrics SDK ProGuard rules

# Keep the public API
-keep class com.mostlygoodmetrics.sdk.MostlyGoodMetrics { *; }
-keep class com.mostlygoodmetrics.sdk.MGMConfiguration { *; }
-keep class com.mostlygoodmetrics.sdk.MGMConfiguration$Builder { *; }
-keep class com.mostlygoodmetrics.sdk.MGMEvent { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.mostlygoodmetrics.sdk.**$$serializer { *; }
-keepclassmembers class com.mostlygoodmetrics.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class com.mostlygoodmetrics.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}
