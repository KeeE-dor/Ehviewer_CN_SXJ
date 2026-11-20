package com.hippo.ehviewer.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.EhUploadDetail;
import com.hippo.ehviewer.client.data.EhUploadDetail.PageLink;
import com.hippo.ehviewer.client.data.EhUploadDetail.SelectOption;
import com.hippo.ehviewer.client.data.EhUploadDetail.SortOption;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadFolderGroup;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadGalleryItem;
import com.hippo.ehviewer.client.parser.EhUploadParser;
import com.hippo.util.ExceptionUtils;
import com.hippo.widget.ProgressView;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生「上传管理」(https://upld.e-hentai.org/manage) 控制器：
 * 全原生 Material 卡片列表呈现每个已上传画廊的标题、分类、页数、时间、目录与批量操作，
 * 并在每张卡片下方提供完全适应手机宽度的 4 个按钮（查看 / 统计 / 编辑 / 放弃），彻底解决桌面表格右偏点不到的问题。
 */
public class NativeUploadsManageController {

    private static final String TAG = "NativeUploadsManage";

    public interface Callback {
        void onViewGallery(@NonNull String viewUrl);

        void onViewStats(@NonNull String statsUrl);

        void onManageGallery(@NonNull String manageUrl);

        void onDisownGallery(@NonNull UploadGalleryItem item);

        void onFallbackToWeb(@NonNull String url);

        void onSelectionModeChanged(boolean hasSelection);
    }

    private final Activity activity;
    private final LinearLayout rootContainer;
    @Nullable
    private final ProgressView progressView;
    private final Callback callback;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestSeq = new AtomicInteger(0);

    private EditText searchInput;
    private MaterialButton toggleAllBtn;
    private MaterialButton webSwitchBtn;
    private HorizontalScrollView chipScroll;
    private LinearLayout chipBar;
    private RecyclerView recyclerView;
    private LinearLayout emptyStateBox;
    private TextView emptyStateMsg;
    private LinearLayout batchBar;
    private TextView batchCountTv;

    private final UploadAdapter adapter = new UploadAdapter();
    private final Set<String> selectedValues = new LinkedHashSet<>();

    @NonNull
    private String currentUrl = EhUrl.URL_MY_UPLOADS_E;
    @Nullable
    private EhUploadDetail currentDetail;
    @NonNull
    private String searchQuery = "";
    @Nullable
    private String selectedFolderFilter = null;
    private boolean allExpanded = true;
    private boolean autoSortedNewestOnServer = false;
    private boolean destroyed = false;

    public NativeUploadsManageController(@NonNull Activity activity,
                                         @NonNull LinearLayout rootContainer,
                                         @Nullable ProgressView progressView,
                                         @NonNull Callback callback) {
        this.activity = activity;
        this.rootContainer = rootContainer;
        this.progressView = progressView;
        this.callback = callback;
        buildLayout();
    }

    private void buildLayout() {
        rootContainer.removeAllViews();
        rootContainer.setOrientation(LinearLayout.VERTICAL);

        // 顶部筛选 + 工具行
        LinearLayout topTools = new LinearLayout(activity);
        topTools.setOrientation(LinearLayout.VERTICAL);
        topTools.setPadding(dp(10), dp(8), dp(10), dp(4));

        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        searchInput = new EditText(activity);
        searchInput.setHint(R.string.my_uploads_search_hint);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        searchInput.setTextColor(getPrimaryTextColor());
        searchInput.setHintTextColor(getSecondaryTextColor());
        searchInput.setPadding(dp(10), dp(7), dp(10), dp(7));
        searchInput.setBackground(createRoundedBg(getCardBg(), getDividerColor(), dp(8), dp(1)));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchInput.setLayoutParams(searchLp);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                rebuildAdapterItems();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        searchRow.addView(searchInput);

        toggleAllBtn = createCompactOutlinedBtn(activity.getString(R.string.my_uploads_collapse_all));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.setMarginStart(dp(6));
        toggleAllBtn.setLayoutParams(toggleLp);
        toggleAllBtn.setOnClickListener(v -> toggleAllFolders());
        searchRow.addView(toggleAllBtn);

        webSwitchBtn = createCompactOutlinedBtn(activity.getString(R.string.my_uploads_web_mode));
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        webLp.setMarginStart(dp(6));
        webSwitchBtn.setLayoutParams(webLp);
        webSwitchBtn.setOnClickListener(v -> callback.onFallbackToWeb(currentUrl));
        searchRow.addView(webSwitchBtn);

        topTools.addView(searchRow);

        chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(6);
        chipScroll.setLayoutParams(scrollLp);

        chipBar = new LinearLayout(activity);
        chipBar.setOrientation(LinearLayout.HORIZONTAL);
        chipBar.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(chipBar);
        topTools.addView(chipScroll);

        rootContainer.addView(topTools);

        // 空状态或错误卡片
        emptyStateBox = new LinearLayout(activity);
        emptyStateBox.setOrientation(LinearLayout.VERTICAL);
        emptyStateBox.setGravity(Gravity.CENTER);
        emptyStateBox.setPadding(dp(24), dp(36), dp(24), dp(24));
        emptyStateBox.setVisibility(View.GONE);

        emptyStateMsg = new TextView(activity);
        emptyStateMsg.setText(R.string.my_uploads_empty);
        emptyStateMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        emptyStateMsg.setTextColor(getSecondaryTextColor());
        emptyStateMsg.setGravity(Gravity.CENTER);
        emptyStateBox.addView(emptyStateMsg);

        LinearLayout emptyBtns = new LinearLayout(activity);
        emptyBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ebLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ebLp.topMargin = dp(12);
        emptyBtns.setLayoutParams(ebLp);

        MaterialButton emptyRetry = createCompactOutlinedBtn(activity.getString(R.string.refresh));
        emptyRetry.setOnClickListener(v -> loadUrl(currentUrl));
        MaterialButton emptyWeb = createCompactFilledBtn(activity.getString(R.string.my_uploads_web_mode));
        LinearLayout.LayoutParams ewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ewLp.setMarginStart(dp(8));
        emptyWeb.setLayoutParams(ewLp);
        emptyWeb.setOnClickListener(v -> callback.onFallbackToWeb(currentUrl));
        emptyBtns.addView(emptyRetry);
        emptyBtns.addView(emptyWeb);
        emptyStateBox.addView(emptyBtns);
        rootContainer.addView(emptyStateBox);

        // 主列表 RecyclerView（留出底部 88dp 以防右下角 + FAB 遮挡最后一个画廊的按钮）
        recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setAdapter(adapter);
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(dp(10), dp(4), dp(10), dp(88));
        recyclerView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rootContainer.addView(recyclerView);

        // 底部勾选批量操作栏
        batchBar = new LinearLayout(activity);
        batchBar.setOrientation(LinearLayout.HORIZONTAL);
        batchBar.setGravity(Gravity.CENTER_VERTICAL);
        batchBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        batchBar.setBackgroundColor(getCardBg());
        batchBar.setElevation(dp(6));
        batchBar.setVisibility(View.GONE);

        batchCountTv = new TextView(activity);
        batchCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        batchCountTv.setTypeface(null, Typeface.BOLD);
        batchCountTv.setTextColor(getPrimaryTextColor());
        batchCountTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        batchBar.addView(batchCountTv);

        MaterialButton selectAllBtn = createCompactOutlinedBtn(activity.getString(R.string.my_uploads_select_all));
        selectAllBtn.setOnClickListener(v -> selectAllVisible());
        batchBar.addView(selectAllBtn);

        MaterialButton clearBtn = createCompactOutlinedBtn(activity.getString(R.string.my_uploads_clear_selection));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(dp(6));
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            selectedValues.clear();
            updateBatchBar();
            adapter.notifyDataSetChanged();
        });
        batchBar.addView(clearBtn);

        MaterialButton applyBtn = createCompactFilledBtn(activity.getString(R.string.my_uploads_batch_execute));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMarginStart(dp(6));
        applyBtn.setLayoutParams(alp);
        applyBtn.setOnClickListener(v -> promptBatchAction());
        batchBar.addView(applyBtn);

        rootContainer.addView(batchBar);
    }

    @NonNull
    public String getCurrentUrl() {
        return currentUrl;
    }

    public boolean hasLoadedData() {
        return currentDetail != null;
    }

    public void loadUrl(@NonNull String url) {
        this.currentUrl = url;
        final int seq = requestSeq.incrementAndGet();
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }

        executor.execute(() -> {
            try {
                EhWebPageHelper.FetchResult res = EhWebPageHelper.fetchHtmlSync(activity, url);
                EhUploadDetail parsed = EhUploadParser.parseManage(res.html, res.url);

                // 排序固定为「最新上传在前」：若服务端返回非降序（例如首尾时间递增），自动切换至 Date Added 降序视图
                if (!autoSortedNewestOnServer && !isNewestFirstOrder(parsed)) {
                    autoSortedNewestOnServer = true;
                    List<SortOption> candidates = collectDateSortCandidates(parsed.sortOptions, res.url);
                    for (SortOption opt : candidates) {
                        try {
                            EhWebPageHelper.FetchResult candRes = EhWebPageHelper.fetchHtmlSync(activity, opt.url);
                            EhUploadDetail candParsed = EhUploadParser.parseManage(candRes.html, candRes.url);
                            if (isNewestFirstOrder(candParsed)) {
                                res = candRes;
                                parsed = candParsed;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }

                final EhWebPageHelper.FetchResult finalRes = res;
                final EhUploadDetail finalParsed = parsed;
                mainHandler.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed() || seq != requestSeq.get()) {
                        return;
                    }
                    if (progressView != null) {
                        progressView.setVisibility(View.GONE);
                    }
                    currentDetail = finalParsed;
                    currentUrl = finalRes.url;
                    selectedValues.clear();
                    updateBatchBar();
                    rebuildChips();
                    rebuildAdapterItems();
                });
            } catch (Throwable t) {
                ExceptionUtils.throwIfFatal(t);
                Log.w(TAG, "Failed to load native manage: " + url, t);
                mainHandler.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed() || seq != requestSeq.get()) {
                        return;
                    }
                    if (progressView != null) {
                        progressView.setVisibility(View.GONE);
                    }
                    showEmptyState(activity.getString(R.string.my_uploads_load_failed)
                            + "\n" + ExceptionUtils.getReadableString(t));
                });
            }
        });
    }

    public void destroy() {
        destroyed = true;
        requestSeq.incrementAndGet();
        executor.shutdownNow();
    }

    @NonNull
    private static List<SortOption> collectDateSortCandidates(@NonNull List<SortOption> options,
                                                            @NonNull String currentUrl) {
        List<SortOption> preferred = new ArrayList<>();
        List<SortOption> fallback = new ArrayList<>();
        for (SortOption opt : options) {
            if (!opt.label.toLowerCase(Locale.US).contains("date") || opt.url.equals(currentUrl)) {
                continue;
            }
            if (opt.descending) {
                preferred.add(opt);
            } else {
                fallback.add(opt);
            }
        }
        List<SortOption> ordered = new ArrayList<>(preferred.size() + fallback.size());
        ordered.addAll(preferred);
        ordered.addAll(fallback);
        return ordered;
    }

    private static boolean isNewestFirstOrder(@NonNull EhUploadDetail detail) {
        for (UploadFolderGroup folder : detail.folders) {
            if (folder.galleries.size() < 2) {
                continue;
            }
            UploadGalleryItem first = folder.galleries.get(0);
            UploadGalleryItem last = folder.galleries.get(folder.galleries.size() - 1);
            String dFirst = EhUploadDetail.normalizeSortableDate(first.dateAdded);
            String dLast = EhUploadDetail.normalizeSortableDate(last.dateAdded);
            int cmp = dFirst.compareTo(dLast);
            if (cmp < 0) {
                return false;
            }
            if (cmp == 0 && first.gid < last.gid) {
                return false;
            }
        }
        return true;
    }

    private void rebuildChips() {
        chipBar.removeAllViews();
        if (currentDetail == null) {
            chipScroll.setVisibility(View.GONE);
            return;
        }

        // 目录过滤芯片（已发布在前、未发布在后；有 >= 2 个目录时展示；排序固定最新上传在前，不展示排序切换芯片）
        if (currentDetail.folders.size() > 1) {
            chipBar.addView(makeFilterChip(activity.getString(R.string.my_uploads_all_folders),
                    selectedFolderFilter == null, v -> {
                        selectedFolderFilter = null;
                        rebuildChips();
                        rebuildAdapterItems();
                    }));
            for (UploadFolderGroup f : getOrderedFolders(currentDetail.folders)) {
                String filterKey = f.getFilterKey();
                boolean active = filterKey.equals(selectedFolderFilter);
                int count = f.serverCount >= 0 ? f.serverCount : f.galleries.size();
                String chipTitle = (f.unpublishedSection
                        ? activity.getString(R.string.my_uploads_unpublished) + " · "
                        : "")
                        + f.name
                        + (count > 0 ? " (" + count + ")" : "");
                chipBar.addView(makeFilterChip(chipTitle, active, v -> {
                    selectedFolderFilter = active ? null : filterKey;
                    rebuildChips();
                    rebuildAdapterItems();
                }));
            }
        }

        chipScroll.setVisibility(chipBar.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void toggleAllFolders() {
        if (currentDetail == null) {
            return;
        }
        if (allExpanded && !TextUtils.isEmpty(currentDetail.collapseAllUrl)) {
            allExpanded = false;
            toggleAllBtn.setText(R.string.my_uploads_expand_all);
        } else if (!allExpanded && !TextUtils.isEmpty(currentDetail.expandAllUrl)) {
            allExpanded = true;
            toggleAllBtn.setText(R.string.my_uploads_collapse_all);
            // 若当前页部分目录在服务端处于折叠状态，先请求全展开链接
            loadUrl(currentDetail.expandAllUrl);
            return;
        } else {
            allExpanded = !allExpanded;
            toggleAllBtn.setText(allExpanded
                    ? R.string.my_uploads_collapse_all
                    : R.string.my_uploads_expand_all);
        }

        for (UploadFolderGroup f : currentDetail.folders) {
            f.collapsed = !allExpanded;
        }
        rebuildAdapterItems();
    }

    @NonNull
    private List<UploadFolderGroup> getOrderedFolders(@NonNull List<UploadFolderGroup> rawFolders) {
        List<UploadFolderGroup> published = new ArrayList<>();
        List<UploadFolderGroup> unpublished = new ArrayList<>();
        for (UploadFolderGroup folder : rawFolders) {
            if (folder.unpublishedSection) {
                unpublished.add(folder);
            } else {
                published.add(folder);
            }
        }
        List<UploadFolderGroup> ordered = new ArrayList<>(published.size() + unpublished.size());
        ordered.addAll(published);
        ordered.addAll(unpublished);
        return ordered;
    }

    private void rebuildAdapterItems() {
        List<RowItem> rows = new ArrayList<>();
        if (currentDetail == null) {
            showEmptyState(activity.getString(R.string.my_uploads_empty));
            return;
        }

        for (UploadFolderGroup folder : getOrderedFolders(currentDetail.folders)) {
            if (selectedFolderFilter != null && !selectedFolderFilter.equals(folder.getFilterKey())) {
                continue;
            }

            List<UploadGalleryItem> matched = new ArrayList<>();
            for (UploadGalleryItem g : folder.galleries) {
                if (searchQuery.isEmpty()
                        || g.title.toLowerCase(Locale.US).contains(searchQuery)
                        || g.category.toLowerCase(Locale.US).contains(searchQuery)) {
                    matched.add(g);
                }
            }

            if (!searchQuery.isEmpty() && matched.isEmpty()) {
                continue;
            }

            // 排序定死：最新上传排在最前面（按添加时间降序，时间相同按 gid 降序）
            if (matched.size() > 1) {
                java.util.Collections.sort(matched, (a, b) -> {
                    String da = EhUploadDetail.normalizeSortableDate(a.dateAdded);
                    String db = EhUploadDetail.normalizeSortableDate(b.dateAdded);
                    int cmp = db.compareTo(da);
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Long.compare(b.gid, a.gid);
                });
            }

            rows.add(RowItem.forFolder(folder, matched.size()));
            if (!folder.collapsed || !searchQuery.isEmpty()) {
                for (UploadGalleryItem g : matched) {
                    rows.add(RowItem.forGallery(g));
                }
            }
        }

        if (!currentDetail.pageLinks.isEmpty()) {
            rows.add(RowItem.forPagination(currentDetail.pageLinks));
        }

        if (rows.isEmpty()) {
            showEmptyState(activity.getString(R.string.my_uploads_empty));
        } else {
            emptyStateBox.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
        adapter.setItems(rows);
    }

    private void showEmptyState(@NonNull String message) {
        recyclerView.setVisibility(View.GONE);
        emptyStateBox.setVisibility(View.VISIBLE);
        emptyStateMsg.setText(message);
        callback.onSelectionModeChanged(false);
    }

    private void selectAllVisible() {
        if (currentDetail == null) {
            return;
        }
        for (UploadFolderGroup f : currentDetail.folders) {
            if (f.collapsed && searchQuery.isEmpty()) {
                continue;
            }
            for (UploadGalleryItem g : f.galleries) {
                if (!TextUtils.isEmpty(g.checkboxValue)) {
                    selectedValues.add(g.checkboxValue);
                }
            }
        }
        updateBatchBar();
        adapter.notifyDataSetChanged();
    }

    private void updateBatchBar() {
        boolean hasVisibleBatch = !selectedValues.isEmpty()
                && !(currentDetail != null && currentDetail.batchActions.isEmpty());
        if (!hasVisibleBatch) {
            batchBar.setVisibility(View.GONE);
            callback.onSelectionModeChanged(false);
            return;
        }
        batchBar.setVisibility(View.VISIBLE);
        batchCountTv.setText(activity.getString(R.string.my_uploads_selected_count, selectedValues.size()));
        callback.onSelectionModeChanged(true);
    }

    private void promptBatchAction() {
        if (currentDetail == null || currentDetail.batchActions.isEmpty() || selectedValues.isEmpty()) {
            return;
        }
        String[] labels = new String[currentDetail.batchActions.size()];
        for (int i = 0; i < currentDetail.batchActions.size(); i++) {
            labels[i] = currentDetail.batchActions.get(i).label;
        }
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.my_uploads_selected_count, selectedValues.size()))
                .setItems(labels, (d, which) -> {
                    SelectOption chosen = currentDetail.batchActions.get(which);
                    if (!currentDetail.targetFolders.isEmpty()
                            && chosen.label.toLowerCase(Locale.US).contains("move")) {
                        promptTargetFolderAndSubmit(chosen);
                    } else {
                        submitBatchAction(chosen, null);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void promptTargetFolderAndSubmit(@NonNull SelectOption actionOpt) {
        if (currentDetail == null) {
            return;
        }
        String[] folderLabels = new String[currentDetail.targetFolders.size()];
        for (int i = 0; i < currentDetail.targetFolders.size(); i++) {
            folderLabels[i] = currentDetail.targetFolders.get(i).label;
        }
        new AlertDialog.Builder(activity)
                .setTitle(actionOpt.label)
                .setItems(folderLabels, (d, which) -> {
                    SelectOption folderOpt = currentDetail.targetFolders.get(which);
                    submitBatchAction(actionOpt, folderOpt);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void submitBatchAction(@NonNull SelectOption actionOpt, @Nullable SelectOption targetFolderOpt) {
        if (currentDetail == null || selectedValues.isEmpty()) {
            return;
        }
        String actionUrl = !TextUtils.isEmpty(currentDetail.formActionUrl)
                ? currentDetail.formActionUrl : currentUrl;
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        for (Map.Entry<String, String> hidden : currentDetail.hiddenInputs.entrySet()) {
            fields.add(new AbstractMap.SimpleEntry<>(hidden.getKey(), hidden.getValue()));
        }
        if (!TextUtils.isEmpty(currentDetail.batchActionSelectName)) {
            fields.add(new AbstractMap.SimpleEntry<>(currentDetail.batchActionSelectName, actionOpt.value));
        }
        if (targetFolderOpt != null && !TextUtils.isEmpty(currentDetail.targetFolderSelectName)) {
            fields.add(new AbstractMap.SimpleEntry<>(currentDetail.targetFolderSelectName, targetFolderOpt.value));
        }

        // 查找各勾选画廊对应的 checkbox name
        for (UploadFolderGroup f : currentDetail.folders) {
            for (UploadGalleryItem g : f.galleries) {
                if (!TextUtils.isEmpty(g.checkboxValue) && selectedValues.contains(g.checkboxValue)) {
                    String name = !TextUtils.isEmpty(g.checkboxName) ? g.checkboxName : "modify[]";
                    fields.add(new AbstractMap.SimpleEntry<>(name, g.checkboxValue));
                }
            }
        }

        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }
        executor.execute(() -> {
            try {
                EhWebPageHelper.FetchResult res = EhWebPageHelper.postFormSync(
                        activity, actionUrl, currentUrl, fields);
                EhUploadDetail refreshed = EhUploadParser.parseManage(res.html, res.url);
                mainHandler.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (progressView != null) {
                        progressView.setVisibility(View.GONE);
                    }
                    selectedValues.clear();
                    currentDetail = refreshed;
                    currentUrl = res.url;
                    updateBatchBar();
                    rebuildChips();
                    rebuildAdapterItems();
                    Toast.makeText(activity, R.string.my_uploads_batch_success, Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                ExceptionUtils.throwIfFatal(t);
                Log.w(TAG, "Batch action failed", t);
                mainHandler.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    if (progressView != null) {
                        progressView.setVisibility(View.GONE);
                    }
                    Toast.makeText(activity,
                            activity.getString(R.string.my_uploads_batch_failed, ExceptionUtils.getReadableString(t)),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ==================== Adapter & ViewHolders ====================

    private static final class RowItem {
        static final int TYPE_FOLDER = 0;
        static final int TYPE_GALLERY = 1;
        static final int TYPE_PAGINATION = 2;

        final int type;
        @Nullable
        final UploadFolderGroup folder;
        final int visibleCount;
        @Nullable
        final UploadGalleryItem gallery;
        @Nullable
        final List<PageLink> pageLinks;

        private RowItem(int type, @Nullable UploadFolderGroup folder, int visibleCount,
                        @Nullable UploadGalleryItem gallery, @Nullable List<PageLink> pageLinks) {
            this.type = type;
            this.folder = folder;
            this.visibleCount = visibleCount;
            this.gallery = gallery;
            this.pageLinks = pageLinks;
        }

        static RowItem forFolder(@NonNull UploadFolderGroup f, int count) {
            return new RowItem(TYPE_FOLDER, f, count, null, null);
        }

        static RowItem forGallery(@NonNull UploadGalleryItem g) {
            return new RowItem(TYPE_GALLERY, null, 0, g, null);
        }

        static RowItem forPagination(@NonNull List<PageLink> links) {
            return new RowItem(TYPE_PAGINATION, null, 0, null, links);
        }
    }

    private class UploadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private final List<RowItem> items = new ArrayList<>();

        void setItems(@NonNull List<RowItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == RowItem.TYPE_FOLDER) {
                return createFolderHolder(parent);
            }
            if (viewType == RowItem.TYPE_PAGINATION) {
                return createPaginationHolder(parent);
            }
            return createGalleryHolder(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            RowItem item = items.get(position);
            if (item.type == RowItem.TYPE_FOLDER && holder instanceof FolderHolder && item.folder != null) {
                ((FolderHolder) holder).bind(item.folder, item.visibleCount);
            } else if (item.type == RowItem.TYPE_GALLERY && holder instanceof GalleryHolder && item.gallery != null) {
                ((GalleryHolder) holder).bind(item.gallery);
            } else if (item.type == RowItem.TYPE_PAGINATION && holder instanceof PaginationHolder && item.pageLinks != null) {
                ((PaginationHolder) holder).bind(item.pageLinks);
            }
        }
    }

    private FolderHolder createFolderHolder(@NonNull ViewGroup parent) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(6);
        bar.setLayoutParams(lp);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setBackground(createRoundedBg(getSubtleBg(), getDividerColor(), dp(8), dp(1)));
        return new FolderHolder(bar);
    }

    private class FolderHolder extends RecyclerView.ViewHolder {
        final TextView titleTv;
        final TextView indicatorTv;

        FolderHolder(@NonNull LinearLayout container) {
            super(container);
            titleTv = new TextView(activity);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            titleTv.setTypeface(null, Typeface.BOLD);
            titleTv.setTextColor(getAccentColor());
            titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            indicatorTv = new TextView(activity);
            indicatorTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            indicatorTv.setTextColor(getSecondaryTextColor());

            container.addView(titleTv);
            container.addView(indicatorTv);
        }

        void bind(@NonNull UploadFolderGroup folder, int visibleCount) {
            String prefix = folder.unpublishedSection ? "[" + activity.getString(R.string.my_uploads_unpublished) + "] " : "";
            int countVal = visibleCount > 0 ? visibleCount : Math.max(0, folder.serverCount);
            String countStr = countVal == 0 && folder.collapsed
                    ? "" : " (" + activity.getString(R.string.my_uploads_folder_count, countVal) + ")";
            titleTv.setText(prefix + folder.name + countStr);
            indicatorTv.setText(folder.collapsed ? "▶" : "▼");

            itemView.setOnClickListener(v -> {
                if (folder.collapsed && folder.galleries.isEmpty() && !TextUtils.isEmpty(folder.toggleUrl)) {
                    loadUrl(folder.toggleUrl);
                } else {
                    folder.collapsed = !folder.collapsed;
                    rebuildAdapterItems();
                }
            });
        }
    }

    private GalleryHolder createGalleryHolder(@NonNull ViewGroup parent) {
        CardView card = new CardView(activity);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        card.setRadius(dp(10));
        card.setCardElevation(dp(2));
        card.setCardBackgroundColor(getCardBg());
        card.setPreventCornerOverlap(false);
        card.setClipChildren(false);

        LinearLayout inner = new LinearLayout(activity);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inner.setClipChildren(false);
        inner.setClipToPadding(false);
        inner.setPadding(dp(12), dp(12), dp(12), dp(10));

        // 顶部行：使用 RelativeLayout + AppCompatTextView，并额外预留 6dp 底部测量裕量，杜绝多行标题末行被裁剪
        RelativeLayout topRow = new RelativeLayout(activity);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        topRow.setClipChildren(false);
        topRow.setClipToPadding(false);

        CheckBox checkBox = new CheckBox(activity);
        int checkBoxId = View.generateViewId();
        checkBox.setId(checkBoxId);
        checkBox.setButtonTintList(ColorStateList.valueOf(getAccentColor()));
        checkBox.setMinWidth(0);
        checkBox.setMinHeight(0);
        checkBox.setMinimumWidth(0);
        checkBox.setMinimumHeight(0);
        checkBox.setPadding(0, 0, dp(4), 0);
        RelativeLayout.LayoutParams cbLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.addRule(RelativeLayout.ALIGN_PARENT_START);
        cbLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        cbLp.topMargin = dp(1);
        cbLp.setMarginEnd(dp(6));
        checkBox.setLayoutParams(cbLp);

        AppCompatTextView titleTv = new AppCompatTextView(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                int extraBottom = dp(6);
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                if (mode == MeasureSpec.AT_MOST) {
                    int maxH = MeasureSpec.getSize(heightMeasureSpec);
                    setMeasuredDimension(getMeasuredWidth(), Math.min(maxH, getMeasuredHeight() + extraBottom));
                } else if (mode != MeasureSpec.EXACTLY) {
                    setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight() + extraBottom);
                }
            }
        };
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setGravity(Gravity.TOP | Gravity.START);
        titleTv.setTextColor(getPrimaryTextColor());
        titleTv.setIncludeFontPadding(true);
        titleTv.setLineSpacing(dp(2), 1.06f);
        titleTv.setPadding(0, dp(1), 0, 0);
        RelativeLayout.LayoutParams titleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(RelativeLayout.END_OF, checkBoxId);
        titleLp.addRule(RelativeLayout.ALIGN_PARENT_END);
        titleLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        titleLp.alignWithParent = true;
        titleTv.setLayoutParams(titleLp);

        topRow.addView(checkBox);
        topRow.addView(titleTv);
        inner.addView(topRow);

        // 中部元信息行：分类彩色标签 + 页数 + 添加时间 + 状态
        LinearLayout metaRow = new LinearLayout(activity);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dp(3);
        metaRow.setLayoutParams(metaLp);

        TextView categoryBadge = new TextView(activity);
        categoryBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        categoryBadge.setTypeface(null, Typeface.BOLD);
        categoryBadge.setTextColor(Color.WHITE);
        categoryBadge.setPadding(dp(7), dp(2), dp(7), dp(2));
        LinearLayout.LayoutParams catLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        catLp.setMarginEnd(dp(8));
        categoryBadge.setLayoutParams(catLp);

        TextView metaText = new TextView(activity);
        metaText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        metaText.setTextColor(getSecondaryTextColor());
        metaText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        metaRow.addView(categoryBadge);
        metaRow.addView(metaText);
        inner.addView(metaRow);

        // 底部 4 个并排原生操作按钮，100% 在手机屏幕内可见可点！
        LinearLayout actionsRow = new LinearLayout(activity);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(10);
        actionsRow.setLayoutParams(actLp);

        MaterialButton btnView = createActionBtn(activity.getString(R.string.my_uploads_action_view), getAccentColor());
        MaterialButton btnStats = createActionBtn(activity.getString(R.string.my_uploads_action_stats), getPrimaryTextColor());
        MaterialButton btnManage = createActionBtn(activity.getString(R.string.my_uploads_action_manage), getPrimaryTextColor());
        MaterialButton btnDisown = createActionBtn(activity.getString(R.string.my_uploads_action_disown),
                ContextCompat.getColor(activity, R.color.red_500));

        actionsRow.addView(btnView);
        actionsRow.addView(btnStats);
        actionsRow.addView(btnManage);
        actionsRow.addView(btnDisown);
        inner.addView(actionsRow);

        card.addView(inner);
        return new GalleryHolder(card, checkBox, titleTv, categoryBadge, metaText,
                btnView, btnStats, btnManage, btnDisown);
    }

    private MaterialButton createActionBtn(@NonNull String label, int textColor) {
        MaterialButton btn = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setSingleLine(true);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setCornerRadius(dp(8));
        btn.setMinHeight(dp(34));
        btn.setMinimumHeight(dp(34));
        btn.setInsetTop(dp(2));
        btn.setInsetBottom(dp(2));
        btn.setPadding(dp(4), dp(2), dp(4), dp(2));
        btn.setTextColor(textColor);
        btn.setStrokeColor(ColorStateList.valueOf(getDividerColor()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(2));
        lp.setMarginEnd(dp(2));
        btn.setLayoutParams(lp);
        return btn;
    }

    private class GalleryHolder extends RecyclerView.ViewHolder {
        final CheckBox checkBox;
        final TextView titleTv;
        final TextView categoryBadge;
        final TextView metaText;
        final MaterialButton btnView;
        final MaterialButton btnStats;
        final MaterialButton btnManage;
        final MaterialButton btnDisown;

        GalleryHolder(@NonNull View root, CheckBox checkBox, TextView titleTv,
                      TextView categoryBadge, TextView metaText,
                      MaterialButton btnView, MaterialButton btnStats,
                      MaterialButton btnManage, MaterialButton btnDisown) {
            super(root);
            this.checkBox = checkBox;
            this.titleTv = titleTv;
            this.categoryBadge = categoryBadge;
            this.metaText = metaText;
            this.btnView = btnView;
            this.btnStats = btnStats;
            this.btnManage = btnManage;
            this.btnDisown = btnDisown;
        }

        void bind(@NonNull UploadGalleryItem g) {
            titleTv.setText(g.title);
            titleTv.setOnClickListener(v -> {
                if (!TextUtils.isEmpty(g.manageUrl)) {
                    callback.onManageGallery(g.manageUrl);
                } else if (!TextUtils.isEmpty(g.viewUrl)) {
                    callback.onViewGallery(g.viewUrl);
                }
            });

            if (!TextUtils.isEmpty(g.checkboxValue)) {
                checkBox.setVisibility(View.VISIBLE);
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(selectedValues.contains(g.checkboxValue));
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        selectedValues.add(g.checkboxValue);
                    } else {
                        selectedValues.remove(g.checkboxValue);
                    }
                    updateBatchBar();
                });
            } else {
                checkBox.setVisibility(View.GONE);
            }

            if (!TextUtils.isEmpty(g.category)) {
                categoryBadge.setVisibility(View.VISIBLE);
                categoryBadge.setText(g.category);
                int catInt = EhUtils.getCategory(g.category);
                int catColor = catInt == EhUtils.UNKNOWN ? getAccentColor() : EhUtils.getCategoryColor(catInt);
                categoryBadge.setBackground(createRoundedBg(catColor, Color.TRANSPARENT, dp(5), 0));
            } else {
                categoryBadge.setVisibility(View.GONE);
            }

            StringBuilder sb = new StringBuilder();
            if (g.filesCount > 0) {
                sb.append(activity.getString(R.string.my_uploads_files_fmt, g.filesCount));
            }
            if (!TextUtils.isEmpty(g.dateAdded)) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(g.dateAdded);
            }
            if (!g.published) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(activity.getString(R.string.my_uploads_unpublished));
            }
            metaText.setText(sb.toString());

            // 1. 查看
            if (!TextUtils.isEmpty(g.viewUrl)) {
                btnView.setEnabled(true);
                btnView.setAlpha(1f);
                btnView.setOnClickListener(v -> callback.onViewGallery(g.viewUrl));
            } else {
                btnView.setEnabled(false);
                btnView.setAlpha(0.4f);
                btnView.setOnClickListener(null);
            }

            // 2. 统计（即使 HTML 未直接给 stats 链接，若存在 gid & token 也能自动拼出！）
            String resolvedStats = g.statsUrl;
            if (TextUtils.isEmpty(resolvedStats) && g.gid > 0 && !TextUtils.isEmpty(g.token)) {
                resolvedStats = EhUrl.getGalleryStatsUrl(g.gid, g.token);
            }
            if (!TextUtils.isEmpty(resolvedStats)) {
                final String finalStatsUrl = resolvedStats;
                btnStats.setEnabled(true);
                btnStats.setAlpha(1f);
                btnStats.setOnClickListener(v -> callback.onViewStats(finalStatsUrl));
            } else {
                btnStats.setEnabled(false);
                btnStats.setAlpha(0.4f);
                btnStats.setOnClickListener(null);
            }

            // 3. 编辑/管理
            if (!TextUtils.isEmpty(g.manageUrl)) {
                btnManage.setEnabled(true);
                btnManage.setAlpha(1f);
                btnManage.setOnClickListener(v -> callback.onManageGallery(g.manageUrl));
            } else {
                btnManage.setEnabled(false);
                btnManage.setAlpha(0.4f);
                btnManage.setOnClickListener(null);
            }

            // 4. 放弃所有权 / 删除
            boolean hasDisown = !TextUtils.isEmpty(g.disownUrl) || !TextUtils.isEmpty(g.disownOnClick)
                    || !TextUtils.isEmpty(g.manageUrl);
            if (hasDisown) {
                btnDisown.setEnabled(true);
                btnDisown.setAlpha(1f);
                btnDisown.setOnClickListener(v -> new AlertDialog.Builder(activity)
                        .setTitle(R.string.my_uploads_disown_confirm_title)
                        .setMessage(activity.getString(R.string.my_uploads_disown_confirm_msg, g.title))
                        .setPositiveButton(android.R.string.ok, (d, w) -> callback.onDisownGallery(g))
                        .setNegativeButton(R.string.cancel, null)
                        .show());
            } else {
                btnDisown.setEnabled(false);
                btnDisown.setAlpha(0.4f);
                btnDisown.setOnClickListener(null);
            }
        }
    }

    private PaginationHolder createPaginationHolder(@NonNull ViewGroup parent) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        wrap.setLayoutParams(lp);
        return new PaginationHolder(wrap);
    }

    private class PaginationHolder extends RecyclerView.ViewHolder {
        final LinearLayout container;

        PaginationHolder(@NonNull LinearLayout container) {
            super(container);
            this.container = container;
        }

        void bind(@NonNull List<PageLink> links) {
            container.removeAllViews();
            for (PageLink link : links) {
                MaterialButton btn = link.current
                        ? createCompactFilledBtn(link.label)
                        : createCompactOutlinedBtn(link.label);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginStart(dp(3));
                lp.setMarginEnd(dp(3));
                btn.setLayoutParams(lp);
                btn.setOnClickListener(v -> {
                    if (!TextUtils.isEmpty(link.url) && !link.current) {
                        loadUrl(link.url);
                    }
                });
                container.addView(btn);
            }
        }
    }

    private View makeFilterChip(@NonNull String text, boolean active, @NonNull View.OnClickListener listener) {
        MaterialButton chip = active ? createCompactFilledBtn(text) : createCompactOutlinedBtn(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(listener);
        return chip;
    }

    private MaterialButton createCompactOutlinedBtn(@NonNull String text) {
        MaterialButton btn = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setCornerRadius(dp(16));
        btn.setMinHeight(dp(32));
        btn.setMinimumHeight(dp(32));
        btn.setInsetTop(dp(2));
        btn.setInsetBottom(dp(2));
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setTextColor(getPrimaryTextColor());
        btn.setStrokeColor(ColorStateList.valueOf(getDividerColor()));
        return btn;
    }

    private MaterialButton createCompactFilledBtn(@NonNull String text) {
        MaterialButton btn = new MaterialButton(activity);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setCornerRadius(dp(16));
        btn.setMinHeight(dp(32));
        btn.setMinimumHeight(dp(32));
        btn.setInsetTop(dp(2));
        btn.setInsetBottom(dp(2));
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundTintList(ColorStateList.valueOf(getAccentColor()));
        return btn;
    }

    private GradientDrawable createRoundedBg(int fillColor, int strokeColor, int radiusPx, int strokeWidthPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fillColor);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }

    private boolean isDarkTheme() {
        int theme = Settings.getTheme();
        return theme == Settings.THEME_DARK || theme == Settings.THEME_BLACK;
    }

    private int getCardBg() {
        return EhWebPageHelper.resolveThemeColor(activity, R.attr.contentColorPrimary,
                isDarkTheme() ? 0xFF303030 : Color.WHITE);
    }

    private int getSubtleBg() {
        return isDarkTheme() ? 0xFF262626 : 0xFFF3F4F6;
    }

    private int getAccentColor() {
        return EhWebPageHelper.resolveThemeColor(activity, R.attr.textColorThemeAccent,
                ContextCompat.getColor(activity, R.color.colorAccent));
    }

    private int getPrimaryTextColor() {
        return EhWebPageHelper.resolveThemeColor(activity, android.R.attr.textColorPrimary,
                isDarkTheme() ? 0xFFEEEEEE : 0xFF212121);
    }

    private int getSecondaryTextColor() {
        return EhWebPageHelper.resolveThemeColor(activity, android.R.attr.textColorSecondary,
                isDarkTheme() ? 0xFFB0B0B0 : 0xFF757575);
    }

    private int getDividerColor() {
        return EhWebPageHelper.resolveThemeColor(activity, R.attr.dividerColor,
                isDarkTheme() ? 0xFF424242 : 0xFFE0E0E0);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }
}
