package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

final class PrayerTimes {
    private static final String TAG = "TvHome.Solat";
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final String[] KEYS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    
    // --- TRANSLASI INDONESIA ---
    static final String[] NAMES = {"Subuh", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "mei", "jun", "jul", "agu", "sep", "okt", "nov", "des"};
    private static final String[] HIJRI_MONTHS = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal",
            "Jumadil Akhir", "Rajab", "Sya'ban", "Ramadhan", "Syawal", "Dzulqa'dah", "Dzulhijjah"};
    // ---------------------------

    static final class Day {
        final int[] minutes;
        final String hijri;

        Day(int[] minutes, String hijri) {
            this.minutes = minutes;
            this.hijri = hijri;
        }
    }

    private PrayerTimes() {
    }

    private static File file(Context ctx, String zone) {
        File base = Config.dir(ctx);
        return base != null ? new File(base, "solat-" + zone + ".json") : null;
    }

    static Day day(Context ctx, String zone, String key) {
        JSONArray days = read(file(ctx, zone));
        for (int i = 0; days != null && i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null || !key.equals(dateKey(d.optString("date")))) continue;
            int[] m = new int[KEYS.length];
            for (int k = 0; k < KEYS.length; k++) {
                m[k] = minutes(d.optString(KEYS[k]));
                if (m[k] < 0) return null;
            }
            return new Day(m, hijri(d.optString("hijri")));
        }
        return null;
    }

    static boolean due(Context ctx, String zone, String todayKey) {
        if (day(ctx, zone, todayKey) != null) return false;
        return System.currentTimeMillis() - prefs(ctx).getLong("tried_" + zone, 0) >= RETRY_MS;
    }

    static boolean refresh(Context ctx, String zone) {
        prefs(ctx).edit().putLong("tried_" + zone, System.currentTimeMillis()).apply();
        File out = file(ctx, zone);
        if (out == null) return false;
        try {
            int year = Calendar.getInstance().get(Calendar.YEAR);
            JSONArray days = new JSONArray();
            // Fetch 12 months from Aladhan API (Tangerang coordinates, Kemenag method)
            for (int month = 1; month <= 12; month++) {
                String url = "https://api.aladhan.com/v1/calendar/" + year + "/" + month + "?latitude=-6.1783&longitude=106.6319&method=20";
                byte[] body = get(url, 1024 * 1024);
                JSONArray data = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("data");
                if (data == null) continue;
                for (int i = 0; i < data.length(); i++) {
                    JSONObject d = data.getJSONObject(i);
                    JSONObject timings = d.getJSONObject("timings");
                    JSONObject obj = new JSONObject();
                    
                    obj.put("fajr", timings.optString("Fajr").split(" ")[0]);
                    obj.put("dhuhr", timings.optString("Dhuhr").split(" ")[0]);
                    obj.put("asr", timings.optString("Asr").split(" ")[0]);
                    obj.put("maghrib", timings.optString("Maghrib").split(" ")[0]);
                    obj.put("isha", timings.optString("Isha").split(" ")[0]);
                    
                    JSONObject date = d.getJSONObject("date");
                    obj.put("date", date.getJSONObject("gregorian").getString("date"));
                    
                    String hDate = date.getJSONObject("hijri").getString("date");
                    String[] hp = hDate.split("-");
                    obj.put("hijri", hp[2] + "-" + hp[1] + "-" + hp[0]);
                    
                    days.put(obj);
                }
            }
            if (days.length() == 0) throw new IllegalStateException("no data");

            File tmp = new File(out.getPath() + ".new");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(days.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(out)) throw new IllegalStateException("rename failed");
            Log.i(TAG, "tersimpan " + days.length() + " hari untuk " + zone);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "gagal mengambil data untuk " + zone + ": " + e.getMessage());
            return false;
        }
    }

    static String key(Calendar c) {
        return String.format(Locale.ROOT, "%02d-%02d-%04d", c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }

    static String dateKey(String date) {
        String[] p = date.trim().split("-");
        if (p.length != 3) return "";
        if (p[0].length() == 2 && p[2].length() == 4) {
            return date; // Aladhan format DD-MM-YYYY
        }
        if (p[1].length() < 3) return "";
        String mon = p[1].substring(0, 3).toLowerCase(Locale.ROOT);
        int m = -1;
        for (int i = 0; i < MONTHS.length; i++) if (MONTHS[i].equals(mon)) m = i;
        if (m < 0) m = "mar".equals(mon) ? 2 : "may".equals(mon) ? 4 : "aug".equals(mon) ? 7
                : "oct".equals(mon) ? 9 : "dec".equals(mon) ? 11 : -1;
        if (m < 0) return "";
        try {
            return String.format(Locale.ROOT, "%02d-%02d-%04d", Integer.parseInt(p[0]), m + 1, Integer.parseInt(p[2]));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    static int minutes(String hms) {
        String[] p = hms.trim().split(":");
        if (p.length < 2) return -1;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? -1 : h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String hijri(String ymd) {
        String[] p = ymd.trim().split("-");
        if (p.length != 3) return "";
        try {
            int m = Integer.parseInt(p[1]);
            if (m < 1 || m > 12) return "";
            return Integer.parseInt(p[2]) + " " + HIJRI_MONTHS[m - 1] + " " + Integer.parseInt(p[0]);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    static int next(int[] minutes, int now) {
        for (int i = 0; i < minutes.length; i++) if (minutes[i] > now) return i;
        return -1;
    }

    // --- HITUNG MUNDUR BAHASA INDONESIA ---
    static String countdown(int minutesLeft) {
        int h = minutesLeft / 60, m = minutesLeft % 60;
        if (h == 0) return "dalam " + Math.max(1, m) + " menit";
        return m == 0 ? "dalam " + h + " jam" : "dalam " + h + " jam " + m + " menit";
    }
    // -------------------------------------

    static String format(int minutes, boolean is24) {
        int h = minutes / 60, m = minutes % 60;
        if (is24) return String.format(Locale.ROOT, "%02d:%02d", h, m);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d", h12, m);
    }

    private static JSONArray read(File f) {
        if (f == null || !f.isFile() || f.length() > 1024 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new JSONArray(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] get(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("prayer_times", Context.MODE_PRIVATE);
    }
}
