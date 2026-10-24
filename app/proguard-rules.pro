# kotlinx.serialization 모델 유지
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.chargis.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
