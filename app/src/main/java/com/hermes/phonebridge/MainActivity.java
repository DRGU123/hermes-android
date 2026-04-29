package com.hermes.phonebridge;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.util.List;

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
        ComponentName serviceName = new ComponentName(this, PhoneBridgeService.class);
        String flatName = serviceName.flattenToShortString();

        boolean enabled = false;
        try {
            List<android.accessibilityservice.AccessibilityServiceInfo> services =
                am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (android.accessibilityservice.AccessibilityServiceInfo svc : services) {
                ComponentName cn = ComponentName.unflattenFromString(svc.getId());
                if (cn != null && cn.equals(serviceName)) {
                    enabled = true;
                    break;
                }
            }
        } catch (Exception e) {
            enabled = false;
        }

        if (enabled) {
            statusText.setText(
                "✅ PhoneBridge Service is active\n\n" +
                "Hermes Agent can now control this phone.\n\n" +
                "HTTP API: http://localhost:7890\n\n" +
                "Endpoints:\n" +
                "  GET  /status\n" +
                "  POST /click   {\"x\":100,\"y\":200}\n" +
                "  POST /swipe   {\"x1\":0,\"y1\":0,\"x2\":500,\"y2\":1000}\n" +
                "  POST /input   {\"text\":\"hello\"}\n" +
                "  POST /press   {\"keycode\":4}\n" +
                "  POST /global_action {\"action\":\"home\"}\n" +
                "  GET  /hierarchy\n" +
                "  GET  /screenshot (requires extra permission)"
            );
            enableBtn.setVisibility(View.GONE);
        } else {
            statusText.setText(
                "❌ Accessibility Service not enabled\n\n" +
                "Tap the button below to open Android settings,\n" +
                "then find and enable \"Hermes Phone Bridge\"."
            );
            enableBtn.setVisibility(View.VISIBLE);
        }
    }
}
