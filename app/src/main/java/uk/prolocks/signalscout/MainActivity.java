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

        row2.addView(rssi
