# Keep the Media3 session service reachable from the system.
-keep class top.izuna.foliamajor.playback.FoliaPlaybackService { *; }

# kotlinx.serialization generates serializers as nested classes; the plugin's own rules cover most
# of it, but the generated $$serializer fields are looked up reflectively.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
