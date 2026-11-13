package app.miuix.tavern.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 独立世界书库：支持导入 SillyTavern world book JSON 格式
 * （entries 对象形式 {"0":{...}} 与数组形式均兼容），
 * 也支持条目手动新建/编辑/开关/删除。
 */
public final class WorldBookStore {

    public static class Entry {
        public String id;
        public String bookId;
        public String keys = "";
        public String secondaryKeys = "";
        public String content = "";
        public boolean constant = false;
        public boolean enabled = true;
        public String comment = "";
        public int order = 100;

        public String keysDisplay() {
            if (keys == null || keys.trim().isEmpty()) return constant ? "[常驻]" : "[无关键词]";
            return keys;
        }
    }

    public static class Book {
        public String id;
        public String name;
        public final List<Entry> entries = new ArrayList<>();
        public boolean enabled = true;

        public int enabledCount() {
            int n = 0;
            for (Entry e : entries) if (e.enabled) n++;
            return n;
        }
    }

    public interface Listener {
        void onBooksChanged();
    }

    private final LocalStore store;
    private Listener listener;

    public WorldBookStore(LocalStore store) {
        this.store = store;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void changed() {
        if (listener != null) listener.onBooksChanged();
    }

    // ---- 存取 ----

    public synchronized List<Book> getBooks() {
        List<Book> out = new ArrayList<>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(
                    store.getRawPref("world_books", "[]"));
            JSONArray array = root.optJSONArray("books");
            if (array == null) return out;
            for (int i = 0; i < array.length(); i++) {
                Book b = parseBook(array.optJSONObject(i));
                if (b != null) out.add(b);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void saveBooks(List<Book> books) {
        try {
            JSONArray array = new JSONArray();
            for (Book b : books) array.put(bookJson(b));
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("books", array);
            store.putRawPref("world_books", root.toString());
            changed();
        } catch (Exception ignored) {
        }
    }

    public synchronized Book getBook(String id) {
        for (Book b : getBooks()) {
            if (b.id.equals(id)) return b;
        }
        return null;
    }

    public synchronized void upsertBook(Book book) {
        List<Book> books = getBooks();
        boolean found = false;
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).id.equals(book.id)) {
                books.set(i, book);
                found = true;
                break;
            }
        }
        if (!found) books.add(book);
        saveBooks(books);
    }

    public synchronized void deleteBook(String id) {
        List<Book> books = getBooks();
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).id.equals(id)) {
                books.remove(i);
                break;
            }
        }
        saveBooks(books);
    }

    /** 把一本书的全部启用条目合并给某张角色卡（写回 card.character_book 关联）。 */
    public synchronized void attachBookToCharacter(Book book, String characterId) {
        LocalStore.CharacterLink link = new LocalStore.CharacterLink();
        link.characterId = characterId;
        link.worldBookId = book.id;
        store.upsertCharacterLink(link);
    }

    // ---- 导入 ----

    /** 解析 ST 世界书 JSON（顶层或嵌套 entries；支持对象/数组两种形态）。 */
    public static Book parseWorldBookJson(String json, String fallbackName) {
        try {
            JSONObject root = new JSONObject(json);
            Book book = new Book();
            book.id = UUID.randomUUID().toString();
            book.name = root.optString("name", fallbackName != null ? fallbackName : "导入世界书");
            JSONObject entries = root.optJSONObject("entries");
            JSONArray entryArray = root.optJSONArray("entries");
            if (entries != null) {
                java.util.Iterator<String> it = entries.keys();
                while (it.hasNext()) {
                    JSONObject e = entries.optJSONObject(it.next());
                    Entry entry = parseEntry(e);
                    if (entry != null) book.entries.add(entry);
                }
            } else if (entryArray != null) {
                for (int i = 0; i < entryArray.length(); i++) {
                    Entry entry = parseEntry(entryArray.optJSONObject(i));
                    if (entry != null) book.entries.add(entry);
                }
            }
            if (book.entries.isEmpty()) return null;
            for (Entry e : book.entries) e.bookId = book.id;
            return book;
        } catch (Exception e) {
            return null;
        }
    }

    private static Entry parseEntry(JSONObject e) {
        if (e == null) return null;
        Entry entry = new Entry();
        entry.id = UUID.randomUUID().toString();
        entry.comment = e.optString("comment", e.optString("name", ""));
        entry.content = e.optString("content", "");
        entry.constant = e.optBoolean("constant", false);
        // SillyTavern 同款规则：只认 extensions.disable，无视顶层 enabled / disable
        JSONObject ext = e.optJSONObject("extensions");
        entry.enabled = ext == null || !ext.optBoolean("disable", false);
        Object keysObj = e.opt("keys");
        if (keysObj == null) keysObj = e.opt("key");
        if (keysObj instanceof JSONArray) {
            JSONArray keys = (JSONArray) keysObj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keys.length(); i++) {
                String k = keys.optString(i, "").trim();
                if (k.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(k);
            }
            entry.keys = sb.toString();
        } else if (keysObj instanceof String) {
            entry.keys = (String) keysObj;
        }
        Object secondaryObj = e.opt("secondary_keys");
        if (secondaryObj == null) secondaryObj = e.opt("keysecondary");
        if (secondaryObj instanceof JSONArray) {
            JSONArray keys = (JSONArray) secondaryObj;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < keys.length(); i++) {
                String k = keys.optString(i, "").trim();
                if (k.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(k);
            }
            entry.secondaryKeys = sb.toString();
        } else if (secondaryObj instanceof String) {
            entry.secondaryKeys = (String) secondaryObj;
        }
        entry.order = e.optInt("order", e.optInt("insertion_order", 100));
        return entry.content.trim().isEmpty() && entry.keys.trim().isEmpty() && !entry.constant
                ? null : entry;
    }

    private static JSONObject bookJson(Book b) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", b.id);
            o.put("name", b.name);
            o.put("enabled", b.enabled);
            JSONArray array = new JSONArray();
            for (Entry e : b.entries) {
                JSONObject eo = new JSONObject();
                eo.put("id", e.id);
                eo.put("bookId", e.bookId);
                eo.put("keys", e.keys);
                eo.put("secondaryKeys", e.secondaryKeys);
                eo.put("content", e.content);
                eo.put("constant", e.constant);
                eo.put("enabled", e.enabled);
                eo.put("comment", e.comment);
                eo.put("order", e.order);
                array.put(eo);
            }
            o.put("entries", array);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Book parseBook(JSONObject o) {
        if (o == null) return null;
        Book b = new Book();
        b.id = o.optString("id", UUID.randomUUID().toString());
        b.name = o.optString("name", "未命名");
        b.enabled = o.optBoolean("enabled", true);
        JSONArray array = o.optJSONArray("entries");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject eo = array.optJSONObject(i);
                if (eo == null) continue;
                Entry e = new Entry();
                e.id = eo.optString("id", UUID.randomUUID().toString());
                e.bookId = b.id;
                e.keys = eo.optString("keys", "");
                e.secondaryKeys = eo.optString("secondaryKeys", "");
                e.content = eo.optString("content", "");
                e.constant = eo.optBoolean("constant", false);
                e.enabled = eo.optBoolean("enabled", true);
                e.comment = eo.optString("comment", "");
                e.order = eo.optInt("order", 100);
                b.entries.add(e);
            }
        }
        return b;
    }
}