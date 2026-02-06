# Keep rules placeholder. Add rules if needed.

# Giữ thông tin generic và annotation cho Gson/Retrofit/OkHttp
-keepattributes Signature
-keepattributes *Annotation*

# Giữ lớp dữ liệu parse bằng Gson (tránh đổi tên field)
-keep class com.kasumi.tool.PreloadApp { *; }
-keep class com.kasumi.tool.ApkItem { *; }
-keep class com.kasumi.tool.ScriptItem { *; }

# Giữ các lớp nội bộ của Gson (thường không bắt buộc, nhưng an toàn)
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.** { *; }

# Giữ Glide classes
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
  *** rewind();
}

# Ẩn cảnh báo không ảnh hưởng build
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
