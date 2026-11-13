package app.miuix.tavern.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import app.miuix.tavern.data.LocalStore;
import app.miuix.tavern.data.PresetStore;
import app.miuix.tavern.data.SyncSettings;
import app.miuix.tavern.data.WorldBookStore;
import app.miuix.tavern.model.AppConfig;
import app.miuix.tavern.model.CharacterCard;
import app.miuix.tavern.model.GroupChat;
import app.miuix.tavern.util.CharacterCardImporter;
import app.miuix.tavern.network.RemoteSyncManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int IMPORT_CARD = 4102;
    private static final int CHANGE_PERSONA_AVATAR = 4103;
    private static final int IMPORT_WORLDBOOK = 4112;
    private static final int IMPORT_PRESET = 4113;
    private static final int[] NAV_TYPES = {
            LineIconView.CHAT, LineIconView.CHARACTER, LineIconView.BOOK, LineIconView.PERSON
    };
    private static final String[] NAV_LABELS = {"消息", "角色", "世界书", "我的"};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LineIconView[] navIcons = new LineIconView[4];
    private final TextView[] navLabels = new TextView[4];
    private final LinearLayout[] navItems = new LinearLayout[4];

    private LocalStore store;
    private FrameLayout content;
    private int activeTab;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MiuixUi.applySystemBars(
                this, MiuixUi.BACKGROUND, MiuixUi.BACKGROUND);
        store = new LocalStore(this);
        setContentView(buildShell());
        int openTab = getIntent().getIntExtra("open_tab", 0);
        showTab(openTab >= 0 && openTab <= 3 ? openTab : 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) showTab(activeTab);
        RemoteSyncManager.syncOnForeground(this, (result, error) -> {
            if (result != null && result.appliedRemote && !isFinishing()) {
                store = new LocalStore(this);
                showTab(activeTab);
                LocalizedToast.makeText(
                        this, "已从服务器自动同步", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private View buildShell() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MiuixUi.background(this));

        content = new FrameLayout(this);
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        contentParams.bottomMargin = MiuixUi.dp(this, 84);
        root.addView(content, contentParams);

        LinearLayout nav = MiuixUi.horizontal(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(MiuixUi.dp(this, 5), MiuixUi.dp(this, 5),
                MiuixUi.dp(this, 5), MiuixUi.dp(this, 5));
        nav.setBackground(MiuixUi.outlinedShape(
                Color.WHITE, MiuixUi.HAIRLINE, 25, this));
        nav.setElevation(MiuixUi.dp(this, 14));
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 68),
                Gravity.BOTTOM);
        navParams.setMargins(
                MiuixUi.dp(this, 14), 0,
                MiuixUi.dp(this, 14), MiuixUi.dp(this, 10));
        root.addView(nav, navParams);

        for (int i = 0; i < 4; i++) {
            final int index = i;
            LinearLayout item = MiuixUi.vertical(this);
            item.setGravity(Gravity.CENTER);
            item.setContentDescription(L10n.tr(this, NAV_LABELS[i]));
            MiuixUi.pressable(item, 0.93f);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            itemParams.setMargins(
                    MiuixUi.dp(this, 2), 0,
                    MiuixUi.dp(this, 2), 0);
            nav.addView(item, itemParams);
            navItems[i] = item;

            LineIconView icon = new LineIconView(this);
            icon.setType(NAV_TYPES[i]);
            item.addView(icon, new LinearLayout.LayoutParams(
                    MiuixUi.dp(this, 24), MiuixUi.dp(this, 24)));
            navIcons[i] = icon;

            TextView label = MiuixUi.text(
                    this, NAV_LABELS[i], 10.5f, MiuixUi.TEXT_SECONDARY, true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams =
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            MiuixUi.dp(this, 18));
            labelParams.topMargin = MiuixUi.dp(this, 1);
            item.addView(label, labelParams);
            navLabels[i] = label;
            item.setOnClickListener(v -> showTab(index));
        }
        return root;
    }

    private void showTab(int index) {
        int previousTab = activeTab;
        boolean animate = content.getChildCount() > 0 && previousTab != index;
        activeTab = index;
        for (int i = 0; i < navIcons.length; i++) {
            boolean selected = i == index;
            navIcons[i].setSelectedState(selected);
            navLabels[i].setTextColor(MiuixUi.color(
                    this,
                    selected ? MiuixUi.GREEN : MiuixUi.TEXT_SECONDARY));
            navItems[i].setBackground(MiuixUi.shape(
                    selected
                            ? Color.rgb(233, 249, 240)
                            : Color.TRANSPARENT,
                    20,
                    this));
        }
        content.removeAllViews();
        View page;
        if (index == 0) page = buildChatsPage();
        else if (index == 1) page = buildCharactersPage();
        else if (index == 2) page = buildLorePage();
        else page = buildProfilePage();
        content.addView(page);
        if (animate) {
            page.performHapticFeedback(
                    android.view.HapticFeedbackConstants.CLOCK_TICK);
            MiuixUi.revealDockPage(
                    page, index > previousTab ? 1 : -1);
        }
    }

    private View buildChatsPage() {
        LinearLayout page = MiuixUi.vertical(this);
        List<CharacterCard> allCharacters = store.getCharacters();
        List<CharacterCard> conversations = new ArrayList<>();
        for (CharacterCard card : allCharacters) {
            if (!store.getMessages(card.id).isEmpty()) conversations.add(card);
        }
        for (GroupChat group : store.getGroups()) {
            conversations.add(groupConversationCard(group));
        }
        Collections.sort(conversations, new Comparator<CharacterCard>() {
            @Override
            public int compare(CharacterCard left, CharacterCard right) {
                if (left.pinned != right.pinned) return left.pinned ? -1 : 1;
                return Long.compare(right.lastUsed, left.lastUsed);
            }
        });
        TextView route = MiuixUi.text(
                this, store.getConfig().readableMode(), 11.5f, MiuixUi.GREEN, true);
        route.setGravity(Gravity.CENTER);
        route.setMinHeight(MiuixUi.dp(this, 27));
        route.setPadding(MiuixUi.dp(this, 10), 0, MiuixUi.dp(this, 10), 0);
        route.setBackground(MiuixUi.shape(Color.rgb(233, 249, 240), 12, this));
        LinearLayout actions = MiuixUi.horizontal(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(route);
        View add = iconButton(LineIconView.PLUS, "新建会话", this::showNewConversationMenu);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                MiuixUi.dp(this, 44), MiuixUi.dp(this, 44));
        addParams.leftMargin = MiuixUi.dp(this, 5);
        actions.addView(add, addParams);
        page.addView(buildHeader(
                "消息",
                conversations.size() + " 个最近会话",
                actions));

        EditText search = null;
        if (!conversations.isEmpty()) {
            search = buildSearch("搜索最近会话内容");
            LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 46));
            searchParams.setMargins(
                    MiuixUi.dp(this, 14), 0,
                    MiuixUi.dp(this, 14), MiuixUi.dp(this, 10));
            page.addView(search, searchParams);
        }

        FrameLayout listCard = new FrameLayout(this);
        listCard.setBackground(MiuixUi.shape(Color.WHITE, 18, this));
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        cardParams.setMargins(MiuixUi.dp(this, 14), 0, MiuixUi.dp(this, 14), MiuixUi.dp(this, 12));
        page.addView(listCard, cardParams);

        if (conversations.isEmpty()) {
            listCard.addView(buildEmptyChats(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            RecyclerView recycler = new RecyclerView(this);
            recycler.setClipToOutline(true);
            recycler.setLayoutManager(new LinearLayoutManager(this));
            recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
            ConversationAdapter adapter = new ConversationAdapter(
                    this, store, conversations, this::openChat);
            recycler.setAdapter(adapter);
            listCard.addView(recycler, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            search.addTextChangedListener(filterWatcher(search, conversations, adapter));
        }
        return page;
    }

    private View buildCharactersPage() {
        LinearLayout page = MiuixUi.vertical(this);
        List<CharacterCard> all = store.getCharacters();
        int worldBooks = 0;
        for (CharacterCard card : all) {
            if (card.loreEntryCount() > 0) worldBooks++;
        }
        page.addView(buildHeader(
                "角色库",
                all.size() + " 位角色 · " + worldBooks + " 本世界书",
                null));

        EditText search = buildSearch("搜索角色名称或描述");
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 46));
        searchParams.setMargins(MiuixUi.dp(this, 14), 0, MiuixUi.dp(this, 14), MiuixUi.dp(this, 6));
        page.addView(search, searchParams);

        RecyclerView recycler = new RecyclerView(this);
        int widthDp = Math.round(
                getResources().getDisplayMetrics().widthPixels
                        / getResources().getDisplayMetrics().density);
        int columns = widthDp >= 840 ? 4 : (widthDp >= 600 ? 3 : 2);
        recycler.setLayoutManager(new GridLayoutManager(this, columns));
        recycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recycler.setPadding(MiuixUi.dp(this, 8), MiuixUi.dp(this, 2),
                MiuixUi.dp(this, 8), MiuixUi.dp(this, 12));
        recycler.setClipToPadding(false);
        CharacterAdapter adapter = new CharacterAdapter(this, all, this::openCharacterProfile);
        recycler.setAdapter(adapter);
        page.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        search.addTextChangedListener(filterWatcher(search, all, adapter));
        return page;
    }

    private View buildEmptyChats() {
        LinearLayout empty = MiuixUi.vertical(this);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(
                MiuixUi.dp(this, 26), MiuixUi.dp(this, 30),
                MiuixUi.dp(this, 26), MiuixUi.dp(this, 30));
        TextView title = MiuixUi.text(
                this, "还没有最近会话", 20, MiuixUi.TEXT_PRIMARY, true);
        title.setGravity(Gravity.CENTER);
        empty.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 34)));
        TextView body = MiuixUi.text(
                this,
                "点右上角“＋”添加角色或创建群聊；单聊会在第一次发送消息后出现在这里。",
                14, MiuixUi.TEXT_SECONDARY, false);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(MiuixUi.dp(this, 3), 1.05f);
        empty.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView action = MiuixUi.pillButton(this, "新建会话", true);
        action.setOnClickListener(this::showNewConversationMenu);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, MiuixUi.dp(this, 46));
        actionParams.gravity = Gravity.CENTER_HORIZONTAL;
        actionParams.topMargin = MiuixUi.dp(this, 18);
        empty.addView(action, actionParams);
        return empty;
    }

    private View buildLorePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = MiuixUi.vertical(this);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(buildHeader("世界书", "独立导入 · 扫描检索 · 手动新建", null));

        // ── 操作按钮区：导入 / 扫描 / 新建 / 预设 ──
        LinearLayout actions = MiuixUi.card(this);
        LinearLayout actionRow = MiuixUi.horizontal(this);
        actions.addView(actionRow);
        TextView importBtn = MiuixUi.pillButton(this, "导入 JSON", true);
        importBtn.setOnClickListener(v -> importWorldBookJson());
        actionRow.addView(importBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView scanBtn = MiuixUi.pillButton(this, "扫描存储", false);
        scanBtn.setOnClickListener(v -> showScannerDialog());
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        scanParams.leftMargin = MiuixUi.dp(this, 8);
        actionRow.addView(scanBtn, scanParams);
        TextView newBookBtn = MiuixUi.pillButton(this, "新建书", false);
        newBookBtn.setOnClickListener(v -> showNewBookDialog());
        LinearLayout.LayoutParams newBookParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        newBookParams.leftMargin = MiuixUi.dp(this, 8);
        actionRow.addView(newBookBtn, newBookParams);
        addWithPageMargins(page, actions, 0, 12);

        // ── 预设管理区 ──
        LinearLayout presetCard = MiuixUi.card(this);
        PresetStore presetStore = new PresetStore();
        List<PresetStore.Preset> presets = presetStore.getPresets(store);
        PresetStore.Preset active = presetStore.activePreset(store);
        String presetSummary = presets.isEmpty()
                ? "尚未导入预设（SillyTavern 预设 JSON）"
                : (active == null
                        ? presets.size() + " 个预设，均未启用"
                        : "已启用：" + active.name + "（" + active.prompts.size() + " 条）");
        presetCard.addView(MiuixUi.text(this, "预设注入", 17, MiuixUi.TEXT_PRIMARY, true));
        TextView presetBody = MiuixUi.text(this, presetSummary, 13,
                MiuixUi.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams presetBodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetBodyParams.topMargin = MiuixUi.dp(this, 5);
        presetCard.addView(presetBody, presetBodyParams);
        LinearLayout presetRow = MiuixUi.horizontal(this);
        TextView presetImport = MiuixUi.pillButton(this, "导入预设", true);
        presetImport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, IMPORT_PRESET);
        });
        presetRow.addView(presetImport, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView presetManage = MiuixUi.pillButton(this, "管理", false);
        presetManage.setOnClickListener(v -> showPresetManager());
        LinearLayout.LayoutParams presetManageParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        presetManageParams.leftMargin = MiuixUi.dp(this, 8);
        presetRow.addView(presetManage, presetManageParams);
        LinearLayout.LayoutParams presetRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetRowParams.topMargin = MiuixUi.dp(this, 10);
        presetCard.addView(presetRow, presetRowParams);
        addWithPageMargins(page, presetCard, 0, 10);

        // ── 独立世界书列表 ──
        WorldBookStore bookStore = new WorldBookStore(store);
        List<WorldBookStore.Book> books = bookStore.getBooks();
        for (WorldBookStore.Book book : books) {
            LinearLayout item = MiuixUi.card(this);
            LinearLayout row = MiuixUi.horizontal(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            item.addView(row);
            LinearLayout labels = MiuixUi.vertical(this);
            LinearLayout.LayoutParams labelsParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            row.addView(labels, labelsParams);
            labels.addView(MiuixUi.rawText(this, book.name, 17, MiuixUi.TEXT_PRIMARY, true));
            labels.addView(MiuixUi.text(this,
                    book.enabledCount() + "/" + book.entries.size() + " 条启用 · 长按管理"
                            + (book.global ? " · 全局" : ""),
                    13, MiuixUi.TEXT_SECONDARY, false));
            TextView status = MiuixUi.text(this,
                    book.enabled ? (book.global ? "全局启用" : "已启用") : "已停用", 12,
                    book.enabled ? MiuixUi.GREEN : MiuixUi.TEXT_SECONDARY, true);
            status.setGravity(Gravity.CENTER);
            status.setBackground(MiuixUi.shape(book.enabled
                    ? Color.rgb(233, 249, 240) : Color.rgb(235, 235, 238), 12, this));
            status.setPadding(MiuixUi.dp(this, 10), 0, MiuixUi.dp(this, 10), 0);
            row.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, MiuixUi.dp(this, 26)));
            item.setOnClickListener(v -> showBookDetail(book.id));
            item.setOnLongClickListener(v -> {
                showBookManager(book.id);
                return true;
            });
            addWithPageMargins(page, item, 0, 10);
        }

        // ── 角色卡内嵌世界书（原有展示保留） ──
        int embedded = 0;
        for (CharacterCard card : store.getCharacters()) {
            if (card.loreEntryCount() <= 0) continue;
            embedded++;
            LinearLayout item = MiuixUi.card(this);
            LinearLayout row = MiuixUi.horizontal(this);
            item.addView(row);
            AvatarView avatar = new AvatarView(this);
            avatar.setCharacter(card);
            row.addView(avatar, new LinearLayout.LayoutParams(MiuixUi.dp(this, 46), MiuixUi.dp(this, 46)));
            LinearLayout labels = MiuixUi.vertical(this);
            LinearLayout.LayoutParams labelsParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            labelsParams.leftMargin = MiuixUi.dp(this, 12);
            row.addView(labels, labelsParams);
            labels.addView(MiuixUi.rawText(
                    this, card.name, 17, MiuixUi.TEXT_PRIMARY, true));
            labels.addView(MiuixUi.text(this, card.loreEntryCount() + " 条内嵌设定",
                    13, MiuixUi.TEXT_SECONDARY, false));
            TextView status = MiuixUi.text(this, "已启用", 12, MiuixUi.GREEN, true);
            status.setGravity(Gravity.CENTER);
            status.setBackground(MiuixUi.shape(Color.rgb(233, 249, 240), 12, this));
            status.setPadding(MiuixUi.dp(this, 10), 0, MiuixUi.dp(this, 10), 0);
            row.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, MiuixUi.dp(this, 26)));
            final CharacterCard cardFinal = card;
            item.setOnClickListener(v -> showEmbeddedBookDetail(cardFinal));
            item.setOnLongClickListener(v -> {
                showEmbeddedBookManager(cardFinal);
                return true;
            });
            addWithPageMargins(page, item, 0, 10);
        }
        if (books.isEmpty() && embedded == 0) {
            TextView empty = MiuixUi.text(this,
                    "还没有世界书。点上方「导入 JSON / 扫描存储 / 新建书」开始",
                    15, MiuixUi.TEXT_SECONDARY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, MiuixUi.dp(this, 28), 0, MiuixUi.dp(this, 28));
            page.addView(empty);
        }
        return scroll;
    }

    /** 导入独立世界书 JSON（SillyTavern world book 格式）。 */
    private void importWorldBookJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_WORLDBOOK);
    }

    /** rg 式全盘扫描：递归扫 /sdcard 找世界书/角色卡/预设 JSON。 */
    private void showScannerDialog() {
        LinearLayout box = MiuixUi.vertical(this);
        EditText pathInput = MiuixUi.field(this, "扫描目录（默认 /sdcard/Download/st）", false);
        pathInput.setText("/sdcard/Download/st");
        box.addView(pathInput);
        TextView progress = MiuixUi.text(this, "", 13, MiuixUi.TEXT_SECONDARY, false);
        box.addView(progress);
        AlertDialog dialog = new LocalizedAlertDialogBuilder(this)
                .setTitle("扫描酒馆资产")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始扫描", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String dir = pathInput.getText().toString().trim();
            if (dir.isEmpty()) dir = "/sdcard/Download/st";
            progress.setText("扫描中…");
            final String root = dir;
            io.execute(() -> {
                List<String> worldBooks = new ArrayList<>();
                List<String> cards = new ArrayList<>();
                List<String> presetFiles = new ArrayList<>();
                scanDirectory(new java.io.File(root), worldBooks, cards, presetFiles, 0);
                runOnUiThread(() -> showScanResults(root, worldBooks, cards, presetFiles, dialog));
            });
        });
    }

    private void scanDirectory(java.io.File dir, List<String> worldBooks,
                               List<String> cards, List<String> presetFiles, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 6) return;
        java.io.File[] children = dir.listFiles();
        if (children == null) return;
        for (java.io.File child : children) {
            if (child.isDirectory()) {
                scanDirectory(child, worldBooks, cards, presetFiles, depth + 1);
            } else if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
                String path = child.getAbsolutePath();
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.contains("preset") || lower.contains("预设")) {
                    presetFiles.add(path);
                }
                worldBooks.add(path);
                cards.add(path);
            }
        }
    }

    /** 展示扫描结果：逐文件嗅探内容分类，点选导入。 */
    private void showScanResults(String root, List<String> candidates,
                                 List<String> unusedCards, List<String> presetFiles,
                                 AlertDialog scanDialog) {
        scanDialog.dismiss();
        List<String> books = new ArrayList<>();
        List<String> presets = new ArrayList<>();
        List<String> cards = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String path : candidates) {
            String kind = sniffJsonKind(path);
            if ("world".equals(kind)) books.add(path);
            else if ("preset".equals(kind)) presets.add(path);
            else if ("card".equals(kind)) cards.add(path);
            else unknown.add(path);
        }
        for (String path : presetFiles) {
            if (!presets.contains(path) && "preset".equals(sniffJsonKind(path))) presets.add(path);
        }
        LinearLayout box = MiuixUi.vertical(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        TextView summary = MiuixUi.text(this,
                "扫描 " + root + "：世界书 " + books.size() + "，预设 " + presets.size()
                        + "，角色卡 " + cards.size(),
                14, MiuixUi.TEXT_PRIMARY, true);
        box.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        java.util.Map<String, java.util.List<String>> groups = new java.util.LinkedHashMap<>();
        groups.put("世界书", books);
        groups.put("预设", presets);
        groups.put("角色卡", cards);
        for (java.util.Map.Entry<String, java.util.List<String>> group : groups.entrySet()) {
            box.addView(MiuixUi.text(this, group.getKey(), 15, MiuixUi.TEXT_PRIMARY, true));
            if (group.getValue().isEmpty()) {
                box.addView(MiuixUi.text(this, "（无）", 13, MiuixUi.TEXT_SECONDARY, false));
                continue;
            }
            for (String path : group.getValue()) {
                java.io.File f = new java.io.File(path);
                TextView row = MiuixUi.text(this,
                        "· " + f.getName() + "（" + (f.length() / 1024) + "KB）",
                        13, MiuixUi.TEXT_SECONDARY, false);
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> {
                    if ("世界书".equals(group.getKey())) importWorldBookFromPath(path);
                    else if ("预设".equals(group.getKey())) importPresetFromPath(path);
                    else importCharacterFromPath(path);
                });
                box.addView(row);
            }
        }
        new LocalizedAlertDialogBuilder(this)
                .setTitle("扫描结果")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 读 JSON 头部嗅探类型：world book / preset / character card。 */
    private String sniffJsonKind(String path) {
        try (InputStream input = new java.io.FileInputStream(path)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            int total = 0;
            while ((n = input.read(chunk)) > 0 && total < 65536) {
                buffer.write(chunk, 0, n);
                total += n;
            }
            String head = buffer.toString("UTF-8");
            // 角色卡优先判定（卡内可含 character_book，避免误判成独立世界书）
            if (head.contains("\"first_mes\"") || head.contains("\"character_book\"")
                    || head.contains("\"chara\"") || head.contains("\"spec_v2\"")
                    || head.contains("\"spec\":\"chara_card_v2\"")) {
                return "card";
            }
            if (head.contains("\"prompt_order\"") && head.contains("\"prompts\"")) {
                return "preset";
            }
            if (head.contains("\"entries\"") && (head.contains("\"keys\"")
                    || head.contains("\"key\"") || head.contains("\"content\""))) {
                return "world";
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private void importWorldBookFromPath(String path) {
        io.execute(() -> {
            try (InputStream input = new java.io.FileInputStream(path)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = input.read(chunk)) > 0) buffer.write(chunk, 0, n);
                String json = buffer.toString("UTF-8");
                String fileName = new java.io.File(path).getName();
                WorldBookStore.Book book = WorldBookStore.parseWorldBookJson(
                        json, fileName.replaceAll("(?i)\\.json$", ""));
                runOnUiThread(() -> {
                    if (book == null) {
                        LocalizedToast.makeText(this, "不是有效的世界书 JSON",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    new WorldBookStore(store).upsertBook(book);
                    LocalizedToast.makeText(this,
                            "已导入：" + book.name + "（" + book.entries.size() + " 条）",
                            Toast.LENGTH_LONG).show();
                    showTab(2);
                });
            } catch (Exception e) {
                runOnUiThread(() -> LocalizedToast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importPresetFromPath(String path) {
        io.execute(() -> {
            try (InputStream input = new java.io.FileInputStream(path)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = input.read(chunk)) > 0) buffer.write(chunk, 0, n);
                String json = buffer.toString("UTF-8");
                String fileName = new java.io.File(path).getName();
                PresetStore.Preset preset = PresetStore.parse(
                        json, fileName.replaceAll("(?i)\\.json$", ""));
                runOnUiThread(() -> {
                    if (preset == null) {
                        LocalizedToast.makeText(this, "不是有效的预设 JSON",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    preset.enabled = true;
                    new PresetStore().upsertPreset(store, preset);
                    LocalizedToast.makeText(this,
                            "已导入并启用预设：" + preset.name, Toast.LENGTH_LONG).show();
                    showTab(2);
                });
            } catch (Exception e) {
                runOnUiThread(() -> LocalizedToast.makeText(this,
                        "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void importCharacterFromPath(String path) {
        try (InputStream input = new java.io.FileInputStream(path)) {
            CharacterCardImporter.Result result = CharacterCardImporter.parse(input);
            chooseCharacterImportMode(result);
        } catch (Exception e) {
            LocalizedToast.makeText(this, "导入失败：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 新建世界书。 */
    private void showNewBookDialog() {
        EditText nameInput = MiuixUi.field(this, "世界书名称", false);
        new LocalizedAlertDialogBuilder(this)
                .setTitle("新建世界书")
                .setView(nameInput)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) return;
                    WorldBookStore.Book book = new WorldBookStore.Book();
                    book.id = java.util.UUID.randomUUID().toString();
                    book.name = name;
                    new WorldBookStore(store).upsertBook(book);
                    showTab(2);
                })
                .show();
    }

    /** 书详情 + 条目列表（点条目可编辑，底部可加新条目）。 */
    private void showBookDetail(String bookId) {
        WorldBookStore bookStore = new WorldBookStore(store);
        WorldBookStore.Book book = bookStore.getBook(bookId);
        if (book == null) return;
        LinearLayout box = MiuixUi.vertical(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        for (WorldBookStore.Entry entry : book.entries) {
            TextView row = MiuixUi.text(this,
                    (entry.enabled ? "● " : "○ ") + entry.keysDisplay()
                            + (entry.comment.isEmpty() ? "" : " ｜ " + entry.comment),
                    14, entry.enabled ? MiuixUi.TEXT_PRIMARY : MiuixUi.TEXT_SECONDARY, false);
            row.setPadding(0, MiuixUi.dp(this, 8), 0, MiuixUi.dp(this, 8));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showEntryEditor(book.id, entry.id, null));
            box.addView(row);
        }
        if (book.entries.isEmpty()) {
            box.addView(MiuixUi.text(this, "空书，返回后长按可添加条目",
                    13, MiuixUi.TEXT_SECONDARY, false));
        }
        new LocalizedAlertDialogBuilder(this)
                .setTitle(book.name + "（" + book.enabledCount() + "/" + book.entries.size() + "）")
                .setView(scroll)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 内嵌书条目摘要（点按卡片）。 */
    private void showEmbeddedBookDetail(CharacterCard card) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject book = new JSONObject(card.worldBookJson);
            JSONArray entries = book.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length() && i < 200; i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e == null) continue;
                    JSONObject ext = e.optJSONObject("extensions");
                    boolean enabled = ext == null || !ext.optBoolean("disable", false);
                    boolean constant = e.optBoolean("constant", false);
                    sb.append(enabled ? "● " : "○ ")
                      .append(constant ? "[常驻]" : "")
                      .append(e.optString("comment", "")).append('\n');
                }
                if (entries.length() > 200) sb.append("…共 ").append(entries.length()).append(" 条\n");
            }
        } catch (Exception e) {
            sb.append("解析失败");
        }
        new LocalizedAlertDialogBuilder(this)
                .setTitle(card.name + " 内嵌世界书")
                .setMessage(sb.length() == 0 ? "空书" : sb.toString())
                .setPositiveButton("完成", null)
                .show();
    }

    /** 内嵌书管理（长按卡片）：提取为独立书 / 恢复卡内书 / 跳转资料页。 */
    private void showEmbeddedBookManager(CharacterCard card) {
        boolean disabled = card.embeddedBookDisabled;
        String[] options = {disabled ? "恢复使用卡内世界书" : "提取内嵌世界书为可编辑书（推荐）",
                "去角色资料页管理"};
        new LocalizedAlertDialogBuilder(this)
                .setTitle(card.name + "（" + card.loreEntryCount() + " 条内嵌设定）")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        if (disabled) {
                            card.embeddedBookDisabled = false;
                            store.upsertCharacter(card);
                            LocalizedToast.makeText(this, "已恢复卡内世界书",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            WorldBookStore.Book book = WorldBookStore.parseWorldBookJson(
                                    card.worldBookJson, card.name + " 的世界书");
                            if (book == null || book.entries.isEmpty()) {
                                LocalizedToast.makeText(this, "内嵌世界书为空或无法解析",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            new WorldBookStore(store).upsertBook(book);
                            LocalStore.CharacterLink link = new LocalStore.CharacterLink();
                            link.characterId = card.id;
                            link.worldBookId = book.id;
                            store.upsertCharacterLink(link);
                            card.embeddedBookDisabled = true;
                            store.upsertCharacter(card);
                            LocalizedToast.makeText(this,
                                    "已提取 " + book.entries.size() + " 条到独立书，长按该书可批量转触发/设全局",
                                    Toast.LENGTH_LONG).show();
                        }
                        showTab(2);
                    } else {
                        Intent i = new Intent(this, CharacterProfileActivity.class);
                        i.putExtra(CharacterProfileActivity.EXTRA_CHARACTER_ID, card.id);
                        startActivity(i);
                    }
                })
                .show();
    }

    /** 书管理：增条目 / 绑角色 / 删书 / 清空。 */
    private void showBookManager(String bookId) {
        WorldBookStore bookStore = new WorldBookStore(store);
        WorldBookStore.Book book = bookStore.getBook(bookId);
        if (book == null) return;
        String[] options = {"添加条目", "绑定到角色卡", book.enabled ? "停用此书" : "启用此书",
                book.global ? "取消全局（仅绑定的角色生效）" : "设为全局书（所有角色生效）",
                "常驻条目全部改为按关键词触发",
                "删除此书"};
        new LocalizedAlertDialogBuilder(this)
                .setTitle(book.name + (book.global ? " ｜全局" : ""))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showEntryEditor(book.id, null, null);
                    } else if (which == 1) {
                        showBookBindDialog(book.id);
                    } else if (which == 2) {
                        book.enabled = !book.enabled;
                        bookStore.upsertBook(book);
                        showTab(2);
                    } else if (which == 3) {
                        book.global = !book.global;
                        bookStore.upsertBook(book);
                        showTab(2);
                    } else if (which == 4) {
                        int changed = 0;
                        for (WorldBookStore.Entry entry : book.entries) {
                            if (!entry.constant) continue;
                            entry.constant = false;
                            // 常驻条目通常没填关键词，用备注名兜底当触发词
                            if (entry.keys == null || entry.keys.trim().isEmpty()) {
                                String fallback = entry.comment == null ? "" : entry.comment.trim();
                                entry.keys = fallback.isEmpty() ? entry.id.substring(0, 6) : fallback;
                            }
                            changed++;
                        }
                        bookStore.upsertBook(book);
                        LocalizedToast.makeText(this,
                                "已把 " + changed + " 条常驻改为关键词触发（无关键词的用备注名兜底）",
                                Toast.LENGTH_LONG).show();
                        showTab(2);
                    } else if (which == 5) {
                        bookStore.deleteBook(book.id);
                        showTab(2);
                    }
                })
                .show();
    }

    /** 把书绑定到角色卡（多选）。 */
    private void showBookBindDialog(String bookId) {
        WorldBookStore bookStore = new WorldBookStore(store);
        WorldBookStore.Book book = bookStore.getBook(bookId);
        if (book == null) return;
        List<CharacterCard> cards = store.getCharacters();
        if (cards.isEmpty()) {
            LocalizedToast.makeText(this, "还没有角色卡", Toast.LENGTH_SHORT).show();
            return;
        }
        List<LocalStore.CharacterLink> links = store.getCharacterLinks();
        List<String> boundIds = new ArrayList<>();
        for (LocalStore.CharacterLink link : links) {
            if (bookId.equals(link.worldBookId)) boundIds.add(link.characterId);
        }
        CharSequence[] names = new CharSequence[cards.size()];
        boolean[] checked = new boolean[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            names[i] = cards.get(i).name;
            checked[i] = boundIds.contains(cards.get(i).id);
        }
        new LocalizedAlertDialogBuilder(this)
                .setTitle("把「" + book.name + "」绑定到角色")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> { })
                .setPositiveButton("保存", (dialog, which) -> {
                    for (int i = 0; i < cards.size(); i++) {
                        String cardId = cards.get(i).id;
                        boolean bound = boundIds.contains(cardId);
                        if (checked[i] && !bound) {
                            LocalStore.CharacterLink link = new LocalStore.CharacterLink();
                            link.characterId = cardId;
                            link.worldBookId = bookId;
                            store.upsertCharacterLink(link);
                        }
                        if (!checked[i] && bound) store.removeCharacterLink(cardId, bookId);
                    }
                    LocalizedToast.makeText(this, "绑定已保存", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** 条目编辑器：新建（entryId=null）或编辑现有条目。 */
    private void showEntryEditor(String bookId, @Nullable String entryId, @Nullable Void unused) {
        WorldBookStore bookStore = new WorldBookStore(store);
        WorldBookStore.Book book = bookStore.getBook(bookId);
        if (book == null) return;
        WorldBookStore.Entry entry = null;
        if (entryId != null) {
            for (WorldBookStore.Entry e : book.entries) {
                if (e.id.equals(entryId)) {
                    entry = e;
                    break;
                }
            }
        }
        WorldBookStore.Entry target = entry != null ? entry : new WorldBookStore.Entry();
        final WorldBookStore.Entry entryFinal = entry;
        final WorldBookStore.Book bookFinal = book;
        LinearLayout box = MiuixUi.vertical(this);
        EditText keysInput = MiuixUi.field(this, "关键词（逗号分隔）", false);
        keysInput.setText(target.keys);
        EditText contentInput = MiuixUi.field(this, "条目内容", false);
        contentInput.setText(target.content);
        contentInput.setMinLines(3);
        EditText commentInput = MiuixUi.field(this, "备注（可选）", false);
        commentInput.setText(target.comment);
        SwitchCompat enableSwitch = new SwitchCompat(this);
        enableSwitch.setChecked(target.enabled);
        enableSwitch.setText("启用此条目");
        enableSwitch.setShowText(false);
        SwitchCompat constantSwitch = new SwitchCompat(this);
        constantSwitch.setChecked(target.constant);
        constantSwitch.setText("常驻条目（无条件注入，开=常驻 / 关=关键词触发）");
        constantSwitch.setShowText(false);
        box.addView(keysInput);
        box.addView(contentInput);
        box.addView(commentInput);
        box.addView(enableSwitch);
        box.addView(constantSwitch);
        AlertDialog dialog = new LocalizedAlertDialogBuilder(this)
                .setTitle(entry == null ? "添加条目" : "编辑条目")
                .setView(box)
                .setNeutralButton("删除", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String keys = keysInput.getText().toString().trim();
            String content = contentInput.getText().toString().trim();
            if (keys.isEmpty() && content.isEmpty()) return;
            if (entryFinal == null) {
                WorldBookStore.Entry created = new WorldBookStore.Entry();
                created.id = java.util.UUID.randomUUID().toString();
                created.bookId = bookId;
                created.keys = keys;
                created.content = content;
                created.comment = commentInput.getText().toString().trim();
                created.constant = constantSwitch.isChecked();
                created.enabled = enableSwitch.isChecked();
                bookFinal.entries.add(created);
            } else {
                entryFinal.keys = keys;
                entryFinal.content = content;
                entryFinal.comment = commentInput.getText().toString().trim();
                entryFinal.constant = constantSwitch.isChecked();
                entryFinal.enabled = enableSwitch.isChecked();
            }
            bookStore.upsertBook(bookFinal);
            dialog.dismiss();
            showTab(2);
        });
        View deleteBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (entryFinal == null) {
            deleteBtn.setVisibility(View.GONE);
        } else {
            deleteBtn.setOnClickListener(v2 -> {
                bookFinal.entries.remove(entryFinal);
                bookStore.upsertBook(bookFinal);
                dialog.dismiss();
                showTab(2);
            });
        }
    }

    /** 预设管理：切换启用 / 删除。 */
    private void showPresetManager() {
        PresetStore presetStore = new PresetStore();
        List<PresetStore.Preset> presets = presetStore.getPresets(store);
        if (presets.isEmpty()) {
            LocalizedToast.makeText(this, "还没有预设", Toast.LENGTH_SHORT).show();
            return;
        }
        PresetStore.Preset active = presetStore.activePreset(store);
        String[] names = new String[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            PresetStore.Preset p = presets.get(i);
            int enabledCount = 0;
            for (PresetStore.PresetPrompt pp : p.prompts) {
                if (pp.enabled) enabledCount++;
            }
            names[i] = (p == active ? "✓ " : "") + p.name
                    + "（" + enabledCount + "/" + p.prompts.size() + " 条启用）"
                    + (p.enabled ? "" : " [整体停用]");
        }
        new LocalizedAlertDialogBuilder(this)
                .setTitle("预设管理")
                .setItems(names, (dialog, which) -> showPresetDetail(presetStore, presets.get(which)))
                .show();
    }

    private void showPresetDetail(PresetStore presetStore, PresetStore.Preset preset) {
        String boundName = "";
        if (preset.characterId != null && !preset.characterId.isEmpty()) {
            for (CharacterCard c : store.getCharacters()) {
                if (c.id.equals(preset.characterId)) { boundName = " ｜绑定:" + c.name; break; }
            }
        }
        String[] options = {
                preset.enabled ? "整体停用" : "设为启用（生效）",
                "prompt 逐条开关",
                "绑定到角色卡（当前：" + (boundName.isEmpty() ? "全局" : boundName) + "）",
                "删除此预设"
        };
        new LocalizedAlertDialogBuilder(this)
                .setTitle(preset.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        preset.enabled = !preset.enabled;
                        presetStore.upsertPreset(store, preset);
                        showTab(2);
                    } else if (which == 1) {
                        showPresetPromptToggles(presetStore, preset);
                    } else if (which == 2) {
                        showPresetBindDialog(presetStore, preset);
                    } else if (which == 3) {
                        presetStore.deletePreset(store, preset.id);
                        showTab(2);
                    }
                })
                .show();
    }
    /** 预设内 prompt 逐条启用/停用（多选对话框，保存后立即生效）。 */
    private void showPresetPromptToggles(PresetStore presetStore, PresetStore.Preset preset) {
        if (preset.prompts.isEmpty()) {
            LocalizedToast.makeText(this, "此预设没有 prompt", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] names = new CharSequence[preset.prompts.size()];
        boolean[] checked = new boolean[preset.prompts.size()];
        for (int i = 0; i < preset.prompts.size(); i++) {
            PresetStore.PresetPrompt pp = preset.prompts.get(i);
            names[i] = (pp.name == null || pp.name.isEmpty() ? pp.identifier : pp.name)
                    + " [" + pp.role + "]";
            checked[i] = pp.enabled;
        }
        new LocalizedAlertDialogBuilder(this)
                .setTitle("prompt 开关（勾选=注入）")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> { })
                .setPositiveButton("保存", (dialog, which) -> {
                    for (int i = 0; i < preset.prompts.size(); i++) {
                        preset.prompts.get(i).enabled = checked[i];
                    }
                    presetStore.upsertPreset(store, preset);
                    LocalizedToast.makeText(this, "prompt 开关已保存", Toast.LENGTH_SHORT).show();
                    showTab(2);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** 把预设绑定到单个角色卡（选「全局」则所有角色生效）。 */
    private void showPresetBindDialog(PresetStore presetStore, PresetStore.Preset preset) {
        List<CharacterCard> cards = store.getCharacters();
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add("");
        names.add("全局（所有角色生效）");
        for (CharacterCard c : cards) {
            ids.add(c.id);
            names.add(c.name);
        }
        int selected = ids.indexOf(preset.characterId == null ? "" : preset.characterId);
        final int[] picked = {Math.max(0, selected)};
        new LocalizedAlertDialogBuilder(this)
                .setTitle("预设生效范围")
                .setSingleChoiceItems(names.toArray(new CharSequence[0]), picked[0],
                        (dialog, which) -> picked[0] = which)
                .setPositiveButton("保存", (dialog, which) -> {
                    preset.characterId = ids.get(picked[0]);
                    presetStore.upsertPreset(store, preset);
                    LocalizedToast.makeText(this, "预设绑定已保存", Toast.LENGTH_SHORT).show();
                    showTab(2);
                })
                .setNegativeButton("取消", null)
                .show();
    }


    private View buildProfilePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = MiuixUi.vertical(this);
        scroll.addView(page);
        page.addView(buildHeader("我的", "本地优先 · 可选 GPT 登录", null));

        LinearLayout profile = MiuixUi.card(this);
        LinearLayout row = MiuixUi.horizontal(this);
        profile.addView(row);
        String personaDisplay = "你".equals(store.getConfig().persona)
                ? L10n.tr(this, "你") : store.getConfig().persona;
        CharacterCard persona = new CharacterCard();
        persona.id = "persona-local";
        persona.name = personaDisplay;
        persona.avatarPath = store.getConfig().personaAvatarPath;
        AvatarView avatar = new AvatarView(this);
        avatar.setCharacter(persona);
        avatar.setContentDescription(L10n.tr(this, "更换我的头像"));
        avatar.setOnClickListener(v -> changePersonaAvatar());
        MiuixUi.pressable(avatar, 0.94f);
        row.addView(avatar, new LinearLayout.LayoutParams(MiuixUi.dp(this, 64), MiuixUi.dp(this, 64)));
        LinearLayout names = MiuixUi.vertical(this);
        LinearLayout.LayoutParams namesParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        namesParams.leftMargin = MiuixUi.dp(this, 14);
        row.addView(names, namesParams);
        names.addView(MiuixUi.rawText(
                this, personaDisplay, 21, MiuixUi.TEXT_PRIMARY, true));
        TextView mode = MiuixUi.text(this,
                store.getConfig().readableMode() + " · 点击头像可更换",
                14, MiuixUi.TEXT_SECONDARY, false);
        names.addView(mode);
        addWithPageMargins(page, profile, 0, 14);

        LinearLayout settings = MiuixUi.card(this);
        settings.setPadding(0, 0, 0, 0);
        settings.addView(settingsRow("连接与账户", "直连 API、GPT / GitHub Copilot 账户、人格", v ->
                startActivity(new Intent(this, SettingsActivity.class))));
        settings.addView(MiuixUi.divider(this, 18));
        settings.addView(settingsRow("备份与还原", "角色、聊天、图片与普通设置", v ->
                startActivity(new Intent(this, BackupRestoreActivity.class))));
        settings.addView(MiuixUi.divider(this, 18));
        SyncSettings syncSettings = new SyncSettings(this);
        String syncDetail = !syncSettings.configured() ? "尚未配置"
                : syncSettings.autoSync() ? "自动同步已开启" : "已配置 · 手动同步";
        settings.addView(settingsRow("服务器同步", syncDetail, v ->
                startActivity(new Intent(this, SyncSettingsActivity.class))));
        settings.addView(MiuixUi.divider(this, 18));
        settings.addView(settingsRow("隐私与存储", "密钥由 Android Keystore 加密", null));
        settings.addView(MiuixUi.divider(this, 18));
        settings.addView(settingsRow("关于澄语", "0.9.0 · 原生角色聊天客户端", null));
        addWithPageMargins(page, settings, 0, 14);

        LinearLayout note = MiuixUi.card(this);
        TextView noteTitle = MiuixUi.text(this, "设计说明", 17, MiuixUi.TEXT_PRIMARY, true);
        note.addView(noteTitle);
        TextView noteBody = MiuixUi.text(this,
                "微信式信息架构负责熟悉感；MIUIX 的大标题、分组卡片与圆角负责层级；按下即反馈和可中断弹簧负责触感。所有动效都会尊重系统“移除动画”设置。",
                14, MiuixUi.TEXT_SECONDARY, false);
        noteBody.setLineSpacing(MiuixUi.dp(this, 3), 1.05f);
        LinearLayout.LayoutParams noteParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = MiuixUi.dp(this, 8);
        note.addView(noteBody, noteParams);
        addWithPageMargins(page, note, 0, 20);
        return scroll;
    }

    private View buildHeader(String titleText, String subtitleText, View action) {
        LinearLayout header = MiuixUi.horizontal(this);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(MiuixUi.dp(this, 18), MiuixUi.dp(this, 20),
                MiuixUi.dp(this, 14), MiuixUi.dp(this, 14));

        LinearLayout titles = MiuixUi.vertical(this);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView title = MiuixUi.text(this, titleText, 31, MiuixUi.TEXT_PRIMARY, true);
        title.setLetterSpacing(-0.025f);
        titles.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 42)));
        TextView subtitle = MiuixUi.text(this, subtitleText, 13, MiuixUi.TEXT_SECONDARY, false);
        titles.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 22)));
        if (action != null) header.addView(action);
        return header;
    }

    private View iconButton(int type, String description, View.OnClickListener listener) {
        FrameLayout holder = new FrameLayout(this);
        holder.setContentDescription(L10n.tr(this, description));
        MiuixUi.ripple(holder, Color.WHITE, 14);
        MiuixUi.pressable(holder, 0.92f);
        LineIconView icon = new LineIconView(this);
        icon.setType(type);
        icon.setSelectedState(false);
        FrameLayout.LayoutParams iconParams =
                new FrameLayout.LayoutParams(MiuixUi.dp(this, 24), MiuixUi.dp(this, 24), Gravity.CENTER);
        holder.addView(icon, iconParams);
        holder.setOnClickListener(listener);
        holder.setLayoutParams(new LinearLayout.LayoutParams(MiuixUi.dp(this, 44), MiuixUi.dp(this, 44)));
        return holder;
    }

    private EditText buildSearch(String hint) {
        EditText search = MiuixUi.field(this, "⌕  " + hint, false);
        search.setTextSize(15);
        search.setBackground(MiuixUi.shape(Color.rgb(235, 235, 238), 14, this));
        return search;
    }

    private TextWatcher filterWatcher(EditText search, List<CharacterCard> all, Object adapter) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                List<CharacterCard> filtered = new ArrayList<>();
                for (CharacterCard card : all) {
                    String lastMessage = "";
                    if (adapter instanceof ConversationAdapter) {
                        List<app.miuix.tavern.model.ChatMessage> messages =
                                store.getMessages(card.id);
                        if (!messages.isEmpty()) {
                            String content = messages.get(messages.size() - 1).content;
                            lastMessage = content == null
                                    ? ""
                                    : content.toLowerCase(Locale.ROOT);
                        }
                    }
                    if (query.isEmpty()
                            || card.name.toLowerCase(Locale.ROOT).contains(query)
                            || (card.description != null
                            && card.description.toLowerCase(Locale.ROOT).contains(query))
                            || lastMessage.contains(query)) {
                        filtered.add(card);
                    }
                }
                if (adapter instanceof ConversationAdapter) {
                    ((ConversationAdapter) adapter).replace(filtered);
                } else {
                    ((CharacterAdapter) adapter).replace(filtered);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
    }

    private View settingsRow(String titleValue, String detailValue, @Nullable View.OnClickListener click) {
        LinearLayout row = MiuixUi.horizontal(this);
        row.setPadding(MiuixUi.dp(this, 18), MiuixUi.dp(this, 13),
                MiuixUi.dp(this, 16), MiuixUi.dp(this, 13));
        if (click != null) {
            MiuixUi.ripple(row, Color.WHITE, 18);
            MiuixUi.pressable(row, 0.985f);
            row.setOnClickListener(click);
        }
        LinearLayout labels = MiuixUi.vertical(this);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        labels.addView(MiuixUi.text(this, titleValue, 16, MiuixUi.TEXT_PRIMARY, true));
        labels.addView(MiuixUi.text(this, detailValue, 13, MiuixUi.TEXT_SECONDARY, false));
        if (click == null) {
            FrameLayout statusHolder = new FrameLayout(this);
            TextView status = MiuixUi.text(
                    this, "✓", 14, Color.WHITE, true);
            status.setGravity(Gravity.CENTER);
            status.setBackground(MiuixUi.shape(MiuixUi.GREEN, 13, this));
            status.setContentDescription(L10n.tr(this, "安全"));
            statusHolder.addView(status, new FrameLayout.LayoutParams(
                    MiuixUi.dp(this, 26), MiuixUi.dp(this, 26), Gravity.CENTER));
            row.addView(statusHolder, new LinearLayout.LayoutParams(
                    MiuixUi.dp(this, 42), MiuixUi.dp(this, 48)));
        } else {
            TextView chevron = MiuixUi.text(
                    this, "›", 25, Color.rgb(180, 180, 185), false);
            chevron.setGravity(Gravity.CENTER);
            row.addView(chevron, new LinearLayout.LayoutParams(
                    MiuixUi.dp(this, 42), MiuixUi.dp(this, 48)));
        }
        return row;
    }

    private void addWithPageMargins(LinearLayout page, View view, int top, int bottom) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(MiuixUi.dp(this, 14), MiuixUi.dp(this, top),
                MiuixUi.dp(this, 14), MiuixUi.dp(this, bottom));
        page.addView(view, params);
    }

    private void openChat(CharacterCard card) {
        if (card.id.startsWith(GroupChat.CONVERSATION_PREFIX)) {
            String groupId = card.id.substring(GroupChat.CONVERSATION_PREFIX.length());
            store.touchGroup(groupId);
            Intent intent = new Intent(this, GroupChatActivity.class);
            intent.putExtra(GroupChatActivity.EXTRA_GROUP_ID, groupId);
            MiuixUi.startConversationActivity(this, intent);
            return;
        }
        store.touchCharacter(card.id);
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CHARACTER_ID, card.id);
        MiuixUi.startConversationActivity(this, intent);
    }

    private CharacterCard groupConversationCard(GroupChat group) {
        CharacterCard card = new CharacterCard();
        card.id = group.conversationId();
        card.name = group.name;
        card.description = L10n.tr(
                this, "本地群聊 · " + group.members.size() + " 人");
        card.firstMessage = L10n.tr(
                this, "群聊已创建，发一条消息开始聊天");
        card.lastUsed = group.lastUsed;
        card.unread = group.unread;
        card.muted = group.muted;
        card.pinned = group.pinned;
        card.chatBackgroundPath = group.chatBackgroundPath;
        if (!group.members.isEmpty()) {
            CharacterCard first = store.getCharacter(group.members.get(0));
            if (first == null) {
                first = store.getCharacterBySourceAvatar(group.members.get(0));
            }
            if (first != null) card.avatarPath = first.avatarPath;
        }
        return card;
    }

    private void showNewConversationMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, L10n.tr(this, "创建群聊"));
        menu.getMenu().add(0, 2, 1, L10n.tr(this, "添加角色"));
        menu.getMenu().add(0, 3, 2, L10n.tr(this, "手动新建角色"));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                startActivity(new Intent(this, CreateGroupActivity.class));
            } else if (item.getItemId() == 2) {
                importCharacter();
            } else if (item.getItemId() == 3) {
                showManualCharacterCreator();
            }
            return true;
        });
        menu.show();
    }

    /** 手动新建角色：填基本信息即建卡，保存后可直接开聊。 */
    private void showManualCharacterCreator() {
        LinearLayout box = MiuixUi.vertical(this);
        final AlertDialog[] holder = new AlertDialog[1];
        EditText nameInput = MiuixUi.field(this, "角色名（必填）", false);
        EditText descInput = MiuixUi.field(this, "角色描述", false);
        descInput.setMinLines(3);
        EditText personaInput = MiuixUi.field(this, "性格", false);
        EditText scenarioInput = MiuixUi.field(this, "场景/情境", false);
        EditText greetingInput = MiuixUi.field(this, "开场白（可选）", false);
        greetingInput.setMinLines(2);
        box.addView(nameInput);
        box.addView(descInput);
        box.addView(personaInput);
        box.addView(scenarioInput);
        box.addView(greetingInput);
        AlertDialog dialog = new LocalizedAlertDialogBuilder(this)
                .setTitle("手动新建角色")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建并聊天", null)
                .show();
        holder[0] = dialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                LocalizedToast.makeText(this, "角色名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            for (CharacterCard card : store.getCharacters()) {
                if (name.equalsIgnoreCase(card.name == null ? "" : card.name.trim())) {
                    LocalizedToast.makeText(this, "已有同名角色，请换个名字", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            CharacterCard card = new CharacterCard();
            card.name = name;
            card.description = descInput.getText().toString().trim();
            card.personality = personaInput.getText().toString().trim();
            card.scenario = scenarioInput.getText().toString().trim();
            card.firstMessage = greetingInput.getText().toString().trim();
            store.upsertCharacter(card);
            dialog.dismiss();
            LocalizedToast.makeText(this, "已创建：" + name, Toast.LENGTH_SHORT).show();
            store.touchCharacter(card.id);
            Intent chatIntent = new Intent(this, ChatActivity.class);
            chatIntent.putExtra(ChatActivity.EXTRA_CHARACTER_ID, card.id);
            MiuixUi.startConversationActivity(this, chatIntent);
        });
    }

    private void openCharacterProfile(CharacterCard card) {
        Intent intent = new Intent(this, CharacterProfileActivity.class);
        intent.putExtra(CharacterProfileActivity.EXTRA_CHARACTER_ID, card.id);
        startActivity(intent);
    }

    private void importCharacter() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // 不按 MIME 过滤：部分文件管理器把 PNG 报成 octet-stream 会被误滤掉，
        // 允许选任意文件，导入时按内容（PNG 魔数 / JSON）判断。
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_CARD);
    }

    private void changePersonaAvatar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, CHANGE_PERSONA_AVATAR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_WORLDBOOK || requestCode == IMPORT_PRESET) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            if (requestCode == IMPORT_WORLDBOOK) {
                io.execute(() -> {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int n;
                        while (input != null && (n = input.read(chunk)) > 0) {
                            buffer.write(chunk, 0, n);
                        }
                        byte[] bytes = buffer.toByteArray();
                        // PNG 误选到这里：自动转角色卡导入
                        if (bytes.length >= 8 && bytes[0] == (byte) 0x89
                                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                            try {
                                CharacterCardImporter.Result result =
                                        CharacterCardImporter.parse(
                                                new java.io.ByteArrayInputStream(bytes));
                                runOnUiThread(() -> chooseCharacterImportMode(result));
                            } catch (Exception pe) {
                                runOnUiThread(() -> LocalizedToast.makeText(this,
                                        "PNG 角色卡导入失败：" + pe.getMessage(),
                                        Toast.LENGTH_LONG).show());
                            }
                            return;
                        }
                        String json = buffer.toString("UTF-8");
                        WorldBookStore.Book book = WorldBookStore.parseWorldBookJson(
                                json, "导入世界书");
                        runOnUiThread(() -> {
                            if (book == null) {
                                LocalizedToast.makeText(this, "不是有效的世界书 JSON",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            new WorldBookStore(store).upsertBook(book);
                            LocalizedToast.makeText(this,
                                    "已导入：" + book.name + "（" + book.entries.size() + " 条）",
                                    Toast.LENGTH_LONG).show();
                            showTab(2);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> LocalizedToast.makeText(this,
                                "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
            } else {
                io.execute(() -> {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int n;
                        while (input != null && (n = input.read(chunk)) > 0) {
                            buffer.write(chunk, 0, n);
                        }
                        String json = buffer.toString("UTF-8");
                        PresetStore.Preset preset = PresetStore.parse(json, "导入预设");
                        runOnUiThread(() -> {
                            if (preset == null) {
                                LocalizedToast.makeText(this, "不是有效的预设 JSON",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            preset.enabled = true;
                            new PresetStore().upsertPreset(store, preset);
                            LocalizedToast.makeText(this,
                                    "已导入并启用预设：" + preset.name, Toast.LENGTH_LONG).show();
                            showTab(2);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> LocalizedToast.makeText(this,
                                "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
            }
            return;
        }
        if ((requestCode != IMPORT_CARD && requestCode != CHANGE_PERSONA_AVATAR)
                || resultCode != RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == CHANGE_PERSONA_AVATAR) {
            savePersonaAvatar(uri);
            return;
        }
        LocalizedToast.makeText(this, "正在读取角色卡…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("无法打开所选文件");
                CharacterCardImporter.Result result = CharacterCardImporter.parse(input);
                runOnUiThread(() -> chooseCharacterImportMode(result));
            } catch (Exception error) {
                runOnUiThread(() -> LocalizedToast.makeText(this,
                        L10n.tr(this, "导入失败：")
                                + L10n.tr(this, error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void chooseCharacterImportMode(CharacterCardImporter.Result result) {
        CharacterCard existing = null;
        String incomingName = result.card.name == null
                ? "" : result.card.name.trim();
        for (CharacterCard card : store.getCharacters()) {
            if (incomingName.equalsIgnoreCase(
                    card.name == null ? "" : card.name.trim())) {
                existing = card;
                break;
            }
        }
        if (existing == null) {
            finishCharacterImport(result, null);
            return;
        }
        CharacterCard matched = existing;
        new LocalizedAlertDialogBuilder(this)
                .setTitle("检测到同名角色")
                .setMessage("本机已有“" + matched.name + "”。覆盖会保留原聊天记录和会话设置；新增角色必须使用不同名称。")
                .setPositiveButton("覆盖原角色",
                        (dialog, which) -> finishCharacterImport(result, matched))
                .setNeutralButton("新增角色",
                        (dialog, which) -> showNewCharacterNameDialog(result))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNewCharacterNameDialog(CharacterCardImporter.Result result) {
        EditText name = MiuixUi.field(this, "输入新的角色名称", false);
        name.setText(suggestUniqueCharacterName(result.card.name));
        name.setSelectAllOnFocus(true);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(MiuixUi.dp(this, 22), MiuixUi.dp(this, 8),
                MiuixUi.dp(this, 22), 0);
        wrapper.addView(name, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, MiuixUi.dp(this, 50)));
        androidx.appcompat.app.AlertDialog dialog =
                new LocalizedAlertDialogBuilder(this)
                        .setTitle("为新角色修改名称")
                        .setMessage("角色名称不能与本机已有角色重复。")
                        .setView(wrapper)
                        .setPositiveButton("新增", null)
                        .setNegativeButton("取消", null)
                        .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty()) {
                        name.setError("请输入角色名称");
                        return;
                    }
                    if (isCharacterNameTaken(value)) {
                        name.setError("该名称已存在，请换一个名称");
                        return;
                    }
                    result.card.name = value;
                    dialog.dismiss();
                    finishCharacterImport(result, null);
                }));
        dialog.show();
    }

    private boolean isCharacterNameTaken(String name) {
        String candidate = name == null ? "" : name.trim();
        for (CharacterCard card : store.getCharacters()) {
            String saved = card.name == null ? "" : card.name.trim();
            if (candidate.equalsIgnoreCase(saved)) return true;
        }
        return false;
    }

    private String suggestUniqueCharacterName(String original) {
        String base = TextUtils.isEmpty(original) ? "新角色" : original.trim();
        int suffix = 2;
        String candidate = base + "（" + suffix + "）";
        while (isCharacterNameTaken(candidate)) {
            suffix++;
            candidate = base + "（" + suffix + "）";
        }
        return candidate;
    }

    private void finishCharacterImport(
            CharacterCardImporter.Result result,
            @Nullable CharacterCard existing) {
        LocalizedToast.makeText(this, existing == null
                ? "正在导入角色…" : "正在覆盖角色…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                CharacterCard card = result.card;
                if (existing != null) {
                    card.id = existing.id;
                    card.lastUsed = existing.lastUsed;
                    card.unread = existing.unread;
                    card.muted = existing.muted;
                    card.pinned = existing.pinned;
                    card.chatBackgroundPath = existing.chatBackgroundPath;
                    if (!TextUtils.isEmpty(existing.sourceAvatar)) {
                        card.sourceAvatar = existing.sourceAvatar;
                    }
                    if (!result.png) card.avatarPath = existing.avatarPath;
                } else if (isCharacterNameTaken(card.name)) {
                    throw new IllegalStateException("角色名称已存在，请重新命名");
                }
                if (result.png) {
                    File directory = new File(getFilesDir(), "avatars");
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IllegalStateException("无法创建头像目录");
                    }
                    File avatar = new File(directory,
                            Integer.toHexString(card.id.hashCode()) + ".png");
                    try (FileOutputStream output = new FileOutputStream(avatar, false)) {
                        output.write(result.originalBytes);
                    }
                    card.avatarPath = avatar.getAbsolutePath();
                }
                store.upsertCharacter(card);
                runOnUiThread(() -> {
                    LocalizedToast.makeText(this,
                            existing == null
                                    ? "已导入 " + card.name
                                    : "已覆盖 " + card.name,
                            Toast.LENGTH_SHORT).show();
                    showTab(1);
                });
            } catch (Exception error) {
                runOnUiThread(() -> LocalizedToast.makeText(this,
                        L10n.tr(this, "导入失败：")
                                + L10n.tr(this, error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void savePersonaAvatar(Uri uri) {
        LocalizedToast.makeText(this, "正在更换头像…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalStateException("无法打开所选图片");
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 15 * 1024 * 1024) {
                        throw new IllegalStateException("图片不能超过 15 MB");
                    }
                    bytes.write(buffer, 0, count);
                }
                byte[] image = bytes.toByteArray();
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw new IllegalStateException("所选文件不是有效图片");
                }
                File directory = new File(getFilesDir(), "persona");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("无法创建头像目录");
                }
                File target = new File(directory, "avatar-" + System.currentTimeMillis());
                try (FileOutputStream output = new FileOutputStream(target)) {
                    output.write(image);
                }
                AppConfig config = store.getConfig();
                config.personaAvatarPath = target.getAbsolutePath();
                store.saveConfig(config);
                runOnUiThread(() -> {
                    LocalizedToast.makeText(this, "头像已更换", Toast.LENGTH_SHORT).show();
                    showTab(3);
                });
            } catch (Exception error) {
                runOnUiThread(() -> LocalizedToast.makeText(this,
                        "更换失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}
