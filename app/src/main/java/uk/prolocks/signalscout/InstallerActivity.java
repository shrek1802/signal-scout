package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.content.pm.ActivityInfo;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;

public class InstallerActivity extends Activity {
    int green = Color.rgb(105,255,75);
    int dimGreen = Color.argb(70,105,255,75);
    TextView leftArrow;
    TextView rightArrow;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent i = getIntent();
        String sinr = clean(i.getStringExtra("sinr"));
        String best = clean(i.getStringExtra("best"));
        String state = i.getStringExtra("status");
        if (state == null || state.trim().length() == 0) state = "HOLD POSITION";

        FrameLayout root = new FrameLayout(this);
        root.setBackground(bg());

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), 0, dp(14), 0);

        TextView heading = new TextView(this);
        heading.setText("Signal Optimiser  •  Installer Mode");
        heading.setTextColor(Color.rgb(215,235,240));
        heading.setTextSize(16);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        Button exit = new Button(this);
        exit.setText("EXIT");
        exit.setTextColor(Color.WHITE);
        exit.setTextSize(15);
        exit.setTypeface(Typeface.DEFAULT_BOLD);
        exit.setAllCaps(false);
        exit.setBackground(exitBg());
        exit.setOnClickListener(v -> finish());

        top.addView(heading, new LinearLayout.LayoutParams(0, dp(52), 1f));
        top.addView(exit, new LinearLayout.LayoutParams(dp(96), dp(44)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(60));
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER);
        body.setPadding(dp(30), dp(58), dp(30), dp(22));

        leftArrow = makeArrow("‹");
        rightArrow = makeArrow("›");

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText(state.toUpperCase());
        title.setTextColor(Color.WHITE);
        title.setTextSize(44);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);

        LinearLayout valueLine = new LinearLayout(this);
        valueLine.setGravity(Gravity.CENTER);
        valueLine.setOrientation(LinearLayout.HORIZONTAL);

        TextView value = new TextView(this);
        value.setText(sinr);
        value.setTextColor(green);
        value.setTextSize(82);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false);

        TextView unit = new TextView(this);
        unit.setText(" dB");
        unit.setTextColor(Color.WHITE);
        unit.setTextSize(30);
        unit.setGravity(Gravity.BOTTOM);
        unit.setIncludeFontPadding(false);

        valueLine.addView(value);
        valueLine.addView(unit);

        TextView status = new TextView(this);
        status.setText(stateText(state));
        status.setTextColor(green);
        status.setTextSize(27);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setIncludeFontPadding(false);

        TextView small = new TextView(this);
        small.setText("Move slowly • Best: " + best);
        small.setTextColor(Color.rgb(205,225,230));
        small.setTextSize(18);
        small.setGravity(Gravity.CENTER);

        center.addView(title, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSpace(center, 16);
        center.addView(valueLine, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSpace(center, 14);
        center.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSpace(center, 12);
        center.addView(small, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        body.addView(leftArrow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f));
        body.addView(center, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.60f));
        body.addView(rightArrow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f));

        root.addView(body, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setArrows(state);
        setContentView(root);
    }

    TextView makeArrow(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(210);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setTextColor(dimGreen);
        return v;
    }

    void setArrows(String state) {
        String s = state == null ? "" : state.toUpperCase();
        leftArrow.setTextColor(dimGreen);
        rightArrow.setTextColor(dimGreen);
        if (s.contains("GO BACK") || s.contains("LEFT")) leftArrow.setTextColor(green);
        else if (s.contains("KEEP") || s.contains("RIGHT")) rightArrow.setTextColor(green);
        else if (s.contains("STOP") || s.contains("BEST")) {
            leftArrow.setTextColor(green);
            rightArrow.setTextColor(green);
        }
    }

    String stateText(String s) {
        if (s == null) return "WAITING";
        s = s.toUpperCase();
        if (s.contains("KEEP")) return "IMPROVING";
        if (s.contains("STOP")) return "BEST SIGNAL";
        if (s.contains("GO BACK")) return "GO BACK";
        return "WAITING";
    }

    String clean(String v) {
        if (v == null) return "--";
        v = v.replace("dBm","").replace("dB","").trim();
        return v.length() == 0 ? "--" : v;
    }

    GradientDrawable bg() {
        return new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{Color.rgb(2,9,18), Color.rgb(4,30,38), Color.rgb(2,9,18)}
        );
    }

    GradientDrawable exitBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(105,255,255,255));
        g.setCornerRadius(dp(14));
        return g;
    }

    void addSpace(LinearLayout l, int h) {
        Space s = new Space(this);
        l.addView(s, new LinearLayout.LayoutParams(1, dp(h)));
    }

    int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
