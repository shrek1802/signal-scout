package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.graphics.Color;
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
    EditText routerUrl, adminPass;
    TextView status, sinrView, bestView, rsrpView, rsrqView, rssiView, bandView, cellView, rawView;
    Button loginBtn, startBtn, stopBtn, resetBtn, testBtn;
    Switch beepSwitch;
    Handler handler = new Handler(Looper.getMainLooper());
    boolean running = false;
    double bestSinr = -999;
    ToneGenerator tone;
    Vibrator vibrator;
    String sessionCookie = "";
    String requestToken = "";

    Runnable poller = new Runnable() {
        @Override public void run() {
            if (!running) return;
            readSignal();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        trustAllHttps();
        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
        buildUi();
    }

    void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 18, 24, 24);
        root.setBackgroundColor(Color.rgb(16,16,16));
        scroll.addView(root);

        TextView title = txt("Signal Scout v0.1.4", 28, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        routerUrl = edit("https://hirouter.net", "https://hirouter.net or http://192.168.8.1", false);
        root.addView(routerUrl);

        adminPass = edit("", "Router admin password", true);
        root.addView(adminPass);

        loginBtn = btn("LOGIN TO ROUTER");
        root.addView(loginBtn);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);

        startBtn = btn("START");
        stopBtn = btn("STOP");
        resetBtn = btn("RESET");

        buttons.addView(startBtn, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(resetBtn, new LinearLayout.LayoutParams(0, -2, 1));

        testBtn = btn("TEST READ ONCE / SHOW RAW REPLY");
        root.addView(testBtn);

        beepSwitch = new Switch(this);
        beepSwitch.setText("Beep faster when SINR gets better");
        beepSwitch.setTextColor(Color.WHITE);
        beepSwitch.setChecked(true);
        root.addView(beepSwitch);

        status = txt("Not logged in", 15, Color.LTGRAY);
        root.addView(status);

        TextView lab = txt("SINR / SNR - aim highest", 18, Color.LTGRAY);
        lab.setGravity(Gravity.CENTER);
        lab.setPadding(0,14,0,0);
        root.addView(lab);

        sinrView = txt("-- dB", 76, Color.rgb(255,90,90));
        sinrView.setGravity(Gravity.CENTER);
        sinrView.setTypeface(null, 1);
        root.addView(sinrView);

        bestView = txt("Best: --", 24, Color.WHITE);
        bestView.setGravity(Gravity.CENTER);
        root.addView(bestView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        rsrpView = metric("RSRP\n--");
        rsrqView = metric("RSRQ\n--");

        row1.addView(rsrpView, new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(rsrqView, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        rssiView = metric("RSSI\n--");
        bandView = metric("Band\n--");

        row2.addView(rssiView, new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(bandView, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row2);

        cellView = txt("PCI: --\nEARFCN: --\nCell ID: --\neNodeB: --", 16, Color.LTGRAY);
        cellView.setPadding(12,12,12,12);
        root.addView(cellView);

        rawView = txt("Raw reply will show here.", 11, Color.GRAY);
        rawView.setPadding(0,12,0,0);
        root.addView(rawView);

        loginBtn.setOnClickListener(v -> login());
        startBtn.setOnClickListener(v -> start());
        stopBtn.setOnClickListener(v -> stop());
        resetBtn.setOnClickListener(v -> {
            bestSinr = -999;
            bestView.setText("Best: --");
        });
        testBtn.setOnClickListener(v -> readSignal());

        setContentView(scroll);
    }

    EditText edit(String text, String hint, boolean password) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        return b;
    }

    TextView metric(String s) {
        TextView v = txt(s, 22, Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setPadding(8,18,8,18);
        return v;
    }

    TextView txt(String s, int size, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    String base() {
        String b = routerUrl.getText().toString().trim();
        if (b.endsWith("/")) b = b.substring(0, b.length()-1);
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

                debug += "SesTokInfo OK\n";
                debug += "Token length: " + requestToken.length() + "\n";
                debug += "Cookie length: " + sessionCookie.length() + "\n\n";

                String pass1 = b64HexSha256(pass);
                String finalPass = b64HexSha256("admin" + pass1 + requestToken);

                String body =
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<request>" +
                        "<Username>admin</Username>" +
                        "<Password>" + finalPass + "</Password>" +
                        "<password_type>4</password_type>" +
                        "</request>";

                HttpResult res = httpPost("/api/user/login", body, requestToken);

                String newTok = headerToken(res);
                if (newTok.length() > 0) requestToken = newTok;

                debug += "LOGIN TRY 1 b64hex/type4\n";
                debug += "HTTP " + res.code + "\n";
                debug += res.body + "\n\n";

                if (!res.body.contains("<response>OK</response>")) {
                    String passHashB64 = base64Bytes(sha256Bytes(pass));
                    String loginHashB64 = base64Bytes(sha256Bytes("admin" + passHashB64 + requestToken));

                    String body2 =
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                            "<request>" +
                            "<Username>admin</Username>" +
                            "<Password>" + loginHashB64 + "</Password>" +
                            "<password_type>4</password_type>" +
                            "</request>";

                    HttpResult res2 = httpPost("/api/user/login", body2, requestToken);

                    String newTok2 = headerToken(res2);
                    if (newTok2.length() > 0) requestToken = newTok2;

                    debug += "LOGIN TRY 2 rawbytes/type4\n";
                    debug += "HTTP " + res2.code + "\n";
                    debug += res2.body + "\n\n";

                    res = res2;
                }

                if (!debug.contains("<response>OK</response>")) {
                    String user64 = Base64.encodeToString("admin".getBytes("UTF-8"), Base64.NO_WRAP);
                    String pass64 = Base64.encodeToString(pass.getBytes("UTF-8"), Base64.NO_WRAP);
                    String hashHex = sha256Hex(user64 + pass64 + requestToken);
                    String oldPass = Base64.encodeToString(hashHex.getBytes("UTF-8"), Base64.NO_WRAP);

                    String body3 =
                            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                            "<request>" +
                            "<Username>admin</Username>" +
                            "<Password>" + oldPass + "</Password>" +
                            "<password_type>4</password_type>" +
                            "</request>";

                    HttpResult res3 = httpPost("/api/user/login", body3, requestToken);

                    String newTok3 = headerToken(res3);
                    if (newTok3.length() > 0) requestToken = newTok3;

                    debug += "LOGIN TRY 3 oldfallback/type4\n";
                    debug += "HTTP " + res3.code + "\n";
                    debug += res3.body + "\n\n";
                }

                final String finalDebug = debug;
                final boolean ok = finalDebug.contains("<response>OK</response>");

                runOnUiThread(() -> {
                    rawView.setText(finalDebug);
                    status.setText(ok ? "Logged in OK - press Test or Start" : "Login not OK - see raw");
                });

            } catch(Exception e) {
                final String finalDebug = debug + "\nEXCEPTION:\n" + e.toString();
                runOnUiThread(() -> {
                    status.setText("Login failed: " + e.getClass().getSimpleName());
                    rawView.setText(finalDebug);
                });
            }
        }).start();
    }

    void start() {
        running = true;
        bestSinr = -999;
        status.setText("Running...");
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poller);
        status.setText("Stopped");
    }

    void readSignal() {
        status.setText("Reading...");
        new Thread(() -> {
            try {
                String xml = httpGet("/api/device/signal");
                runOnUiThread(() -> updateFromXml(xml, 200));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Read failed: " + e.getClass().getSimpleName());
                    rawView.setText(e.toString());
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

        if (sessionCookie.length() > 0) {
            con.setRequestProperty("Cookie", sessionCookie);
        }

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

        if (sessionCookie.length() > 0) {
            con.setRequestProperty("Cookie", sessionCookie);
        }

        if (token.length() > 0) {
            con.setRequestProperty("__RequestVerificationToken", token);
        }

        OutputStream os = con.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();

        int code = con.getResponseCode();
        InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String resp = readAll(is);

        return new HttpResult(code, resp, con.getHeaderFields());
    }

    String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;

        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }

        br.close();
        return sb.toString();
    }

    String headerToken(HttpResult res) {
        for (String k: res.headers.keySet()) {
            if (k != null && k.equalsIgnoreCase("__RequestVerificationToken")) {
                List<String> vals = res.headers.get(k);
                if (vals != null && vals.size() > 0) return vals.get(0);
            }
        }
        return "";
    }

    void updateFromXml(String xml, int code) {
        rawView.setText("HTTP " + code + "\n\n" + xml);

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

        if (sinr.length() == 0 && xml.toLowerCase().contains("<error>")) {
            status.setText("Router returned error XML");
        } else if (sinr.length() == 0) {
            status.setText("Connected but no SINR found");
        } else {
            status.setText("Updated OK");
        }

        sinrView.setText(sinr.length() > 0 ? sinr : "-- dB");
        sinrView.setTextColor(sinrColor(sinrNum));

        rsrpView.setText("RSRP\n" + empty(rsrp));
        rsrpView.setTextColor(rsrpColor(num(rsrp)));

        rsrqView.setText("RSRQ\n" + empty(rsrq));
        rsrqView.setTextColor(rsrqColor(num(rsrq)));

        rssiView.setText("RSSI\n" + empty(rssi));

        bandView.setText("Band\n" + (band.length() > 0 ? "B" + band : "--"));

        cellView.setText(
                "PCI: " + empty(pci) +
                "\nEARFCN: " + empty(earfcn) +
                "\nCell ID: " + empty(cell) +
                "\neNodeB: " + empty(enodeb)
        );

        if (sinrNum > -900) {
            if (sinrNum > bestSinr) {
                bestSinr = sinrNum;
                bestView.setText("Best: " + bestSinr + " dB");
                vibrate();
            }
            beepForSinr(sinrNum);
        }
    }

    void beepForSinr(double sinr) {
        if (!beepSwitch.isChecked()) return;

        int duration = 40;

        if (sinr >= 20) duration = 130;
        else if (sinr >= 13) duration = 90;
        else if (sinr >= 5) duration = 60;

        tone.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
    }

    void vibrate() {
        try {
            if (vibrator == null) return;

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(80);
            }
        } catch(Exception ignored) {}
    }

    String pick(String xml, String... tags) {
        for (String tag: tags) {
            Matcher m = Pattern
                    .compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(xml);

            if (m.find()) return m.group(1).trim();
        }
        return "";
    }

    double num(String s) {
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(s == null ? "" : s);

        if (m.find()) {
            try {
                return Double.parseDouble(m.group());
            } catch(Exception ignored) {}
        }

        return -999;
    }

    String empty(String s) {
        return s == null || s.length() == 0 ? "--" : s;
    }

    byte[] sha256Bytes(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(input.getBytes("UTF-8"));
    }

    String sha256Hex(String input) throws Exception {
        byte[] out = sha256Bytes(input);
        StringBuilder sb = new StringBuilder();

        for (byte b: out) {
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }

    String b64HexSha256(String input) throws Exception {
        String hex = sha256Hex(input);
        return Base64.encodeToString(hex.getBytes("UTF-8"), Base64.NO_WRAP);
    }

    String base64Bytes(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    int sinrColor(double v) {
        if (v >= 20) return Color.rgb(60,255,120);
        if (v >= 13) return Color.rgb(200,255,90);
        if (v >= 5) return Color.rgb(255,185,70);
        return Color.rgb(255,90,90);
    }

    int rsrpColor(double v) {
        if (v >= -85) return Color.rgb(60,255,120);
        if (v >= -95) return Color.rgb(200,255,90);
        if (v >= -105) return Color.rgb(255,185,70);
        return Color.rgb(255,90,90);
    }

    int rsrqColor(double v) {
        if (v >= -10) return Color.rgb(60,255,120);
        if (v >= -13) return Color.rgb(200,255,90);
        if (v >= -15) return Color.rgb(255,185,70);
        return Color.rgb(255,90,90);
    }

    void trustAllHttps() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());

            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        } catch(Exception ignored) {}
    }

    static class HttpResult {
        int code;
        String body;
        Map<String, List<String>> headers;

        HttpResult(int c, String b, Map<String, List<String>> h) {
            code = c;
            body = b;
            headers = h;
        }
    }
                   }
