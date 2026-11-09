# kotlinx.serialization keeps its generated serializers via @Serializable; R8 needs
# the companion-held serializer to survive. Added now so the first release build is
# not the moment this is discovered.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.amrakshay.shutterlink.** {
    *** Companion;
}
-keepclasseswithmembers class dev.amrakshay.shutterlink.** {
    kotlinx.serialization.KSerializer serializer(...);
}
