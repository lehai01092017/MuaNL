# Mua NL — ứng dụng Android chọn mua nguyên liệu

Chọn nguyên liệu cần mua, xuất bill (ảnh hoặc văn bản) và chia sẻ qua Zalo, Messenger, Gmail…
Dữ liệu lưu trên máy (SQLite), có lịch sử bill và thống kê chi tiêu.

## Tính năng
| Tab | Chức năng |
|---|---|
| **Mua hàng** | Danh sách nguyên liệu theo danh mục (thu gọn/mở rộng), tìm kiếm, bấm `+ / −` hoặc chạm số lượng để nhập số lẻ (0,5 kg…). Thanh dưới hiện tổng tiền → **Xuất bill** (thêm ghi chú) → lưu và chia sẻ. |
| **Danh mục** | Thêm / sửa tên / xoá danh mục (**chỉ xoá được danh mục trống**), nút **Xoá tất cả danh mục trống**. Thêm / sửa / xoá nguyên liệu (tên, đơn vị, đơn giá, chuyển danh mục). |
| **Lịch sử** | Danh sách bill đã lưu; mở để xem chi tiết, **gửi ảnh qua Zalo**, chia sẻ ảnh/văn bản qua ứng dụng khác, hoặc xoá bill. |
| **Thống kê** | Hôm nay / 7 ngày / Tháng này / Năm nay / Tất cả: tổng chi, số bill, trung bình mỗi bill, biểu đồ chi theo ngày/tháng, tỉ lệ theo danh mục, chi theo người tạo, top nguyên liệu. |
| **⚙ Cài đặt** (góc trên phải) | **Tên người tạo bill** (in trên ảnh/văn bản bill, sửa được ngay khi xuất bill). **Xuất & gửi dữ liệu** sang máy khác qua Zalo/Drive/Bluetooth… và **Nhập dữ liệu từ file** với 2 lựa chọn: *Gộp* (thêm cái chưa có, giữ dữ liệu máy này) hoặc *Thay thế* toàn bộ. |

### Chuyển dữ liệu sang máy khác
1. Máy A: ⚙ Cài đặt → **Xuất & gửi dữ liệu** → chọn Zalo (gửi cho chính mình hoặc người kia).
2. Máy B: tải file `.db` từ Zalo về → mở app → ⚙ Cài đặt → **Nhập dữ liệu từ file** → chọn file → **Gộp** hoặc **Thay thế**.
   Khi gộp: danh mục/nguyên liệu so theo tên, bill trùng (cùng thời điểm, tổng tiền, người tạo) sẽ bỏ qua — nên gộp nhiều lần không bị nhân đôi.

Bill lưu bản chụp tên và giá tại thời điểm mua, nên sửa hoặc xoá nguyên liệu sau này không làm sai lịch sử, thống kê.
Lần đầu mở app có sẵn vài danh mục mẫu (Rau củ, Thịt cá, Gia vị) — sửa hoặc xoá tuỳ ý.

## Cách lấy file APK

### Cách 1 — GitHub (không cần cài gì)
1. Tạo repo mới trên GitHub, tải toàn bộ thư mục này lên (giữ cả thư mục `.github`).
2. Vào tab **Actions** → workflow **Build APK** tự chạy (~5 phút).
3. Mở lượt chạy → mục **Artifacts** → tải `MuaNguyenLieu-apk`, giải nén, chép file `.apk` vào điện thoại và cài
   (cho phép "Cài ứng dụng không rõ nguồn gốc").

### Cách 2 — Android Studio
Mở thư mục bằng Android Studio (bản Ladybug trở lên) → chờ Gradle đồng bộ → **Run** hoặc
**Build > Build APK(s)**.

## Kỹ thuật
- Kotlin 2.0, Jetpack Compose (Material 3), minSdk 26 (Android 8.0+), targetSdk 35.
- SQLite thuần (`SQLiteOpenHelper`), không dùng thư viện sinh mã.
- Chia sẻ ảnh qua `FileProvider`; nút Zalo mở thẳng gói `com.zing.zalo`, nếu chưa cài thì hiện danh sách ứng dụng.
- Bản release ký bằng khoá debug cho tiện cài cá nhân. Muốn đưa lên CH Play thì tạo keystore riêng.

## Cấu trúc mã
```
app/src/main/java/vn/nguyenlieu/app/
  MainActivity.kt   – theme, thanh điều hướng 4 tab, trạng thái chung (giỏ hàng)
  Db.kt             – CSDL: danh mục, nguyên liệu, bill, truy vấn thống kê
  ShopScreen.kt     – màn chọn mua, nhập số lượng, xác nhận bill
  ManageScreen.kt   – quản lý danh mục & nguyên liệu
  HistoryScreen.kt  – lịch sử bill + hộp thoại xem/chia sẻ bill
  StatsScreen.kt    – thống kê, biểu đồ
  Backup.kt         – xuất/nhập file dữ liệu (gộp hoặc thay thế), lưu tên người tạo
  SettingsDialog.kt – hộp thoại cài đặt
  BillShare.kt      – vẽ ảnh bill, tạo văn bản, gửi Zalo/ứng dụng khác
  Format.kt         – định dạng tiền (120.000đ), số lượng, ngày giờ
```
