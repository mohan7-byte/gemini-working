package com.gemini.live;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.GradientDrawable;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

/** Native Android UI and controller. No WebView, Chromium, HTML, or JavaScript runtime. */
public final class MainActivity extends Activity implements NativeGeminiClient.Listener {
    static MainActivity instance;
    private static final int REQ_AUDIO = 41;
    private static final String PREFS = "voice_native";
    private static final String DEFAULT_MODEL = "models/gemini-3.1-flash-live-preview";
    private static final String DEFAULT_VOICE = "Aoede";
    private static final String DEFAULT_PROMPT =
            "You are Voice, a fast autonomous Android agent. Read the Android screen before interacting with another app. " +
            "Use element IDs when possible, keep confirmations concise, and execute actions in sequence.";

    private SharedPreferences prefs;
    private NativeGeminiClient client;
    private TextView stateText;
    private TextView detailText;
    private TextView micButton;
    private FrameLayout root;
    private LinearLayout sheet;
    private EditText apiKey;
    private EditText prompt;
    private Spinner model;
    private Spinner voice;
    private boolean intentionalStop;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildUi();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp((int) radius));
        return d;
    }

    private TextView text(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String value, int height, View.OnClickListener listener) {
        TextView t = text(value, 14, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setBackground(bg(0xFF161B2A, 22));
        t.setOnClickListener(listener);
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setMinHeight(dp(height));
        return t;
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF7D8796);
        e.setTextSize(14);
        e.setSingleLine(false);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(bg(0xF51A1F2E, 16));
        return e;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        setContentView(root);

        TextView backdrop = text("", 1, Color.TRANSPARENT);
        backdrop.setBackgroundColor(0x01000000);
        backdrop.setOnClickListener(v -> hideSheet());
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setGravity(Gravity.CENTER_HORIZONTAL);
        dock.setPadding(dp(16), dp(8), dp(16), dp(16));
        dock.setBackground(bg(0xF20D101A, 28));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text("VOICE", 13, 0xFFE9D5FF);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(badge, new LinearLayout.LayoutParams(0, dp(40), 1));
        stateText = text("Idle", 17, Color.WHITE);
        stateText.setGravity(Gravity.CENTER);
        top.addView(stateText, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView settings = text("⚙", 22, Color.WHITE);
        settings.setGravity(Gravity.CENTER);
        settings.setOnClickListener(v -> showSheet());
        top.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(40)));
        dock.addView(top);

        detailText = text("Ready", 12, 0xFF9AA5B4);
        detailText.setGravity(Gravity.CENTER);
        dock.addView(detailText, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(4), 0, 0);
        micButton = text("Start voice", 14, Color.WHITE);
        micButton.setGravity(Gravity.CENTER);
        micButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        micButton.setBackground(bg(0xFF7C3AED, 24));
        micButton.setOnClickListener(v -> toggleVoice());
        actions.addView(micButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView access = text("A", 15, Color.WHITE);
        access.setGravity(Gravity.CENTER);
        access.setBackground(bg(0xFF23283A, 24));
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(52), dp(52));
        ap.leftMargin = dp(8);
        actions.addView(access, ap);
        dock.addView(actions);

        LinearLayout.LayoutParams dockLp = new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT);
        dockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dockLp.bottomMargin = dp(18);
        root.addView(dock, dockLp);

        buildSettingsSheet();
    }

    private void buildSettingsSheet() {
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(18), dp(18), dp(18));
        sheet.setBackground(bg(0xF7181C2B, 28));

        FrameLayout titleRow = new FrameLayout(this);
        TextView title = text("Configuration & Knowledge", 17, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new FrameLayout.LayoutParams(-1, dp(42)));
        TextView close = text("×", 24, 0xFFCBD5E1);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> hideSheet());
        titleRow.addView(close, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.RIGHT | Gravity.TOP));
        sheet.addView(titleRow);

        TextView note = text("Native mode • no browser engine", 12, 0xFF9AA5B4);
        sheet.addView(note, new LinearLayout.LayoutParams(-1, dp(28)));

        TextView keyLabel = text("GEMINI API KEY", 11, 0xFF9AA5B4);
        sheet.addView(keyLabel);
        apiKey = field(prefs.getString("api_key", ""), "AIza...");
        apiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        sheet.addView(apiKey, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView modelLabel = text("TARGET MODEL", 11, 0xFF9AA5B4);
        modelLabel.setPadding(0, dp(12), 0, dp(4));
        sheet.addView(modelLabel);
        model = spinner(new String[]{"models/gemini-3.1-flash-live-preview", "models/gemini-2.5-flash-live-preview"},
                prefs.getString("model", DEFAULT_MODEL));
        sheet.addView(model, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView voiceLabel = text("VOICE PROFILE", 11, 0xFF9AA5B4);
        voiceLabel.setPadding(0, dp(10), 0, dp(4));
        sheet.addView(voiceLabel);
        voice = spinner(new String[]{"Aoede", "Puck", "Kore", "Charon", "Fenrir"}, prefs.getString("voice", DEFAULT_VOICE));
        sheet.addView(voice, new LinearLayout.LayoutParams(-1, dp(52)));

        addSwitch("Auto-Disconnect on Silence (4s)", "Automatically close after finishing action", false);
        addSwitch("Auto-Connect on Launch", "Listen immediately when triggered", false);
        addSwitch("Speaker Echo Guard", "Prevents speaker acoustic feedback loop", true);
        addSwitch("Bluetooth Priority Mode", "Keep audio on media speaker", true);

        TextView promptLabel = text("ASSISTANT INSTRUCTIONS (JARVIS PROTOCOL)", 11, 0xFF9AA5B4);
        promptLabel.setPadding(0, dp(12), 0, dp(4));
        sheet.addView(promptLabel);
        prompt = field(prefs.getString("prompt", DEFAULT_PROMPT), "Instructions");
        prompt.setMinHeight(dp(118));
        sheet.addView(prompt, new LinearLayout.LayoutParams(-1, dp(118)));

        TextView save = button("Save settings", 48, v -> saveSettings());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(48));
        sp.topMargin = dp(12);
        sheet.addView(save, sp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1,
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.86f), Gravity.BOTTOM);
        lp.leftMargin = dp(8); lp.rightMargin = dp(8); lp.bottomMargin = dp(8);
        root.addView(sheet, lp);
        sheet.setVisibility(View.GONE);
    }

    private Spinner spinner(String[] values, String selected) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(Color.WHITE); v.setTextSize(14); v.setPadding(dp(12), dp(8), dp(12), dp(8));
                return v;
            }
        };
        s.setAdapter(a);
        for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) { s.setSelection(i); break; }
        s.setBackground(bg(0xF51A1F2E, 16));
        return s;
    }

    private void addSwitch(String title, String subtitle, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(title, 14, Color.WHITE));
        copy.addView(text(subtitle, 11, 0xFF8E98A7));
        row.addView(copy, new LinearLayout.LayoutParams(0, dp(52), 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        row.addView(sw, new LinearLayout.LayoutParams(dp(58), dp(48)));
        sheet.addView(row);
    }

    private void showSheet() { sheet.setVisibility(View.VISIBLE); }
    private void hideSheet() { sheet.setVisibility(View.GONE); }

    private void saveSettings() {
        prefs.edit()
                .putString("api_key", apiKey.getText().toString().trim())
                .putString("model", String.valueOf(model.getSelectedItem()))
                .putString("voice", String.valueOf(voice.getSelectedItem()))
                .putString("prompt", prompt.getText().toString().trim())
                .apply();
        detailText.setText("Settings saved");
        hideSheet();
    }

    private void toggleVoice() { if (client != null) stopSession(); else startSession(); }

    private void startSession() {
        if (client != null) return;
        intentionalStop = false;
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) { showSheet(); detailText.setText("Add your Gemini API key"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        setState("Connecting", "Opening native audio link");
        client = new NativeGeminiClient(this, key,
                prefs.getString("model", DEFAULT_MODEL), prefs.getString("voice", DEFAULT_VOICE),
                prefs.getString("prompt", DEFAULT_PROMPT), true);
        client.start();
    }

    private void stopSession() {
        intentionalStop = true;
        NativeGeminiClient c = client;
        client = null;
        if (c != null) c.stop();
        setState("Idle", "Ready");
    }

    private void setState(String title, String detail) {
        runOnUiThread(() -> {
            stateText.setText(title);
            detailText.setText(detail == null ? "" : detail);
            micButton.setText("Listening".equals(title) || "Speaking".equals(title) || "Working".equals(title) || "Connecting".equals(title) ? "Stop voice" : "Start voice");
            int color = "Error".equals(title) ? 0xFFDC2626 : ("Idle".equals(title) ? 0xFF7C3AED : 0xFF9333EA);
            micButton.setBackground(bg(color, 24));
        });
    }

    @Override public void onState(String state, String d) {
        String title = "listening".equals(state) ? "Listening" : "speaking".equals(state) ? "Speaking" :
                "working".equals(state) ? "Working" : "connecting".equals(state) ? "Connecting" : state;
        setState(title, d);
    }

    @Override public void onError(String error) {
        client = null;
        setState("Error", error == null ? "Unknown error" : error);
    }

    @Override public void onClosed() {
        if (!intentionalStop) {
            client = null;
            setState("Disconnected", "Connection closed — tap Start voice to retry");
        }
    }

    @Override protected void onDestroy() {
        intentionalStop = true;
        NativeGeminiClient c = client;
        client = null;
        if (c != null) c.stop();
        instance = null;
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE && client != null) stopSession();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startSession();
        else if (requestCode == REQ_AUDIO) setState("Permission", "Microphone permission is required for voice");
    }

    static String executeNativeTool(String name, JSONObject args, NativeGeminiClient c) {
        MainActivity a = instance;
        if (a == null) return "App is not active.";
        if (args == null) args = new JSONObject();
        try {
            VolumeTriggerService svc = VolumeTriggerService.instance;
            switch (name) {
                case "get_device_info": return a.deviceInfo();
                case "read_screen_text": return svc == null ? "Accessibility Service required." : svc.read();
                case "tap_element_id": return svc == null ? "Accessibility Service required." : svc.tapId(args.optInt("element_id", 0));
                case "long_press_element_id": return svc == null ? "Accessibility Service required." : svc.longId(args.optInt("element_id", 0));
                case "replace_text": return svc == null ? "Accessibility Service required." : svc.replace(args.optString("text", ""));
                case "clear_text": return svc == null ? "Accessibility Service required." : svc.replace("");
                case "type_text": return svc == null ? "Accessibility Service required." : svc.type(args.optString("text", ""));
                case "tap_element": return svc == null ? "Accessibility Service required." : svc.tapLabel(args.optString("label", ""));
                case "tap_coordinates": return svc == null ? "Accessibility Service required." : svc.tap(a.normX(args.optInt("x", 500)), a.normY(args.optInt("y", 500))) ? "Tapped" : "Tap failed";
                case "long_press": return svc == null ? "Accessibility Service required." : svc.longPress(a.normX(args.optInt("x", 500)), a.normY(args.optInt("y", 500))) ? "Long-pressed" : "Long press failed";
                case "scroll": return svc == null ? "Accessibility Service required." : svc.scroll(args.optString("direction", "down")) ? "Scrolled" : "Scroll failed";
                case "swipe": return svc == null ? "Accessibility Service required." : svc.swipe(a.normX(args.optInt("start_x", 100)), a.normY(args.optInt("start_y", 500)), a.normX(args.optInt("end_x", 900)), a.normY(args.optInt("end_y", 500)), Math.max(100, args.optInt("duration_ms", 300))) ? "Swiped" : "Swipe failed";
                case "wait_seconds": Thread.sleep(Math.max(1, Math.min(4, args.optInt("seconds", 1))) * 1000L); return "Waited.";
                case "open_application": return a.openApplication(args.optString("app_name", ""));
                case "search_web": return a.openUrl("https://www.google.com/search?q=" + Uri.encode(args.optString("query", "")));
                case "search_youtube": return a.openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(args.optString("query", "")));
                case "open_whatsapp": return a.openUrl("https://wa.me/" + args.optString("phone_number", "").replaceAll("[^0-9]", "") + "?text=" + Uri.encode(args.optString("message", "")));
                case "toggle_flashlight": return a.flashlight(args.optBoolean("state", false));
                case "set_volume": return a.setVolume(args.optInt("level_percent", 50));
                case "navigate_system": return svc == null ? "Accessibility Service required." : svc.navigate(args.optString("action", "home"));
                case "make_phone_call": return a.makeCall(args.optString("phone_number", ""));
                case "send_sms": return a.sendSms(args.optString("phone_number", ""), args.optString("message", ""));
                case "create_note": return a.openUrl("mailto:?subject=Voice%20Note&body=" + Uri.encode(args.optString("text", "")));
                case "search_contacts": return "Contacts search requires the native contacts layer.";
                case "save_app_rule": return "Rule storage requires the native playbook layer.";
                case "capture_screen": return "Screen capture will be enabled by the native capture service.";
                case "search_internet": return a.openUrl("https://www.google.com/search?q=" + Uri.encode(args.optString("query", "")));
                default: return "Unsupported native tool: " + name;
            }
        } catch (Exception e) { return "Tool error: " + e.getMessage(); }
    }

    private int normX(int value) { return Math.round(getResources().getDisplayMetrics().widthPixels * Math.max(0, Math.min(1000, value)) / 1000f); }
    private int normY(int value) { return Math.round(getResources().getDisplayMetrics().heightPixels * Math.max(0, Math.min(1000, value)) / 1000f); }

    private String deviceInfo() {
        BatteryManager b = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = b == null ? -1 : b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        int memoryClass = am == null ? 0 : am.getMemoryClass();
        return "{\"battery\":" + battery + ",\"low_ram_device\":" + lowRam + ",\"memory_class_mb\":" + memoryClass + "}";
    }

    private String openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); return "Opened."; }
        catch (Exception e) { return "Could not open URL: " + e.getMessage(); }
    }

    private String openApplication(String label) {
        PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo info : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            String name = pm.getApplicationLabel(info).toString();
            if (name.equalsIgnoreCase(label) || name.toLowerCase().contains(label.toLowerCase())) {
                Intent i = pm.getLaunchIntentForPackage(info.packageName);
                if (i != null) { startActivity(i); return "Opened " + name; }
            }
        }
        return "Application not found: " + label;
    }

    private String flashlight(boolean enabled) {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    manager.setTorchMode(id, enabled); return enabled ? "Flashlight on" : "Flashlight off";
                }
            }
            return "No flashlight available.";
        } catch (Exception e) { return "Flashlight error: " + e.getMessage(); }
    }

    private String setVolume(int percent) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) return "Audio service unavailable.";
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int value = Math.round(max * Math.max(0, Math.min(100, percent)) / 100f);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
        return "Volume set to " + percent + " percent.";
    }

    private String makeCall(String number) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, 43); return "Phone permission requested.";
        }
        startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number))); return "Calling.";
    }

    private String sendSms(String number, String message) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, 44); return "SMS permission requested.";
        }
        SmsManager.getDefault().sendTextMessage(number, null, message, null, null); return "SMS sent.";
    }
}
