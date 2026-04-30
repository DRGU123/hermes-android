package com.hermes.phonebridge;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Base64;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.nio.ByteBuffer;

public class PhoneBridgeService extends AccessibilityService {

    public static PhoneBridgeService instance;
    private NanoHttpd httpServer;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int httpPort = 7890;

    // MediaProjection for screenshots
    private static int sMediaProjectionResultCode = -1;
    private static Intent sMediaProjectionData = null;
    private MediaProjection sMediaProjection;
    private VirtualDisplay sVirtualDisplay;
    private ImageReader sImageReader;
    private int sScreenWidth = 1080;
    private int sScreenHeight = 1920;
    private int sScreenDensity = 420;

    public static void updateMediaProjection(int resultCode, Intent data) {
        sMediaProjectionResultCode = resultCode;
        sMediaProjectionData = data;
    }

    public static PhoneBridgeService getInstance() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;

        // Get screen dimensions
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        sScreenWidth = dm.widthPixels;
        sScreenHeight = dm.heightPixels;
        sScreenDensity = dm.densityDpi;

        // Restore saved port
        int savedPort = getSharedPreferences("phonebridge", Context.MODE_PRIVATE)
            .getInt("http_port", 7890);
        httpPort = savedPort;

        // Re-init MediaProjection if we have saved credentials
        if (sMediaProjectionResultCode != -1 && sMediaProjectionData != null) {
            initMediaProjection();
        }

        startHttpServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (httpServer != null) httpServer.stop();
        releaseMediaProjection();
        executor.shutdown();
    }

    // ══════════════════════════════════════════════════════════════════
    // HTTP ROUTER
    // ══════════════════════════════════════════════════════════════════

    private NanoHttpd.Response route(String method, String uri, String body) {
        try {
            Map<String, String> params = parseQuery(uri);
            String path = uri.split("\\?")[0];

            if ("GET".equals(method) && "/status".equals(path)) {
                return json(200, "{\"ok\":true,\"service\":\"PhoneBridge\"}");
            }
            if ("GET".equals(method) && "/ping".equals(path)) {
                boolean ready = (instance != null);
                String winState = "no_instance";
                if (ready) {
                    AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                    winState = (root != null) ? "active" : "inactive";
                    if (root != null) root.recycle();
                }
                return json(200, "{\"ping\":true,\"instance\":" + ready + ",\"window\":\"" + winState + "\"}");
            }
            if ("POST".equals(method) && "/click".equals(path)) {
                return handleClick(body);
            }
            if ("POST".equals(method) && "/swipe".equals(path)) {
                return handleSwipe(body);
            }
            if ("POST".equals(method) && "/input".equals(path)) {
                return handleInput(body);
            }
            if (("POST".equals(method) || "GET".equals(method)) && path.startsWith("/press")) {
                String keycode = params.containsKey("keycode") ? params.get("keycode") : null;
                return handlePress(body, keycode);
            }
            if ("POST".equals(method) && "/global_action".equals(path)) {
                return handleGlobalAction(body);
            }
            if ("GET".equals(method) && "/hierarchy".equals(path)) {
                return handleHierarchy();
            }
            if ("GET".equals(method) && "/screenshot".equals(path)) {
                return handleScreenshot();
            }
            return json(404, "{\"error\":\"unknown_command\"}");
        } catch (Exception e) {
            return json(400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // COMMAND HANDLERS
    // ══════════════════════════════════════════════════════════════════

    private NanoHttpd.Response handleClick(String body) throws Exception {
        int[] p = parseIntParams(body, "x", "y");
        final boolean[] ok = {false};
        mainHandler.post(() -> {
            ok[0] = clickAt(p[0], p[1]);
        });
        Thread.sleep(300);
        return json(200, "{\"success\":" + ok[0] + "}");
    }

    private NanoHttpd.Response handleSwipe(String body) throws Exception {
        int[] p = parseIntParams(body, "x1", "y1", "x2", "y2");
        int duration = parseIntParam(body, "duration", 300);
        final boolean[] ok = {false};
        mainHandler.post(() -> {
            ok[0] = swipe(p[0], p[1], p[2], p[3], duration);
        });
        Thread.sleep(duration + 200);
        return json(200, "{\"success\":" + ok[0] + "}");
    }

    private NanoHttpd.Response handleInput(String body) throws Exception {
        String text = parseTextParam(body, "text");
        if (text == null) return json(400, "{\"error\":\"missing_text\"}");
        final boolean[] ok = {false};
        mainHandler.post(() -> {
            ok[0] = inputText(text);
        });
        Thread.sleep(200);
        return json(200, "{\"success\":" + ok[0] + "}");
    }

    private NanoHttpd.Response handlePress(String body, String keycodeParam) throws Exception {
        String keyStr = keycodeParam != null ? keycodeParam : parseTextParam(body, "keycode");
        if (keyStr == null) return json(400, "{\"error\":\"missing_keycode\"}");
        int keycode = Integer.parseInt(keyStr);
        final boolean[] ok = {false};
        mainHandler.post(() -> {
            ok[0] = performGlobalAction(keycode);
        });
        Thread.sleep(150);
        return json(200, "{\"success\":" + ok[0] + "}");
    }

    private NanoHttpd.Response handleGlobalAction(String body) throws Exception {
        String action = parseTextParam(body, "action");
        int code = globalActionCode(action);
        final boolean[] ok = {false};
        mainHandler.post(() -> {
            ok[0] = performGlobalAction(code);
        });
        Thread.sleep(150);
        return json(200, "{\"success\":" + ok[0] + "}");
    }

    private NanoHttpd.Response handleHierarchy() {
        final String[] result = {""};
        mainHandler.post(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            result[0] = nodeToJson(root, 0);
            if (root != null) root.recycle();
        });
        try { Thread.sleep(500); } catch (Exception e) {}
        return json(200, "{\"hierarchy\":" + result[0] + "}");
    }

    private NanoHttpd.Response handleScreenshot() {
        if (sMediaProjection == null) {
            return json(200, "{\"error\":\"media_projection_not_granted\",\"hint\":\"Open app and tap '截屏投影' to authorize\"}");
        }
        Bitmap bmp = captureScreenshot();
        if (bmp == null) {
            return json(500, "{\"error\":\"screenshot_failed\"}");
        }
        String base64;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
            base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            bmp.recycle();
        } catch (Exception e) {
            return json(500, "{\"error\":\"encoding_failed\"}");
        }
        return new NanoHttpd.Response(200, "application/json",
            "{\"screenshot\":\"data:image/png;base64," + base64 + "\"}");
    }

    // ══════════════════════════════════════════════════════════════════
    // SCREENSHOT — MediaProjection
    // ══════════════════════════════════════════════════════════════════

    private synchronized void initMediaProjection() {
        if (sMediaProjectionData == null) return;
        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            sMediaProjection = mpm.getMediaProjection(sMediaProjectionResultCode, sMediaProjectionData);
            sImageReader = ImageReader.newInstance(sScreenWidth, sScreenHeight, PixelFormat.RGBA_8888, 2);
            sVirtualDisplay = sMediaProjection.createVirtualDisplay(
                "PhoneBridge",
                sScreenWidth, sScreenHeight, sScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                sImageReader.getSurface(),
                null, mainHandler);
        } catch (Exception e) {
            android.util.Log.e("PhoneBridge", "initMediaProjection failed", e);
            sMediaProjection = null;
        }
    }

    private synchronized void releaseMediaProjection() {
        if (sVirtualDisplay != null) {
            sVirtualDisplay.release();
            sVirtualDisplay = null;
        }
        if (sImageReader != null) {
            sImageReader.close();
            sImageReader = null;
        }
        if (sMediaProjection != null) {
            sMediaProjection.stop();
            sMediaProjection = null;
        }
    }

    private synchronized Bitmap captureScreenshot() {
        if (sImageReader == null) return null;
        Image image = null;
        try {
            image = sImageReader.acquireLatestImage();
            if (image == null) {
                // Try acquireNextImage as fallback
                image = sImageReader.acquireNextImage();
            }
            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * sScreenWidth;

            Bitmap bitmap = Bitmap.createBitmap(sScreenWidth + rowPadding / pixelStride,
                sScreenHeight, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop to actual screen size
            if (rowPadding > 0) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, sScreenWidth, sScreenHeight);
            }
            return bitmap;
        } catch (Exception e) {
            android.util.Log.e("PhoneBridge", "captureScreenshot failed", e);
            return null;
        } finally {
            if (image != null) image.close();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // CORE ACCESSIBILITY OPERATIONS
    // ══════════════════════════════════════════════════════════════════

    private boolean clickAt(int x, int y) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(x, y);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        return dispatchGesture(builder.build(), null, mainHandler);
    }

    private boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, mainHandler);
    }

    private boolean inputText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = findInputField(root);
        if (root != null) root.recycle();
        if (focused == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", text);
        boolean ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        focused.recycle();
        return ok;
    }

    private AccessibilityNodeInfo findInputField(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused() && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findInputField(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════

    private int globalActionCode(String action) {
        if (action == null) return GLOBAL_ACTION_BACK;
        switch (action) {
            case "back":      return GLOBAL_ACTION_BACK;
            case "home":      return GLOBAL_ACTION_HOME;
            case "recents":   return GLOBAL_ACTION_RECENTS;
            case "notifications": return GLOBAL_ACTION_NOTIFICATIONS;
            case "quick_settings": return GLOBAL_ACTION_QUICK_SETTINGS;
            case "power":     return GLOBAL_ACTION_POWER_DIALOG;
            case "lock":      return GLOBAL_ACTION_LOCK_SCREEN;
            case "screenshot": return GLOBAL_ACTION_TAKE_SCREENSHOT;
            default:          return GLOBAL_ACTION_BACK;
        }
    }

    private String nodeToJson(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"class\":\"" + escapeJson(node.getClassName() != null ? node.getClassName().toString() : "") + "\",");
        sb.append("\"text\":\"" + escapeJson(node.getText() != null ? node.getText().toString() : "") + "\",");
        sb.append("\"desc\":\"" + escapeJson(node.getContentDescription() != null ? node.getContentDescription().toString() : "") + "\",");
        sb.append("\"enabled\":" + node.isEnabled() + ",");
        sb.append("\"clickable\":" + node.isClickable() + ",");
        sb.append("\"scrollable\":" + node.isScrollable() + ",");
        sb.append("\"focused\":" + node.isFocused() + ",");
        android.graphics.Rect r = new android.graphics.Rect();
        node.getBoundsInScreen(r);
        sb.append("\"bounds\":{\"top\":" + r.top + ",\"left\":" + r.left + ",\"right\":" + r.right + ",\"bottom\":" + r.bottom + "},");
        sb.append("\"children\":[");
        for (int i = 0; i < node.getChildCount(); i++) {
            if (i > 0) sb.append(",");
            AccessibilityNodeInfo child = node.getChild(i);
            sb.append(nodeToJson(child, depth + 1));
            if (child != null) child.recycle();
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private NanoHttpd.Response json(int status, String body) {
        return new NanoHttpd.Response(status, "application/json", body);
    }

    private Map<String, String> parseQuery(String uri) {
        Map<String, String> m = new HashMap<>();
        if (!uri.contains("?")) return m;
        try {
            String qs = uri.split("\\?")[1];
            for (String pair : qs.split("&")) {
                String[] kv = pair.split("=", 2);
                m.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", "UTF-8"));
            }
        } catch (Exception e) {}
        return m;
    }

    private int parseIntParam(String body, String key, int def) {
        try {
            String v = parseTextParam(body, key);
            return v != null ? Integer.parseInt(v) : def;
        } catch (Exception e) { return def; }
    }

    private int[] parseIntParams(String body, String... keys) throws Exception {
        int[] out = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String v = parseTextParam(body, keys[i]);
            if (v == null) throw new Exception("missing " + keys[i]);
            out[i] = Integer.parseInt(v);
        }
        return out;
    }

    private String parseTextParam(String body, String key) {
        if (body == null) return null;
        try {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|\\d+|true|false)");
            Matcher m = p.matcher(body);
            if (m.find()) {
                String v = m.group(1);
                if (v.startsWith("\"")) v = v.substring(1, v.length() - 1);
                return v;
            }
        } catch (Exception e) {}
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    // NANO HTTP SERVER
    // ══════════════════════════════════════════════════════════════════

    private void startHttpServer() {
        executor.execute(() -> {
            try {
                httpServer = new NanoHttpd(httpPort, executor) {
                    @Override
                    protected Response handle(String method, String uri, Map<String, String> headers, String body) {
                        return route(method, uri, body);
                    }
                };
                httpServer.run();
            } catch (IOException e) {
                android.util.Log.e("PhoneBridge", "Failed to start HTTP server on port " + httpPort, e);
            }
        });
    }

    public abstract static class NanoHttpd implements Runnable {
        private final int port;
        private final ExecutorService exec;
        private volatile ServerSocket serverSocket;
        private volatile boolean alive = true;

        public NanoHttpd(int port, ExecutorService exec) throws IOException {
            this.port = port;
            this.exec = exec;
            this.serverSocket = new ServerSocket(port);
        }

        @Override
        public void run() {
            android.util.Log.i("NanoHttpd", "Listening on port " + port);
            while (alive) {
                try {
                    final Socket client = serverSocket.accept();
                    exec.execute(() -> serveClient(client));
                } catch (IOException e) {
                    if (alive) android.util.Log.e("NanoHttpd", "accept error", e);
                }
            }
        }

        private void serveClient(Socket client) {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String requestLine = in.readLine();
                if (requestLine == null) { client.close(); return; }

                StringTokenizer st = new StringTokenizer(requestLine);
                String method = st.nextToken();
                String uri = URLDecoder.decode(st.nextToken(), "UTF-8");

                Map<String, String> headers = new HashMap<>();
                int contentLength = 0;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim().toLowerCase();
                        headers.put(k, line.substring(colon + 1).trim());
                        if ("content-length".equals(k))
                            contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }

                StringBuilder bodyBuilder = new StringBuilder();
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int r = in.read(buf, 0, contentLength);
                    if (r > 0) bodyBuilder.append(new String(buf, 0, r));
                }

                Response resp = handle(method, uri, headers, bodyBuilder.toString());

                PrintWriter out = new PrintWriter(client.getOutputStream());
                out.print("HTTP/1.1 " + resp.status + " OK\r\n");
                out.print("Content-Type: " + resp.mimeType + "\r\n");
                out.print("Content-Length: " + resp.bodyBytes().length + "\r\n");
                out.print("Access-Control-Allow-Origin: *\r\n");
                out.print("Connection: close\r\n");
                out.print("\r\n");
                out.write(new String(resp.bodyBytes()));
                out.flush();
                client.close();
            } catch (Exception e) {
                android.util.Log.e("NanoHttpd", "serve error", e);
            }
        }

        public void stop() {
            alive = false;
            try { serverSocket.close(); } catch (IOException e) {}
        }

        protected abstract Response handle(String method, String uri, Map<String, String> headers, String body);

        public static class Response {
            public final int status;
            public final String mimeType;
            public final String body;

            public Response(int status, String mimeType, String body) {
                this.status = status;
                this.mimeType = mimeType;
                this.body = body;
            }

            public byte[] bodyBytes() {
                try { return body.getBytes("UTF-8"); }
                catch (UnsupportedEncodingException e) { return body.getBytes(); }
            }
        }
    }
}
