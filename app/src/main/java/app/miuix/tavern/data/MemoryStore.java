package app.miuix.tavern.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import app.miuix.tavern.model.AppConfig;
import app.miuix.tavern.model.ChatMessage;
import app.miuix.tavern.network.ApiClient;
import app.miuix.prompts.Mem0Prompts;

/**
 * 自动记忆模块——核心机制与 prompt 原样搬运自开源项目 mem0 (mem0ai/mem0)：
 * 1. FACT_RETRIEVAL_PROMPT：从最近对话抽取事实（保持用户语言）；
 * 2. DEFAULT_UPDATE_MEMORY_PROMPT：把新事实与已有记忆对比，产出 ADD/UPDATE/DELETE/NONE 决策；
 * 两次 LLM 调用，决策结果落库。检索时按字符预算取最近记忆注入 system prompt。
 */
public final class MemoryStore {

    public static class MemoryItem {
        public String id;
        public String text;
        public long createdAt;
    }

    public interface ExtractCallback {
        void onDone(int extractedCount);
    }

    private static final String PREF_KEY = "memories";
    /** 注入 system prompt 的记忆字符预算。 */
    private static final int INJECT_BUDGET_CHARS = 3000;
    /** 抽取用的对话窗口大小（最近 N 条消息）。 */
    private static final int EXTRACT_WINDOW = 12;

    private final LocalStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;

    public MemoryStore(LocalStore store) {
        this.store = store;
    }

    // ---- 存取 ----

    public synchronized List<MemoryItem> getAll() {
        List<MemoryItem> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(store.getRawPref(PREF_KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                MemoryItem item = new MemoryItem();
                item.id = o.optString("id", "");
                item.text = o.optString("text", "");
                item.createdAt = o.optLong("createdAt", 0L);
                out.add(item);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized int count() {
        return getAll().size();
    }

    private synchronized void saveAll(List<MemoryItem> items) {
        try {
            JSONArray array = new JSONArray();
            for (MemoryItem item : items) {
                JSONObject o = new JSONObject();
                o.put("id", item.id);
                o.put("text", item.text);
                o.put("createdAt", item.createdAt);
                array.put(o);
            }
            store.putRawPref(PREF_KEY, array.toString());
        } catch (Exception ignored) {
        }
    }

    public void clearAll() {
        saveAll(new ArrayList<MemoryItem>());
    }

    public void deleteById(String id) {
        List<MemoryItem> items = getAll();
        List<MemoryItem> remaining = new ArrayList<>();
        for (MemoryItem item : items) {
            if (item.id.equals(id)) continue;
            remaining.add(item);
        }
        saveAll(remaining);
    }

    public void addManual(String text) {
        MemoryItem item = new MemoryItem();
        item.id = UUID.randomUUID().toString();
        item.text = text;
        item.createdAt = System.currentTimeMillis();
        List<MemoryItem> items = getAll();
        items.add(item);
        saveAll(items);
    }

    // ---- 注入 ----

    /** 按字符预算返回注入 system prompt 的记忆文本（最近优先）。 */
    public String buildInjection() {
        List<MemoryItem> items = getAll();
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            String text = items.get(i).text;
            if (text == null || text.trim().isEmpty()) continue;
            int cost = text.length() + 2;
            if (used + cost > INJECT_BUDGET_CHARS) break;
            if (sb.length() > 0) sb.insert(0, "\n");
            sb.insert(0, "- " + text);
            used += cost;
        }
        return sb.toString();
    }

    // ---- 抽取 + 决策（mem0 两阶段） ----

    /** 用已完成的模型回复文本执行 mem0 两阶段（抽取 → 决策落库）。 */
    public void extractFacts(Context context, AppConfig config,
                             SecureStore secureStore, String apiKey,
                             List<ChatMessage> recentMessages,
                             String assistantReply,
                             final ExtractCallback callback) {
        if (running) {
            if (callback != null) main.post(() -> callback.onDone(0));
            return;
        }
        List<ChatMessage> window = new ArrayList<>();
        for (int i = recentMessages.size() - 1; i >= 0 && window.size() < EXTRACT_WINDOW; i--) {
            ChatMessage m = recentMessages.get(i);
            if (ChatMessage.SYSTEM.equals(m.role)) continue;
            window.add(0, m);
        }

        StringBuilder conversation = new StringBuilder();
        for (ChatMessage m : window) {
            String who = ChatMessage.USER.equals(m.role) ? "User" : "Assistant";
            conversation.append(who).append(": ").append(m.content).append("\n");
        }
        conversation.append("Assistant: ").append(assistantReply == null ? "" : assistantReply).append("\n");

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String extractUser = Mem0Prompts.factRetrievalPrompt(conversation.toString(), today);

        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", Mem0Prompts.FACT_RETRIEVAL_SYSTEM);
            messages.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", extractUser);
            messages.put(user);
        } catch (Exception e) {
            if (callback != null) main.post(() -> callback.onDone(0));
            return;
        }

        running = true;
        ApiClient.generate(context, config, secureStore, apiKey, messages,
                new ApiClient.StreamCallback() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onDelta(String delta) {
                        buf.append(delta);
                    }

                    @Override
                    public void onComplete() {
                        List<String> facts = Mem0Prompts.parseFacts(buf.toString());
                        if (facts.isEmpty()) {
                            running = false;
                            if (callback != null) main.post(() -> callback.onDone(0));
                            return;
                        }
                        runUpdatePhase(context, config, secureStore, apiKey, facts, callback);
                    }

                    @Override
                    public void onError(String message) {
                        running = false;
                        if (callback != null) main.post(() -> callback.onDone(0));
                    }
                });
    }

    /** mem0 第二阶段：把新事实与已有记忆对比，产出 ADD/UPDATE/DELETE 决策。 */
    private void runUpdatePhase(Context context, AppConfig config,
                                SecureStore secureStore, String apiKey,
                                List<String> facts,
                                final ExtractCallback callback) {
        List<MemoryItem> existing = getAll();
        StringBuilder oldMemoryJson = new StringBuilder("[");
        for (int i = 0; i < existing.size(); i++) {
            if (i > 0) oldMemoryJson.append(",");
            JSONObject o = new JSONObject();
            try {
                o.put("id", String.valueOf(i));
                o.put("text", existing.get(i).text);
                oldMemoryJson.append(o.toString());
            } catch (Exception ignored) {
            }
        }
        oldMemoryJson.append("]");

        StringBuilder factsJson = new StringBuilder("[");
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) factsJson.append(",");
            factsJson.append("\"").append(facts.get(i).replace("\"", "\\\"")).append("\"");
        }
        factsJson.append("]");

        String updatePrompt = Mem0Prompts.updateMemoryPrompt(
                oldMemoryJson.toString(), factsJson.toString());

        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", Mem0Prompts.DEFAULT_UPDATE_MEMORY_PROMPT);
            messages.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", updatePrompt);
            messages.put(user);
        } catch (Exception e) {
            running = false;
            if (callback != null) main.post(() -> callback.onDone(0));
            return;
        }

        ApiClient.generate(context, config, secureStore, apiKey, messages,
                new ApiClient.StreamCallback() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onDelta(String delta) {
                        buf.append(delta);
                    }

                    @Override
                    public void onComplete() {
                        applyDecisions(buf.toString(), existing, facts, callback);
                        running = false;
                    }

                    @Override
                    public void onError(String message) {
                        running = false;
                        if (callback != null) main.post(() -> callback.onDone(0));
                    }
                });
    }

    /** 解析决策 JSON，把 ADD/UPDATE/DELETE 应用到本地存储。 */
    private void applyDecisions(String response, List<MemoryItem> existingBefore,
                                List<String> facts, ExtractCallback callback) {
        int changed = 0;
        List<MemoryItem> current = getAll();
        try {
            String text = Mem0Prompts.stripCodeFence(response);
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JSONObject root = new JSONObject(text.substring(start, end + 1));
                JSONArray memory = root.optJSONArray("memory");
                if (memory != null) {
                    for (int i = 0; i < memory.length(); i++) {
                        JSONObject o = memory.optJSONObject(i);
                        if (o == null) continue;
                        String event = o.optString("event", "NONE");
                        String idStr = o.optString("id", "");
                        String textValue = o.optString("text", "");
                        if ("NONE".equals(event) || textValue.trim().isEmpty()) continue;
                        changed++;
                        if ("ADD".equals(event)) {
                            MemoryItem item = new MemoryItem();
                            item.id = UUID.randomUUID().toString();
                            item.text = textValue;
                            item.createdAt = System.currentTimeMillis();
                            current.add(item);
                        } else if ("UPDATE".equals(event)) {
                            try {
                                int idx = Integer.parseInt(idStr);
                                if (idx >= 0 && idx < current.size()) {
                                    current.get(idx).text = textValue;
                                    current.get(idx).createdAt = System.currentTimeMillis();
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        } else if ("DELETE".equals(event)) {
                            try {
                                int idx = Integer.parseInt(idStr);
                                if (idx >= 0 && idx < current.size()) {
                                    current.remove(idx);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        saveAll(current);
        final int finalChanged = changed;
        if (callback != null) main.post(() -> callback.onDone(finalChanged));
    }
}