package com.hippo.ehviewer.client.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * E-Hentai 统计页面 (stats.php / stats.php?gid=...&t=...) 结构化数据模型。
 */
public class EhStatsDetail {

    public static class StatsEntry {
        @NonNull
        public final String label;
        @NonNull
        public final String visitsText;
        public final double visitsValue;
        @NonNull
        public final String hitsText;
        public final double hitsValue;

        public StatsEntry(@NonNull String label,
                          @NonNull String visitsText, double visitsValue,
                          @NonNull String hitsText, double hitsValue) {
            this.label = label;
            this.visitsText = visitsText;
            this.visitsValue = visitsValue;
            this.hitsText = hitsText;
            this.hitsValue = hitsValue;
        }
    }

    public static class StatsPeriod {
        @NonNull
        public final String title;
        @NonNull
        public final List<StatsEntry> entries;

        public StatsPeriod(@NonNull String title, @NonNull List<StatsEntry> entries) {
            this.title = title;
            this.entries = entries;
        }

        public double getMaxVisits() {
            double max = 0;
            for (StatsEntry e : entries) {
                if (e.visitsValue > max) {
                    max = e.visitsValue;
                }
            }
            return max;
        }

        public double getMaxHits() {
            double max = 0;
            for (StatsEntry e : entries) {
                if (e.hitsValue > max) {
                    max = e.hitsValue;
                }
            }
            return max;
        }

        public double getTotalVisits() {
            double sum = 0;
            for (StatsEntry e : entries) {
                sum += e.visitsValue;
            }
            return sum;
        }

        public double getTotalHits() {
            double sum = 0;
            for (StatsEntry e : entries) {
                sum += e.hitsValue;
            }
            return sum;
        }

        @Nullable
        public StatsEntry getPeakEntry() {
            StatsEntry peak = null;
            for (StatsEntry e : entries) {
                if (peak == null || e.hitsValue > peak.hitsValue
                        || (e.hitsValue == peak.hitsValue && e.visitsValue > peak.visitsValue)) {
                    peak = e;
                }
            }
            return peak;
        }
    }

    public static class RankingItem {
        @NonNull
        public final String text;
        @Nullable
        public final String url;

        public RankingItem(@NonNull String text, @Nullable String url) {
            this.text = text;
            this.url = url;
        }
    }

    @NonNull
    public String headline = "";
    @NonNull
    public String totalVisitsSummary = "";
    @NonNull
    public String totalVisitsNumberText = "";
    @Nullable
    public String backToAllStatsUrl = null;
    @NonNull
    public final List<StatsPeriod> periods = new ArrayList<>();
    @NonNull
    public final List<RankingItem> extraItems = new ArrayList<>();

    public boolean isEmpty() {
        return periods.isEmpty() && extraItems.isEmpty() && totalVisitsSummary.isEmpty();
    }

    @NonNull
    public static String formatCompact(double value) {
        if (value <= 0) {
            return "0";
        }
        if (value >= 1_000_000d) {
            return String.format(Locale.US, "%.2fM", value / 1_000_000d)
                    .replaceAll("\\.?0+M$", "M");
        }
        if (value >= 10_000d) {
            return String.format(Locale.US, "%.1fK", value / 1_000d)
                    .replaceAll("\\.0K$", "K");
        }
        return NumberFormat.getNumberInstance(Locale.US).format(Math.round(value));
    }
}
