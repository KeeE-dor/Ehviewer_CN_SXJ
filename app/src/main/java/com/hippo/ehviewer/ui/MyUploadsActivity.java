/*
 * Copyright 2026 EhViewer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadGalleryItem;
import com.hippo.ehviewer.widget.DialogWebChromeClient;
import com.hippo.widget.ProgressView;

/**
 * E-Hentai 完整版「我的上传」(upld.e-hentai.org)：
 * 始终使用未被阉割的 E-Hentai 上传端，通过 EhWebPageHelper 注入 EhViewer 主题质感 Material UI
 * 与内置 Hosts/代理拦截，完整支持上传漫画/压缩包、编辑内容、文件夹管理与查看统计。
 */
public class MyUploadsActivity extends ToolbarActivity {

    private static final String TAG = "MyUploadsActivity";
    public static final String KEY_URL = "url";
    private static final int REQUEST_CODE_FILE_CHOOSER = 1001;

    private static final String[] TAB_URLS = {
            EhUrl.URL_MY_UPLOADS_E,
            EhUrl.URL_MY_UPLOADS_NEW_E,
            EhUrl.URL_MY_UPLOADS_FOLDERS_E,
            EhUrl.URL_STATS_E
    };

    private static final int[] TAB_TITLES = {
            R.string.my_uploads_dashboard,
            R.string.my_uploads_new_gallery_tab,
            R.string.my_uploads_folders,
            R.string.my_uploads_stats
    };

    private TabLayout tabLayout;
    private LinearLayout nativeManageContainer;
    private NestedScrollView nativeStatsScroll;
    private LinearLayout nativeStatsContainer;
    private WebView webView;
    private ProgressView progress;
    private ProgressBar progressBar;
    private FloatingActionButton fabNewUpload;

    @Nullable
    private NativeUploadsManageController nativeManageController;
    @Nullable
    private NativeStatsController nativeStatsController;

    private String currentUrl = EhUrl.URL_MY_UPLOADS_E;
    private boolean syncingTabSelection = false;
    private boolean cookiesSynced = false;
    private boolean wasLaunchedFromNativeManage = false;
    private boolean hasBatchSelection = false;
    @Nullable
    private ValueCallback<Uri[]> filePathCallback;

    @NonNull
    public String getCurrentUrl() {
        return TextUtils.isEmpty(currentUrl) ? EhUrl.URL_MY_UPLOADS_E : currentUrl;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String initialUrl = getIntent() != null ? getIntent().getStringExtra(KEY_URL) : null;
        if (TextUtils.isEmpty(initialUrl) || initialUrl.contains("exhentai.org")) {
            initialUrl = EhUrl.URL_MY_UPLOADS_E;
        }
        currentUrl = initialUrl;

        try {
            setContentView(R.layout.activity_my_uploads);
            setNavigationIcon(R.drawable.v_arrow_left_dark_x24);
            setTitle(R.string.my_uploads);

            tabLayout = findViewById(R.id.tab_layout);
            nativeManageContainer = findViewById(R.id.native_manage_container);
            nativeStatsScroll = findViewById(R.id.native_stats_scroll);
            nativeStatsContainer = findViewById(R.id.native_stats_container);
            webView = findViewById(R.id.webview);
            progress = findViewById(R.id.progress);
            progressBar = findViewById(R.id.progress_bar);
            fabNewUpload = findViewById(R.id.fab_new_upload);

            initNativeControllers();
            initTabs();
            fabNewUpload.setOnClickListener(v -> selectAndLoadTab(1));

            setupWebView();
            updateTabAndFabState(initialUrl);
            routeUrl(initialUrl, true);
        } catch (Throwable t) {
            Log.e(TAG, "MyUploads init failed", t);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.webview_unavailable_title)
                    .setMessage(R.string.webview_unavailable_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        }
    }

    private void initNativeControllers() {
        nativeManageController = new NativeUploadsManageController(
                this,
                nativeManageContainer,
                progress,
                new NativeUploadsManageController.Callback() {
                    @Override
                    public void onViewGallery(@NonNull String viewUrl) {
                        EhWebPageHelper.handleUrlNavigation(MyUploadsActivity.this, viewUrl, webView);
                    }

                    @Override
                    public void onViewStats(@NonNull String statsUrl) {
                        wasLaunchedFromNativeManage = true;
                        selectStatsTabWithUrl(statsUrl);
                    }

                    @Override
                    public void onManageGallery(@NonNull String manageUrl) {
                        wasLaunchedFromNativeManage = true;
                        routeUrl(manageUrl, false);
                    }

                    @Override
                    public void onDisownGallery(@NonNull UploadGalleryItem item) {
                        wasLaunchedFromNativeManage = true;
                        if (!TextUtils.isEmpty(item.disownUrl)
                                && !item.disownUrl.startsWith("javascript:")
                                && !"#".equals(item.disownUrl)) {
                            routeUrl(item.disownUrl, false);
                        } else if (!TextUtils.isEmpty(item.manageUrl)) {
                            routeUrl(item.manageUrl, false);
                        }
                    }

                    @Override
                    public void onFallbackToWeb(@NonNull String url) {
                        wasLaunchedFromNativeManage = false;
                        hasBatchSelection = false;
                        routeUrl(url, false);
                    }

                    @Override
                    public void onSelectionModeChanged(boolean hasSelection) {
                        hasBatchSelection = hasSelection;
                        updateFabVisibility(currentUrl);
                    }
                }
        );

        nativeStatsController = new NativeStatsController(
                this,
                nativeStatsScroll,
                nativeStatsContainer,
                progress,
                new NativeStatsController.Callback() {
                    @Override
                    public void onFallbackToWeb(@NonNull String url) {
                        routeUrl(url, false);
                    }

                    @Override
                    public void onOpenGalleryOrLink(@NonNull String url) {
                        if (!EhWebPageHelper.handleUrlNavigation(MyUploadsActivity.this, url, webView)) {
                            routeUrl(url, url.contains("stats.php"));
                        }
                    }
                }
        );
    }

    private void selectStatsTabWithUrl(@NonNull String statsUrl) {
        if (tabLayout != null) {
            TabLayout.Tab statsTab = tabLayout.getTabAt(3);
            if (statsTab != null && tabLayout.getSelectedTabPosition() != 3) {
                syncingTabSelection = true;
                statsTab.select();
                syncingTabSelection = false;
            }
        }
        routeUrl(statsUrl, true);
    }

    private void initTabs() {
        for (int titleRes : TAB_TITLES) {
            tabLayout.addTab(tabLayout.newTab().setText(titleRes), false);
        }
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (syncingTabSelection) {
                    return;
                }
                int pos = tab.getPosition();
                if (pos >= 0 && pos < TAB_URLS.length) {
                    loadUrlSafely(TAB_URLS[pos]);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                if (syncingTabSelection) {
                    return;
                }
                int pos = tab.getPosition();
                if (pos >= 0 && pos < TAB_URLS.length) {
                    loadUrlSafely(TAB_URLS[pos]);
                }
            }
        });
    }

    private void selectAndLoadTab(int index) {
        if (index < 0 || index >= TAB_URLS.length) {
            return;
        }
        TabLayout.Tab tab = tabLayout.getTabAt(index);
        if (tab != null && tabLayout.getSelectedTabPosition() != index) {
            tab.select();
        } else {
            loadUrlSafely(TAB_URLS[index]);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        EhWebPageHelper.configureWebView(webView, this);
        webView.setWebViewClient(new MyUploadsWebViewClient());
        webView.setWebChromeClient(new MyUploadsWebChromeClient(this));
    }

    private void loadUrlSafely(@NonNull String url) {
        wasLaunchedFromNativeManage = false;
        routeUrl(url, true);
    }

    private void routeUrl(@NonNull String url, boolean allowNative) {
        currentUrl = url;
        updateTabAndFabState(url);

        boolean isManageDashboard = !url.contains("managegallery")
                && !url.contains("managefolders")
                && !url.contains("stats.php")
                && url.contains("upld.e-hentai.org");
        boolean isStatsPage = url.contains("stats.php");

        if (allowNative && isManageDashboard && nativeManageController != null) {
            hasBatchSelection = false;
            nativeManageContainer.setVisibility(View.VISIBLE);
            nativeStatsScroll.setVisibility(View.GONE);
            webView.setVisibility(View.GONE);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            updateFabVisibility(url);
            nativeManageController.loadUrl(url);
            return;
        }

        if (allowNative && isStatsPage && nativeStatsController != null) {
            hasBatchSelection = false;
            nativeManageContainer.setVisibility(View.GONE);
            nativeStatsScroll.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            updateFabVisibility(url);
            nativeStatsController.loadUrl(url);
            return;
        }

        hasBatchSelection = false;
        nativeManageContainer.setVisibility(View.GONE);
        nativeStatsScroll.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        updateFabVisibility(url);
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
        if (!cookiesSynced) {
            cookiesSynced = true;
            EhWebPageHelper.syncCookiesAndLoad(this, webView, url);
        } else {
            webView.loadUrl(url);
        }
    }

    private void updateTabAndFabState(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            setTitle(R.string.my_uploads);
            return;
        }
        int matchedTab = -1;
        if (url.contains("stats.php")) {
            setTitle(R.string.my_uploads_stats);
            matchedTab = 3;
        } else if (url.contains("managegallery") && url.contains("act=new")) {
            setTitle(R.string.my_uploads_new_gallery_title);
            matchedTab = 1;
        } else if (url.contains("managegallery")) {
            setTitle(R.string.my_uploads_edit_gallery);
            matchedTab = 0;
        } else if (url.contains("managefolders")) {
            setTitle(R.string.my_uploads_folders);
            matchedTab = 2;
        } else {
            setTitle(R.string.my_uploads);
            matchedTab = 0;
        }

        updateFabVisibility(url);

        if (matchedTab >= 0 && tabLayout != null && tabLayout.getSelectedTabPosition() != matchedTab) {
            TabLayout.Tab target = tabLayout.getTabAt(matchedTab);
            if (target != null) {
                syncingTabSelection = true;
                target.select();
                syncingTabSelection = false;
            }
        }
    }

    private void updateFabVisibility(@Nullable String url) {
        if (fabNewUpload == null) {
            return;
        }
        boolean hideForPage = !TextUtils.isEmpty(url) && url.contains("managegallery");
        if (hideForPage || hasBatchSelection) {
            fabNewUpload.hide();
        } else {
            fabNewUpload.show();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_my_uploads, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_refresh) {
            if (nativeManageContainer != null && nativeManageContainer.getVisibility() == View.VISIBLE
                    && nativeManageController != null) {
                nativeManageController.loadUrl(nativeManageController.getCurrentUrl());
            } else if (nativeStatsScroll != null && nativeStatsScroll.getVisibility() == View.VISIBLE
                    && nativeStatsController != null) {
                nativeStatsController.loadUrl(nativeStatsController.getCurrentUrl());
            } else if (webView != null) {
                webView.reload();
            }
            return true;
        } else if (id == R.id.action_open_home) {
            startActivity(new Intent(this, MyHomeActivity.class));
            return true;
        } else if (id == R.id.action_open_in_browser) {
            String target = currentUrl;
            if (nativeManageContainer != null && nativeManageContainer.getVisibility() == View.VISIBLE
                    && nativeManageController != null) {
                target = nativeManageController.getCurrentUrl();
            } else if (nativeStatsScroll != null && nativeStatsScroll.getVisibility() == View.VISIBLE
                    && nativeStatsController != null) {
                target = nativeStatsController.getCurrentUrl();
            } else if (webView != null && webView.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(webView.getUrl())) {
                target = webView.getUrl();
            }
            if (!TextUtils.isEmpty(target)) {
                UrlOpener.openUrl(this, target, true);
            }
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
            if (wasLaunchedFromNativeManage) {
                wasLaunchedFromNativeManage = false;
                selectAndLoadTab(0);
                return;
            }
        } else if (nativeStatsScroll != null && nativeStatsScroll.getVisibility() == View.VISIBLE
                && wasLaunchedFromNativeManage) {
            wasLaunchedFromNativeManage = false;
            selectAndLoadTab(0);
            return;
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
        if (nativeManageController != null) {
            nativeManageController.destroy();
            nativeManageController = null;
        }
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

    private class MyUploadsWebViewClient extends WebViewClient {
        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return EhWebPageHelper.interceptGetWithOkHttp(MyUploadsActivity.this, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
            return handleNavigation(url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(url);
        }

        private boolean handleNavigation(@Nullable String url) {
            if (wasLaunchedFromNativeManage && !TextUtils.isEmpty(url)
                    && url.contains("upld.e-hentai.org/manage")
                    && !url.contains("managegallery")
                    && !url.contains("managefolders")) {
                wasLaunchedFromNativeManage = false;
                routeUrl(url, true);
                return true;
            }
            if (!TextUtils.isEmpty(url) && !url.contains("upld.e-hentai.org")
                    && (url.contains("home.php") || url.contains("hentaiathome.php")
                    || url.contains("hathperks.php") || url.contains("exchange.php")
                    || url.contains("logs.php") || url.contains("bitcoin.php"))) {
                Intent intent = new Intent(MyUploadsActivity.this, MyHomeActivity.class);
                intent.putExtra(MyHomeActivity.KEY_URL, url);
                startActivity(intent);
                return true;
            }
            return EhWebPageHelper.handleUrlNavigation(MyUploadsActivity.this, url, webView);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            currentUrl = url;
            updateTabAndFabState(url);
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(15);
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
            updateTabAndFabState(url);
            EhWebPageHelper.injectMaterialAdaptationJs(MyUploadsActivity.this, view);
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
                        })
                        .show();
            }
        }
    }

    private class MyUploadsWebChromeClient extends DialogWebChromeClient {

        MyUploadsWebChromeClient(android.content.Context context) {
            super(context);
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (progressBar != null) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams fileChooserParams) {
            EhWebPageHelper.handleFileChooser(
                    MyUploadsActivity.this,
                    REQUEST_CODE_FILE_CHOOSER,
                    filePathCallback,
                    callback,
                    val -> filePathCallback = val
            );
            return true;
        }
    }
}
