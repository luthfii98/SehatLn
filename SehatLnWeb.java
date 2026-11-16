import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SehatLn - Pengingat Hidup Sehat (Java + HTML)
 *
 * Java  : server web kecil + semua logika (jadwal, minum, alarm, tunda).
 * HTML  : tampilan (index.html), mengambil data dari Java lewat /api/...
 *
 * Perulangan yang dipakai hanya while dan do-while.
 *
 * Cara jalan (index.html harus satu folder dengan file ini):
 *   javac SehatLnWeb.java
 *   java SehatLnWeb
 * lalu buka http://localhost:8080
 */
public class SehatLnWeb {

    // ===================== MODEL DATA =====================
    static class Jadwal {
        int id;
        String nama;
        String jenis;       // MAKAN, OLAHRAGA, BANGUN, TIDUR
        LocalTime jam;
        boolean aktif = true;
        boolean selesai = false;
        boolean tetap;      // bangun & tidur tidak bisa dihapus

        Jadwal(int id, String nama, String jenis, LocalTime jam, boolean tetap) {
            this.id = id;
            this.nama = nama;
            this.jenis = jenis;
            this.jam = jam;
            this.tetap = tetap;
        }
    }

    static class Alarm {
        String label;
        String jenis;       // MAKAN, OLAHRAGA, BANGUN, TIDUR, AIR
        String jam;
        int jadwalId;       // -1 jika alarm air / tes

        Alarm(String label, String jenis, String jam, int jadwalId) {
            this.label = label;
            this.jenis = jenis;
            this.jam = jam;
            this.jadwalId = jadwalId;
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
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");
    static final Object LOCK = new Object();      // thread server & thread pemantau berbagi data

    static List<Jadwal> daftar = new ArrayList<>();
    static int idBerikut = 1;
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
    static String preMsg = "";
    static int preSeq = 0;

    static LocalDate hariIni = LocalDate.now();

    static LinkedList<Alarm> antrian = new LinkedList<>();
    static List<Tunda> daftarTunda = new ArrayList<>();
    static Set<String> sudahBunyi = new HashSet<>();

    static volatile boolean jalan = true;

    // ===================== MAIN =====================
    public static void main(String[] args) throws Exception {
        isiDefault();

        // thread pemantau alarm
        Thread pemantau = new Thread(SehatLnWeb::loopPemantau);
        pemantau.setDaemon(true);
        pemantau.start();

        // do-while: coba port 8080, kalau terpakai coba port berikutnya
        HttpServer server = null;
        int port = 8080;
        do {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (BindException e) {
                port++;
            }
        } while (server == null && port <= 8090);

        if (server == null) {
            System.out.println("Gagal membuka port 8080-8090. Tutup aplikasi lain lalu coba lagi.");
            return;
        }

        server.createContext("/api/", SehatLnWeb::handleApi);
        server.createContext("/", SehatLnWeb::handleFile);
        server.start();

        String url = "http://localhost:" + port;
        System.out.println("=====================================");
        System.out.println("   SEHATLN  -  Pengingat Hidup Sehat");
        System.out.println("=====================================");
        System.out.println("Server berjalan di " + url);
        System.out.println("Buka alamat itu di browser. Tekan ENTER di sini untuk berhenti.");

        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            // abaikan, buka manual saja
        }

        new BufferedReader(new InputStreamReader(System.in)).readLine();
        jalan = false;
        server.stop(0);
        System.out.println("Server dihentikan. Tetap sehat!");
    }

    // ===================== DATA AWAL =====================
    static void isiDefault() {
        daftar.add(new Jadwal(idBerikut++, "Sarapan", "MAKAN", LocalTime.of(7, 0), false));
        daftar.add(new Jadwal(idBerikut++, "Makan siang", "MAKAN", LocalTime.of(12, 30), false));
        daftar.add(new Jadwal(idBerikut++, "Makan malam", "MAKAN", LocalTime.of(18, 30), false));
        daftar.add(new Jadwal(idBerikut++, "Olahraga pagi", "OLAHRAGA", LocalTime.of(6, 0), false));
        jadwalBangun = new Jadwal(idBerikut++, "Bangun tidur", "BANGUN", LocalTime.of(5, 30), true);
        jadwalTidur = new Jadwal(idBerikut++, "Waktunya tidur", "TIDUR", LocalTime.of(22, 0), true);
        daftar.add(jadwalBangun);
        daftar.add(jadwalTidur);
    }

    static void gantiHariJikaPerlu() {
        if (!hariIni.equals(LocalDate.now())) {
            hariIni = LocalDate.now();
            minumMl = 0;
            terakhirMinum = LocalDateTime.now();
            sudahBunyi.clear();
            int i = 0;
            while (i < daftar.size()) {
                daftar.get(i).selesai = false;
                i++;
            }
        }
    }

    // ===================== PEMANTAU ALARM =====================
    // while (jalan): cek waktu tiap detik di thread terpisah
    static void loopPemantau() {
        while (jalan) {
            synchronized (LOCK) {
                periksaAlarm();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                jalan = false;
            }
        }
    }

    static void periksaAlarm() {
        gantiHariJikaPerlu();
        LocalDateTime kini = LocalDateTime.now();
        LocalTime t = kini.toLocalTime().withSecond(0).withNano(0);
        String tanggal = kini.toLocalDate().toString();

        // 1) alarm tepat waktu
        int i = 0;
        while (i < daftar.size()) {
            Jadwal j = daftar.get(i);
            if (j.aktif && j.jam.equals(t)) {
                String kunci = tanggal + "|" + j.id + "|" + j.jam;
                if (sudahBunyi.add(kunci)) {
                    antrian.add(new Alarm(j.nama, j.jenis, j.jam.format(FMT), j.id));
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
                    String kunci = "pre|" + tanggal + "|" + j.id + "|" + j.jam;
                    if (sudahBunyi.add(kunci)) {
                        preMsg = j.nama + " " + preMenit + " menit lagi (" + j.jam.format(FMT) + ")";
                        preSeq++;
                    }
                }
                i++;
            }
        }

        // 3) alarm yang ditunda
        i = 0;
        while (i < daftarTunda.size()) {
            Tunda td = daftarTunda.get(i);
            if (!kini.isBefore(td.kapan)) {
                daftarTunda.remove(i);
                antrian.add(td.alarm);
            } else {
                i++;
            }
        }

        // 4) alarm minum berulang selama terjaga
        boolean sedangTerjaga = terjaga(t);
        boolean sudahLewat = kini.isAfter(terakhirMinum.plusMinutes(intervalMenit));
        if (airAktif && minumMl < targetMl && sedangTerjaga && sudahLewat) {
            terakhirMinum = kini;
            antrian.add(new Alarm("Waktunya minum", "AIR", t.format(FMT), -1));
        }
    }

    static boolean terjaga(LocalTime n) {
        LocalTime w = jadwalBangun.jam;
        LocalTime b = jadwalTidur.jam;
        if (w.isBefore(b)) return !n.isBefore(w) && n.isBefore(b);
        return !n.isBefore(w) || n.isBefore(b);
    }

    // ===================== AKSI =====================
    static void minum(int n) {
        minumMl += gelasMl * n;
        terakhirMinum = LocalDateTime.now();
    }

    static Jadwal cari(String idTeks) {
        int id;
        try {
            id = Integer.parseInt(idTeks);
        } catch (Exception e) {
            return null;
        }
        int i = 0;
        while (i < daftar.size()) {
            if (daftar.get(i).id == id) return daftar.get(i);
            i++;
        }
        return null;
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

    static int angka(String s, int min, int max, int lama) {
        try {
            int v = Integer.parseInt(s.trim());
            return (v >= min && v <= max) ? v : lama;
        } catch (Exception e) {
            return lama;
        }
    }

    static LocalTime jamAtauNull(String s) {
        try {
            return LocalTime.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== API =====================
    static void handleApi(HttpExchange ex) throws IOException {
        String json;
        try {
            synchronized (LOCK) {
                json = prosesApi(ex);
            }
        } catch (Exception e) {
            json = "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}";
        }
        kirim(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    static String prosesApi(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring(5);   // buang "/api/"
        Map<String, String> p = new HashMap<>();
        parseForm(ex.getRequestURI().getRawQuery(), p);
        if ("POST".equals(ex.getRequestMethod())) {
            parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), p);
        }
        gantiHariJikaPerlu();

        switch (path) {
            case "state":
                break;
            case "drink":
                minum(1);
                break;
            case "undrink":
                minumMl = Math.max(0, minumMl - gelasMl);
                break;
            case "done": {
                Jadwal j = cari(p.getOrDefault("id", ""));
                if (j != null) j.selesai = !j.selesai;
                break;
            }
            case "toggle": {
                Jadwal j = cari(p.getOrDefault("id", ""));
                if (j != null) j.aktif = !j.aktif;
                break;
            }
            case "add": {
                String jenis = p.getOrDefault("jenis", "");
                String nama = p.getOrDefault("nama", "").trim();
                LocalTime jam = jamAtauNull(p.getOrDefault("jam", ""));
                boolean jenisOk = jenis.equals("MAKAN") || jenis.equals("OLAHRAGA");
                if (jenisOk && !nama.isEmpty() && jam != null) {
                    daftar.add(new Jadwal(idBerikut++, nama, jenis, jam, false));
                }
                break;
            }
            case "update": {
                Jadwal j = cari(p.getOrDefault("id", ""));
                if (j != null) {
                    String nama = p.get("nama");
                    if (nama != null && !nama.trim().isEmpty() && !j.tetap) j.nama = nama.trim();
                    LocalTime jam = jamAtauNull(p.getOrDefault("jam", ""));
                    if (jam != null) j.jam = jam;
                }
                break;
            }
            case "delete": {
                Jadwal j = cari(p.getOrDefault("id", ""));
                if (j != null && !j.tetap) daftar.remove(j);
                break;
            }
            case "settings":
                terapkanPengaturan(p);
                break;
            case "alarm/ok": {
                Alarm a = antrian.pollFirst();
                if (a != null) {
                    if (a.jenis.equals("AIR")) {
                        minum(1);
                    } else {
                        Jadwal j = cari(String.valueOf(a.jadwalId));
                        if (j != null) j.selesai = true;
                    }
                }
                break;
            }
            case "alarm/snooze": {
                Alarm a = antrian.pollFirst();
                if (a != null) daftarTunda.add(new Tunda(a, LocalDateTime.now().plusMinutes(5)));
                break;
            }
            case "test":
                antrian.add(new Alarm("Coba alarm", "AIR", LocalTime.now().format(FMT), -1));
                break;
            default:
                return "{\"error\":\"endpoint tidak dikenal\"}";
        }
        return stateJson();
    }

    static void terapkanPengaturan(Map<String, String> p) {
        if (p.containsKey("goal"))     targetMl = angka(p.get("goal"), 250, 10000, targetMl);
        if (p.containsKey("glass"))    gelasMl = angka(p.get("glass"), 50, 1000, gelasMl);
        if (p.containsKey("interval")) intervalMenit = angka(p.get("interval"), 1, 600, intervalMenit);
        if (p.containsKey("waterOn"))  airAktif = "true".equals(p.get("waterOn"));
        if (p.containsKey("preMin"))   preMenit = angka(p.get("preMin"), 1, 120, preMenit);
        if (p.containsKey("preOn"))    preAktif = "true".equals(p.get("preOn"));
        if (p.containsKey("bedOn"))    jadwalTidur.aktif = "true".equals(p.get("bedOn"));
        if (p.containsKey("wakeOn"))   jadwalBangun.aktif = "true".equals(p.get("wakeOn"));
        if (p.containsKey("bed")) {
            LocalTime t = jamAtauNull(p.get("bed"));
            if (t != null) jadwalTidur.jam = t;
        }
        if (p.containsKey("wake")) {
            LocalTime t = jamAtauNull(p.get("wake"));
            if (t != null) jadwalBangun.jam = t;
        }
    }

    // ===================== JSON =====================
    static String stateJson() {
        LocalTime kini = LocalTime.now();
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"tanggal\":\"").append(hariIni).append("\",");
        sb.append("\"jam\":\"").append(kini.withNano(0)).append("\",");
        sb.append("\"ml\":").append(minumMl).append(",");
        sb.append("\"goal\":").append(targetMl).append(",");
        sb.append("\"glass\":").append(gelasMl).append(",");
        sb.append("\"interval\":").append(intervalMenit).append(",");
        sb.append("\"waterOn\":").append(airAktif).append(",");
        sb.append("\"preOn\":").append(preAktif).append(",");
        sb.append("\"preMin\":").append(preMenit).append(",");
        sb.append("\"preSeq\":").append(preSeq).append(",");
        sb.append("\"preMsg\":\"").append(esc(preMsg)).append("\",");
        sb.append("\"bed\":\"").append(jadwalTidur.jam.format(FMT)).append("\",");
        sb.append("\"wake\":\"").append(jadwalBangun.jam.format(FMT)).append("\",");
        sb.append("\"bedOn\":").append(jadwalTidur.aktif).append(",");
        sb.append("\"wakeOn\":").append(jadwalBangun.aktif).append(",");

        Alarm a = antrian.peekFirst();
        sb.append("\"alarm\":");
        if (a == null) {
            sb.append("null");
        } else {
            sb.append("{\"label\":\"").append(esc(a.label)).append("\",")
              .append("\"jenis\":\"").append(a.jenis).append("\",")
              .append("\"jam\":\"").append(a.jam).append("\"}");
        }

        sb.append(",\"jadwal\":[");
        List<Jadwal> urut = urutkan();
        int i = 0;
        while (i < urut.size()) {
            Jadwal j = urut.get(i);
            String status;
            if (!j.aktif) status = "off";
            else if (j.jam.isBefore(kini) && !j.selesai) status = "terlewat";
            else status = "aktif";

            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(j.id)
              .append(",\"nama\":\"").append(esc(j.nama)).append("\"")
              .append(",\"jenis\":\"").append(j.jenis).append("\"")
              .append(",\"jam\":\"").append(j.jam.format(FMT)).append("\"")
              .append(",\"aktif\":").append(j.aktif)
              .append(",\"selesai\":").append(j.selesai)
              .append(",\"tetap\":").append(j.tetap)
              .append(",\"status\":\"").append(status).append("\"}");
            i++;
        }
        sb.append("]}");
        return sb.toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // "a=1&b=2" -> map
    static void parseForm(String s, Map<String, String> out) {
        if (s == null || s.isEmpty()) return;
        String[] bagian = s.split("&");
        int i = 0;
        while (i < bagian.length) {
            String b = bagian[i];
            int sama = b.indexOf('=');
            if (sama > 0) {
                String k = URLDecoder.decode(b.substring(0, sama), StandardCharsets.UTF_8);
                String v = URLDecoder.decode(b.substring(sama + 1), StandardCharsets.UTF_8);
                out.put(k, v);
            }
            i++;
        }
    }

    // ===================== FILE HTML =====================
    static void handleFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            kirim(ex, 404, "text/plain; charset=utf-8", "Tidak ditemukan".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path f = Paths.get("index.html");
        if (!Files.exists(f)) {
            String msg = "index.html tidak ditemukan. Taruh index.html satu folder dengan SehatLnWeb.java, "
                    + "lalu jalankan java SehatLnWeb dari folder itu.";
            kirim(ex, 404, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
            return;
        }
        kirim(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(f));
    }

    static void kirim(HttpExchange ex, int kode, String tipe, byte[] isi) throws IOException {
        ex.getResponseHeaders().set("Content-Type", tipe);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(kode, isi.length);
        ex.getResponseBody().write(isi);
        ex.close();
    }
}
