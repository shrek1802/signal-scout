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
            js("setStatus('Running live signal finder...');closePanel();");
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
                js("setRaw(`" + esc(debug) + "`); setStatus('" + (ok ? "Logged in OK - press Start" : "Login not OK - see raw") + "');");
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
body{margin:0;background:#020b15;color:white;font-family:Arial,Helvetica,sans-serif;overflow:hidden}
#app{height:100vh;display:flex;flex-direction:column;background:#020b15}
#content{flex:1;overflow:hidden;position:relative}
.screen{display:none;position:absolute;inset:0;background:#020b15}.screen.active{display:block}
.bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:top center}
.tap{position:absolute;left:13%;right:13%;bottom:8.5%;height:7%;border-radius:24px;border:none;background:linear-gradient(135deg,#008cff,#00b4ff);color:white;font-size:20px;font-weight:800;box-shadow:0 7px 24px rgba(0,140,255,.5)}
.hot{position:absolute;background:rgba(0,0,0,.02)}
#dash .overlay{position:absolute;inset:0}
.mask{position:absolute;background:#071d31;border-radius:8px}
.val{position:absolute;color:#62ff48;font-weight:900;text-shadow:0 0 12px rgba(98,255,72,.25);line-height:1}
.statusText{position:absolute;color:#d4e9ff;font-size:13px}
#q{left:47%;top:15.3%;font-size:52px}
#qWord{left:48%;top:24.5%;font-size:27px;color:#62ff48}
#rsrp{left:8%;top:41.2%;font-size:36px}
#sinr{left:58%;top:41.2%;font-size:36px}
#rsrq{left:8%;top:63.3%;font-size:36px}
#rssi{left:58%;top:63.3%;font-size:36px}
#band{left:34%;top:83.6%;font-size:29px;color:white}
#freq{left:34%;top:88.1%;font-size:17px;color:white;font-weight:400}
#smallStatus{left:8%;top:96%;right:8%;font-size:13px;text-align:center;color:#a8c6df}
#ctrlBtn{position:absolute;right:5%;top:4.5%;width:15%;height:8%;border:none;background:rgba(0,0,0,.02);color:transparent}
.panel{position:absolute;left:0;right:0;bottom:-100%;background:#071d31;border-radius:26px 26px 0 0;border:1px solid rgba(0,180,255,.4);padding:20px;transition:.25s;z-index:20;box-shadow:0 -10px 35px rgba(0,0,0,.65)}
.panel.open{bottom:0}
.panel h2{text-align:center;margin:0 0 14px}
.panel input{width:100%;background:#061a2b;border:1px solid rgba(0,180,255,.3);border-radius:12px;color:#fff;padding:14px;margin:6px 0;font-size:16px;outline:none}
.btn{width:100%;border:none;border-radius:14px;background:linear-gradient(135deg,#0084e8,#00b4ff);color:#fff;font-weight:800;padding:14px;margin:6px 0;font-size:15px}
.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}.dark{background:#284762}
.raw{font-family:monospace;color:#8ea4b5;font-size:11px;white-space:pre-wrap;max-height:120px;overflow:auto;margin-top:8px}
.nav{height:66px;background:rgba(2,10,19,.96);display:grid;grid-template-columns:repeat(5,1fr);border-top:1px solid rgba(0,180,255,.12);z-index:30}
.nav button{background:none;border:none;color:#7d91a7;font-size:12px}.nav button.active{color:#00b4ff;font-weight:800}.nav span{display:block;font-size:23px;margin-bottom:1px}
</style>
</head>
<body>
<div id='app'>
<div id='content'>

<section id='home' class='screen active'>
<img class='bg' src='screen_home.png'>
<button class='tap' onclick='show("dash")'>Tap to Begin ›</button>
</section>

<section id='dash' class='screen'>
<img class='bg' src='screen_dashboard.png'>
<div class='overlay'>
<div class='mask' style='left:45%;top:14%;width:25%;height:9%'></div>
<div id='q' class='val'>--<span style='font-size:23px'>/100</span></div>
<div class='mask' style='left:46%;top:24%;width:38%;height:6%'></div>
<div id='qWord' class='val'>Waiting</div>

<div class='mask' style='left:7%;top:40%;width:31%;height:7%'></div>
<div id='rsrp' class='val'>--</div>
<div class='mask' style='left:57%;top:40%;width:31%;height:7%'></div>
<div id='sinr' class='val'>--</div>
<div class='mask' style='left:7%;top:62%;width:31%;height:7%'></div>
<div id='rsrq' class='val'>--</div>
<div class='mask' style='left:57%;top:62%;width:31%;height:7%'></div>
<div id='rssi' class='val'>--</div>

<div class='mask' style='left:33%;top:83%;width:31%;height:9%'></div>
<div id='band' class='val'>--</div>
<div id='freq' class='val'>--</div>
<div id='smallStatus' class='statusText'>Not logged in</div>
<button id='ctrlBtn' onclick='openPanel()'>controls</button>
</div>
</section>

<section id='scan' class='screen'><img class='bg' src='screen_scan.png'></section>
<section id='tower' class='screen'><img class='bg' src='screen_tower.png'></section>
<section id='reports' class='screen'><img class='bg' src='screen_reports.png'></section>

<div id='panel' class='panel'>
<h2>Router Controls</h2>
<input id='url' value='https://hirouter.net'>
<input id='pass' type='password' placeholder='Router admin password'>
<button class='btn' onclick='saveRouter();SignalScout.login()'>Login to Router</button>
<div class='row'><button class='btn dark' onclick='saveRouter();SignalScout.startLive()'>Start</button><button class='btn dark' onclick='SignalScout.stopLive()'>Stop</button><button class='btn dark' onclick='resetBest()'>Reset</button></div>
<button class='btn' onclick='saveRouter();SignalScout.testRead()'>Test Read Once</button>
<button class='btn dark' onclick='closePanel()'>Close</button>
<div id='raw' class='raw'>Raw reply will show here.</div>
</div>

</div>
<nav class='nav'>
<button id='nav-home' class='active' onclick='show("home")'><span>⌂</span>Home</button>
<button id='nav-dash' onclick='show("dash")'><span>📶</span>Live</button>
<button id='nav-scan' onclick='show("scan")'><span>🔍</span>Scan</button>
<button id='nav-tower' onclick='show("tower")'><span>🧭</span>Tower</button>
<button id='nav-reports' onclick='show("reports")'><span>📄</span>Reports</button>
</nav>
</div>
<script>
function show(id){document.querySelectorAll('.screen').forEach(s=>s.classList.remove('active'));document.getElementById(id).classList.add('active');document.querySelectorAll('.nav button').forEach(b=>b.classList.remove('active'));let n=document.getElementById('nav-'+id);if(n)n.classList.add('active');closePanel()}
function openPanel(){document.getElementById('panel').classList.add('open')}
function closePanel(){document.getElementById('panel').classList.remove('open')}
function saveRouter(){SignalScout.setRouter(document.getElementById('url').value,document.getElementById('pass').value)}
function setStatus(s){document.getElementById('smallStatus').innerText=s}
function setRaw(r){document.getElementById('raw').innerText=r}
function resetBest(){setStatus('Best reset')}
function updateLive(d){
 setStatus(d.status+'  Best '+d.best);
 document.getElementById('q').innerHTML=d.quality+'<span style="font-size:23px">/100</span>';
 document.getElementById('qWord').innerText=qualityWord(d.quality);
 document.getElementById('sinr').innerText=d.sinr;
 document.getElementById('rsrp').innerText=d.rsrp;
 document.getElementById('rsrq').innerText=d.rsrq;
 document.getElementById('rssi').innerText=d.rssi;
 document.getElementById('band').innerText=d.band;
 document.getElementById('freq').innerText=bandFreq(d.band);
 setRaw(d.raw);
}
function qualityWord(q){q=parseInt(q);if(isNaN(q))return'Waiting';if(q>=90)return'Excellent';if(q>=70)return'Good';if(q>=40)return'Needs Improvement';return'Poor'}
function bandFreq(b){if(!b||b==='--')return'--';if(b.includes('20'))return'800 MHz';if(b.includes('3'))return'1800 MHz';if(b.includes('7'))return'2600 MHz';if(b.includes('1'))return'2100 MHz';return'Current LTE band'}
</script>
</body>
</html>
""";
    }
}
