package com.gemini.live;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Small dependency-free Gemini Live client.
 * It intentionally avoids WebView, Chromium, JavaScript audio APIs, and external
 * WebSocket/audio libraries. Audio is PCM end-to-end using Android framework APIs.
 */
public final class NativeGeminiClient {
    public interface Listener {
        void onState(String state, String detail);
        void onError(String error);
        void onClosed();
    }

    private final Listener listener;
    private final String apiKey;
    private final String model;
    private final String voice;
    private final String systemPrompt;
    private final boolean echoGuard;

    private volatile boolean running;
    private volatile boolean muted;
    private volatile boolean modelSpeaking;
    private WebSocket socket;
    private AudioRecord recorder;
    private Thread captureThread;
    private AudioTrack audioTrack;
    private AudioPlayback playback;
    private Thread readerThread;

    public NativeGeminiClient(Listener listener, String apiKey, String model, String voice,
                              String systemPrompt, boolean echoGuard) {
        this.listener = listener;
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.systemPrompt = systemPrompt;
        this.echoGuard = echoGuard;
    }

    public void start() {
        if (running) return;
        running = true;
        new Thread(this::connect, "gemini-connect").start();
    }

    public void stop() {
        running = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignored) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        if (captureThread != null) captureThread.interrupt();
        captureThread = null;
        if (playback != null) playback.stop();
        playback = null;
        if (audioTrack != null) {
            try { audioTrack.pause(); } catch (Exception ignored) {}
            try { audioTrack.flush(); } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
        }
        audioTrack = null;
        if (socket != null) socket.close();
        socket = null;
    }

    public void setMuted(boolean value) { muted = value; }
    public boolean isModelSpeaking() { return modelSpeaking; }

    public void sendImageBase64(String jpegBase64) {
        if (!running || socket == null || !socket.isOpen()) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject input = new JSONObject();
            JSONObject video = new JSONObject();
            video.put("data", jpegBase64);
            video.put("mimeType", "image/jpeg");
            input.put("video", video);
            root.put("realtimeInput", input);
            socket.sendText(root.toString());
        } catch (Exception e) {
            listener.onError("Could not send image: " + e.getMessage());
        }
    }

    public void sendToolResponse(String id, Object result) {
        if (socket == null || !socket.isOpen()) return;
        try {
            JSONObject response = new JSONObject();
            JSONObject output = new JSONObject();
            output.put("result", String.valueOf(result));
            JSONObject functionResponse = new JSONObject();
            functionResponse.put("id", id == null ? "call_1" : id);
            functionResponse.put("response", new JSONObject().put("output", output));
            JSONArray responses = new JSONArray().put(functionResponse);
            JSONObject toolResponse = new JSONObject().put("functionResponses", responses);
            socket.sendText(new JSONObject().put("toolResponse", toolResponse).toString());
        } catch (Exception e) {
            listener.onError("Tool response failed: " + e.getMessage());
        }
    }

    private void connect() {
        try {
            listener.onState("connecting", "Opening native audio link");
            URI uri = new URI("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + apiKey);
            socket = new WebSocket(uri);
            socket.connect();
            sendSetup();
            openAudio();
            readerThread = new Thread(this::readLoop, "gemini-reader");
            readerThread.start();
            startCapture();
            listener.onState("listening", "Go ahead");
        } catch (Exception e) {
            listener.onError("Connection failed: " + e.getMessage());
            stop();
        }
    }

    private void sendSetup() throws Exception {
        String prompt = systemPrompt == null ? "" : systemPrompt.trim();
        String live = "\\n\\nLIVE DEVICE TELEMETRY:\\n- Exact Local Time: " +
                java.text.DateFormat.getTimeInstance().format(new java.util.Date()) +
                "\\n- Current Date: " + new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(new java.util.Date()) +
                "\\n- Timezone: " + java.util.TimeZone.getDefault().getID() +
                "\\nAnswer current time/date questions directly from this telemetry.";
        if (prompt.length() == 0) prompt = defaultPrompt();

        JSONObject setup = new JSONObject();
        JSONObject cfg = new JSONObject();
        JSONArray modalities = new JSONArray().put("AUDIO");
        cfg.put("responseModalities", modalities);
        cfg.put("speechConfig", new JSONObject().put("voiceConfig",
                new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice))));

        JSONObject instruction = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt + live)));
        setup.put("model", model);
        setup.put("generationConfig", cfg);
        setup.put("systemInstruction", instruction);
        setup.put("tools", toolSchema());

        socket.sendText(new JSONObject().put("setup", setup).toString());
    }

    private JSONArray toolSchema() {
        JSONArray groups = new JSONArray();
        JSONArray funcs = new JSONArray();
        funcs.put(fn("search_internet", "Silently fetch current web facts. Use for real-time facts, sports, news and schedules.", obj("query", "STRING"), req("query")));
        funcs.put(fn("tap_element_id", "Tap a candidate element ID returned by read_screen_text.", obj("element_id", "INTEGER"), req("element_id")));
        funcs.put(fn("long_press_element_id", "Long-press a candidate element ID returned by read_screen_text.", obj("element_id", "INTEGER"), req("element_id")));
        funcs.put(fn("replace_text", "Replace the active editable field.", obj("text", "STRING"), req("text")));
        funcs.put(fn("clear_text", "Clear the active editable field.", new JSONObject(), new JSONArray()));
        funcs.put(fn("get_device_info", "Get local time, battery, network and memory telemetry.", new JSONObject(), new JSONArray()));
        JSONObject saveProps = obj("app_package", "STRING");
        saveProps.put("action_name", prop("STRING")); saveProps.put("x", prop("INTEGER")); saveProps.put("y", prop("INTEGER")); saveProps.put("rule", prop("STRING"));
        funcs.put(fn("save_app_rule", "Persist a learned Android app rule or coordinate.", saveProps, req("app_package")));
        funcs.put(fn("create_note", "Open a native note composer with the given content.", obj("text", "STRING"), req("text")));
        funcs.put(fn("type_text", "Insert text into the active editable field.", obj("text", "STRING"), req("text")));
        JSONObject xy = obj("x", "INTEGER"); xy.put("y", prop("INTEGER"));
        funcs.put(fn("tap_coordinates", "Tap normalized screen coordinates from 0 to 1000.", xy, req("x", "y")));
        funcs.put(fn("long_press", "Long press normalized screen coordinates from 0 to 1000.", xy, req("x", "y")));
        funcs.put(fn("tap_element", "Tap an element matching a label or description.", obj("label", "STRING"), req("label")));
        funcs.put(fn("scroll", "Scroll the active screen up or down.", obj("direction", "STRING"), req("direction")));
        JSONObject swipe = obj("start_x", "INTEGER"); swipe.put("start_y", prop("INTEGER")); swipe.put("end_x", prop("INTEGER")); swipe.put("end_y", prop("INTEGER")); swipe.put("duration_ms", prop("INTEGER"));
        funcs.put(fn("swipe", "Perform a physical swipe.", swipe, req("start_x", "start_y", "end_x", "end_y")));
        funcs.put(fn("wait_seconds", "Pause for one to four seconds for an app to load.", obj("seconds", "NUMBER"), req("seconds")));
        funcs.put(fn("read_screen_text", "Extract visible text and interactive elements from the current Android screen.", new JSONObject(), new JSONArray()));
        funcs.put(fn("capture_screen", "Capture the screen and send the image into the visual feed.", new JSONObject(), new JSONArray()));
        funcs.put(fn("open_application", "Launch an installed Android application by label.", obj("app_name", "STRING"), req("app_name")));
        funcs.put(fn("search_contacts", "Search Android contacts by display name.", obj("query", "STRING"), req("query")));
        funcs.put(fn("search_youtube", "Open YouTube with a query.", obj("query", "STRING"), req("query")));
        funcs.put(fn("search_web", "Open the browser with a search only when explicitly requested.", obj("query", "STRING"), req("query")));
        JSONObject wa = obj("phone_number", "STRING"); wa.put("message", prop("STRING"));
        funcs.put(fn("open_whatsapp", "Open WhatsApp chat with a prefilled message.", wa, req("phone_number", "message")));
        funcs.put(fn("toggle_flashlight", "Turn the flashlight on or off.", obj("state", "BOOLEAN"), req("state")));
        funcs.put(fn("set_volume", "Set media volume as 0 to 100 percent.", obj("level_percent", "INTEGER"), req("level_percent")));
        funcs.put(fn("navigate_system", "Use Android system navigation.", obj("action", "STRING"), req("action")));
        funcs.put(fn("make_phone_call", "Call a phone number.", obj("phone_number", "STRING"), req("phone_number")));
        JSONObject sms = obj("phone_number", "STRING"); sms.put("message", prop("STRING"));
        funcs.put(fn("send_sms", "Send an SMS.", sms, req("phone_number", "message")));
        groups.put(new JSONObject().put("functionDeclarations", funcs));
        return groups;
    }

    private static JSONObject fn(String name, String desc, JSONObject properties, JSONArray required) {
        return new JSONObject().put("name", name).put("description", desc).put("parameters",
                new JSONObject().put("type", "OBJECT").put("properties", properties).put("required", required));
    }
    private static JSONObject obj(String key, String type) { return new JSONObject().put(key, prop(type)); }
    private static JSONObject prop(String type) { return new JSONObject().put("type", type); }
    private static JSONArray req(String... names) { JSONArray a = new JSONArray(); for (String n : names) a.put(n); return a; }

    private void openAudio() {
        int minRec = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int recSize = Math.max(minRec, 3200 * 2);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recSize);
        int minPlay = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minPlay, 24000), AudioTrack.MODE_STREAM);
        audioTrack.play();
        playback = new AudioPlayback(audioTrack);
    }

    private void startCapture() {
        recorder.startRecording();
        captureThread = new Thread(() -> {
            byte[] pcm = new byte[6400];
            while (running) {
                int read;
                try { read = recorder.read(pcm, 0, pcm.length); } catch (Exception e) { break; }
                if (read <= 0) continue;
                if (muted || (echoGuard && modelSpeaking)) continue;
                if (socket == null || !socket.isOpen()) continue;
                try {
                    JSONObject audio = new JSONObject();
                    JSONObject realtime = new JSONObject();
                    JSONObject part = new JSONObject();
                    part.put("data", Base64.encodeToString(pcm, 0, read, Base64.NO_WRAP));
                    part.put("mimeType", "audio/pcm;rate=16000");
                    realtime.put("audio", part);
                    audio.put("realtimeInput", realtime);
                    socket.sendText(audio.toString());
                } catch (Exception e) {
                    listener.onError("Audio send error: " + e.getMessage());
                }
            }
        }, "gemini-mic");
        captureThread.start();
    }

    private void readLoop() {
        try {
            while (running && socket != null && socket.isOpen()) {
                String text = socket.readText();
                if (text == null) break;
                handleMessage(new JSONObject(text));
            }
        } catch (Exception e) {
            if (running) listener.onError("Live connection closed: " + e.getMessage());
        } finally {
            if (running) {
                running = false;
                listener.onClosed();
            }
        }
    }

    private void handleMessage(JSONObject msg) {
        try {
            JSONObject toolCall = msg.optJSONObject("toolCall");
            if (toolCall != null) {
                JSONArray calls = toolCall.optJSONArray("functionCalls");
                if (calls != null) for (int i = 0; i < calls.length(); i++) executeFunction(calls.optJSONObject(i));
            }
            JSONObject content = msg.optJSONObject("serverContent");
            if (content == null) return;
            if (content.optBoolean("interrupted", false)) {
                modelSpeaking = false;
                if (playback != null) playback.flush();
                listener.onState("listening", "Go ahead");
                return;
            }
            JSONObject modelTurn = content.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        JSONObject call = part.optJSONObject("functionCall");
                        if (call != null) executeFunction(call);
                        JSONObject inline = part.optJSONObject("inlineData");
                        if (inline != null) {
                            String data = inline.optString("data", null);
                            if (data != null) {
                                modelSpeaking = true;
                                listener.onState("speaking", "Voice response");
                                if (playback != null) playback.offer(Base64.decode(data, Base64.DEFAULT));
                            }
                        }
                    }
                }
            }
            if (content.optBoolean("turnComplete", false)) {
                listener.onState("listening", "Go ahead");
                modelSpeaking = false;
            }
        } catch (Exception e) {
            listener.onError("Response parse error: " + e.getMessage());
        }
    }

    private void executeFunction(JSONObject call) {
        String name = call.optString("name", "");
        String id = call.optString("id", UUID.randomUUID().toString());
        JSONObject args = call.optJSONObject("args");
        new Thread(() -> {
            String result;
            try {
                listener.onState("working", actionDescription(name, args));
                result = MainActivity.executeNativeTool(name, args, this);
            } catch (Exception e) {
                result = "Error executing tool: " + e.getMessage();
            }
            sendToolResponse(id, result);
            if (running) listener.onState("listening", "Go ahead");
        }, "tool-" + name).start();
    }

    private static String actionDescription(String name, JSONObject args) {
        if (args == null) return "Working...";
        switch (name) {
            case "search_internet": return "Searching the web...";
            case "tap_element_id": return "Tapping element #" + args.optInt("element_id");
            case "long_press_element_id": return "Holding element #" + args.optInt("element_id");
            case "read_screen_text": return "Reading the screen...";
            case "capture_screen": return "Analyzing the screen...";
            case "open_application": return "Opening " + args.optString("app_name", "app") + "...";
            case "type_text": return "Typing text...";
            case "search_contacts": return "Searching contacts...";
            case "make_phone_call": return "Calling...";
            case "send_sms": return "Sending SMS...";
            default: return "Working...";
        }
    }

    private static String defaultPrompt() {
        return "You are Voice, a fast autonomous Android agent with direct device control and real-time web intelligence. " +
                "Use search_internet for current facts and news. Read the screen before interacting with another app. " +
                "Prefer element IDs, use replace_text for editing fields, and keep confirmations concise. " +
                "Execute device actions in sequence and do not narrate intermediate steps unless needed.";
    }

    private static final class AudioPlayback {
        private final AudioTrack track;
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(24);
        private final Thread thread;
        private volatile boolean running = true;
        AudioPlayback(AudioTrack track) {
            this.track = track;
            thread = new Thread(() -> {
                while (running) {
                    try {
                        byte[] b = queue.poll(500, TimeUnit.MILLISECONDS);
                        if (b != null && b.length > 0) track.write(b, 0, b.length);
                    } catch (InterruptedException ignored) { break; }
                    catch (Exception ignored) { break; }
                }
            }, "gemini-audio-out");
            thread.start();
        }
        void offer(byte[] data) {
            if (data == null) return;
            while (!queue.offer(data)) queue.poll();
        }
        void flush() { queue.clear(); try { track.pause(); track.flush(); track.play(); } catch (Exception ignored) {} }
        void stop() { running = false; thread.interrupt(); queue.clear(); }
    }

    private static final class WebSocket {
        private final URI uri;
        private SSLSocket socket;
        private InputStream in;
        private OutputStream out;
        private final Object writeLock = new Object();
        private volatile boolean open;
        WebSocket(URI uri) { this.uri = uri; }
        void connect() throws Exception {
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            socket.setUseClientMode(true);
            if (Build.VERSION.SDK_INT >= 24) socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
            socket.connect(new InetSocketAddress(host, port), 15000);
            socket.startHandshake();
            in = socket.getInputStream(); out = socket.getOutputStream();
            byte[] keyBytes = new byte[16]; new SecureRandom().nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String req = "GET " + path + " HTTP/1.1\\r\\nHost: " + host + "\\r\\nUpgrade: websocket\\r\\nConnection: Upgrade\\r\\nSec-WebSocket-Key: " + key + "\\r\\nSec-WebSocket-Version: 13\\r\\n\\r\\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII)); out.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String status = r.readLine();
            if (status == null || !status.contains("101")) throw new EOFException("WebSocket handshake rejected: " + status);
            String line; while ((line = r.readLine()) != null && !line.isEmpty()) {}
            open = true;
        }
        boolean isOpen() { return open && socket != null && socket.isConnected() && !socket.isClosed(); }
        void sendText(String text) throws Exception { byte[] b = text.getBytes(StandardCharsets.UTF_8); sendFrame(0x1, b); }
        private void sendFrame(int opcode, byte[] payload) throws Exception {
            synchronized (writeLock) {
                if (!isOpen()) return;
                ByteArrayOutputStream header = new ByteArrayOutputStream(14);
                header.write(0x80 | (opcode & 0x0f));
                int n = payload.length;
                int first = 0x80;
                if (n < 126) { header.write(first | n); }
                else if (n <= 65535) { header.write(first | 126); header.write((n >>> 8) & 0xff); header.write(n & 0xff); }
                else { header.write(first | 127); long x = n; for (int i=7;i>=0;i--) header.write((int)(x >>> (8*i)) & 0xff); }
                byte[] mask = new byte[4]; new SecureRandom().nextBytes(mask); header.write(mask);
                byte[] masked = new byte[n]; for (int i=0;i<n;i++) masked[i] = (byte)(payload[i] ^ mask[i&3]);
                out.write(header.toByteArray()); out.write(masked); out.flush();
            }
        }
        String readText() throws Exception {
            while (true) {
                int b0 = in.read(); if (b0 < 0) return null;
                int b1 = in.read(); if (b1 < 0) return null;
                int opcode = b0 & 0x0f; boolean masked = (b1 & 0x80) != 0; long len = b1 & 0x7f;
                if (len == 126) len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                else if (len == 127) { len = 0; for (int i=0;i<8;i++) len = (len<<8) | (in.read() & 0xff); }
                if (len > 2_000_000) throw new EOFException("Frame too large");
                byte[] mask = masked ? readFully(4) : null;
                byte[] payload = readFully((int)len);
                if (masked) for (int i=0;i<payload.length;i++) payload[i] = (byte)(payload[i] ^ mask[i&3]);
                if (opcode == 0x8) { open=false; return null; }
                if (opcode == 0x9) { sendFrame(0xA, payload); continue; }
                if (opcode == 0xA) continue;
                if (opcode == 0x1 || opcode == 0x0) return new String(payload, StandardCharsets.UTF_8);
            }
        }
        private byte[] readFully(int n) throws Exception { byte[] b = new byte[n]; int p=0; while(p<n){int r=in.read(b,p,n-p); if(r<0)throw new EOFException(); p+=r;} return b; }
        void close() { open=false; try{if(socket!=null)socket.close();}catch(Exception ignored){} }
    }
}
