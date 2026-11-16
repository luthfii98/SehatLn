import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SehatLn - Pengingat Hidup Sehat (versi Java console)
 *
 * Fitur: pelacak minum, jadwal makan / olahraga / tidur,
 *        pengingat awal, alarm (dengan tunda 5 menit).
 *
 * Perulangan yang dipakai hanya while dan do-while.
 *
 * Cara jalan:
 *   javac SehatLn.java
 *   java SehatLn
 */
public class SehatLn {

    // ===================== MODEL DATA =====================
    static class Jadwal {
        String nama;
        String jenis;       // MAKAN, OLAHRAGA, BANGUN, TIDUR
        LocalTime jam;
        boolean aktif;
        boolean selesai;
        boolean tetap;      // bangun & tidur tidak bisa dihapus

        Jadwal(String nama, String jenis, LocalTime jam, boolean tetap) {
            this.nama = nama;
            this.jenis = jenis;
            this.jam = jam;
            this.aktif = true;
            this.selesai = false;
            this.tetap = tetap;
        }
    }

    static class Alarm {
        String label;
        String jenis;       // MAKAN, OLAHRAGA, BANGUN, TIDUR, AIR
        String jam;
        Jadwal sumber;      // null untuk alarm air / tes

        Alarm(String label, String jenis, String jam, Jadwal sumber) {
            this.label = label;
            this.jenis = jenis;
            this.jam = jam;
            this.sumber = sumber;
        }
    }

    static class Tunda {
        Alarm alarm;
        LocalDateTime kapan;

        Tunda(Alarm alarm, LocalDateTime kapan) {
            this.alarm = alarm;
            this.kapan = kapan;
        }
    }

    // ===================== STATE APLIKASI =====================
    static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    static List<Jadwal> daftar = new ArrayList<>();
    static Jadwal jadwalBangun;
    static Jadwal jadwalTidur;

    static int targetMl = 2000;
    static int gelasMl = 250;
    static int intervalMenit = 90;
    static boolean airAktif = true;
    static int minumMl = 0;
    static LocalDateTime terakhirMinum = LocalDateTime.now();

    static boolean preAktif = true;
    static int preMenit = 15;

    static LocalDate hariIni = LocalDate.now();

    // ===================== MAIN =====================
    public static void main(String[] args) throws IOException {
        isiDefault();

        System.out.println("=====================================");
        System.out.println("   SEHATLN  -  Pengingat Hidup Sehat");
        System.out.println("=====================================");
        System.out.println(sapaan() + ". Jaga ritme harianmu.");

        int pilih;
        // do-while: menu utama selalu tampil minimal sekali
        do {
            gantiHariJikaPerlu();
            System.out.println();
            System.out.println("------------- MENU UTAMA -------------");
            System.out.println("1. Lihat hari ini (minum + jadwal)");
            System.out.println("2. Minum segelas (+" + gelasMl + " ml)");
            System.out.println("3. Kurangi segelas");
            System.out.println("4. Tandai jadwal selesai");
            System.out.println("5. Atur jadwal makan");
            System.out.println("6. Atur jadwal olahraga");
            System.out.println("7. Atur jam tidur & bangun");
            System.out.println("8. Atur target minum");
            System.out.println("9. Atur pengingat awal");
            System.out.println("10. Mulai pemantau alarm");
            System.out.println("11. Coba bunyi alarm");
            System.out.println("0. Keluar");
            pilih = bacaInt("Pilih menu", 0, 11);

            switch (pilih) {
                case 1:  tampilHariIni(); break;
                case 2:  minum(1); System.out.println("Glek glek. Total: " + minumMl + " ml"); break;
                case 3:  minumMl = Math.max(0, minumMl - gelasMl); System.out.println("Total: " + minumMl + " ml"); break;
                case 4:  tandaiSelesai(); break;
                case 5:  aturJadwal("MAKAN", "jam makan"); break;
                case 6:  aturJadwal("OLAHRAGA", "jam olahraga"); break;
                case 7:  aturTidur(); break;
                case 8:  aturAir(); break;
                case 9:  aturPre(); break;
                case 10: mulaiPemantau(); break;
                case 11: bunyiAlarm(new Alarm("Coba alarm", "AIR", LocalTime.now().format(FMT), null)); break;
                default: break;
            }
        } while (pilih != 0);

        System.out.println("Sampai jumpa. Tetap sehat!");
    }

    // ===================== DATA AWAL =====================
    static void isiDefault() {
        daftar.add(new Jadwal("Sarapan", "MAKAN", LocalTime.of(7, 0), false));
        daftar.add(new Jadwal("Makan siang", "MAKAN", LocalTime.of(12, 30), false));
        daftar.add(new Jadwal("Makan malam", "MAKAN", LocalTime.of(18, 30), false));
        daftar.add(new Jadwal("Olahraga pagi", "OLAHRAGA", LocalTime.of(6, 0), false));
        jadwalBangun = new Jadwal("Bangun tidur", "BANGUN", LocalTime.of(5, 30), true);
        jadwalTidur = new Jadwal("Waktunya tidur", "TIDUR", LocalTime.of(22, 0), true);
        daftar.add(jadwalBangun);
        daftar.add(jadwalTidur);
    }

    static String sapaan() {
        int jam = LocalTime.now().getHour();
        if (jam < 11) return "Selamat pagi";
        if (jam < 15) return "Selamat siang";
        if (jam < 19) return "Selamat sore";
        return "Selamat malam";
    }

    static void gantiHariJikaPerlu() {
        if (!hariIni.equals(LocalDate.now())) {
            hariIni = LocalDate.now();
            minumMl = 0;
            terakhirMinum = LocalDateTime.now();
            int i = 0;
            while (i < daftar.size()) {
                daftar.get(i).selesai = false;
                i++;
            }
        }
    }

    // ===================== TAMPILAN HARI INI =====================
    static void tampilHariIni() {
        double persen = Math.min(1.0, (double) minumMl / targetMl);
        int total = (int) Math.ceil((double) targetMl / gelasMl);
        int sisa = Math.max(0, (int) Math.ceil((double) (targetMl - minumMl) / gelasMl));

        System.out.println();
        System.out.println("=== Minum hari ini ===");
        System.out.println(barProgres(persen) + " " + minumMl + " / " + targetMl + " ml");
        System.out.println(persen >= 1.0
                ? "Target tercapai. Kerja bagus!"
                : "Sisa " + sisa + " dari " + total + " gelas");

        System.out.println();
        System.out.println("=== Jadwal hari ini ===");
        List<Jadwal> urut = urutkan();
        if (urut.isEmpty()) {
            System.out.println("Belum ada jadwal.");
            return;
        }
        LocalTime sekarang = LocalTime.now();
        int i = 0;
        while (i < urut.size()) {
            Jadwal j = urut.get(i);
            String status;
            if (!j.aktif) status = "Alarm mati";
            else if (j.jam.isBefore(sekarang) && !j.selesai) status = "Terlewat";
            else status = "Alarm aktif";

            System.out.printf("%-3s [%s] %s  %-16s %s%n",
                    (i + 1) + ".", j.selesai ? "x" : " ", j.jam.format(FMT), j.nama, status);
            i++;
        }
    }

    static String barProgres(double persen) {
        int penuh = (int) Math.round(persen * 20);
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        while (i < 20) {
            sb.append(i < penuh ? '#' : '-');
            i++;
        }
        return sb.append("]").toString();
    }

    // urutkan berdasarkan jam (insertion sort pakai while)
    static List<Jadwal> urutkan() {
        List<Jadwal> hasil = new ArrayList<>(daftar);
        int i = 1;
        while (i < hasil.size()) {
            Jadwal kunci = hasil.get(i);
            int j = i - 1;
            while (j >= 0 && hasil.get(j).jam.isAfter(kunci.jam)) {
                hasil.set(j + 1, hasil.get(j));
                j--;
            }
            hasil.set(j + 1, kunci);
            i++;
        }
        return hasil;
    }

    // ===================== AKSI =====================
    static void minum(int n) {
        minumMl += gelasMl * n;
        terakhirMinum = LocalDateTime.now();
    }

    static void tandaiSelesai() {
        List<Jadwal> urut = urutkan();
        tampilHariIni();
        int no = bacaInt("Nomor jadwal yang mau ditandai (0 = batal)", 0, urut.size());
        if (no == 0) return;
        Jadwal j = urut.get(no - 1);
        j.selesai = !j.selesai;
        System.out.println(j.nama + (j.selesai ? " ditandai selesai." : " dibatalkan."));
    }

    // Kelola daftar makan / olahraga
    static void aturJadwal(String jenis, String judul) {
        int pilih;
        do {
            System.out.println();
            System.out.println("--- Atur " + judul + " ---");
            List<Jadwal> sub = ambilJenis(jenis);
            if (sub.isEmpty()) System.out.println("(belum ada)");
            int i = 0;
            while (i < sub.size()) {
                Jadwal j = sub.get(i);
                System.out.println((i + 1) + ". " + j.nama + " - " + j.jam.format(FMT)
                        + " - alarm " + (j.aktif ? "ON" : "OFF"));
                i++;
            }
            System.out.println("1. Tambah   2. Ubah nama/jam   3. Alarm ON/OFF   4. Hapus   0. Kembali");
            pilih = bacaInt("Pilih", 0, 4);

            if (pilih == 1) {
                String nama = bacaTeks("Nama");
                LocalTime jam = bacaJam("Jam (HH:mm)");
                daftar.add(new Jadwal(nama, jenis, jam, false));
                System.out.println("Ditambahkan.");
            } else if (pilih >= 2 && pilih <= 4) {
                if (sub.isEmpty()) {
                    System.out.println("Belum ada data.");
                } else {
                    int no = bacaInt("Nomor", 1, sub.size());
                    Jadwal j = sub.get(no - 1);
                    if (pilih == 2) {
                        j.nama = bacaTeks("Nama baru");
                        j.jam = bacaJam("Jam baru (HH:mm)");
                    } else if (pilih == 3) {
                        j.aktif = !j.aktif;
                    } else {
                        daftar.remove(j);
                        System.out.println("Dihapus.");
                    }
                }
            }
        } while (pilih != 0);
    }

    static List<Jadwal> ambilJenis(String jenis) {
        List<Jadwal> hasil = new ArrayList<>();
        int i = 0;
        while (i < daftar.size()) {
            if (daftar.get(i).jenis.equals(jenis)) hasil.add(daftar.get(i));
            i++;
        }
        return hasil;
    }

    static void aturTidur() {
        System.out.println("Waktu tidur  : " + jadwalTidur.jam.format(FMT) + " (" + (jadwalTidur.aktif ? "ON" : "OFF") + ")");
        System.out.println("Waktu bangun : " + jadwalBangun.jam.format(FMT) + " (" + (jadwalBangun.aktif ? "ON" : "OFF") + ")");
        jadwalTidur.jam = bacaJam("Waktu tidur baru (HH:mm)");
        jadwalBangun.jam = bacaJam("Waktu bangun baru (HH:mm)");
        jadwalTidur.aktif = bacaYaTidak("Alarm tidur aktif?");
        jadwalBangun.aktif = bacaYaTidak("Alarm bangun aktif?");
    }

    static void aturAir() {
        targetMl = bacaInt("Target per hari (ml)", 250, 10000);
        gelasMl = bacaInt("Ukuran gelas (ml)", 50, 1000);
        intervalMenit = bacaInt("Ingatkan tiap (menit)", 1, 600);
        airAktif = bacaYaTidak("Alarm minum aktif?");
    }

    static void aturPre() {
        preMenit = bacaInt("Ingatkan sebelum (menit)", 1, 120);
        preAktif = bacaYaTidak("Pengingat awal aktif?");
    }

    // ===================== PEMANTAU ALARM =====================
    // Loop utama: while (jalan) mengecek waktu tiap detik
    static void mulaiPemantau() throws IOException {
        System.out.println();
        System.out.println(">> Pemantau alarm berjalan. Tekan ENTER untuk berhenti.");

        Set<String> sudahBunyi = new HashSet<>();
        List<Tunda> antrianTunda = new ArrayList<>();
        boolean jalan = true;

        while (jalan) {
            gantiHariJikaPerlu();
            LocalDateTime kini = LocalDateTime.now();
            LocalTime t = kini.toLocalTime().withSecond(0).withNano(0);
            String tanggal = kini.toLocalDate().toString();

            // 1) alarm tepat waktu
            int i = 0;
            while (i < daftar.size()) {
                Jadwal j = daftar.get(i);
                if (j.aktif && j.jam.equals(t)) {
                    String kunci = tanggal + "|" + j.nama + "|" + j.jam;
                    if (sudahBunyi.add(kunci)) {
                        Tunda hasil = bunyiAlarm(new Alarm(j.nama, j.jenis, j.jam.format(FMT), j));
                        if (hasil != null) antrianTunda.add(hasil);
                    }
                }
                i++;
            }

            // 2) pengingat awal
            if (preAktif) {
                i = 0;
                while (i < daftar.size()) {
                    Jadwal j = daftar.get(i);
                    if (j.aktif && j.jam.minusMinutes(preMenit).equals(t)) {
                        String kunci = "pre|" + tanggal + "|" + j.nama + "|" + j.jam;
                        if (sudahBunyi.add(kunci)) {
                            System.out.println();
                            System.out.println("\u0007>> SEBENTAR LAGI: " + j.nama + " " + preMenit
                                    + " menit lagi (" + j.jam.format(FMT) + ")");
                        }
                    }
                    i++;
                }
            }

            // 3) alarm yang ditunda
            i = 0;
            while (i < antrianTunda.size()) {
                Tunda td = antrianTunda.get(i);
                if (!kini.isBefore(td.kapan)) {
                    antrianTunda.remove(i);
                    Tunda lagi = bunyiAlarm(td.alarm);
                    if (lagi != null) antrianTunda.add(lagi);
                } else {
                    i++;
                }
            }

            // 4) alarm minum berulang
            boolean sedangTerjaga = terjaga(t);
            boolean sudahLewat = kini.isAfter(terakhirMinum.plusMinutes(intervalMenit));
            if (airAktif && minumMl < targetMl && sedangTerjaga && sudahLewat) {
                terakhirMinum = kini;
                Tunda hasil = bunyiAlarm(new Alarm("Waktunya minum", "AIR", t.format(FMT), null));
                if (hasil != null) antrianTunda.add(hasil);
            }

            // 5) berhenti jika user menekan ENTER
            if (System.in.available() > 0) {
                while (System.in.available() > 0) System.in.read();
                jalan = false;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                jalan = false;
            }
        }
        System.out.println(">> Pemantau dihentikan.");
    }

    static boolean terjaga(LocalTime n) {
        LocalTime w = jadwalBangun.jam;
        LocalTime b = jadwalTidur.jam;
        if (w.isBefore(b)) return !n.isBefore(w) && n.isBefore(b);
        return !n.isBefore(w) || n.isBefore(b);
    }

    // Menampilkan alarm. Return Tunda jika user memilih tunda, null jika selesai.
    static Tunda bunyiAlarm(Alarm a) {
        System.out.println();
        System.out.println("\u0007**********************************");
        System.out.println("  " + ikon(a.jenis) + "  " + a.jam + "  " + a.label.toUpperCase());
        System.out.println("  " + pesan(a.jenis));
        System.out.println("**********************************");
        System.out.println("1. " + tombolOk(a.jenis));
        System.out.println("2. Tunda 5 menit");

        int pilih;
        // do-while: tanya terus sampai user memilih 1 atau 2
        do {
            pilih = bacaInt("Pilih", 1, 2);
        } while (pilih != 1 && pilih != 2);

        if (pilih == 1) {
            if (a.jenis.equals("AIR")) {
                minum(1);
                System.out.println("Tercatat. Total minum: " + minumMl + " ml");
            } else if (a.sumber != null) {
                a.sumber.selesai = true;
            }
            return null;
        }
        System.out.println("Diingatkan lagi 5 menit.");
        return new Tunda(a, LocalDateTime.now().plusMinutes(5));
    }

    static String ikon(String jenis) {
        switch (jenis) {
            case "MAKAN": return "[MAKAN]";
            case "OLAHRAGA": return "[OLAHRAGA]";
            case "BANGUN": return "[BANGUN]";
            case "TIDUR": return "[TIDUR]";
            default: return "[AIR]";
        }
    }

    static String pesan(String jenis) {
        switch (jenis) {
            case "MAKAN": return "Duduk dulu, makan pelan-pelan.";
            case "OLAHRAGA": return "Saatnya bergerak. Badanmu akan berterima kasih.";
            case "BANGUN": return "Selamat pagi! Mulai hari dengan segelas air.";
            case "TIDUR": return "Letakkan ponsel dan istirahat.";
            default: return "Minum segelas air putih sekarang.";
        }
    }

    static String tombolOk(String jenis) {
        switch (jenis) {
            case "MAKAN": return "Sudah makan";
            case "OLAHRAGA": return "Sudah olahraga";
            case "BANGUN": return "Sudah bangun";
            case "TIDUR": return "Siap tidur";
            default: return "Sudah minum 1 gelas";
        }
    }

    // ===================== INPUT (validasi pakai while) =====================
    static String bacaBaris() {
        try {
            String s = IN.readLine();
            return s == null ? "" : s.trim();
        } catch (IOException e) {
            return "";
        }
    }

    static int bacaInt(String label, int min, int max) {
        int hasil = 0;
        boolean valid = false;
        while (!valid) {
            System.out.print(label + " [" + min + "-" + max + "]: ");
            String s = bacaBaris();
            try {
                hasil = Integer.parseInt(s);
                if (hasil >= min && hasil <= max) valid = true;
                else System.out.println("Di luar rentang.");
            } catch (NumberFormatException e) {
                System.out.println("Masukkan angka.");
            }
        }
        return hasil;
    }

    static String bacaTeks(String label) {
        String s = "";
        while (s.isEmpty()) {
            System.out.print(label + ": ");
            s = bacaBaris();
            if (s.isEmpty()) System.out.println("Tidak boleh kosong.");
        }
        return s;
    }

    static LocalTime bacaJam(String label) {
        LocalTime hasil = null;
        while (hasil == null) {
            System.out.print(label + ": ");
            String s = bacaBaris();
            try {
                hasil = LocalTime.parse(s, FMT);
            } catch (DateTimeParseException e) {
                System.out.println("Format jam salah, contoh 07:30");
            }
        }
        return hasil;
    }

    static boolean bacaYaTidak(String label) {
        String s = "";
        while (!s.equals("y") && !s.equals("n")) {
            System.out.print(label + " (y/n): ");
            s = bacaBaris().toLowerCase();
        }
        return s.equals("y");
    }
}
