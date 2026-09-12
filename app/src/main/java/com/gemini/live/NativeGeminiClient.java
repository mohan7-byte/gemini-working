package com.gemini.live;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Dependency-free Gemini Live transport.
 * No WebView, Chromium, JavaScript audio APIs, or third-party runtime libraries.
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

    private SSLSocket socket;
    private InputStream input;
    private OutputStream output;
    private AudioRecord recorder;
    private AudioTrack audioTrack;
    private AudioPlayback playback;
    private Thread connectThread;
    private Thread readerThread;
    private Thread captureThread;

    public NativeGeminiClient(Listener listener, String apiKey, String model, String voice,
                              String systemPrompt, boolean echoGuard) {
        this.listener = listener;
        this.apiKey = apiKey;
        this.model = model;
        this.voice = voice;
        this.systemPrompt = systemPrompt;
        this.echoGuard = echoGuard;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        connectThread = new Thread(this::connect, "voice-connect");
        connectThread.start();
    }

    public synchronized void stop() {
        running = false;
        closeAudio();
        closeSocket();
        interrupt(connectThread);
        interrupt(readerThread);
        interrupt(captureThread);
        connectThread = null;
        readerThread = null;
        captureThread = null;
    }

    public void setMuted(boolean value) { muted = value; }
    public boolean isModelSpeaking() { return modelSpeaking; }

    public void sendImageBase64(String jpegBase64) {
        if (!running || jpegBase64 == null || jpegBase64.isEmpty()) return;
        try {
            JSONObject video = new JSONObject();
            video.put("data", jpegBase64);
            video.put("mimeType", "image/jpeg");
            JSONObject realtime = new JSONObject();
            realtime.put("video", video);
            JSONObject root = new JSONObject();
            root.put("realtimeInput", realtime);
            sendText(root.toString());
        } catch (Exception e) {
            listener.onError("Image send failed: " + e.getMessage());
        }
    }

    public void sendToolResponse(String id, Object result) {
        try {
            JSONObject response = new JSONObject();
            response.put("output", String.valueOf(result));
            JSONObject function = new JSONObject();
            function.put("id", id == null || id.isEmpty() ? "call_1" : id);
            function.put("response", response);
            JSONArray calls = new JSONArray();
            calls.put(function);
            JSONObject tool = new JSONObject();
            tool.put("functionResponses", calls);
            JSONObject root = new JSONObject();
            root.put("toolResponse", tool);
            sendText(root.toString());
        } catch (Exception e) {
            listener.onError("Tool response failed: " + e.getMessage());
        }
    }

    private void connect() {
        try {
            listener.onState("connecting", "Opening native audio link");
            openWebSocket();
            sendSetup();
            openAudio();
            readerThread = new Thread(this::readLoop, "voice-reader");
            captureThread = new Thread(this::captureLoop, "voice-mic");
            readerThread.start();
            captureThread.start();
            listener.onState("listening", "Go ahead");
        } catch (Exception e) {
            if (running) listener.onError("Connection failed: " + e.getMessage());
            stop();
        }
    }

    private void openWebSocket() throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) factory.createSocket();
        s.connect(new InetSocketAddress("generativelanguage.googleapis.com", 443), 10000);
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.startHandshake();

        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String wsKey = Base64.encodeToString(random, Base64.NO_WRAP);
        String path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name());
        String headers = "GET " + path + " HTTP/1.1\r\n"
                + "Host: generativelanguage.googleapis.com\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String status = readHttpStatus(in);
        if (!status.contains(" 101 ")) throw new IOException("WebSocket handshake rejected: " + status);
        input = in;
        output = out;
        socket = s;
    }

    private static String readHttpStatus(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean first = true;
        int b;
        while (true) {
            b = in.read();
            if (b < 0) throw new EOFException("Handshake ended early");
            if (b == '\r') continue;
            if (b == '\n') {
                if (first) return line.toString();
                if (line.length() == 0) return "HTTP/1.1 000 empty";
                line.setLength(0);
                first = false;
            } else {
                line.append((char) b);
                if (!first && line.length() > 8192) throw new IOException("Invalid handshake");
            }
            if (!first && b == '\n' && line.length() == 0) return "HTTP/1.1 101";
        }
    }

    private void sendSetup() throws Exception {
        String prompt = systemPrompt == null ? "" : systemPrompt.trim();
        if (prompt.isEmpty()) prompt = defaultPrompt();

        JSONObject cfg = new JSONObject();
        cfg.put("responseModalities", new JSONArray().put("AUDIO"));
        JSONObject voiceConfig = new JSONObject();
        voiceConfig.put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice));
        cfg.put("speechConfig", new JSONObject().put("voiceConfig", voiceConfig));

        JSONObject instruction = new JSONObject();
        instruction.put("parts", new JSONArray().put(new JSONObject().put("text", prompt + telemetry())));

        JSONObject setup = new JSONObject();
        setup.put("model", model);
        setup.put("generationConfig", cfg);
        setup.put("systemInstruction", instruction);
        setup.put("tools", toolSchema());

        sendText(new JSONObject().put("setup", setup).toString());
    }

    private String telemetry() {
        String date = new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(new java.util.Date());
        String time = java.text.DateFormat.getTimeInstance().format(new java.util.Date());
        return "\n\nDEVICE TELEMETRY:\n- Local time: " + time +
                "\n- Date: " + date +
                "\n- Timezone: " + TimeZone.getDefault().getID() +
                "\nAnswer time/date questions from this telemetry.";
    }

    private static String defaultPrompt() {
        return "You are Voice, a fast autonomous Android agent. Read the Android screen before interacting with another app. " +
                "Prefer element IDs when available, keep confirmations concise, and execute actions in sequence.";
    }

    private JSONArray toolSchema() throws Exception {
        JSONArray groups = new JSONArray();
        JSONArray funcs = new JSONArray();
        funcs.put(fn("search_internet", "Search current web facts.", obj("query", "STRING"), req("query")));
        funcs.put(fn("get_device_info", "Get battery and memory telemetry.", new JSONObject(), req()));
        funcs.put(fn("read_screen_text", "Read visible Android text and interactive elements.", new JSONObject(), req()));
        funcs.put(fn("tap_element_id", "Tap a previously discovered element id.", obj("element_id", "INTEGER"), req("element_id")));
        funcs.put(fn("long_press_element_id", "Long press a previously discovered element id.", obj("element_id", "INTEGER"), req("element_id")));
        funcs.put(fn("replace_text", "Replace the active editable field.", obj("text", "STRING"), req("text")));
        funcs.put(fn("clear_text", "Clear the active editable field.", new JSONObject(), req()));
        funcs.put(fn("type_text", "Type text into the active editable field.", obj("text", "STRING"), req("text")));
        JSONObject xy = obj("x", "INTEGER");
        xy.put("y", prop("INTEGER"));
        funcs.put(fn("tap_coordinates", "Tap normalized 0..1000 coordinates.", xy, req("x", "y")));
        funcs.put(fn("long_press", "Long press normalized 0..1000 coordinates.", xy, req("x", "y")));
        funcs.put(fn("scroll", "Scroll the active screen.", obj("direction", "STRING"), req("direction")));
        JSONObject swipe = obj("start_x", "INTEGER");
        swipe.put("start_y", prop("INTEGER"));
        swipe.put("end_x", prop("INTEGER"));
        swipe.put("end_y", prop("INTEGER"));
        swipe.put("duration_ms", prop("INTEGER"));
        funcs.put(fn("swipe", "Perform a swipe.", swipe, req("start_x", "start_y", "end_x", "end_y")));
        funcs.put(fn("wait_seconds", "Wait one to four seconds.", obj("seconds", "NUMBER"), req("seconds")));
        funcs.put(fn("open_application", "Launch an installed Android application.", obj("app_name", "STRING"), req("app_name")));
        funcs.put(fn("search_contacts", "Search contacts.", obj("query", "STRING"), req("query")));
        funcs.put(fn("search_youtube", "Open YouTube search.", obj("query", "STRING"), req("query")));
        funcs.put(fn("search_web", "Open browser search.", obj("query", "STRING"), req("query")));
        JSONObject wa = obj("phone_number", "STRING");
        wa.put("message", prop("STRING"));
        funcs.put(fn("open_whatsapp", "Open WhatsApp with a prefilled message.", wa, req("phone_number", "message")));
        funcs.put(fn("toggle_flashlight", "Toggle flashlight.", obj("state", "BOOLEAN"), req("state")));
        funcs.put(fn("set_volume", "Set media volume percentage.", obj("level_percent", "INTEGER"), req("level_percent")));
        funcs.put(fn("navigate_system", "Use system navigation.", obj("action", "STRING"), req("action")));
        funcs.put(fn("make_phone_call", "Call a phone number.", obj("phone_number", "STRING"), req("phone_number")));
        JSONObject sms = obj("phone_number", "STRING");
        sms.put("message", prop("STRING"));
        funcs.put(fn("send_sms", "Send an SMS.", sms, req("phone_number", "message")));
        funcs.put(fn("create_note", "Open a note composer.", obj("text", "STRING"), req("text")));
        funcs.put(fn("capture_screen", "Capture the screen if available.", new JSONObject(), req()));
        funcs.put(fn("save_app_rule", "Save an app automation rule.", obj("app_package", "STRING"), req("app_package")));
        groups.put(new JSONObject().put("functionDeclarations", funcs));
        return groups;
    }

    private static JSONObject fn(String name, String desc, JSONObject props, JSONArray required) throws Exception {
        JSONObject params = new JSONObject();
        params.put("type", "OBJECT");
        params.put("properties", props);
        params.put("required", required);
        return new JSONObject().put("name", name).put("description", desc).put("parameters", params);
    }

    private static JSONObject obj(String key, String type) throws Exception { return new JSONObject().put(key, prop(type)); }
    private static JSONObject prop(String type) throws Exception { return new JSONObject().put("type", type); }
    private static JSONArray req(String... names) throws Exception {
        JSONArray out = new JSONArray();
        for (String name : names) out.put(name);
        return out;
    }

    private void openAudio() throws Exception {
        int recMin = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int recSize = Math.max(4096, recMin > 0 ? recMin * 2 : 8192);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");

        int playMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int playSize = Math.max(8192, playMin > 0 ? playMin : 8192);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, playSize, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio output initialization failed");
        audioTrack.play();
        playback = new AudioPlayback(audioTrack);
    }

    private void captureLoop() {
        try { recorder.startRecording(); } catch (Exception e) { listener.onError("Microphone start failed: " + e.getMessage()); return; }
        byte[] pcm = new byte[6400];
        while (running) {
            int count;
            try { count = recorder.read(pcm, 0, pcm.length); } catch (Exception e) { break; }
            if (count <= 0 || muted || (echoGuard && modelSpeaking) || !running) continue;
            try {
                JSONObject part = new JSONObject();
                part.put("data", Base64.encodeToString(pcm, 0, count, Base64.NO_WRAP));
                part.put("mimeType", "audio/pcm;rate=16000");
                JSONObject realtime = new JSONObject().put("audio", part);
                sendText(new JSONObject().put("realtimeInput", realtime).toString());
            } catch (Exception e) {
                if (running) listener.onError("Audio send failed: " + e.getMessage());
            }
        }
    }

    private void readLoop() {
        try {
            while (running) {
                Frame frame = readMessage();
                if (frame == null) break;
                if (frame.opcode == 9) { sendFrame(10, frame.payload); continue; }
                if (frame.opcode == 8) break;
                if (frame.opcode != 1) continue;
                handleMessage(new JSONObject(new String(frame.payload, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            if (running) listener.onError("Live connection closed: " + e.getMessage());
        } finally {
            boolean wasRunning = running;
            running = false;
            closeAudio();
            closeSocket();
            if (wasRunning) listener.onClosed();
        }
    }

    private void handleMessage(JSONObject msg) {
        try {
            JSONObject rootTool = msg.optJSONObject("toolCall");
            if (rootTool != null) {
                JSONArray calls = rootTool.optJSONArray("functionCalls");
                executeCalls(calls);
            }

            JSONObject content = msg.optJSONObject("serverContent");
            if (content == null) return;
            if (content.optBoolean("interrupted", false)) {
                modelSpeaking = false;
                if (playback != null) playback.flush();
            }

            JSONObject turn = content.optJSONObject("modelTurn");
            if (turn != null) {
                JSONArray parts = turn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        JSONObject call = part.optJSONObject("functionCall");
                        if (call != null) executeCall(call);
                        JSONObject inline = part.optJSONObject("inlineData");
                        if (inline != null) {
                            String data = inline.optString("data", null);
                            if (data != null && playback != null) {
                                modelSpeaking = true;
                                listener.onState("speaking", "Voice response");
                                playback.offer(Base64.decode(data, Base64.DEFAULT));
                            }
                        }
                    }
                }
            }
            if (content.optBoolean("turnComplete", false)) {
                modelSpeaking = false;
                listener.onState("listening", "Go ahead");
            }
        } catch (Exception e) {
            listener.onError("Response parse error: " + e.getMessage());
        }
    }

    private void executeCalls(JSONArray calls) {
        if (calls == null) return;
        for (int i = 0; i < calls.length(); i++) executeCall(calls.optJSONObject(i));
    }

    private void executeCall(JSONObject call) {
        if (call == null) return;
        String id = call.optString("id", "call_1");
        String name = call.optString("name", "");
        JSONObject args = call.optJSONObject("args");
        new Thread(() -> {
            String result;
            try {
                listener.onState("working", name);
                result = MainActivity.executeNativeTool(name, args, this);
            } catch (Exception e) {
                result = "Tool error: " + e.getMessage();
            }
            sendToolResponse(id, result);
            if (running) listener.onState("listening", "Go ahead");
        }, "voice-tool").start();
    }

    private synchronized void sendText(String text) throws IOException {
        sendFrame(1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] data) throws IOException {
        if (output == null) throw new IOException("Socket is not connected");
        int len = data == null ? 0 : data.length;
        output.write(0x80 | (opcode & 0x0F));
        if (len <= 125) {
            output.write(0x80 | len);
        } else if (len <= 65535) {
            output.write(0x80 | 126);
            output.write((len >>> 8) & 0xFF);
            output.write(len & 0xFF);
        } else {
            output.write(0x80 | 127);
            long value = len & 0xFFFFFFFFL;
            for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (value >>> shift) & 0xFF);
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        output.write(mask);
        for (int i = 0; i < len; i++) output.write(data[i] ^ mask[i & 3]);
        output.flush();
    }

    private Frame readMessage() throws IOException {
        Frame first = readFrame();
        if (first == null) return null;
        if (first.opcode == 8 || first.opcode == 9 || first.opcode == 10) return first;
        if (first.fin) return first;
        ByteArrayOutputStream all = new ByteArrayOutputStream(first.payload.length + 1024);
        all.write(first.payload);
        while (true) {
            Frame next = readFrame();
            if (next == null) return null;
            if (next.opcode == 9) { sendFrame(10, next.payload); continue; }
            if (next.opcode == 0) {
                all.write(next.payload);
                if (next.fin) return new Frame(true, first.opcode, all.toByteArray());
            } else if (next.opcode == 8) return next;
        }
    }

    private Frame readFrame() throws IOException {
        int first = input.read();
        if (first < 0) return null;
        int second = input.read();
        if (second < 0) throw new EOFException("Frame ended early");
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0F;
        boolean masked = (second & 0x80) != 0;
        long len = second & 0x7F;
        if (len == 126) len = readUnsignedShort(input);
        else if (len == 127) len = readLong(input);
        if (len > 8_000_000L) throw new IOException("WebSocket frame too large");
        byte[] mask = masked ? readBytes(input, 4) : null;
        byte[] payload = readBytes(input, (int) len);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new Frame(fin, opcode, payload);
    }

    private static int readUnsignedShort(InputStream in) throws IOException {
        int a = in.read(), b = in.read();
        if ((a | b) < 0) throw new EOFException("Short frame length ended early");
        return (a << 8) | b;
    }

    private static long readLong(InputStream in) throws IOException {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException("Long frame length ended early");
            value = (value << 8) | (b & 0xFFL);
        }
        return value;
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("Frame payload ended early");
            offset += count;
        }
        return data;
    }

    private void closeAudio() {
        if (captureThread != null) captureThread.interrupt();
        if (playback != null) playback.stop();
        playback = null;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (audioTrack != null) {
            try { audioTrack.pause(); } catch (Exception ignored) {}
            try { audioTrack.flush(); } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void closeSocket() {
        try { if (output != null) sendFrame(8, new byte[0]); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null; input = null; output = null;
    }

    private static void interrupt(Thread t) { if (t != null) t.interrupt(); }

    private static final class Frame {
        final boolean fin; final int opcode; final byte[] payload;
        Frame(boolean fin, int opcode, byte[] payload) { this.fin = fin; this.opcode = opcode; this.payload = payload; }
    }

    private static final class AudioPlayback {
        private final AudioTrack track;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(6);
        private volatile boolean running = true;
        private final Thread worker;

        AudioPlayback(AudioTrack track) {
            this.track = track;
            worker = new Thread(this::run, "voice-speaker");
            worker.start();
        }

        void offer(byte[] audio) {
            if (audio == null || audio.length == 0 || !running) return;
            if (!queue.offer(audio)) { queue.poll(); queue.offer(audio); }
        }

        void flush() { queue.clear(); }

        void stop() { running = false; queue.clear(); worker.interrupt(); }

        private void run() {
            while (running) {
                try {
                    byte[] audio = queue.take();
                    int offset = 0;
                    while (running && offset < audio.length) {
                        int written = track.write(audio, offset, audio.length - offset);
                        if (written <= 0) break;
                        offset += written;
                    }
                } catch (InterruptedException ignored) {
                    if (!running) return;
                }
            }
        }
    }
}
