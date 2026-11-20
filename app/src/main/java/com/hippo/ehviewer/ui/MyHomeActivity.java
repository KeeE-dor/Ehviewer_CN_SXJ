package com.hippo.ehviewer.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.HomeDetail;
import com.hippo.ehviewer.widget.DialogWebChromeClient;
import com.hippo.util.ExceptionUtils;
import com.hippo.widget.ProgressView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * E-Hentai 个人主页（「概况」采用全原生 Material 卡片实现；
 * 「我的统计 / Hentai@Home / Hath Perks / Hath 交易 / GP 交易 / Credit 记录 / Karma 记录 / 捐赠」
 * 强制走 e-hentai.org 并通过 EhWebPageHelper 适配为 EhViewer 主题质感）。
 */
public class MyHomeActivity extends ToolbarActivity {

    private static final String TAG = "MyHomeActivity";
    public static final String KEY_URL = "url";
    public static final String KEY_TAB = "tab";
    private static final int REQUEST_CODE_FILE_CHOOSER = 2001;

    private static class HomeTab {
        final int titleResId;
        @Nullable
        final String url;
        final int specialAction; // 0=Overview(原生), 1=WebView, 2=UConfig, 3=MyTags

        HomeTab(int titleResId, @Nullable String url, int specialAction) {
            this.titleResId = titleResId;
            this.url = url;
            this.specialAction = specialAction;
        }
    }

    private final List<HomeTab> tabs = new ArrayList<>();
    private TabLayout tabLayout;
    private NestedScrollView overviewScroll;
    private NestedScrollView nativeStatsScroll;
    private LinearLayout nativeStatsContainer;
    @Nullable
    private NativeStatsController nativeStatsController;
    private WebView webView;
    private ProgressView progress;
    private ProgressBar progressBar;

    // 原生概况控件
    private TextView tvLimitsRatio;
    private ProgressBar pbLimits;
    private TextView tvLimitsDesc;
    private MaterialButton btnUnlockQuota;

    private TextView tvTrackerUploaded;
    private TextView tvTrackerDownloaded;
    private TextView tvTrackerRatio;
    private TextView tvTrackerTorrents;
    private TextView tvTrackerGalleries;
    private TextView tvTrackerSeedmins;
    private TextView tvTorrentKey;
    private ImageButton btnCopyKey;
    private MaterialButton btnShowMyTorrents;
    private MaterialButton btnResetTorrentKey;

    private TextView tvGpVisits;
    private TextView tvGpTorrents;
    private TextView tvGpArchives;
    private TextView tvGpHath;
    private TextView tvGpTotal;

    private LinearLayout containerToplists;
    private TextView tvModerationPower;
    private LinearLayout containerPowerLeft;
    private LinearLayout containerPowerRight;

    @Nullable
    private HomeDetail currentHomeDetail;
    private int currentTabIndex = 0;
    private boolean cookiesSynced = false;
    private boolean isOverviewLoading = false;
    private String currentWebUrl = EhUrl.HOME_E;
    @Nullable
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_home);
        setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
        setTitle(R.string.my_home);

        bindViews();
        initTabs();
        setupWebView();

        String initialUrl = getIntent() != null ? getIntent().getStringExtra(KEY_URL) : null;
        int requestedTab = getIntent() != null ? getIntent().getIntExtra(KEY_TAB, 0) : 0;

        if (!TextUtils.isEmpty(initialUrl)) {
            int matched = findTabIndexForUrl(initialUrl);
            if (matched >= 0) {
                TabLayout.Tab t = tabLayout.getTabAt(matched);
                if (t != null) {
                    t.select();
                }
                showUrlSmart(initialUrl);
            } else {
                TabLayout.Tab statsTab = tabLayout.getTabAt(1);
                if (statsTab != null) {
                    statsTab.select();
                }
                showUrlSmart(initialUrl);
            }
        } else if (requestedTab > 0 && requestedTab < tabs.size()) {
            TabLayout.Tab t = tabLayout.getTabAt(requestedTab);
            if (t != null) {
                t.select();
            }
            onTabChosen(requestedTab);
        } else {
            showOverviewTab();
            loadOverviewData();
        }
    }

    private void bindViews() {
        tabLayout = findViewById(R.id.tab_layout);
        overviewScroll = findViewById(R.id.overview_scroll);
        nativeStatsScroll = findViewById(R.id.native_stats_scroll);
        nativeStatsContainer = findViewById(R.id.native_stats_container);
        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        progressBar = findViewById(R.id.progress_bar);

        nativeStatsController = new NativeStatsController(
                this,
                nativeStatsScroll,
                nativeStatsContainer,
                progress,
                new NativeStatsController.Callback() {
                    @Override
                    public void onFallbackToWeb(@NonNull String url) {
                        showWebTab(url);
                    }

                    @Override
                    public void onOpenGalleryOrLink(@NonNull String url) {
                        if (!EhWebPageHelper.handleUrlNavigation(MyHomeActivity.this, url, webView)) {
                            showUrlSmart(url);
                        }
                    }
                }
        );

        tvLimitsRatio = findViewById(R.id.tv_limits_ratio);
        pbLimits = findViewById(R.id.pb_limits);
        tvLimitsDesc = findViewById(R.id.tv_limits_desc);
        btnUnlockQuota = findViewById(R.id.btn_unlock_quota);

        tvTrackerUploaded = findViewById(R.id.tv_tracker_uploaded);
        tvTrackerDownloaded = findViewById(R.id.tv_tracker_downloaded);
        tvTrackerRatio = findViewById(R.id.tv_tracker_ratio);
        tvTrackerTorrents = findViewById(R.id.tv_tracker_torrents);
        tvTrackerGalleries = findViewById(R.id.tv_tracker_galleries);
        tvTrackerSeedmins = findViewById(R.id.tv_tracker_seedmins);
        tvTorrentKey = findViewById(R.id.tv_torrent_key);
        btnCopyKey = findViewById(R.id.btn_copy_key);
        btnShowMyTorrents = findViewById(R.id.btn_show_my_torrents);
        btnResetTorrentKey = findViewById(R.id.btn_reset_torrent_key);

        tvGpVisits = findViewById(R.id.tv_gp_visits);
        tvGpTorrents = findViewById(R.id.tv_gp_torrents);
        tvGpArchives = findViewById(R.id.tv_gp_archives);
        tvGpHath = findViewById(R.id.tv_gp_hath);
        tvGpTotal = findViewById(R.id.tv_gp_total);

        containerToplists = findViewById(R.id.container_toplists);
        tvModerationPower = findViewById(R.id.tv_moderation_power);
        containerPowerLeft = findViewById(R.id.container_power_left);
        containerPowerRight = findViewById(R.id.container_power_right);

        btnUnlockQuota.setOnClickListener(v -> confirmAndResetLimit());
        btnCopyKey.setOnClickListener(v -> copyTorrentKey());
        btnShowMyTorrents.setOnClickListener(v -> openShowMyTorrents());
        btnResetTorrentKey.setOnClickListener(v -> confirmAndResetTorrentKey());

        findViewById(R.id.btn_goto_my_uploads).setOnClickListener(v ->
                startActivity(new Intent(this, MyUploadsActivity.class)));
        findViewById(R.id.btn_goto_uconfig).setOnClickListener(v ->
                startActivity(new Intent(this, UConfigActivity.class)));
        findViewById(R.id.btn_goto_mytags).setOnClickListener(v ->
                startActivity(new Intent(this, MyTagsActivity.class)));
    }

    private void initTabs() {
        tabs.clear();
        tabs.add(new HomeTab(R.string.my_home_tab_overview, EhUrl.HOME_E, 0));
        tabs.add(new HomeTab(R.string.my_home_tab_stats, EhUrl.URL_STATS_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_hath, EhUrl.URL_HENTAI_AT_HOME_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_perks, EhUrl.URL_HATH_PERKS_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_exchange_hath, EhUrl.URL_EXCHANGE_HATH_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_exchange_gp, EhUrl.URL_EXCHANGE_GP_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_log_credits, EhUrl.URL_LOGS_CREDITS_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_log_karma, EhUrl.URL_LOGS_KARMA_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_donations, EhUrl.URL_DONATIONS_E, 1));
        tabs.add(new HomeTab(R.string.my_home_tab_settings, EhUrl.URL_UCONFIG_E, 2));
        tabs.add(new HomeTab(R.string.my_home_tab_tags, EhUrl.URL_MY_TAGS_E, 3));

        for (HomeTab item : tabs) {
            tabLayout.addTab(tabLayout.newTab().setText(item.titleResId), false);
        }

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                onTabChosen(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                int pos = tab.getPosition();
                if (pos == 0) {
                    loadOverviewData();
                } else if (pos > 0 && pos < tabs.size() && tabs.get(pos).specialAction == 1) {
                    String target = tabs.get(pos).url;
                    if (!TextUtils.isEmpty(target)) {
                        showUrlSmart(target);
                    }
                }
            }
        });
    }

    private int findTabIndexForUrl(@NonNull String url) {
        String lower = url.toLowerCase(Locale.US);
        for (int i = 1; i < tabs.size(); i++) {
            HomeTab tab = tabs.get(i);
            if (tab.specialAction == 1 && tab.url != null) {
                String pathPart = Uri.parse(tab.url).getLastPathSegment();
                String queryPart = Uri.parse(tab.url).getQuery();
                if (!TextUtils.isEmpty(queryPart) && lower.contains(pathPart) && lower.contains(queryPart)) {
                    return i;
                }
                if (TextUtils.isEmpty(queryPart) && !TextUtils.isEmpty(pathPart) && lower.contains(pathPart)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void onTabChosen(int pos) {
        if (pos < 0 || pos >= tabs.size()) {
            return;
        }
        HomeTab item = tabs.get(pos);
        if (item.specialAction == 2) {
            startActivity(new Intent(this, UConfigActivity.class));
            restorePreviousTab();
            return;
        }
        if (item.specialAction == 3) {
            startActivity(new Intent(this, MyTagsActivity.class));
            restorePreviousTab();
            return;
        }

        currentTabIndex = pos;
        if (item.specialAction == 0) {
            showOverviewTab();
            if (currentHomeDetail == null && !isOverviewLoading) {
                loadOverviewData();
            }
        } else if (item.url != null) {
            showUrlSmart(item.url);
        }
    }

    private void showUrlSmart(@NonNull String url) {
        if (url.contains("stats.php") && nativeStatsController != null) {
            showNativeStatsTab(url);
        } else {
            showWebTab(url);
        }
    }

    private void showNativeStatsTab(@NonNull String url) {
        currentWebUrl = url;
        if (overviewScroll != null) {
            overviewScroll.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        if (nativeStatsScroll != null) {
            nativeStatsScroll.setVisibility(View.VISIBLE);
        }
        if (nativeStatsController != null) {
            nativeStatsController.loadUrl(url);
        }
    }

    private void restorePreviousTab() {
        TabLayout.Tab prev = tabLayout.getTabAt(currentTabIndex);
        if (prev != null) {
            tabLayout.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    prev.select();
                }
            });
        }
    }

    private void showOverviewTab() {
        setTitle(R.string.my_home);
        if (overviewScroll != null) {
            overviewScroll.setVisibility(View.VISIBLE);
        }
        if (nativeStatsScroll != null) {
            nativeStatsScroll.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        if (!isOverviewLoading && progress != null) {
            progress.setVisibility(View.GONE);
        }
    }

    private void showWebTab(@NonNull String url) {
        currentWebUrl = url;
        if (overviewScroll != null) {
            overviewScroll.setVisibility(View.GONE);
        }
        if (nativeStatsScroll != null) {
            nativeStatsScroll.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            if (!cookiesSynced) {
                cookiesSynced = true;
                EhWebPageHelper.syncCookiesAndLoad(this, webView, url);
            } else {
                webView.loadUrl(url);
            }
        }
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        EhWebPageHelper.configureWebView(webView, this);
        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return EhWebPageHelper.interceptGetWithOkHttp(MyHomeActivity.this, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                return handleInternalNavigation(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleInternalNavigation(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentWebUrl = url;
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(20);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (progress != null) {
                    progress.setVisibility(View.GONE);
                }
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                EhWebPageHelper.injectMaterialAdaptationJs(MyHomeActivity.this, view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && webView != null) {
                    if (progress != null) {
                        progress.setVisibility(View.GONE);
                    }
                    Snackbar.make(webView, R.string.my_uploads_load_failed, Snackbar.LENGTH_LONG)
                            .setAction(R.string.refresh, v -> {
                                if (webView != null) {
                                    webView.reload();
                                }
                            }).show();
                }
            }
        });
        webView.setWebChromeClient(new DialogWebChromeClient(this) {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null && webView != null && webView.getVisibility() == View.VISIBLE) {
                    if (newProgress >= 100) {
                        progressBar.setVisibility(View.GONE);
                    } else {
                        progressBar.setVisibility(View.VISIBLE);
                        progressBar.setProgress(newProgress);
                    }
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                EhWebPageHelper.handleFileChooser(
                        MyHomeActivity.this,
                        REQUEST_CODE_FILE_CHOOSER,
                        filePathCallback,
                        callback,
                        val -> filePathCallback = val
                );
                return true;
            }
        });
    }

    private boolean handleInternalNavigation(@Nullable String url) {
        if (!TextUtils.isEmpty(url) && url.contains("upld.e-hentai.org")) {
            Intent intent = new Intent(this, MyUploadsActivity.class);
            intent.putExtra(MyUploadsActivity.KEY_URL, url);
            startActivity(intent);
            return true;
        }
        return EhWebPageHelper.handleUrlNavigation(this, url, webView);
    }

    private void loadOverviewData() {
        isOverviewLoading = true;
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
        EhRequest request = new EhRequest()
                .setMethod(EhClient.METHOD_GET_HOME)
                .setCallback(new OverviewCallback());
        EhApplication.getEhClient(this).execute(request);
    }

    private void confirmAndResetLimit() {
        String costStr = currentHomeDetail != null
                ? HomeDetail.formatNumber(currentHomeDetail.resetCost()) + " GP"
                : "";
        String msg = TextUtils.isEmpty(costStr)
                ? getString(R.string.my_home_unlock_confirm_plain)
                : getString(R.string.my_home_unlock_confirm, costStr);

        new AlertDialog.Builder(this)
                .setTitle(R.string.my_home_image_limits)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (progress != null) {
                        progress.setVisibility(View.VISIBLE);
                    }
                    EhRequest req = new EhRequest()
                            .setMethod(EhClient.METHOD_RESET_LIMIT)
                            .setCallback(new OverviewCallback());
                    EhApplication.getEhClient(this).execute(req);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmAndResetTorrentKey() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.my_home_reset_torrent_key)
                .setMessage(R.string.my_home_reset_key_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (progress != null) {
                        progress.setVisibility(View.VISIBLE);
                    }
                    EhRequest req = new EhRequest()
                            .setMethod(EhClient.METHOD_RESET_TORRENT_KEY)
                            .setCallback(new OverviewCallback());
                    EhApplication.getEhClient(this).execute(req);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyTorrentKey() {
        String key = currentHomeDetail != null ? currentHomeDetail.getTorrentKey() : "";
        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.my_home_no_key, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("EhTorrentKey", key));
            Toast.makeText(this, getString(R.string.my_home_key_copied, key), Toast.LENGTH_SHORT).show();
        }
    }

    private void openShowMyTorrents() {
        String url = currentHomeDetail != null ? currentHomeDetail.getShowMyTorrentsUrl() : null;
        if (TextUtils.isEmpty(url)) {
            url = EhUrl.HOST_E + "torrents.php";
        }
        showWebTab(url);
    }

    private void bindHomeDetail(@NonNull HomeDetail detail) {
        currentHomeDetail = detail;

        // 1. 图像配额
        long used = detail.getUsed();
        long total = detail.getTotal();
        long cost = detail.resetCost();
        if (detail.isIpLimited() || total <= 0) {
            tvLimitsRatio.setText(R.string.ip_base_limits);
            pbLimits.setProgress(100);
            pbLimits.setProgressTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.red_500)));
            tvLimitsDesc.setText(cost > 0
                    ? getString(R.string.my_home_ip_limited_desc, HomeDetail.formatNumber(cost))
                    : getString(R.string.my_home_ip_limited_plain));
            btnUnlockQuota.setText(cost > 0
                    ? getString(R.string.my_home_unlock_quota_cost, HomeDetail.formatNumber(cost))
                    : getString(R.string.my_home_unlock_quota));
        } else {
            tvLimitsRatio.setText(String.format(Locale.US, "%s / %s",
                    HomeDetail.formatNumber(used), HomeDetail.formatNumber(total)));
            int percent = (int) Math.min(100L, Math.max(0L, (used * 100L) / total));
            pbLimits.setProgress(percent);
            int barColor = percent >= 85
                    ? ContextCompat.getColor(this, R.color.red_500)
                    : EhWebPageHelper.resolveThemeColor(this, R.attr.progressColor,
                    ContextCompat.getColor(this, R.color.colorAccent));
            pbLimits.setProgressTintList(ColorStateList.valueOf(barColor));
            tvLimitsDesc.setText(getString(R.string.my_home_quota_cost_desc, HomeDetail.formatNumber(cost)));
            btnUnlockQuota.setText(getString(R.string.my_home_reset_quota_cost, HomeDetail.formatNumber(cost)));
        }

        // 2. EH 种子服务器
        tvTrackerUploaded.setText(detail.getTrackerUploaded());
        tvTrackerDownloaded.setText(detail.getTrackerDownloaded());
        String ratio = detail.getTrackerRatio();
        tvTrackerRatio.setText(ratio);
        applyRatioColor(ratio);
        tvTrackerTorrents.setText(HomeDetail.formatNumber(detail.getTrackerTorrentCompletes()));
        tvTrackerGalleries.setText(HomeDetail.formatNumber(detail.getTrackerGalleryCompletes()));
        tvTrackerSeedmins.setText(HomeDetail.formatNumber(detail.getTrackerSeedmins()));

        String key = detail.getTorrentKey();
        tvTorrentKey.setText(TextUtils.isEmpty(key)
                ? getString(R.string.my_home_no_key)
                : getString(R.string.my_home_torrent_key_value, key));

        // 3. 获得的总 GP
        tvGpVisits.setText(formatGp(detail.rawFromGalleryVisits()));
        tvGpTorrents.setText(formatGp(detail.rawFromTorrentCompletions()));
        tvGpArchives.setText(formatGp(detail.rawFromArchiveDownloads()));
        tvGpHath.setText(formatGp(detail.rawFromHentaiAtHome()));
        tvGpTotal.setText(formatGp(detail.getTotalGpGainedSum()));

        // 4. 排行榜
        renderToplists(detail.getToplists());

        // 5. 愿力
        tvModerationPower.setText(detail.getCurrentModerationPower());
        renderPowerColumn(containerPowerLeft, detail.getModerationLeftItems());
        renderPowerColumn(containerPowerRight, detail.getModerationRightItems());
    }

    private void applyRatioColor(@NonNull String ratio) {
        if ("∞".equals(ratio) || "-".equals(ratio)) {
            int defaultText = EhWebPageHelper.resolveThemeColor(this, android.R.attr.textColorPrimary, Color.GRAY);
            tvTrackerRatio.setTextColor(defaultText);
            return;
        }
        try {
            double val = Double.parseDouble(ratio);
            if (val < 0.8) {
                tvTrackerRatio.setTextColor(ContextCompat.getColor(this, R.color.red_500));
            } else {
                tvTrackerRatio.setTextColor(ContextCompat.getColor(this, R.color.teal_500));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void renderToplists(@NonNull List<HomeDetail.ToplistItem> items) {
        containerToplists.removeAllViews();
        int secondary = EhWebPageHelper.resolveThemeColor(this, android.R.attr.textColorSecondary, Color.GRAY);
        int linkColor = EhWebPageHelper.resolveThemeColor(this, R.attr.textColorThemeAccent,
                ContextCompat.getColor(this, R.color.colorAccent));

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.my_home_toplists_empty);
            empty.setTextColor(secondary);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            containerToplists.addView(empty);
            return;
        }

        for (HomeDetail.ToplistItem item : items) {
            TextView tv = new TextView(this);
            tv.setText(item.text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setPadding(0, dp(4), 0, dp(4));
            if (!TextUtils.isEmpty(item.url)) {
                tv.setTextColor(linkColor);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setOnClickListener(v -> showWebTab(item.url));
            } else {
                tv.setTextColor(EhWebPageHelper.resolveThemeColor(this, android.R.attr.textColorPrimary, Color.BLACK));
            }
            containerToplists.addView(tv);
        }
    }

    private void renderPowerColumn(@NonNull LinearLayout container, @NonNull List<HomeDetail.PowerItem> items) {
        container.removeAllViews();
        int primary = EhWebPageHelper.resolveThemeColor(this, android.R.attr.textColorPrimary, Color.BLACK);
        int secondary = EhWebPageHelper.resolveThemeColor(this, android.R.attr.textColorSecondary, Color.GRAY);

        for (HomeDetail.PowerItem item : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(3), 0, dp(3));

            TextView labelTv = new TextView(this);
            labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            labelTv.setText(localizePowerLabel(item.label));
            labelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            labelTv.setTextColor(secondary);

            TextView valueTv = new TextView(this);
            valueTv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            valueTv.setGravity(Gravity.END);
            valueTv.setText(item.value);
            valueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            valueTv.setTypeface(null, Typeface.BOLD);
            valueTv.setTextColor(primary);

            row.addView(labelTv);
            row.addView(valueTv);
            container.addView(row);
        }
    }

    @NonNull
    private String localizePowerLabel(@NonNull String raw) {
        String lower = raw.trim().toLowerCase(Locale.US);
        switch (lower) {
            case "base":
                return getString(R.string.my_home_power_base);
            case "awards":
                return getString(R.string.my_home_power_awards);
            case "tagging":
                return getString(R.string.my_home_power_tagging);
            case "level":
                return getString(R.string.my_home_power_level);
            case "donations":
                return getString(R.string.my_home_power_donations);
            case "forum activity":
                return getString(R.string.my_home_power_forum);
            case "uploads/h@h":
                return getString(R.string.my_home_power_uploads_hath);
            case "account age":
                return getString(R.string.my_home_power_age);
            case "(capped to 25)":
                return getString(R.string.my_home_power_capped);
            default:
                return raw;
        }
    }

    @NonNull
    private String formatGp(long gp) {
        if (gp < 0) {
            return "--";
        }
        return HomeDetail.formatNumber(gp) + " GP";
    }

    private int dp(int dpValue) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpValue, getResources().getDisplayMetrics()));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_my_home, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_refresh) {
            if (nativeStatsScroll != null && nativeStatsScroll.getVisibility() == View.VISIBLE
                    && nativeStatsController != null) {
                nativeStatsController.loadUrl(nativeStatsController.getCurrentUrl());
            } else if (webView != null && webView.getVisibility() == View.VISIBLE) {
                webView.reload();
            } else {
                loadOverviewData();
            }
            return true;
        } else if (id == R.id.action_open_uploads) {
            startActivity(new Intent(this, MyUploadsActivity.class));
            return true;
        } else if (id == R.id.action_open_in_browser) {
            String target = currentWebUrl;
            if (nativeStatsScroll != null && nativeStatsScroll.getVisibility() == View.VISIBLE
                    && nativeStatsController != null) {
                target = nativeStatsController.getCurrentUrl();
            } else if (webView != null && webView.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(webView.getUrl())) {
                target = webView.getUrl();
            } else if (currentTabIndex >= 0 && currentTabIndex < tabs.size() && tabs.get(currentTabIndex).url != null) {
                target = tabs.get(currentTabIndex).url;
            }
            UrlOpener.openUrl(this, target, true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack();
                return;
            }
            // 从子链接返回「概况」卡片首页
            TabLayout.Tab overview = tabLayout.getTabAt(0);
            if (overview != null && tabLayout.getSelectedTabPosition() != 0) {
                overview.select();
                return;
            }
        } else if (nativeStatsScroll != null && nativeStatsScroll.getVisibility() == View.VISIBLE) {
            TabLayout.Tab overview = tabLayout.getTabAt(0);
            if (overview != null && tabLayout.getSelectedTabPosition() != 0) {
                overview.select();
                return;
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            ValueCallback<Uri[]> callback = filePathCallback;
            filePathCallback = null;
            if (callback != null) {
                callback.onReceiveValue(EhWebPageHelper.parseFileChooserResult(resultCode, data));
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (nativeStatsController != null) {
            nativeStatsController.destroy();
            nativeStatsController = null;
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private class OverviewCallback implements EhClient.Callback<HomeDetail> {
        @Override
        public void onSuccess(HomeDetail result) {
            isOverviewLoading = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (progress != null) {
                progress.setVisibility(View.GONE);
            }
            if (result != null) {
                bindHomeDetail(result);
            }
        }

        @Override
        public void onFailure(Exception e) {
            isOverviewLoading = false;
            Log.e(TAG, "Failed to load home detail", e);
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (progress != null) {
                progress.setVisibility(View.GONE);
            }
            String err = ExceptionUtils.getReadableString(e);
            if (overviewScroll != null) {
                Snackbar.make(overviewScroll, err, Snackbar.LENGTH_LONG)
                        .setAction(R.string.refresh, v -> loadOverviewData())
                        .show();
            } else {
                Toast.makeText(MyHomeActivity.this, err, Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public void onCancel() {
            isOverviewLoading = false;
            if (progress != null) {
                progress.setVisibility(View.GONE);
            }
        }
    }
}
