package com.openclaw.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;


public class ConversationStore {

    private static final String TAG    = "ConvStore";
    private static final String DIR    = "convs";
    private static final int    MAX_CONVS = 100; // 最多保留100条历史

    public static class ConvSummary {
        public final String id;
        public final String title;
        public final long   updatedAt;
        public final int    msgCount;

        ConvSummary(String id, String title, long updatedAt, int msgCount) {
            this.id        = id;
            this.title     = title;
            this.updatedAt = updatedAt;
            this.msgCount  = msgCount;
        }

        
        public String timeLabel() {
            long diff = System.currentTimeMillis() - updatedAt;
            if (diff < 60_000)              return "刚刚";
            if (diff < 3_600_000)           return (diff / 60_000) + " 分钟前";
            if (diff < 86_400_000)          return (diff / 3_600_000) + " 小时前";
            if (diff < 7 * 86_400_000L)     return (diff / 86_400_000) + " 天前";
            return new SimpleDateFormat("MM/dd", Locale.getDefault()).format(new Date(updatedAt));
        }
    }

    private static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void save(Context ctx, String convId, List<Message> messages) {
        if (messages.isEmpty()) return;
        try {
            String title = "";
            for (Message m : messages) {
                if (m.isUser() && !m.getContent().isEmpty()) {
                    title = m.getContent();
                    if (title.length() > 40) title = title.substring(0, 40) + "…";
                    break;
                }
            }
            if (title.isEmpty()) return; // 没有用户消息则不保存

            JSONArray arr = new JSONArray();
            for (Message m : messages) {
                if (m.isStreaming()) continue; // 跳过未完成的消息
                JSONObject obj = new JSONObject();
                obj.put("role",    m.getRole());
                obj.put("content", m.getContent());
                arr.put(obj);
            }

            JSONObject conv = new JSONObject();
            conv.put("id",        convId);
            conv.put("title",     title);
            conv.put("updatedAt", System.currentTimeMillis());
            conv.put("msgCount",  arr.length());
            conv.put("messages",  arr);

            File file = new File(dir(ctx), convId + ".json");
            try (FileWriter w = new FileWriter(file)) {
                w.write(conv.toString());
            }

            pruneOldConversations(ctx);
        } catch (Exception e) {
            Log.e(TAG, "save error", e);
        }
    }

    public static List<ConvSummary> loadAll(Context ctx) {
        List<ConvSummary> list = new ArrayList<>();
        File d = dir(ctx);
        File[] files = d.listFiles((f, name) -> name.endsWith(".json"));
        if (files == null) return list;
        for (File f : files) {
            try (FileReader r = new FileReader(f)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                JSONObject obj = new JSONObject(sb.toString());
                list.add(new ConvSummary(
                    obj.getString("id"),
                    obj.getString("title"),
                    obj.getLong("updatedAt"),
                    obj.optInt("msgCount", 0)
                ));
            } catch (Exception ignored) {}
        }
        Collections.sort(list, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return list;
    }

    public static List<Message> loadMessages(Context ctx, String convId) {
        List<Message> msgs = new ArrayList<>();
        File file = new File(dir(ctx), convId + ".json");
        if (!file.exists()) return msgs;
        try (FileReader r = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            JSONObject conv = new JSONObject(sb.toString());
            JSONArray arr = conv.getJSONArray("messages");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                msgs.add(new Message(m.getString("role"), m.getString("content")));
            }
        } catch (Exception e) {
            Log.e(TAG, "load error", e);
        }
        return msgs;
    }

    public static void delete(Context ctx, String convId) {
        new File(dir(ctx), convId + ".json").delete();
    }

    private static void pruneOldConversations(Context ctx) {
        File d = dir(ctx);
        File[] files = d.listFiles((f, name) -> name.endsWith(".json"));
        if (files == null || files.length <= MAX_CONVS) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_CONVS; i++) {
            files[i].delete();
        }
    }
}