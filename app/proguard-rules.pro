# Keep rules placeholder. Add rules if needed.

# Giữ thông tin generic và annotation cho Gson/OkHttp
-keepattributes Signature
-keepattributes *Annotation*

# Giữ lớp dữ liệu parse bằng Gson (tránh đổi tên field)
-keep class com.kasumi.tool.PreloadApp { *; }
-keep class com.kasumi.tool.ApkItem { *; }

# Giữ các lớp nội bộ của Gson (thường không bắt buộc, nhưng an toàn)
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.** { *; }

# Ẩn cảnh báo không ảnh hưởng build
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
