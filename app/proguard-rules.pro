# kotlinx.serialization keeps generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.modelforge.bench.**$$serializer { *; }
-keepclassmembers class com.modelforge.bench.** {
    *** Companion;
}
-keepclasseswithmembers class com.modelforge.bench.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# JNI entry points are called by name from C++.
-keepclasseswithmembernames class com.modelforge.bench.engine.NativeBridge {
    native <methods>;
}
-keep class com.modelforge.bench.engine.NativeBridge { *; }

-dontwarn org.jetbrains.annotations.**
