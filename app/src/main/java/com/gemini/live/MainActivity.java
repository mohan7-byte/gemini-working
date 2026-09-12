package com.gemini.live;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;

public final class MainActivity extends Activity implements NativeGeminiClient.Listener {
    static MainActivity instance;
    private static final int REQUEST_AUDIO = 41;
    private static final int REQUEST_CONTACTS = 42;
    private static final int REQUEST_CALL = 43;
    private static final int REQUEST_SMS = 44;
    private static final int REQUEST_CAPTURE = 45;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private NativeGeminiClient client;
    private NativeVoiceView voiceView;
    private String lastRulesJson = "{}";

    private static final String DEFAULT_PROMPT =
            "You are Voice, a fast autonomous Android agent with direct device control and real-time web intelligence. " +
            "Use search_internet for current facts and news. Read the screen before interacting with another app. " +
            "Prefer element IDs, use replace_text for editing fields, and keep confirmations concise. " +
            "Execute device actions in sequence and do not narrate intermediate steps unless needed.";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        prefs = getSharedPreferences("voice_native", MODE_PRIVATE);
        Window w = getWindow();
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        w.setStatusBarColor(0xFF080A10);
        w.setNavigationBarColor(0xFF080A10);
        voiceView = new NativeVoiceView(this);
        setContentView(voiceView);
        loadRules();
        if (prefs.getBoolean("auto_connect", false) && !prefs.getString("api_key", "").isEmpty()) {
            main.postDelayed(this::startSession, 300);
        }
    }

    private void startSession() {
        if (client != null) return;
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) { showSettings(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        voiceView.setState("connecting", "Connecting", "Native audio");
        client = new NativeGeminiClient(this, key,
                prefs.getString("model", "models/gemini-3.1-flash-live-preview"),
                prefs.getString("voice", "Aoede"),
                prefs.getString("prompt", DEFAULT_PROMPT),
                prefs.getBoolean("echo_guard", true));
        client.start();
    }

    private void stopSession() {
        NativeGeminiClient c = client;
        client = null;
        if (c != null) c.stop();
        if (voiceView != null) voiceView.setState("idle", "Voice", "Ready");
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18); box.setPadding(pad, dp(10), pad, pad);
        scroll.addView(box);

        TextView title = new TextView(this);
        title.setText("Voice • Native configuration");
        title.setTextSize(20); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(title);

        EditText key = field("Gemini API Key", prefs.getString("api_key", "")); key.setInputType(0x81); box.addView(key);
        EditText model = field("Model", prefs.getString("model", "models/gemini-3.1-flash-live-preview")); box.addView(model);
        EditText voice = field("Voice", prefs.getString("voice", "Aoede")); box.addView(voice);
        EditText prompt = field("Assistant Instructions", prefs.getString("prompt", DEFAULT_PROMPT)); prompt.setMinLines(5); box.addView(prompt);

        CheckBox auto = new CheckBox(this); auto.setText("Auto-connect after launch"); auto.setChecked(prefs.getBoolean("auto_connect", false)); box.addView(auto);
        CheckBox echo = new CheckBox(this); echo.setText("Speaker echo guard"); echo.setChecked(prefs.getBoolean("echo_guard", true)); box.addView(echo);
        TextView access = new TextView(this); access.setText("⚡ Open Accessibility settings"); access.setTextSize(16); access.setPadding(0, dp(12), 0, dp(12));
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); box.addView(access);

        TextView memory = new TextView(this);
        memory.setText("Native mode: WebView/Chromium/JavaScript are not used. Audio and screen capture are created only when needed.");
        memory.setTextSize(13); box.addView(memory);

        EditText rules = field("App playbooks JSON", lastRulesJson); rules.setMinLines(8); box.addView(rules);
        TextView saveRules = new TextView(this); saveRules.setText("💾 Save playbooks"); saveRules.setTextSize(16); saveRules.setPadding(0, dp(12), 0, dp(12));
        saveRules.setOnClickListener(v -> { try { new JSONObject(rules.getText().toString()); lastRulesJson = rules.getText().toString(); saveRulesToFile(lastRulesJson); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); } catch (Exception e) { Toast.makeText(this, "Invalid JSON", Toast.LENGTH_SHORT).show(); } });
        box.addView(saveRules);

        new AlertDialog.Builder(this).setView(scroll).setPositiveButton("Save", (d, which) -> prefs.edit()
                .putString("api_key", key.getText().toString().trim())
                .putString("model", model.getText().toString().trim())
                .putString("voice", voice.getText().toString().trim())
                .putString("prompt", prompt.getText().toString())
                .putBoolean("auto_connect", auto.isChecked())
                .putBoolean("echo_guard", echo.isChecked()).apply())
                .setNegativeButton("Cancel", null).show();
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextSize(14); e.setSingleLine(false); e.setPadding(0, dp(10), 0, dp(10)); return e;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void loadRules() {
        File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "rules.json");
        if (!f.exists()) return;
        try (Scanner s = new Scanner(f, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) { if (s.hasNext()) lastRulesJson = s.next(); }
        catch (Exception ignored) { lastRulesJson = "{}"; }
    }

    private boolean saveRulesToFile(String json) {
        try {
            File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "rules.json");
            try (FileWriter w = new FileWriter(f)) { w.write(json); }
            return true;
        } catch (Exception e) { return false; }
    }

    @Override public void onRequestPermissionsResult(int request, String[] perms, int[] results) {
        super.onRequestPermissionsResult(request, perms, results);
        if (request == REQUEST_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSession();
    }
    @Override public void onState(String state, String detail) { main.post(() -> voiceView.setState(state, state.equals("working") ? "Working" : state.equals("speaking") ? "Speaking" : state.equals("connecting") ? "Connecting" : "Listening", detail)); }
    @Override public void onError(String error) { main.post(() -> { voiceView.setState("idle", "Error", error.length() > 38 ? error.substring(0, 38) : error); Toast.makeText(this, error, Toast.LENGTH_LONG).show(); }); }
    @Override public void onClosed() { main.post(this::stopSession); }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); }

    static String executeNativeTool(String name, JSONObject a, NativeGeminiClient c) {
        MainActivity x = instance;
        if (x == null) return "App unavailable";
        if (a == null) a = new JSONObject();
        try {
            switch (name) {
                case "get_device_info": return x.deviceInfo();
                case "search_internet": return x.webSearch(a.optString("query", ""));
                case "tap_element_id": return x.svc() == null ? "Accessibility Service required." : x.svc().tapId(a.optInt("element_id", 0));
                case "long_press_element_id": return x.svc() == null ? "Accessibility Service required." : x.svc().longId(a.optInt("element_id", 0));
                case "replace_text": return x.svc() == null ? "Accessibility Service required." : x.svc().replace(a.optString("text", ""));
                case "clear_text": return x.svc() == null ? "Accessibility Service required." : x.svc().replace("");
                case "type_text": return x.svc() == null ? "Accessibility Service required." : x.svc().type(a.optString("text", ""));
                case "tap_element": return x.svc() == null ? "Accessibility Service required." : x.svc().tapLabel(a.optString("label", ""));
                case "read_screen_text": return x.svc() == null ? "Accessibility Service required." : x.svc().read();
                case "tap_coordinates": return x.tapNorm(a.optInt("x", 500), a.optInt("y", 500));
                case "long_press": return x.longNorm(a.optInt("x", 500), a.optInt("y", 500));
                case "swipe": return x.swipe(a);
                case "scroll": return x.svc() == null ? "Accessibility Service required." : x.svc().scroll(a.optString("direction", "down"));
                case "wait_seconds": Thread.sleep(Math.max(1, Math.min(4, a.optInt("seconds", 1))) * 1000L); return "Waited.";
                case "capture_screen": return x.captureForGemini(c);
                case "open_application": return x.openApp(a.optString("app_name", ""));
                case "search_contacts": return x.searchContacts(a.optString("query", ""));
                case "search_youtube": return x.openExternal("https://www.youtube.com/results?search_query=" + URLEncoder.encode(a.optString("query", ""), "UTF-8"), "YouTube");
                case "search_web": return x.openExternal("https://www.google.com/search?q=" + URLEncoder.encode(a.optString("query", ""), "UTF-8"), "Browser");
                case "open_whatsapp": return x.openExternal("https://wa.me/" + a.optString("phone_number", "").replaceAll("[^0-9]", "") + "?text=" + URLEncoder.encode(a.optString("message", ""), "UTF-8"), "WhatsApp");
                case "toggle_flashlight": return x.flashlight(a.optBoolean("state", false));
                case "set_volume": return x.volume(a.optInt("level_percent", 50));
                case "navigate_system": return x.navigate(a.optString("action", "home"));
                case "make_phone_call": return x.call(a.optString("phone_number", ""));
                case "send_sms": return x.sms(a.optString("phone_number", ""), a.optString("message", ""));
                case "save_app_rule": return x.saveAppRule(a);
                case "create_note": return x.openExternal("mailto:?body=" + URLEncoder.encode(a.optString("text", ""), "UTF-8"), "Note composer");
                default: return "Unsupported native tool: " + name;
            }
        } catch (Exception e) { return "Tool error: " + e.getMessage(); }
    }

    private String swipe(JSONObject a) {
        if (svc() == null) return "Accessibility Service required.";
        boolean ok = svc().swipe(normX(a.optInt("start_x", 100)), normY(a.optInt("start_y", 500)), normX(a.optInt("end_x", 900)), normY(a.optInt("end_y", 500)), a.optInt("duration_ms", 300));
        return ok ? "Swiped" : "Swipe failed";
    }

    private String saveAppRule(JSONObject a) throws Exception {
        JSONObject rules = new JSONObject(lastRulesJson.length() == 0 ? "{}" : lastRulesJson);
        String pkg = a.optString("app_package", ""); if (pkg.isEmpty()) return "App package required.";
        JSONObject app = rules.optJSONObject(pkg); if (app == null) { app = new JSONObject(); rules.put(pkg, app); }
        JSONObject actions = app.optJSONObject("actions"); if (actions == null) { actions = new JSONObject(); app.put("actions", actions); }
        JSONObject point = new JSONObject().put("x", a.optInt("x", 0)).put("y", a.optInt("y", 0));
        actions.put(a.optString("action_name", "rule"), point);
        if (a.has("rule")) app.put("last_rule", a.optString("rule"));
        lastRulesJson = rules.toString(2); saveRulesToFile(lastRulesJson); return "Saved rule for " + pkg;
    }

    private VolumeTriggerService svc() { return VolumeTriggerService.instance; }
    private int normX(int n) { return Math.round(getResources().getDisplayMetrics().widthPixels * Math.max(0, Math.min(1000, n)) / 1000f); }
    private int normY(int n) { return Math.round(getResources().getDisplayMetrics().heightPixels * Math.max(0, Math.min(1000, n)) / 1000f); }
    private String tapNorm(int x, int y) { return svc() == null ? "Accessibility Service required." : svc().tap(normX(x), normY(y)) ? "Tapped" : "Tap failed"; }
    private String longNorm(int x, int y) { return svc() == null ? "Accessibility Service required." : svc().longPress(normX(x), normY(y)) ? "Long-pressed" : "Long press failed"; }

    private String deviceInfo() {
        BatteryManager b = (BatteryManager)getSystemService(BATTERY_SERVICE);
        int battery = b == null ? -1 : b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return "{\"battery\":" + battery + "}";
    }

    private String webSearch(String q) throws Exception {
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) return "Missing API key.";
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection h = (HttpURLConnection) url.openConnection();
        h.setRequestMethod("POST"); h.setConnectTimeout(8000); h.setReadTimeout(12000); h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "Answer in 1-2 concise sentences using live web search: " + q)))));
        body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
        try (java.io.OutputStream out = h.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        InputStream in = h.getResponseCode() >= 400 ? h.getErrorStream() : h.getInputStream();
        if (in == null) return "No response.";
        String text;
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) { text = s.hasNext() ? s.next() : ""; }
        try {
            JSONObject j = new JSONObject(text);
            return j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text", text);
        } catch (Exception ignored) { return text; }
    }

    private String captureForGemini(NativeGeminiClient c) {
        if (ScreenCaptureService.instance == null) {
            main.post(this::requestScreenCapture);
            return "Screen capture permission required; request sent.";
        }
        String b64 = ScreenCaptureService.instance.capture();
        if (b64 == null) return "Capture failed.";
        c.sendImageBase64(b64);
        return "Screenshot captured and injected into the visual feed.";
    }

    private void requestScreenCapture() {
        android.media.projection.MediaProjectionManager m = (android.media.projection.MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_CAPTURE && result == RESULT_OK && data != null) {
            Intent i = new Intent(this, ScreenCaptureService.class).putExtra("resultCode", result).putExtra("data", data);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
    }

    private String openApp(String name) {
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo a : pm.getInstalledApplications(0)) {
            String label = pm.getApplicationLabel(a).toString();
            if (label.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                Intent i = pm.getLaunchIntentForPackage(a.packageName);
                if (i != null) { main.post(() -> startActivity(i)); return "Opening " + label; }
            }
        }
        return "App not found: " + name;
    }

    private String openExternal(String url, String what) {
        main.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} });
        return "Opening " + what;
    }

    private String flashlight(boolean on) throws Exception {
        CameraManager cm = (CameraManager)getSystemService(CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) { cm.setTorchMode(id, on); return "Flashlight " + (on ? "ON" : "OFF"); }
        }
        return "No flashlight available.";
    }

    private String volume(int p) {
        AudioManager a = (AudioManager)getSystemService(AUDIO_SERVICE); int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        a.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * Math.max(0, Math.min(100, p)) / 100f), 0); return "Volume set.";
    }

    private String navigate(String action) {
        if (svc() == null) return "Accessibility Service required.";
        int g;
        switch (action) {
            case "back": g = VolumeTriggerService.GLOBAL_ACTION_BACK; break;
            case "recents": g = VolumeTriggerService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": g = VolumeTriggerService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": g = VolumeTriggerService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock": g = VolumeTriggerService.GLOBAL_ACTION_LOCK_SCREEN; break;
            default: g = VolumeTriggerService.GLOBAL_ACTION_HOME;
        }
        return svc().performGlobalAction(g) ? "Done" : "Failed";
    }

    private String call(String number) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL));
            return "Call permission requested.";
        }
        main.post(() -> startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number))));
        return "Calling.";
    }

    private String sms(String number, String message) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQUEST_SMS));
            return "SMS permission requested.";
        }
        SmsManager.getDefault().sendTextMessage(number, null, message, null, null);
        return "SMS sent.";
    }

    private String searchContacts(String q) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS));
            return "Contacts permission requested.";
        }
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{"display_name", "data1"}, "display_name LIKE ?", new String[]{"%" + q + "%"}, null);
            StringBuilder s = new StringBuilder(); int n = 0;
            while (c != null && c.moveToNext() && n++ < 5) s.append(c.getString(0)).append(": ").append(c.getString(1)).append('\n');
            return s.length() == 0 ? "No contacts found." : s.toString();
        } finally { if (c != null) c.close(); }
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (prefs.getBoolean("auto_connect", false) && client == null) main.postDelayed(this::startSession, 200); }
    @Override public void onBackPressed() { moveTaskToBack(true); }
    @Override protected void onDestroy() { stopSession(); instance = null; super.onDestroy(); }

    private final class NativeVoiceView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final RectF capsule = new RectF();
        String state = "idle", title = "Voice", sub = "Ready"; boolean muted; final float density;
        NativeVoiceView(Context c) { super(c); density = getResources().getDisplayMetrics().density; setLayerType(View.LAYER_TYPE_HARDWARE, null); setFocusable(true); }
        void setState(String s, String t, String d) { state = s; title = t; sub = d; invalidate(); }
        int d(float v) { return Math.round(v * density); }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(0xFF080A10); float w = getWidth(), h = getHeight();
            float left = d(12), right = w - d(12), bottom = h - d(28), top = bottom - d(68); capsule.set(left, top, right, bottom);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF111522); c.drawRoundRect(capsule, d(34), d(34), p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0x335C657A); c.drawRoundRect(capsule, d(34), d(34), p); p.setStyle(Paint.Style.FILL);
            float cx = left + d(31), cy = top + d(34); p.setColor(colorForState()); c.drawCircle(cx, cy, d(12), p);
            p.setColor(0x44FFFFFF); c.drawCircle(cx - d(3), cy - d(3), d(4), p);
            p.setColor(0xFFF3F5F8); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(d(13)); c.drawText(title, left + d(52), top + d(29), p);
            p.setColor(0xFF96A0B4); p.setTypeface(Typeface.DEFAULT); p.setTextSize(d(10)); String s = sub.length() > 28 ? sub.substring(0, 28) : sub; c.drawText(s, left + d(52), top + d(46), p);
            float gearX = right - d(44); p.setColor(0xFF202638); c.drawCircle(gearX, cy, d(18), p); p.setColor(0xFFE8ECF4); p.setTextSize(d(16)); c.drawText("⚙", gearX - d(8), cy + d(6), p);
            if (client == null) { p.setColor(0xFF3156D9); c.drawRoundRect(right - d(134), top + d(16), right - d(58), top + d(52), d(18), d(18), p); p.setColor(0xFFFFFFFF); p.setTextSize(d(12)); c.drawText("START", right - d(117), top + d(39), p); }
            else { float stop = right - d(91); p.setColor(0xFFE63F4E); c.drawCircle(stop, cy, d(18), p); p.setColor(0xFFFFFFFF); p.setTextSize(d(14)); c.drawText("■", stop - d(5), cy + d(5), p); }
        }
        private int colorForState() { if ("speaking".equals(state)) return 0xFFF6B31B; if ("working".equals(state)) return 0xFF06B6D4; if ("connecting".equals(state)) return 0xFF7C73FF; return "idle".equals(state) ? 0xFF4A55C8 : 0xFFA855F7; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            float w = getWidth(), h = getHeight(); float y = h - d(62), x = e.getX();
            if (e.getY() < y - d(28) || e.getY() > y + d(28)) return true;
            if (x > w - d(58)) showSettings();
            else if (client == null && x > w - d(150)) startSession();
            else if (client != null && x > w - d(112)) stopSession();
            return true;
        }
    }
}
