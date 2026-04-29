package com.hermes.phonebridge;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.accessibility.AccessibilityManager;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_ALL_DANGEROUS = 1000;
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 102;

    // Permissions that require user to manually enable in settings
    private static final Set<String> SETTINGS_PERMISSIONS = new HashSet<>(Arrays.asList(
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        "android.permission.PACKAGE_USAGE_STATS"
    ));

    // Dangerous permissions grouped by category
    private static final String[][] PERMISSION_GROUPS = {
        {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            "android.permission.MANAGE_EXTERNAL_STORAGE"
        },
        {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        },
        {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        },
        {
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG
        },
        {
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.WRITE_SMS
        },
        {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        },
        {
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        },
        {
            Manifest.permission.POST_NOTIFICATIONS
        },
        {
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        }
    };

    private static final String[] PERMISSION_GROUP_NAMES = {
        "存储 / 文件",
        "相机 / 麦克风",
        "位置",
        "电话 / 通话",
        "短信",
        "联系人",
        "日历",
        "通知",
        "蓝牙"
    };

    private static final String[] DANGEROUS_PERMISSIONS;

    static {
        List<String> all = new ArrayList<>();
        for (String[] group : PERMISSION_GROUPS) {
            for (String p : group) all.add(p);
        }
        DANGEROUS_PERMISSIONS = all.toArray(new String[0]);
    }

    private LinearLayout permissionsContainer;
    private TextView tvAccessibilityStatus;
    private TextView tvOverlayStatus;
    private TextView tvProjectionStatus;
    private TextView tvNotificationStatus;
    private TextView tvUsageStatsStatus;
    private Spinner spinnerPort;

    public static int mediaProjectionResultCode = -1;
    public static Intent mediaProjectionData = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        initViews();
        setupListeners();
        updateAllStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAllStatus();
    }

    private void initViews() {
        permissionsContainer = findViewById(R.id.permissions_container);
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        tvProjectionStatus = findViewById(R.id.tv_projection_status);
        tvNotificationStatus = findViewById(R.id.tv_notification_status);
        tvUsageStatsStatus = findViewById(R.id.tv_usage_stats_status);
        spinnerPort = findViewById(R.id.spinner_port);

        String[] ports = {"7890", "7891", "7892", "8080"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_dropdown_item, ports);
        spinnerPort.setAdapter(adapter);

        int savedPort = getSharedPreferences("phonebridge", MODE_PRIVATE)
            .getInt("http_port", 7890);
        for (int i = 0; i < ports.length; i++) {
            if (String.valueOf(savedPort).equals(ports[i])) {
                spinnerPort.setSelection(i);
                break;
            }
        }

        buildPermissionRows();
    }

    private void buildPermissionRows() {
        permissionsContainer.removeAllViews();
        String[] labels = PERMISSION_GROUP_NAMES;

        for (int g = 0; g < PERMISSION_GROUPS.length; g++) {
            final String[] group = PERMISSION_GROUPS[g];
            String label = labels[g];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 16, 0, 16);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextSize(16);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button btn = new Button(this);
            btn.setText("授权");
            btn.setOnClickListener(v -> {
                requestPermissionsGroup(group);
            });

            row.addView(tv);
            row.addView(btn);
            permissionsContainer.addView(row);

            View divider = new View(this);
            divider.setBackgroundColor(0xFFE0E0E0);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divider.setLayoutParams(divParams);
            permissionsContainer.addView(divider);
        }
    }

    private void setupListeners() {
        findViewById(R.id.btn_request_permissions).setOnClickListener(v -> requestAllDangerousPermissions());
        findViewById(R.id.btn_open_settings).setOnClickListener(v -> openAppSettings());
        findViewById(R.id.btn_start_service).setOnClickListener(v -> {
            String selectedPort = (String) spinnerPort.getSelectedItem();
            getSharedPreferences("phonebridge", MODE_PRIVATE)
                .edit().putInt("http_port", Integer.parseInt(selectedPort)).apply();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        findViewById(R.id.btn_stop_service).setOnClickListener(v -> {
            stopService(new Intent(this, PhoneBridgeService.class));
            updateAllStatus();
        });

        tvAccessibilityStatus.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        tvOverlayStatus.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION_URI,
                Uri.parse("package:" + getPackageName()))));

        tvNotificationStatus.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        tvUsageStatsStatus.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        tvProjectionStatus.setOnClickListener(v -> requestMediaProjection());
    }

    private void updateAllStatus() {
        boolean accEnabled = isAccessibilityServiceEnabled(this, PhoneBridgeService.class);
        tvAccessibilityStatus.setText(accEnabled ? "✅ 已启用" : "⚠️ 未启用");
        tvAccessibilityStatus.setTextColor(accEnabled ? 0xFF4CAF50 : 0xFFFF9800);
        findViewById(R.id.btn_start_service).setVisibility(accEnabled ? View.GONE : View.VISIBLE);
        findViewById(R.id.btn_stop_service).setVisibility(accEnabled ? View.VISIBLE : View.GONE);

        boolean overlay = Settings.canDrawOverlays(this);
        tvOverlayStatus.setText(overlay ? "✅ 已授权" : "⚠️ 需授权");
        tvOverlayStatus.setTextColor(overlay ? 0xFF4CAF50 : 0xFFFF9800);

        boolean hasProjection = (mediaProjectionResultCode != -1 && mediaProjectionData != null);
        tvProjectionStatus.setText(hasProjection ? "✅ 已授权" : "⚠️ 需授权（截图用）");
        tvProjectionStatus.setTextColor(hasProjection ? 0xFF4CAF50 : 0xFFFF9800);

        boolean notifEnabled = isNotificationListenerEnabled();
        tvNotificationStatus.setText(notifEnabled ? "✅ 已启用" : "⚠️ 未启用");
        tvNotificationStatus.setTextColor(notifEnabled ? 0xFF4CAF50 : 0xFFFF9800);

        boolean usageStats = hasUsageStatsPermission();
        tvUsageStatsStatus.setText(usageStats ? "✅ 已授权" : "⚠️ 需授权（UI分析）");
        tvUsageStatsStatus.setTextColor(usageStats ? 0xFF4CAF50 : 0xFFFF9800);
    }

    private void requestAllDangerousPermissions() {
        List<String> toRequest = new ArrayList<>();
        for (String p : DANGEROUS_PERMISSIONS) {
            if (!SETTINGS_PERMISSIONS.contains(p) &&
                ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (toRequest.isEmpty()) {
            Toast.makeText(this, "所有运行时权限已授权", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityCompat.requestPermissions(this,
            toRequest.toArray(new String[0]), REQUEST_ALL_DANGEROUS);
    }

    private void requestPermissionsGroup(String[] group) {
        List<String> toRequest = new ArrayList<>();
        for (String p : group) {
            if (!SETTINGS_PERMISSIONS.contains(p) &&
                ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (toRequest.isEmpty()) {
            Toast.makeText(this, "此权限组已全部授权", Toast.LENGTH_SHORT).show();
            return;
        }
        ActivityCompat.requestPermissions(this,
            toRequest.toArray(new String[0]), REQUEST_ALL_DANGEROUS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int granted = 0, denied = 0;
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) granted++;
            else denied++;
        }
        if (denied == 0) {
            Toast.makeText(this, "全部授权成功 ✓", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "完成: " + granted + " 成功, " + denied + " 被拒",
                Toast.LENGTH_LONG).show();
        }
        updateAllStatus();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName())));
    }

    private void requestMediaProjection() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                mediaProjectionResultCode = resultCode;
                mediaProjectionData = data;
                PhoneBridgeService.updateMediaProjection(resultCode, data);
                Toast.makeText(this, "截屏授权成功 ✓", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "截屏授权被取消", Toast.LENGTH_SHORT).show();
            }
        }
        updateAllStatus();
    }

    private boolean isAccessibilityServiceEnabled(Context ctx, Class<?> serviceClass) {
        AccessibilityManager am = (AccessibilityManager)
            ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : enabled) {
            if (info.getResolveInfo().getComponentName().getClassName()
                    .equals(serviceClass.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNotificationListenerEnabled() {
        ComponentName cn = new ComponentName(this, NotificationInterceptorService.class);
        String flat = Settings.Secure.getString(getContentResolver(),
            "enabled_notification_listeners");
        return flat != null && flat.contains(cn.flattenToString());
    }

    private boolean hasUsageStatsPermission() {
        try {
            android.app.AppOpsManager ao = (android.app.AppOpsManager)
                getSystemService(Context.APP_OPS_SERVICE);
            int mode = ao.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }
}
