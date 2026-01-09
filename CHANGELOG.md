# Changelog

## [1.3.0] - 2025-11-03
### ✨ Cải tiến giao diện
- Chuyển đổi toàn bộ giao diện sang Jetpack Compose Material 3 chuẩn.
- Giao diện hiện đại, mượt mà hơn.
- Loại bỏ tab Nhật ký để tối ưu trải nghiệm người dùng.

### 🎯 Cập nhật & Sửa lỗi (Mới)
- **Tên ứng dụng**: Đã sửa lại tên ứng dụng thành **Kasumi Store** (bỏ dấu gạch ngang).
- **Quản lý Cache**:
  - Sửa lỗi nút "Xóa cache" vẫn hiện khi cache trống.
  - Tự động ẩn thông tin cache khi không có dữ liệu (0 B).
  - Cập nhật giao diện ngay lập tức sau khi xóa cache mà không cần khởi động lại.
- **Tải Script**:
  - Tự động loại bỏ đuôi `.txt` khi tải về (Ví dụ: `Teddy Hub` thay vì `Teddy Hub.txt`).
  - Sửa lỗi script đã tải về vẫn hiện nút "Tải" thay vì trạng thái đã cài đặt.
  - Hợp nhất danh sách script online và offline dựa trên tên file.
  - Cập nhật giao diện hộp thoại tải xuống theo chuẩn Material 3.

### 🐛 Các thay đổi khác
- Cập nhật biểu tượng ứng dụng mới.
- Log hệ thống được chuyển về Logcat.

## [1.2.0] - 2025-11-02
### Sửa lỗi
- Đã loại bỏ viền trắng quanh biểu tượng ứng dụng trong thông báo, toast và phần xem trước trong ứng dụng.
- Các định nghĩa adaptive icon nay trỏ trực tiếp tới `app_icon` để bảo đảm hiển thị đồng nhất.

### Khác
- Thông điệp nhật ký khi khởi động giờ đọc phiên bản ứng dụng trực tiếp từ gói cài đặt, nên vẫn chính xác sau mỗi lần tăng version.

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
