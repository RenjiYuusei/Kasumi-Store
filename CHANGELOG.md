# Changelog

## [1.8.0] - 2026-05-06
### ✨Tính năng mới
- **Tab "Auto Rejoin"**: Thêm tab thứ 4 ở thanh điều hướng để tự động rejoin Roblox khi tài khoản bị **kick / disconnect / crash / force-stop** — không phải mở app rồi bấm lại tay nữa.
  - **Hỗ trợ cả 2 bản Roblox**: tự động phát hiện `com.roblox.client` (global) và `com.roblox.client.vnggames` (VNG) — ưu tiên bản global nếu cài cả hai (đồng bộ với tab "Login Roblox").
  - **Cấu hình**:
    - **Place ID** (bắt buộc): chỉ chấp nhận chuỗi số (1–16 chữ số), validate trước khi start.
    - **Game Instance ID** (tùy chọn): dùng cho VIP server / Private server — gắn vào URL deeplink dạng `&gameInstanceId=…`.
    - **Chu kỳ kiểm tra**: slider 5–60 giây, mặc định 15s.
    - **Nút "Tự dò từ Roblox"**: bấm 1 lần khi Roblox đang ở trong game → app extract `placeId` + `gameInstanceId` từ 2 nguồn fallback và tự fill vào ô input. Không cần tìm placeId rồi paste tay nữa.
      - **Nguồn 1 — `dumpsys activity activities`**: chỉ khả dụng khi Roblox được launch qua deeplink `roblox://...` (URI gốc còn trong task stack). `gameInstanceId` được decode bằng `Uri.decode` để hiển thị raw cho user (service sẽ tự encode lại bằng `Uri.encode` khi build deeplink M1).
      - **Nguồn 2 — `logcat --pid=<roblox_pid>`** (fallback): hoạt động bất kể cách launch. Roblox client log placeId qua `FLog::PlaceLauncher`, `JoinGameJob`, network logs (JSON `"placeId":...`). Regex case-insensitive match `placeId=`, `placeId:`, `"placeId":`, ưu tiên match cuối cùng theo thứ tự thời gian → game user đang chơi.
      - Disable khi service đang chạy / chưa root / chưa cài Roblox / đang detect dở.
  - **Cơ chế kiểm tra trạng thái** (theo thứ tự early-return):
    1. `pidof <pkg>` — process còn sống không.
    2. `logcat -d -T <epoch> --pid=<pid> | grep -E '(You have been kicked|Lost connection with reason|Sending disconnect with reason|Disconnection Notification|Connection lost|Teleport failed|same account launched|server.?shut)'` — phát hiện disconnect/kick gần đây của ĐÚNG process Roblox. Lọc theo `--pid` + `-T <epoch>` để tránh đọc lại hint cũ + tránh false positive từ app khác. **Phải chạy trước dumpsys**: Roblox dùng kiến trúc single-activity (Unity engine), Activity gốc vẫn còn trong task stack với đúng intent `placeId=<target>` ngay cả sau khi bị kick → nếu check IN_GAME trước, ta sẽ early-return `IN_GAME` và không bao giờ phát hiện disconnect.
    3. `dumpsys activity activities | grep -F <pkg>` — đang trong game đúng `placeId` hay không (regex `placeId=([0-9]+)` để extract).
  - **Cơ chế rejoin** (3 phương thức, fallback theo thứ tự):
    - **M1 — Experiences deeplink**: `roblox://experiences/start?placeId=<id>[&gameInstanceId=<gid>]` (gid encode bằng `Uri.encode` RFC 3986; ưu tiên).
    - **M2 — Legacy deeplink**: `roblox://placeId=<id>` (cho ROM/client cũ).
    - **M3 — Cold launch**: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p <pkg>` (chỉ mở app).
  - **Watchdog FOREGROUND_NO_GAME**: nếu deeplink trả exit 0 nhưng Roblox không load (placeId chết, server đóng, hoặc bị đẩy về home), đếm `noGameStreak`. Sau 8 tick liên tiếp (~2 phút với interval 15s mặc định), force-stop + rejoin để thoát kẹt.
  - **UI**: Status card (root + Roblox), Config card (PlaceId, GameInstanceId, Slider chu kỳ), Control card (Start/Stop, hiển thị state hiện tại + tổng số lần đã rejoin), Log card (50 dòng cuộn, có timestamp + level INFO/OK/WARN/ERR), Warning card (cảnh báo về vận hành).
  - Yêu cầu: Quyền root (Magisk/KernelSU); một trong 2 bản Roblox đã cài.

### 🛠️Hạ tầng
- **`AutoRejoinManager`**: Module mới, độc lập với `RobloxLoginManager` (chỉ delegate `detectActivePackage` để giữ 1 nguồn sự thật khi cả 2 bản Roblox cùng cài). Pattern `executeAsRoot` được copy thay vì internalize để 2 module không phụ thuộc chéo. Toàn bộ shell argument đi qua `shellQuote()` (pidof / am force-stop / am start / dumpsys) để defense-in-depth dù package name an toàn.
- **`AutoRejoinService`** (foreground service, **mới**): vòng lặp polling chạy trong `Service` thay vì Composable để tiếp tục hoạt động khi user switch sang Roblox app. Trên Android 8+, mọi process không có FG service / activity ở foreground sẽ bị OS giới hạn background execution sau ~1–2 phút (aggressive hơn trên MIUI/ColorOS/OneUI) — không thể chạy auto-rejoin nếu loop bind vào `LaunchedEffect`.
  - Expose `MutableStateFlow<AutoRejoinUiState>` singleton trong companion để UI observe qua `collectAsState()`. Khi Composable bị dispose / recompose, observation tự thiết lập lại từ StateFlow → UI khôi phục đúng state hiện tại.
  - Persistent notification (channel `kasumi_auto_rejoin`, IMPORTANCE_LOW không kêu/rung) hiển thị state + rejoin count + nút "Dừng" qua `PendingIntent.getService(... ACTION_STOP)`. Tap notification → mở MainActivity. Tất cả nhãn đều qua `getString(R.string.*)` để i18n-ready.
  - Manifest: `foregroundServiceType="specialUse"` + property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (Android 14+). Permissions mới: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` (runtime request Android 13+ qua `rememberLauncherForActivityResult`).
  - `START_NOT_STICKY` — không tự restart khi process bị OS kill (an toàn vì restart không có extras sẽ vô nghĩa). `onDestroy` reset `_state.running` để UI không kẹt khi service bị system kill / ADB terminate / OOM mà không đi qua `stopServiceCompletely()`.
  - Khi `pkg`/`placeId` rỗng trong `handleStart`, vẫn gọi `startForegroundCompat()` 1 nhịp trước `stopSelf()` để thoả mãn Android 8+ contract (nếu không sẽ crash `Context.startForegroundService() did not then call Service.startForeground()`).
  - `LOG_TIME_FMT` dùng `ThreadLocal<SimpleDateFormat>` — mỗi thread có instance riêng, an toàn cả khi sau này có refactor cho phép `appendLog` chạy từ IO thread.
- **`AutoRejoinScreen`**: chỉ còn nhiệm vụ detect root + Roblox, nhận input cấu hình, start/stop service qua `AutoRejoinService.start()` / `.stop()` Intent helpers, observe `AutoRejoinService.state` qua `collectAsState()`. Log buffer giới hạn 50 entries (cap ở service, UI render trực tiếp không cần `take()`).
- **Bump phiên bản**: `1.7.3` → `1.8.0` (versionCode 17 → 18).

## [1.7.3] - 2026-05-05
### 🎨 UI / UX
- **Login Roblox — đồng bộ chiều cao 2 nút "Sao chép" / "Dùng để login"**: Trên màn hình hẹp, text "Dùng để login" bị wrap thành 2 dòng khiến nút phải cao hơn nút trái. Fix: ép `IntrinsicSize.Min` cho Row + `maxLines=1, softWrap=false, overflow=Ellipsis` cho Text + giảm contentPadding/icon size để text vừa khít trong 1 dòng.
- **Bump phiên bản**: `1.7.2` → `1.7.3` (versionCode 16 → 17).

## [1.7.2] - 2026-05-05
### 🐛 Sửa lỗi
- **Login Roblox bằng cookie — Lỗi `unknown error (code 0 SQLITE_OK): Queries can be performed using SQLiteDatabase query or rawQuery methods only`**: Tiếp nối fix `1.7.1` — sau khi đổi sang WAL mode, connection setup lại fail vì gọi `db.execSQL("PRAGMA journal_mode = WAL")`.
  - **Nguyên nhân**: `PRAGMA journal_mode = X` (kể cả dạng setter) luôn trả về 1 row chứa mode mới. Android `SQLiteDatabase.execSQL()` từ chối thực thi câu lệnh có output column → throw `SQLiteException` ngay khi prepare statement. Bản 1.7.1 cũ dùng `PRAGMA journal_mode = DELETE` cũng gặp tình huống tương tự nhưng có thể đã bị che bởi lỗi 7434 ở bước commit nên không lộ ra.
  - **Cách sửa**: Đổi `db.execSQL("PRAGMA journal_mode = WAL")` thành `db.rawQuery("PRAGMA journal_mode = WAL", null).use { ... }` và verify cột 0 = `"wal"`. Nếu mode trả về khác `wal` → throw để abort thay vì rơi xuống rollback journal (có thể lại trigger 7434). `PRAGMA synchronous = NORMAL` (setter) KHÔNG trả row nên giữ nguyên `execSQL`.
- **Bump phiên bản**: `1.7.1` → `1.7.2` (versionCode 15 → 16).

## [1.7.1] - 2026-05-05
### 🐛 Sửa lỗi
- **Login Roblox bằng cookie — Lỗi `disk I/O error (code 7434)`**: Khắc phục lỗi `SQLITE_IOERR_BEGIN_ATOMIC` ở bước "Ghi cookie vào DB" trên một số thiết bị (đặc biệt là bản Roblox VNG `com.roblox.client.vnggames`).
  - **Nguyên nhân**: SQLite chỉ ioctl `F2FS_IOC_START_ATOMIC_WRITE` khi commit ở rollback-journal mode (DELETE/TRUNCATE/PERSIST), KHÔNG phải WAL. Việc ép cache DB sang **legacy mode** (byte 18-19 = 1) trong bản trước thực ra là nguyên nhân kích hoạt atomic write — ngược với mục tiêu ban đầu. Trên cacheDir của app khác, ioctl này có thể trả EBUSY/EPERM (do attribute inode hoặc SELinux context) khiến commit fail.
  - **Cách sửa**: Đổi sang ép **WAL mode** (byte 18-19 = 2) cho cache DB và `PRAGMA journal_mode = WAL` cho connection, sau đó `PRAGMA wal_checkpoint(TRUNCATE)` để flush WAL vào main DB trước khi đẩy ngược về Roblox. WAL pathway không bao giờ ioctl `F2FS_IOC_START_ATOMIC_WRITE` → bypass hoàn toàn lỗi 7434.
- **Bump phiên bản**: `1.7.0` → `1.7.1` (versionCode 14 → 15).

## [1.7.0] - 2026-05-01
### ✨Tính năng mới
- **Tab "Login Roblox"**: Thêm tab thứ ba ở thanh điều hướng để đăng nhập tài khoản Roblox bằng cookie `.ROBLOSECURITY` mà không cần tài khoản/mật khẩu.
  - **Hỗ trợ cả 2 bản Roblox**: tự động phát hiện `com.roblox.client` (global) và `com.roblox.client.vnggames` (VNG) — ưu tiên bản global nếu cài cả hai.
  - **Trích xuất Cookie**: Đọc cookie từ database WebView của Roblox tại `/data/data/<package>/app_webview/Default/Cookies` (cần root). Có nút sao chép và nút "Dùng để login" để đẩy cookie sang phần đăng nhập.
  - **Đăng nhập bằng Cookie**: Dán cookie `.ROBLOSECURITY` của tài khoản cần đăng nhập, ứng dụng sẽ tự động:
    1. Force-stop ứng dụng Roblox.
    2. Xóa file `Cookies-journal/wal/shm` để tránh xung đột writer SQLite.
    3. `DELETE FROM cookies` xóa toàn bộ dữ liệu cũ và `INSERT OR REPLACE` cookie mới với host `.roblox.com`.
    4. Sửa quyền (`chown`/`chmod`/`restorecon`) cho khớp với uid của ứng dụng Roblox.
  - **Hiển thị nhật ký từng bước**: Mỗi bước root đều có log chi tiết để dễ chẩn đoán khi gặp lỗi.
  - **Bảo mật**: Có cảnh báo trong UI; cookie được ẩn mặc định (toggle hiện/ẩn) và validate định dạng (`_|WARNING:` prefix) trước khi chèn vào SQL.
  - Yêu cầu: Quyền root (Magisk/KernelSU); một trong 2 bản Roblox đã cài (`com.roblox.client` hoặc `com.roblox.client.vnggames`). Không cần binary `sqlite3` trên thiết bị (đọc/ghi DB qua API Android `SQLiteDatabase`).

### 🛠️Hạ tầng
- **`RobloxLoginManager`**: Module mới sử dụng `ProcessBuilder("su", "-c", ...)` chỉ để **copy** file DB sang `cacheDir` của ứng dụng; việc đọc/ghi DB SQLite được thực hiện bằng API `android.database.sqlite.SQLiteDatabase` thuần Kotlin (Cursor + ContentValues), **không cần binary `sqlite3`** trên thiết bị. Sau khi sửa xong, copy ngược lại + restore quyền (chown/chmod 660/restorecon).
- **Đọc stdout/stderr song song** trong `executeAsRoot` (CompletableFuture) để tránh deadlock khi pipe buffer đầy.
- **Chown bằng UID/GID dạng số** (`stat -c '%u'`/`'%g'`) thay vì tên symbolic — đáng tin cậy hơn trên các ROM Android không có `/etc/passwd`.
- **Bump phiên bản**: `1.6.0` → `1.7.0` (versionCode 13 → 14).

## [1.6.0] - 2026-04-24
### ✨ Cải tiến giao diện
- **Hộp thoại Giới thiệu**: Thêm nút "Giới thiệu" trên thanh trên cùng, hiển thị tên ứng dụng, phiên bản hiện tại và nút mở server Discord cộng đồng.
- **Xác nhận xóa cache**: Thêm hộp thoại xác nhận trước khi xóa cache kèm thống kê số tệp và dung lượng sẽ bị xóa, tránh thao tác nhầm.
- **Chuẩn hóa chuỗi văn bản**: Chuyển các chuỗi UI còn hard-code (ví dụ nút "Đóng") sang resource `strings.xml` để dễ dịch và đồng nhất.

### 🐛 Sửa lỗi
- **Cài đặt APK khi thiếu quyền**: Khắc phục lỗi `installNormally` vẫn tiếp tục mở installer dù quyền "Cài đặt ứng dụng không rõ nguồn gốc" chưa được cấp, dẫn đến thông báo lỗi khó hiểu. Giờ đây ứng dụng sẽ dừng lại và hướng dẫn người dùng cấp quyền.
- **Kiểm tra đuôi `.txt`**: Dùng `endsWith(".txt", ignoreCase = true)` thay vì `lowercase().endsWith(...)` khi lưu script, loại bỏ cấp phát chuỗi thừa và tránh các lỗi locale (Turkish `i`).

### ⚡ Tối ưu mã nguồn
- **Chia sẻ `OkHttpClient` duy nhất**: `MainActivity` không còn tạo `OkHttpClient` riêng mà tái sử dụng instance trong `KasumiApplication`, tiết kiệm bộ nhớ và tái dùng connection pool cho cả tải ảnh và gọi API.
- **Gỡ mã chết trong MainActivity**: Loại bỏ các hàm `log`/`logBg`, biến `isRefreshing` không được đọc, tham số `initial` không sử dụng trong `refreshPreloadedApps`, và dọn ~20 import không cần thiết.
- **Tối ưu `filterAndSortApps`**: Loại bỏ `.lowercase()` thừa trên từ khóa tìm kiếm (hàm đã dùng `contains(ignoreCase = true)`), giảm cấp phát chuỗi mỗi lần gõ phím.

### 🧹 Dọn dead code
- **Xóa layout XML View cũ**: Bỏ hoàn toàn `item_apk.xml`, `item_installed_app.xml`, `item_script.xml` — các layout View-based không còn được dùng sau khi chuyển sang Jetpack Compose.
- **Xóa drawable không dùng**: Loại bỏ `ic_sort`, `ic_log`, `ic_download`, `ic_apps`, `ic_installed`, `ic_copy`, `app_icon_bg`, `game_tag_bg`, `card_gradient_bg` và color `tab_icon_tint` — không còn layout nào tham chiếu.
- **Dọn màu thừa**: Loại bỏ các màu `card_bg`, `card_bg_start`, `card_bg_end`, `elevated_surface`, `success_green`, `warning_orange`, `info_blue`, `delete_red` khỏi `colors.xml` vì không còn được dùng.
- **Dọn ProGuard rules**: Gỡ bỏ các quy tắc dành cho Glide đã loại bỏ từ phiên bản 1.5.4.
- **Dọn dependency**: Loại bỏ `androidx.recyclerview:recyclerview` khỏi `app/build.gradle` — không còn RecyclerView nào trong dự án.

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
