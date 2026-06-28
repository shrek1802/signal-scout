package uk.prolocks.signalscout;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.pm.ActivityInfo;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;

public class InstallerActivity extends Activity {
    TextView title;
    TextView sinr;
    TextView best;
    TextView status;
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
        String sinrVal = i.getStringExtra("sinr");
        String bestVal = i.getStringExtra("best");
        String statusVal = i.getStringExtra("status");

        if (sinrVal == null || sinrVal.trim().length() == 0) sinrVal = "--";
        if (bestVal == null || bestVal.trim().length() == 0) bestVal = "--";
        if (statusVal == null || statusVal.trim().length() == 0) statusVal = "HOLD POSITION";

        FrameLayout root = new FrameLayout(this);
        root.setBackground(makeBackground());

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER);
        main.setPadding(dp(24), dp(32), dp(24), dp(24));

        FrameLayout.LayoutParams mainLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        root.addView(main, mainLp);

        leftArrow = arrowView("◀");
        rightArrow = arrowView("▶");

        LinearLayout centre = new LinearLayout(this);
        centre.setOrientation(LinearLayout.VERTICAL);
        centre.setGravity(Gravity.CENTER);
        centre.setPadding(dp(20), 0, dp(20), 0);

        title = new TextView(this);
        title.setText(statusVal.toUpperCase());
        title.setTextColor(Color.WHITE);
        title.setTextSize(42);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);

        LinearLayout sinrLine = new LinearLayout(this);
        sinrLine.setGravity(Gravity.CENTER);
        sinrLine.setOrientation(LinearLayout.HORIZONTAL);

        sinr = new TextView(this);
        sinr.setText(clean(sinrVal));
        sinr.setTextColor(Color.rgb(105,255,75));
        sinr.setTextSize(76);
        sinr.setTypeface(Typeface.DEFAULT_BOLD);
        sinr.setGravity(Gravity.CENTER);
        sinr.setIncludeFontPadding(false);

        TextView unit = new TextView(this);
        unit.setText(" dB");
        unit.setTextColor(Color.WHITE);
        unit.setTextSize(30);
        unit.setGravity(Gravity.BOTTOM);
        unit.setIncludeFontPadding(false);

        sinrLine.addView(sinr);
        sinrLine.addView(unit);

        status = new TextView(this);
        status.setText(stateText(statusVal));
        status.setTextColor(Color.rgb(105,255,75));
        status.setTextSize(27);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setIncludeFontPadding(false);

        best = new TextView(this);
        best.setText("Move slowly • Best: " + clean(bestVal));
        best.setTextColor(Color.rgb(205,225,230));
        best.setTextSize(18);
        best.setGravity(Gravity.CENTER);

        centre.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSpace(centre, 18);
        centre.addView(sinrLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSpace(centre, 18);
        centre.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addSpace(centre, 12);
        centre.addView(best, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        main.addView(leftArrow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.75f));
        main.addView(centre, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.55f));
        main.addView(rightArrow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.75f));

        TextView header = new TextView(this);
        header.setText("Signal Optimiser  •  Installer Mode");
        header.setTextColor(Color.rgb(210,230,235));
        header.setTextSize(16);
        header.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(44));
        hp.leftMargin = dp(16);
        hp.topMargin = dp(8);
        root.addView(header, hp);

        Button exit = new Button(this);
        exit.setText("EXIT");
        exit.setTextColor(Color.WHITE);
        exit.setTextSize(16);
        exit.setTypeface(Typeface.DEFAULT_BOLD);
        exit.setAllCaps(false);
        exit.setBackground(buttonBg());
        exit.setOnClickListener(v -> finish());

        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(dp(96), dp(48));
        ep.gravity = Gravity.RIGHT | Gravity.TOP;
        ep.rightMargin = dp(16);
        ep.topMargin = dp(8);
        root.addView(exit, ep);

        setArrowState(statusVal);

        setContentView(root);
    }

    TextView arrowView(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(112);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.argb(75,105,255,75));
        v.setIncludeFontPadding(false);
        return v;
    }

    void setArrowState(String state) {
        String s = state == null ? "" : state.toUpperCase();
        int green = Color.rgb(105,255,75);
        int dim = Color.argb(65,105,255,75);
        int red = Color.rgb(255,74,61);

        leftArrow.setTextColor(dim);
        rightArrow.setTextColor(dim);

        if (s.contains("GO BACK") || s.contains("LEFT")) {
            leftArrow.setTextColor(green);
        } else if (s.contains("KEEP") || s.contains("RIGHT")) {
            rightArrow.setTextColor(green);
        } else if (s.contains("STOP") || s.contains("BEST")) {
            leftArrow.setTextColor(green);
            rightArrow.setTextColor(green);
        } else if (s.contains("WRONG")) {
            leftArrow.setTextColor(red);
            rightArrow.setTextColor(red);
        }
    }

    String clean(String v) {
        if (v == null) return "--";
        return v.replace("dB","").replace("dBm","").trim();
    }

    String stateText(String s) {
        if (s == null) return "WAITING";
        s = s.toUpperCase();
        if (s.contains("KEEP")) return "IMPROVING";
        if (s.contains("STOP")) return "BEST SIGNAL";
        if (s.contains("GO BACK")) return "GO BACK";
        return "WAITING";
    }

    GradientDrawable makeBackground() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(2,9,18), Color.rgb(4,30,38), Color.rgb(2,9,18)}
        );
        return g;
    }

    GradientDrawable buttonBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(90,255,255,255));
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
