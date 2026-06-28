package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.text.InputType;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.*;

public class MainActivity extends Activity {
    final int BG = Color.rgb(3, 15, 27);
    final int CARD = Color.rgb(8, 28, 48);
    final int CARD2 = Color.rgb(10, 38, 64);
    final int BLUE = Color.rgb(0, 180, 255);
    final int GREEN = Color.rgb(95, 255, 95);
    final int AMBER = Color.rgb(255, 190, 70);
    final int RED = Color.rgb(255, 82, 82);

    LinearLayout root, tabs;
    TextView status, sinrBig, qualityBig, rsrpTxt, rsrqTxt, rssiTxt, bandTxt, bestTxt, cellTxt, rawTxt;
    EditText routerUrl, adminPass;
    Handler handler = new Handler(Looper.getMainLooper());
    boolean running = false;
    double bestSinr = -999;
    ToneGenerator tone;
    Vibrator vibrator;
    String sessionCookie = "";
    String requestToken = "";
    int screen = 0;

    Runnable poller = new Runnable() {
        public void run() {
            if (!running) return;
            readSignal();
            handler.postDelayed(this, 1000);
        }
    };

    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        trustAllHttps();
        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabs.setBackgroundColor(Color.rgb(2, 11, 22));
        shell.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        setContentView(shell);
        drawTabs();
        showHome();
    }

    int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    void drawTabs() {
        tabs.removeAllViews();
        tab("Home", 0);
        tab("Live", 1);
        tab("Scan", 2);
        tab("Tower", 3);
        tab("Reports", 4);
        tab("More", 5);
    }

    void tab(String label, int target) {
        TextView t = text(label, 11, screen == target ? BLUE : Color.rgb(165, 180, 195), true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(7), 0, dp(7));
        if (screen == target) t.setBackground(round(Color.rgb(5, 38, 65), dp(14), BLUE));
        t.setOnClickListener(v -> {
            screen = target;
            drawTabs();
            if (target == 0) showHome();
            if (target == 1) showLive();
            if (target == 2) showScan();
            if (target == 3) showTower();
            if (target == 4) showReports();
            if (target == 5) showMore();
        });
        tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    }

    void clear() { root.removeAllViews(); }

    void showHome() {
        clear();
        image("art_home", 620);
        pillTitle("Signal Scout", "Professional LTE & 5G Installation Assistant");
        root.addView(primary("Tap to Begin", v -> { screen = 1; drawTabs(); showLive(); }));

        root.addView(card("What are you trying to achieve today?",
                "🛰️ New Antenna Installation\n📶 Improve Existing Installation\n🚀 Find the Fastest Connection\n🔍 Diagnose Poor Signal\n⚙️ Advanced / Professional Mode"));

        root.addView(primary("Guided Installation", v -> showGuide()));
        root.addView(primary("Live Signal Finder", v -> { screen = 1; drawTabs(); showLive(); }));
        root.addView(primary("Band Scanner", v -> { screen = 2; drawTabs(); showScan(); }));
    }

    void showGuide() {
        clear();
        image("art_scout", 280);
        pillTitle("Guided Installation", "Scout will walk you through the job.");
        root.addView(card("Step 1 - Connect", "Connect to the router and confirm live signal readings."));
        root.addView(card("Step 2 - Aim", "Rotate the antenna slowly. Stop when the beeps are fastest and the quality score is highest."));
        root.addView(card("Step 3 - Scan Bands", "Scan all available LTE bands and combinations to find the best pair."));
        root.addView(card("Step 4 - Report", "Save the final signal quality, bands and tower information."));
        root.addView(primary("Start Live Signal Finder", v -> { screen = 1; drawTabs(); showLive(); }));
    }

    void showLive() {
        clear();
        image("art_dashboard", 300);
        pillTitle("Dashboard", "Live router signal and antenna alignment.");

        routerUrl = edit("https://hirouter.net", "Router URL", false);
        adminPass = edit("", "Router admin password", true);
        root.addView(routerUrl);
        root.addView(adminPass);
        root.addView(primary("Login to Router", v -> login()));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(action("Start", v -> startLive()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(action("Stop", v -> stopLive()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(action("Reset", v -> { bestSinr = -999; if (bestTxt != null) bestTxt.setText("Best --"); }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);

        root.addView(primary("Test Read Once / Show Raw Reply", v -> readSignal()));

        status = text("Not logged in", 14, Color.LTGRAY, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        qualityBig = text("Quality -- / 100", 30, GREEN, true);
        qualityBig.setGravity(Gravity.CENTER);
        root.addView(cardView(qualityBig));

        sinrBig = text("-- dB", 74, RED, true);
        sinrBig.setGravity(Gravity.CENTER);
        root.addView(cardView(sinrBig));

        bestTxt = text("Best --", 20, Color.WHITE, true);
        bestTxt.setGravity(Gravity.CENTER);
        root.addView(bestTxt);

        LinearLayout grid1 = new LinearLayout(this);
        grid1.setOrientation(LinearLayout.HORIZONTAL);
        rsrpTxt = metric("RSRP", "--");
        sinrPlaceholder();
        rsrqTxt = metric("RSRQ", "--");
        grid1.addView(rsrpTxt, new LinearLayout.LayoutParams(0, -2, 1));
        grid1.addView(rsrqTxt, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(grid1);

        LinearLayout grid2 = new LinearLayout(this);
        grid2.setOrientation(LinearLayout.HORIZONTAL);
        rssiTxt = metric("RSSI", "--");
        bandTxt = metric("Band", "--");
        grid2.addView(rssiTxt, new LinearLayout.LayoutParams(0, -2, 1));
        grid2.addView(bandTxt, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(grid2);

        cellTxt = card("Connected Cell", "PCI: --\nEARFCN: --\nCell ID: --\neNodeB: --");
        root.addView(cellTxt);

        rawTxt = text("Raw reply will show here.", 10, Color.GRAY, false);
        rawTxt.setPadding(0, dp(8), 0, dp(20));
        root.addView(rawTxt);
    }

    void sinrPlaceholder() {}

    void showScan() {
        clear();
        image("art_scan", 430);
        pillTitle("Scan Bands", "Scanning available bands and combinations.");
        root.addView(card("Band Scanner", "Scanning all available LTE bands for the best signal and combination.\n\nThis may take a few minutes."));
        root.addView(progressCard("Progress", "75%", "B1   2100 MHz     -95 dBm\nB3   1800 MHz     -78 dBm  ✓\nB7   2600 MHz     -98 dBm\nB20  800 MHz      -82 dBm  ✓\nB28  700 MHz      -101 dBm"));
        root.addView(primary("Start Smart Scan (Coming Soon)", v -> Toast.makeText(this, "Band scanner will be added after login works", Toast.LENGTH_LONG).show()));
    }

    void showTower() {
        clear();
        image("art_tower", 450);
        pillTitle("Tower Direction", "Use compass/camera guidance to aim the antenna.");
        TextView arrow = text("↑\n23°\nNE", 54, GREEN, true);
        arrow.setGravity(Gravity.CENTER);
        root.addView(cardView(arrow));
        root.addView(card("Best Signal This Direction", "-72 dBm\n\nTower Camera and AR marker coming soon."));
    }

    void showReports() {
        clear();
        image("art_reports", 450);
        pillTitle("Reports", "Installer report tools.");
        root.addView(card("Site Survey", "12 Jun 2026 - 10:30\nNG12 3NH\nExcellent"));
        root.addView(primary("View Report", v -> Toast.makeText(this, "Reports coming soon", Toast.LENGTH_SHORT).show()));
        root.addView(primary("Export PDF", v -> Toast.makeText(this, "PDF export coming soon", Toast.LENGTH_SHORT).show()));
        root.addView(primary("Share", v -> Toast.makeText(this, "Share coming soon", Toast.LENGTH_SHORT).show()));
    }

    void showMore() {
        clear();
        image("art_scout", 300);
        pillTitle("Scout", "Your friendly signal expert.");
        root.addView(card("Signal Quality", "Excellent 90-100\nGood 70-89\nNeeds Improvement 40-69\nPoor 20-39\nNo Signal 0-19"));
        root.addView(card("Version", "Signal Scout v1.0.1 beta\nProfessional UI preview\nHuawei B535 testing build"));
        root.addView(card("Rollback", "Older APKs stay available in GitHub Releases."));
    }

    TextView progressCard(String head, String pct, String body) {
        return card(head, pct + "\n\n" + body);
    }

    void image(String name, int height) {
        ImageView img = new ImageView(this);
        img.setImageResource(getResources().getIdentifier(name, "drawable", getPackageName()));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(height));
        lp.setMargins(0, 0, 0, dp(10));
        root.addView(img, lp);
    }

    void pillTitle(String h, String sub) {
        TextView a = text(h, 30, Color.WHITE, true);
        a.setGravity(Gravity.CENTER);
        root.addView(a);
        TextView b = text(sub, 15, BLUE, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, dp(10));
        root.addView(b);
    }

    TextView metric(String label, String value) {
        TextView v = text(label + "\n" + value, 22, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(18), dp(8), dp(18));
        v.setBackground(round(CARD, dp(18), Color.rgb(18, 58, 92)));
        return v;
    }

    TextView card(String h, String b) {
        TextView v = text(h + "\n" + b, 16, Color.WHITE, false);
        v.setPadding(dp(18), dp(16), dp(18), dp(16));
        v.setBackground(round(CARD, dp(18), Color.rgb(18, 58, 92)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        v.setLayoutParams(lp);
        return v;
    }

    View cardView(View inside) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setBackground(round(CARD2, dp(22), Color.rgb(18, 80, 120)));
        box.addView(inside, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    Button primary(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setBackgroundColor(Color.rgb(0, 110, 190));
        b.setOnClickListener(l);
        return b;
    }

    Button action(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(45, 70, 95));
        b.setOnClickListener(l);
        return b;
    }

    EditText edit(String text, String hint, boolean password) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        e.setBackgroundColor(Color.rgb(6, 24, 42));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(null, 1);
        return v;
    }

    Drawable round(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(dp(1), stroke);
        return g;
    }

    void startLive() {
        running = true;
        bestSinr = -999;
        status.setText("Running...");
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    void stopLive() {
        running = false;
        handler.removeCallbacks(poller);
        status.setText("Stopped");
    }

    String base() {
        String b = routerUrl.getText().toString().trim();
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    void login() {
        status.setText("Logging in...");
        new Thread(() -> {
            String debug = "";
            try {
                String sesTok = httpGet("/api/webserver/SesTokInfo");
                sessionCookie = pick(sesTok, "SesInfo");
                requestToken = pick(sesTok, "TokInfo");
                String pass = adminPass.getText().toString();

                debug += "SesTokInfo OK\nToken length: " + requestToken.length() + "\nCookie length: " + sessionCookie.length() + "\n\n";

                String pass1 = b64HexSha256(pass);
                String finalPass = b64HexSha256("admin" + pass1 + requestToken);
                String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><Username>admin</Username><Password>" + finalPass + "</Password><password_type>4</password_type></request>";
                HttpResult res = httpPost("/api/user/login", body, requestToken);
                String newTok = headerToken(res);
                if (newTok.length() > 0) requestToken = newTok;

                debug += "LOGIN TRY 1 b64hex/type4\nHTTP " + res.code + "\n" + res.body + "\n\n";

                final String finalDebug = debug;
                final boolean ok = finalDebug.contains("<response>OK</response>");
                runOnUiThread(() -> {
                    rawTxt.setText(finalDebug);
                    status.setText(ok ? "Logged in OK - press Test or Start" : "Login not OK - see raw");
                });
            } catch (Exception e) {
                final String finalDebug = debug + "\nEXCEPTION:\n" + e.toString();
                runOnUiThread(() -> {
                    status.setText("Login failed: " + e.getClass().getSimpleName());
                    rawTxt.setText(finalDebug);
                });
            }
        }).start();
    }

    void readSignal() {
        status.setText("Reading...");
        new Thread(() -> {
            try {
                String xml = httpGet("/api/device/signal");
                runOnUiThread(() -> updateFromXml(xml));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Read failed: " + e.getClass().getSimpleName());
                    rawTxt.setText(e.toString());
                });
            }
        }).start();
    }

    String httpGet(String path) throws Exception {
        URL url = new URL(base() + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(7000);
        con.setRequestMethod("GET");
        con.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 SignalScout");
        con.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (sessionCookie.length() > 0) con.setRequestProperty("Cookie", sessionCookie);
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        return readAll(is);
    }

    HttpResult httpPost(String path, String body, String token) throws Exception {
        URL url = new URL(base() + path);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(7000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/xml");
        con.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 SignalScout");
        con.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (sessionCookie.length() > 0) con.setRequestProperty("Cookie", sessionCookie);
        if (token.length() > 0) con.setRequestProperty("__RequestVerificationToken", token);
        OutputStream os = con.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        return new HttpResult(code, readAll(is), con.getHeaderFields());
    }

    String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    String headerToken(HttpResult res) {
        for (String k : res.headers.keySet()) {
            if (k != null && k.equalsIgnoreCase("__RequestVerificationToken")) {
                List<String> vals = res.headers.get(k);
                if (vals != null && vals.size() > 0) return vals.get(0);
            }
        }
        return "";
    }

    void updateFromXml(String xml) {
        rawTxt.setText("HTTP 200\n\n" + xml);

        String sinr = pick(xml, "sinr", "snr");
        String rsrp = pick(xml, "rsrp");
        String rsrq = pick(xml, "rsrq");
        String rssi = pick(xml, "rssi");
        String band = pick(xml, "band");
        String pci = pick(xml, "pci");
        String earfcn = pick(xml, "earfcn");
        String cell = pick(xml, "cell_id");
        String enodeb = pick(xml, "enodeb_id");

        double sinrNum = num(sinr);
        int quality = qualityScore(sinrNum, num(rsrq), num(rsrp));

        status.setText(sinr.length() == 0 ? (xml.toLowerCase().contains("<error>") ? "Router returned error XML" : "Connected but no SINR found") : "Updated OK");
        qualityBig.setText("Quality " + (quality < 0 ? "--" : quality) + " / 100");
        qualityBig.setTextColor(qualityColor(quality));
        sinrBig.setText(sinr.length() > 0 ? sinr : "-- dB");
        sinrBig.setTextColor(sinrColor(sinrNum));

        rsrpTxt.setText("RSRP\n" + empty(rsrp));
        rsrpTxt.setTextColor(rsrpColor(num(rsrp)));
        rsrqTxt.setText("RSRQ\n" + empty(rsrq));
        rsrqTxt.setTextColor(rsrqColor(num(rsrq)));
        rssiTxt.setText("RSSI\n" + empty(rssi));
        bandTxt.setText("Band\n" + (band.length() > 0 ? "B" + band : "--"));

        cellTxt.setText("Connected Cell\nPCI: " + empty(pci) + "\nEARFCN: " + empty(earfcn) + "\nCell ID: " + empty(cell) + "\neNodeB: " + empty(enodeb));

        if (sinrNum > -900) {
            if (sinrNum > bestSinr) {
                bestSinr = sinrNum;
                bestTxt.setText("Best " + bestSinr + " dB");
                vibrate();
            }
            beepForSinr(sinrNum);
        }
    }

    int qualityScore(double sinr, double rsrq, double rsrp) {
        if (sinr < -900) return -1;
        int s = 0;
        s += clamp((int)((sinr + 5) / 30.0 * 60), 0, 60);
        s += clamp((int)((rsrq + 20) / 12.0 * 25), 0, 25);
        s += clamp((int)((rsrp + 115) / 35.0 * 15), 0, 15);
        return clamp(s, 0, 100);
    }

    int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    void beepForSinr(double sinr) {
        int duration = 40;
        if (sinr >= 20) duration = 130;
        else if (sinr >= 13) duration = 90;
        else if (sinr >= 5) duration = 60;
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
    }

    void vibrate() {
        try {
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(80);
        } catch (Exception ignored) {}
    }

    String pick(String xml, String... tags) {
        for (String tag : tags) {
            Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
            if (m.find()) return m.group(1).trim();
        }
        return "";
    }

    double num(String s) {
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(s == null ? "" : s);
        if (m.find()) try { return Double.parseDouble(m.group()); } catch (Exception ignored) {}
        return -999;
    }

    String empty(String s) { return s == null || s.length() == 0 ? "--" : s; }
    byte[] sha256Bytes(String input) throws Exception { return MessageDigest.getInstance("SHA-256").digest(input.getBytes("UTF-8")); }

    String sha256Hex(String input) throws Exception {
        byte[] out = sha256Bytes(input);
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    String b64HexSha256(String input) throws Exception {
        return Base64.encodeToString(sha256Hex(input).getBytes("UTF-8"), Base64.NO_WRAP);
    }

    int qualityColor(int q) {
        if (q >= 85) return GREEN;
        if (q >= 65) return Color.rgb(200,255,90);
        if (q >= 40) return AMBER;
        return RED;
    }

    int sinrColor(double v) { if (v >= 20) return GREEN; if (v >= 13) return Color.rgb(200,255,90); if (v >= 5) return AMBER; return RED; }
    int rsrpColor(double v) { if (v >= -85) return GREEN; if (v >= -95) return Color.rgb(200,255,90); if (v >= -105) return AMBER; return RED; }
    int rsrqColor(double v) { if (v >= -10) return GREEN; if (v >= -13) return Color.rgb(200,255,90); if (v >= -15) return AMBER; return RED; }

    void trustAllHttps() {
        try {
            TrustManager[] t = new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, t, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}
    }

    static class HttpResult {
        int code;
        String body;
        Map<String, List<String>> headers;
        HttpResult(int c, String b, Map<String, List<String>> h) { code = c; body = b; headers = h; }
    }
}
