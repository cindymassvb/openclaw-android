package com.openclaw.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import io.noties.markwon.Markwon;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // payload 常量：仅刷新文本，不重建整个 item（消除流式输出闪烁）
    public static final Object PAYLOAD_TEXT = new Object();

    private static final int TYPE_USER      = 0;
    private static final int TYPE_ASSISTANT = 1;

    private final List<Message> messages;
    private final Markwon       markwon;

    public ChatAdapter(List<Message> messages, Markwon markwon) {
        this.messages = messages;
        this.markwon  = markwon;
    }

    @Override
    public int getItemViewType(int pos) {
        return messages.get(pos).isUser() ? TYPE_USER : TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new UserVH(inf.inflate(R.layout.item_message_user, parent, false));
        } else {
            return new AssistantVH(inf.inflate(R.layout.item_message_assistant, parent, false));
        }
    }

    // ── 带 payload 的快速更新（仅文本，不闪烁）──────────────────────────────
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder,
                                 int pos,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // 快速路径：只更新文字，避免整个 item 重建产生闪烁
            Message msg = messages.get(pos);
            if (holder instanceof AssistantVH) {
                ((AssistantVH) holder).updateText(msg);
            } else if (holder instanceof UserVH) {
                ((UserVH) holder).tvContent.setText(msg.getContent());
            }
            return;
        }
        onBindViewHolder(holder, pos);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
        Message msg = messages.get(pos);
        if (holder instanceof UserVH) {
            ((UserVH) holder).bind(msg);
        } else {
            ((AssistantVH) holder).bind(msg, markwon);
        }
    }

    @Override
    public int getItemCount() { return messages.size(); }

    // ─── User bubble ──────────────────────────────────────────────────────────
    static class UserVH extends RecyclerView.ViewHolder {
        final TextView tvContent;

        UserVH(View v) {
            super(v);
            tvContent = v.findViewById(R.id.tvContent);
            tvContent.setOnLongClickListener(view -> {
                copyToClipboard(view.getContext(), tvContent.getText().toString());
                return true;
            });
        }

        void bind(Message msg) {
            tvContent.setText(msg.getContent());
        }
    }

    // ─── Assistant bubble ─────────────────────────────────────────────────────
    class AssistantVH extends RecyclerView.ViewHolder {
        final TextView tvContent;

        AssistantVH(View v) {
            super(v);
            tvContent = v.findViewById(R.id.tvContent);
            tvContent.setOnLongClickListener(view -> {
                CharSequence text = tvContent.getText();
                if (text != null && !text.toString().equals("●   ●   ●")) {
                    copyToClipboard(view.getContext(), text.toString());
                }
                return true;
            });
        }

        void bind(Message msg, Markwon markwon) {
            updateText(msg);
        }

        void updateText(Message msg) {
            if (msg.isStreaming()) {
                // 流式中：纯文本，不渲染 Markdown，避免频繁渲染造成闪烁
                String content = msg.getContent();
                tvContent.setText(content.isEmpty() ? "●   ●   ●" : content);
            } else {
                // 完成后：渲染 Markdown
                String content = msg.getContent();
                if (content.isEmpty()) {
                    tvContent.setText("");
                } else {
                    markwon.setMarkdown(tvContent, content);
                }
            }
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────
    private static void copyToClipboard(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", text));
            Toast.makeText(ctx, R.string.toast_copied, Toast.LENGTH_SHORT).show();
        }
    }
}
