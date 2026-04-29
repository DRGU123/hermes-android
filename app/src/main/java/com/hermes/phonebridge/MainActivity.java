package com.hermes.phonebridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView statusText;
    private Button enableBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setPadding(60, 60, 60, 20);

        enableBtn = new Button(this);
        enableBtn.setText("Enable Accessibility Service");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(statusText);
        layout.addView(enableBtn);
        setContentView(layout);

        enableBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        boolean enabled = am.isAccessibilityServiceEnabled(
            new android.content.ComponentName(this, PhoneBridgeService.class)
        );
        if (enabled) {
            statusText.setText("✅ PhoneBridge Service is active\n\nOpen Hermes Agent and it will connect to this phone.\n\nHTTP API available at:\nhttp://localhost:7890\n\nEndpoints:\n  GET  /status\n  POST /click   {x, y}\n  POST /swipe   {x1, y1, x2, y2, duration}\n  POST /input   {text}\n  POST /press   {keycode}\n  POST /global_action {action}\n  GET  /hierarchy");
            enableBtn.setVisibility(View.GONE);
        } else {
            statusText.setText("❌ Accessibility Service not enabled\n\nTap the button below to open Android settings and enable PhoneBridge.");
            enableBtn.setVisibility(View.VISIBLE);
        }
    }
}
