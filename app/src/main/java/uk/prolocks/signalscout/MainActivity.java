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
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
            js("setStatus('Running live data'); closeRouter();");
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

    void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

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
                js("setRaw(`" + esc(debug) + "`); setStatus('" + (ok ? "Logged in OK" : "Login not OK - see raw") + "');");
            } catch(Exception e) {
                debug += "\\nEXCEPTION:\\n" + e.toString();
                js("setRaw(`" + esc(debug) + "`); setStatus('Login failed');");
            }
        }).start();
    }

    void readSignal() {
        js("setStatus('Reading router...');");
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

                String status = sinr.length() == 0 ? (xml.toLowerCase().contains("<error>") ? "Router returned error XML" : "Connected but no SINR found") : "Live data";
                js("updateLive({status:`" + esc(status) + "`,sinr:`" + esc(clean(sinr)) + "`,rsrp:`" + esc(clean(rsrp)) + "`,rsrq:`" + esc(clean(rsrq)) + "`,rssi:`" + esc(clean(rssi)) + "`,band:`" + esc(band.length()>0 ? "B"+band : "--") + "`,pci:`" + esc(clean(pci)) + "`,earfcn:`" + esc(clean(earfcn)) + "`,cell:`" + esc(clean(cell)) + "`,enodeb:`" + esc(clean(enodeb)) + "`,quality:`" + (quality < 0 ? "--" : quality) + "`,best:`" + (bestSinr > -900 ? bestSinr + " dB" : "--") + "`,raw:`" + esc(xml) + "`});");
            } catch(Exception e) {
                js("setStatus('Read failed'); setRaw(`" + esc(e.toString()) + "`);");
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

    String html() {
        return """
<!DOCTYPE html>
<html>
<head>
<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#000;color:white;font-family:Arial,Helvetica,sans-serif;overflow:hidden}
#app{height:100vh;width:100vw;background:#000;position:relative;overflow:hidden}
.screen{position:absolute;inset:0;display:none;background:#000}.screen.active{display:block}
.bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:center top}
.homeBtn{position:absolute;left:7.5%;right:7.5%;height:8.2%;border:none;border-radius:17px;background:transparent;color:transparent}
#beginBtn{bottom:18.8%}
#setupBtn{bottom:8.4%}
.dashOverlay{position:absolute;inset:0}
.liveText{position:absolute;font-weight:900;color:#65ff49;text-shadow:0 0 12px rgba(101,255,73,.28);text-align:center}
#ovQuality{right:10%;top:10%;font-size:34px}
#rsrp{left:22%;top:30.5%;font-size:37px}
#sinr{right:23%;top:30.5%;font-size:37px}
#rsrq{left:22%;top:45.8%;font-size:37px;color:#ffcc30}
#rssi{right:23%;top:45.8%;font-size:37px}
#band{left:19%;top:60.8%;font-size:27px}
#quality{right:18%;top:61%;font-size:30px}
#pci{left:13%;top:76.2%;font-size:14px;color:white}
#earfcn{left:33%;top:76.2%;font-size:14px;color:white}
#enodeb{left:56%;top:76.2%;font-size:14px;color:white}
#cellid{right:14%;top:76.2%;font-size:14px;color:white}
#status{left:12%;bottom:2.2%;font-size:12px;color:white;text-align:left}
#updated{right:8%;bottom:2.2%;font-size:12px;color:white;text-align:right}
#menuBtn{position:absolute;left:3%;top:2%;width:14%;height:9%;background:transparent;border:none;color:transparent}
#routerBtn{position:absolute;right:2%;top:2%;width:14%;height:9%;background:transparent;border:none;color:transparent}
.drawer{position:absolute;top:0;bottom:0;left:-78%;width:78%;z-index:10;transition:.25s;background:#020910;box-shadow:18px 0 40px rgba(0,0,0,.6)}
.drawer.open{left:0}.drawer img{width:100%;height:100%;object-fit:cover}
.scrim{position:absolute;inset:0;background:rgba(0,0,0,.55);z-index:9;display:none}.scrim.open{display:block}
.router{position:absolute;left:0;right:0;bottom:-100%;background:#071d28;border-radius:24px 24px 0 0;border:1px solid rgba(101,255,73,.28);padding:18px;z-index:20;transition:.25s}
.router.open{bottom:0}.router h2{text-align:center;margin:0 0 12px}.router input{width:100%;background:#06131d;border:1px solid rgba(101,255,73,.24);border-radius:12px;padding:13px;color:white;margin:6px 0;font-size:16px}.btn{width:100%;height:46px;border:none;border-radius:12px;background:linear-gradient(135deg,#0a84ff,#00b8ff);color:white;font-weight:800;font-size:15px;margin-top:8px}.btn.dark{background:#1c3440}.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}.raw{font-family:monospace;color:#a7b9bf;font-size:10px;white-space:pre-wrap;max-height:105px;overflow:auto;margin-top:8px}
.placeholder{padding:30px;background:#020b13;height:100%;color:white}.placeholder h1{margin-top:70px}
</style>
</head>
<body>
<div id='app'>
<div id='scrim' class='scrim' onclick='closeAll()'></div>

<section id='home' class='screen active'>
<img class='bg' src='home_bg.png'>
<button id='beginBtn' class='homeBtn' onclick='show("dashboard")'>Begin Survey</button>
<button id='setupBtn' class='homeBtn' onclick='openRouter()'>First Time Setup</button>
</section>

<section id='dashboard' class='screen'>
<img class='bg' src='dashboard_bg.png'>
<div class='dashOverlay'>
<button id='menuBtn' onclick='openMenu()'>menu</button>
<button id='routerBtn' onclick='openRouter()'>router</button>
<div id='ovQuality' class='liveText'>--</div>
<div id='rsrp' class='liveText'>--</div>
<div id='sinr' class='liveText'>--</div>
<div id='rsrq' class='liveText'>--</div>
<div id='rssi' class='liveText'>--</div>
<div id='band' class='liveText'>--</div>
<div id='quality' class='liveText'>--</div>
<div id='pci' class='liveText'>--</div>
<div id='earfcn' class='liveText'>--</div>
<div id='enodeb' class='liveText'>--</div>
<div id='cellid' class='liveText'>--</div>
<div id='status' class='liveText'>Not logged in</div>
<div id='updated' class='liveText'>Updated --</div>
</div>
</section>

<section id='scan' class='screen'><div class='placeholder'><h1>Smart Band Scanner</h1><p>Next screen to wire in.</p></div></section>
<section id='tower' class='screen'><div class='placeholder'><h1>Tower Finder</h1><p>Next screen to wire in.</p></div></section>
<section id='reports' class='screen'><div class='placeholder'><h1>Reports</h1><p>Next screen to wire in.</p></div></section>

<div id='drawer' class='drawer'><img src='menu_bg.png'></div>

<div id='router' class='router'>
<h2>Router Controls</h2>
<input id='url' value='https://hirouter.net'>
<input id='pass' type='password' placeholder='Router admin password'>
<button class='btn' onclick='saveRouter();SignalScout.login()'>Login to Router</button>
<div class='row'>
<button class='btn dark' onclick='saveRouter();SignalScout.startLive()'>Start</button>
<button class='btn dark' onclick='SignalScout.stopLive()'>Stop</button>
<button class='btn dark' onclick='setStatus("Best reset")'>Reset</button>
</div>
<button class='btn' onclick='saveRouter();SignalScout.testRead()'>Test Read Once</button>
<button class='btn dark' onclick='closeAll()'>Close</button>
<div id='raw' class='raw'>Raw reply will show here.</div>
</div>

</div>
<script>
function show(id){document.querySelectorAll('.screen').forEach(s=>s.classList.remove('active'));document.getElementById(id).classList.add('active');closeAll()}
function openMenu(){document.getElementById('drawer').classList.add('open');document.getElementById('scrim').classList.add('open')}
function openRouter(){document.getElementById('router').classList.add('open');document.getElementById('scrim').classList.add('open')}
function closeAll(){document.getElementById('drawer').classList.remove('open');document.getElementById('router').classList.remove('open');document.getElementById('scrim').classList.remove('open')}
function saveRouter(){SignalScout.setRouter(document.getElementById('url').value,document.getElementById('pass').value)}
function setStatus(s){document.getElementById('status').innerText=s}
function setRaw(r){document.getElementById('raw').innerText=r}
function updateLive(d){
 setStatus(d.status);
 setRaw(d.raw);
 document.getElementById('ovQuality').innerText=d.quality;
 document.getElementById('quality').innerText=d.quality;
 document.getElementById('rsrp').innerText=numOnly(d.rsrp);
 document.getElementById('sinr').innerText=numOnly(d.sinr);
 document.getElementById('rsrq').innerText=numOnly(d.rsrq);
 document.getElementById('rssi').innerText=numOnly(d.rssi);
 document.getElementById('band').innerText=d.band;
 document.getElementById('pci').innerText=d.pci;
 document.getElementById('earfcn').innerText=d.earfcn;
 document.getElementById('enodeb').innerText=d.enodeb;
 document.getElementById('cellid').innerText=d.cell;
 document.getElementById('updated').innerText='Updated now';
}
function numOnly(v){return (v||'--').replace('dBm','').replace('dB','').trim()}
</script>
</body>
</html>
""";
    }
}
