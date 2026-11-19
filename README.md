# Dynamic Island untuk Android

Aplikasi Android native (Kotlin, tanpa library tambahan, APK ±100 KB) yang menghadirkan
**Dynamic Island ala iPhone** di ponsel Android, dan hampir semua bagiannya bisa diatur.

## Fitur

| Fitur | Keterangan |
|---|---|
| **Notifikasi pop-up** | Island membesar dengan ikon, judul, dan isi notifikasi. Ketuk untuk membuka aplikasinya. |
| **Balas langsung** | Balas chat (WhatsApp, Telegram, dll.) dari island tanpa membuka aplikasi. |
| **Salin OTP otomatis** | Kode verifikasi terdeteksi otomatis, lalu muncul tombol "Salin kode". |
| **Kontrol musik** | Mode ringkas menampilkan album art dan visualizer. Saat diperluas: judul, artis, seek bar, prev/play/next. |
| **Warna dari album** | Warna visualizer dan progress mengikuti warna album art. |
| **Gestur musik** | Geser kiri/kanan untuk pindah lagu. |
| **Animasi mengisi daya** | Persentase dan ikon baterai muncul saat charger dicolok. Bisa juga tampil terus di mode ringkas. |
| **Baterai lemah** | Peringatan saat baterai turun ke 20%. |
| **Mode senyap/getar/dering** | Indikator muncul saat mode suara berubah. |
| **Timer & stopwatch** | Berjalan sebagai *Live Activity*. Bisa jeda, +1 menit, dan berhenti, dengan getaran saat selesai. |
| **Cahaya keliling** | Ketuk island saat kosong: cahaya berputar mengelilingi sisi luarnya. Warna, mode pelangi, ketebalan, kecepatan, dan jumlah putaran bisa diatur. |
| **Panel mini apps** | Geser island kosong ke bawah: senter, timer 5 menit, stopwatch, screenshot, kunci layar, edit, dan pengaturan. |
| **Animasi 120 Hz** | Bentuk island digambar langsung oleh GPU (tanpa hitung ulang tata letak), membesar dan mengecil tepat dari tengah, dengan kurva pegas dan refresh rate tertinggi layar. |
| **Tampil di layar kunci** | Notifikasi, musik, timer, dan pengisian daya juga muncul saat ponsel terkunci (bisa dimatikan). Memakai overlay aksesibilitas, jadi tidak perlu izin "tampil di atas aplikasi lain". |
| **Daftar blokir aplikasi** | Pilih aplikasi mana yang boleh muncul di island, plus pintasan untuk mematikan pop-up bawaan sistem (agar notifikasi tidak dobel). |
| **Anti notifikasi ganda** | Notifikasi berisi sama yang dikirim dua kali dalam 5 detik hanya ditampilkan sekali. |

### Gestur
- **Ketuk**: perluas/tutup. Pada island kosong, memunculkan cahaya keliling.
- **Tahan**: buka aplikasi terkait (notifikasi/musik). Pada island kosong, membuka pengaturan.
- **Geser notifikasi ke atas/kiri/kanan**: island mengikuti jari, lalu menutup dan kembali mulus ke bentuk awal.
- **Geser ke bawah**: perluas atau buka panel mini apps.
- **Geser kiri/kanan** saat musik: lagu berikutnya/sebelumnya.

### Kustomisasi ukuran, posisi & sudut
- **Edit langsung di layar** (dari pengaturan atau ikon ✏ di panel mini apps): seret island ke mana saja,
  lalu atur lebar, tinggi, sudut, sudut saat diperluas, dan lebar saat diperluas dengan tombol −/+
  (tahan tombol untuk mengubah cepat). Hasilnya langsung terlihat di atas aplikasi apa pun.
- **Preset bentuk**: iPhone, Kecil, Besar, Titik kamera, Kapsul tipis, Kotak tumpul, Kotak.
- Setiap slider punya tombol −/+ untuk penyetelan presisi.

### Kustomisasi lain
Lebar, tinggi, posisi X/Y, kebulatan sudut, lebar mode ringkas dan diperluas, sudut saat diperluas,
opasitas, warna latar, warna aksen, durasi animasi, efek pegas (bounce), lama notifikasi tampil,
getaran haptik, sembunyikan saat landscape atau saat tidak ada aktivitas, dan tombol
**"Sejajarkan ke kamera"** yang membaca posisi lubang kamera ponsel secara otomatis.

## Instalasi

1. Unduh APK dari tab **Actions** → build terakhir → artifact `DynamicIsland-apk`
   (atau build sendiri, lihat di bawah). Pasang `app-release.apk`.
2. Buka aplikasi, lalu aktifkan:
   - **Layanan aksesibilitas** (untuk menggambar island)
   - **Akses notifikasi** (untuk notifikasi, musik, balas, dan OTP)
3. **Android 13+**: jika tombol aksesibilitas abu-abu, buka *Info aplikasi* → menu ⋮ →
   **"Izinkan setelan terbatas"**. Ini wajib untuk APK yang dipasang di luar Play Store.
4. Tekan **"Sejajarkan ke kamera"**, lalu atur sisanya sesuai selera.

> Di beberapa ponsel (Xiaomi/MIUI, Oppo/ColorOS, Vivo), matikan juga optimasi baterai
> untuk aplikasi ini supaya layanannya tidak dimatikan sistem.

## Build sendiri

Butuh JDK 17+ dan Android SDK (platform 35).

```bash
./gradlew assembleRelease
```

Hasilnya ada di `app/build/outputs/apk/release/app-release.apk`. APK release ditandatangani
dengan kunci debug agar bisa langsung dipasang. Ganti `signingConfig` jika ingin rilis ke Play Store.

## Struktur kode

```
app/src/main/java/com/dynamicisland/app/
├── IslandService.kt     Layanan aksesibilitas: pemilik jendela overlay
├── IslandWindow.kt      UI island, animasi, gestur, semua "live activity"
├── NotifService.kt      Pendengar notifikasi (filter, balas, OTP)
├── MediaTracker.kt      Sesi media aktif + kontrol pemutaran
├── TimerController.kt   Timer & stopwatch
├── MainActivity.kt      Layar pengaturan
├── Prefs.kt             Semua pengaturan + nilai default
├── Otp.kt               Deteksi kode OTP
└── Widgets.kt           Visualizer & ikon baterai
```

## Ide pengembangan berikutnya
- Dua aktivitas sekaligus (island terbelah, misalnya timer dan musik), seperti iPhone
- Live Activity untuk navigasi Maps, panggilan telepon, dan rekam layar
- Pelacakan pengiriman paket dan skor olahraga dari notifikasi
- Tema bawaan dan ekspor/impor pengaturan

## Referensi
Fitur diadaptasi dari aplikasi sejenis seperti dynamicSpot (Jawomo) dan TheNotch.
Semua kode di repo ini ditulis dari nol.
