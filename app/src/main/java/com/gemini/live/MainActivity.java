package com.gemini.live;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

public class MainActivity extends Activity implements NativeGeminiClient.Listener {
    static MainActivity instance;
    private static final int REQUEST_AUDIO = 41;
    private static final int REQUEST_CONTACTS = 42;
    private static final int REQUEST_CALL = 43;
    private static final int REQUEST_SMS = 44;
    private static final int REQUEST_CAPTURE = 45;

    private SharedPreferences prefs;
    private NativeGeminiClient client;
    private NativeVoiceView voiceView;
    private final Handler main = new Handler(Looper.getMainLooper());
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
            main.postDelayed(this::startSession, 500);
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
        if (client != null) client.stop();
        client = null;
        voiceView.setState("idle", "Voice", "Ready");
    }

    private void toggleMute() {
        if (client != null) {
            boolean muted = !voiceView.muted;
            voiceView.muted = muted;
            client.setMuted(muted);
            voiceView.invalidate();
        }
    }

    private void showSettings() {
        final ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(10), dp(18), dp(18));
        scroll.addView(box);

        TextView title = new TextView(this);
        title.setText("Voice • Native configuration"); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(title, lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0, dp(8)));

        EditText key = input("Gemini API Key", prefs.getString("api_key", "")); key.setInputType(0x00000081); box.addView(key, lp(-1, -2, 0, 0));
        EditText model = input("Model", prefs.getString("model", "models/gemini-3.1-flash-live-preview")); box.addView(model, lp(-1, -2, 0, 0));
        EditText voice = input("Voice (Aoede/Puck/Charon/Fenrir/Kore)", prefs.getString("voice", "Aoede")); box.addView(voice, lp(-1, -2, 0, 0));
        EditText prompt = input("Assistant Instructions", prefs.getString("prompt", DEFAULT_PROMPT)); prompt.setMinLines(5); box.addView(prompt, lp(-1, -2, 0, 0));

        CheckBox auto = new CheckBox(this); auto.setText("Auto-connect after launch"); auto.setChecked(prefs.getBoolean("auto_connect", false)); box.addView(auto);
        CheckBox echo = new CheckBox(this); echo.setText("Speaker echo guard"); echo.setChecked(prefs.getBoolean("echo_guard", true)); box.addView(echo);

        Button access = new Button(this); access.setText("Open Accessibility settings");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); box.addView(access);

        TextView mem = new TextView(this); mem.setText("Low-RAM mode: no WebView, no Chromium, no JS runtime.\\n" +
                "Native audio/capture is created only while a session is active."); mem.setTextSize(13); box.addView(mem, lp(-1, -2, 0, dp(8)));

        EditText rules = input("App playbooks JSON", lastRulesJson); rules.setMinLines(8); box.addView(rules, lp(-1, -2, 0, dp(8)));
        Button saveRules = new Button(this); saveRules.setText("Save playbooks"); saveRules.setOnClickListener(v -> {
            try { new JSONObject(rules.getText().toString()); saveRulesToFile(rules.getText().toString()); lastRulesJson = rules.getText().toString(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); }
            catch (Exception e) { Toast.makeText(this, "Invalid JSON", Toast.LENGTH_SHORT).show(); }
        }); box.addView(saveRules);

        new AlertDialog.Builder(this).setView(scroll).setPositiveButton("Save", (d, which) -> {
            prefs.edit().putString("api_key", key.getText().toString().trim())
                    .putString("model", model.getText().toString().trim())
                    .putString("voice", voice.getText().toString().trim())
                    .putString("prompt", prompt.getText().toString())
                    .putBoolean("auto_connect", auto.isChecked())
                    .putBoolean("echo_guard", echo.isChecked()).apply();
        }).setNegativeButton("Cancel", null).show();
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setTextSize(14); e.setSingleLine(false); return e;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(0, top, 0, bottom); return p;
    }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private void loadRules() {
        File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "rules.json");
        if (!f.exists()) { lastRulesJson = "{}"; return; }
        try {
            java.util.Scanner s = new java.util.Scanner(f).useDelimiter("\\A");
            lastRulesJson = s.hasNext() ? s.next() : "{}"; s.close();
        } catch (Exception ignored) { lastRulesJson = "{}"; }
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

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && voiceView != null) voiceView.reduceCaches();
    }

    static String executeNativeTool(String name, JSONObject a, NativeGeminiClient c) {
        MainActivity x = instance;
        if (x == null) return "App unavailable";
        try {
            if (a == null) a = new JSONObject();
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
                case "swipe": return x.svc() == null ? "Accessibility Service required." : x.svc().swipe(x.normX(a.optInt("start_x", 100)), x.normY(a.optInt("start_y", 500)), x.normX(a.optInt("end_x", 900)), x.normY(a.optInt("end_y", 500)), a.optInt("duration_ms", 300)) ? "Swiped" : "Swipe failed";
                case "scroll": return x.svc() == null ? "Accessibility Service required." : x.svc().scroll(a.optString("direction", "down"));
                case "wait_seconds": Thread.sleep(Math.max(1, Math.min(4, Math.round(a.optDouble("seconds", 1) * 1000) / 1000))); return "Waited.";
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
                case "save_app_rule":
                    JSONObject rules = new JSONObject(x.lastRulesJson.length() == 0 ? "{}" : x.lastRulesJson);
                    String pkg = a.optString("app_package", ""); if (pkg.length() == 0) return "App package required.";
                    JSONObject app = rules.optJSONObject(pkg); if (app == null) { app = new JSONObject(); rules.put(pkg, app); }
                    JSONObject actions = app.optJSONObject("actions"); if (actions == null) { actions = new JSONObject(); app.put("actions", actions); }
                    String action = a.optString("action_name", "rule");
                    actions.put(action, new JSONObject().put("x", a.optInt("x", 0)).put("y", a.optInt("y", 0)));
                    if (a.has("rule")) app.put("last_rule", a.optString("rule"));
                    x.lastRulesJson = rules.toString(2); x.saveRulesToFile(x.lastRulesJson); return "Saved rule for " + pkg;
                case "create_note": return x.openExternal("mailto:?body=" + URLEncoder.encode(a.optString("text", ""), "UTF-8"), "Note composer");
                default: return "Unsupported native tool: " + name;
            }
        } catch (Exception e) { return "Tool error: " + e.getMessage(); }
    }

    private VolumeTriggerService svc() { return VolumeTriggerService.instance; }
    private int normX(int n) { return Math.round(getResources().getDisplayMetrics().widthPixels * Math.max(0, Math.min(1000, n)) / 1000f); }
    private int normY(int n) { return Math.round(getResources().getDisplayMetrics().heightPixels * Math.max(0, Math.min(1000, n)) / 1000f); }
    private String tapNorm(int x, int y) { return svc() == null ? "Accessibility Service required." : svc().tap(normX(x), normY(y)) ? "Tapped" : "Tap failed"; }
    private String longNorm(int x, int y) { return svc() == null ? "Accessibility Service required." : svc().longPress(normX(x), normY(y)) ? "Long-pressed" : "Long press failed"; }

    private String deviceInfo() {
        BatteryManager b = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = b == null ? -1 : b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return "{\"current_time\":\"" + java.text.DateFormat.getTimeInstance().format(new java.util.Date()) +
                "\",\"current_date\":\"" + java.text.DateFormat.getDateInstance().format(new java.util.Date()) +
                "\",\"timezone\":\"" + java.util.TimeZone.getDefault().getID() +
                "\",\"battery\":\"" + battery + "%\",\"memory_class_mb\":" + getMemoryClass() +
                ",\"low_ram_device\":" + getActivityManager().isLowRamDevice() + "}";
    }

    private String webSearch(String q) throws Exception {
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) return "Missing API key.";
        URL u = new URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection h = (HttpURLConnection) u.openConnection(); h.setRequestMethod("POST"); h.setConnectTimeout(8000); h.setReadTimeout(12000); h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json");
        String body = new JSONObject().put("contents", new org.json.JSONArray().put(new JSONObject().put("parts", new org.json.JSONArray().put(new JSONObject().put("text", "Answer in 1-2 concise sentences using live web search: " + q))))).put("tools", new org.json.JSONArray().put(new JSONObject().put("google_search", new JSONObject()))).toString();
        h.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        InputStream in = h.getResponseCode() >= 400 ? h.getErrorStream() : h.getInputStream();
        String out = new java.util.Scanner(in).useDelimiter("\\A").hasNext() ? new java.util.Scanner(in).useDelimiter("\\A").next() : "";
        try { JSONObject j = new JSONObject(out); return j.optJSONArray("candidates").getJSONObject(0).optJSONObject("content").optJSONArray("parts").getJSONObject(0).optString("text", out); } catch (Exception e) { return out; }
    }

    private String captureForGemini(NativeGeminiClient c) {
        if (ScreenCaptureService.instance == null) { requestScreenCapture(); return "Screen capture permission required; request sent."; }
        String b64 = ScreenCaptureService.instance.capture(); if (b64 == null) return "Capture failed."; c.sendImageBase64(b64); return "Screenshot captured and injected into the visual feed.";
    }

    private void requestScreenCapture() {
        android.media.projection.MediaProjectionManager m = (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }
    @Override protected void onActivityResult(int r, int c, Intent d) {
        super.onActivityResult(r, c, d);
        if (r == REQUEST_CAPTURE && c == RESULT_OK && d != null) {
            Intent i = new Intent(this, ScreenCaptureService.class).putExtra("resultCode", c).putExtra("data", d);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
    }

    private String openApp(String name) {
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo a : pm.getInstalledApplications(0)) {
            String label = pm.getApplicationLabel(a).toString();
            if (label.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                Intent i = pm.getLaunchIntentForPackage(a.packageName); if (i != null) { startActivity(i); return "Opened " + label; }
            }
        }
        return "App not found: " + name;
    }
    private String openExternal(String url, String what) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return "Opened " + what; } catch (Exception e) { return e.toString(); } }
    private String flashlight(boolean on) throws Exception { CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE); for (String id : cm.getCameraIdList()) { Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE); if (Boolean.TRUE.equals(flash)) { cm.setTorchMode(id, on); return "Flashlight " + (on ? "ON" : "OFF"); } } return "No flashlight available."; }
    private String volume(int p) { AudioManager a = (AudioManager) getSystemService(AUDIO_SERVICE); int m = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC); a.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(m * Math.max(0, Math.min(100, p)) / 100f), AudioManager.FLAG_SHOW_UI); return "Volume set."; }
    private String navigate(String action) { int g; switch (action) { case "back": g = VolumeTriggerService.GLOBAL_ACTION_BACK; break; case "recents": g = VolumeTriggerService.GLOBAL_ACTION_RECENTS; break; case "notifications": g = VolumeTriggerService.GLOBAL_ACTION_NOTIFICATIONS; break; case "quick_settings": g = VolumeTriggerService.GLOBAL_ACTION_QUICK_SETTINGS; break; case "lock": g = VolumeTriggerService.GLOBAL_ACTION_LOCK_SCREEN; break; default: g = VolumeTriggerService.GLOBAL_ACTION_HOME; } return svc() != null && svc().performGlobalAction(g) ? "Done" : "Accessibility Service required."; }
    private String call(String n) throws Exception { if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, REQUEST_CALL); return "Call permission requested."; } startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + n))); return "Calling."; }
    private String sms(String n, String m) throws Exception { if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.SEND_SMS}, REQUEST_SMS); return "SMS permission requested."; } SmsManager.getDefault().sendTextMessage(n, null, m, null, null); return "SMS sent."; }
    private String searchContacts(String q) throws Exception { if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS); return "Contacts permission requested."; } android.database.Cursor c = null; try { c = getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, new String[]{"display_name", "data1"}, "display_name LIKE ?", new String[]{"%" + q + "%"}, null); StringBuilder s = new StringBuilder(); int n=0; while(c != null && c.moveToNext() && n++ < 5) s.append(c.getString(0)).append(": ").append(c.getString(1)).append('\\n'); return s.length() == 0 ? "No contacts found." : s.toString(); } finally { if (c != null) c.close(); } }

    private android.app.ActivityManager getActivityManager() { return (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE); }
    private int getMemoryClass() { return getActivityManager().getMemoryClass(); }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (prefs.getBoolean("auto_connect", false) && client == null) main.postDelayed(this::startSession, 200); }
    @Override public void onBackPressed() { moveTaskToBack(true); }
    @Override protected void onDestroy() { stopSession(); instance = null; super.onDestroy(); }

    private final class NativeVoiceView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final RectF capsule = new RectF();
        String state="idle", title="Voice", sub="Ready"; boolean muted;
        float density;
        NativeVoiceView(Context c) { super(c); density=getResources().getDisplayMetrics().density; setLayerType(View.LAYER_TYPE_HARDWARE, null); }
        void setState(String st, String t, String s) { state=st; title=t; sub=s; invalidate(); }
        void reduceCaches() { }
        int d(float v) { return Math.round(v*density); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.drawColor(0xFF080A10);
            float w=getWidth(), h=getHeight();
            float left=d(12), right=w-d(12), bottom=h-d(28), top=bottom-d(68);
            capsule.set(left,top,right,bottom); p.setColor(0xFF111522); p.setStyle(Paint.Style.FILL); c.drawRoundRect(capsule,d(34),d(34),p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(d(1)); p.setColor(0x335C657A); c.drawRoundRect(capsule,d(34),d(34),p); p.setStyle(Paint.Style.FILL);
            float cx=left+d(31), cy=top+d(34);
            p.setColor(stateColor()); c.drawCircle(cx,cy,d(12),p);
            p.setColor(0x44FFFFFF); c.drawCircle(cx-d(3),cy-d(3),d(4),p);
            p.setColor(0xFFF3F5F8); p.setTextSize(d(13)); p.setTypeface(Typeface.DEFAULT_BOLD); c.drawText(title,left+d(52),top+d(29),p);
            p.setColor(0xFF96A0B4); p.setTextSize(d(10)); p.setTypeface(Typeface.DEFAULT); String small=sub.length()>26?sub.substring(0,26):sub; c.drawText(small,left+d(52),top+d(46),p);

            float bx=right-d(46); p.setColor(0xFF202638); c.drawCircle(bx,cy,d(18),p); p.setColor(0xFFE8ECF4); p.setTextSize(d(18)); c.drawText("⚙",bx-d(9),cy+d(6),p);
            if (client==null) { p.setColor(0xFF3156D9); c.drawRoundRect(right-d(135),top+d(16),right-d(57),top+d(52),d(18),d(18),p); p.setColor(Color.WHITE); p.setTextSize(d(12)); c.drawText("START",right-d(116),top+d(39),p); }
            else {
                float x=right-d(136); p.setColor(0xFF242A39); c.drawCircle(x,cy,d(18),p); p.setColor(0xFFE8ECF4); p.setTextSize(d(16)); c.drawText(muted?"×":"•",x-d(5),cy+d(5),p);
                x=right-d(91); p.setColor(0xFFE63F4E); c.drawCircle(x,cy,d(18),p); p.setColor(Color.WHITE); p.setTextSize(d(16)); c.drawText("■",x-d(6),cy+d(6),p);
                x=right-d(46); p.setColor(0xFF202638); c.drawCircle(x,cy,d(18),p); p.setColor(0xFFE8ECF4); c.drawText("⚙",x-d(9),cy+d(6),p);
            }
            if ("working".equals(state)) { p.setColor(0x5539D6E8); c.drawRect(left+d(16),bottom-d(3),right-d(16),bottom-d(1),p); }
        }
        private int stateColor() { if ("speaking".equals(state)) return 0xFFF6B31B; if ("working".equals(state)) return 0xFF06B6D4; if ("connecting".equals(state)) return 0xFF7C73FF; if ("idle".equals(state)) return 0xFF4A55C8; return 0xFFA855F7; }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction()!=MotionEvent.ACTION_UP) return true;
            float w=getWidth(), h=getHeight(), cy=h-d(62); float x=e.getX(), y=e.getY();
            if (y < cy-d(25) || y > cy+d(30)) return true;
            if (x > w-d(58)) { showSettings(); return true; }
            if (client == null && x > w-d(150)) { startSession(); return true; }
            if (client != null) {
                if (x > w-d(110) && x <= w-d(65)) { stopSession(); return true; }
                if (x > w-d(155) && x <= w-d(110)) { toggleMute(); return true; }
            }
            return true;
        }
    }
}
