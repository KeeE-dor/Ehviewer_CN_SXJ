package com.hippo.ehviewer.client.parser;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Analytics;
import com.hippo.ehviewer.client.data.HomeDetail;
import com.hippo.ehviewer.client.exception.EhException;
import com.hippo.lib.yorozuya.NumberUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EhHomeParser {
    private static final String TAG = "EhHomeParser";
    private static final Pattern PATTERN_IMAGE_LIMIT = Pattern.compile(
            "<p>You are currently at <strong>(\\d+)</strong> towards a limit of <strong>(\\d+)</strong>\\.</p>.+?<p>Reset Cost: <strong>(\\d+)</strong> GP</p>",
            Pattern.DOTALL
    );
    private static final Pattern PATTERN_IMAGE_LIMIT_NEW = Pattern.compile(
            "You are currently at <strong>([^<]+)</strong> towards your account limit of <strong>([^<]+)</strong>\\..*?(?:reset|unlock)[^<]*?<strong>([^<]+)</strong>\\s*GP",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATTERN_IP_LIMIT_UNLOCK = Pattern.compile(
            "(?:unlock[^<]*?by spending|Reset Cost:)\\s*<strong>([\\d,]+)</strong>\\s*GP",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PATTERN_CURRENT_KEY = Pattern.compile(
            "Your current key is:\\s*([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final String HOME_BOX = "homebox";

    /**
     * 个人详情页数据处理
     *
     * @param body 传入原始个人中心 html 数据
     * @return 返回从 html 数据中提取的完整个人主页数据
     */
    public static HomeDetail parse(String body) throws EhException {
        HomeDetail homeDetail = new HomeDetail();

        try {
            Document document = Jsoup.parse(body);
            Elements homeBoxes = document.getElementsByClass(HOME_BOX);

            if (homeBoxes.isEmpty()) {
                return homeDetail;
            }
            if (homeBoxes.size() > 0) {
                parseImageLimits(homeBoxes.get(0), homeDetail);
            }
            if (homeBoxes.size() > 1) {
                parseEhTracker(homeBoxes.get(1), homeDetail);
            }
            if (homeBoxes.size() > 2) {
                parseTotalGpGained(homeBoxes.get(2), homeDetail);
            }
            if (homeBoxes.size() > 3) {
                parseToplists(homeBoxes.get(3), homeDetail);
            }
            if (homeBoxes.size() > 4) {
                parseModerationPower(homeBoxes.get(4), homeDetail);
            }
        } catch (Exception e) {
            Log.e(TAG, "数据结构错误", e);
            Analytics.recordException(e);
        }

        return homeDetail;
    }

    private static void parseImageLimits(@NonNull Element imageLimits, @NonNull HomeDetail homeDetail) {
        Element submitBtn = imageLimits.selectFirst("input[type=submit][name=reset_imagelimit]");
        if (submitBtn != null && !submitBtn.attr("value").isEmpty()) {
            homeDetail.setImageResetActionName(submitBtn.attr("name"));
            homeDetail.setImageResetActionValue(submitBtn.attr("value"));
        }

        String imageListsString = imageLimits.html();
        Matcher matcher = PATTERN_IMAGE_LIMIT_NEW.matcher(imageListsString);
        if (!matcher.find()) {
            matcher = PATTERN_IMAGE_LIMIT.matcher(imageListsString);
            if (!matcher.find()) {
                // 处理 IP 限制模式（如 "Your IP is currently restricted... spending 20,000 GP"）
                if (imageListsString.toLowerCase().contains("ip")
                        && imageListsString.toLowerCase().contains("restricted")) {
                    homeDetail.setIpLimited(true);
                    homeDetail.setUsed(0L);
                    homeDetail.setTotal(0L);
                }
                Matcher ipMatcher = PATTERN_IP_LIMIT_UNLOCK.matcher(imageListsString);
                if (ipMatcher.find()) {
                    homeDetail.setResetCost(parseLongComma(ipMatcher.group(1), 0L));
                }
                return;
            }
        }

        long used = NumberUtils.parseLongSafely(getGroupIntString(1, matcher), -1L);
        long total = NumberUtils.parseLongSafely(getGroupIntString(2, matcher), -1L);
        long resetCost = NumberUtils.parseLongSafely(getGroupIntString(3, matcher), 0L);
        homeDetail.setUsed(used);
        homeDetail.setTotal(total);
        homeDetail.setResetCost(resetCost);
        homeDetail.setIpLimited(false);
    }

    private static void parseEhTracker(@NonNull Element trackerBox, @NonNull HomeDetail homeDetail) {
        Elements c1Cells = trackerBox.select("td.c1");
        if (c1Cells.size() >= 6) {
            homeDetail.setTrackerUploaded(c1Cells.get(0).text().trim());
            homeDetail.setTrackerDownloaded(c1Cells.get(1).text().trim());
            homeDetail.setTrackerRatio(c1Cells.get(2).text().trim());
            homeDetail.setTrackerTorrentCompletes(parseLongComma(c1Cells.get(3).text(), 0L));
            homeDetail.setTrackerGalleryCompletes(parseLongComma(c1Cells.get(4).text(), 0L));
            homeDetail.setTrackerSeedmins(parseLongComma(c1Cells.get(5).text(), 0L));
        }

        Element torrentsLink = trackerBox.selectFirst("a[href*=torrents.php]");
        if (torrentsLink != null) {
            homeDetail.setShowMyTorrentsUrl(torrentsLink.attr("href").trim());
        }

        Element keySpan = trackerBox.selectFirst("p:contains(current key) span");
        if (keySpan != null && !keySpan.text().trim().isEmpty()) {
            homeDetail.setTorrentKey(keySpan.text().trim());
        } else {
            Matcher keyMatcher = PATTERN_CURRENT_KEY.matcher(trackerBox.text());
            if (keyMatcher.find()) {
                homeDetail.setTorrentKey(keyMatcher.group(1));
            }
        }

        Element resetKeyBtn = trackerBox.selectFirst("input[type=submit][name=reset_torrent_key]");
        if (resetKeyBtn != null && !resetKeyBtn.attr("value").isEmpty()) {
            homeDetail.setResetTorrentKeyValue(resetKeyBtn.attr("value"));
        }
    }

    private static void parseTotalGpGained(@NonNull Element totalGpGained, @NonNull HomeDetail homeDetail) {
        Element table = totalGpGained.selectFirst("table");
        if (table == null) {
            return;
        }
        Elements rows = table.select("tr");
        if (rows.size() >= 4) {
            homeDetail.setFromGalleryVisits(parseLongComma(rows.get(0).child(0).text(), -1L));
            homeDetail.setFromTorrentCompletions(parseLongComma(rows.get(1).child(0).text(), -1L));
            homeDetail.setFromArchiveDownloads(parseLongComma(rows.get(2).child(0).text(), -1L));
            homeDetail.setFromHentaiAtHome(parseLongComma(rows.get(3).child(0).text(), -1L));
        }
    }

    private static void parseToplists(@NonNull Element toplistsBox, @NonNull HomeDetail homeDetail) {
        List<HomeDetail.ToplistItem> list = homeDetail.getToplists();
        list.clear();

        String rawText = cleanNbsp(toplistsBox.text());
        if (rawText.isEmpty() || rawText.toLowerCase().contains("not featured on any toplists")) {
            return;
        }

        Elements tables = toplistsBox.select("table");
        Element targetTable = !tables.isEmpty() ? tables.last() : null;
        Elements rows = targetTable != null ? targetTable.select("> tbody > tr, > tr") : new Elements();

        if (tables.size() >= 2 && !rows.isEmpty()) {
            for (Element row : rows) {
                addToplistRow(row, list);
            }
            if (!list.isEmpty()) {
                return;
            }
        } else if (rows.size() > 1) {
            for (Element row : rows) {
                addToplistRow(row, list);
            }
            if (!list.isEmpty()) {
                return;
            }
        }

        Element contentCell = toplistsBox;
        if (rows.size() == 1) {
            Elements tds = rows.get(0).select("> td");
            if (!tds.isEmpty()) {
                contentCell = tds.last();
            }
        }

        Elements blocks = contentCell.select("> p, > div");
        if (!blocks.isEmpty()) {
            for (Element block : blocks) {
                addToplistFragment(block, list);
            }
        }
        if (list.isEmpty() && !contentCell.select("br").isEmpty()) {
            String[] parts = contentCell.html().split("(?i)<br\\s*/?>");
            for (String part : parts) {
                Document frag = Jsoup.parseBodyFragment(part);
                addToplistFragment(frag.body(), list);
            }
        }
        if (list.isEmpty()) {
            Elements links = contentCell.select("a[href]");
            if (links.size() > 1) {
                for (Element a : links) {
                    String url = a.attr("href").trim();
                    String text = stripToplistPrefix(cleanNbsp(a.text()));
                    addUniqueToplistItem(list, text, url);
                }
            } else {
                addToplistFragment(contentCell, list);
            }
        }
    }

    private static void addToplistRow(@NonNull Element row, @NonNull List<HomeDetail.ToplistItem> list) {
        Elements tds = row.select("> td");
        StringBuilder sb = new StringBuilder();
        if (!tds.isEmpty()) {
            for (Element td : tds) {
                String cellText = stripToplistPrefix(cleanNbsp(td.text()));
                if (!cellText.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(cellText);
                }
            }
        } else {
            sb.append(stripToplistPrefix(cleanNbsp(row.text())));
        }
        String text = sb.toString().trim();
        Element link = row.selectFirst("a[href]");
        String url = link != null ? link.attr("href").trim() : null;
        addUniqueToplistItem(list, text, url);
    }

    private static void addToplistFragment(@NonNull Element el, @NonNull List<HomeDetail.ToplistItem> list) {
        String text = stripToplistPrefix(cleanNbsp(el.text()));
        Element link = el.selectFirst("a[href]");
        String url = link != null ? link.attr("href").trim() : null;
        addUniqueToplistItem(list, text, url);
    }

    private static void addUniqueToplistItem(@NonNull List<HomeDetail.ToplistItem> list,
                                             @NonNull String text, @Nullable String url) {
        if (text.isEmpty()) {
            return;
        }
        for (HomeDetail.ToplistItem existing : list) {
            if (existing.text.equals(text)
                    && ((existing.url == null && url == null) || (existing.url != null && existing.url.equals(url)))) {
                return;
            }
        }
        list.add(new HomeDetail.ToplistItem(text, url));
    }

    private static String stripToplistPrefix(@NonNull String text) {
        return text.replaceFirst("(?i)^You are currently:?\\s*", "").trim();
    }

    private static void parseModerationPower(@NonNull Element moderationPower, @NonNull HomeDetail homeDetail) {
        Element outerTable = moderationPower.selectFirst("table");
        if (outerTable == null) {
            return;
        }
        Elements firstRowCells = outerTable.select("> tbody > tr > td");
        if (firstRowCells.isEmpty()) {
            return;
        }

        // 左侧格：Current Moderation Power 数值
        Element leftCell = firstRowCells.get(0);
        Elements leftDivs = leftCell.select("div");
        if (leftDivs.size() >= 2) {
            homeDetail.setCurrentModerationPower(parseLongComma(leftDivs.get(1).text(), -1L));
        } else {
            Elements subTables = moderationPower.select("table");
            if (!subTables.isEmpty()) {
                Element row0 = subTables.get(0).selectFirst("tr");
                if (row0 != null && row0.childrenSize() > 0) {
                    Elements divs = row0.child(0).children();
                    if (divs.size() > 1) {
                        homeDetail.setCurrentModerationPower(parseLongComma(divs.get(1).text(), -1L));
                    }
                }
            }
        }

        // 右侧两个明细子表格
        homeDetail.getModerationLeftItems().clear();
        homeDetail.getModerationRightItems().clear();
        if (firstRowCells.size() >= 2) {
            parsePowerSubTable(firstRowCells.get(1), homeDetail.getModerationLeftItems());
        }
        if (firstRowCells.size() >= 3) {
            parsePowerSubTable(firstRowCells.get(2), homeDetail.getModerationRightItems());
        }
    }

    private static void parsePowerSubTable(@NonNull Element cell, @NonNull List<HomeDetail.PowerItem> target) {
        for (Element tr : cell.select("tr")) {
            Elements tds = tr.select("td");
            if (tds.size() < 2) {
                continue;
            }
            String label = cleanNbsp(tds.get(0).text());
            String value = cleanNbsp(tds.get(1).text());
            if (label.isEmpty() && value.isEmpty()) {
                continue;
            }
            target.add(new HomeDetail.PowerItem(label, value));
        }
    }

    private static String cleanNbsp(@NonNull String s) {
        return s.replace('\u00A0', ' ').trim();
    }

    private static long parseLongComma(String text, long defaultValue) {
        if (text == null) {
            return defaultValue;
        }
        return NumberUtils.parseLongSafely(text.replaceAll("[,\\s]", ""), defaultValue);
    }

    private static String getGroupIntString(int index, Matcher matcher) {
        String groupS = matcher.group(index);
        if (groupS != null) {
            return groupS.replaceAll("[,\\s]", "");
        }
        return "0";
    }
}
