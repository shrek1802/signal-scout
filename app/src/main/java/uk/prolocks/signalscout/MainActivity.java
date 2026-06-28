package uk.prolocks.signalscout;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.text.InputType;
import android.util.Base64;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.*;

public class MainActivity extends Activity {
    SignalView view;
    Handler handler = new Handler(Looper.getMainLooper());
    boolean running = false;
    String routerBase = "https://hirouter.net";
    String routerPass = "";
    String sessionCookie = "";
    String requestToken = "";
    double bestSinr = -999;
    ToneGenerator tone;
    Vibrator vibrator;

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
        view = new SignalView(this);
        setContentView(view);
    }

    void showRouterPanel() {
        final Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setBackgroundColor(Color.rgb(7, 29, 49));

        TextView title = new TextView(this);
        title.setText("Router Controls");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(null, 1);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        EditText url = input("Router URL", routerBase, false);
        EditText pass = input("Router admin password", routerPass, true);
        box.addView(url);
        box.addView(pass);

        Button login = button("Login to Router");
        Button start = button("Start Live");
        Button stop = button("Stop");
        Button test = button("Test Read Once");
        Button close = button("Close");

        box.addView(login);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row);
        box.addView(test);
        box.addView(close);

        TextView raw = new TextView(this);
        raw.setText(view.raw);
        raw.setTextColor(Color.rgb(140, 165, 185));
        raw.setTextSize(10);
        raw.setPadding(0, dp(8), 0, 0);
        box.addView(raw);

        login.setOnClickListener(v -> {
            hideKeyboard(pass);
            routerBase = url.getText().toString().trim();
            if (routerBase.endsWith("/")) routerBase = routerBase.substring(0, routerBase.length() - 1);
            routerPass = pass.getText().toString();
            login();
        });

        start.setOnClickListener(v -> {
            hideKeyboard(pass);
            routerBase = url.getText().toString().trim();
            if (routerBase.endsWith("/")) routerBase = routerBase.substring(0, routerBase.length() - 1);
            routerPass = pass.getText().toString();
            startLive();
            d.dismiss();
        });

        stop.setOnClickListener(v -> stopLive());
        test.setOnClickListener(v -> {
            routerBase = url.getText().toString().trim();
            if (routerBase.endsWith("/")) routerBase = routerBase.substring(0, routerBase.length() - 1);
            routerPass = pass.getText().toString();
            readSignal();
        });
        close.setOnClickListener(v -> d.dismiss());

        d.setContentView(box);
        Window w = d.getWindow();
        d.show();
        Window win = d.getWindow();
        if (win != null) {
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
    }

    EditText input(String hint, String text, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(text);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(150, 170, 185));
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(Color.rgb(6, 24, 42));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setBackgroundColor(Color.rgb(0, 120, 205));
        return b;
    }

    void hideKeyboard(View v) {
        try {
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch(Exception ignored) {}
    }

    int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    void startLive() {
        running = true;
        bestSinr = -999;
        view.status = "Running live signal finder...";
        view.invalidate();
        handler.removeCallbacks(poller);
        handler.post(poller);
    }

    void stopLive() {
        running = false;
        handler.removeCallbacks(poller);
        view.status = "Stopped";
        view.invalidate();
    }

    void login() {
        view.status = "Logging in...";
        view.invalidate();

        new Thread(() -> {
            String debug = "";
            try {
                String sesTok = httpGet("/api/webserver/SesTokInfo");
                sessionCookie = pick(sesTok, "SesInfo");
                requestToken = pick(sesTok, "TokInfo");

                debug += "SesTokInfo OK\nToken length: " + requestToken.length() + "\nCookie length: " + sessionCookie.length() + "\n\n";

                String pass1 = b64HexSha256(routerPass);
                String finalPass = b64HexSha256("admin" + pass1 + requestToken);
                String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><Username>admin</Username><Password>" + finalPass + "</Password><password_type>4</password_type></request>";

                HttpResult res = httpPost("/api/user/login", body, requestToken);
                String newTok = headerToken(res);
                if (newTok.length() > 0) requestToken = newTok;

                debug += "LOGIN TRY b64hex/type4\nHTTP " + res.code + "\n" + res.body + "\n\n";

                boolean ok = debug.contains("<response>OK</response>");
                final String fDebug = debug;
                runOnUiThread(() -> {
                    view.raw = fDebug;
                    view.status = ok ? "Logged in OK - press Start" : "Login not OK - see raw";
                    view.invalidate();
                });
            } catch(Exception e) {
                final String fDebug = debug + "\nEXCEPTION:\n" + e.toString();
                runOnUiThread(() -> {
                    view.raw = fDebug;
                    view.status = "Login failed";
                    view.invalidate();
                });
            }
        }).start();
    }

    void readSignal() {
        view.status = "Reading router signal...";
        view.invalidate();

        new Thread(() -> {
            try {
                String xml = httpGet("/api/device/signal");

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

                if (sinrNum > -900) {
                    if (sinrNum > bestSinr) {
                        bestSinr = sinrNum;
                        vibrate();
                    }
                    int duration = sinrNum >= 20 ? 130 : sinrNum >= 13 ? 90 : sinrNum >= 5 ? 60 : 40;
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
                }

                String status = sinr.length() == 0 ? (xml.toLowerCase().contains("<error>") ? "Router returned error XML" : "Connected but no SINR found") : "Updated OK";

                runOnUiThread(() -> {
                    view.status = status;
                    view.raw = xml;
                    view.sinr = clean(sinr);
                    view.rsrp = clean(rsrp);
                    view.rsrq = clean(rsrq);
                    view.rssi = clean(rssi);
                    view.band = band.length() > 0 ? "B" + band : "--";
                    view.pci = clean(pci);
                    view.earfcn = clean(earfcn);
                    view.cell = clean(cell);
                    view.enodeb = clean(enodeb);
                    view.quality = quality;
                    view.best = bestSinr > -900 ? bestSinr + " dB" : "--";
                    view.invalidate();
                });

            } catch(Exception e) {
                runOnUiThread(() -> {
                    view.status = "Read failed";
                    view.raw = e.toString();
                    view.invalidate();
                });
            }
        }).start();
    }

    String clean(String s) { return s == null || s.length() == 0 ? "--" : s; }

    String httpGet(String path) throws Exception {
        HttpURLConnection con = (HttpURLConnection)new URL(routerBase + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(7000);
        con.setRequestMethod("GET");
        con.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 SignalScout");
        con.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (sessionCookie.length() > 0) con.setRequestProperty("Cookie", sessionCookie);
        return readAll(con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream());
    }

    HttpResult httpPost(String path, String body, String tok) throws Exception {
        HttpURLConnection con = (HttpURLConnection)new URL(routerBase + path).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(7000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/xml");
        con.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 SignalScout");
        con.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        if (sessionCookie.length() > 0) con.setRequestProperty("Cookie", sessionCookie);
        if (tok.length() > 0) con.setRequestProperty("__RequestVerificationToken", tok);
        OutputStream os = con.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = con.getResponseCode();
        return new HttpResult(code, readAll(code >= 400 ? con.getErrorStream() : con.getInputStream()), con.getHeaderFields());
    }

    String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while((line = br.readLine()) != null) sb.append(line).append("\n");
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

    String pick(String xml, String... tags) {
        for (String tag : tags) {
            Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml);
            if (m.find()) return m.group(1).trim();
        }
        return "";
    }

    double num(String s) {
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(s == null ? "" : s);
        if (m.find()) try { return Double.parseDouble(m.group()); } catch(Exception ignored) {}
        return -999;
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

    void vibrate() {
        try {
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(80);
        } catch(Exception ignored) {}
    }

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
        } catch(Exception ignored) {}
    }

    static class HttpResult {
        int code;
        String body;
        Map<String, List<String>> headers;
        HttpResult(int c, String b, Map<String, List<String>> h) { code = c; body = b; headers = h; }
    }

    class SignalView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF();
        int screen = 0;

        String status = "Not logged in";
        String raw = "Raw reply will show here.";
        String sinr = "--";
        String rsrp = "--";
        String rsrq = "--";
        String rssi = "--";
        String band = "--";
        String pci = "--";
        String earfcn = "--";
        String cell = "--";
        String enodeb = "--";
        String best = "--";
        int quality = -1;

        int bg = Color.rgb(3, 15, 27);
        int card = Color.rgb(7, 29, 49);
        int card2 = Color.rgb(9, 38, 64);
        int blue = Color.rgb(0, 180, 255);
        int green = Color.rgb(98, 255, 72);
        int amber = Color.rgb(255, 189, 56);
        int red = Color.rgb(255, 82, 82);
        int muted = Color.rgb(145, 167, 186);

        SignalView(Context c) { super(c); }

        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0,0,0,h, Color.rgb(8,42,70), bg, Shader.TileMode.CLAMP));
            c.drawRect(0,0,w,h,p);
            p.setShader(null);

            if (screen == 0) drawHome(c,w,h);
            if (screen == 1) drawDashboard(c,w,h);
            if (screen == 2) drawScan(c,w,h);
            if (screen == 3) drawTower(c,w,h);
            if (screen == 4) drawReports(c,w,h);

            drawNav(c,w,h);
        }

        public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float x = e.getX(), y = e.getY();
            float w = getWidth(), h = getHeight();
            if (y > h - dp(72)) {
                int idx = (int)(x / (w / 5f));
                screen = idx;
                invalidate();
                return true;
            }

            if (screen == 0) {
                screen = 1;
                invalidate();
                return true;
            }

            if (screen == 1 && y < dp(85) && x > w - dp(90)) {
                showRouterPanel();
                return true;
            }

            return true;
        }

        void drawHome(Canvas c, float w, float h) {
            text(c,"Signal Scout", w/2, dp(55), 26, Color.WHITE, true, Paint.Align.CENTER);
            card(c, dp(22), dp(100), w-dp(22), h-dp(95), dp(24));

            text(c,"Signal", dp(70), dp(210), 54, Color.WHITE, true, Paint.Align.LEFT);
            text(c,"Scout", dp(70), dp(285), 54, blue, true, Paint.Align.LEFT);
            text(c,"Professional LTE & 5G", dp(70), dp(365), 21, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"Installation Assistant", dp(70), dp(398), 21, Color.WHITE, false, Paint.Align.LEFT);

            drawScout(c, w/2, dp(620), 1.65f);

            rounded(c, dp(90), h-dp(210), w-dp(90), h-dp(150), dp(24), blue, 0);
            text(c,"Tap to Begin ›", w/2, h-dp(170), 22, Color.WHITE, true, Paint.Align.CENTER);
        }

        void drawDashboard(Canvas c, float w, float h) {
            text(c,"☰", dp(40), dp(52), 30, Color.WHITE, false, Paint.Align.CENTER);
            text(c,"Dashboard", w/2, dp(52), 26, Color.WHITE, true, Paint.Align.CENTER);
            text(c,"⋮", w-dp(35), dp(52), 30, Color.WHITE, false, Paint.Align.CENTER);

            card(c, dp(20), dp(90), w-dp(20), dp(260), dp(22));
            drawScout(c, dp(115), dp(185), .75f);
            text(c,"Overall Quality", dp(250), dp(145), 16, muted, false, Paint.Align.LEFT);
            String qText = quality < 0 ? "--" : String.valueOf(quality);
            text(c,qText + "/100", dp(250), dp(190), 40, green, true, Paint.Align.LEFT);
            text(c,qualityWord(), dp(250), dp(225), 22, qualityColor(), true, Paint.Align.LEFT);
            text(c,"📍 Location: NG12 3NH", dp(250), dp(250), 15, Color.rgb(210,225,240), false, Paint.Align.LEFT);
            gauge(c, w-dp(85), dp(155), dp(65), quality < 0 ? 0 : quality);

            float left = dp(20), top = dp(280), gap = dp(12);
            float cw = (w - dp(52)) / 2f;
            float ch = dp(170);
            metric(c, left, top, cw, ch, "RSRP", rsrp, "Excellent", 1);
            metric(c, left+cw+gap, top, cw, ch, "SINR", sinr, "Excellent", 2);
            metric(c, left, top+ch+gap, cw, ch, "RSRQ", rsrq, "Good", 3);
            metric(c, left+cw+gap, top+ch+gap, cw, ch, "RSSI", rssi, "Excellent", 4);

            card(c, dp(20), top+ch*2+gap*2, w-dp(20), top+ch*2+gap*2+dp(110), dp(20));
            text(c,"♜", dp(75), top+ch*2+gap*2+dp(68), 44, Color.WHITE, false, Paint.Align.CENTER);
            text(c,"Connected to", dp(135), top+ch*2+gap*2+dp(42), 17, Color.rgb(210,225,240), false, Paint.Align.LEFT);
            text(c,band, dp(135), top+ch*2+gap*2+dp(75), 25, Color.WHITE, true, Paint.Align.LEFT);
            text(c,bandFreq(), dp(135), top+ch*2+gap*2+dp(100), 16, Color.rgb(210,225,240), false, Paint.Align.LEFT);
            text(c,"4G+", w-dp(70), top+ch*2+gap*2+dp(58), 14, green, true, Paint.Align.CENTER);

            text(c,status + "  Best: " + best, w/2, h-dp(92), 13, Color.rgb(200,215,230), false, Paint.Align.CENTER);
        }

        void drawScan(Canvas c, float w, float h) {
            text(c,"Scan Bands", w/2, dp(52), 24, Color.WHITE, true, Paint.Align.CENTER);
            card(c, dp(20), dp(90), w-dp(20), dp(250), dp(22));
            drawScout(c, dp(110), dp(170), .72f);
            text(c,"Scanning available", dp(200), dp(135), 17, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"bands...", dp(200), dp(160), 17, blue, true, Paint.Align.LEFT);
            text(c,"This may take a few seconds.", dp(200), dp(195), 15, Color.rgb(210,225,240), false, Paint.Align.LEFT);

            card(c, dp(20), dp(275), w-dp(20), h-dp(95), dp(22));
            text(c,"Progress", dp(40), dp(320), 18, Color.WHITE, true, Paint.Align.LEFT);
            text(c,"75%", w-dp(55), dp(320), 18, Color.WHITE, true, Paint.Align.RIGHT);
            progress(c, dp(40), dp(340), w-dp(40), dp(352), .75f);
            bandRow(c, "B1", "2100 MHz", "-95 dBm", .35f, dp(390), false);
            bandRow(c, "B3", "1800 MHz", "-78 dBm", .86f, dp(435), true);
            bandRow(c, "B7", "2600 MHz", "-98 dBm", .30f, dp(480), false);
            bandRow(c, "B20", "800 MHz", "-82 dBm", .70f, dp(525), true);
            bandRow(c, "B28", "700 MHz", "-101 dBm", .25f, dp(570), false);
        }

        void drawTower(Canvas c, float w, float h) {
            text(c,"Tower Direction", w/2, dp(52), 24, Color.WHITE, true, Paint.Align.CENTER);
            card(c, dp(20), dp(90), w-dp(20), dp(235), dp(22));
            drawScout(c, dp(95), dp(162), .62f);
            text(c,"Turn this way", dp(185), dp(132), 18, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"for a stronger", dp(185), dp(160), 18, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"signal!", dp(185), dp(188), 18, green, true, Paint.Align.LEFT);

            float cx = w/2, cy = dp(420), rad = dp(150);
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(2)); stroke.setColor(Color.rgb(55,80,105));
            c.drawCircle(cx, cy, rad, stroke);
            c.drawCircle(cx, cy, rad*.70f, stroke);
            text(c,"N", cx, cy-rad+dp(35), 22, Color.WHITE, true, Paint.Align.CENTER);
            text(c,"S", cx, cy+rad-dp(20), 22, Color.WHITE, true, Paint.Align.CENTER);
            text(c,"W", cx-rad+dp(25), cy+dp(5), 22, Color.WHITE, true, Paint.Align.CENTER);
            text(c,"E", cx+rad-dp(25), cy+dp(5), 22, Color.WHITE, true, Paint.Align.CENTER);
            text(c,"↑", cx, cy-dp(5), 85, green, true, Paint.Align.CENTER);
            text(c,"23°", cx, cy+dp(75), 40, green, true, Paint.Align.CENTER);
            text(c,"NE", cx, cy+dp(112), 24, Color.WHITE, true, Paint.Align.CENTER);

            card(c, dp(20), dp(600), w-dp(20), dp(690), dp(18));
            text(c,"Best Signal This Direction", dp(40), dp(640), 17, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"-72 dBm", w-dp(40), dp(650), 28, green, true, Paint.Align.RIGHT);
        }

        void drawReports(Canvas c, float w, float h) {
            text(c,"Reports", w/2, dp(52), 24, Color.WHITE, true, Paint.Align.CENTER);
            card(c, dp(20), dp(90), w-dp(20), dp(235), dp(22));
            drawScout(c, dp(95), dp(162), .62f);
            text(c,"Your report is", dp(185), dp(138), 18, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"ready!", dp(185), dp(166), 18, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"Great work.", dp(185), dp(196), 18, green, true, Paint.Align.LEFT);

            card(c, dp(20), dp(270), w-dp(20), dp(405), dp(20));
            text(c,"▣", dp(70), dp(350), 55, blue, true, Paint.Align.CENTER);
            text(c,"Site Survey", dp(130), dp(315), 20, Color.WHITE, true, Paint.Align.LEFT);
            text(c,"12 Jun 2026 - 10:30", dp(130), dp(345), 15, Color.rgb(210,225,240), false, Paint.Align.LEFT);
            text(c,"NG12 3NH", dp(130), dp(372), 15, Color.rgb(210,225,240), false, Paint.Align.LEFT);
            text(c,"Excellent", dp(130), dp(397), 15, green, true, Paint.Align.LEFT);

            reportButton(c, "View Report", dp(440));
            reportButton(c, "Export PDF", dp(505));
            reportButton(c, "Share", dp(570));
        }

        void reportButton(Canvas c, String s, float y) {
            card(c, dp(20), y, getWidth()-dp(20), y+dp(54), dp(14));
            text(c,s, dp(60), y+dp(35), 17, Color.WHITE, false, Paint.Align.LEFT);
            text(c,"›", getWidth()-dp(45), y+dp(36), 25, Color.WHITE, true, Paint.Align.CENTER);
        }

        void metric(Canvas c, float x, float y, float w, float h, String label, String value, String sub, int wave) {
            card(c, x, y, x+w, y+h, dp(20));
            text(c,label, x+dp(24), y+dp(40), 22, Color.WHITE, true, Paint.Align.LEFT);
            text(c,value, x+dp(24), y+dp(86), 32, green, true, Paint.Align.LEFT);
            drawWave(c, x+dp(24), y+dp(105), w-dp(48), dp(34), wave);
            text(c,sub, x+dp(24), y+h-dp(25), 16, sub.equals("Good") ? amber : green, false, Paint.Align.LEFT);
        }

        void bandRow(Canvas c, String b, String mhz, String dbm, float fill, float y, boolean ok) {
            text(c,b, dp(40), y, 18, Color.WHITE, true, Paint.Align.LEFT);
            text(c,mhz, dp(95), y, 13, Color.rgb(210,225,240), false, Paint.Align.LEFT);
            progress(c, dp(185), y-dp(10), getWidth()-dp(115), y, fill);
            text(c,dbm, getWidth()-dp(40), y, 14, Color.WHITE, false, Paint.Align.RIGHT);
            if (ok) text(c,"✓", getWidth()-dp(20), y, 18, green, true, Paint.Align.CENTER);
        }

        void progress(Canvas c, float l, float t, float rr, float b, float fill) {
            rounded(c,l,t,rr,b,dp(6),Color.rgb(24,55,80),0);
            rounded(c,l,t,l+(rr-l)*fill,b,dp(6),green,0);
        }

        void gauge(Canvas c, float cx, float cy, float rad, int q) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(12));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.rgb(30,65,90));
            r.set(cx-rad, cy-rad, cx+rad, cy+rad);
            c.drawArc(r, -120, 240, false, stroke);
            stroke.setColor(qualityColor());
            c.drawArc(r, -120, q < 0 ? 0 : 240f * q / 100f, false, stroke);
        }

        void drawWave(Canvas c, float x, float y, float w, float h, int seed) {
            Path area = new Path();
            Path line = new Path();
            area.moveTo(x, y+h);
            line.moveTo(x, y+h*.55f);
            for (int i=0;i<=8;i++) {
                float px = x + w*i/8f;
                float py = y + h*(.55f - .25f*(float)Math.sin((i+seed)*.9));
                line.lineTo(px, py);
                area.lineTo(px, py);
            }
            area.lineTo(x+w, y+h);
            area.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(70, 98, 255, 72));
            c.drawPath(area, p);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(3));
            stroke.setColor(green);
            c.drawPath(line, stroke);
        }

        void drawScout(Canvas c, float cx, float cy, float scale) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(25,35,45));
            r.set(cx-dp(70)*scale, cy-dp(75)*scale, cx+dp(70)*scale, cy+dp(85)*scale);
            c.drawRoundRect(r, dp(18)*scale, dp(18)*scale, p);

            p.setColor(Color.rgb(230,235,238));
            c.drawCircle(cx, cy-dp(40)*scale, dp(47)*scale, p);

            p.setColor(Color.rgb(5,13,22));
            r.set(cx-dp(48)*scale, cy-dp(55)*scale, cx+dp(48)*scale, cy-dp(12)*scale);
            c.drawRoundRect(r, dp(18)*scale, dp(18)*scale, p);

            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(4)*scale);
            stroke.setColor(blue);
            r.set(cx-dp(30)*scale, cy-dp(42)*scale, cx-dp(14)*scale, cy-dp(24)*scale);
            c.drawArc(r, 180, 180, false, stroke);
            r.set(cx+dp(14)*scale, cy-dp(42)*scale, cx+dp(30)*scale, cy-dp(24)*scale);
            c.drawArc(r, 180, 180, false, stroke);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(190,255,0));
            r.set(cx-dp(45)*scale, cy+dp(10)*scale, cx+dp(45)*scale, cy+dp(78)*scale);
            c.drawRoundRect(r, dp(10)*scale, dp(10)*scale, p);

            p.setColor(blue);
            float bx = cx + dp(12)*scale, by = cy + dp(42)*scale;
            c.drawRoundRect(new RectF(bx, by+dp(18)*scale, bx+dp(7)*scale, by+dp(30)*scale), dp(3)*scale, dp(3)*scale, p);
            c.drawRoundRect(new RectF(bx+dp(12)*scale, by+dp(8)*scale, bx+dp(19)*scale, by+dp(30)*scale), dp(3)*scale, dp(3)*scale, p);
            c.drawRoundRect(new RectF(bx+dp(24)*scale, by-dp(5)*scale, bx+dp(31)*scale, by+dp(30)*scale), dp(3)*scale, dp(3)*scale, p);
        }

        void card(Canvas c, float l, float t, float rr, float b, float rad) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(card);
            r.set(l,t,rr,b);
            c.drawRoundRect(r, rad, rad, p);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(Color.rgb(18,80,120));
            c.drawRoundRect(r, rad, rad, stroke);
        }

        void rounded(Canvas c, float l, float t, float rr, float b, float rad, int color, int strokeColor) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            r.set(l,t,rr,b);
            c.drawRoundRect(r, rad, rad, p);
            if (strokeColor != 0) {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(dp(1));
                stroke.setColor(strokeColor);
                c.drawRoundRect(r, rad, rad, stroke);
            }
        }

        void text(Canvas c, String s, float x, float y, int sp, int color, boolean bold, Paint.Align align) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(dp(sp));
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextAlign(align);
            c.drawText(s, x, y, p);
        }

        void drawNav(Canvas c, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(2, 10, 19));
            c.drawRect(0, h-dp(72), w, h, p);
            String[] icons = {"⌂","▥","⌕","◴","▣"};
            String[] labels = {"Home","Live","Scan","Tower","Reports"};
            for (int i=0;i<5;i++) {
                float cx = w*(i+.5f)/5f;
                int col = screen == i ? blue : Color.rgb(90,110,130);
                text(c, icons[i], cx, h-dp(42), 24, col, true, Paint.Align.CENTER);
                text(c, labels[i], cx, h-dp(16), 12, col, screen == i, Paint.Align.CENTER);
            }
        }

        String qualityWord() {
            if (quality < 0) return "Waiting";
            if (quality >= 90) return "Excellent";
            if (quality >= 70) return "Good";
            if (quality >= 40) return "Needs Improvement";
            return "Poor";
        }

        int qualityColor() {
            if (quality >= 85) return green;
            if (quality >= 65) return Color.rgb(215,255,79);
            if (quality >= 40) return amber;
            return red;
        }

        String bandFreq() {
            if (band.equals("--")) return "--";
            if (band.contains("20")) return "800 MHz";
            if (band.contains("3")) return "1800 MHz";
            if (band.contains("7")) return "2600 MHz";
            if (band.contains("1")) return "2100 MHz";
            return "Current LTE band";
        }
    }
}
