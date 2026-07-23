# Kasumi-Store

Ứng dụng quản lý và tải xuống APK cho giả lập và thiết bị Android, hỗ trợ cài đặt nâng cao, đăng nhập Roblox bằng cookie và auto-rejoin.

**Phiên bản:** 2.0.0
**Ngôn ngữ:** Kotlin
**Giao diện:** Jetpack Compose (Material 3)

## Tính năng chính

*   **Kho ứng dụng (Apps):**
    *   Tải xuống và cài đặt APK, APKS (Split APKs), XAPK.
    *   Hỗ trợ cài đặt APK với quyền Root (nếu có) hoặc Shizuku/Session Installer (No-Root).
    *   Tự động phát hiện và sao chép file OBB vào đúng thư mục (`/Android/obb/...`).
    *   Quản lý cache, xóa file rác.
    *   Tìm kiếm, sắp xếp theo tên, kích thước, ngày tải.

*   **Login Roblox:**
    *   Trích xuất và đăng nhập Roblox bằng cookie `.ROBLOSECURITY` (yêu cầu root).

*   **Auto Rejoin:**
    *   Tự động rejoin Roblox theo Place ID, chạy nền dưới dạng foreground service.

*   **Đồng bộ (Sync):**
    *   Sao lưu/khôi phục config Delta giữa các máy qua database.

*   **Bypass Key Delta:**
    *   Lấy key Delta từ link platoboost/platorelay ngay trong app, thông qua API bypass (Kasumi-Bypass) mà bạn **tự host** (ví dụ trên VPS).
    *   Nhập địa chỉ API (được lưu lại), dán link, bấm "Lấy key" để nhận key kèm số phút còn lại.
    *   Xem hướng dẫn host API trên VPS trong repo [Kasumi-Bypass](https://github.com/RenjiYuusei/Kasumi-Bypass) (`docs/VPS_DEPLOY.md`).

*   **Giao diện hiện đại:**
    *   Sử dụng Jetpack Compose với chuẩn Material Design 3.
    *   Điều hướng bằng menu trượt (navigation drawer) từ cạnh trái.
    *   Hỗ trợ Dark Theme mặc định.
    *   Trải nghiệm mượt mà, trực quan.

## Cài đặt và Yêu cầu

1.  Tải file APK từ trang phát hành.
2.  Cài đặt lên thiết bị Android (Android 8.0 trở lên).
3.  Cấp quyền truy cập bộ nhớ (Manage External Storage) để ứng dụng có thể quản lý file APK.

## Đóng góp

Mọi đóng góp, báo lỗi vui lòng gửi về GitHub Issues.
