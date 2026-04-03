# Changelog

## [1.5.5] - 2026-04-03
### 🐛 Sửa lỗi
- **Làm mới Script**: Khắc phục lỗi vuốt để làm mới (pull-to-refresh) ở tab Scripts không cập nhật danh sách script. Đã sửa để hệ thống tự động tải lại cả script từ server và script cục bộ thay vì tải lại danh sách ứng dụng.

## [1.5.4] - 2026-03-12
### ⚡ Cập nhật thư viện
- **Nâng cấp Android SDK**: Tăng `compileSdk` và `targetSdk` lên phiên bản 35 để đảm bảo khả năng tương thích với các API mới nhất của Android và Jetpack Compose.
- **Jetpack Compose**: Cập nhật Compose BOM lên `2025.01.00`, mang đến các thành phần Material 3 mới và cải tiến hiệu năng render.
- **Thư viện ảnh Coil**: Nâng cấp toàn diện từ Coil 2.x sang Coil 3.0.4. Thay đổi API `AsyncImage` để hỗ trợ đa nền tảng tốt hơn và thêm dependency `coil-network-okhttp`.
- **Loại bỏ Glide**: Xóa bỏ hoàn toàn dependency Glide không còn sử dụng để giảm kích thước file APK và thời gian build.
- **Cập nhật khác**: Nâng cấp Gson lên `2.11.0`, các plugin Kotlin/Compose lên `2.1.0` và cập nhật các thư viện AndroidX cốt lõi (`core-ktx`, `activity-ktx`, `lifecycle-runtime-ktx`, `kotlinx-coroutines-core`) lên phiên bản ổn định mới nhất. Giữ nguyên OkHttp ở bản `4.12.0` để đảm bảo tương thích hoàn hảo.

## [1.5.3] - 2026-03-6
### ✨ Cải tiến giao diện
- **Thanh tìm kiếm**: Thêm nút xóa nhanh (clear) ở cuối ô tìm kiếm để reset từ khóa chỉ với 1 chạm.
- **Trạng thái rỗng**: Bổ sung `EmptyState` cho danh sách ứng dụng và script khi không có kết quả, giúp người dùng hiểu rõ trạng thái hiện tại.
- **Ngữ cảnh danh sách script**: Hiển thị số lượng script phù hợp ngay phía trên danh sách.
- **Card hiện đại hơn**: Tinh chỉnh bo góc lớn hơn (`16dp`) và tăng nhẹ đổ bóng để giao diện đồng nhất, dễ nhìn hơn.

### ⚡ Hiệu năng
- **Tối ưu hóa xóa Cache**: Thay thế vòng lặp kiểm tra từng ứng dụng bằng một thao tác liệt kê thư mục duy nhất (`listFiles`) trong `clearCache`, giúp tăng tốc độ xóa cache lên khoảng 650 lần và loại bỏ hoàn toàn hiện tượng thắt nút cổ chai I/O khi danh sách ứng dụng lớn.
- **Tối ưu hóa băm URL**: Sử dụng hàm `toHexString()` thay cho vòng lặp StringBuilder dịch bit để tạo mã băm trong `stableIdFromUrl`, giúp giảm ~57% thời gian xử lý và giảm cấp phát bộ nhớ.
- **Tối ưu hóa tìm kiếm Script**: Tối ưu hóa bộ lọc trong danh sách Script bằng thuật toán `contains(q, ignoreCase = true)`, loại bỏ hoàn toàn việc cấp phát chuỗi `.lowercase()` thừa trong quá trình duyệt qua danh sách, giúp tăng tốc độ lọc danh sách Script lên khoảng 3.7 lần.
- **Tối ưu Gson**: Tái sử dụng đối tượng `Gson` dùng chung thay vì cấp phát mới liên tục, giúp cải thiện tốc độ phân tích cú pháp khoảng 8 lần và giảm áp lực lên bộ dọn rác (Garbage Collector).
- **Tối ưu hóa tìm kiếm & sắp xếp**: Tối ưu hóa bộ lọc và sắp xếp ứng dụng bằng các thuật toán so sánh không phân biệt chữ hoa chữ thường. Giảm thiểu rác bộ nhớ phát sinh, giúp thuật toán nhanh hơn khoảng 3.4 lần so với dùng `.lowercase()`.
- **Tối ưu FileUtils**: Sử dụng phương pháp xử lý chuỗi trực tiếp thay vì `java.net.URI` để trích xuất đuôi file trong `getCacheFile`, tăng tốc độ xử lý hơn 13 lần, giảm đáng kể overhead khi cập nhật UI.

## [1.5.2] - 2026-02-17
### 🐛 Sửa lỗi
- **Cập nhật trạng thái cache tức thì**: Sửa lỗi sau khi tải app xong, số lượng ứng dụng đã cache/nhãn "Đã tải" đôi khi chưa cập nhật ngay (phải thoát và mở lại).
- **Nút Xóa cache**: Sửa lỗi sau khi bấm xóa cache, bảng trạng thái vẫn còn hiển thị còn 1 ứng dụng đã cache dù tệp đã xóa.
- **Đồng bộ UI**: Tối ưu lại luồng làm mới `fileStats` và trigger recomposition để toàn bộ danh sách + thống kê cache đồng bộ ngay trong phiên hiện tại.

## [1.5.1] - 2026-02-15
### 🚀 Tính năng mới
- **Cài đặt từ Cache**: Hỗ trợ cài đặt ứng dụng trực tiếp từ file đã tải (cache) mà không cần tải lại, tiết kiệm thời gian và băng thông. Nút tải xuống sẽ tự động chuyển thành nút "Cài đặt" (icon Play) khi phát hiện file.

### 🐛 Sửa lỗi
- **Giao diện Cache**: Khắc phục lỗi trạng thái "Đã tải" không cập nhật sau khi xóa cache hoặc tải xong. Giờ đây danh sách sẽ tự động làm mới ngay lập tức.
## [1.5.0] - 2026-02-15
### ⚡ Hiệu năng
- **Hiệu năng UI**: Tối ưu hóa cập nhật danh sách ứng dụng, sử dụng `mutableStateMapOf` và xử lý thread an toàn giúp cập nhật trạng thái file nhanh hơn ~3700 lần (0.015ms vs 55ms), loại bỏ hoàn toàn giật lag khi tải file.
- **Tối ưu hóa**: Chuyển logic xóa script xuống thread `IO` (background), loại bỏ hiện tượng khựng UI khi thao tác trên file hệ thống.
- **Đồng bộ hóa**: Đảm bảo trạng thái UI cập nhật mượt mà sau khi xóa file.
- **Tải Script**: Tối ưu hóa quá trình lưu file script, chuyển thao tác I/O sang thread nền giúp loại bỏ hoàn toàn việc chặn UI (giảm ~90ms block).
- **Danh sách ứng dụng**: Tối ưu hóa bộ lọc và tìm kiếm, chuyển logic lọc/sắp xếp sang background thread (`Dispatchers.Default`) giúp loại bỏ giật lag khi gõ từ khóa trên thiết bị yếu hoặc danh sách lớn (thời gian chặn main thread giảm về ~0ms).
- **Root Installer**: Tối ưu hóa kiểm tra quyền root (`isDeviceRooted`) bằng cơ chế lazy caching, giảm thời gian kiểm tra từ ~1000ms xuống ~0ms cho các lần gọi sau, đồng thời sửa lỗi treo khi process `su` chờ input.

## [1.4.0] - 2026-02-06
### 🚀 Tính năng mới
- **Web Editor**: Ra mắt công cụ quản lý dữ liệu (`apps.json`, `scripts.json`) trực quan trên nền web (`tools/web-editor`), hỗ trợ review và đóng góp dễ dàng hơn.
- **Delta Updater**: Tích hợp quy trình tự động cập nhật script Delta mới nhất qua GitHub Actions.

### ⚡ Hiệu năng & Kỹ thuật
- **Delta Updater**: Cập nhật cơ chế lấy link Delta VNG (Fix 261) theo vị trí cố định và hỗ trợ ghi đè phiên bản hiển thị.
- **Root Installer 2.0**:
  - Tái cấu trúc toàn bộ logic cài đặt Root sử dụng `ShellSession` để duy trì kết nối su, loại bỏ độ trễ khi tạo process mới.
  - Tích hợp `TarUtil`: Stream và giải nén dữ liệu trực tiếp giúp cài đặt Split APKs nhanh và ổn định hơn.
  - Tối ưu hóa I/O: Sử dụng `copyTo` native của Kotlin cho các thao tác stream dữ liệu.
- **Script Engine**:
  - Thuật toán gộp danh sách script mới (HashMap O(N+M)) giúp xử lý danh sách lớn tức thì.
  - Cập nhật ProGuard rules bảo vệ các lớp dữ liệu quan trọng (`ScriptItem`).

### 🐛 Sửa lỗi
- **Giao diện**: Đồng bộ màu thanh trạng thái (Status Bar) với theme tối hoàn toàn.
- **Danh sách**: Khắc phục triệt để lỗi hiển thị ứng dụng trùng lặp khi làm mới.

## [1.3.0] - 2026-1-09
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
  - Bắt buộc lưu script với đuôi `.txt` (Ví dụ: `Teddy Hub.txt`).
  - Sửa lỗi script đã tải về vẫn hiện nút "Tải" thay vì trạng thái đã cài đặt.
  - Hợp nhất danh sách script online và offline dựa trên tên file.
  - Cập nhật giao diện hộp thoại tải xuống theo chuẩn Material 3.
  - Tối ưu hiệu năng: Tăng tốc độ khớp script lên hơn 50 lần (sử dụng thuật toán O(N+M) thay vì O(N*M)).
  - Tối ưu hiệu năng: Tính toán thông tin file trong background để tránh giật lag UI.

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
- **Thanh tìm kiếm cải tiến**: Outlined style với icon search và clear button

### 🎯 Chức năng mới
- **Sắp xếp đa dạng**:
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
