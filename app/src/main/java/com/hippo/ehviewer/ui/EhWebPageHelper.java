package com.hippo.ehviewer.ui;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhRequestBuilder;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 共享辅助类：将 E-Hentai 的上传端（upld.e-hentai.org）与主页次级页面（stats / H@H / exchange / logs 等）
 * 在渲染前和渲染后适配为匹配当前 EhViewer 主题的响应式 Material 界面，
 * 并统一处理 EhHosts/代理拦截、Cookie 同步、文件选择上传与原生画廊跳转。
 */
public final class EhWebPageHelper {

    private static final String TAG = "EhWebPageHelper";

    public static final class FetchResult {
        @NonNull
        public final String url;
        @NonNull
        public final String html;

        public FetchResult(@NonNull String url, @NonNull String html) {
            this.url = url;
            this.html = html;
        }
    }

    private EhWebPageHelper() {
    }

    @NonNull
    public static FetchResult fetchHtmlSync(@NonNull Context context, @NonNull String url) throws IOException {
        OkHttpClient client = EhApplication.getOkHttpClient(context);
        String referer = url.contains("upld.e-hentai.org") ? EhUrl.HOST_UPLD_E : EhUrl.REFERER_E;
        EhRequestBuilder builder = new EhRequestBuilder(url, referer);
        attachFallbackWebViewCookie(context, builder, url);
        try (Response response = client.newCall(builder.build()).execute()) {
            return readFetchResponse(response, url);
        }
    }

    @NonNull
    public static FetchResult postFormSync(@NonNull Context context, @NonNull String url,
                                           @Nullable String referer,
                                           @NonNull List<Map.Entry<String, String>> formFields) throws IOException {
        OkHttpClient client = EhApplication.getOkHttpClient(context);
        String resolvedReferer = !TextUtils.isEmpty(referer)
                ? referer
                : (url.contains("upld.e-hentai.org") ? EhUrl.HOST_UPLD_E : EhUrl.REFERER_E);
        String origin = url.contains("upld.e-hentai.org") ? "https://upld.e-hentai.org" : EhUrl.ORIGIN_E;
        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> entry : formFields) {
            form.add(entry.getKey(), entry.getValue());
        }
        Request.Builder builder = new EhRequestBuilder(url, resolvedReferer, origin).post(form.build());
        attachFallbackWebViewCookie(context, builder, url);
        try (Response response = client.newCall(builder.build()).execute()) {
            return readFetchResponse(response, url);
        }
    }

    private static void attachFallbackWebViewCookie(@NonNull Context context,
                                                    @NonNull Request.Builder builder,
                                                    @NonNull String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || EhApplication.getEhCookieStore(context).getCookies(parsed).isEmpty()) {
            try {
                String webCookie = CookieManager.getInstance().getCookie(url);
                if (!TextUtils.isEmpty(webCookie)) {
                    builder.header("Cookie", webCookie);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @NonNull
    private static FetchResult readFetchResponse(@NonNull Response response, @NonNull String fallbackUrl) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Empty response body (HTTP " + response.code() + ")");
        }
        if (!response.isSuccessful()) {
            throw new IOException("HTTP " + response.code());
        }
        String html = body.string();
        String finalUrl = response.request().url().toString();
        return new FetchResult(TextUtils.isEmpty(finalUrl) ? fallbackUrl : finalUrl, html);
    }

    public static boolean hasSignedIn(@NonNull Context context) {
        EhCookieStore store = EhApplication.getEhCookieStore(context);
        HttpUrl eUrl = HttpUrl.get(EhUrl.HOST_E);
        HttpUrl exUrl = HttpUrl.get(EhUrl.HOST_EX);
        return store.contains(eUrl, EhCookieStore.KEY_IPD_MEMBER_ID)
                || store.contains(eUrl, EhCookieStore.KEY_IPD_PASS_HASH)
                || store.contains(exUrl, EhCookieStore.KEY_IPD_MEMBER_ID)
                || store.contains(exUrl, EhCookieStore.KEY_IPD_PASS_HASH);
    }

    @ColorInt
    public static int resolveThemeColor(@NonNull Context context, @AttrRes int attr, @ColorInt int fallback) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    return ContextCompat.getColor(context, tv.resourceId);
                } catch (Throwable ignored) {
                }
            }
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return fallback;
    }

    @NonNull
    public static String toCssHex(@ColorInt int color) {
        return String.format(Locale.US, "#%06X", (0xFFFFFF & color));
    }

    public static void configureWebView(@NonNull WebView webView, @NonNull Activity activity) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        webView.setHorizontalScrollBarEnabled(false);

        int defaultBg = Settings.getTheme() == Settings.THEME_BLACK
                ? Color.BLACK
                : (Settings.getTheme() == Settings.THEME_DARK ? 0xFF303030 : 0xFFFAFAFA);
        webView.setBackgroundColor(resolveThemeColor(activity, android.R.attr.windowBackground, defaultBg));

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                String cookies = CookieManager.getInstance().getCookie(url);
                if (!TextUtils.isEmpty(cookies)) {
                    request.addRequestHeader("Cookie", cookies);
                }
                if (!TextUtils.isEmpty(userAgent)) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                request.setTitle(fileName);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
                DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(activity, activity.getString(R.string.my_uploads_download_started, fileName),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "DownloadManager enqueue failed, falling back to external browser", t);
            }
            UrlOpener.openUrl(activity, url, true);
        });
    }

    public static void syncCookiesAndLoad(@NonNull Context context, @NonNull WebView webView, @NonNull String targetUrl) {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        cookieManager.removeAllCookies(ignored -> {
            applyEhCookies(context, cookieManager);
            cookieManager.flush();
            webView.loadUrl(targetUrl);
        });
    }

    public static void applyEhCookies(@NonNull Context context, @NonNull CookieManager cookieManager) {
        EhCookieStore store = EhApplication.getEhCookieStore(context);
        syncHostCookies(store, cookieManager, EhUrl.HOST_E, EhUrl.DOMAIN_E, EhUrl.HOST_UPLD_E);
        syncHostCookies(store, cookieManager, EhUrl.HOST_EX, EhUrl.DOMAIN_E, EhUrl.HOST_UPLD_E);
        syncDirectCookies(store, cookieManager, EhUrl.HOST_UPLD_E);
        syncDirectCookies(store, cookieManager, EhUrl.URL_FORUMS);
    }

    private static void syncHostCookies(@NonNull EhCookieStore store, @NonNull CookieManager cookieManager,
                                        @NonNull String sourceHostUrl, @NonNull String targetRootDomain,
                                        @NonNull String targetUpldHostUrl) {
        HttpUrl parsedSource = HttpUrl.parse(sourceHostUrl);
        if (parsedSource == null) {
            return;
        }
        List<Cookie> cookies = store.getCookies(parsedSource);
        for (Cookie cookie : cookies) {
            if (EhCookieStore.KEY_IGNEOUS.equals(cookie.name())) {
                continue;
            }
            String shared = cookie.name() + "=" + cookie.value()
                    + "; Domain=." + targetRootDomain
                    + "; Path=/";
            cookieManager.setCookie(EhUrl.HOST_E, shared);
            cookieManager.setCookie(targetUpldHostUrl, shared);
        }
    }

    private static void syncDirectCookies(@NonNull EhCookieStore store, @NonNull CookieManager cookieManager,
                                          @NonNull String hostUrl) {
        HttpUrl parsed = HttpUrl.parse(hostUrl);
        if (parsed == null) {
            return;
        }
        for (Cookie cookie : store.getCookies(parsed)) {
            cookieManager.setCookie(hostUrl, cookie.toString());
        }
    }

    public static boolean isEhDomainHost(@Nullable String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        String lower = host.toLowerCase(Locale.US);
        return lower.equals("e-hentai.org")
                || lower.endsWith(".e-hentai.org")
                || lower.equals("ehgt.org")
                || lower.endsWith(".ehgt.org")
                || lower.equals("exhentai.org")
                || lower.endsWith(".exhentai.org");
    }

    /**
     * 拦截 GET 请求并通过 EhViewer 的 OkHttpClient（内置 Hosts/代理/前置域名）执行，
     * 并在 text/html 渲染前注入主题化 Material CSS，避免出现白底/米黄桌面网页闪烁。
     * 对于 POST 上传请求（含 multipart 文件上传）交由 Chromium 原生处理，确保大文件分块与表单上传稳定可用。
     */
    @Nullable
    public static WebResourceResponse interceptGetWithOkHttp(@NonNull Context context,
                                                            @NonNull WebResourceRequest request) {
        if (request.getUrl() == null) {
            return null;
        }
        String url = request.getUrl().toString();
        String method = request.getMethod();
        String host = request.getUrl().getHost();

        if (!"GET".equalsIgnoreCase(method) || !isEhDomainHost(host)) {
            return null;
        }
        if (url.contains("upld.exhentai.org")) {
            return null;
        }

        try {
            OkHttpClient okHttpClient = EhApplication.getOkHttpClient(context);
            Map<String, String> headers = request.getRequestHeaders();
            Map<String, String> cleanHeaders = new HashMap<>();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!"Accept-Encoding".equalsIgnoreCase(entry.getKey())
                            && !"Cookie".equalsIgnoreCase(entry.getKey())) {
                        cleanHeaders.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            EhRequestBuilder builder = new EhRequestBuilder(cleanHeaders, url);

            // 仅当 EhCookieStore 尚未收录该 URL 的登录态时，才从 CookieManager 兜底补齐；
            // 否则交由 OkHttpClient 自带的 EhCookieStore 自动注入完整 Cookie（含 nw=1 等）
            HttpUrl parsedHttpUrl = HttpUrl.parse(url);
            if (parsedHttpUrl == null || EhApplication.getEhCookieStore(context).getCookies(parsedHttpUrl).isEmpty()) {
                String webCookie = CookieManager.getInstance().getCookie(url);
                if (!TextUtils.isEmpty(webCookie)) {
                    builder.header("Cookie", webCookie);
                }
            }

            Request okRequest = builder.build();
            Response response = okHttpClient.newCall(okRequest).execute();
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body");
            }

            String contentType = response.header("content-type", "text/html; charset=UTF-8");
            if (contentType == null) {
                contentType = "text/html; charset=UTF-8";
            }
            String[] parts = contentType.split(";");
            String mimeType = parts[0].trim().toLowerCase(Locale.US);
            String encoding = "UTF-8";
            if (parts.length > 1) {
                for (int i = 1; i < parts.length; i++) {
                    String p = parts[i].trim();
                    if (p.toLowerCase(Locale.US).startsWith("charset=")) {
                        encoding = p.substring(8).trim();
                        break;
                    }
                }
            }

            if ("text/html".equals(mimeType) && request.isForMainFrame()) {
                Charset charset;
                try {
                    charset = Charset.forName(encoding);
                } catch (Exception e) {
                    charset = StandardCharsets.UTF_8;
                    encoding = "UTF-8";
                }
                String html = new String(body.bytes(), charset);
                String adaptedHtml = injectThemeIntoHtml(context, html);
                return new WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        new ByteArrayInputStream(adaptedHtml.getBytes(StandardCharsets.UTF_8))
                );
            }

            return new WebResourceResponse(mimeType, encoding, body.byteStream());
        } catch (Throwable t) {
            Log.w(TAG, "OkHttp intercept failed for " + url + ", falling back to WebView", t);
            return null;
        }
    }

    /**
     * 路由处理：
     * - 画廊详情链接 /g/{gid}/{token}/ 直接转原生 GalleryDetailScene 打开
     * - 任何 upld.exhentai.org 链接自动纠正回完整的 upld.e-hentai.org
     * - 站内 E-Hentai 链接留在本页面加载并应用适配 UI
     * - 外站链接调系统浏览器
     */
    public static final String EXTRA_RETURN_TO_MY_UPLOADS = "eh_return_to_my_uploads";
    public static final String EXTRA_RETURN_MY_UPLOADS_URL = "eh_return_my_uploads_url";

    public static boolean handleUrlNavigation(@NonNull Activity activity, @Nullable String url,
                                              @Nullable WebView webView) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }

        if (url.contains("upld.exhentai.org/upld/")) {
            String fixed = url.replace("https://upld.exhentai.org/upld/", EhUrl.HOST_UPLD_E)
                    .replace("http://upld.exhentai.org/upld/", EhUrl.HOST_UPLD_E);
            if (webView != null) {
                webView.loadUrl(fixed);
                return true;
            }
        }

        GalleryDetailUrlParser.Result detailResult = GalleryDetailUrlParser.parse(url);
        if (detailResult != null) {
            Intent intent = new Intent(activity, MainActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(url));
            if (activity instanceof MyUploadsActivity) {
                intent.putExtra(EXTRA_RETURN_TO_MY_UPLOADS, true);
                String returnUrl = ((MyUploadsActivity) activity).getCurrentUrl();
                if (!TextUtils.isEmpty(returnUrl)) {
                    intent.putExtra(EXTRA_RETURN_MY_UPLOADS_URL, returnUrl);
                }
            }
            activity.startActivity(intent);
            return true;
        }

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            UrlOpener.openUrl(activity, url, true);
            return true;
        }

        if (isEhDomainHost(uri.getHost())) {
            return false;
        }

        UrlOpener.openUrl(activity, url, true);
        return true;
    }

    private static String injectThemeIntoHtml(@NonNull Context context, @NonNull String html) {
        String injection = "\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes\"/>\n"
                + "<style id=\"ehv-material-theme\">\n"
                + buildEhThemeCss(context)
                + "\n</style>\n";

        int headEnd = html.toLowerCase(Locale.US).indexOf("</head>");
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + injection + html.substring(headEnd);
        }
        return injection + html;
    }

    /**
     * 在页面加载完成后补充执行 DOM 轻量重排（包含 POST 提交后未经过 GET 拦截的页面）：
     * - 清除桌面固定宽高、&nbsp; 连词阻塞及 nowrap，杜绝任何左右滚动条或内容穿模出框
     * - 将多列表格（上传管理、记录、挂单列表等）重排为移动端全宽卡片
     * - 将双栏表单表格（新建/编辑画廊、设置等）重排为垂直单栏控件
     * - 完整保留并等比自适应 stats.php 统计柱状图，避免高度被清零
     */
    public static void injectMaterialAdaptationJs(@NonNull Context context, @NonNull WebView view) {
        String cssEscaped = buildEhThemeCss(context)
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", " ");

        String js = "(function(){"
                + "if(window.__ehvAdapting) return;"
                + "window.__ehvAdapting=true;"
                + "try{"
                + buildViewportAndCssJs(cssEscaped)
                + buildDomSanitizeJs()
                + buildTableAdaptationJs()
                + "}finally{window.__ehvAdapting=false;}"
                + "if(!window.__ehvObserver && document.body && window.MutationObserver){"
                + "  var timer=null;"
                + "  window.__ehvObserver=new MutationObserver(function(){"
                + "    if(window.__ehvAdapting) return;"
                + "    clearTimeout(timer);"
                + "    timer=setTimeout(function(){"
                + "      if(window.__ehvRunAdapt) window.__ehvRunAdapt();"
                + "    },120);"
                + "  });"
                + "  window.__ehvObserver.observe(document.body,{childList:true,subtree:true});"
                + "}"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    private static String buildViewportAndCssJs(@NonNull String cssEscaped) {
        return "if(document.head){"
                + "  var mv=document.querySelector('meta[name=\"viewport\"]');"
                + "  if(!mv){mv=document.createElement('meta');mv.name='viewport';document.head.appendChild(mv);}"
                + "  mv.content='width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes';"
                + "  var st=document.getElementById('ehv-material-theme');"
                + "  if(!st){st=document.createElement('style');st.id='ehv-material-theme';document.head.appendChild(st);}"
                + "  st.textContent='" + cssEscaped + "';"
                + "}"
                + "window.popUp=function(u){if(u){window.location.href=u;}return false;};"
                + "window.open=function(u){if(u){window.location.href=u;}return null;};";
    }

    private static String buildDomSanitizeJs() {
        return "function sanitizeDom(){"
                + "  if(!document.body) return;"
                + "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
                + "  var n;"
                + "  while((n=w.nextNode())){"
                + "    if(!n.parentElement) continue;"
                + "    var tag=n.parentElement.tagName;"
                + "    if(tag==='SCRIPT'||tag==='STYLE'||tag==='PRE'||tag==='CODE') continue;"
                + "    if(n.nodeValue && n.nodeValue.indexOf('\\u00A0')>=0){"
                + "      n.nodeValue=n.nodeValue.replace(/\\u00A0+/g,' ');"
                + "    }"
                + "  }"
                + "  var els=document.body.querySelectorAll('div,form,fieldset,center,table,colgroup,col,tr,td,th,p,span,input,select,textarea,ul,ol');"
                + "  for(var i=0;i<els.length;i++){"
                + "    var el=els[i];"
                + "    if(el.closest && el.closest('.ehv-chart-bars-td')) continue;"
                + "    if(el.hasAttribute('width') && el.tagName!=='IMG') el.removeAttribute('width');"
                + "    if(el.style){"
                + "      el.style.setProperty('min-width','0','important');"
                + "      if(el.style.whiteSpace==='nowrap') el.style.setProperty('white-space','normal','important');"
                + "      var sw=el.style.width;"
                + "      if(sw && sw.indexOf('px')>0 && parseFloat(sw)>120 && el.type!=='checkbox' && el.type!=='radio'){"
                + "        el.style.removeProperty('width');"
                + "      }"
                + "      if(el.style.minWidth && el.style.minWidth.indexOf('px')>0) el.style.setProperty('min-width','0','important');"
                + "      if(el.style.marginLeft && parseFloat(el.style.marginLeft)<0) el.style.setProperty('margin-left','0','important');"
                + "      if(el.style.marginRight && parseFloat(el.style.marginRight)<0) el.style.setProperty('margin-right','0','important');"
                + "      if(el.style.left && parseFloat(el.style.left)<0) el.style.setProperty('left','0','important');"
                + "    }"
                + "    if(el.tagName==='INPUT' && (el.type==='text'||el.type==='number'||el.type==='password')){"
                + "      if(el.size && el.size>10) el.size=10;"
                + "    }"
                + "  }"
                + "}"
                + "sanitizeDom();";
    }

    private static String buildTableAdaptationJs() {
        return "function directRows(t){"
                + "  var out=[];"
                + "  var all=t.querySelectorAll('tr');"
                + "  for(var i=0;i<all.length;i++){"
                + "    if(all[i].closest('table')===t) out.push(all[i]);"
                + "  }"
                + "  return out;"
                + "}"
                + "function directCells(tr){"
                + "  var out=[];"
                + "  for(var i=0;i<tr.children.length;i++){"
                + "    var c=tr.children[i];"
                + "    if(c.tagName==='TD'||c.tagName==='TH') out.push(c);"
                + "  }"
                + "  return out;"
                + "}"
                + "function isChartTable(t,rows){"
                + "  var onStats=(location.pathname||'').indexOf('stats')>=0;"
                + "  for(var r=0;r<rows.length;r++){"
                + "    var cells=directCells(rows[r]);"
                + "    if(cells.length>=2){"
                + "      var imgsWithBar=0;"
                + "      for(var c=0;c<cells.length;c++){"
                + "        if(cells[c].querySelector('a[href*=\"/g/\"],a[href*=\"managegallery\"]')) continue;"
                + "        var imgs=cells[c].querySelectorAll('img');"
                + "        if(imgs.length>0){"
                + "          if(onStats && !cells[c].textContent.trim()){ imgsWithBar++; continue; }"
                + "          for(var k=0;k<imgs.length;k++){"
                + "            var hAttr=parseFloat(imgs[k].getAttribute('height')||imgs[k].style.height||0);"
                + "            var wAttr=parseFloat(imgs[k].getAttribute('width')||imgs[k].naturalWidth||0);"
                + "            if(hAttr>0 || (wAttr>0 && wAttr<=40)) { imgsWithBar++; break; }"
                + "          }"
                + "        }else{"
                + "          var divBar=cells[c].querySelector('div[style*=\"height\"]');"
                + "          if(divBar && !cells[c].textContent.trim()) imgsWithBar++;"
                + "        }"
                + "      }"
                + "      if(imgsWithBar>=2) return true;"
                + "    }"
                + "  }"
                + "  return false;"
                + "}"
                + "function adaptChartTable(t,rows){"
                + "  t.classList.add('ehv-chart-table');"
                + "  var maxCols=1;"
                + "  var barImgs=[];"
                + "  var barDivs=[];"
                + "  for(var r=0;r<rows.length;r++){"
                + "    var cells=directCells(rows[r]);"
                + "    if(cells.length>maxCols) maxCols=cells.length;"
                + "    var rowBars=[];"
                + "    for(var c=0;c<cells.length;c++){"
                + "      var imgs=cells[c].querySelectorAll('img');"
                + "      var barDiv=cells[c].querySelector('div[style*=\"height\"]');"
                + "      if(imgs.length>0 || (barDiv && !cells[c].textContent.trim())) rowBars.push(cells[c]);"
                + "    }"
                + "    if(rowBars.length>=2 && cells.length>=2){"
                + "      for(var c=0;c<cells.length;c++){"
                + "        var td=cells[c];"
                + "        td.classList.add('ehv-chart-bars-td');"
                + "        td.style.setProperty('vertical-align','bottom','important');"
                + "        td.style.setProperty('text-align','center','important');"
                + "        td.style.setProperty('white-space','nowrap','important');"
                + "        td.style.setProperty('padding','6px 1px 0 1px','important');"
                + "        td.style.setProperty('background','transparent','important');"
                + "        td.style.setProperty('height','156px','important');"
        + "        var imgs=td.querySelectorAll('img');"
                + "        for(var k=0;k<imgs.length;k++) barImgs.push(imgs[k]);"
                + "        var divs=td.querySelectorAll('div[style*=\"height\"]');"
                + "        for(var k=0;k<divs.length;k++){"
                + "          if(divs[k].children.length===0 && !divs[k].textContent.trim()) barDivs.push(divs[k]);"
                + "        }"
                + "      }"
                + "    }"
                + "  }"
                + "  if(maxCols>=10) t.classList.add('ehv-chart-dense');"
                + "  var maxH=0;"
                + "  for(var i=0;i<barImgs.length;i++){"
                + "    var hAttr=barImgs[i].getAttribute('data-ehv-h')||barImgs[i].getAttribute('height')||barImgs[i].style.height||'';"
                + "    var rawH=(typeof hAttr==='string' && hAttr.indexOf('%')>=0)?0:parseFloat(hAttr||0);"
                + "    if(!barImgs[i].getAttribute('data-ehv-h') && rawH>0) barImgs[i].setAttribute('data-ehv-h',String(rawH));"
                + "    if(rawH>maxH) maxH=rawH;"
                + "  }"
                + "  for(var i=0;i<barDivs.length;i++){"
                + "    var dAttr=barDivs[i].getAttribute('data-ehv-h')||barDivs[i].style.height||'';"
                + "    var rawDH=(typeof dAttr==='string' && dAttr.indexOf('%')>=0)?0:parseFloat(dAttr||0);"
                + "    if(!barDivs[i].getAttribute('data-ehv-h') && rawDH>0) barDivs[i].setAttribute('data-ehv-h',String(rawDH));"
                + "    if(rawDH>maxH) maxH=rawDH;"
                + "  }"
                + "  if(maxH===0 && barImgs.length>0){"
                + "    function parseCompactNum(s){"
                + "      if(!s) return 0;"
                + "      var m=s.replace(/,/g,'').match(/([\\d.]+)\\s*([KkMm])?/);"
                + "      if(!m) return 0;"
                + "      var v=parseFloat(m[1]);"
                + "      if(m[2]==='k'||m[2]==='K') v*=1000;"
                + "      else if(m[2]==='m'||m[2]==='M') v*=1000000;"
                + "      return isNaN(v)?0:v;"
                + "    }"
                + "    var numRows=[];"
                + "    for(var r=0;r<rows.length;r++){"
                + "      var cs=directCells(rows[r]);"
                + "      if(cs.length>=2 && !rows[r].querySelector('.ehv-chart-bars-td')){"
                + "        var nums=0;"
                + "        for(var c=0;c<cs.length;c++){ if(/^[\\d.,]+[KkMm]?$/.test(cs[c].textContent.trim())) nums++; }"
                + "        if(nums>=Math.min(2,cs.length)) numRows.push(cs);"
                + "      }"
                + "    }"
                + "    var barCells=t.querySelectorAll('.ehv-chart-bars-td');"
                + "    var maxV=0;"
                + "    var colVals=[];"
                + "    for(var c=0;c<barCells.length;c++){"
                + "      var arr=[];"
                + "      for(var nr=0;nr<numRows.length;nr++){"
                + "        var val=numRows[nr][c]?parseCompactNum(numRows[nr][c].textContent.trim()):0;"
                + "        arr.push(val); if(val>maxV) maxV=val;"
                + "      }"
                + "      colVals.push(arr);"
                + "    }"
                + "    if(maxV>0){"
                + "      for(var c=0;c<barCells.length;c++){"
                + "        var cImgs=barCells[c].querySelectorAll('img');"
                + "        var vals=colVals[c];"
                + "        for(var k=0;k<cImgs.length;k++){"
                + "          var v=(vals && k<vals.length)?vals[k]:(vals&&vals.length?vals[vals.length-1]:0);"
                + "          var fh=Math.max(4,Math.round(v*136/maxV));"
                + "          cImgs[k].setAttribute('data-ehv-h',String(fh));"
                + "          if(fh>maxH) maxH=fh;"
                + "        }"
                + "      }"
                + "    }"
                + "  }"
                + "  var scale=(maxH>140)?(140/maxH):1;"
                + "  for(var i=0;i<barImgs.length;i++){"
                + "    var im=barImgs[i];"
                + "    var h0=parseFloat(im.getAttribute('data-ehv-h')||0);"
                + "    if(h0>0){"
                + "      var sh=Math.max(3,Math.round(h0*scale));"
                + "      im.style.setProperty('height',sh+'px','important');"
                + "    }else{"
                + "      im.style.removeProperty('height');"
                + "    }"
                + "    im.style.setProperty('width',maxCols>=16?'45%':'42%','important');"
                + "    im.style.setProperty('max-width','14px','important');"
                + "    im.style.setProperty('min-width','2px','important');"
                + "    im.style.setProperty('display','inline-block','important');"
                + "    im.style.setProperty('vertical-align','bottom','important');"
                + "    im.style.setProperty('margin',maxCols>=16?'0':'0 1px','important');"
                + "    im.style.setProperty('border-radius','2px 2px 0 0','important');"
                + "  }"
                + "  for(var i=0;i<barDivs.length;i++){"
                + "    var dv=barDivs[i];"
                + "    var dh0=parseFloat(dv.getAttribute('data-ehv-h')||0);"
                + "    if(dh0>0){"
                + "      var dsh=Math.max(3,Math.round(dh0*scale));"
                + "      dv.style.setProperty('height',dsh+'px','important');"
                + "    }"
                + "    dv.style.setProperty('width',maxCols>=16?'45%':'42%','important');"
                + "    dv.style.setProperty('max-width','14px','important');"
                + "    dv.style.setProperty('min-width','2px','important');"
                + "    dv.style.setProperty('display','inline-block','important');"
                + "    dv.style.setProperty('vertical-align','bottom','important');"
                + "    dv.style.setProperty('margin',maxCols>=16?'0':'0 1px','important');"
                + "    dv.style.setProperty('border-radius','2px 2px 0 0','important');"
                + "  }"
                + "}"
                + "function adaptCardTable(t,rows){"
                + "  t.classList.add('ehv-card-table');"
                + "  var headers=[];"
                + "  var headerRow=rows[0];"
                + "  var hCells=directCells(headerRow);"
                + "  var isHeader=false;"
                + "  var hasSortLink=false;"
                + "  for(var i=0;i<hCells.length;i++){"
                + "    var txt=hCells[i].textContent.trim();"
                + "    headers.push(txt);"
                + "    if(hCells[i].tagName==='TH') isHeader=true;"
                + "    if(hCells[i].querySelector('a')){"
                + "      hasSortLink=true;"
                + "    }else{"
                + "      hCells[i].classList.add('ehv-hidden-th');"
                + "    }"
                + "  }"
                + "  if(isHeader){"
                + "    if(hasSortLink){"
                + "      headerRow.classList.add('ehv-header-chips-row');"
                + "    }else{"
                + "      headerRow.classList.add('ehv-hidden-header');"
                + "    }"
                + "  }"
                + "  var startIdx=isHeader?1:0;"
                + "  for(var r=startIdx;r<rows.length;r++){"
                + "    var row=rows[r];"
                + "    row.classList.add('ehv-card-row');"
                + "    var cells=directCells(row);"
                + "    for(var c=0;c<cells.length;c++){"
                + "      var td=cells[c];"
                + "      var txt=td.textContent.trim();"
                + "      var hasCheck=!!td.querySelector('input[type=\"checkbox\"],input[type=\"radio\"]');"
                + "      var links=td.querySelectorAll('a');"
                + "      if(!txt && !hasCheck && !td.querySelector('img') && links.length===0){"
                + "        td.classList.add('ehv-cell-empty'); continue;"
                + "      }"
                + "      if(isHeader && headers[c] && !td.getAttribute('data-label')){"
                + "        td.setAttribute('data-label',headers[c]);"
                + "      }"
                + "      var isTitle=!!td.querySelector('a[href*=\"/g/\"],a[href*=\"managegallery\"],img');"
                + "      if(hasCheck && txt.length<16){"
                + "        td.classList.add('ehv-cell-check');"
                + "      }else if(links.length>=2){"
                + "        td.classList.add('ehv-cell-block','ehv-actions-row');"
                + "        for(var k=0;k<td.childNodes.length;k++){"
                + "          if(td.childNodes[k].nodeType===3) td.childNodes[k].nodeValue=td.childNodes[k].nodeValue.replace(/[\\[\\]]/g,'');"
                + "        }"
                + "      }else if(isTitle || txt.length>28){"
                + "        td.classList.add('ehv-cell-block');"
                + "      }else{"
                + "        td.classList.add('ehv-cell-kv');"
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "function adaptAllTables(){"
                + "  var tables=document.querySelectorAll('table');"
                + "  for(var i=0;i<tables.length;i++){"
                + "    var t=tables[i];"
                + "    var rows=directRows(t);"
                + "    if(rows.length===0) continue;"
                + "    var maxCols=0;"
                + "    for(var r=0;r<rows.length;r++){"
                + "      var len=directCells(rows[r]).length;"
                + "      if(len>maxCols) maxCols=len;"
                + "    }"
                + "    if(isChartTable(t,rows)){"
                + "      adaptChartTable(t,rows);"
                + "      continue;"
                + "    }"
                + "    var hasNested=!!t.querySelector('table');"
                + "    var hasForm=!!t.querySelector('input[type=\"text\"],input[type=\"number\"],input[type=\"password\"],input[type=\"file\"],select,textarea');"
                + "    if(hasNested || (hasForm && maxCols<=4)){"
                + "      t.classList.add('ehv-stack-table');"
                + "    }else if(maxCols>=3 && rows.length>=2){"
                + "      adaptCardTable(t,rows);"
                + "    }"
                + "  }"
                + "  var allTabs=document.querySelectorAll('table');"
                + "  for(var i=0;i<allTabs.length;i++){"
                + "    var tb=allTabs[i];"
                + "    if(tb.classList.contains('ehv-chart-table')||tb.classList.contains('ehv-card-table')||tb.classList.contains('ehv-stack-table')) continue;"
                + "    var p=tb.parentElement;"
                + "    if(p && tb.scrollWidth>p.clientWidth+2){ tb.classList.add('ehv-stack-table'); }"
                + "  }"
                + "}"
                + "window.__ehvRunAdapt=function(){"
                + "  window.__ehvAdapting=true;"
                + "  try{sanitizeDom();adaptAllTables();}finally{window.__ehvAdapting=false;}"
                + "};"
                + "adaptAllTables();";
    }

    @NonNull
    public static String buildEhThemeCss(@NonNull Context context) {
        int theme = Settings.getTheme();
        boolean isDark = (theme == Settings.THEME_DARK || theme == Settings.THEME_BLACK);

        String bg = toCssHex(resolveThemeColor(context, android.R.attr.windowBackground,
                theme == Settings.THEME_BLACK ? Color.BLACK : (isDark ? 0xFF212121 : 0xFFFAFAFA)));
        String cardBg = toCssHex(resolveThemeColor(context, R.attr.contentColorPrimary,
                theme == Settings.THEME_BLACK ? 0xFF161616 : (isDark ? 0xFF303030 : Color.WHITE)));
        String cardAlt = theme == Settings.THEME_BLACK ? "#202020" : (isDark ? "#383838" : "#F5F5F5");
        String border = toCssHex(resolveThemeColor(context, R.attr.dividerColor,
                theme == Settings.THEME_BLACK ? 0xFF2C2C2C : (isDark ? 0xFF454545 : 0xFFE0E0E0)));
        String textPrimary = toCssHex(resolveThemeColor(context, android.R.attr.textColorPrimary,
                isDark ? 0xFFEEEEEE : 0xFF212121));
        String accent = toCssHex(resolveThemeColor(context, R.attr.widgetColorThemeAccent,
                isDark ? 0xFFF06292 : 0xFFE91E63));
        String linkColor = isDark ? "#80CBC4" : accent;
        String btnBg = theme == Settings.THEME_BLACK ? "#242424" : (isDark ? "#424242" : "#EEEEEE");
        String btnText = isDark ? "#FFFFFF" : "#212121";
        String inputBg = theme == Settings.THEME_BLACK ? "#1F1F1F" : (isDark ? "#282828" : "#FFFFFF");

        return buildBaseThemeCss(bg, cardBg, cardAlt, border, textPrimary, accent, linkColor, btnBg, btnText, inputBg)
                + buildTableAndChartCss(cardBg, cardAlt, border, accent);
    }

    private static String buildBaseThemeCss(String bg, String cardBg, String cardAlt, String border,
                                            String textPrimary, String accent, String linkColor,
                                            String btnBg, String btnText, String inputBg) {
        return ":root{--ehv-bg:" + bg + ";--ehv-card:" + cardBg + ";--ehv-alt:" + cardAlt
                + ";--ehv-border:" + border + ";--ehv-text:" + textPrimary
                + ";--ehv-accent:" + accent + ";--ehv-link:" + linkColor + ";}"
                + "*,*::before,*::after{box-sizing:border-box !important;max-width:100% !important;}"
                + "html,body{width:100% !important;max-width:100% !important;min-width:0 !important;"
                + "background:" + bg + " !important;color:" + textPrimary + " !important;"
                + "margin:0 !important;padding:8px 8px 84px 8px !important;text-align:left !important;"
                + "font-family:-apple-system,Roboto,sans-serif !important;"
                + "font-size:14px !important;line-height:1.5 !important;-webkit-text-size-adjust:100% !important;"
                + "overflow-x:hidden !important;}"
                + "#nb,#lb,#toppane,.dp,ins,[id*='juicy'],div.cl{display:none !important;}"
                + "div,form,fieldset,center,header,footer,main,section,p,ul,ol,li,dl,pre,blockquote{"
                + "min-width:0 !important;max-width:100% !important;float:none !important;"
                + "margin-left:0 !important;margin-right:0 !important;}"
                + ".stuffbox,.homebox,.ido,.idi{"
                + "width:100% !important;max-width:100% !important;min-width:0 !important;"
                + "margin:8px 0 !important;padding:12px !important;overflow:hidden !important;"
                + "background:" + cardBg + " !important;border:1px solid " + border + " !important;"
                + "border-radius:12px !important;box-shadow:0 2px 6px rgba(0,0,0,0.12) !important;}"
                + ".stuffbox .homebox,.stuffbox .ido,.homebox .homebox{"
                + "margin:8px 0 !important;padding:10px !important;box-shadow:none !important;}"
                + "div[style*='#E3E0D1'],div[style*='#EDEBDF'],div[style*='#F2EFDF'],"
                + "tr[style*='#E3E0D1'],tr[style*='#EDEBDF'],tr[style*='#F2EFDF']{"
                + "background:" + cardBg + " !important;background-color:" + cardBg + " !important;}"
                + "body,div,span,p,a,b,strong,label,font,center,td,th,li,h1,h2,h3{"
                + "white-space:normal !important;overflow-wrap:anywhere !important;word-break:break-word !important;}"
                + "h1,h2,h3,p,td,th,div,span,label{color:" + textPrimary + " !important;}"
                + "h1,h2{font-size:15px !important;font-weight:700 !important;margin:8px 0 !important;"
                + "color:" + accent + " !important;text-align:left !important;}"
                + "a{color:" + linkColor + " !important;text-decoration:none !important;}"
                + "img{max-width:100% !important;height:auto !important;border-radius:6px !important;}"
                + "input[type='submit'],input[type='button'],button{"
                + "background:" + accent + " !important;color:#FFFFFF !important;border:none !important;"
                + "border-radius:8px !important;min-height:36px !important;padding:6px 14px !important;"
                + "font-size:13px !important;font-weight:600 !important;cursor:pointer !important;margin:4px 2px !important;"
                + "max-width:100% !important;}"
                + "input[name*='delete'],input[value*='Delete'],button[value*='Delete']{background:#D32F2F !important;color:#FFFFFF !important;}"
                + "input[type='text'],input[type='number'],input[type='password'],select,textarea{"
                + "background:" + inputBg + " !important;color:" + textPrimary + " !important;"
                + "border:1px solid " + border + " !important;border-radius:8px !important;"
                + "min-height:36px !important;padding:6px 10px !important;font-size:13px !important;"
                + "max-width:100% !important;min-width:0 !important;width:auto !important;margin:3px 2px !important;}"
                + "select,textarea{width:100% !important;}"
                + "input[type='checkbox'],input[type='radio']{width:18px !important;height:18px !important;"
                + "accent-color:" + accent + " !important;vertical-align:middle !important;margin-right:6px !important;}"
                + "input[type='file']{width:100% !important;padding:10px !important;background:" + cardAlt + " !important;"
                + "border:1px dashed " + accent + " !important;border-radius:8px !important;}"
                + ".stdbtn{background:" + btnBg + " !important;color:" + btnText + " !important;border:1px solid " + border + " !important;}"
                + "hr{border:none !important;border-top:1px solid " + border + " !important;margin:10px 0 !important;}";
    }

    private static String buildTableAndChartCss(String cardBg, String cardAlt, String border, String accent) {
        return "table{border-collapse:collapse !important;width:100% !important;max-width:100% !important;"
                + "table-layout:fixed !important;margin:6px 0 !important;}"
                + "th{background:" + cardAlt + " !important;font-weight:600 !important;padding:6px 4px !important;"
                + "border-bottom:1px solid " + border + " !important;font-size:12px !important;}"
                + "td{padding:6px 4px !important;border-bottom:1px solid " + border + " !important;font-size:13px !important;"
                + "vertical-align:middle !important;}"
                + "tr:last-child>td{border-bottom:none !important;}"
                + "table.ehv-stack-table,table.ehv-stack-table>tbody,table.ehv-stack-table>tbody>tr,table.ehv-stack-table>tr{"
                + "display:block !important;width:100% !important;}"
                + "table.ehv-stack-table>tbody>tr,table.ehv-stack-table>tr{"
                + "border-bottom:1px solid " + border + " !important;padding:6px 0 !important;}"
                + "table.ehv-stack-table>tbody>tr:last-child,table.ehv-stack-table>tr:last-child{border-bottom:none !important;}"
                + "table.ehv-stack-table>tbody>tr>td,table.ehv-stack-table>tr>td,"
                + "table.ehv-stack-table>tbody>tr>th,table.ehv-stack-table>tr>th{"
                + "display:block !important;width:100% !important;padding:4px 0 !important;border:none !important;text-align:left !important;}"
                + "table.ehv-stack-table input[type='text'],table.ehv-stack-table input[type='number'],"
                + "table.ehv-stack-table input[type='password'],table.ehv-stack-table select,table.ehv-stack-table textarea{"
                + "width:100% !important;display:block !important;}"
                + "table.ehv-card-table,table.ehv-card-table>tbody{display:block !important;width:100% !important;border:none !important;}"
                + "tr.ehv-hidden-header{display:none !important;}"
                + "tr.ehv-header-chips-row{display:flex !important;flex-wrap:wrap !important;gap:6px !important;"
                + "width:100% !important;padding:4px 0 8px 0 !important;border:none !important;}"
                + "tr.ehv-header-chips-row>th{display:inline-flex !important;align-items:center !important;width:auto !important;"
                + "padding:4px 10px !important;border:1px solid " + border + " !important;border-radius:16px !important;"
                + "background:" + cardAlt + " !important;font-size:12px !important;}"
                + "tr.ehv-header-chips-row>th.ehv-hidden-th{display:none !important;}"
                + "tr.ehv-card-row img{max-width:96px !important;max-height:136px !important;width:auto !important;height:auto !important;}"
                + "tr.ehv-card-row{display:block !important;width:100% !important;margin:0 0 10px 0 !important;"
                + "padding:10px 12px !important;background:" + cardBg + " !important;border:1px solid " + border + " !important;"
                + "border-radius:10px !important;overflow:hidden !important;}"
                + "tr.ehv-card-row>td.ehv-cell-empty{display:none !important;}"
                + "tr.ehv-card-row>td.ehv-cell-check{display:flex !important;align-items:center !important;gap:6px !important;"
                + "width:100% !important;padding:2px 0 !important;border:none !important;}"
                + "tr.ehv-card-row>td.ehv-cell-block{display:block !important;width:100% !important;padding:4px 0 !important;"
                + "border:none !important;text-align:left !important;font-weight:500 !important;}"
                + "tr.ehv-card-row>td.ehv-cell-kv{display:flex !important;justify-content:space-between !important;"
                + "align-items:center !important;gap:8px !important;width:100% !important;padding:3px 0 !important;"
                + "border-bottom:1px dashed " + border + " !important;font-size:12.5px !important;}"
                + "tr.ehv-card-row>td.ehv-cell-kv:last-child{border-bottom:none !important;}"
                + "tr.ehv-card-row>td.ehv-cell-kv[data-label]::before{content:attr(data-label);opacity:0.7;font-size:12px;flex-shrink:0;}"
                + "td.ehv-actions-row a{display:inline-block !important;padding:4px 10px !important;margin:3px 4px 3px 0 !important;"
                + "border-radius:14px !important;border:1px solid " + border + " !important;background:" + cardAlt + " !important;"
                + "font-size:12px !important;font-weight:600 !important;}"
                + "table.ehv-chart-table{width:100% !important;table-layout:fixed !important;margin:6px 0 !important;border-collapse:collapse !important;}"
                + "table.ehv-chart-table th,table.ehv-chart-table td{"
                + "padding:4px 1px !important;font-size:10px !important;line-height:1.25 !important;text-align:center !important;"
                + "border:1px solid " + border + " !important;white-space:normal !important;}"
                + "table.ehv-chart-table.ehv-chart-dense th,table.ehv-chart-table.ehv-chart-dense td{"
                + "padding:3px 0 !important;font-size:8.5px !important;}"
                + "table.ehv-chart-table td.ehv-chart-bars-td{"
                + "vertical-align:bottom !important;white-space:nowrap !important;padding:6px 1px 0 1px !important;"
                + "height:156px !important;background:transparent !important;border-bottom:2px solid " + accent + " !important;}"
                + "table.ehv-chart-table td.ehv-chart-bars-td img{"
                + "height:revert !important;max-height:146px !important;min-width:2px !important;max-width:44% !important;"
                + "display:inline-block !important;vertical-align:bottom !important;border-radius:2px 2px 0 0 !important;}";
    }

    @Nullable
    public static Uri[] parseFileChooserResult(int resultCode, @Nullable Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return null;
        }
        ClipData clipData = data.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            List<Uri> uriList = new ArrayList<>(clipData.getItemCount());
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri u = clipData.getItemAt(i).getUri();
                if (u != null) {
                    uriList.add(u);
                }
            }
            return uriList.toArray(new Uri[0]);
        } else if (data.getData() != null) {
            return new Uri[]{data.getData()};
        }
        return null;
    }

    public static Intent buildFileChooserIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return intent;
    }

    public static void handleFileChooser(@NonNull Activity activity, int requestCode,
                                         @Nullable ValueCallback<Uri[]> oldCallback,
                                         @NonNull ValueCallback<Uri[]> newCallback,
                                         @NonNull Consumer<ValueCallback<Uri[]>> holderSetter) {
        if (oldCallback != null) {
            oldCallback.onReceiveValue(null);
        }
        holderSetter.accept(newCallback);
        try {
            activity.startActivityForResult(
                    Intent.createChooser(buildFileChooserIntent(), activity.getString(R.string.my_uploads_choose_files)),
                    requestCode
            );
        } catch (Throwable t) {
            Log.w(TAG, "ACTION_OPEN_DOCUMENT failed, trying ACTION_GET_CONTENT", t);
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                activity.startActivityForResult(
                        Intent.createChooser(fallback, activity.getString(R.string.my_uploads_choose_files)),
                        requestCode
                );
            } catch (Throwable t2) {
                Log.e(TAG, "File chooser failed", t2);
                holderSetter.accept(null);
                newCallback.onReceiveValue(null);
                Toast.makeText(activity, R.string.my_uploads_file_chooser_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }
}
