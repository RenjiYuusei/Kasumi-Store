# Changelog

## [1.2.0] - 2025-11-2
### Sửa Lỗi
- Đã **loại bỏ viền trắng** bao quanh biểu tượng ứng dụng trong các thông báo (notifications), tin nhắn nhanh (toasts) và bản xem trước trong ứng dụng (in-app previews).
- Định nghĩa biểu tượng thích ứng (**Adaptive icon**) giờ đây **trỏ trực tiếp đến** `app_icon` để có hình ảnh nhất quán.

### Khác
- Thông báo nhật ký (log message) khi khởi động giờ đây sử dụng `BuildConfig.VERSION_NAME` để đảm bảo nó **luôn chính xác** sau các lần tăng phiên bản (version bumps).

## [1.1.1] - 2025-10-10
### 🎯 Chức năng mới
- **Hỗ trợ XAPK hoàn chỉnh**: Thêm khả năng cài đặt file XAPK (cùng với APK và APKS đã hỗ trợ)
  - Tự động phát hiện và giải nén file XAPK
  - Cài đặt split APK từ file XAPK qua root hoặc cách thường
  - **Tự động copy OBB**: Giải nén và copy file OBB vào `/Android/obb/<package>/`
  - Parse manifest.json để lấy package name chính xác
  - Tương thích với các file từ APKPure, APKMirror và các nguồn khác
- **Quyền Storage**: Tự động yêu cầu quyền quản lý storage để copy OBB

### 🐛 Sửa lỗi
- **Clear cache**: Sửa lỗi nút xóa cache không xóa hết - giờ đã xóa cả thư mục splits đã giải nén
- **Log gọn gàng**: Loại bỏ các log không cần thiết (khởi động, tải nguồn, ENV trùng lặp)
- **XAPK parsing**: Cải thiện logic giải nén - đọc đúng tất cả APK entries trong ZIP
- **Debug logs**: Thêm log chi tiết từng file được giải nén để dễ debug

## [1.1.0] - 2025-09-30 (Update 2)

### ✨ Cải tiến giao diện
- **Material Design 3**: Áp dụng Material You với màu sắc hiện đại
- **Theme tối nâng cao**: Giao diện tối mượt mà hơn với gradient và shadow
- **Icon cho tabs**: Thêm icon trực quan cho các tab Ứng dụng, Đã cài đặt, Nhật ký
- **Card design mới**: Bo góc 16dp, stroke outline, elevation tối ưu
- **Thanh tìm kiếm cải tiến**: Outlined style với icon search và clear button

### 🎯 Chức năng mới
- **Sắp xếp đa dạng**:
  - Tên A-Z / Z-A
  - Kích thước file (lớn → nhỏ)
  - Ngày tải xuống (mới → cũ)
- **Badge "Đã tải"**: Hiển thị trạng thái cache với badge màu
- **Hiển thị kích thước file**: Xem dung lượng APK đã cache (MB/GB)
- **Thống kê cache**: Thanh stats hiển thị tổng số app và dung lượng cache
- **Quản lý cache**: Nút xóa toàn bộ cache với thống kê chi tiết
- **Progress indicator**: Màu sắc đồng nhất theo theme

### 🔧 Cải tiến kỹ thuật
- Tối ưu hiển thị danh sách với RecyclerView
- Format file size chính xác (B/KB/MB/GB)
- Sort performance được tối ưu
- Code structure rõ ràng hơn với enum SortMode

### 🎨 UI/UX
- Button style Material 3 (Tonal, Outlined, Text)
- Icon buttons với ripple effect
- Spacing và padding đồng nhất
- Color contrast tốt hơn cho dark theme
- Typography cải tiến

### 🐛 Sửa lỗi (Update 2)
- ✅ Sửa lỗi tab Nhật ký không hiển thị đúng
- ✅ Thiết kế lại icon app với gradient Purple Material You
- ✅ Thêm adaptive icon cho Android 8.0+
- ✅ Icon hiện đại với phone + cloud + download arrow

## [1.0.1] - Previous version
- Cài đặt APK từ URL
- Hỗ trợ root installation
- Quản lý ứng dụng đã cài đặt
