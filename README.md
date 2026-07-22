# SimpleCall

Ứng dụng gọi điện Android với giao diện Google Phone, viết bằng Kotlin.

## Tính năng

- 📞 Bàn phím quay số với sub-label ABC/DEF, format số VN tự động
- 📋 Nhật ký cuộc gọi với badge cuộc gọi nhỡ
- 👤 Danh bạ với tìm kiếm
- 🔄 **Chuyển hướng cuộc gọi đi** (ẩn trong menu ⋮) — gọi số nào cũng tự chuyển sang số đích
- ⏱️ Bộ đếm thời gian cuộc gọi
- 🎨 Avatar màu động, giao diện chuẩn Material Design

## Build

### Tự động (GitHub Actions)

Push lên branch `main` → Actions tự build và upload APK vào tab **Actions → Artifacts**.

Để tạo Release có APK đính kèm:
```bash
git tag v1.0.0
git push origin v1.0.0
```

### Build thủ công

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Yêu cầu

- Android 8.0+ (API 26)
- Đặt làm ứng dụng gọi điện mặc định khi mở lần đầu

## Cấu trúc

```
app/src/main/
├── java/com/h/simplecall/
│   ├── MainActivity.kt
│   ├── InCallActivity.kt
│   ├── call/          # CallManager, CallForwardManager, InCallService
│   ├── data/          # Models
│   └── ui/            # Fragments, Adapters
└── res/
    ├── layout/
    ├── drawable/      # Material icons & backgrounds
    └── values/        # Colors, Strings, Styles
```
