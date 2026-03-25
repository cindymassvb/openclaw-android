package com.openclaw.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private TextInputEditText etUrl;
    private TextInputEditText etToken;
    private TextInputEditText etModel;
    private TextView          tvDeviceId;
    private boolean           firstLaunch;
    private ApiClient         apiClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        firstLaunch = getIntent().getBooleanExtra("first_launch", false);
        apiClient   = new ApiClient(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (!firstLaunch && getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        etUrl      = findViewById(R.id.etServerUrl);
        etToken    = findViewById(R.id.etToken);
        etModel    = findViewById(R.id.etModel);
        tvDeviceId = findViewById(R.id.tvDeviceId);

        View     clearUrlBtn = findViewById(R.id.urlClearBtn);
        TextView tvVersion   = findViewById(R.id.tvVersion);

        // 版本号
        tvVersion.setText(getString(R.string.app_name) + " " + getString(R.string.app_version));

        // 加载已保存设置
        SharedPreferences p = getSharedPreferences("openclaw_prefs", MODE_PRIVATE);
        etUrl.setText(p.getString("server_url", ""));
        etToken.setText(p.getString("api_token", ""));
        etModel.setText(p.getString("model", "openclaw"));

        // URL 清除按钮
        String savedUrl = p.getString("server_url", "");
        clearUrlBtn.setVisibility(savedUrl.isEmpty() ? View.GONE : View.VISIBLE);
        clearUrlBtn.setOnClickListener(v -> etUrl.setText(""));
        etUrl.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                clearUrlBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            public void afterTextChanged(Editable s) {}
        });

        // 设备 ID
        String deviceId = apiClient.getDeviceId();
        tvDeviceId.setText(deviceId != null && !deviceId.isEmpty() ? deviceId : "—");
        findViewById(R.id.btnCopyDeviceId).setOnClickListener(v -> {
            String id = tvDeviceId.getText().toString();
            if (!id.isEmpty() && !id.equals("—")) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("device_id", id));
                Toast.makeText(this, R.string.toast_device_id_copied, Toast.LENGTH_SHORT).show();
            }
        });

        // 获取模型列表
        findViewById(R.id.btnFetchModels).setOnClickListener(v -> fetchModels());

        // 打开控制台
        findViewById(R.id.btnOpenConsole).setOnClickListener(v -> openConsole());

        // 保存
        findViewById(R.id.btnSave).setOnClickListener(v -> save());
    }

    // ── 从服务器获取模型列表 ──────────────────────────────────────────────────
    private void fetchModels() {
        String url   = etUrl.getText()   != null ? etUrl.getText().toString().trim()   : "";
        String token = etToken.getText() != null ? etToken.getText().toString().trim() : "";

        if (url.isEmpty()) { etUrl.setError("请先填写服务器地址"); return; }
        if (!url.startsWith("http")) url = "https://" + url;

        final View btn = findViewById(R.id.btnFetchModels);
        btn.setEnabled(false);
        ((TextView) btn).setText("获取中…");

        final String finalUrl = url;
        apiClient.fetchModels(finalUrl, token, new ApiClient.ModelsCallback() {
            @Override
            public void onSuccess(List<String> models) {
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    ((TextView) btn).setText("获取");
                    if (models.isEmpty()) {
                        Toast.makeText(SettingsActivity.this, "未找到模型", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] items = models.toArray(new String[0]);
                    new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("选择模型")
                        .setItems(items, (dialog, which) -> etModel.setText(items[which]))
                        .setNegativeButton("取消", null)
                        .show();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btn.setEnabled(true);
                    ((TextView) btn).setText("获取");
                    Toast.makeText(SettingsActivity.this, "获取失败：" + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ── 在浏览器中打开控制台 ──────────────────────────────────────────────────
    private void openConsole() {
        String url = etUrl.getText() != null ? etUrl.getText().toString().trim() : "";
        if (url.isEmpty()) { etUrl.setError("请先填写服务器地址"); return; }
        if (!url.startsWith("http")) url = "https://" + url;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    // ── 保存 ─────────────────────────────────────────────────────────────────
    private void save() {
        String url   = etUrl.getText()   != null ? etUrl.getText().toString().trim()   : "";
        String token = etToken.getText() != null ? etToken.getText().toString().trim() : "";
        String model = etModel.getText() != null ? etModel.getText().toString().trim() : "openclaw";

        if (url.isEmpty()) { etUrl.setError("请输入服务器地址"); return; }
        if (!url.startsWith("http")) url = "https://" + url;
        if (!url.endsWith("/")) url += "/";
        if (model.isEmpty()) model = "openclaw";

        getSharedPreferences("openclaw_prefs", MODE_PRIVATE).edit()
            .putString("server_url", url)
            .putString("api_token",  token)
            .putString("model",      model)
            .apply();

        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show();
        if (firstLaunch) startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
