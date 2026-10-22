# kotlinx.serialization ships its own R8 rules; these keep the generated serializers of our models
# even if a future library version drops them.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.brainrot.core.** {
    static ** Companion;
    static **$Companion Companion;
    *** Companion;
}
-keepclasseswithmembers class com.brainrot.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.brainrot.core.**$$serializer { *; }

# Strip verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
