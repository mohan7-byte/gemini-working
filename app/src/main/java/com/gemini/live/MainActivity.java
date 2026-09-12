package com.gemini.live;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;

/** Native Android entry point. No WebView, Chromium, HTML, or JavaScript runtime. */
public final class MainActivity extends Activity implements NativeGeminiClient.Listener {
    static MainActivity instance;

    private static final int REQ_AUDIO = 41;
    private static final String PREFS = "voice_native";
    private static final String DEFAULT_MODEL = "models/gemini-3.1-flash-live-preview";
    private static final String DEFAULT_VOICE = "Aoede";
    private static final String DEFAULT_PROMPT =
            "You are Voice, a fast autonomous Android agent. Read the Android screen before interacting with another app. " +
            "Use element IDs when possible, keep confirmations concise, and execute actions in sequence.";

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private NativeGeminiClient client;
    private TextView status;
    private TextView detail;
    private EditText apiKey;
    private EditText model;
    private EditText voice;
    private EditText prompt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, float size) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(Color.WHITE);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF8892A0);
        e.setPadding(dp(8), dp(6), dp(8), dp(6));
        return e;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xFF080A10);

        TextView title = label("VOICE • Native Android", 24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = label("No WebView • No Chromium • Native audio", 13);
        subtitle.setTextColor(0xFF9BA6B2);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle);

        status = label("Idle", 19);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(20), 0, dp(4));
        root.addView(status);

        detail = label("Ready", 13);
        detail.setTextColor(0xFF9BA6B2);
        detail.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(detail);

        root.addView(actionButton("Start voice", v -> startSession()));
        root.addView(actionButton("Stop voice", v -> stopSession()));
        root.addView(actionButton("Accessibility settings", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(label("Settings", 18));
        apiKey = input("Gemini API key", prefs.getString("api_key", ""));
        model = input("Live model", prefs.getString("model", DEFAULT_MODEL));
        voice = input("Voice", prefs.getString("voice", DEFAULT_VOICE));
        prompt = input("System prompt", prefs.getString("prompt", DEFAULT_PROMPT));
        prompt.setMinLines(4);
        root.addView(apiKey);
        root.addView(model);
        root.addView(voice);
        root.addView(prompt);
        root.addView(actionButton("Save settings", v -> saveSettings()));

        TextView memory = label("RAM strategy: audio/network are allocated only while a session is active; no browser engine is started.", 12);
        memory.setTextColor(0xFF9BA6B2);
        memory.setPadding(0, dp(14), 0, dp(4));
        root.addView(memory);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveSettings() {
        prefs.edit()
                .putString("api_key", apiKey.getText().toString().trim())
                .putString("model", model.getText().toString().trim())
                .putString("voice", voice.getText().toString().trim())
                .putString("prompt", prompt.getText().toString())
                .apply();
        setState("Saved", "Settings stored locally");
    }

    private void startSession() {
        if (client != null) return;
        saveSettings();
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) {
            setState("Waiting", "Enter a Gemini API key");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        setState("Connecting", "Native audio link");
        client = new NativeGeminiClient(
                this,
                key,
                prefs.getString("model", DEFAULT_MODEL),
                prefs.getString("voice", DEFAULT_VOICE),
                prefs.getString("prompt", DEFAULT_PROMPT),
                true);
        client.start();
    }

    private void stopSession() {
        NativeGeminiClient c = client;
        client = null;
        if (c != null) c.stop();
        setState("Idle", "Ready");
    }

    private void setState(String title, String text) {
        main.post(() -> {
            if (status != null) status.setText(title);
            if (detail != null) detail.setText(text);
        });
    }

    @Override
    public void onState(String state, String d) {
        String title;
        if ("speaking".equals(state)) title = "Speaking";
        else if ("working".equals(state)) title = "Working";
        else if ("connecting".equals(state)) title = "Connecting";
        else if ("listening".equals(state)) title = "Listening";
        else title = state;
        setState(title, d);
    }

    @Override
    public void onError(String error) {
        setState("Error", error == null ? "Unknown error" : error);
    }

    @Override
    public void onClosed() {
        main.post(this::stopSession);
    }

    @Override
    protected void onDestroy() {
        stopSession();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE && client != null) {
            stopSession();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startSession();
        } else if (requestCode == REQ_AUDIO) {
            setState("Permission", "Microphone permission is required for voice");
        }
    }

    static String executeNativeTool(String name, JSONObject args, NativeGeminiClient c) {
        MainActivity a = instance;
        if (a == null) return "App is not active.";
        if (args == null) args = new JSONObject();
        try {
            VolumeTriggerService svc = VolumeTriggerService.instance;
            switch (name) {
                case "get_device_info":
                    return a.deviceInfo();
                case "read_screen_text":
                    return svc == null ? "Accessibility Service required." : svc.read();
                case "tap_element_id":
                    return svc == null ? "Accessibility Service required." : svc.tapId(args.optInt("element_id", 0));
                case "long_press_element_id":
                    return svc == null ? "Accessibility Service required." : svc.longId(args.optInt("element_id", 0));
                case "replace_text":
                    return svc == null ? "Accessibility Service required." : svc.replace(args.optString("text", ""));
                case "clear_text":
                    return svc == null ? "Accessibility Service required." : svc.replace("");
                case "type_text":
                    return svc == null ? "Accessibility Service required." : svc.type(args.optString("text", ""));
                case "tap_element":
                    return svc == null ? "Accessibility Service required." : svc.tapLabel(args.optString("label", ""));
                case "tap_coordinates":
                    return svc == null ? "Accessibility Service required." : svc.tap(a.normX(args.optInt("x", 500)), a.normY(args.optInt("y", 500))) ? "Tapped" : "Tap failed";
                case "long_press":
                    return svc == null ? "Accessibility Service required." : svc.longPress(a.normX(args.optInt("x", 500)), a.normY(args.optInt("y", 500))) ? "Long-pressed" : "Long press failed";
                case "scroll":
                    return svc == null ? "Accessibility Service required." : svc.scroll(args.optString("direction", "down")) ? "Scrolled" : "Scroll failed";
                case "swipe":
                    return svc == null ? "Accessibility Service required." : svc.swipe(a.normX(args.optInt("start_x", 100)), a.normY(args.optInt("start_y", 500)), a.normX(args.optInt("end_x", 900)), a.normY(args.optInt("end_y", 500)), Math.max(100, args.optInt("duration_ms", 300))) ? "Swiped" : "Swipe failed";
                case "wait_seconds":
                    Thread.sleep(Math.max(1, Math.min(4, args.optInt("seconds", 1))) * 1000L);
                    return "Waited.";
                case "open_application":
                    return a.openApplication(args.optString("app_name", ""));
                case "search_web":
                    return a.openUrl("https://www.google.com/search?q=" + Uri.encode(args.optString("query", "")));
                case "search_youtube":
                    return a.openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(args.optString("query", "")));
                case "open_whatsapp":
                    return a.openUrl("https://wa.me/" + args.optString("phone_number", "").replaceAll("[^0-9]", "") + "?text=" + Uri.encode(args.optString("message", "")));
                case "toggle_flashlight":
                    return a.flashlight(args.optBoolean("state", false));
                case "set_volume":
                    return a.setVolume(args.optInt("level_percent", 50));
                case "navigate_system":
                    if (svc == null) return "Accessibility Service required.";
                    String action = args.optString("action", "home");
                    return svc.navigate(action);
                case "make_phone_call":
                    return a.makeCall(args.optString("phone_number", ""));
                case "send_sms":
                    return a.sendSms(args.optString("phone_number", ""), args.optString("message", ""));
                case "create_note":
                    return a.openUrl("mailto:?subject=Voice%20Note&body=" + Uri.encode(args.optString("text", "")));
                case "search_contacts":
                    return "Contacts search is deferred until READ_CONTACTS is granted.";
                case "save_app_rule":
                    return "Rule storage is deferred to the native playbook layer.";
                case "capture_screen":
                    return "Screen capture is not active in the lightweight build yet.";
                case "search_internet":
                    return a.openUrl("https://www.google.com/search?q=" + Uri.encode(args.optString("query", "")));
                default:
                    return "Unsupported native tool: " + name;
            }
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    private int normX(int value) {
        return Math.round(getResources().getDisplayMetrics().widthPixels * Math.max(0, Math.min(1000, value)) / 1000f);
    }

    private int normY(int value) {
        return Math.round(getResources().getDisplayMetrics().heightPixels * Math.max(0, Math.min(1000, value)) / 1000f);
    }

    private String deviceInfo() {
        BatteryManager b = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int battery = b == null ? -1 : b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        ActivityManagerCompat.Memory memory = ActivityManagerCompat.memory(this);
        return "{\"battery\":" + battery + ",\"low_ram_device\":" + memory.lowRam +",\"memory_class_mb\":" + memory.memoryClassMb + "}";
    }

    private String openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return "Opened.";
        } catch (Exception e) {
            return "Could not open URL: " + e.getMessage();
        }
    }

    private String openApplication(String label) {
        PackageManager pm = getPackageManager();
        for (android.content.pm.ApplicationInfo info : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            String name = pm.getApplicationLabel(info).toString();
            if (name.equalsIgnoreCase(label) || name.toLowerCase().contains(label.toLowerCase())) {
                Intent i = pm.getLaunchIntentForPackage(info.packageName);
                if (i != null) {
                    startActivity(i);
                    return "Opened " + name;
                }
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
                    manager.setTorchMode(id, enabled);
                    return enabled ? "Flashlight on" : "Flashlight off";
                }
            }
            return "No flashlight available.";
        } catch (Exception e) {
            return "Flashlight error: " + e.getMessage();
        }
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
            requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, 43);
            return "Phone permission requested.";
        }
        startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number)));
        return "Calling.";
    }

    private String sendSms(String number, String message) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, 44);
            return "SMS permission requested.";
        }
        SmsManager.getDefault().sendTextMessage(number, null, message, null, null);
        return "SMS sent.";
    }

    private static final class ActivityManagerCompat {
        static Memory memory(Context context) {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
            if (am == null) return new Memory(false, 0);
            return new Memory(am.isLowRamDevice(), am.getMemoryClass());
        }
        static final class Memory {
            final boolean lowRam;
            final int memoryClassMb;
            Memory(boolean lowRam, int memoryClassMb) { this.lowRam = lowRam; this.memoryClassMb = memoryClassMb; }
        }
    }
}
