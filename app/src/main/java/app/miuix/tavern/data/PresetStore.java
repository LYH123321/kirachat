package app.miuix.tavern.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * SillyTavern OpenAI 预设（prompt preset）解析与存储。
 * 兼容 ST 导出的 preset JSON：prompts 数组 + prompt_order（character_id 100001=default）。
 */
public final class PresetStore {

    public static class PresetPrompt {
        public String identifier;
        public String name;
        public String role;     // system / user / assistant
        public String content;
        public boolean enabled = true;
        /** ST 标记：注入到对话区而非 system 区（简化处理：一律按消息顺序排在 system 后）。 */
        public boolean marker;
        public boolean injection;
    }

    public static class Preset {
        public String id;
        public String name;
        public final List<PresetPrompt> prompts = new ArrayList<>();
        public boolean enabled = true;
        public String characterId = ""; // 绑定角色卡；空 = 全局预设
    }

    private static final String PREF_KEY = "presets";

    // ---- 解析 ----

    /** 解析 ST 预设 JSON；失败返回 null。 */
    public static Preset parse(String json, String fallbackName) {
        try {
            JSONObject root = new JSONObject(json);
            Preset preset = new Preset();
            preset.id = UUID.randomUUID().toString();
            preset.name = root.optString("name", fallbackName != null ? fallbackName : "导入预设");

            // prompts 数组：identifier -> 内容
            java.util.Map<String, PresetPrompt> byId = new java.util.LinkedHashMap<>();
            JSONArray prompts = root.optJSONArray("prompts");
            if (prompts != null) {
                for (int i = 0; i < prompts.length(); i++) {
                    JSONObject p = prompts.optJSONObject(i);
                    if (p == null) continue;
                    PresetPrompt pp = new PresetPrompt();
                    pp.identifier = p.optString("identifier", UUID.randomUUID().toString());
                    pp.name = p.optString("name", pp.identifier);
                    pp.role = p.optString("role", "system");
                    if (pp.role.isEmpty()) pp.role = "system";
                    pp.content = p.optString("content", "");
                    pp.marker = p.optBoolean("marker", false);
                    pp.injection = p.optBoolean("injection", false);
                    if (!pp.marker && pp.content.trim().isEmpty()) continue;
                    byId.put(pp.identifier, pp);
                }
            }

            // prompt_order：取 character_id=100001（default）的顺序与 enabled
            JSONArray orders = root.optJSONArray("prompt_order");
            boolean ordered = false;
            if (orders != null) {
                for (int i = 0; i < orders.length(); i++) {
                    JSONObject o = orders.optJSONObject(i);
                    if (o == null) continue;
                    int charId = o.optInt("character_id", -1);
                    if (charId != 100001 && charId != 100000 && i != 0) continue;
                    JSONArray order = o.optJSONArray("order");
                    if (order == null) continue;
                    ordered = true;
                    for (int k = 0; k < order.length(); k++) {
                        JSONObject item = order.optJSONObject(k);
                        if (item == null) continue;
                        String identifier = item.optString("identifier", "");
                        PresetPrompt pp = byId.get(identifier);
                        if (pp == null) continue;
                        pp.enabled = item.optBoolean("enabled", true);
                        preset.prompts.add(pp);
                    }
                    break;
                }
            }
            if (!ordered) {
                // 没有 prompt_order 就全收，默认启用
                preset.prompts.addAll(byId.values());
            }
            // 过滤 marker（chat-start 占位符类），只留真实 prompt
            List<PresetPrompt> real = new ArrayList<>();
            for (PresetPrompt pp : preset.prompts) {
                if (pp.marker) continue;
                real.add(pp);
            }
            preset.prompts.clear();
            preset.prompts.addAll(real);
            return preset.prompts.isEmpty() ? null : preset;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 存取 ----

    public synchronized List<Preset> getPresets(LocalStore store) {
        List<Preset> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(store.getRawPref(PREF_KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                Preset p = fromStoreJson(array.optJSONObject(i));
                if (p != null) out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void savePresets(LocalStore store, List<Preset> presets) {
        try {
            JSONArray array = new JSONArray();
            for (Preset p : presets) array.put(toStoreJson(p));
            store.putRawPref(PREF_KEY, array.toString());
        } catch (Exception ignored) {
        }
    }

    public synchronized void upsertPreset(LocalStore store, Preset preset) {
        List<Preset> presets = getPresets(store);
        boolean found = false;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(preset.id)) {
                presets.set(i, preset);
                found = true;
                break;
            }
        }
        if (!found) presets.add(preset);
        savePresets(store, presets);
    }

    public synchronized void deletePreset(LocalStore store, String id) {
        List<Preset> presets = getPresets(store);
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(id)) {
                presets.remove(i);
                break;
            }
        }
        savePresets(store, presets);
    }

    /** 取当前启用的预设：优先取绑定到该角色的预设，否则取全局预设（第一个 enabled 且勾选了 prompt 的）。 */
    public synchronized Preset activePreset(LocalStore store) {
        return activePreset(store, null);
    }
    public synchronized Preset activePreset(LocalStore store, String characterId) {
        Preset global = null;
        for (Preset p : getPresets(store)) {
            if (!p.enabled) continue;
            boolean hasEnabled = false;
            for (PresetPrompt pp : p.prompts) {
                if (pp.enabled) { hasEnabled = true; break; }
            }
            if (!hasEnabled) continue;
            boolean bound = p.characterId != null && !p.characterId.isEmpty();
            if (bound && p.characterId.equals(characterId)) return p;
            if (!bound && global == null) global = p;
        }
        return global;
    }

    // ---- 序列化 ----

    private static JSONObject toStoreJson(Preset p) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("enabled", p.enabled);
            o.put("characterId", p.characterId);
            JSONArray array = new JSONArray();
            for (PresetPrompt pp : p.prompts) {
                JSONObject po = new JSONObject();
                po.put("identifier", pp.identifier);
                po.put("name", pp.name);
                po.put("role", pp.role);
                po.put("content", pp.content);
                po.put("enabled", pp.enabled);
                array.put(po);
            }
            o.put("prompts", array);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Preset fromStoreJson(JSONObject o) {
        if (o == null) return null;
        Preset p = new Preset();
        p.id = o.optString("id", UUID.randomUUID().toString());
        p.name = o.optString("name", "未命名");
        p.enabled = o.optBoolean("enabled", true);
        p.characterId = o.optString("characterId", "");
        JSONArray array = o.optJSONArray("prompts");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject po = array.optJSONObject(i);
                if (po == null) continue;
                PresetPrompt pp = new PresetPrompt();
                pp.identifier = po.optString("identifier", "");
                pp.name = po.optString("name", "");
                pp.role = po.optString("role", "system");
                pp.content = po.optString("content", "");
                pp.enabled = po.optBoolean("enabled", true);
                p.prompts.add(pp);
            }
        }
        return p;
    }

    /** 把启用中的预设 prompt 展开为 messages 数组（宏替换后）。 */
    public static JSONArray buildPresetMessages(Preset preset, String charName, String userName) {
        JSONArray messages = new JSONArray();
        if (preset == null) return messages;
        for (PresetPrompt pp : preset.prompts) {
            if (!pp.enabled || pp.content == null || pp.content.trim().isEmpty()) continue;
            String content = pp.content
                    .replace("{{char}}", charName == null ? "" : charName)
                    .replace("{{user}}", userName == null ? "" : userName)
                    .replace("<BOT>", charName == null ? "" : charName)
                    .replace("<USER>", userName == null ? "" : userName);
            // 通配清除未知占位符（宿主/中转不认识 {{...}} 会报错的场景兜底）
            content = content.replaceAll("\\{\\{[^}]*\\}\\}", "");
            try {
                messages.put(new JSONObject()
                        .put("role", pp.role)
                        .put("content", content));
            } catch (Exception ignored) {
            }
        }
        return messages;
    }
}