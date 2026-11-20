package com.hippo.ehviewer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.EhStatsDetail.StatsEntry;
import com.hippo.ehviewer.ui.EhWebPageHelper;

import java.util.Collections;
import java.util.List;

/**
 * 原生双指标柱状图（Visits / Hits 各自归一化比例渲染），支持点击高亮查看当期详细数值。
 */
public class StatsBarChartView extends View {

    public interface OnBarSelectedListener {
        void onBarSelected(int index, @NonNull StatsEntry entry);
    }

    private final Paint visitsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hitsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tempRect = new RectF();

    @NonNull
    private List<StatsEntry> entries = Collections.emptyList();
    private double maxVisits = 0d;
    private double maxHits = 0d;
    private int selectedIndex = -1;
    @Nullable
    private OnBarSelectedListener listener;

    private float topPadPx;
    private float bottomLabelHeightPx;
    private float sidePadPx;
    private float cornerRadiusPx;
    private float minBarHeightPx;

    public StatsBarChartView(Context context) {
        this(context, null);
    }

    public StatsBarChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatsBarChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(@NonNull Context context) {
        boolean isDark = Settings.getTheme() == Settings.THEME_DARK
                || Settings.getTheme() == Settings.THEME_BLACK;

        visitsPaint.setColor(isDark ? 0xFF4DB6AC : 0xFF00897B);
        hitsPaint.setColor(isDark ? 0xFFFFB74D : 0xFFFB8C00);

        int dividerColor = EhWebPageHelper.resolveThemeColor(context, R.attr.dividerColor,
                isDark ? 0xFF3A3A3A : 0xFFE0E0E0);
        gridPaint.setColor(dividerColor);
        gridPaint.setStrokeWidth(dp(1));

        int accent = EhWebPageHelper.resolveThemeColor(context, R.attr.textColorThemeAccent,
                isDark ? 0xFF80CBC4 : 0xFF00897B);
        axisPaint.setColor(accent);
        axisPaint.setStrokeWidth(dp(1.5f));

        int labelColor = EhWebPageHelper.resolveThemeColor(context, android.R.attr.textColorSecondary,
                isDark ? 0xFFBDBDBD : 0xFF616161);
        labelPaint.setColor(labelColor);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(10.5f));

        selectBgPaint.setColor(isDark ? 0x22FFFFFF : 0x12000000);

        topPadPx = dp(8);
        bottomLabelHeightPx = dp(22);
        sidePadPx = dp(4);
        cornerRadiusPx = dp(3);
        minBarHeightPx = dp(3);
    }

    public void setData(@NonNull List<StatsEntry> entries) {
        this.entries = entries;
        double mv = 0d;
        double mh = 0d;
        for (StatsEntry e : entries) {
            if (e.visitsValue > mv) {
                mv = e.visitsValue;
            }
            if (e.hitsValue > mh) {
                mh = e.hitsValue;
            }
        }
        this.maxVisits = mv;
        this.maxHits = mh;
        this.selectedIndex = entries.isEmpty() ? -1 : entries.size() - 1;
        invalidate();
        if (listener != null && selectedIndex >= 0 && selectedIndex < entries.size()) {
            listener.onBarSelected(selectedIndex, entries.get(selectedIndex));
        }
    }

    public void setOnBarSelectedListener(@Nullable OnBarSelectedListener listener) {
        this.listener = listener;
        if (listener != null && selectedIndex >= 0 && selectedIndex < entries.size()) {
            listener.onBarSelected(selectedIndex, entries.get(selectedIndex));
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredH = (int) dp(164);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = resolveSize(desiredH, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (entries.isEmpty()) {
            return;
        }

        int w = getWidth();
        int h = getHeight();
        float chartLeft = sidePadPx;
        float chartRight = Math.max(chartLeft + 1, w - sidePadPx);
        float chartTop = topPadPx;
        float chartBottom = Math.max(chartTop + 10, h - bottomLabelHeightPx);
        float chartHeight = chartBottom - chartTop;
        float totalW = chartRight - chartLeft;

        // 绘制两条水平参考线与底部基准线
        canvas.drawLine(chartLeft, chartTop, chartRight, chartTop, gridPaint);
        canvas.drawLine(chartLeft, chartTop + chartHeight * 0.5f, chartRight, chartTop + chartHeight * 0.5f, gridPaint);
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint);

        int n = entries.size();
        float slotW = totalW / n;
        labelPaint.setTextSize(sp(n >= 10 ? 9.5f : 11f));

        float maxPairW = Math.min(dp(34), slotW * 0.78f);
        float gap = n >= 10 ? dp(1.5f) : dp(3);
        float singleBarW = Math.max(dp(3), (maxPairW - gap) / 2f);

        for (int i = 0; i < n; i++) {
            StatsEntry e = entries.get(i);
            float centerX = chartLeft + slotW * (i + 0.5f);

            if (i == selectedIndex) {
                tempRect.set(
                        chartLeft + slotW * i + dp(1),
                        chartTop,
                        chartLeft + slotW * (i + 1) - dp(1),
                        h - dp(2)
                );
                canvas.drawRoundRect(tempRect, dp(6), dp(6), selectBgPaint);
            }

            if (e.visitsValue > 0 && maxVisits > 0) {
                float bh = (float) Math.max(minBarHeightPx, (e.visitsValue / maxVisits) * (chartHeight - dp(4)));
                float left = centerX - gap * 0.5f - singleBarW;
                tempRect.set(left, chartBottom - bh, left + singleBarW, chartBottom);
                drawTopRoundedRect(canvas, tempRect, visitsPaint);
            }

            if (e.hitsValue > 0 && maxHits > 0) {
                float bh = (float) Math.max(minBarHeightPx, (e.hitsValue / maxHits) * (chartHeight - dp(4)));
                float left = centerX + gap * 0.5f;
                tempRect.set(left, chartBottom - bh, left + singleBarW, chartBottom);
                drawTopRoundedRect(canvas, tempRect, hitsPaint);
            }

            // 单行精简标签，绝不垂直折断字母
            String label = e.label;
            if (n >= 10 && label.length() > 4) {
                label = label.substring(0, 3);
            }
            float textY = chartBottom + bottomLabelHeightPx * 0.72f;
            canvas.drawText(label, centerX, textY, labelPaint);
        }
    }

    private void drawTopRoundedRect(@NonNull Canvas canvas, @NonNull RectF rect, @NonNull Paint paint) {
        float r = Math.min(cornerRadiusPx, Math.min(rect.width() * 0.5f, rect.height() * 0.5f));
        canvas.drawRoundRect(rect, r, r, paint);
        if (rect.height() > r * 2) {
            canvas.drawRect(rect.left, rect.bottom - r, rect.right, rect.bottom, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (entries.isEmpty()) {
            return super.onTouchEvent(event);
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float chartLeft = sidePadPx;
            float totalW = Math.max(1f, getWidth() - sidePadPx * 2f);
            float rel = Math.max(0f, Math.min(totalW - 1f, event.getX() - chartLeft));
            int idx = Math.max(0, Math.min(entries.size() - 1, (int) (rel / totalW * entries.size())));
            if (idx != selectedIndex) {
                selectedIndex = idx;
                invalidate();
                if (listener != null) {
                    listener.onBarSelected(idx, entries.get(idx));
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
