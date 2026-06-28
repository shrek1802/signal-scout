package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.*;
import android.annotation.SuppressLint;
import android.webkit.*;
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
        web.addJavascriptInterface(new Bridge(), "SignalScout");
        setContentView(web);
        web.loadDataWithBaseURL(null, html(), "text/html", "UTF-8", null);
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
:root{--bg:#03101d;--card:#071d31;--card2:#092842;--blue:#00b4ff;--green:#62ff48;--yellow:#d7ff4f;--amber:#ffbd38;--red:#ff5252;--muted:#91a7ba}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#020a13;color:white;font-family:Arial,Helvetica,sans-serif;overflow:hidden}
#app{height:100vh;display:flex;flex-direction:column;background:radial-gradient(circle at top,#0a2d4b 0,#03101d 42%,#020812 100%)}
#content{flex:1;overflow:auto;padding:12px 12px 86px}
.screen{display:none}.screen.active{display:block}
.top{height:44px;display:flex;align-items:center;justify-content:space-between;margin-bottom:8px}
.menu,.dots{font-size:30px;color:white;opacity:.9}.pageTitle{font-size:20px;font-weight:800}
.hero{background:linear-gradient(140deg,#071c31,#0b2f4e);border:1px solid rgba(0,180,255,.24);border-radius:20px;padding:14px;margin-bottom:12px;box-shadow:0 0 26px rgba(0,180,255,.14);display:flex;gap:12px;align-items:center;min-height:170px;overflow:hidden;position:relative}
.bot{width:118px;height:142px;border-radius:22px;background:radial-gradient(circle at 50% 28%,#ffffff 0,#e4e8ec 28%,#707982 29%,#151b22 55%,#0a0d12 100%);position:relative;flex-shrink:0;box-shadow:0 8px 24px rgba(0,0,0,.45)}
.face{position:absolute;top:32px;left:19px;width:80px;height:42px;background:#06101b;border-radius:18px;border:2px solid #536373;box-shadow:inset 0 0 20px #001}
.eye{position:absolute;top:13px;width:15px;height:15px;border:4px solid #00d6ff;border-bottom:none;border-radius:18px 18px 0 0}.eye.l{left:18px}.eye.r{right:18px}
.vest{position:absolute;top:78px;left:25px;width:68px;height:58px;background:linear-gradient(90deg,#b8ff00,#efff00,#a8ff00);border-radius:8px 8px 14px 14px;border:2px solid #374}
.barsMini{position:absolute;right:9px;bottom:13px;display:flex;gap:3px;align-items:flex-end}.barsMini i{width:5px;background:#0af;border-radius:3px}.barsMini i:nth-child(1){height:10px}.barsMini i:nth-child(2){height:18px}.barsMini i:nth-child(3){height:28px}
.heroText{flex:1}.heroText .small{font-size:13px;color:var(--muted)}.heroText .big{font-size:46px;color:var(--green);font-weight:900;line-height:1}.heroText .word{font-size:20px;color:var(--green);font-weight:800}.gauge{position:absolute;right:-50px;top:12px;width:160px;height:160px;border-radius:50%;border:14px solid rgba(98,255,72,.9);border-left-color:transparent;border-bottom-color:transparent;opacity:.85}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.card{background:rgba(7,29,49,.92);border:1px solid rgba(0,180,255,.23);border-radius:18px;padding:15px;box-shadow:inset 0 0 18px rgba(0,180,255,.05)}
.metric h3{margin:0;color:white;font-size:18px}.metric .val{font-size:36px;color:var(--green);font-weight:900;margin:10px 0 2px}.metric .sub{font-size:14px;color:var(--green)}.metric svg{width:100%;height:38px;margin-top:8px}.line{fill:none;stroke:var(--green);stroke-width:3}.area{fill:rgba(98,255,72,.18)}
.connect{display:flex;gap:14px;align-items:center;margin-top:12px}.towerIcon{width:72px;height:72px;border-radius:16px;background:#0d253d;display:flex;align-items:center;justify-content:center;font-size:42px}.connect .band{font-size:26px}.badge{margin-left:auto;background:#163d25;color:var(--green);font-size:12px;border-radius:12px;padding:5px 8px}
.controls{margin-top:12px}.controls h2{margin:0 0 8px;text-align:center}.controls input{width:100%;background:#061a2b;border:1px solid rgba(0,180,255,.25);border-radius:12px;color:#fff;padding:13px;margin:5px 0;font-size:16px;outline:none}.btn{border:none;border-radius:14px;background:linear-gradient(135deg,#0084e8,#00b4ff);color:#fff;font-weight:800;padding:13px;font-size:15px;box-shadow:0 7px 18px rgba(0,140,255,.25);width:100%;margin:6px 0}.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}.darkBtn{background:#27465f}.status{text-align:center;color:#c5d8e8;font-size:14px;margin:8px}.raw{font-family:monospace;color:#8ea4b5;font-size:11px;white-space:pre-wrap;max-height:140px;overflow:auto}
.scanPanel .progress{height:10px;background:#17354f;border-radius:30px;overflow:hidden;margin:12px 0}.scanPanel .bar{height:100%;width:75%;background:linear-gradient(90deg,var(--green),var(--blue))}.bandRow{display:flex;align-items:center;gap:10px;margin:10px 0}.bandRow span:first-child{width:42px}.bandBar{flex:1;height:9px;background:#17354f;border-radius:20px;overflow:hidden}.bandFill{height:100%;background:var(--blue);border-radius:20px}.tick{color:var(--green);font-weight:900}
.compass{width:280px;height:280px;margin:20px auto;border-radius:50%;border:2px solid rgba(255,255,255,.2);background:radial-gradient(circle,#0a2035,#06131f);position:relative;display:flex;align-items:center;justify-content:center;color:var(--green);font-size:74px;font-weight:900}.compass:before{content:'N';position:absolute;top:18px;font-size:24px;color:white}.compass:after{content:'23° NE';position:absolute;bottom:45px;font-size:36px;color:var(--green)}
.reportItem{display:flex;align-items:center;gap:14px}.docIcon{font-size:44px;color:var(--blue)}
.nav{height:66px;background:rgba(2,10,19,.97);display:grid;grid-template-columns:repeat(5,1fr);border-top:1px solid rgba(0,180,255,.14);position:fixed;bottom:0;left:0;right:0}.nav button{background:none;border:none;color:#7d91a7;font-size:12px}.nav button.active{color:var(--blue);font-weight:800}.nav span{display:block;font-size:23px;margin-bottom:1px}
</style>
</head>
<body>
<div id='app'><div id='content'>

<section id='home' class='screen active'>
<div class='top'><div></div><div class='pageTitle'>Signal Scout</div><div></div></div>
<div class='hero' style='min-height:520px;display:block;text-align:center;padding-top:25px'>
<div style='font-size:44px;font-weight:900;text-align:left;line-height:1;margin-left:10px'><span style='color:white'>Signal</span><br><span style='color:#00b4ff'>Scout</span></div>
<div style='font-size:16px;color:white;text-align:left;margin:18px 0 0 10px'>Professional LTE & 5G<br>Installation Assistant</div>
<div class='bot' style='width:220px;height:270px;margin:40px auto 0'><div class='face' style='width:150px;height:78px;left:35px;top:50px'><div class='eye l' style='left:36px;top:24px'></div><div class='eye r' style='right:36px;top:24px'></div></div><div class='vest' style='top:150px;left:50px;width:120px;height:95px'></div></div>
<button class='btn' onclick='show("live")' style='position:absolute;left:35px;right:35px;bottom:22px;width:auto'>Tap to Begin ›</button>
</div>
<div class='card'><h3>What are you trying to achieve today?</h3><p>🛰️ New Antenna Installation<br>📶 Improve Existing Installation<br>🚀 Find the Fastest Connection<br>🔍 Diagnose Poor Signal<br>⚙️ Advanced / Professional Mode</p></div>
</section>

<section id='live' class='screen'>
<div class='top'><div class='menu'>≡</div><div class='pageTitle'>Dashboard</div><div class='dots'>⋮</div></div>
<div class='hero'>
<div class='bot'><div class='face'><div class='eye l'></div><div class='eye r'></div></div><div class='vest'><div class='barsMini'><i></i><i></i><i></i></div></div></div>
<div class='heroText'><div class='small'>Overall Quality</div><div id='qHero' class='big'>--<span style='font-size:23px'>/100</span></div><div id='qWord' class='word'>Waiting</div><div class='small'>📍 Location: NG12 3NH</div></div><div class='gauge'></div>
</div>
<div class='grid'>
<div class='card metric'><h3>RSRP</h3><div id='rsrp' class='val'>--</div><svg viewBox='0 0 160 40'><path class='area' d='M0 38 L0 30 C25 22 35 32 52 24 S82 18 100 24 S130 14 160 18 L160 38 Z'/><path class='line' d='M0 30 C25 22 35 32 52 24 S82 18 100 24 S130 14 160 18'/></svg><div class='sub'>Excellent</div></div>
<div class='card metric'><h3>SINR</h3><div id='sinr' class='val'>--</div><svg viewBox='0 0 160 40'><path class='area' d='M0 38 L0 32 C30 32 48 29 70 26 S105 8 160 16 L160 38 Z'/><path class='line' d='M0 32 C30 32 48 29 70 26 S105 8 160 16'/></svg><div class='sub'>Excellent</div></div>
<div class='card metric'><h3>RSRQ</h3><div id='rsrq' class='val'>--</div><svg viewBox='0 0 160 40'><path class='area' d='M0 38 L0 24 C24 29 43 20 66 25 S103 26 160 20 L160 38 Z'/><path class='line' d='M0 24 C24 29 43 20 66 25 S103 26 160 20'/></svg><div class='sub' style='color:#ffd84e'>Good</div></div>
<div class='card metric'><h3>RSSI</h3><div id='rssi' class='val'>--</div><svg viewBox='0 0 160 40'><path class='area' d='M0 38 L0 28 C24 23 50 27 76 18 S121 24 160 12 L160 38 Z'/><path class='line' d='M0 28 C24 23 50 27 76 18 S121 24 160 12'/></svg><div class='sub'>Excellent</div></div>
</div>
<div class='card connect'><div class='towerIcon'>♜</div><div><div style='color:#b8d8ef'>Connected to</div><div id='band' class='band'>--</div><div id='freq' style='color:#d9e8f5'>--</div></div><div class='badge'>4G+</div></div>
<div class='controls card'><h2>Router Controls</h2><input id='url' value='https://hirouter.net'><input id='pass' type='password' placeholder='Router admin password'><button class='btn' onclick='saveRouter();SignalScout.login()'>Login to Router</button><div class='row'><button class='btn darkBtn' onclick='saveRouter();SignalScout.startLive()'>Start</button><button class='btn darkBtn' onclick='SignalScout.stopLive()'>Stop</button><button class='btn darkBtn' onclick='resetBest()'>Reset</button></div><button class='btn' onclick='saveRouter();SignalScout.testRead()'>Test Read Once</button><div id='status' class='status'>Not logged in</div></div>
<div class='card'><h3>Connected Cell</h3><p id='cellinfo'>PCI: --<br>EARFCN: --<br>Cell ID: --<br>eNodeB: --</p></div>
<div class='card'><h3>Raw Reply</h3><div id='raw' class='raw'>Raw reply will show here.</div></div>
</section>

<section id='scan' class='screen'><div class='top'><div>‹</div><div class='pageTitle'>Scan Bands</div><div></div></div><div class='hero'><div class='bot'><div class='face'><div class='eye l'></div><div class='eye r'></div></div><div class='vest'></div></div><div class='heroText'><div class='card'>Scanning available bands...<br><br>This may take a few seconds.</div></div></div><div class='card scanPanel'><h3>Progress <span style='float:right'>75%</span></h3><div class='progress'><div class='bar'></div></div><div class='bandRow'><span>B1</span><span>2100 MHz</span><div class='bandBar'><div class='bandFill' style='width:35%'></div></div><span>-95 dBm</span></div><div class='bandRow'><span>B3</span><span>1800 MHz</span><div class='bandBar'><div class='bandFill' style='width:85%;background:var(--green)'></div></div><span>-78 dBm</span><span class='tick'>✓</span></div><div class='bandRow'><span>B7</span><span>2600 MHz</span><div class='bandBar'><div class='bandFill' style='width:30%'></div></div><span>-98 dBm</span></div><div class='bandRow'><span>B20</span><span>800 MHz</span><div class='bandBar'><div class='bandFill' style='width:70%;background:var(--green)'></div></div><span>-82 dBm</span><span class='tick'>✓</span></div></div><button class='btn' onclick='alert("Smart Scan coming soon")'>Start Smart Scan</button></section>

<section id='tower' class='screen'><div class='top'><div>‹</div><div class='pageTitle'>Tower Direction</div><div>⋮</div></div><div class='hero'><div class='bot'><div class='face'><div class='eye l'></div><div class='eye r'></div></div><div class='vest'></div></div><div class='heroText'><div class='card'>Turn this way for a <span style='color:var(--green)'>stronger</span> signal!</div></div></div><div class='compass'>↑</div><div class='card'><h3>Best Signal This Direction</h3><p style='font-size:34px;color:var(--green);font-weight:900'>-72 dBm</p></div></section>

<section id='reports' class='screen'><div class='top'><div>‹</div><div class='pageTitle'>Reports</div><div></div></div><div class='hero'><div class='bot'><div class='face'><div class='eye l'></div><div class='eye r'></div></div><div class='vest'></div></div><div class='heroText'><div class='card'>Your report is ready!<br><span style='color:var(--green)'>Great work.</span></div></div></div><div class='card reportItem'><div class='docIcon'>▣</div><div><h3>Site Survey</h3><p>12 Jun 2026 - 10:30<br>NG12 3NH<br><span style='color:var(--green)'>Excellent</span></p></div></div><button class='btn'>View Report</button><button class='btn'>Export PDF</button><button class='btn'>Share</button></section>

<section id='more' class='screen'><div class='top'><div></div><div class='pageTitle'>Scout</div><div></div></div><div class='hero'><div class='bot'><div class='face'><div class='eye l'></div><div class='eye r'></div></div><div class='vest'></div></div><div class='heroText'><div style='font-size:26px;font-weight:900'>SCOUT</div><div class='small'>Your friendly signal expert, here to help you find the best connection.</div></div></div><div class='grid'><div class='metric card'><div class='val'>90-100</div><div class='sub'>Excellent</div></div><div class='metric card'><div class='val' style='color:#d7ff4f'>70-89</div><div class='sub'>Good</div></div><div class='metric card'><div class='val' style='color:#ffbd38'>40-69</div><div class='sub'>Needs Improvement</div></div><div class='metric card'><div class='val' style='color:#ff5252'>0-39</div><div class='sub'>Poor</div></div></div></section>

</div><nav class='nav'><button id='nav-home' class='active' onclick='show("home")'><span>⌂</span>Home</button><button id='nav-live' onclick='show("live")'><span>📶</span>Live</button><button id='nav-scan' onclick='show("scan")'><span>🔍</span>Scan</button><button id='nav-tower' onclick='show("tower")'><span>🧭</span>Tower</button><button id='nav-reports' onclick='show("reports")'><span>📄</span>Reports</button></nav></div>
<script>
function show(id){document.querySelectorAll('.screen').forEach(s=>s.classList.remove('active'));document.getElementById(id).classList.add('active');document.querySelectorAll('.nav button').forEach(b=>b.classList.remove('active'));let n=document.getElementById('nav-'+id);if(n)n.classList.add('active');document.getElementById('content').scrollTop=0}
function saveRouter(){SignalScout.setRouter(document.getElementById('url').value,document.getElementById('pass').value)}
function setStatus(s){document.getElementById('status').innerText=s}
function setRaw(r){document.getElementById('raw').innerText=r}
function resetBest(){document.getElementById('status').innerText='Best reset'}
function updateLive(d){setStatus(d.status);document.getElementById('qHero').innerHTML=d.quality+'<span style="font-size:23px">/100</span>';document.getElementById('qWord').innerText=qualityWord(d.quality);document.getElementById('sinr').innerText=d.sinr;document.getElementById('rsrp').innerText=d.rsrp;document.getElementById('rsrq').innerText=d.rsrq;document.getElementById('rssi').innerText=d.rssi;document.getElementById('band').innerText=d.band;document.getElementById('freq').innerText=d.band.includes('20')?'800 MHz':'Current LTE band';document.getElementById('cellinfo').innerHTML='PCI: '+d.pci+'<br>EARFCN: '+d.earfcn+'<br>Cell ID: '+d.cell+'<br>eNodeB: '+d.enodeb;setRaw(d.raw)}
function qualityWord(q){q=parseInt(q);if(isNaN(q))return'Waiting';if(q>=90)return'Excellent';if(q>=70)return'Good';if(q>=40)return'Needs Improvement';return'Poor'}
</script>
</body></html>
""";
    }
}
