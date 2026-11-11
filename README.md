# Nexus Asisten

Asisten Game untuk Android (Kotlin, View programatik, tanpa Compose). Dibangun ulang dari NexusLauncher.

## Build
Android Studio (Giraffe/Hedgehog+), buka folder ini, Sync, Run. minSdk 29, target 33, AGP 7.4.2, Kotlin 1.8.22.

## Fitur
- Beranda: game, tombol Mulai, perangkat live (gauge suhu/baterai/profil), waktu bermain, izin.
- Overlay Asisten Game (geser dari tepi / tap pill): tab Performa, Alat, Saya + dock aplikasi.
- Performa per game: Hemat / Seimbang / Gamer Pro, refresh rate, sampling sentuhan, sensitivitas, blokir panggilan & notifikasi.
- Alat: kecerahan, cek jaringan, RAM, cuplikan & rekam layar, bantuan bidik, cegah salah sentuh, DND, Mode Fokus, layar menyala, volume, kunci orientasi.
- Album game, pintasan layar Awal, auto-turun mode saat panas.

## Data nyata vs terbatas
Nyata: suhu/baterai (BatteryManager), ping (TCP), RAM, foreground game (UsageEvents), waktu bermain, rekam/cuplik (MediaProjection).
FPS akurat: `adb shell pm grant com.nexus.asisten android.permission.DUMP` (tanpa itu = estimasi bertanda ~).
Terbatas ROM: refresh rate (butuh WRITE_SETTINGS, sebagian ROM mengabaikan), sampling sentuhan/sensitivitas (disimpan di profil saja).

## Catatan
- Kode belum dikompilasi di lingkungan pembuatnya; jika ada error build, kirim lognya.
- Jika target SDK dinaikkan ke 34: tambahkan foregroundServiceType untuk OverlayService (specialUse) dan naikkan AGP ke 8.1+.
