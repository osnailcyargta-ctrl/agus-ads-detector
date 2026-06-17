# 🎯 Agus Ads Detector

Detect iklan di HP1 secara otomatis, langsung notif ke HP2 via **Bluetooth** — tanpa internet, tanpa server!

## Kegunaan

Cocok buat AFK farming di game yang sering ada iklan. HP1 ditinggal farming, HP2 di tangan kamu. Kalau ada iklan muncul di HP1, HP2 langsung bunyi notif.

## Cara Pakai

### Setup Awal
1. Install APK di **kedua HP**
2. Pair kedua HP via Bluetooth di Settings HP (lakukan sekali saja)
3. Aktifkan **Accessibility Service** di Settings → Accessibility → Agus Ads Detector

### HP1 (yang farming/AFK)
1. Pilih mode **Monitor 📡**
2. Tap **Scan Paired Devices** → pilih HP2
3. Edit keyword iklan sesuai game (default sudah ada yang umum)
4. Tap **START**

### HP2 (yang kamu pegang)
1. Pilih mode **Receiver 🔔**
2. Tap **Scan Paired Devices** → pilih HP1
3. Tap **START**
4. Tunggu koneksi → siap!

## Cara Kerja

```
HP1 (AFK farming)                    HP2 (kamu)
┌─────────────────┐                ┌─────────────────┐
│ Accessibility   │                │ BluetoothService│
│ Service detects │──── BT RFCOMM ─│ receives msg    │
│ ad keywords     │                │                 │
│ in UI           │                │ Shows HIGH      │
└─────────────────┘                │ priority notif  │
                                   └─────────────────┘
```

**Detection method:**
- Scan UI text di semua app foreground pakai Accessibility API
- Cocokkan dengan keyword list (customizable)
- Juga deteksi package name ad SDK (Google AdMob, Meta, Unity, dll)

**Koneksi:**
- Bluetooth Classic RFCOMM (stabil, hemat baterai)
- Auto reconnect kalau putus
- Keepalive ping setiap 10 detik

## Permissions

| Permission | Untuk |
|---|---|
| BLUETOOTH_CONNECT | Connect ke device lain |
| ACCESSIBILITY_SERVICE | Baca UI untuk detect iklan |
| FOREGROUND_SERVICE | Jalan di background |
| POST_NOTIFICATIONS | Kirim notif di HP2 |

## Build

```bash
./gradlew assembleDebug
```

APK ada di `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26)
- Bluetooth Classic support
- Kedua HP sudah di-pair via Settings Bluetooth
