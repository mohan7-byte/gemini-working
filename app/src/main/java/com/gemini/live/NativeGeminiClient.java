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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Lightweight native Gemini Live client. No WebView, Chromium, or JavaScript runtime. */
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
    private volatile boolean modelSpeaking;
    private volatile boolean muted;
    private volatile boolean setupComplete;

    private SSLSocket socket;
    private InputStream input;
    private OutputStream output;
    private AudioRecord recorder;
    private AudioTrack audioTrack;
    private AudioPlayback playback;
    private Thread connectThread;
    private Thread readerThread;
    private Thread captureThread;
    private CountDownLatch setupLatch;

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
        setupComplete = false;
        setupLatch = new CountDownLatch(1);
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
        CountDownLatch latch = setupLatch;
        setupLatch = null;
        if (latch != null) latch.countDown();
    }

    private void connect() {
        try {
            listener.onState("connecting", "Opening native audio link");
            openWebSocket();
            readerThread = new Thread(this::readLoop, "voice-reader");
            readerThread.start();
            sendSetup();
            listener.onState("connecting", "Waiting for Gemini session");
            CountDownLatch latch = setupLatch;
            if (latch == null || !latch.await(8, TimeUnit.SECONDS) || !setupComplete) {
                throw new IOException("Gemini session setup did not complete");
            }
            if (!running) return;
            openAudio();
            captureThread = new Thread(this::captureLoop, "voice-mic");
            captureThread.start();
            listener.onState("listening", "Go ahead");
        } catch (Exception e) {
            if (running) listener.onError("Voice start failed: " + readableError(e));
            running = false;
            closeAudio();
            closeSocket();
            interrupt(readerThread);
        }
    }

    private static String readableError(Throwable t) {
        String s = t.getMessage();
        return s == null || s.trim().isEmpty() ? t.getClass().getSimpleName() : s;
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
        String path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + URLEncoder.encode(apiKey, "UTF-8");
        String headers = "GET " + path + " HTTP/1.1\r\n"
                + "Host: generativelanguage.googleapis.com\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readHttpStatus(in);
        if (!(status.startsWith("HTTP/1.1 101") || status.contains(" 101 "))) throw new IOException("WebSocket handshake rejected: " + status);
        input = in;
        output = out;
        socket = s;
    }

    private static String readHttpStatus(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\r') continue;
            if (b == '\n') break;
            line.append((char) b);
            if (line.length() > 4096) throw new IOException("Invalid handshake response");
        }
        if (b < 0) throw new EOFException("Handshake ended early");
        return line.toString();
    }

    private void sendSetup() throws Exception {
        String prompt = systemPrompt == null ? "" : systemPrompt.trim();
        if (prompt.isEmpty()) prompt = defaultPrompt();

        JSONObject generation = new JSONObject();
        generation.put("responseModalities", new JSONArray().put("AUDIO"));
        JSONObject voiceConfig = new JSONObject();
        voiceConfig.put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice));
        generation.put("speechConfig", new JSONObject().put("voiceConfig", voiceConfig));

        JSONObject setup = new JSONObject();
        setup.put("model", model.startsWith("models/") ? model : "models/" + model);
        setup.put("generationConfig", generation);
        setup.put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", prompt + telemetry()))));
        setup.put("tools", toolSchema());
        sendText(new JSONObject().put("setup", setup).toString());
    }

    private String telemetry() {
        String date = new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(new java.util.Date());
        String time = java.text.DateFormat.getTimeInstance().format(new java.util.Date());
        return "\n\nDEVICE TELEMETRY:\n- Local time: " + time + "\n- Date: " + date
                + "\n- Timezone: " + TimeZone.getDefault().getID() + "\nAnswer time/date questions from this telemetry.";
    }

    private static String defaultPrompt() {
        return "You are Voice, a fast autonomous Android agent. Read the Android screen before interacting with another app. "
                + "Prefer element IDs when available, keep confirmations concise, and execute actions in sequence.";
    }

    private JSONArray toolSchema() throws Exception {
        JSONArray funcs = new JSONArray();
        funcs.put(fn("get_device_info", "Get battery and memory telemetry.", new JSONObject(), req()));
        funcs.put(fn("read_screen_text", "Read visible Android text and interactive elements.", new JSONObject(), req()));
        funcs.put(fn("tap_element_id", "Tap a discovered element id.", obj("element_id", "INTEGER"), req("element_id")));
        funcs.put(fn("long_press_element_id", "Long press a discovered element id.", obj("element_id", "INTEGER"), req("element_id")));
        funcs.put(fn("replace_text", "Replace the active editable field.", obj("text", "STRING"), req("text")));
        funcs.put(fn("clear_text", "Clear the active editable field.", new JSONObject(), req()));
        funcs.put(fn("type_text", "Type text into the active editable field.", obj("text", "STRING"), req("text")));
        funcs.put(fn("tap_element", "Tap a visible element by label.", obj("label", "STRING"), req("label")));
        JSONObject xy = obj("x", "INTEGER"); xy.put("y", prop("INTEGER"));
        funcs.put(fn("tap_coordinates", "Tap normalized 0..1000 coordinates.", xy, req("x", "y")));
        funcs.put(fn("long_press", "Long press normalized 0..1000 coordinates.", xy, req("x", "y")));
        funcs.put(fn("scroll", "Scroll the active screen.", obj("direction", "STRING"), req("direction")));
        funcs.put(fn("wait_seconds", "Wait one to four seconds.", obj("seconds", "INTEGER"), req("seconds")));
        funcs.put(fn("open_application", "Launch an installed Android application.", obj("app_name", "STRING"), req("app_name")));
        funcs.put(fn("search_internet", "Open a web search.", obj("query", "STRING"), req("query")));
        funcs.put(fn("search_web", "Open a browser search.", obj("query", "STRING"), req("query")));
        funcs.put(fn("search_youtube", "Open YouTube search.", obj("query", "STRING"), req("query")));
        funcs.put(fn("toggle_flashlight", "Toggle flashlight.", obj("state", "BOOLEAN"), req("state")));
        funcs.put(fn("set_volume", "Set media volume percentage.", obj("level_percent", "INTEGER"), req("level_percent")));
        funcs.put(fn("navigate_system", "Use system navigation.", obj("action", "STRING"), req("action")));
        funcs.put(fn("make_phone_call", "Call a phone number.", obj("phone_number", "STRING"), req("phone_number")));
        JSONObject sms = obj("phone_number", "STRING"); sms.put("message", prop("STRING"));
        funcs.put(fn("send_sms", "Send an SMS.", sms, req("phone_number", "message")));
        return new JSONArray().put(new JSONObject().put("functionDeclarations", funcs));
    }

    private static JSONObject fn(String name, String desc, JSONObject props, JSONArray required) throws Exception {
        JSONObject params = new JSONObject().put("type", "OBJECT").put("properties", props).put("required", required);
        return new JSONObject().put("name", name).put("description", desc).put("parameters", params);
    }

    private static JSONObject obj(String key, String type) throws Exception { return new JSONObject().put(key, prop(type)); }
    private static JSONObject prop(String type) throws Exception { return new JSONObject().put("type", type); }
    private static JSONArray req(String... names) { JSONArray a = new JSONArray(); for (String n : names) a.put(n); return a; }

    private void openAudio() throws Exception {
        int recMin = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int recSize = Math.max(4096, recMin > 0 ? recMin * 2 : 8192);
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, recSize);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
        int playMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int playSize = Math.max(8192, playMin > 0 ? playMin * 2 : 8192);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, playSize, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Audio output initialization failed");
        audioTrack.play();
        playback = new AudioPlayback(audioTrack);
    }

    private void captureLoop() {
        try { recorder.startRecording(); }
        catch (Exception e) { if (running) listener.onError("Microphone start failed: " + readableError(e)); return; }
        byte[] pcm = new byte[6400];
        while (running) {
            int count;
            try { count = recorder.read(pcm, 0, pcm.length); }
            catch (Exception e) { if (running) listener.onError("Microphone read failed: " + readableError(e)); break; }
            if (count <= 0 || muted || (echoGuard && modelSpeaking) || !running) continue;
            try {
                JSONObject audio = new JSONObject();
                audio.put("data", Base64.encodeToString(pcm, 0, count, Base64.NO_WRAP));
                audio.put("mimeType", "audio/pcm;rate=16000");
                sendText(new JSONObject().put("realtimeInput", new JSONObject().put("audio", audio)).toString());
            } catch (Exception e) {
                if (running) listener.onError("Audio send failed: " + readableError(e));
                break;
            }
        }
    }

    private void readLoop() {
        boolean notifyClosed = false;
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
            if (running) listener.onError("Live connection closed: " + readableError(e));
        } finally {
            notifyClosed = running;
            running = false;
            closeAudio();
            closeSocket();
            CountDownLatch latch = setupLatch;
            if (latch != null) latch.countDown();
            if (notifyClosed) listener.onClosed();
        }
    }

    private void handleMessage(JSONObject msg) {
        try {
            if (msg.has("setupComplete")) {
                setupComplete = true;
                CountDownLatch latch = setupLatch;
                if (latch != null) latch.countDown();
            }
            JSONObject toolCall = msg.optJSONObject("toolCall");
            if (toolCall != null) executeCalls(toolCall.optJSONArray("functionCalls"));
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
                        JSONObject inline = part.optJSONObject("inlineData");
                        if (inline != null) {
                            String data = inline.optString("data", "");
                            if (!data.isEmpty() && playback != null) {
                                modelSpeaking = true;
                                listener.onState("speaking", "Voice response");
                                playback.offer(Base64.decode(data, Base64.DEFAULT));
                            }
                        }
                        JSONObject call = part.optJSONObject("functionCall");
                        if (call != null) executeCall(call);
                    }
                }
            }
            if (content.optBoolean("turnComplete", false)) {
                modelSpeaking = false;
                listener.onState("listening", "Go ahead");
            }
        } catch (Exception e) { listener.onError("Response parse error: " + readableError(e)); }
    }

    private void executeCalls(JSONArray calls) {
        if (calls == null) return;
        for (int i = 0; i < calls.length(); i++) executeCall(calls.optJSONObject(i));
    }

    private void executeCall(JSONObject call) {
        if (call == null || !running) return;
        String id = call.optString("id", "call_1");
        String name = call.optString("name", "");
        JSONObject args = call.optJSONObject("args");
        new Thread(() -> {
            String result;
            try { listener.onState("working", name); result = MainActivity.executeNativeTool(name, args, this); }
            catch (Exception e) { result = "Tool error: " + readableError(e); }
            try { sendToolResponse(id, name, result); }
            catch (Exception e) { if (running) listener.onError("Tool response failed: " + readableError(e)); }
            if (running) listener.onState("listening", "Go ahead");
        }, "voice-tool").start();
    }

    private void sendToolResponse(String id, String name, String result) throws Exception {
        JSONObject response = new JSONObject().put("result", result == null ? "" : result);
        JSONObject function = new JSONObject().put("name", name == null ? "" : name)
                .put("id", id == null || id.isEmpty() ? "call_1" : id).put("response", response);
        sendText(new JSONObject().put("toolResponse", new JSONObject()
                .put("functionResponses", new JSONArray().put(function))).toString());
    }

    private synchronized void sendText(String text) throws IOException { sendFrame(1, text.getBytes(StandardCharsets.UTF_8)); }

    private synchronized void sendFrame(int opcode, byte[] data) throws IOException {
        if (output == null) throw new IOException("Socket is not connected");
        int len = data == null ? 0 : data.length;
        output.write(0x80 | (opcode & 0x0F));
        if (len <= 125) output.write(0x80 | len);
        else if (len <= 65535) { output.write(0x80 | 126); output.write((len >>> 8) & 0xFF); output.write(len & 0xFF); }
        else { output.write(0x80 | 127); long value = len & 0xFFFFFFFFL; for (int shift = 56; shift >= 0; shift -= 8) output.write((int) (value >>> shift) & 0xFF); }
        byte[] mask = new byte[4]; new SecureRandom().nextBytes(mask); output.write(mask);
        for (int i = 0; i < len; i++) output.write(data[i] ^ mask[i & 3]);
        output.flush();
    }

    private Frame readMessage() throws IOException {
        Frame first = readFrame();
        if (first == null || first.fin) return first;
        if (first.opcode == 8 || first.opcode == 9 || first.opcode == 10) return first;
        ByteArrayOutputStream all = new ByteArrayOutputStream(first.payload.length + 1024); all.write(first.payload);
        while (true) {
            Frame next = readFrame();
            if (next == null) return null;
            if (next.opcode == 9) { sendFrame(10, next.payload); continue; }
            if (next.opcode == 0) { all.write(next.payload); if (next.fin) return new Frame(true, first.opcode, all.toByteArray()); }
            else if (next.opcode == 8) return next;
        }
    }

    private Frame readFrame() throws IOException {
        if (input == null) return null;
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

    private static int readUnsignedShort(InputStream in) throws IOException { int a = in.read(), b = in.read(); if ((a | b) < 0) throw new EOFException(); return (a << 8) | b; }
    private static long readLong(InputStream in) throws IOException { long v = 0; for (int i = 0; i < 8; i++) { int b = in.read(); if (b < 0) throw new EOFException(); v = (v << 8) | (b & 0xFFL); } return v; }
    private static byte[] readBytes(InputStream in, int length) throws IOException { byte[] data = new byte[length]; int off = 0; while (off < length) { int n = in.read(data, off, length - off); if (n < 0) throw new EOFException(); off += n; } return data; }

    private void closeAudio() {
        if (playback != null) playback.stop(); playback = null;
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} try { recorder.release(); } catch (Exception ignored) {} recorder = null; }
        if (audioTrack != null) { try { audioTrack.pause(); } catch (Exception ignored) {} try { audioTrack.flush(); } catch (Exception ignored) {} try { audioTrack.release(); } catch (Exception ignored) {} audioTrack = null; }
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
        AudioPlayback(AudioTrack track) { this.track = track; worker = new Thread(this::run, "voice-speaker"); worker.start(); }
        void offer(byte[] audio) { if (audio == null || audio.length == 0 || !running) return; if (!queue.offer(audio)) { queue.poll(); queue.offer(audio); } }
        void flush() { queue.clear(); }
        void stop() { running = false; queue.clear(); worker.interrupt(); }
        private void run() { while (running) { try { byte[] d = queue.take(); if (running) track.write(d, 0, d.length); } catch (InterruptedException e) { if (!running) break; } catch (Exception ignored) {} } }
    }
}
