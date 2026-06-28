package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.*;
import android.webkit.*;
import android.annotation.SuppressLint;
import android.media.*;
import android.content.*;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.*;

public class MainActivity extends Activity {
    WebView web;
    Handler handler = new Handler(Looper.getMainLooper());
    boolean running = false;
    ToneGenerator tone;
    Vibrator vibrator;
    String routerBase = "https://hirouter.net";
    String routerPass = "";
    String sessionCookie = "";
    String requestToken = "";
    double bestSinr = -999;

    Runnable poller = new Runnable() {
        public void run() {
            if (!running) return;
            readSignal();
            handler.postDelayed(this, 1000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        trustAllHttps();

        tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        vibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(), "SignalScout");
        setContentView(web);
        web.loadDataWithBaseURL("file:///android_res/drawable/", html(), "text/html", "UTF-8", null);
    }

    public class Bridge {
        @JavascriptInterface public void setRouter(String url, String pass) {
            routerBase = url == null || url.trim().length() == 0 ? "https://hirouter.net" : url.trim();
            if (routerBase.endsWith("/")) routerBase = routerBase.substring(0, routerBase.length() - 1);
            routerPass = pass == null ? "" : pass;
        }
        @JavascriptInterface public void login() { MainActivity.this.login(); }
        @JavascriptInterface public void startLive() {
            running = true;
            bestSinr = -999;
            js("setStatus('Running live signal finder...');");
            handler.removeCallbacks(poller);
            handler.post(poller);
        }
        @JavascriptInterface public void stopLive() {
            running = false;
            handler.removeCallbacks(poller);
            js("setStatus('Stopped');");
        }
        @JavascriptInterface public void testRead() { readSignal(); }
    }

    void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    String esc(String x) {
        if (x == null) return "";
        return x.replace("\\", "\\\\").replace("`", "\\`").replace("\n", "\\n").replace("\r", "");
    }

    void login() {
        js("setStatus('Logging in...');");
        new Thread(() -> {
            String debug = "";
            try {
                String sesTok = httpGet("/api/webserver/SesTokInfo");
                sessionCookie = pick(sesTok, "SesInfo");
                requestToken = pick(sesTok, "TokInfo");

                debug += "SesTokInfo OK\\nToken length: " + requestToken.length() + "\\nCookie length: " + sessionCookie.length() + "\\n\\n";

                String pass1 = b64HexSha256(routerPass);
                String finalPass = b64HexSha256("admin" + pass1 + requestToken);
                String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><Username>admin</Username><Password>" + finalPass + "</Password><password_type>4</password_type></request>";

                HttpResult res = httpPost("/api/user/login", body, requestToken);
                String newTok = headerToken(res);
                if (newTok.length() > 0) requestToken = newTok;

                debug += "LOGIN TRY b64hex/type4\\nHTTP " + res.code + "\\n" + res.body + "\\n";

                boolean ok = debug.contains("<response>OK</response>");
                js("setRaw(`" + esc(debug) + "`); setStatus('" + (ok ? "Logged in OK - press Start Live" : "Login not OK - see raw reply") + "');");
            } catch(Exception e) {
                debug += "\\nEXCEPTION:\\n" + e.toString();
                js("setRaw(`" + esc(debug) + "`); setStatus('Login failed');");
            }
        }).start();
    }

    void readSignal() {
        js("setStatus('Reading router signal...');");
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
                double rsrqNum = num(rsrq);
                double rsrpNum = num(rsrp);
                int quality = qualityScore(sinrNum, rsrqNum, rsrpNum);

                if (sinrNum > -900) {
                    if (sinrNum > bestSinr) {
                        bestSinr = sinrNum;
                        vibrate();
                    }
                    int duration = sinrNum >= 20 ? 130 : sinrNum >= 13 ? 90 : sinrNum >= 5 ? 60 : 40;
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, duration);
                }

                String status = sinr.length() == 0 ? (xml.toLowerCase().contains("<error>") ? "Router returned error XML" : "Connected but no SINR found") : "Updated OK";
                js("updateLive({status:`" + esc(status) + "`, sinr:`" + esc(empty(sinr)) + "`, rsrp:`" + esc(empty(rsrp)) + "`, rsrq:`" + esc(empty(rsrq)) + "`, rssi:`" + esc(empty(rssi)) + "`, band:`" + esc(band.length() > 0 ? "B" + band : "--") + "`, pci:`" + esc(empty(pci)) + "`, earfcn:`" + esc(empty(earfcn)) + "`, cell:`" + esc(empty(cell)) + "`, enodeb:`" + esc(empty(enodeb)) + "`, quality:`" + (quality < 0 ? "--" : quality) + "`, best:`" + (bestSinr > -900 ? bestSinr + " dB" : "--") + "`, raw:`" + esc(xml) + "`});");
            } catch(Exception e) {
                js("setStatus('Read failed'); setRaw(`" + esc(e.toString()) + "`);");
            }
        }).start();
    }

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

    String empty(String s) { return s == null || s.length() == 0 ? "--" : s; }

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
            Vibrator v = vibrator;
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(80);
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

    String html() {
        return """
<!DOCTYPE html>
<html>
<head>
<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>
<style>
:root{--bg:#030f1b;--card:#081d31;--card2:#0b2a46;--blue:#00b4ff;--green:#63ff63;--yellow:#d7ff4f;--amber:#ffbd38;--red:#ff5252;--text:#fff;--muted:#92a7ba}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:var(--bg);font-family:Arial,Helvetica,sans-serif;color:var(--text);overflow:hidden}
#app{height:100vh;display:flex;flex-direction:column}
#content{flex:1;overflow:auto;padding:10px;background:radial-gradient(circle at top,#06233d 0,#030f1b 45%,#020811 100%)}
.screen{display:none}.screen.active{display:block}
.mock{width:100%;border-radius:18px;box-shadow:0 0 22px rgba(0,180,255,.22);display:block;background:#020b14}
.mock.full{border-radius:0;box-shadow:none}
.heroBtn{width:76%;margin:-72px auto 18px auto;display:block;border:none;border-radius:22px;padding:15px 18px;background:linear-gradient(135deg,#008cff,#00b4ff);color:white;font-size:19px;font-weight:700;box-shadow:0 8px 22px rgba(0,140,255,.45);position:relative;z-index:2}
.title{text-align:center;font-size:22px;font-weight:800;margin:8px 0 2px}
.sub{text-align:center;color:var(--blue);font-size:14px;margin-bottom:12px}
.card{background:rgba(8,29,49,.92);border:1px solid rgba(0,180,255,.25);border-radius:18px;padding:15px;margin:10px 0;box-shadow:inset 0 0 18px rgba(0,180,255,.05)}
.card h3{margin:0 0 8px 0;font-size:18px}.card p{margin:0;color:#d8ecff;line-height:1.4}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.metric{background:rgba(8,29,49,.92);border:1px solid rgba(0,180,255,.25);border-radius:18px;padding:17px;text-align:center}
.metric .label{color:#dcefff;font-size:16px}.metric .value{font-size:30px;color:var(--green);font-weight:800;margin-top:6px}.metric .small{font-size:12px;color:#8edfff}
input{width:100%;background:#061a2b;border:1px solid rgba(0,180,255,.22);border-radius:12px;color:#fff;padding:14px;margin:6px 0;font-size:16px;outline:none}
.btn{width:100%;border:none;border-radius:14px;background:linear-gradient(135deg,#007ad9,#00a6ff);color:#fff;font-weight:800;padding:14px;margin:7px 0;font-size:15px;box-shadow:0 6px 16px rgba(0,140,255,.28)}
.btn.dark{background:#284762}.btn.red{background:#d92d2d}
.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}
.status{text-align:center;color:#c5d8e8;margin:8px}
.quality{font-size:44px;text-align:center;color:var(--green);font-weight:900;margin:10px 0}
.sinr{font-size:82px;text-align:center;color:var(--red);font-weight:900;margin:2px 0 6px}
.raw{font-family:monospace;color:#8ea4b5;font-size:11px;white-space:pre-wrap;max-height:170px;overflow:auto}
.progress{height:12px;background:#183753;border-radius:50px;overflow:hidden;margin:12px 0}.bar{height:100%;width:75%;background:linear-gradient(90deg,#62ff59,#00b4ff)}
.nav{height:62px;background:rgba(2,10,19,.97);display:grid;grid-template-columns:repeat(5,1fr);border-top:1px solid rgba(0,180,255,.12)}
.nav button{background:none;border:none;color:#7d91a7;font-size:12px}.nav button.active{color:var(--blue);font-weight:800}.nav span{display:block;font-size:21px;margin-bottom:1px}
</style>
</head>
<body>
<div id='app'>
<div id='content'>

<section id='home' class='screen active'>
<img class='mock full' src='file:///android_res/drawable/screen_home.png'>
<button class='heroBtn' onclick='show("live")'>Tap to Begin ›</button>
<div class='card'><h3>What are you trying to achieve today?</h3><p>🛰️ New Antenna Installation<br>📶 Improve Existing Installation<br>🚀 Find the Fastest Connection<br>🔍 Diagnose Poor Signal<br>⚙️ Advanced / Professional Mode</p></div>
<button class='btn' onclick='show("live")'>Live Signal Finder</button>
<button class='btn' onclick='show("scan")'>Band Scanner</button>
</section>

<section id='live' class='screen'>
<img class='mock' src='file:///android_res/drawable/screen_dashboard.png'>
<div class='title'>Router Controls</div><div class='sub'>Live router signal and antenna alignment</div>
<input id='url' value='https://hirouter.net'>
<input id='pass' type='password' placeholder='Router admin password'>
<button class='btn' onclick='saveRouter();SignalScout.login()'>Login to Router</button>
<div class='row'><button class='btn dark' onclick='saveRouter();SignalScout.startLive()'>Start</button><button class='btn dark' onclick='SignalScout.stopLive()'>Stop</button><button class='btn dark' onclick='resetBest()'>Reset</button></div>
<button class='btn' onclick='saveRouter();SignalScout.testRead()'>Test Read Once</button>
<div id='status' class='status'>Not logged in</div>
<div id='quality' class='quality'>Quality -- / 100</div>
<div id='sinr' class='sinr'>-- dB</div>
<div id='best' class='status'>Best --</div>
<div class='grid'>
<div class='metric'><div class='label'>RSRP</div><div id='rsrp' class='value'>--</div><div class='small'>Signal power</div></div>
<div class='metric'><div class='label'>RSRQ</div><div id='rsrq' class='value'>--</div><div class='small'>Signal quality</div></div>
<div class='metric'><div class='label'>RSSI</div><div id='rssi' class='value'>--</div><div class='small'>Received strength</div></div>
<div class='metric'><div class='label'>Band</div><div id='band' class='value'>--</div><div class='small'>Current LTE band</div></div>
</div>
<div class='card'><h3>Connected Cell</h3><p id='cellinfo'>PCI: --<br>EARFCN: --<br>Cell ID: --<br>eNodeB: --</p></div>
<div class='card'><h3>Raw Reply</h3><div id='raw' class='raw'>Raw reply will show here.</div></div>
</section>

<section id='scan' class='screen'>
<img class='mock' src='file:///android_res/drawable/screen_scan.png'>
<div class='card'><h3>Band Scanner</h3><p>Scanning all available bands for the best signal and combination.</p><div class='progress'><div class='bar'></div></div><p>Smart Scan will test B1, B3, B7, B20, B28 and common carrier aggregation pairs.</p></div>
<button class='btn' onclick='alert("Band Scanner comes after router login is finished")'>Start Smart Scan</button>
</section>

<section id='tower' class='screen'>
<img class='mock' src='file:///android_res/drawable/screen_tower.png'>
<div class='card'><h3>Tower Direction</h3><p>Camera and compass tower finder coming soon. This will use PCI, eNodeB, EARFCN and best SINR direction.</p></div>
</section>

<section id='reports' class='screen'>
<img class='mock' src='file:///android_res/drawable/screen_reports.png'>
<div class='card'><h3>Installer Reports</h3><p>PDF reports will include router model, firmware, final bands, quality score, tower info and speed test results.</p></div>
<button class='btn'>Export PDF Coming Soon</button>
</section>

<section id='more' class='screen'>
<img class='mock' src='file:///android_res/drawable/screen_scout.png'>
<div class='card'><h3>Signal Scout v1.1.0 beta</h3><p>Premium WebView UI build using Scout artwork and mockup-style screens.</p></div>
<div class='card'><h3>Signal Quality Guide</h3><p>Excellent: 90-100<br>Good: 70-89<br>Needs Improvement: 40-69<br>Poor: 20-39<br>No Signal: 0-19</p></div>
</section>

</div>
<nav class='nav'>
<button id='nav-home' class='active' onclick='show("home")'><span>⌂</span>Home</button>
<button id='nav-live' onclick='show("live")'><span>📶</span>Live</button>
<button id='nav-scan' onclick='show("scan")'><span>🔍</span>Scan</button>
<button id='nav-tower' onclick='show("tower")'><span>🧭</span>Tower</button>
<button id='nav-reports' onclick='show("reports")'><span>📄</span>Reports</button>
</nav>
</div>
<script>
let best='--';
function show(id){
 document.querySelectorAll('.screen').forEach(s=>s.classList.remove('active'));
 document.getElementById(id).classList.add('active');
 document.querySelectorAll('.nav button').forEach(b=>b.classList.remove('active'));
 let n=document.getElementById('nav-'+id); if(n)n.classList.add('active');
 document.getElementById('content').scrollTop=0;
}
function saveRouter(){ SignalScout.setRouter(document.getElementById('url').value,document.getElementById('pass').value); }
function setStatus(s){ document.getElementById('status').innerText=s; }
function setRaw(r){ document.getElementById('raw').innerText=r; }
function resetBest(){ best='--'; document.getElementById('best').innerText='Best --'; }
function updateLive(d){
 setStatus(d.status);
 document.getElementById('sinr').innerText=d.sinr;
 document.getElementById('quality').innerText='Quality '+d.quality+' / 100';
 document.getElementById('rsrp').innerText=d.rsrp;
 document.getElementById('rsrq').innerText=d.rsrq;
 document.getElementById('rssi').innerText=d.rssi;
 document.getElementById('band').innerText=d.band;
 document.getElementById('best').innerText='Best '+d.best;
 document.getElementById('cellinfo').innerHTML='PCI: '+d.pci+'<br>EARFCN: '+d.earfcn+'<br>Cell ID: '+d.cell+'<br>eNodeB: '+d.enodeb;
 setRaw(d.raw);
}
</script>
</body>
</html>
""";
    }
}
