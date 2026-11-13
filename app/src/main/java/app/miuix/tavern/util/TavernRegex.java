package app.miuix.tavern.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SillyTavern regex_scripts 兼容层（实现思路参考 SillyTavern 社区生态）。
 * 支持 display（显示层）与 prompt（发送层）两个时机；findingRegex 支持 /pattern/flags 形式。
 */
public final class TavernRegex {

    public static final int PLACEMENT_USER_INPUT = 1;
    public static final int PLACEMENT_AI_OUTPUT = 2;

    public static class Script {
        public String scriptName;
        public String findRegex;
        public String replaceString;
        public boolean disabled;
        public boolean promptOnly;
        public boolean displayOnly;
        public final List<Integer> placement = new ArrayList<>();
        public Pattern compiled;
        public boolean substituteRegex;

        public String label() {
            return scriptName == null || scriptName.trim().isEmpty()
                    ? (findRegex == null ? "未命名" : findRegex) : scriptName;
        }
    }

    private TavernRegex() {
    }

    /** 从角色卡 data JSON（含 data.extensions.regex_scripts）解析正则脚本列表。 */
    public static List<Script> fromCardJson(String cardDataJson) {
        List<Script> out = new ArrayList<>();
        if (cardDataJson == null || cardDataJson.trim().isEmpty()) return out;
        try {
            JSONObject data = new JSONObject(cardDataJson);
            JSONArray array = data.optJSONArray("regex_scripts");
            JSONObject ext = data.optJSONObject("extensions");
            if (array == null && ext != null) array = ext.optJSONArray("regex_scripts");
            if (array == null) return out;
            for (int i = 0; i < array.length(); i++) {
                Script s = parse(array.optJSONObject(i));
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Script parse(JSONObject o) {
        if (o == null) return null;
        Script s = new Script();
        s.scriptName = o.optString("scriptName", "");
        s.findRegex = o.optString("findRegex", "");
        s.replaceString = o.optString("replaceString", "");
        s.disabled = o.optBoolean("disabled", false);
        s.substituteRegex = o.optBoolean("substituteRegex", false);
        if (o.has("markdownOnly")) s.displayOnly = o.optBoolean("markdownOnly", false);
        if (o.has("promptOnly")) s.promptOnly = o.optBoolean("promptOnly", false);

        String find = s.findRegex;
        int flags = 0;
        if (find != null && find.length() > 2 && find.startsWith("/")) {
            int last = find.lastIndexOf('/');
            if (last > 1) {
                String flagStr = find.substring(last + 1);
                find = find.substring(1, last);
                if (flagStr.contains("i")) flags |= Pattern.CASE_INSENSITIVE;
                if (flagStr.contains("m")) flags |= Pattern.MULTILINE;
                if (flagStr.contains("s")) flags |= Pattern.DOTALL;
            }
        }
        if (find == null || find.isEmpty()) return null;
        try {
            s.compiled = Pattern.compile(find, flags);
        } catch (Exception e) {
            return null;
        }
        JSONArray placement = o.optJSONArray("placement");
        if (placement != null) {
            for (int i = 0; i < placement.length(); i++) {
                s.placement.add(placement.optInt(i));
            }
        }
        return s;
    }

    private static boolean affects(Script s, int placementBit) {
        if (s.placement.isEmpty()) return true;
        for (int p : s.placement) {
            if ((p & placementBit) != 0) return true;
        }
        return false;
    }

    /** 对 AI 输出应用显示层正则（UI 渲染用，不改历史原文）。 */
    public static String applyDisplay(List<Script> scripts, String text) {
        return apply(scripts, text, PLACEMENT_AI_OUTPUT, true);
    }

    /** 对发送给模型的 AI 历史应用 prompt 层正则。 */
    public static String applyPrompt(List<Script> scripts, String text) {
        return apply(scripts, text, PLACEMENT_AI_OUTPUT, false);
    }

    /** 对用户输入应用正则。 */
    public static String applyUserInput(List<Script> scripts, String text, boolean display) {
        return apply(scripts, text, PLACEMENT_USER_INPUT, display);
    }

    private static String apply(List<Script> scripts, String text, int placementBit, boolean displayPass) {
        if (scripts == null || scripts.isEmpty() || text == null || text.isEmpty()) return text;
        String current = text;
        for (Script s : scripts) {
            if (s.disabled || s.compiled == null) continue;
            if (!affects(s, placementBit)) continue;
            if (displayPass && s.promptOnly) continue;
            if (!displayPass && s.displayOnly) continue;
            current = replaceAll(s, current);
        }
        return current;
    }

    private static String replaceAll(Script s, String input) {
        Matcher m = s.compiled.matcher(input);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (m.find()) {
            sb.append(input, lastEnd, m.start()).append(expandReplacement(s, m));
            lastEnd = m.end();
        }
        sb.append(input, lastEnd, input.length());
        return sb.toString();
    }

    private static String expandReplacement(Script s, Matcher m) {
        String replacement = s.replaceString == null ? "" : s.replaceString;
        if (s.substituteRegex && replacement.contains("{{match}}")) {
            String capture = m.groupCount() > 0 ? m.group(1) : null;
            return replacement.replace("{{match}}",
                    capture != null ? capture : m.group(0));
        }
        StringBuilder rep = new StringBuilder();
        for (int i = 0; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            if (c == '$' && i + 1 < replacement.length()
                    && Character.isDigit(replacement.charAt(i + 1))) {
                int j = i + 1;
                StringBuilder num = new StringBuilder();
                while (j < replacement.length() && Character.isDigit(replacement.charAt(j))) {
                    num.append(replacement.charAt(j));
                    j++;
                }
                int groupIdx = Integer.parseInt(num.toString());
                String g = groupIdx <= m.groupCount() ? m.group(groupIdx) : "";
                rep.append(g == null ? "" : g);
                i = j - 1;
            } else {
                rep.append(c);
            }
        }
        return rep.toString();
    }
}