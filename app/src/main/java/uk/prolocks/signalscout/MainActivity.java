package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.*;
import android.webkit.*;
import android.widget.Toast;
import android.annotation.SuppressLint;
import android.media.*;
import android.content.*;
import android.content.Intent;
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
    ArrayList<String> tokenQueue = new ArrayList<>();
    String lastToken = "";
    double bestSinr = -999;

    String[] candidateBases = new String[]{
        "https://hirouter.net", "http://hirouter.net",
        "https://192.168.8.1", "http://192.168.8.1",
        "https://192.168.1.1", "http://192.168.1.1"
    };

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
        @JavascriptInterface public void openInstallerActivity(String sinr, String best, String status) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, InstallerActivity.class);
                    i.putExtra("sinr", sinr == null || sinr.length() == 0 ? "--" : sinr);
                    i.putExtra("best", best == null || best.length() == 0 ? "--" : best);
                    i.putExtra("status", status == null || status.length() == 0 ? "HOLD POSITION" : status);
                    MainActivity.this.startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Installer Mode failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    js("setStatus('Installer Mode failed');");
                }
            });
        }

        @JavascriptInterface public void setRouter(String url, String pass) {
            routerBase = url == null || url.trim().length() == 0 ? "AUTO" : url.trim();
            if (routerBase.endsWith("/")) routerBase = routerBase.substring(0, routerBase.length() - 1);
            routerPass = pass == null ? "" : pass;
        }
        @JavascriptInterface public void detectAndLogin() { MainActivity.this.detectAndLogin(); }
        @JavascriptInterface public void login() { MainActivity.this.login(); }
        @JavascriptInterface public void startLive() {
            running = true;
            bestSinr = -999;
            js("setStatus('Running live data'); closeAll();");
            handler.removeCallbacks(poller);
            handler.post(poller);
        }
        @JavascriptInterface public void stopLive() {
            running = false;
            handler.removeCallbacks(poller);
            js("setStatus('Stopped');");
        }
        @JavascriptInterface public void testRead() { readSignal(); }
        @JavascriptInterface public void debugEndpoints() { MainActivity.this.debugEndpoints(); }
    }

    void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }
    String esc(String x) { return x == null ? "" : x.replace("\\", "\\\\").replace("`", "\\`").replace("\n", "\\n").replace("\r", ""); }
    void log(String s) { js("appendLog(`" + esc(s) + "`);"); }

    void detectAndLogin() {
        new Thread(() -> {
            js("clearLog(); setStatus('Detecting router...');");
            log("Signal Scout Router Engine v3.5.5");
            String manual = routerBase;
            ArrayList<String> bases = new ArrayList<>();
            if (manual != null && manual.length() > 0 && !manual.equalsIgnoreCase("AUTO")) bases.add(manual);
            for (String b : candidateBases) if (!bases.contains(b)) bases.add(b);

            for (String base : bases) {
                try {
                    log("Trying " + base);
                    routerBase = base;
                    sessionCookie = "";
                    tokenQueue.clear();
                    lastToken = "";
                    HttpResult res = request("GET", "/api/webserver/SesTokInfo", null, null);
                    String tok = pick(res.body, "TokInfo");
                    String ses = pick(res.body, "SesInfo");
                    if (tok.length() > 0 || ses.length() > 0 || res.body.contains("<response>")) {
                        log("✓ Router responded on " + base);
                        if (ses.length() > 0) sessionCookie = ses;
                        if (tok.length() > 0) pushTokens(tok);
                        login();
                        return;
                    }
                } catch(Exception e) {
                    log("Failed " + base + " : " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
            js("setStatus('Router not found');");
            log("✗ No supported router found.");
        }).start();
    }

    void login() {
        js("setStatus('Logging in...');");
        new Thread(() -> {
            String debug = "";
            try {
                debug += "Base: " + routerBase + "\n";
                debug += ensureSession();

                HttpResult state = request("GET", "/api/user/state-login", null, null);
                debug += "\nstate-login HTTP " + state.code + "\n" + shrink(state.body) + "\n";

                String pwdType = pick(state.body, "password_type");
                if (pwdType.length() == 0) pwdType = "4";
                String[] types = pwdType.equals("4") ? new String[]{"4","3","2"} : new String[]{pwdType,"4","3","2"};
                boolean ok = false;
                String finalDebug = debug;

                for (String type : types) {
                    String token = nextToken();
                    if (token.length() == 0) {
                        ensureSession();
                        token = nextToken();
                    }

                    String passwordHash = loginPassword(type, routerPass, token);
                    String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><request><Username>admin</Username><Password>" + passwordHash + "</Password><password_type>" + type + "</password_type></request>";

                    HttpResult res = request("POST", "/api/user/login", body, token);
                    finalDebug += "\nLOGIN TRY password_type " + type + "\nHTTP " + res.code + "\n" + shrink(res.body) + "\n";
                    finalDebug += "Cookie length " + sessionCookie.length() + " tokenQueue " + tokenQueue.size() + "\n";

                    if (res.body.contains("<response>OK</response>") || res.body.trim().equals("OK")) {
                        ok = true;
                        break;
                    }
                }

                final String outDebug = finalDebug;
                boolean outOk = ok;
                runOnUiThread(() -> js("setRaw(`" + esc(outDebug) + "`); setStatus('" + (outOk ? "Logged in OK" : "Login not OK - see debug") + "'); setRouterState('" + (outOk ? "Connected" : "Not connected") + "');"));
                log(ok ? "✓ Login OK" : "✗ Login not OK. Check debug box.");
            } catch(Exception e) {
                debug += "\nEXCEPTION:\n" + e.toString();
                final String outDebug = debug;
                runOnUiThread(() -> js("setRaw(`" + esc(outDebug) + "`); setStatus('Login failed'); setRouterState('Not connected');"));
                log("✗ Login exception: " + e.toString());
            }
        }).start();
    }

    String ensureSession() throws Exception {
        HttpResult res = request("GET", "/api/webserver/SesTokInfo", null, null);
        String tok = pick(res.body, "TokInfo");
        String ses = pick(res.body, "SesInfo");
        if (ses.length() > 0) sessionCookie = ses;
        if (tok.length() > 0) pushTokens(tok);
        return "SesTokInfo HTTP " + res.code + "\nTokenQueue " + tokenQueue.size() + "\nCookie length " + sessionCookie.length() + "\n" + shrink(res.body) + "\n";
    }

    String loginPassword(String type, String password, String token) throws Exception {
        if (type.equals("4")) return b64HexSha256("admin" + b64HexSha256(password) + token);
        if (type.equals("3")) return b64Sha256("admin" + b64Sha256(password) + token);
        if (type.equals("2")) return b64Sha256(password);
        return b64HexSha256("admin" + b64HexSha256(password) + token);
    }

    void debugEndpoints() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Debug endpoints on ").append(routerBase).append("\n\n");
            String[] endpoints = new String[]{"/api/webserver/SesTokInfo","/api/user/state-login","/api/device/information","/api/device/basic_information","/api/monitoring/status","/api/device/signal","/api/net/current-plmn"};
            for (String ep : endpoints) {
                try {
                    HttpResult r = request("GET", ep, null, null);
                    sb.append(ep).append(" HTTP ").append(r.code).append("\n").append(shrink(r.body)).append("\n\n");
                } catch(Exception e) {
                    sb.append(ep).append(" ERROR ").append(e.toString()).append("\n\n");
                }
            }
            String debug = sb.toString();
            js("setRaw(`" + esc(debug) + "`); setStatus('Endpoint debug complete');");
        }).start();
    }

    void readSignal() {
        js("setStatus('Reading router...');");
        new Thread(() -> {
            try {
                HttpResult r = request("GET", "/api/device/signal", null, null);
                String xml = r.body;
                String sinr = pick(xml, "sinr", "snr");
                String rsrp = pick(xml, "rsrp");
                String rsrq = pick(xml, "rsrq");
                String rssi = pick(xml, "rssi");
                String band = pick(xml, "band");
                String pci = pick(xml, "pci");
                String earfcn = pick(xml, "earfcn");
                String cell = pick(xml, "cell_id", "cellid");
                String enodeb = pick(xml, "enodeb_id", "enodebid");

                double sinrNum = num(sinr);
                int quality = qualityScore(sinrNum, num(rsrq), num(rsrp));

                if (sinrNum > -900) {
                    if (sinrNum > bestSinr) { bestSinr = sinrNum; vibrate(); }
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

    HttpResult request(String method, String path, String body, String token) throws Exception {
        URL url = new URL(routerBase + path);
        HttpURLConnection con = (HttpURLConnection)url.openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(8000);
        con.setRequestMethod(method);
        con.setRequestProperty("Accept", "application/xml,text/xml,*/*");
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SignalScout");
        con.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        con.setRequestProperty("Referer", routerBase + "/html/home.html");
        if (sessionCookie.length() > 0) con.setRequestProperty("Cookie", sessionCookie);
        if (token != null && token.length() > 0) con.setRequestProperty("__RequestVerificationToken", token);
        if (body != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/xml");
            OutputStream os = con.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = con.getResponseCode();
        String text = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
        HttpResult res = new HttpResult(code, text, con.getHeaderFields());
        processHeaders(res);
        return res;
    }

    void processHeaders(HttpResult res) {
        if (res.headers == null) return;
        for (String k : res.headers.keySet()) {
            List<String> vals = res.headers.get(k);
            if (vals == null) continue;
            if (k != null && k.equalsIgnoreCase("Set-Cookie")) {
                for (String v : vals) {
                    if (v == null) continue;
                    String first = v.split(";", 2)[0];
                    if (first.toLowerCase().contains("sessionid")) sessionCookie = first;
                }
            }
            if (k != null && (k.equalsIgnoreCase("__RequestVerificationToken") || k.equalsIgnoreCase("__RequestVerificationTokenone") || k.equalsIgnoreCase("__RequestVerificationTokentwo"))) {
                for (String v : vals) pushTokens(v);
            }
        }
    }

    void pushTokens(String val) {
        if (val == null) return;
        String[] parts = val.split("#");
        for (String p : parts) {
            p = p.trim();
            if (p.length() > 0) { lastToken = p; tokenQueue.add(p); }
        }
    }

    String nextToken() {
        if (tokenQueue.size() > 0) return tokenQueue.remove(0);
        return lastToken == null ? "" : lastToken;
    }

    String shrink(String s) { if (s == null) return ""; s=s.replace("\r","").trim(); return s.length()>1200 ? s.substring(0,1200)+"\n...[trimmed]" : s; }
    String clean(String s) { return s == null || s.length() == 0 ? "--" : s; }

    String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    String pick(String xml, String... tags) {
        for (String tag : tags) {
            Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(xml == null ? "" : xml);
            if (m.find()) return m.group(1).trim();
        }
        return "";
    }

    double num(String s) { Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(s == null ? "" : s); if (m.find()) try { return Double.parseDouble(m.group()); } catch(Exception ignored) {} return -999; }

    int qualityScore(double sinr, double rsrq, double rsrp) {
        if (sinr < -900) return -1;
        int s = 0;
        s += clamp((int)((sinr + 5) / 30.0 * 60), 0, 60);
        s += clamp((int)((rsrq + 20) / 12.0 * 25), 0, 25);
        s += clamp((int)((rsrp + 115) / 35.0 * 15), 0, 15);
        return clamp(s, 0, 100);
    }
    int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    void vibrate() { try { if (vibrator == null) return; if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)); else vibrator.vibrate(80); } catch(Exception ignored) {} }

    byte[] sha256Bytes(String input) throws Exception { return MessageDigest.getInstance("SHA-256").digest(input.getBytes("UTF-8")); }
    String sha256Hex(String input) throws Exception { byte[] out = sha256Bytes(input); StringBuilder sb = new StringBuilder(); for (byte b : out) sb.append(String.format("%02x", b)); return sb.toString(); }
    String b64HexSha256(String input) throws Exception { return Base64.encodeToString(sha256Hex(input).getBytes("UTF-8"), Base64.NO_WRAP); }
    String b64Sha256(String input) throws Exception { return Base64.encodeToString(sha256Bytes(input), Base64.NO_WRAP); }

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
        int code; String body; Map<String, List<String>> headers;
        HttpResult(int c, String b, Map<String, List<String>> h) { code=c; body=b; headers=h; }
    }

    String html() {
        return """
<!DOCTYPE html>
<html>
<head>
<meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>
<style>
:root{--green:#69ff4b;--blue:#00b8ff;--yellow:#ffd13b;--card:rgba(3,14,22,.89);--stroke:rgba(105,255,75,.24)}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:#000;color:white;font-family:Arial,Helvetica,sans-serif;overflow:hidden}
#app{height:100vh;width:100vw;background:#000;position:relative;overflow:hidden}
.screen{position:absolute;inset:0;display:none;background:#000}.screen.active{display:block}
.bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;object-position:center top}
.homeBtn{position:absolute;left:7.5%;right:7.5%;height:8.2%;border:none;border-radius:17px;background:transparent;color:transparent}
#beginBtn{bottom:18.8%}#setupBtn{bottom:8.4%}
#menuBtn{position:absolute;left:12px;top:22px;width:56px;height:50px;background:rgba(3,14,22,.55);border:1px solid rgba(255,255,255,.15);border-radius:15px;color:white;z-index:3;font-size:30px}
#routerBtn{position:absolute;right:12px;top:22px;width:56px;height:50px;background:rgba(3,14,22,.55);border:1px solid rgba(255,255,255,.15);border-radius:15px;color:white;z-index:3;font-size:28px}
.dash{position:absolute;inset:0;overflow:auto;padding:18px 14px 16px}
.title{text-align:center;margin-top:10px;font-size:25px;font-weight:900}
.hero{margin-top:32px;background:var(--card);border:1px solid var(--stroke);border-radius:22px;box-shadow:0 0 30px rgba(0,0,0,.45);padding:18px;min-height:165px;position:relative}
.hero .small{font-size:14px;color:#b7cdd5}.hero .big{font-size:54px;color:var(--green);font-weight:900;line-height:1;margin-top:5px}.hero .word{font-size:22px;color:var(--green);font-weight:800;margin-top:6px}.ring{position:absolute;right:16px;top:22px;width:112px;height:112px;border-radius:50%;border:10px solid rgba(105,255,75,.18);border-top-color:var(--green);border-right-color:var(--green);display:flex;align-items:center;justify-content:center;font-size:25px;color:var(--green);font-weight:900}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}
.card{background:var(--card);border:1px solid var(--stroke);border-radius:18px;min-height:132px;padding:14px;box-shadow:0 0 22px rgba(0,0,0,.35);display:flex;flex-direction:column;justify-content:space-between;text-align:center}
.name{font-size:17px;color:white;font-weight:800}.valLine{height:55px;display:flex;align-items:baseline;justify-content:center;white-space:nowrap}.val{font-size:45px;color:var(--green);font-weight:900;line-height:1;min-width:72px;text-align:center}.unit{font-size:17px;color:white;margin-left:4px}.pill{color:var(--green);font-size:13px;background:rgba(105,255,75,.12);border-radius:9px;padding:7px;text-align:center;min-height:29px}
.cell{background:var(--card);border:1px solid var(--stroke);border-radius:18px;padding:14px;margin-top:12px}.cell h3{margin:0 0 12px;color:var(--green);font-size:17px}.cellGrid{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}.cellGrid div{font-size:11px;color:#c7d6da;text-align:center}.cellGrid b{display:block;color:white;font-size:14px;margin-top:4px;min-height:18px}
.statusBar{background:rgba(3,14,22,.95);border:1px solid var(--stroke);border-radius:14px;min-height:42px;padding:10px 12px;font-size:12px;display:flex;justify-content:space-between;align-items:center;margin-top:12px;margin-bottom:18px}

.optimiserWrap{position:absolute;inset:0;overflow:auto;padding:18px 14px 16px}
.optimiserHero{margin-top:32px;background:linear-gradient(160deg,rgba(3,14,22,.94),rgba(4,20,30,.92));border:1px solid var(--stroke);border-radius:24px;box-shadow:0 0 34px rgba(0,0,0,.5);padding:18px;text-align:center}
.optimiserHero h1{margin:0 0 6px;font-size:24px}.optimiserHero p{margin:0;color:#c7d6da;font-size:14px}
.bigSinrBox{margin-top:14px;background:rgba(0,0,0,.25);border:1px solid rgba(105,255,75,.18);border-radius:22px;padding:18px;text-align:center}
.bigSinrBox .label{font-size:16px;color:#c7d6da}.bigSinrBox .num{font-size:72px;font-weight:900;color:var(--green);line-height:1;margin-top:6px;font-variant-numeric:tabular-nums}.bigSinrBox .hint{font-size:20px;color:var(--green);font-weight:900;margin-top:8px}
.guideCard{background:var(--card);border:1px solid var(--stroke);border-radius:18px;margin-top:12px;padding:16px;text-align:center}.guideArrows{font-size:70px;line-height:1;color:var(--green);text-shadow:0 0 20px rgba(105,255,75,.35)}.guideText{font-size:32px;font-weight:900;margin-top:8px}.guideSub{font-size:14px;color:#c7d6da;margin-top:6px}
.optimiserGrid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:12px}.miniCard{background:var(--card);border:1px solid var(--stroke);border-radius:16px;padding:13px;text-align:center}.miniCard .miniName{font-size:13px;color:#c7d6da}.miniCard .miniVal{font-size:28px;color:var(--green);font-weight:900;margin-top:7px;font-variant-numeric:tabular-nums}
.installerBtn{width:100%;height:58px;border:none;border-radius:18px;background:linear-gradient(135deg,#22b14c,#69ff4b);color:white;font-size:19px;font-weight:900;margin-top:14px;box-shadow:0 0 25px rgba(105,255,75,.25)}
.installerOverlay{position:absolute;inset:0;background:#020912;z-index:40;display:none;overflow:hidden}.installerOverlay.active{display:block}.installerBg{position:absolute;inset:0;background:radial-gradient(circle at 50% 20%,rgba(105,255,75,.16),transparent 35%),linear-gradient(120deg,#020912,#061b27,#020912)}
.installerContent{position:absolute;inset:0;display:grid;grid-template-columns:1fr 1.15fr 1fr;align-items:center;padding:24px;color:white;text-align:center}
.instArrow{font-size:130px;font-weight:900;color:rgba(105,255,75,.22);line-height:1}.instArrow.active{color:var(--green);text-shadow:0 0 38px rgba(105,255,75,.55)}.instArrow.red{color:#ff4a3d;text-shadow:0 0 38px rgba(255,74,61,.55)}
.instMain .instTitle{font-size:48px;font-weight:900;line-height:1.02}.instMain .instSinr{font-size:86px;font-weight:900;color:var(--green);margin-top:10px;font-variant-numeric:tabular-nums}.instMain .instUnit{font-size:27px}.instMain .instState{font-size:28px;font-weight:900;color:var(--green);margin-top:10px}.instMain .instSmall{font-size:18px;color:#c7d6da;margin-top:10px}
.instTop{position:absolute;left:18px;right:18px;top:14px;display:flex;justify-content:space-between;align-items:center;font-size:15px;color:#c7d6da}.exitInst{border:none;border-radius:12px;background:rgba(255,255,255,.12);color:white;padding:10px 14px;font-weight:800}
@media (orientation:portrait){
 .installerContent{grid-template-columns:1fr;grid-template-rows:1fr 1.4fr 1fr;padding:18px}
 .instArrow{font-size:96px}
 .instMain .instTitle{font-size:42px}
 .instMain .instSinr{font-size:78px}
}

.drawer{position:absolute;top:0;bottom:0;left:-82%;width:82%;z-index:10;transition:.25s;background:#020910;box-shadow:18px 0 40px rgba(0,0,0,.6);padding:26px 18px}.drawer.open{left:0}.drawer h1{color:var(--green);margin:20px 0 2px;font-size:32px}.drawer h2{margin:0 0 18px;font-size:18px}.menuItem{height:48px;border-radius:12px;display:flex;align-items:center;gap:14px;padding:0 12px;font-size:16px}.menuItem.active{background:rgba(105,255,75,.18);color:var(--green)}.menuFoot{position:absolute;bottom:24px;left:18px;right:18px;color:#a8bac1;font-size:13px}
.scrim{position:absolute;inset:0;background:rgba(0,0,0,.55);z-index:9;display:none}.scrim.open{display:block}
.router{position:absolute;left:0;right:0;bottom:-100%;background:#071d28;border-radius:24px 24px 0 0;border:1px solid rgba(101,255,73,.28);padding:18px;z-index:20;transition:.25s;max-height:92vh;overflow:auto}.router.open{bottom:0}.router h2{text-align:center;margin:0 0 12px}.router input{width:100%;background:#06131d;border:1px solid rgba(101,255,73,.24);border-radius:12px;padding:13px;color:white;margin:6px 0;font-size:16px}.btn{width:100%;height:46px;border:none;border-radius:12px;background:linear-gradient(135deg,#0a84ff,#00b8ff);color:white;font-weight:800;font-size:15px;margin-top:8px}.btn.dark{background:#1c3440}.row{display:grid;grid-template-columns:1fr 1fr 1fr;gap:8px}.raw{font-family:monospace;color:#a7b9bf;font-size:10px;white-space:pre-wrap;max-height:180px;overflow:auto;margin-top:8px}.log{font-family:monospace;color:#d7f4df;font-size:11px;white-space:pre-wrap;max-height:145px;overflow:auto;background:#03111a;border-radius:10px;padding:8px;margin-top:8px}
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
<button id='menuBtn' onclick='openMenu()'>☰</button>
<button id='routerBtn' onclick='openRouter()'>⋮</button>

<div class='dash'>
<div class='title'>Dashboard</div>

<div class='hero'>
  <div class='small'>Overall Quality</div>
  <div class='big'><span id='quality'>--</span><span style='font-size:22px'>/100</span></div>
  <div id='qualityWord' class='word'>Waiting</div>
  <div class='small'>Router: <span id='routerStateSmall'>Not connected</span></div>
  <div id='ringQuality' class='ring'>--</div>
</div>

<div class='grid'>
  <div class='card'><div class='name'>SINR</div><div class='valLine'><span id='sinr' class='val'>--</span><span class='unit'>dB</span></div><div id='sinrTxt' class='pill'>Waiting</div></div>
  <div class='card'><div class='name'>RSRP</div><div class='valLine'><span id='rsrp' class='val'>--</span><span class='unit'>dBm</span></div><div id='rsrpTxt' class='pill'>Waiting</div></div>
  <div class='card'><div class='name'>RSRQ</div><div class='valLine'><span id='rsrq' class='val' style='color:var(--yellow)'>--</span><span class='unit'>dB</span></div><div id='rsrqTxt' class='pill'>Waiting</div></div>
  <div class='card'><div class='name'>RSSI</div><div class='valLine'><span id='rssi' class='val'>--</span><span class='unit'>dBm</span></div><div id='rssiTxt' class='pill'>Waiting</div></div>
  <div class='card'><div class='name'>Band</div><div class='valLine'><span id='band' class='val'>--</span></div><div id='bandFreq' class='pill'>--</div></div>
  <div class='card'><div class='name'>Best SINR</div><div class='valLine'><span id='best' class='val'>--</span></div><div class='pill'>Peak this survey</div></div>
</div>

<div class='cell'>
  <h3>Connected Cell</h3>
  <div class='cellGrid'>
    <div>EARFCN<b id='earfcn'>--</b></div>
    <div>eNodeB<b id='enodeb'>--</b></div>
    <div>Cell ID<b id='cellid'>--</b></div>
    <div>PCI<b id='pci'>--</b></div>
  </div>
</div>

<div class='statusBar'><div id='status'>Not logged in</div><div id='updated'>Updated --</div></div>
</div>
</section>

<section id='scan' class='screen'>
<img class='bg' src='dashboard_bg.png'>
<button id='menuBtn' onclick='openMenu()'>☰</button>
<button id='routerBtn' onclick='openRouter()'>⋮</button>
<div class='optimiserWrap'>
  <div class='title'>Signal Optimiser</div>
  <div class='optimiserHero'>
    <h1>Find the best signal</h1>
    <p>Turn the antenna slowly. Scout will tell you when the signal improves or if you pass the best point.</p>
    <button class='installerBtn' onclick='openInstallerMode()'>🛠 INSTALLER MODE</button>
  </div>

  <div class='bigSinrBox'>
    <div class='label'>Live SINR</div>
    <div><span id='optSinr' class='num'>--</span><span style='font-size:26px;color:white'> dB</span></div>
    <div id='optHint' class='hint'>Waiting for live data</div>
  </div>

  <div class='guideCard'>
    <div id='optArrow' class='guideArrows'>↔</div>
    <div id='optDirection' class='guideText'>HOLD POSITION</div>
    <div id='optSub' class='guideSub'>Start live reading, then move antenna slowly left and right.</div>
  </div>

  <div class='optimiserGrid'>
    <div class='miniCard'><div class='miniName'>Current RSRP</div><div id='optRsrp' class='miniVal'>--</div></div>
    <div class='miniCard'><div class='miniName'>Current RSRQ</div><div id='optRsrq' class='miniVal'>--</div></div>
    <div class='miniCard'><div class='miniName'>Best SINR</div><div id='optBest' class='miniVal'>--</div></div>
    <div class='miniCard'><div class='miniName'>Band</div><div id='optBand' class='miniVal'>--</div></div>
  </div>
</div>
</section>
<section id='tower' class='screen'><div class='placeholder'><h1>Tower Finder</h1><p>Next screen to build.</p></div></section>
<section id='reports' class='screen'><div class='placeholder'><h1>Reports</h1><p>Next screen to build.</p></div></section>

<div id='drawer' class='drawer'>
  <h1>Scout</h1><h2>Signal Scout</h2>
  <div class='menuItem active' onclick='show("dashboard")'>🏠 Dashboard</div>
  <div class='menuItem' onclick='show("scan")'>📶 Signal Optimiser</div>
  <div class='menuItem' onclick='show("scan")'>🔒 Best Band Lock</div>
  <div class='menuItem' onclick='show("tower")'>🧭 Tower Finder</div>
  <div class='menuItem' onclick='show("scan")'>📈 Live Graphs</div>
  <div class='menuItem' onclick='show("reports")'>📄 Reports</div>
  <div class='menuItem' onclick='openRouter()'>⚙ Router Setup</div>
  <div class='menuItem'>ℹ About</div>
  <div class='menuFoot'>Router: <span id='routerState'>Not connected</span><br>Signal Scout v3.5.5<br>🇬🇧 Pro Locks UK</div>
</div>


<div id='installerOverlay' class='installerOverlay'>
  <div class='installerBg'></div>
  <div class='instTop'>
    <div>Signal Optimiser • Installer Mode</div>
    <button class='exitInst' onclick='closeInstallerMode()'>EXIT</button>
  </div>
  <div class='installerContent'>
    <div id='instLeft' class='instArrow'>⬅</div>
    <div class='instMain'>
      <div id='instTitle' class='instTitle'>HOLD POSITION</div>
      <div><span id='instSinr' class='instSinr'>--</span><span class='instUnit'> dB</span></div>
      <div id='instState' class='instState'>WAITING</div>
      <div id='instSmall' class='instSmall'>Move the antenna slowly. Best: <span id='instBest'>--</span></div>
    </div>
    <div id='instRight' class='instArrow'>➡</div>
  </div>
</div>

<div id='router' class='router'>
<h2>Router Engine</h2>
<input id='url' value='AUTO' placeholder='AUTO or https://hirouter.net'>
<input id='pass' type='password' placeholder='Router admin password'>
<button class='btn' onclick='saveRouter();SignalScout.detectAndLogin()'>Auto Detect + Login</button>
<button class='btn dark' onclick='saveRouter();SignalScout.login()'>Login Current URL</button>
<div class='row'>
<button class='btn dark' onclick='saveRouter();SignalScout.startLive()'>Start</button>
<button class='btn dark' onclick='SignalScout.stopLive()'>Stop</button>
<button class='btn dark' onclick='SignalScout.debugEndpoints()'>Debug</button>
</div>
<button class='btn' onclick='saveRouter();SignalScout.testRead()'>Test Signal Read</button>
<button class='btn dark' onclick='closeAll()'>Close</button>
<div id='log' class='log'>Router log will show here.</div>
<div id='raw' class='raw'>Raw reply will show here.</div>
</div>

</div>
<script>

let optLastSinr = null;
let optBestSinr = null;
let optTrend = 'hold';
let optSamples = [];

function setOptimiserDirection(sinr){
  let n = parseFloat(sinr);
  if(isNaN(n)){
    optTrend='hold';
    setGuide('↔','HOLD POSITION','Waiting for live SINR data.','WAITING');
    return;
  }

  optSamples.push(n);
  if(optSamples.length > 8) optSamples.shift();

  if(optBestSinr === null || n > optBestSinr){
    optBestSinr = n;
    optTrend = 'better';
    setGuide('✅','KEEP GOING','Signal improving - new best found.','IMPROVING');
    return;
  }

  if(optLastSinr !== null){
    let diff = n - optLastSinr;
    let dropFromBest = optBestSinr - n;

    if(dropFromBest <= 0.4){
      optTrend = 'stop';
      setGuide('✅','STOP','You are very close to the best signal found.','BEST');
    } else if(diff < -0.7 && dropFromBest > 1.0){
      optTrend = 'back';
      setGuide('↩','GO BACK','You passed the best point - move back slightly.','GO BACK');
    } else if(diff > 0.4){
      optTrend = 'better';
      setGuide('⬆','KEEP GOING','Signal is improving.','IMPROVING');
    } else {
      optTrend = 'hold';
      setGuide('↔','HOLD POSITION','Small change only - move slowly.','CHECKING');
    }
  }
  optLastSinr = n;
}

function setGuide(arrow, title, sub, state){
  let a=document.getElementById('optArrow'); if(a)a.innerText=arrow;
  let d=document.getElementById('optDirection'); if(d)d.innerText=title;
  let s=document.getElementById('optSub'); if(s)s.innerText=sub;
  let it=document.getElementById('instTitle'); if(it)it.innerText=title;
  let is=document.getElementById('instState'); if(is)is.innerText=state;
  let l=document.getElementById('instLeft'), r=document.getElementById('instRight');
  if(l&&r){
    l.className='instArrow'; r.className='instArrow';
    if(title.includes('GO BACK')){l.className='instArrow active';}
    else if(title.includes('KEEP')){r.className='instArrow active';}
    else if(title.includes('STOP')){l.className='instArrow active'; r.className='instArrow active';}
  }
}

function openInstallerMode(){
  localStorage.setItem('signalScoutPage','scan');
  localStorage.setItem('signalScoutInstaller','0');

  let sinr = document.getElementById('optSinr') ? document.getElementById('optSinr').innerText : '--';
  let best = document.getElementById('optBest') ? document.getElementById('optBest').innerText : '--';
  let status = document.getElementById('optDirection') ? document.getElementById('optDirection').innerText : 'HOLD POSITION';

  let sub = document.getElementById('optSub');
  if(sub) sub.innerText = 'Opening native Installer Mode...';

  SignalScout.openInstallerActivity(String(sinr), String(best), String(status));
}

function fallbackInstallerMode(){
  setStatus('Native Installer Mode required');
}

function closeInstallerMode(){
  localStorage.setItem('signalScoutInstaller','0');
  let overlay = document.getElementById('installerOverlay');
  if(overlay) overlay.classList.remove('active');
  try{SignalScout.portrait();}catch(e){}
  try{document.exitFullscreen();}catch(e){}
  show('scan');
}

function show(id){document.querySelectorAll('.screen').forEach(s=>s.classList.remove('active'));document.getElementById(id).classList.add('active');closeAll()}
function openMenu(){document.getElementById('drawer').classList.add('open');document.getElementById('scrim').classList.add('open')}
function openRouter(){document.getElementById('router').classList.add('open');document.getElementById('scrim').classList.add('open')}
function closeAll(){document.getElementById('drawer').classList.remove('open');document.getElementById('router').classList.remove('open');document.getElementById('scrim').classList.remove('open')}
function saveRouter(){SignalScout.setRouter(document.getElementById('url').value,document.getElementById('pass').value)}
function setStatus(s){document.getElementById('status').innerText=s}
function setRaw(r){document.getElementById('raw').innerText=r}
function clearLog(){document.getElementById('log').innerText=''}
function appendLog(s){document.getElementById('log').innerText += s + '\\n'; document.getElementById('log').scrollTop=document.getElementById('log').scrollHeight}
function setRouterState(s){document.getElementById('routerState').innerText=s;document.getElementById('routerStateSmall').innerText=s}
function updateLive(d){
 setStatus('● '+d.status);
 setRaw(d.raw);
 document.getElementById('quality').innerText=d.quality;
 document.getElementById('ringQuality').innerText=d.quality;
 document.getElementById('qualityWord').innerText=qualityWord(d.quality);
 document.getElementById('rsrp').innerText=numOnly(d.rsrp);
 document.getElementById('sinr').innerText=numOnly(d.sinr);
 document.getElementById('rsrq').innerText=numOnly(d.rsrq);
 document.getElementById('rssi').innerText=numOnly(d.rssi);
 document.getElementById('band').innerText=d.band;
 document.getElementById('bandFreq').innerText=bandFreq(d.band);
 document.getElementById('best').innerText=d.best;
 document.getElementById('pci').innerText=d.pci;
 document.getElementById('earfcn').innerText=d.earfcn;
 document.getElementById('enodeb').innerText=d.enodeb;
 document.getElementById('cellid').innerText=d.cell;
 document.getElementById('updated').innerText='Updated now';
 document.getElementById('rsrpTxt').innerText=rsrpWord(d.rsrp);
 document.getElementById('sinrTxt').innerText=sinrWord(d.sinr);
 document.getElementById('rsrqTxt').innerText=rsrqWord(d.rsrq);
 document.getElementById('rssiTxt').innerText='Live';

 let os=document.getElementById('optSinr'); if(os)os.innerText=numOnly(d.sinr);
 let orp=document.getElementById('optRsrp'); if(orp)orp.innerText=numOnly(d.rsrp);
 let orq=document.getElementById('optRsrq'); if(orq)orq.innerText=numOnly(d.rsrq);
 let ob=document.getElementById('optBest'); if(ob)ob.innerText=d.best;
 let oband=document.getElementById('optBand'); if(oband)oband.innerText=d.band;
 let inst=document.getElementById('instSinr'); if(inst)inst.innerText=numOnly(d.sinr);
 let ib=document.getElementById('instBest'); if(ib)ib.innerText=d.best;
 setOptimiserDirection(d.sinr);
}
function numOnly(v){return (v||'--').replace('dBm','').replace('dB','').trim()}
function qualityWord(q){q=parseInt(q);if(isNaN(q))return'Waiting';if(q>=90)return'Excellent';if(q>=70)return'Good';if(q>=40)return'Needs Improvement';return'Poor'}
function sinrWord(v){v=parseFloat(v);if(isNaN(v))return'Waiting';if(v>=20)return'Excellent';if(v>=10)return'Good';if(v>=0)return'Fair';return'Poor'}
function rsrpWord(v){v=parseFloat(v);if(isNaN(v))return'Waiting';if(v>=-85)return'Excellent';if(v>=-95)return'Good';if(v>=-105)return'Fair';return'Poor'}
function rsrqWord(v){v=parseFloat(v);if(isNaN(v))return'Waiting';if(v>=-10)return'Excellent';if(v>=-13)return'Good';if(v>=-15)return'Fair';return'Poor'}
function bandFreq(b){if(!b||b==='--')return'--';if(b.includes('20'))return'1800 + 800 MHz';if(b.includes('3'))return'1800 MHz';if(b.includes('7'))return'2600 MHz';if(b.includes('1'))return'2100 MHz';return'LTE band'}
</script>
</body>
</html>
""";
    }
}
