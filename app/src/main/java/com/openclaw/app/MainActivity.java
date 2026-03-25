package com.openclaw.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.noties.markwon.Markwon;

public class MainActivity extends AppCompatActivity {

    private View         headerBar;
    private View         statusDot;
    private TextView     statusText;
    private RecyclerView recyclerView;
    private View         emptyState;
    private EditText     etMessage;
    private ImageButton  btnSend;
    private ImageButton  btnAttach;
    private LinearLayout inputContainer;
    private LinearLayout attachmentBar;
    private TextView     tvAttachName;

    private final List<Message> messages = new ArrayList<>();
    private ChatAdapter adapter;
    private Markwon     markwon;
    private ApiClient   apiClient;
    private boolean     isStreaming = false;
    private boolean     isAtBottom  = true;

    private String pendingAttachName     = null;
    private String pendingAttachMimeType = null;
    private byte[] pendingAttachData     = null;

    private String currentConvId = UUID.randomUUID().toString();

    private String serverUrl = "";
    private String apiToken  = "";
    private String model     = "openclaw";

    private ActivityResultLauncher<String> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        markwon   = Markwon.create(this);
        apiClient = new ApiClient(this);

        loadConfig();
        bindViews();
        setupEdgeToEdge();
        setupRecyclerView();
        setupInput();
        setupFilePicker();
        handleHistoryIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleHistoryIntent(intent);
    }

    private void handleHistoryIntent(Intent intent) {
        if (intent == null) return;
        String convId = intent.getStringExtra(HistoryActivity.EXTRA_CONV_ID);
        if (convId == null) return;
        List<Message> loaded = ConversationStore.loadMessages(this, convId);
        if (!loaded.isEmpty()) {
            apiClient.cancel();
            int count = messages.size();
            messages.clear();
            if (count > 0) adapter.notifyItemRangeRemoved(0, count);
            messages.addAll(loaded);
            adapter.notifyItemRangeInserted(0, messages.size());
            currentConvId = convId;
            updateEmptyState();
            setStreaming(false);
            setStatus(StatusState.READY);
            scrollToBottom(true);
        }
    }

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences("openclaw_prefs", MODE_PRIVATE);
        serverUrl = p.getString("server_url", "");
        apiToken  = p.getString("api_token",  "");
        model     = p.getString("model",      "openclaw");
        if (serverUrl.isEmpty()) {
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra("first_launch", true);
            startActivity(i);
            finish();
        }
    }

    private void bindViews() {
        headerBar      = findViewById(R.id.headerBar);
        statusDot      = findViewById(R.id.statusDot);
        statusText     = findViewById(R.id.statusText);
        recyclerView   = findViewById(R.id.recyclerView);
        emptyState     = findViewById(R.id.emptyState);
        etMessage      = findViewById(R.id.etMessage);
        btnSend        = findViewById(R.id.btnSend);
        btnAttach      = findViewById(R.id.btnAttach);
        inputContainer = findViewById(R.id.inputContainer);
        attachmentBar  = findViewById(R.id.attachmentBar);
        tvAttachName   = findViewById(R.id.tvAttachName);

        findViewById(R.id.btnHistory) .setOnClickListener(v -> openHistory());
        findViewById(R.id.btnNewChat) .setOnClickListener(v -> clearConversation());
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnClearAttach).setOnClickListener(v -> clearAttachment());
    }

    private void setupEdgeToEdge() {
        View root = findViewById(R.id.rootContainer);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        final int basePadBottom = inputContainer.getPaddingBottom();
        final int basePadLeft   = inputContainer.getPaddingLeft();
        final int basePadRight  = inputContainer.getPaddingRight();
        final int basePadTop    = inputContainer.getPaddingTop();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            headerBar.setPadding(sys.left, sys.top, sys.right, 0);
            int bottom = Math.max(sys.bottom, ime.bottom);
            inputContainer.setPadding(basePadLeft, basePadTop, basePadRight, basePadBottom + bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(getWindow(), root);
        if (ctrl != null) {
            ctrl.setAppearanceLightStatusBars(false);
            ctrl.setAppearanceLightNavigationBars(false);
        }
    }

    private void setupRecyclerView() {
        adapter = new ChatAdapter(messages, markwon);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recyclerView.setLayoutManager(lm);
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager l = (LinearLayoutManager) rv.getLayoutManager();
                if (l == null) return;
                isAtBottom = l.findLastCompletelyVisibleItemPosition() >= adapter.getItemCount() - 2;
            }
        });
        updateEmptyState();
        setStatus(StatusState.READY);
    }

    private void setupInput() {
        btnSend.setOnClickListener(v -> {
            if (isStreaming) stopGeneration(); else handleSend();
        });
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) { handleSend(); return true; }
            return false;
        });
        btnAttach.setOnClickListener(v -> filePicker.launch("*/*"));
    }

    private void setupFilePicker() {
        filePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                String name     = resolveFileName(uri);
                String mimeType = getContentResolver().getType(uri);
                if (mimeType == null) mimeType = "application/octet-stream";
                byte[] data;
                try { data = readBytes(uri); }
                catch (IOException e) { return; }
                if (data.length > 10 * 1024 * 1024) {
                    android.widget.Toast.makeText(this,
                        R.string.toast_attach_too_large, android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingAttachName     = name;
                pendingAttachMimeType = mimeType;
                pendingAttachData     = data;
                tvAttachName.setText("📎 " + name);
                attachmentBar.setVisibility(View.VISIBLE);
            });
    }

    private String resolveFileName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "attachment";
        return name;
    }

    private byte[] readBytes(Uri uri) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("Cannot open stream");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private void clearAttachment() {
        pendingAttachName = null; pendingAttachMimeType = null; pendingAttachData = null;
        attachmentBar.setVisibility(View.GONE);
    }

    // ── 发送 ─────────────────────────────────────────────────────────────────

    private void handleSend() {
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text) && pendingAttachData == null) return;
        etMessage.setText("");
        hideKeyboard();

        ApiClient.Attachment attachment = null;
        String displayText = text;
        if (pendingAttachData != null) {
            attachment = new ApiClient.Attachment(
                pendingAttachName, pendingAttachMimeType, pendingAttachData);
            if (displayText.isEmpty()) displayText = "📎 " + pendingAttachName;
            clearAttachment();
        }
        sendMessage(displayText, attachment);
    }

    private void sendMessage(String displayText, ApiClient.Attachment attachment) {
        messages.add(new Message(Message.ROLE_USER, displayText));
        adapter.notifyItemInserted(messages.size() - 1);

        Message assistantMsg = new Message(Message.ROLE_ASSISTANT, "");
        assistantMsg.setStreaming(true);
        messages.add(assistantMsg);
        final int replyIdx = messages.size() - 1;

        updateEmptyState();
        scrollToBottom(true);
        setStreaming(true);
        setStatus(StatusState.REQUESTING);

        List<Message> history = new ArrayList<>(messages.subList(0, replyIdx));

        apiClient.chat(serverUrl, apiToken, model, history, attachment, new StreamCallback() {
            @Override
            public void onToken(String token) {
                runOnUiThread(() -> {
                    Message m = messages.get(replyIdx);
                    m.setContent(m.getContent() + token);
                    adapter.notifyItemChanged(replyIdx, ChatAdapter.PAYLOAD_TEXT);
                    scrollToBottom(false);
                });
            }

            @Override
            public void onComplete() {
                runOnUiThread(() -> {
                    Message m = messages.get(replyIdx);
                    m.setStreaming(false);
                    adapter.notifyItemChanged(replyIdx);
                    setStreaming(false);
                    setStatus(StatusState.CONNECTED);
                    ConversationStore.save(MainActivity.this, currentConvId, messages);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Message m = messages.get(replyIdx);
                    m.setContent("⚠️ " + error);
                    m.setStreaming(false);
                    adapter.notifyItemChanged(replyIdx);
                    setStreaming(false);
                    setStatus(StatusState.ERROR);
                });
            }
        });
    }

    private void stopGeneration() {
        apiClient.cancel();
        if (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (!last.isUser() && last.isStreaming()) {
                last.setStreaming(false);
                if (last.getContent().isEmpty()) last.setContent("（已停止）");
                adapter.notifyItemChanged(messages.size() - 1);
            }
        }
        setStreaming(false);
        setStatus(StatusState.CONNECTED);
        ConversationStore.save(this, currentConvId, messages);
    }

    private void clearConversation() {
        apiClient.cancel();
        int count = messages.size();
        messages.clear();
        adapter.notifyItemRangeRemoved(0, count);
        clearAttachment();
        updateEmptyState();
        setStreaming(false);
        setStatus(StatusState.READY);
        currentConvId = UUID.randomUUID().toString();
    }

    // ── ★ 流式保活：启动/停止前台 Service + 唤醒锁 ───────────────────────────
    private void setStreaming(boolean streaming) {
        isStreaming = streaming;
        btnSend.setImageResource(streaming ? R.drawable.ic_stop : R.drawable.ic_send);
        etMessage.setEnabled(!streaming);
        btnAttach.setEnabled(!streaming);

        if (streaming) {
            // 开始流式：启动前台 Service，让系统认为进程正在进行重要工作，不杀它
            ChatForegroundService.start(this);
        } else {
            // 完成/停止：关闭前台 Service，释放唤醒锁
            ChatForegroundService.stop(this);
        }
    }

    private enum StatusState { READY, REQUESTING, CONNECTED, ERROR }

    private void setStatus(StatusState state) {
        switch (state) {
            case REQUESTING:
                statusDot.setBackgroundResource(R.drawable.dot_yellow);
                statusText.setText(R.string.status_requesting); break;
            case CONNECTED:
                statusDot.setBackgroundResource(R.drawable.dot_green);
                statusText.setText(R.string.status_connected); break;
            case ERROR:
                statusDot.setBackgroundResource(R.drawable.dot_red);
                statusText.setText(R.string.status_error); break;
            default:
                statusDot.setBackgroundResource(R.drawable.dot_green);
                statusText.setText(R.string.status_ready); break;
        }
    }

    private void updateEmptyState() {
        emptyState.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void scrollToBottom(boolean force) {
        if ((force || isAtBottom) && !messages.isEmpty())
            recyclerView.post(() -> recyclerView.smoothScrollToPosition(messages.size() - 1));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etMessage.getWindowToken(), 0);
    }

    private void openHistory() {
        startActivity(new Intent(this, HistoryActivity.class));
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences p = getSharedPreferences("openclaw_prefs", MODE_PRIVATE);
        serverUrl = p.getString("server_url", serverUrl);
        apiToken  = p.getString("api_token",  apiToken);
        model     = p.getString("model",      model);
        if (!isStreaming) setStatus(StatusState.READY);
    }

    @Override
    protected void onDestroy() {
        if (apiClient != null) apiClient.cancel();
        // 确保 Service 被关闭
        ChatForegroundService.stop(this);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { moveTaskToBack(true); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
