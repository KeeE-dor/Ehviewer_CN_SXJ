package com.hippo.ehviewer.client.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.EhStatsDetail;
import com.hippo.ehviewer.client.data.EhStatsDetail.RankingItem;
import com.hippo.ehviewer.client.data.EhStatsDetail.StatsEntry;
import com.hippo.ehviewer.client.data.EhStatsDetail.StatsPeriod;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 E-Hentai stats.php（全部画廊总统计 & 单画廊统计）页面为原生图表与明细模型。
 */
public final class EhStatsParser {

    private static final Pattern PATTERN_COMPACT_NUM = Pattern.compile(
            "^([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbB])?$"
    );
    private static final Pattern PATTERN_TOTAL_VISITS = Pattern.compile(
            "total\\s+of\\s+([\\d,]+)\\s+visits",
            Pattern.CASE_INSENSITIVE
    );

    private EhStatsParser() {
    }

    @NonNull
    public static EhStatsDetail parse(@NonNull String html, @Nullable String baseUrl) {
        EhStatsDetail detail = new EhStatsDetail();
        Document doc = Jsoup.parse(html, baseUrl != null ? baseUrl : "https://e-hentai.org/stats.php");

        Element mainContainer = doc.selectFirst("div.stuffbox, div.ido, body");
        if (mainContainer == null) {
            return detail;
        }

        Element h1 = mainContainer.selectFirst("h1, h2");
        if (h1 != null) {
            detail.headline = cleanText(h1.text());
        }

        for (Element p : mainContainer.select("p")) {
            if (p.closest("table") != null) {
                continue;
            }
            String txt = cleanText(p.text());
            if (txt.isEmpty()) {
                continue;
            }
            Matcher tm = PATTERN_TOTAL_VISITS.matcher(txt);
            if (tm.find()) {
                detail.totalVisitsSummary = txt;
                detail.totalVisitsNumberText = tm.group(1) != null ? tm.group(1) : "";
            } else if (detail.totalVisitsSummary.isEmpty() && txt.toLowerCase(Locale.US).contains("visits")) {
                detail.totalVisitsSummary = txt;
            }
        }

        Element allStatsLink = doc.selectFirst("a[href$='stats.php'], a[href*='stats.php']:not([href*='gid='])");
        if (allStatsLink != null && baseUrl != null && baseUrl.contains("gid=")) {
            String href = allStatsLink.absUrl("href");
            if (!href.isEmpty()) {
                detail.backToAllStatsUrl = href;
            }
        }

        for (Element table : mainContainer.select("table")) {
            if (table.select("table").size() > 1) {
                continue;
            }
            StatsPeriod period = tryParsePeriodTable(table);
            if (period != null && !period.entries.isEmpty()) {
                detail.periods.add(period);
            } else {
                extractExtraItemsFromElement(table, detail.extraItems);
            }
        }

        for (Element a : mainContainer.select("a[href*='/g/']")) {
            if (a.closest("table") != null) {
                continue;
            }
            String url = a.absUrl("href");
            if (url.isEmpty()) {
                url = a.attr("href");
            }
            Element parent = a.parent();
            String label = parent != null ? cleanText(parent.text()) : cleanText(a.text());
            if (!label.isEmpty() && !url.isEmpty()) {
                detail.extraItems.add(new RankingItem(label, url));
            }
        }

        return detail;
    }

    @Nullable
    private static StatsPeriod tryParsePeriodTable(@NonNull Element table) {
        List<Element> rows = directRows(table);
        if (rows.size() < 2) {
            return null;
        }

        List<Element> labelCells = null;
        List<Element> visitsCells = null;
        List<Element> hitsCells = null;
        int labelRowIndex = -1;

        for (int i = 0; i < rows.size(); i++) {
            List<Element> cells = directCells(rows.get(i));
            if (cells.isEmpty()) {
                continue;
            }
            String c0 = cleanText(cells.get(0).text()).toLowerCase(Locale.US);
            if (c0.equals("visits") || c0.equals("total visits")) {
                visitsCells = cells;
                if (labelRowIndex < 0 && i > 0) {
                    // 向上找最近一行有非空文字的标签行（跳过纯图片柱状图行）
                    for (int prev = i - 1; prev >= 0; prev--) {
                        List<Element> candidate = directCells(rows.get(prev));
                        if (hasAnyCellText(candidate)) {
                            labelCells = candidate;
                            labelRowIndex = prev;
                            break;
                        }
                    }
                }
            } else if (c0.equals("hits") || c0.equals("views") || c0.equals("image views")) {
                hitsCells = cells;
            }
        }

        if (visitsCells == null && hitsCells == null) {
            return null;
        }

        List<Element> primaryMetric = visitsCells != null ? visitsCells : hitsCells;
        int colCount = primaryMetric.size();
        if (colCount < 2) {
            return null;
        }

        int startCol = isMetricHeaderCell(primaryMetric.get(0)) ? 1 : 0;
        if (labelCells != null && labelCells.size() == colCount - 1 && startCol == 1) {
            // 若标签行少了第0格空白单元格，补齐索引对齐
            List<Element> padded = new ArrayList<>(colCount);
            padded.add(new Element("td"));
            padded.addAll(labelCells);
            labelCells = padded;
        }

        List<StatsEntry> entries = new ArrayList<>();
        for (int col = startCol; col < colCount; col++) {
            String label = (labelCells != null && col < labelCells.size())
                    ? cleanText(labelCells.get(col).text()) : String.valueOf(col + 1 - startCol);
            String visitsText = (visitsCells != null && col < visitsCells.size())
                    ? cleanText(visitsCells.get(col).text()) : "0";
            String hitsText = (hitsCells != null && col < hitsCells.size())
                    ? cleanText(hitsCells.get(col).text()) : "0";

            if (label.isEmpty() && visitsText.isEmpty() && hitsText.isEmpty()) {
                continue;
            }
            if (visitsText.isEmpty()) {
                visitsText = "0";
            }
            if (hitsText.isEmpty()) {
                hitsText = "0";
            }
            double vVal = parseCompactNumber(visitsText);
            double hVal = parseCompactNumber(hitsText);
            entries.add(new StatsEntry(label, visitsText, vVal, hitsText, hVal));
        }

        if (entries.isEmpty()) {
            return null;
        }

        String title = resolvePeriodTitle(table, entries);
        return new StatsPeriod(title, entries);
    }

    private static boolean hasAnyCellText(@NonNull List<Element> cells) {
        int nonEmpty = 0;
        for (int i = 0; i < cells.size(); i++) {
            String t = cleanText(cells.get(i).text());
            if (!t.isEmpty()) {
                nonEmpty++;
            }
        }
        return nonEmpty >= 1;
    }

    private static boolean isMetricHeaderCell(@NonNull Element cell) {
        String s = cleanText(cell.text()).toLowerCase(Locale.US);
        return s.equals("visits") || s.equals("total visits")
                || s.equals("hits") || s.equals("views") || s.equals("image views");
    }

    @NonNull
    private static String resolvePeriodTitle(@NonNull Element table, @NonNull List<StatsEntry> entries) {
        // 1) 同父容器内紧邻 table 之后的标题文字 (e.g. "Daily Stats", "Last 12 Months", "Yearly Stats")
        Element next = table.nextElementSibling();
        while (next != null) {
            if ("table".equalsIgnoreCase(next.tagName()) || next.selectFirst("table") != null) {
                break;
            }
            if (next.selectFirst("a") == null) {
                String txt = cleanText(next.text());
                if (!txt.isEmpty() && txt.length() <= 60) {
                    return txt;
                }
            }
            next = next.nextElementSibling();
        }

        // 2) 检查紧邻 table 之前的标题（排除链接按钮、总访问数文案）
        Element prev = table.previousElementSibling();
        if (prev != null && !prev.is("table") && prev.selectFirst("table") == null && prev.selectFirst("a") == null) {
            String txt = cleanText(prev.text());
            if (!txt.isEmpty() && txt.length() <= 60 && !PATTERN_TOTAL_VISITS.matcher(txt).find()) {
                return txt;
            }
        }

        // 3) 按标签特征自动推断
        String first = entries.get(0).label;
        if (first.matches("^20\\d{2}$")) {
            return "Yearly Stats";
        }
        if (first.matches("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)$")) {
            return "Last 12 Months";
        }
        if (entries.size() > 12) {
            return "Daily Stats";
        }
        return "Stats";
    }

    private static void extractExtraItemsFromElement(@NonNull Element table, @NonNull List<RankingItem> out) {
        for (Element tr : directRows(table)) {
            Element link = tr.selectFirst("a[href*='/g/'], a[href*='toplist.php']");
            if (link == null) {
                continue;
            }
            String url = link.absUrl("href");
            if (url.isEmpty()) {
                url = link.attr("href");
            }
            String text = cleanText(tr.text());
            if (!text.isEmpty() && !url.isEmpty()) {
                out.add(new RankingItem(text, url));
            }
        }
    }

    @NonNull
    private static List<Element> directRows(@NonNull Element table) {
        List<Element> result = new ArrayList<>();
        Elements all = table.select("tr");
        for (Element tr : all) {
            if (tr.closest("table") == table) {
                result.add(tr);
            }
        }
        return result;
    }

    @NonNull
    private static List<Element> directCells(@NonNull Element tr) {
        List<Element> result = new ArrayList<>();
        for (Element child : tr.children()) {
            String tag = child.tagName().toLowerCase(Locale.US);
            if ("td".equals(tag) || "th".equals(tag)) {
                result.add(child);
            }
        }
        return result;
    }

    public static double parseCompactNumber(@Nullable String raw) {
        if (raw == null) {
            return 0d;
        }
        String trimmed = cleanText(raw);
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return 0d;
        }
        Matcher m = PATTERN_COMPACT_NUM.matcher(trimmed);
        if (!m.find()) {
            return 0d;
        }
        try {
            String numPart = m.group(1) != null ? m.group(1).replace(",", "") : "0";
            double val = Double.parseDouble(numPart);
            String unit = m.group(2);
            if (unit != null) {
                switch (unit.toUpperCase(Locale.US)) {
                    case "K":
                        val *= 1_000d;
                        break;
                    case "M":
                        val *= 1_000_000d;
                        break;
                    case "B":
                        val *= 1_000_000_000d;
                        break;
                    default:
                        break;
                }
            }
            return val;
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }

    @NonNull
    private static String cleanText(@Nullable String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').trim();
    }
}
