package com.hippo.ehviewer.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.EhStatsDetail;
import com.hippo.ehviewer.client.data.EhStatsDetail.RankingItem;
import com.hippo.ehviewer.client.data.EhStatsDetail.StatsEntry;
import com.hippo.ehviewer.client.data.EhStatsDetail.StatsPeriod;
import com.hippo.ehviewer.client.parser.EhStatsParser;
import com.hippo.ehviewer.widget.StatsBarChartView;
import com.hippo.util.ExceptionUtils;
import com.hippo.widget.ProgressView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生统计视图控制器（用于 MyHomeActivity「我的统计」与 MyUploadsActivity「观看统计」）。
 * 依据数据密度自适应：
 * - <= 12 条（如 Last 12 Months / Yearly Stats）：展示清晰的原生 Canvas 双柱图 + 水平对比卡片；
 * - > 12 条（如 30 天 Daily Stats）：放弃拥挤的竖向柱图，改用「最新优先」可排序水平双色进度条与数值卡片。
 */
public class NativeStatsController {

    private static final String TAG = "NativeStatsController";
    private static final int CHART_COLUMN_THRESHOLD = 12;

    public interface Callback {
        void onFallbackToWeb(@NonNull String url);

        void onOpenGalleryOrLink(@NonNull String url);
    }

    private final Activity activity;
    private final NestedScrollView scrollView;
    private final LinearLayout rootContainer;
    @Nullable
    private final ProgressView progressView;
    private final Callback callback;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestSeq = new AtomicInteger(0);

    @NonNull
    private String currentUrl = EhUrl.URL_STATS_E;
    @Nullable
    private EhStatsDetail currentDetail;
    private boolean reverseDensePeriods = true;
    private boolean destroyed = false;

    public NativeStatsController(@NonNull Activity activity,
                                 @NonNull NestedScrollView scrollView,
                                 @NonNull LinearLayout rootContainer,
                                 @Nullable ProgressView progressView,
                                 @NonNull Callback callback) {
        this.activity = activity;
        this.scrollView = scrollView;
        this.rootContainer = rootContainer;
        this.progressView = progressView;
        this.callback = callback;
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
                EhStatsDetail parsed = EhStatsParser.parse(res.html, res.url);
                mainHandler.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed() || seq != requestSeq.get()) {
                        return;
                    }
                    if (progressView != null) {
                        progressView.setVisibility(View.GONE);
                    }
                    currentDetail = parsed;
                    currentUrl = res.url;
                    renderStats(parsed);
                });
            } catch (Throwable t) {
                ExceptionUtils.throwIfFatal(t);
                Log.w(TAG, "Failed to load native stats: " + url, t);
                mainHandler.post(() -> {
                    if (destroyed || activity.isFinishing() || activity.isDestroyed() || seq != requestSeq.get()) {
                        return;
                    }
                    if (progressView != null) {
                        progressView.setVisibility(View.GONE);
                    }
                    renderError(ExceptionUtils.getReadableString(t));
                });
            }
        });
    }

    public void destroy() {
        destroyed = true;
        requestSeq.incrementAndGet();
        executor.shutdownNow();
    }

    private void renderError(@NonNull String message) {
        rootContainer.removeAllViews();
        CardView card = createCard();
        LinearLayout box = createCardInner();

        TextView title = makeHeading(activity.getString(R.string.my_stats_load_failed));
        TextView detail = makeSecondaryText(message);
        detail.setPadding(0, dp(4), 0, dp(12));

        LinearLayout btns = new LinearLayout(activity);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        MaterialButton retry = createOutlinedButton(activity.getString(R.string.refresh));
        retry.setOnClickListener(v -> loadUrl(currentUrl));

        MaterialButton webBtn = createFilledButton(activity.getString(R.string.my_stats_open_web));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(8));
        webBtn.setLayoutParams(lp);
        webBtn.setOnClickListener(v -> callback.onFallbackToWeb(currentUrl));

        retry.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btns.addView(retry);
        btns.addView(webBtn);

        box.addView(title);
        box.addView(detail);
        box.addView(btns);
        card.addView(box);
        rootContainer.addView(card);
    }

    private void renderStats(@NonNull EhStatsDetail detail) {
        rootContainer.removeAllViews();
        if (detail.isEmpty()) {
            renderEmptyWithWebFallback();
            return;
        }

        renderHeroCard(detail);
        for (StatsPeriod period : detail.periods) {
            renderPeriodCard(period);
        }
        if (!detail.extraItems.isEmpty()) {
            renderExtraItemsCard(detail.extraItems);
        }
        scrollView.scrollTo(0, 0);
    }

    private void renderEmptyWithWebFallback() {
        CardView card = createCard();
        LinearLayout box = createCardInner();
        TextView tv = makeSecondaryText(activity.getString(R.string.my_stats_empty));
        tv.setPadding(0, 0, 0, dp(10));
        MaterialButton webBtn = createOutlinedButton(activity.getString(R.string.my_stats_open_web));
        webBtn.setOnClickListener(v -> callback.onFallbackToWeb(currentUrl));
        box.addView(tv);
        box.addView(webBtn);
        card.addView(box);
        rootContainer.addView(card);
    }

    private void renderHeroCard(@NonNull EhStatsDetail detail) {
        CardView card = createCard();
        LinearLayout box = createCardInner();

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        String titleStr = !detail.headline.isEmpty() ? detail.headline : activity.getString(R.string.my_uploads_stats);
        TextView title = makeHeading(titleStr);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(title);

        MaterialButton webBtn = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        webBtn.setText(R.string.my_uploads_web_mode);
        webBtn.setAllCaps(false);
        webBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        webBtn.setMinHeight(dp(32));
        webBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        webBtn.setTextColor(EhWebPageHelper.resolveThemeColor(activity, android.R.attr.textColorPrimary, Color.BLACK));
        webBtn.setOnClickListener(v -> callback.onFallbackToWeb(currentUrl));
        topRow.addView(webBtn);
        box.addView(topRow);

        if (!detail.totalVisitsNumberText.isEmpty()) {
            TextView bigNum = new TextView(activity);
            bigNum.setText(detail.totalVisitsNumberText);
            bigNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            bigNum.setTypeface(null, Typeface.BOLD);
            bigNum.setTextColor(getAccentColor());
            bigNum.setPadding(0, dp(4), 0, dp(2));
            box.addView(bigNum);
        }

        if (!detail.totalVisitsSummary.isEmpty()) {
            TextView summary = makeSecondaryText(detail.totalVisitsSummary);
            box.addView(summary);
        }

        if (!TextUtils.isEmpty(detail.backToAllStatsUrl)) {
            MaterialButton backAll = createOutlinedButton(activity.getString(R.string.my_stats_back_all));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            backAll.setLayoutParams(lp);
            backAll.setOnClickListener(v -> loadUrl(detail.backToAllStatsUrl));
            box.addView(backAll);
        }

        card.addView(box);
        rootContainer.addView(card);
    }

    private void renderPeriodCard(@NonNull StatsPeriod period) {
        CardView card = createCard();
        LinearLayout box = createCardInner();

        // 标题栏 +（若为 30 天等高密度数据则显示排序切换）
        boolean isDense = period.entries.size() > CHART_COLUMN_THRESHOLD;
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView heading = makeHeading(localizePeriodTitle(period.title));
        heading.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        headerRow.addView(heading);

        if (isDense) {
            MaterialButton sortToggle = createOutlinedButton(activity.getString(
                    reverseDensePeriods ? R.string.my_stats_sort_desc : R.string.my_stats_sort_asc));
            sortToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            sortToggle.setMinHeight(dp(30));
            sortToggle.setPadding(dp(10), dp(2), dp(10), dp(2));
            sortToggle.setOnClickListener(v -> {
                reverseDensePeriods = !reverseDensePeriods;
                if (currentDetail != null) {
                    renderStats(currentDetail);
                }
            });
            headerRow.addView(sortToggle);
        }
        box.addView(headerRow);

        // 3 格汇总 KPI Bar
        box.addView(buildKpiRow(period));

        if (!isDense) {
            renderChartAndTableSection(box, period);
        } else {
            renderDenseDataListSection(box, period);
        }

        card.addView(box);
        rootContainer.addView(card);
    }

    private View buildKpiRow(@NonNull StatsPeriod period) {
        LinearLayout kpiRow = new LinearLayout(activity);
        kpiRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams kpiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kpiLp.topMargin = dp(8);
        kpiLp.bottomMargin = dp(10);
        kpiRow.setLayoutParams(kpiLp);

        StatsEntry peak = period.getPeakEntry();
        String peakStr = peak != null ? (peak.label + " · " + peak.hitsText) : "--";

        kpiRow.addView(buildKpiTile(activity.getString(R.string.my_stats_total_visits),
                EhStatsDetail.formatCompact(period.getTotalVisits()), getVisitsColor()));
        kpiRow.addView(buildKpiTile(activity.getString(R.string.my_stats_total_hits),
                EhStatsDetail.formatCompact(period.getTotalHits()), getHitsColor()));
        kpiRow.addView(buildKpiTile(activity.getString(R.string.my_stats_peak),
                peakStr, getAccentColor()));
        return kpiRow;
    }

    private View buildKpiTile(@NonNull String label, @NonNull String value, int accentColor) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(3));
        lp.setMarginEnd(dp(3));
        tile.setLayoutParams(lp);
        tile.setBackground(createRoundedBg(getSubtleCardBg(), getDividerColor(), dp(8), dp(1)));

        TextView tvLabel = new TextView(activity);
        tvLabel.setText(label);
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvLabel.setTextColor(getSecondaryTextColor());
        tvLabel.setGravity(Gravity.CENTER);
        tvLabel.setSingleLine(true);

        TextView tvValue = new TextView(activity);
        tvValue.setText(value);
        tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvValue.setTypeface(null, Typeface.BOLD);
        tvValue.setTextColor(accentColor);
        tvValue.setGravity(Gravity.CENTER);
        tvValue.setPadding(0, dp(2), 0, 0);

        tile.addView(tvLabel);
        tile.addView(tvValue);
        return tile;
    }

    private void renderChartAndTableSection(@NonNull LinearLayout box, @NonNull StatsPeriod period) {
        // 图例与选中指示条
        LinearLayout legendBar = new LinearLayout(activity);
        legendBar.setOrientation(LinearLayout.HORIZONTAL);
        legendBar.setGravity(Gravity.CENTER_VERTICAL);
        legendBar.setPadding(0, 0, 0, dp(4));

        legendBar.addView(makeLegendDot(getVisitsColor(), activity.getString(R.string.my_stats_visits)));
        legendBar.addView(makeLegendDot(getHitsColor(), activity.getString(R.string.my_stats_hits)));

        TextView selectedReadout = new TextView(activity);
        selectedReadout.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectedReadout.setGravity(Gravity.END);
        selectedReadout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        selectedReadout.setTypeface(null, Typeface.BOLD);
        selectedReadout.setTextColor(getPrimaryTextColor());
        legendBar.addView(selectedReadout);
        box.addView(legendBar);

        StatsBarChartView chartView = new StatsBarChartView(activity);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(164));
        chartLp.bottomMargin = dp(8);
        chartView.setLayoutParams(chartLp);
        chartView.setOnBarSelectedListener((idx, entry) -> selectedReadout.setText(
                activity.getString(R.string.my_stats_selected_fmt, entry.label, entry.visitsText, entry.hitsText)));
        chartView.setData(period.entries);
        box.addView(chartView);

        box.addView(makeDivider());

        double maxV = Math.max(1d, period.getMaxVisits());
        double maxH = Math.max(1d, period.getMaxHits());
        StatsEntry peak = period.getPeakEntry();
        for (StatsEntry entry : period.entries) {
            box.addView(buildEntryDataRow(entry, maxV, maxH, entry == peak));
        }
    }

    private void renderDenseDataListSection(@NonNull LinearLayout box, @NonNull StatsPeriod period) {
        List<StatsEntry> displayList = new ArrayList<>(period.entries);
        if (reverseDensePeriods) {
            Collections.reverse(displayList);
        }

        LinearLayout legendBar = new LinearLayout(activity);
        legendBar.setOrientation(LinearLayout.HORIZONTAL);
        legendBar.setPadding(0, 0, 0, dp(6));
        legendBar.addView(makeLegendDot(getVisitsColor(), activity.getString(R.string.my_stats_visits)));
        legendBar.addView(makeLegendDot(getHitsColor(), activity.getString(R.string.my_stats_hits)));
        box.addView(legendBar);

        box.addView(makeDivider());

        double maxV = Math.max(1d, period.getMaxVisits());
        double maxH = Math.max(1d, period.getMaxHits());
        StatsEntry peak = period.getPeakEntry();
        for (StatsEntry entry : displayList) {
            box.addView(buildEntryDataRow(entry, maxV, maxH, entry == peak));
        }
    }

    private View buildEntryDataRow(@NonNull StatsEntry entry, double maxV, double maxH, boolean isPeak) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));

        // 左侧日期/月份徽标
        TextView badge = new TextView(activity);
        badge.setText(entry.label);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setTextColor(isPeak ? Color.WHITE : getPrimaryTextColor());
        badge.setBackground(createRoundedBg(
                isPeak ? getAccentColor() : getSubtleCardBg(),
                isPeak ? getAccentColor() : getDividerColor(),
                dp(8), dp(1)));
        badge.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.setMarginEnd(dp(10));
        badge.setLayoutParams(badgeLp);
        row.addView(badge);

        // 右侧双指标占比条与准确数值
        LinearLayout barsCol = new LinearLayout(activity);
        barsCol.setOrientation(LinearLayout.VERTICAL);
        barsCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        barsCol.addView(buildSingleMetricBarLine(
                activity.getString(R.string.my_stats_visits),
                entry.visitsText,
                entry.visitsValue / maxV,
                getVisitsColor()));
        View hitLine = buildSingleMetricBarLine(
                activity.getString(R.string.my_stats_hits),
                entry.hitsText,
                entry.hitsValue / maxH,
                getHitsColor());
        ((LinearLayout.LayoutParams) hitLine.getLayoutParams()).topMargin = dp(4);
        barsCol.addView(hitLine);

        row.addView(barsCol);
        return row;
    }

    private View buildSingleMetricBarLine(@NonNull String metricName, @NonNull String valueText,
                                          double ratio, int barColor) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nameTv = new TextView(activity);
        nameTv.setText(metricName);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        nameTv.setTextColor(getSecondaryTextColor());
        nameTv.setLayoutParams(new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(nameTv);

        // 横向比例轨道
        LinearLayout track = new LinearLayout(activity);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(0, dp(7), 1f);
        trackLp.setMarginEnd(dp(8));
        track.setLayoutParams(trackLp);
        track.setBackground(createRoundedBg(getDividerColor(), Color.TRANSPARENT, dp(4), 0));

        float weight = (float) Math.max(0d, Math.min(1d, ratio));
        if (weight > 0f) {
            weight = Math.max(0.03f, weight);
            View fill = new View(activity);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight));
            fill.setBackground(createRoundedBg(barColor, Color.TRANSPARENT, dp(4), 0));
            track.addView(fill);
        }
        if (weight < 1f) {
            View rest = new View(activity);
            rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - weight));
            track.addView(rest);
        }
        line.addView(track);

        TextView valTv = new TextView(activity);
        valTv.setText(valueText);
        valTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        valTv.setTypeface(null, Typeface.BOLD);
        valTv.setTextColor(getPrimaryTextColor());
        valTv.setGravity(Gravity.END);
        valTv.setLayoutParams(new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(valTv);

        return line;
    }

    private void renderExtraItemsCard(@NonNull List<RankingItem> items) {
        CardView card = createCard();
        LinearLayout box = createCardInner();
        box.addView(makeHeading(activity.getString(R.string.my_stats_extra_rankings)));

        for (RankingItem item : items) {
            TextView tv = new TextView(activity);
            tv.setText(item.text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            tv.setPadding(0, dp(6), 0, dp(6));
            if (!TextUtils.isEmpty(item.url)) {
                tv.setTextColor(getAccentColor());
                tv.setTypeface(null, Typeface.BOLD);
                tv.setOnClickListener(v -> callback.onOpenGalleryOrLink(item.url));
            } else {
                tv.setTextColor(getPrimaryTextColor());
            }
            box.addView(tv);
        }
        card.addView(box);
        rootContainer.addView(card);
    }

    @NonNull
    private String localizePeriodTitle(@NonNull String rawTitle) {
        String lower = rawTitle.toLowerCase(Locale.US);
        if (lower.contains("daily")) {
            return activity.getString(R.string.my_stats_period_daily);
        }
        if (lower.contains("12 months") || lower.contains("monthly")) {
            return activity.getString(R.string.my_stats_period_monthly);
        }
        if (lower.contains("yearly")) {
            return activity.getString(R.string.my_stats_period_yearly);
        }
        return rawTitle;
    }

    private View makeLegendDot(int color, @NonNull String text) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(12));
        item.setLayoutParams(lp);

        View dot = new View(activity);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(9), dp(9)));
        dot.setBackground(createRoundedBg(color, Color.TRANSPARENT, dp(5), 0));

        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTextColor(getSecondaryTextColor());
        tv.setPadding(dp(4), 0, 0, 0);

        item.addView(dot);
        item.addView(tv);
        return item;
    }

    private CardView createCard() {
        CardView card = new CardView(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        card.setRadius(dp(10));
        card.setCardElevation(dp(2));
        card.setCardBackgroundColor(EhWebPageHelper.resolveThemeColor(activity, R.attr.contentColorPrimary,
                isDarkTheme() ? 0xFF303030 : Color.WHITE));
        return card;
    }

    private LinearLayout createCardInner() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        return box;
    }

    private TextView makeHeading(@NonNull String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(getAccentColor());
        return tv;
    }

    private TextView makeSecondaryText(@NonNull String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(getSecondaryTextColor());
        return tv;
    }

    private MaterialButton createOutlinedButton(@NonNull String text) {
        MaterialButton btn = new MaterialButton(activity, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setCornerRadius(dp(8));
        btn.setTextColor(getPrimaryTextColor());
        btn.setStrokeColor(ColorStateList.valueOf(getDividerColor()));
        return btn;
    }

    private MaterialButton createFilledButton(@NonNull String text) {
        MaterialButton btn = new MaterialButton(activity);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setCornerRadius(dp(8));
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundTintList(ColorStateList.valueOf(getAccentColor()));
        return btn;
    }

    private View makeDivider() {
        View v = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        v.setLayoutParams(lp);
        v.setBackgroundColor(getDividerColor());
        return v;
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

    private int getSubtleCardBg() {
        return isDarkTheme() ? 0xFF262626 : 0xFFF3F4F6;
    }

    private int getVisitsColor() {
        return isDarkTheme() ? 0xFF4DB6AC : 0xFF00897B;
    }

    private int getHitsColor() {
        return isDarkTheme() ? 0xFFFFB74D : 0xFFFB8C00;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }
}
