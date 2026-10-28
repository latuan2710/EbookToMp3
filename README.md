# Ebook → MP3

Ứng dụng Android chuyển eBook (EPUB / TXT) thành audiobook **mp3**, đọc offline bằng giọng Text-to-speech có sẵn trên điện thoại. APK được build tự động bằng GitHub Actions.

## Tính năng

- Chọn sách định dạng **EPUB** hoặc **TXT**, **chọn được nhiều file cùng lúc**.
- **Hàng đợi nhiều sách**: làm xong quyển 1 tự động làm quyển 2. Đang chạy vẫn thêm được sách mới, sách mới ở trạng thái chờ. Chạm vào sách đang chờ để bỏ khỏi hàng đợi.
- Nút **Bỏ qua sách đang đọc** (chuyển sang sách kế tiếp) và **Dừng tất cả**.
- **Tab "Danh sách"**: xem sách đang đọc, các sách đang chờ (chạm để bỏ), và toàn bộ file mp3 đã xong, gom theo từng sách kèm nơi lưu. File bị dừng giữa chừng được đánh dấu "một phần". Lịch sử đã xong được lưu trên máy (tối đa 500 file), có nút xoá lịch sử.
- Đọc offline bằng TTS của Android, chọn ngôn ngữ (mặc định `vi`).
- **Đọc từ chương n**: nhập số chương muốn bắt đầu, các chương trước đó được bỏ qua. Tiến trình và thời gian còn lại chỉ tính phần sẽ đọc, tên file vẫn dùng số chương thật (ví dụ `Chuong_010-014`).
- **Tốc độ đọc** từ 0.5x đến 2.0x (mặc định 1.0x).
- **Gộp mỗi N chương thành 1 file mp3**: `1` = mỗi chương một file, `0` = gộp cả sách. Tên file ghi rõ khoảng chương, ví dụ `TenSach_Chuong_001-005.mp3`.
- **Chọn thư mục lưu** (mặc định `Music/EbookMp3`).
- **Tiến trình đầy đủ**: phần trăm, sách/chương/đoạn đang đọc, số ký tự đã xử lý, thời gian đã chạy, thời gian còn lại ước tính, nhật ký, thông báo có thanh tiến độ.
- Đọc EPUB **đúng thứ tự thật của sách** (theo spine trong file .opf), bỏ qua trang mục lục (nav).
- **Wake lock**: giữ CPU thức trong lúc chuyển đổi nên tắt màn hình vẫn chạy ổn định, tự nhả khi hàng đợi xong hoặc khi dừng (tối đa 12 giờ). Nên cắm sạc khi đọc sách dài, và trên máy Xiaomi/Oppo/Samsung... hãy đặt pin của app ở chế độ "Không hạn chế".
- **Thẻ ID3** trong mỗi file mp3: tên chương (ví dụ "Chương 1-5"), tên sách, số thứ tự file (3/12), nghệ sĩ và thể loại "Audiobook". Trình phát nhạc và app audiobook hiện đúng tên và xếp đúng thứ tự.
- **Ảnh bìa** lấy từ EPUB (thẻ cover-image, meta cover hoặc file ảnh tên chứa "cover") được thu nhỏ rồi gắn vào thẻ ID3 của từng file mp3, trình phát sẽ hiện bìa sách.
- **Điều khiển từ thông báo**: nút Tạm dừng/Tiếp tục và Dừng ngay trên thông báo, không cần mở app.
- **Tiết kiệm RAM với sách lớn**: sách được chép vào cache theo luồng và mở bằng ZipFile, app phân tích trước (chỉ đếm ký tự từng chương) rồi mỗi lần chỉ nạp một chương, nên sách rất dài không còn nạp cả cuốn vào bộ nhớ. Bù lại, mỗi sách có thêm một bước phân tích ngắn trước khi đọc.
- **Tạm dừng / Tiếp tục** ngay trong lúc đang chạy (khi tạm dừng sẽ nhả wake lock để đỡ tốn pin).
- **Làm tiếp sau khi app bị tắt**: hàng đợi được lưu trên máy. Mở lại app sẽ thấy nút **Tiếp tục hàng đợi**, app bỏ qua các file mp3 đã xong của từng sách và làm lại file đang dở.
- **Chọn giọng đọc** theo ngôn ngữ (chỉ liệt kê giọng offline đã cài) và nút **Nghe thử giọng** với đúng tốc độ đã chọn.
- Luôn xuất ra **mp3** (mã hóa bằng LAME, 64 kbps).

## Cách dùng

1. Cài giọng đọc tiếng Việt trong Google Text-to-speech (Cài đặt → Quản lý chung → Chuyển văn bản thành giọng nói).
2. Mở app, cho phép thông báo, bấm **Chọn file EPUB / TXT** và chọn một hoặc nhiều sách.
3. Chọn ngôn ngữ, giọng đọc (bấm Nghe thử để kiểm tra), số chương gộp mỗi file, chương bắt đầu, tốc độ đọc và thư mục lưu.
4. Bấm **Bắt đầu chuyển thành MP3**. Muốn thêm sách khi đang chạy: chọn thêm file rồi bấm **Thêm vào hàng đợi**.
5. Có thể thoát app, việc chuyển đổi vẫn chạy nền.

Tuỳ chọn (ngôn ngữ, gộp chương, chương bắt đầu, tốc độ, thư mục) được áp dụng cho nhóm sách bạn thêm vào ở lần bấm đó, nên mỗi lần thêm có thể dùng tuỳ chọn khác nhau.

## Build APK bằng GitHub Actions

1. Đẩy toàn bộ mã nguồn (kể cả thư mục `.github`) lên GitHub.
2. Vào tab **Actions**, chờ workflow **Build APK** chạy xong.
3. Tải artifact `EbookToMp3-debug-apk`, giải nén và cài file `.apk`.

Yêu cầu: minSdk 29 (Android 10 trở lên), JDK 17, Gradle 8.5, Android Gradle Plugin 8.2.2.

## Cấu trúc dự án

```
.github/workflows/build.yml   Workflow build APK
app/build.gradle.kts          Cấu hình module app, thư viện LAME (JitPack)
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/ebooktomp3/
  MainActivity.kt             Giao diện, chọn file và tuỳ chọn, hàng đợi, tiến trình
  ConvertService.kt           Foreground service: hàng đợi, đọc EPUB/TXT, TTS, mã hóa mp3, ghi file
```

## Giới hạn hiện tại

- Chưa hỗ trợ PDF.
- Tạm dừng có hiệu lực giữa các đoạn đọc, có thể trễ vài chục giây vì đoạn đang đọc phải xong trước.
- Làm tiếp sau khi bị tắt app theo đơn vị file mp3: file đang làm dở sẽ bị xoá và làm lại từ đầu file đó, các file đã xong được giữ nguyên.
- Số "chương" dựa trên các file nội dung trong spine của EPUB, có thể nhiều hơn số chương in trong sách. Chưa đọc mục lục để gộp theo chương thật.
- File TXT được xem là một chương duy nhất và vẫn được nạp cả file vào bộ nhớ.
- Ảnh bìa chỉ có với EPUB có ảnh bìa; mỗi file mp3 mang theo một bản ảnh bìa nên dung lượng tăng thêm khoảng vài chục KB.
- Từ Android 11, không chọn được thư mục gốc như Download hay Documents, hãy chọn thư mục con.

## Lịch sử thay đổi

- **Cập nhật 11**: ảnh bìa trong thẻ ID3, nút Tạm dừng/Dừng trên thông báo, đọc sách theo từng chương để tiết kiệm RAM.
- **Cập nhật 10**: thẻ ID3 cho file mp3, tạm dừng/tiếp tục, làm tiếp hàng đợi sau khi app bị tắt, chọn giọng đọc và nghe thử.
- **Cập nhật 9**: thêm wake lock (quyền `WAKE_LOCK`) để việc chuyển đổi không bị chậm hoặc tạm dừng khi tắt màn hình.
- **Cập nhật 8**: thêm tab "Danh sách" hiển thị sách đang đọc, sách đang chờ và các file mp3 đã xong (lưu lịch sử trên máy).
- **Cập nhật 7**: thêm tuỳ chọn đọc từ chương n (bỏ qua các chương đứng trước, báo lỗi nếu n lớn hơn số chương của sách).
- **Cập nhật 6**: hàng đợi nhiều sách (tự chạy tuần tự, thêm sách khi đang chạy, bỏ sách chờ, bỏ qua sách đang đọc, dừng tất cả).
- **Cập nhật 5**: thêm README này, từ nay mỗi lần thay đổi dự án đều cập nhật README.
- **Cập nhật 4**: đọc EPUB theo đúng thứ tự spine, sửa lỗi đọc chương 1, 10, 100, 2... (trước đây sắp xếp theo tên file như chữ).
- **Cập nhật 3**: gộp mỗi N chương thành một file mp3, tên file ghi rõ khoảng chương.
- **Cập nhật 2**: thêm tuỳ chọn tốc độ đọc 0.5x–2.0x.
- **Cập nhật 1**: giao diện mới, tiến trình đầy đủ (phần trăm, thời gian còn lại, nhật ký, Huỷ), chọn thư mục lưu.
- **Sửa lỗi build**: thêm `tools:replace="android:label"` vào manifest, loại thư viện `com.android.support` cũ khỏi dependency của LAME.
- **Bản đầu**: chọn EPUB/TXT, đọc bằng TTS offline, xuất mp3, chạy nền bằng foreground service, workflow build APK.
