# Generic signatures are needed for the TypeToken<List<ApkItem>> lookups in
# Models.kt; without them Gson cannot recover the element type at runtime.
-keepattributes Signature

# PreloadApp is parsed reflectively from source/apps.json, so its field names
# must survive obfuscation. ApkItem goes through a hand-written TypeAdapter and
# does not strictly need this, but the cost is a few bytes and getting it wrong
# would silently wipe the user's saved catalogue in a release build.
-keep class com.kasumi.tool.PreloadApp { *; }
-keep class com.kasumi.tool.ApkItem { *; }

# Gson ships its own keep rules in META-INF/proguard/gson.pro, so the blanket
# "-keep class com.google.gson.**" that used to live here was both redundant and
# harmful: it pinned the whole library and stopped R8 shrinking it.

-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
