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

## Update 1.8

- Overlay & aplikasi didesain ulang bergaya Premium Gamer (kaca gelap, neon violet/cyan/magenta, aurora bergerak, bingkai mengalir, ikon baru, ikon aplikasi baru).
- Animasi memakai mesin sendiri (Choreographer), jadi tetap halus walau "Skala animasi" di Opsi Pengembang dimatikan atau Hemat Baterai aktif.
- Aplikasi dari tab Aplikasi dibuka tanpa izin tambahan: bila HP belum mendukung jendela mengambang, muncul gelembung Nexus untuk kembali ke game.
- Blur latar panel (Android 12+), bisa dimatikan di Pengaturan.

## Data nyata vs terbatas
Nyata: suhu/baterai (BatteryManager), ping (TCP), RAM, foreground game (UsageEvents), waktu bermain, rekam/cuplik (MediaProjection).
FPS akurat: `adb shell pm grant com.nexus.asisten android.permission.DUMP` (tanpa itu = estimasi bertanda ~).
Terbatas ROM: refresh rate (butuh WRITE_SETTINGS, sebagian ROM mengabaikan), sampling sentuhan/sensitivitas (disimpan di profil saja).

## Update 1.2

- **Pembaruan lewat aplikasi**: cek rilis terbaru di GitHub Releases, unduh & pasang dari dalam aplikasi (Pengaturan > Pengaturan > Pembaruan, juga muncul otomatis di beranda). Rilis dibuat oleh GitHub Actions; lihat `build.yml` terbaru. APK ditandatangani dengan keystore tetap (`app/nexus-debug.keystore.b64`) supaya bisa menimpa versi sebelumnya.
- Desain UI & overlay baru (kaca gelap, gradien, animasi masuk/keluar), efek tekan + getar halus, notifikasi kapsul setiap aksi.
- Pegangan overlay tidak pernah di dasar layar, posisinya diingat.
- Mode kompatibel: tidak mematikan asisten game lain, tidak menimpa DND/refresh rate milik aplikasi lain.
- Pusat izin: jalur cadangan untuk tiap ROM, panduan "setelan terbatas" Android 13+, perintah ADB yang bisa disalin.
- Ringan: FPS & ping hanya berjalan saat panel dibuka; tombol Boost; kunci Wi-Fi latensi rendah; DNS pribadi (opsional via ADB).

## Update 1.3

- Overlay dirombak: pegangan di bawah-tengah, geser ke atas membuka menu aksi berbentuk busur 180° dan kartu kaca hitam transparan yang bisa digeser kiri-kanan (Performa, Alat, Aplikasi, Saya), semua dengan animasi.
- Tab Aplikasi: aplikasi overlay dibuka sebagai jendela mengambang (butuh mode freeform di HP; ada panduan di Pengaturan).
- Seluruh UI aplikasi didesain ulang bergaya gamer premium + animasi masuk, transisi layar, efek tekan.
- Fitur "akses notifikasi" dihapus: izin itu yang memicu blokir Play Protect dan layar izinnya terkunci (setelan terbatas Android 13+).
- Build kini memakai varian release (non-debuggable) dengan tanda tangan tetap.

## Catatan
- Kode belum dikompilasi di lingkungan pembuatnya; jika ada error build, kirim lognya.
- Jika target SDK dinaikkan ke 34: tambahkan foregroundServiceType untuk OverlayService (specialUse) dan naikkan AGP ke 8.1+.
