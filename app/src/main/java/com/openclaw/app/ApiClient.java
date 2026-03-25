package com.openclaw.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.json.JSONArray;
import org.json.JSONObject;

public class ApiClient {

    private static final String TAG = "OC_WS";

    private static final String   SESSION_KEY  = "main";
    private static final String   CLIENT_ID    = "openclaw-android";
    private static final String   CLIENT_MODE  = "ui";
    private static final String   ROLE         = "operator";
    private static final String[] SCOPES       = {
        "operator.admin", "operator.approvals", "operator.pairing"
    };

    // ── WebSocket 保活参数 ────────────────────────────────────────────────────
    /** OkHttp 层 ping 间隔（秒）：底层 TCP keepalive，防止 NAT/防火墙超时断连 */
    private static final long WS_PING_INTERVAL_SEC = 25;
    /** 最大重连次数 */
    private static final int  MAX_RETRY = 3;
    /** 重连基础延迟（ms），指数退避：500 → 1000 → 2000 */
    private static final long RETRY_BASE_MS = 500;

    private static final String PREF_NAME  = "openclaw_device";
    private static final String PREF_PRIV  = "device_private_key";
    private static final String PREF_PUB   = "device_public_key";
    private static final String PREF_DEVID = "device_id";

    // ── 固定设备密钥（开发用）────────────────────────────────────────────────
    // 填入从 adb 读出的 device_private_key 值，即可让重装后保持相同设备 ID。
    // 如果不需要固定，把 HARDCODED_SEED 留为空字符串 ""。
    private static final String HARDCODED_SEED = ""; // 硬编码风险较高，留空以改为运行时生成

    private final OkHttpClient  httpClient;
    private final AtomicInteger reqId = new AtomicInteger(1);
    private volatile WebSocket  activeWs;
    private final Context       context;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    private byte[] seed;
    private byte[] pubKeyBytes;
    private String deviceId;

    // ── 附件 ─────────────────────────────────────────────────────────────────
    public static class Attachment {
        public final String name;
        public final String mimeType;
        public final byte[] data;
        public Attachment(String name, String mimeType, byte[] data) {
            this.name = name; this.mimeType = mimeType; this.data = data;
        }
        public boolean isText() {
            if (mimeType == null) return false;
            return mimeType.startsWith("text/")
                || mimeType.equals("application/json")
                || mimeType.equals("application/xml")
                || name.endsWith(".md") || name.endsWith(".txt")
                || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".csv") || name.endsWith(".log");
        }
        public boolean isImage() {
            return mimeType != null && mimeType.startsWith("image/");
        }
    }

    // ── 模型列表回调 ──────────────────────────────────────────────────────────
    public interface ModelsCallback {
        void onSuccess(List<String> models);
        void onError(String error);
    }

    // ── 构造 ──────────────────────────────────────────────────────────────────
    public ApiClient(Context context) {
        this.context = context;
        loadOrCreateDeviceKey();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0,  TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // ★ 关键：OkHttp 层 WebSocket ping，防止路由器/NAT 超时断连
            .pingInterval(WS_PING_INTERVAL_SEC, TimeUnit.SECONDS);

        try {
            X509TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            builder.sslSocketFactory(sc.getSocketFactory(), trustAll)
                   .hostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}
        httpClient = builder.build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  设备密钥
    // ══════════════════════════════════════════════════════════════════════════

    private void loadOrCreateDeviceKey() {
        SharedPreferences p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // 如果填了硬编码密钥，直接用它（重装 App 后也保持同一设备 ID）
        if (!HARDCODED_SEED.isEmpty()) {
            try {
                seed = base64UrlDecode(HARDCODED_SEED);
                Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
                pubKeyBytes = priv.generatePublicKey().getEncoded();
                deviceId = sha256Hex(pubKeyBytes);
                // 同步写入 SharedPreferences，方便调试时用 Settings 界面复制
                p.edit()
                    .putString(PREF_PRIV,  HARDCODED_SEED)
                    .putString(PREF_PUB,   base64UrlEncode(pubKeyBytes))
                    .putString(PREF_DEVID, deviceId)
                    .apply();
                return;
            } catch (Exception e) {
                Log.e(TAG, "HARDCODED_SEED 无效，回退到动态生成", e);
            }
        }

        String privB64 = p.getString(PREF_PRIV,  null);
        String pubB64  = p.getString(PREF_PUB,   null);
        String devId   = p.getString(PREF_DEVID, null);
        if (privB64 != null && pubB64 != null && devId != null) {
            seed = base64UrlDecode(privB64);
            pubKeyBytes = base64UrlDecode(pubB64);
            deviceId = devId;
        } else {
            generateAndSaveDeviceKey(p);
        }
    }

    private void generateAndSaveDeviceKey(SharedPreferences prefs) {
        try {
            seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
            pubKeyBytes = priv.generatePublicKey().getEncoded();
            deviceId = sha256Hex(pubKeyBytes);
            prefs.edit()
                .putString(PREF_PRIV,  base64UrlEncode(seed))
                .putString(PREF_PUB,   base64UrlEncode(pubKeyBytes))
                .putString(PREF_DEVID, deviceId)
                .apply();
        } catch (Exception e) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
            seed = new byte[32]; pubKeyBytes = new byte[32];
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  签名
    // ══════════════════════════════════════════════════════════════════════════

    private String computeSignature(byte[] keySeed, String message) throws Exception {
        Ed25519PrivateKeyParameters privKey = new Ed25519PrivateKeyParameters(keySeed, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privKey);
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        signer.update(msgBytes, 0, msgBytes.length);
        return base64UrlEncode(signer.generateSignature());
    }

    private String buildSignMessage(String token, String nonce, long signedAtMs) {
        String t = token != null ? token : "";
        return String.join("|", "v2", deviceId, CLIENT_ID, CLIENT_MODE, ROLE,
            String.join(",", SCOPES), String.valueOf(signedAtMs), t, nonce);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════════════════

    private String sha256Hex(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String base64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private byte[] base64UrlDecode(String s) {
        return Base64.decode(s, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private String buildWsUrl(String baseUrl, String token) {
        String url = baseUrl.trim();
        if (!url.endsWith("/")) url += "/";
        String ws;
        if      (url.startsWith("https://")) ws = "wss://" + url.substring(8);
        else if (url.startsWith("http://"))  ws = "ws://"  + url.substring(7);
        else                                 ws = "wss://" + url;
        if (token != null && !token.trim().isEmpty()) ws += "?token=" + token.trim();
        return ws;
    }

    private String buildReq(String method, JSONObject params) {
        try {
            JSONObject r = new JSONObject();
            r.put("type",   "req");
            r.put("id",     String.valueOf(reqId.getAndIncrement()));
            r.put("method", method);
            r.put("params", params != null ? params : new JSONObject());
            return r.toString();
        } catch (Exception e) { return "{}"; }
    }

    private JSONObject buildConnectParams(String token, String nonce,
                                          long signedAt, String sig) throws Exception {
        JSONArray scopesArr = new JSONArray();
        for (String s : SCOPES) scopesArr.put(s);
        JSONObject device = new JSONObject();
        device.put("id", deviceId); device.put("publicKey", base64UrlEncode(pubKeyBytes));
        device.put("signature", sig); device.put("signedAt", signedAt); device.put("nonce", nonce);
        JSONObject client = new JSONObject();
        client.put("id", CLIENT_ID); client.put("version", "5.0");
        client.put("platform", "android"); client.put("mode", CLIENT_MODE);
        JSONObject params = new JSONObject();
        params.put("minProtocol", 3); params.put("maxProtocol", 3);
        params.put("client", client); params.put("role", ROLE);
        params.put("scopes", scopesArr); params.put("device", device);
        params.put("caps", new JSONArray().put("tool-events"));
        params.put("userAgent", "OpenClaw-Android/5.0"); params.put("locale", "zh-CN");
        if (token != null && !token.trim().isEmpty()) {
            JSONObject auth = new JSONObject(); auth.put("token", token.trim());
            params.put("auth", auth);
        }
        return params;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  获取模型列表（通过 WebSocket，认证后查询）
    // ══════════════════════════════════════════════════════════════════════════

    public void fetchModels(String baseUrl, String token, ModelsCallback cb) {
        final String wsUrl      = buildWsUrl(baseUrl, token);
        final String finalToken = token != null ? token.trim() : "";
        final boolean[] done    = {false};
        final String[]  modelsReqId = {null};
        final CountDownLatch latch = new CountDownLatch(1);

        httpClient.newWebSocket(new Request.Builder().url(wsUrl).build(),
            new WebSocketListener() {
                private boolean authed = false;

                @Override public void onOpen(WebSocket ws, Response r) {}

                @Override
                public void onMessage(WebSocket ws, String text) {
                    if (done[0]) return;
                    try {
                        JSONObject msg  = new JSONObject(text);
                        String event = msg.optString("event", "");
                        String type  = msg.optString("type",  "");
                        String id    = msg.optString("id",    "");

                        if ("connect.challenge".equals(event)) {
                            String nonce  = msg.getJSONObject("payload").getString("nonce");
                            long signedAt = System.currentTimeMillis();
                            String sig    = computeSignature(seed,
                                buildSignMessage(finalToken, nonce, signedAt));
                            ws.send(buildReq("connect",
                                buildConnectParams(finalToken, nonce, signedAt, sig)));
                            return;
                        }

                        if ("res".equals(type) && !authed) {
                            if (msg.has("error")) {
                                done[0] = true;
                                cb.onError("认证失败");
                                ws.close(1000, null); latch.countDown(); return;
                            }
                            authed = true;
                            String reqStr = buildReq("models.list", new JSONObject());
                            modelsReqId[0] = new JSONObject(reqStr).getString("id");
                            ws.send(reqStr);
                            return;
                        }

                        if ("res".equals(type) && authed
                                && modelsReqId[0] != null && modelsReqId[0].equals(id)) {
                            done[0] = true;
                            ws.close(1000, null);
                            if (msg.has("error")) {
                                cb.onError("服务器暂不支持模型列表，请手动填写");
                                latch.countDown(); return;
                            }
                            List<String> models = new ArrayList<>();
                            JSONObject payload = msg.optJSONObject("payload");
                            if (payload != null) {
                                JSONArray arr = payload.optJSONArray("models");
                                if (arr == null) arr = payload.optJSONArray("data");
                                if (arr != null) {
                                    for (int i = 0; i < arr.length(); i++) {
                                        try {
                                            Object item = arr.get(i);
                                            if (item instanceof String) models.add((String) item);
                                            else if (item instanceof JSONObject) {
                                                JSONObject m = (JSONObject) item;
                                                String mid = m.optString("id", m.optString("name", ""));
                                                if (!mid.isEmpty()) models.add(mid);
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                            if (models.isEmpty()) cb.onError("未找到模型，请手动填写");
                            else cb.onSuccess(models);
                            latch.countDown();
                        }
                    } catch (Exception e) {
                        if (!done[0]) { done[0] = true; cb.onError("错误：" + e.getMessage());
                            ws.close(1000, null); latch.countDown(); }
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response r) {
                    if (done[0]) return; done[0] = true;
                    cb.onError("连接失败：" + (t.getMessage() != null ? t.getMessage() : "网络错误"));
                    latch.countDown();
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(1000, null);
                    if (!done[0]) { done[0] = true; cb.onError("连接关闭"); latch.countDown(); }
                }
            });

        new Thread(() -> {
            try { if (!latch.await(15, TimeUnit.SECONDS) && !done[0]) {
                done[0] = true; cb.onError("超时，请手动填写"); }
            } catch (InterruptedException ignored) {}
        }).start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  测试连接
    // ══════════════════════════════════════════════════════════════════════════

    public interface TestCallback {
        void onResult(boolean success, String message);
    }

    public void testConnection(String baseUrl, String token, TestCallback cb) {
        final String wsUrl      = buildWsUrl(baseUrl, token);
        final String finalToken = token != null ? token.trim() : "";
        final boolean[] done    = {false};
        final CountDownLatch latch = new CountDownLatch(1);

        httpClient.newWebSocket(new Request.Builder().url(wsUrl).build(),
            new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response r) {}

                @Override
                public void onMessage(WebSocket ws, String text) {
                    if (done[0]) return;
                    try {
                        JSONObject msg = new JSONObject(text);
                        String ev   = msg.optString("event", "");
                        String type = msg.optString("type",  "");
                        if ("connect.challenge".equals(ev)) {
                            String nonce  = msg.getJSONObject("payload").getString("nonce");
                            long signedAt = System.currentTimeMillis();
                            String sig    = computeSignature(seed,
                                buildSignMessage(finalToken, nonce, signedAt));
                            ws.send(buildReq("connect",
                                buildConnectParams(finalToken, nonce, signedAt, sig)));
                        } else if ("res".equals(type)) {
                            done[0] = true;
                            if (msg.has("error")) {
                                JSONObject e = msg.getJSONObject("error");
                                String code  = e.optString("code", "");
                                String err   = e.optString("message", "认证失败");
                                cb.onResult(false, "NOT_PAIRED".equals(code)
                                    || err.contains("pairing required")
                                    ? "⚠️ 设备未配对：请在控制台点击【批准】" : "❌ 认证失败：" + err);
                            } else {
                                cb.onResult(true, "✅ 连接并认证成功！");
                            }
                            ws.close(1000, "test done"); latch.countDown();
                        }
                    } catch (Exception e) {
                        if (!done[0]) { done[0] = true;
                            cb.onResult(false, "❌ " + e.getMessage());
                            ws.close(1000, null); latch.countDown(); }
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response r) {
                    if (done[0]) return; done[0] = true;
                    String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    cb.onResult(false,
                        r != null && r.code() == 401 ? "❌ Token 鉴权失败 (401)"
                        : m.contains("resolve host") ? "❌ 无法解析域名"
                        : m.contains("ECONNREFUSED") ? "❌ 连接被拒绝"
                        : "❌ 连接失败：" + m);
                    latch.countDown();
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(1000, null);
                    if (!done[0] && code != 1000) { done[0] = true;
                        cb.onResult(false, code == 1008
                            && reason.toLowerCase().contains("pairing required")
                            ? "⚠️ 设备未配对" : "❌ 连接关闭 " + code);
                        latch.countDown(); }
                }
            });

        new Thread(() -> {
            try { if (!latch.await(15, TimeUnit.SECONDS) && !done[0]) {
                done[0] = true; cb.onResult(false, "❌ 连接超时（15s）"); }
            } catch (InterruptedException ignored) {}
        }).start();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  聊天（流式 + 附件 + 自动重连）
    // ══════════════════════════════════════════════════════════════════════════

    public void chat(String baseUrl, String token, String model,
                     List<Message> messages, Attachment attachment, StreamCallback cb) {
        chatWithRetry(baseUrl, token, model, messages, attachment, cb, 0);
    }

    private void chatWithRetry(String baseUrl, String token, String model,
                                List<Message> messages, Attachment attachment,
                                StreamCallback cb, int retryCount) {
        cancel();

        // 取最后一条用户消息
        String userMsgText = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) { userMsgText = messages.get(i).getContent(); break; }
        }

        // 处理附件
        final String finalMsg;
        final JSONArray attachmentsJson = new JSONArray();
        if (attachment != null) {
            if (attachment.isText()) {
                String textContent;
                try { textContent = new String(attachment.data, StandardCharsets.UTF_8); }
                catch (Exception e) { textContent = "（无法读取文件内容）"; }
                finalMsg = userMsgText + "\n\n---\n📎 文件：" + attachment.name + "\n\n" + textContent;
            } else if (attachment.isImage()) {
                finalMsg = userMsgText.isEmpty() ? "请分析这张图片" : userMsgText;
                try {
                    JSONObject imgBlock = new JSONObject();
                    imgBlock.put("type", "image");
                    JSONObject source = new JSONObject();
                    source.put("type", "base64");
                    source.put("media_type", attachment.mimeType);
                    source.put("data", Base64.encodeToString(attachment.data, Base64.NO_WRAP));
                    imgBlock.put("source", source);
                    attachmentsJson.put(imgBlock);
                } catch (Exception ignored) {}
            } else {
                finalMsg = userMsgText + "\n\n（附件：" + attachment.name
                    + "，" + attachment.data.length + " 字节）";
            }
        } else {
            finalMsg = userMsgText;
        }

        final String iKey       = UUID.randomUUID().toString();
        final String wsUrl      = buildWsUrl(baseUrl, token);
        final String finalToken = token != null ? token.trim() : "";
        final int    retry      = retryCount;

        // 保存重连所需参数
        final String  savedBaseUrl   = baseUrl;
        final String  savedToken     = token;
        final String  savedModel     = model;
        final List<Message> savedMsgs = messages;
        final Attachment savedAtt    = attachment;

        activeWs = httpClient.newWebSocket(
            new Request.Builder().url(wsUrl).build(),
            new WebSocketListener() {
                private final StringBuilder buf = new StringBuilder();
                private boolean authed    = false;
                private boolean completed = false;

                @Override public void onOpen(WebSocket ws, Response r) {
                    Log.d(TAG, "WS open (retry=" + retry + ")");
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    if (completed) return;
                    Log.d(TAG, text);
                    try {
                        JSONObject msg = new JSONObject(text);
                        String type  = msg.optString("type",  "");
                        String event = msg.optString("event", "");

                        if ("connect.challenge".equals(event)) {
                            String nonce  = msg.getJSONObject("payload").getString("nonce");
                            long signedAt = System.currentTimeMillis();
                            String sig    = computeSignature(seed,
                                buildSignMessage(finalToken, nonce, signedAt));
                            ws.send(buildReq("connect",
                                buildConnectParams(finalToken, nonce, signedAt, sig)));
                            return;
                        }

                        if ("res".equals(type) && !authed) {
                            if (msg.has("error")) {
                                completed = true;
                                String err = msg.getJSONObject("error").optString("message", "认证失败");
                                cb.onError(err.contains("pairing required")
                                    ? "设备未配对，请在控制台点击【批准】" : "认证失败：" + err);
                                ws.close(1000, null); return;
                            }
                            authed = true;
                            JSONObject p = new JSONObject();
                            p.put("sessionKey", SESSION_KEY);
                            p.put("message",    finalMsg);
                            p.put("deliver",    false);
                            p.put("idempotencyKey", iKey);
                            p.put("attachments", attachmentsJson);
                            ws.send(buildReq("chat.send", p));
                            return;
                        }

                        if (!authed) return;

                        if ("agent".equals(event)) { handleAgentEvent(ws, msg); return; }
                        if ("chat".equals(event))  { handleChatEvent(ws, msg);  return; }

                        if ("res".equals(type) && msg.has("error") && !completed) {
                            completed = true;
                            String err = "发送失败";
                            try { err = msg.getJSONObject("error").optString("message", err); }
                            catch (Exception ignored) {}
                            cb.onError(err); ws.close(1000, null);
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "parse error", e);
                        if (!completed) {
                            completed = true;
                            cb.onError("解析错误：" + e.getMessage());
                            ws.close(1000, null);
                        }
                    }
                }

                private void handleAgentEvent(WebSocket ws, JSONObject msg) throws Exception {
                    JSONObject payload = msg.optJSONObject("payload");
                    if (payload == null) return;
                    String stream = payload.optString("stream", "");
                    if ("assistant".equals(stream)) {
                        JSONObject data = payload.optJSONObject("data");
                        if (data != null) {
                            String delta = data.optString("delta", "");
                            if (!delta.isEmpty()) { buf.append(delta); cb.onToken(delta); }
                        }
                    } else if ("lifecycle".equals(stream)) {
                        JSONObject data = payload.optJSONObject("data");
                        String phase = data != null ? data.optString("phase", "") : "";
                        if ("end".equals(phase) || "stop".equals(phase)
                                || "done".equals(phase) || "finish".equals(phase)) {
                            if (!completed) { completed = true; cb.onComplete(); ws.close(1000, "done"); }
                        }
                    }
                }

                private void handleChatEvent(WebSocket ws, JSONObject msg) throws Exception {
                    JSONObject payload = msg.optJSONObject("payload");
                    if (payload == null) return;
                    String state = payload.optString("state", "");
                    if ("done".equals(state) || "complete".equals(state) || "end".equals(state)) {
                        if (!completed) { completed = true; cb.onComplete(); ws.close(1000, "done"); }
                        return;
                    }
                    if ("delta".equals(state) || "streaming".equals(state)) {
                        JSONObject message = payload.optJSONObject("message");
                        if (message == null || !"assistant".equals(message.optString("role"))) return;
                        String fullText = extractTextFromContent(message);
                        if (!fullText.isEmpty() && fullText.length() > buf.length()) {
                            String delta = fullText.substring(buf.length());
                            if (!delta.isEmpty()) {
                                buf.setLength(0); buf.append(fullText); cb.onToken(delta);
                            }
                        }
                    }
                }

                @Override
                public void onMessage(WebSocket ws, ByteString bytes) { onMessage(ws, bytes.utf8()); }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(1000, null);
                    if (!completed) {
                        completed = true;
                        if (buf.length() > 0) {
                            // 已有内容：当作完成处理
                            cb.onComplete();
                        } else {
                            // 没有任何内容且未预期关闭：尝试重连
                            scheduleRetry(savedBaseUrl, savedToken, savedModel,
                                savedMsgs, savedAtt, cb, retry, code, reason);
                        }
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response r) {
                    if (completed) return;
                    completed = true;
                    String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                    Log.w(TAG, "WS failure (retry=" + retry + "): " + m);

                    if (buf.length() > 0) {
                        // 已有部分内容就当完成
                        cb.onComplete();
                    } else {
                        // 没有内容：重连
                        scheduleRetry(savedBaseUrl, savedToken, savedModel,
                            savedMsgs, savedAtt, cb, retry, -1, m);
                    }
                }
            });
    }

    // ── 重连调度 ─────────────────────────────────────────────────────────────

    private void scheduleRetry(String baseUrl, String token, String model,
                                List<Message> messages, Attachment attachment,
                                StreamCallback cb, int prevRetry,
                                int closeCode, String reason) {
        int nextRetry = prevRetry + 1;
        if (nextRetry > MAX_RETRY) {
            Log.w(TAG, "Max retry reached");
            cb.onError("连接断开（" + (reason != null ? reason : "code " + closeCode) + "）");
            return;
        }

        long delayMs = RETRY_BASE_MS * (1L << prevRetry); // 指数退避: 500/1000/2000ms
        Log.d(TAG, "Retry " + nextRetry + "/" + MAX_RETRY + " in " + delayMs + "ms");

        // 通知 UI 正在重连
        cb.onToken("\n\n🔄 连接中断，正在重连（" + nextRetry + "/" + MAX_RETRY + "）…");

        mainHandler.postDelayed(() ->
            chatWithRetry(baseUrl, token, model, messages, attachment, cb, nextRetry),
            delayMs
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  内容提取
    // ══════════════════════════════════════════════════════════════════════════

    private String extractTextFromContent(JSONObject message) {
        if (message == null) return "";
        JSONArray arr = message.optJSONArray("content");
        if (arr != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject b = arr.getJSONObject(i);
                    if ("text".equals(b.optString("type"))) sb.append(b.optString("text", ""));
                } catch (Exception ignored) {}
            }
            if (sb.length() > 0) return sb.toString();
        }
        return message.optString("content", "");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  取消
    // ══════════════════════════════════════════════════════════════════════════

    public void cancel() {
        if (activeWs != null) {
            try {
                JSONObject p = new JSONObject();
                p.put("sessionKey", SESSION_KEY);
                activeWs.send(buildReq("chat.abort", p));
            } catch (Exception ignored) {}
            activeWs.close(1000, "cancelled");
            activeWs = null;
        }
    }

    public String getDeviceId() { return deviceId; }
}
