package com.hippo.ehviewer.client.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.EhUploadDetail;
import com.hippo.ehviewer.client.data.EhUploadDetail.PageLink;
import com.hippo.ehviewer.client.data.EhUploadDetail.SelectOption;
import com.hippo.ehviewer.client.data.EhUploadDetail.SortOption;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadFolderGroup;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadGalleryItem;

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
 * 解析 https://upld.e-hentai.org/manage 上传管理页，提取文件夹、画廊、排序、分页及批量操作表单，
 * 让客户端能够以纯原生卡片呈现全部操作按钮。
 */
public final class EhUploadParser {

    private static final Pattern PATTERN_GALLERY_URL = Pattern.compile("/g/(\\d+)/([0-9a-fA-F]+)/?");
    private static final Pattern PATTERN_GID_PARAM = Pattern.compile("[?&]gid=(\\d+)");
    private static final Pattern PATTERN_T_PARAM = Pattern.compile("[?&]t=([0-9a-fA-F]+)");
    private static final Pattern PATTERN_DATE = Pattern.compile("\\d{2,4}-\\d{2}-\\d{2}");

    private EhUploadParser() {
    }

    @NonNull
    public static EhUploadDetail parseManage(@NonNull String html, @Nullable String baseUrl) {
        EhUploadDetail detail = new EhUploadDetail();
        String resolvedBase = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl : EhUrl.URL_MY_UPLOADS_E;
        Document doc = Jsoup.parse(html, resolvedBase);

        for (Element a : doc.select("a[href]")) {
            String txt = clean(a.text()).toLowerCase(Locale.US);
            String href = resolveHref(a, resolvedBase);
            if (txt.contains("expand") && txt.contains("folder")) {
                detail.expandAllUrl = href;
            } else if (txt.contains("collapse") && txt.contains("folder")) {
                detail.collapseAllUrl = href;
            }
        }

        // 查找画廊列表表格（优先匹配包含 managegallery 链接或 Gallery Name 表头的表格）
        for (Element table : doc.select("table")) {
            if (hasNestedUploadTable(table)) {
                continue;
            }
            if (isUploadListTable(table)) {
                parseGalleryTable(doc, table, resolvedBase, detail);
            }
        }

        // 将所有仅含未发布画廊的目录也标为未发布区，并将「已发布」目录置于「未发布」目录之前
        reorderPublishedBeforeUnpublished(detail);

        // 抓取批量操作表单（移动文件夹、批量删除/Disown 等）
        parseBatchForm(doc, resolvedBase, detail);

        // 抓取分页导航（如 p=0, p=1）
        parsePagination(doc, resolvedBase, detail);

        return detail;
    }

    private static boolean hasNestedUploadTable(@NonNull Element table) {
        Elements innerTables = table.select("table");
        for (int i = 1; i < innerTables.size(); i++) {
            if (isUploadListTable(innerTables.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUploadListTable(@NonNull Element table) {
        if (table.selectFirst("a[href*='managegallery']") != null) {
            return true;
        }
        for (Element th : table.select("th")) {
            String t = clean(th.text()).toLowerCase(Locale.US);
            if (t.contains("gallery") || t.contains("available actions") || t.contains("date added")) {
                return true;
            }
        }
        return false;
    }

    private static void parseGalleryTable(@NonNull Document doc, @NonNull Element table,
                                          @NonNull String baseUrl, @NonNull EhUploadDetail detail) {
        boolean isUnpublishedSection = isNearUnpublishedHeading(doc, table);

        List<Element> rows = directRows(table);
        int colTitle = -1;
        int colDate = -1;
        int colFiles = -1;
        int colCategory = -1;
        int colActions = -1;

        UploadFolderGroup currentFolder = null;

        for (Element row : rows) {
            Elements ths = directCells(row, "th");
            if (!ths.isEmpty()) {
                for (int i = 0; i < ths.size(); i++) {
                    Element th = ths.get(i);
                    String label = clean(th.text()).toLowerCase(Locale.US);
                    if (label.contains("gallery") || label.contains("title") || label.contains("name")) {
                        colTitle = i;
                    } else if (label.contains("date")) {
                        colDate = i;
                    } else if (label.contains("file")) {
                        colFiles = i;
                    } else if (label.contains("category")) {
                        colCategory = i;
                    } else if (label.contains("action")) {
                        colActions = i;
                    }
                    extractSortLinks(th, baseUrl, detail.sortOptions);
                }
                continue;
            }

            Elements tds = directCells(row, "td");
            if (tds.isEmpty()) {
                continue;
            }

            // 文件夹行检测：整行跨列或仅1~2个单元格且不含画廊/管理动作链接
            if (isFolderHeaderRow(row, tds)) {
                Element toggleLink = row.selectFirst("a[href]");
                String toggleUrl = toggleLink != null ? resolveHref(toggleLink, baseUrl) : null;
                boolean collapsed = clean(row.text()).contains("[ + ]") || clean(row.text()).contains("[+]");

                currentFolder = new UploadFolderGroup();
                parseFolderHeaderInfo(row, currentFolder);
                currentFolder.toggleUrl = toggleUrl;
                currentFolder.collapsed = collapsed;
                currentFolder.unpublishedSection = isUnpublishedSection;
                detail.folders.add(currentFolder);
                continue;
            }

            UploadGalleryItem item = parseGalleryRow(row, tds, colTitle, colDate, colFiles, colCategory, colActions,
                    baseUrl, isUnpublishedSection);
            if (item != null) {
                if (currentFolder == null) {
                    currentFolder = new UploadFolderGroup();
                    currentFolder.name = isUnpublishedSection ? "Unpublished" : "Unsorted";
                    currentFolder.unpublishedSection = isUnpublishedSection;
                    detail.folders.add(currentFolder);
                }
                item.folderName = currentFolder.name;
                currentFolder.galleries.add(item);
            }
        }
    }

    private static void reorderPublishedBeforeUnpublished(@NonNull EhUploadDetail detail) {
        if (detail.folders.isEmpty()) {
            return;
        }
        List<UploadFolderGroup> published = new ArrayList<>();
        List<UploadFolderGroup> unpublished = new ArrayList<>();
        for (UploadFolderGroup f : detail.folders) {
            if (!f.unpublishedSection && !f.galleries.isEmpty()) {
                boolean anyPublished = false;
                for (UploadGalleryItem g : f.galleries) {
                    if (g.published) {
                        anyPublished = true;
                        break;
                    }
                }
                if (!anyPublished) {
                    f.unpublishedSection = true;
                }
            }
            if (f.unpublishedSection) {
                unpublished.add(f);
            } else {
                published.add(f);
            }
        }
        detail.folders.clear();
        detail.folders.addAll(published);
        detail.folders.addAll(unpublished);
    }

    private static boolean isNearUnpublishedHeading(@NonNull Document doc, @NonNull Element table) {
        String latestHeading = "";
        for (Element el : doc.select("h1, h2, h3, h4, p, div, table")) {
            if (el == table) {
                break;
            }
            if (el.selectFirst("table") != null) {
                continue;
            }
            String t = clean(el.text()).toLowerCase(Locale.US);
            if (t.length() > 80) {
                continue;
            }
            if (t.contains("unpublished galleries")) {
                latestHeading = "unpublished";
            } else if (t.contains("published galleries")) {
                latestHeading = "published";
            }
        }
        return "unpublished".equals(latestHeading);
    }

    private static boolean isFolderHeaderRow(@NonNull Element row, @NonNull Elements tds) {
        if (row.selectFirst("a[href*='managegallery'], a[href*='/g/'], a[href*='stats.php']") != null) {
            return false;
        }
        if (row.select("a[href*='?p='], a[href*='&p=']").size() > 1) {
            return false;
        }
        String text = clean(row.text());
        if (text.isEmpty()) {
            return false;
        }
        if (tds.size() == 1 || !tds.first().attr("colspan").isEmpty()) {
            return true;
        }
        return (text.contains("[ - ]") || text.contains("[ + ]") || text.contains("[-]") || text.contains("[+]"))
                && row.selectFirst("input[type=checkbox]") == null;
    }

    private static void parseFolderHeaderInfo(@NonNull Element row, @NonNull UploadFolderGroup target) {
        Element clone = row.clone();
        for (Element a : clone.select("a")) {
            String at = clean(a.text()).toLowerCase(Locale.US);
            if (at.equals("+ all") || at.equals("- all") || at.equals("+all") || at.equals("-all")
                    || at.equals("+") || at.equals("-") || at.contains("collapse") || at.contains("expand")) {
                a.remove();
            }
        }
        String text = clean(clone.text())
                .replace("[ - ]", "")
                .replace("[ + ]", "")
                .replace("[-]", "")
                .replace("[+]", "")
                .replaceAll("\\[\\s*[+-]\\s*All\\s*\\]", "")
                .replaceAll("(?i)[+-]\\s*All\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        Matcher mLead = Pattern.compile("^(\\d+)\\s+(.+)$").matcher(text);
        if (mLead.matches() && mLead.group(1) != null && mLead.group(2) != null) {
            target.serverCount = parseIntSafe(mLead.group(1));
            text = mLead.group(2).trim();
        } else {
            Matcher mParen = Pattern.compile("^(.*?)\\s*\\((\\d+)\\)\\s*$").matcher(text);
            if (mParen.matches() && mParen.group(1) != null && mParen.group(2) != null) {
                target.serverCount = parseIntSafe(mParen.group(2));
                text = mParen.group(1).trim();
            }
        }
        target.name = text.isEmpty() ? "Unsorted" : text;
    }

    @Nullable
    private static UploadGalleryItem parseGalleryRow(@NonNull Element row, @NonNull Elements tds,
                                                   int colTitle, int colDate, int colFiles,
                                                   int colCategory, int colActions,
                                                   @NonNull String baseUrl, boolean isUnpublished) {
        UploadGalleryItem item = new UploadGalleryItem();
        item.published = !isUnpublished;

        Element checkbox = row.selectFirst("input[type=checkbox]");
        if (checkbox != null) {
            item.checkboxName = checkbox.attr("name");
            item.checkboxValue = checkbox.attr("value");
        }

        // 先扫描所有链接识别 [View] [Stats] [Manage] [Disown] 与标题链接
        Element actionsCell = (colActions >= 0 && colActions < tds.size()) ? tds.get(colActions) : tds.last();
        for (Element a : row.select("a")) {
            String linkText = clean(a.text());
            String lower = linkText.toLowerCase(Locale.US);
            String href = resolveHref(a, baseUrl);
            String onclick = a.attr("onclick");

            if ("view".equals(lower) || "[view]".equals(lower)) {
                item.viewUrl = href;
            } else if ("stats".equals(lower) || "[stats]".equals(lower)) {
                item.statsUrl = href;
            } else if ("manage".equals(lower) || "[manage]".equals(lower)) {
                item.manageUrl = href;
            } else if ("disown".equals(lower) || "[disown]".equals(lower)
                    || "delete".equals(lower) || "[delete]".equals(lower)) {
                item.disownUrl = href;
                if (!onclick.isEmpty()) {
                    item.disownOnClick = onclick;
                }
            } else if (a.closest("td") != actionsCell && !linkText.isEmpty()
                    && (href.contains("managegallery") || href.contains("/g/") || item.title.isEmpty())) {
                item.title = linkText;
                if (href.contains("managegallery") && item.manageUrl == null) {
                    item.manageUrl = href;
                } else if (PATTERN_GALLERY_URL.matcher(href).find() && item.viewUrl == null) {
                    item.viewUrl = href;
                }
            }
        }

        if (item.title.isEmpty() && colTitle >= 0 && colTitle < tds.size()) {
            item.title = clean(tds.get(colTitle).text());
        }
        if (item.title.isEmpty()) {
            // 兜底：找最像画廊标题的链接文本
            for (Element a : row.select("a[href*='managegallery']")) {
                String t = clean(a.text());
                if (!t.isEmpty() && !"manage".equalsIgnoreCase(t)) {
                    item.title = t;
                    break;
                }
            }
        }
        if (item.title.isEmpty() && item.manageUrl == null && item.viewUrl == null) {
            return null;
        }

        // 解析 gid 与 token
        extractGidToken(item, item.viewUrl);
        extractGidToken(item, item.manageUrl);
        extractGidToken(item, item.statsUrl);

        // 确定发布状态（未发布画廊通常没有有效的 View 链接）
        if (item.viewUrl == null && !isUnpublished) {
            // 若页面存在 "View" 灰色纯文本，仍视作未发布或仅无公网地址
            String actText = actionsCell != null ? clean(actionsCell.text()).toLowerCase(Locale.US) : "";
            if (actText.contains("[view]") || actText.contains("view")) {
                item.published = false;
            }
        }

        // 解析 Date / Files / Public Category（表头定位 + 特征识别兜底）
        if (colDate >= 0 && colDate < tds.size()) {
            item.dateAdded = clean(tds.get(colDate).text());
        }
        if (colFiles >= 0 && colFiles < tds.size()) {
            item.filesCount = parseIntSafe(clean(tds.get(colFiles).text()));
        }
        if (colCategory >= 0 && colCategory < tds.size()) {
            item.category = clean(tds.get(colCategory).text());
        }

        for (Element td : tds) {
            if (td == actionsCell) {
                continue;
            }
            String txt = clean(td.text());
            if (txt.isEmpty() || txt.equals(item.title)) {
                continue;
            }
            if (item.dateAdded.isEmpty() && PATTERN_DATE.matcher(txt).find()) {
                item.dateAdded = txt;
            } else if (item.filesCount == 0 && txt.matches("^\\d{1,6}$")) {
                item.filesCount = parseIntSafe(txt);
            }
        }

        return item;
    }

    private static void extractGidToken(@NonNull UploadGalleryItem item, @Nullable String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Matcher mPath = PATTERN_GALLERY_URL.matcher(url);
        if (mPath.find()) {
            if (item.gid <= 0 && mPath.group(1) != null) {
                item.gid = parseLongSafe(mPath.group(1));
            }
            if (item.token == null) {
                item.token = mPath.group(2);
            }
            return;
        }
        Matcher mGid = PATTERN_GID_PARAM.matcher(url);
        if (mGid.find() && item.gid <= 0 && mGid.group(1) != null) {
            item.gid = parseLongSafe(mGid.group(1));
        }
        Matcher mTok = PATTERN_T_PARAM.matcher(url);
        if (mTok.find() && item.token == null) {
            item.token = mTok.group(1);
        }
    }

    private static void extractSortLinks(@NonNull Element th, @NonNull String baseUrl,
                                         @NonNull List<SortOption> sortOptions) {
        String rawTh = clean(th.text());
        String baseText = rawTh.replace("▼", "").replace("▲", "").trim();
        Elements links = th.select("a[href]");
        for (Element a : links) {
            String href = resolveHref(a, baseUrl);
            if (href.isEmpty() || "#".equals(href)) {
                continue;
            }
            String txt = clean(a.text());
            Element img = a.selectFirst("img");
            String imgHint = img != null ? (img.attr("alt") + " " + img.attr("src")).toLowerCase(Locale.US) : "";

            boolean desc = txt.contains("▼")
                    || href.toLowerCase(Locale.US).contains("desc")
                    || imgHint.contains("desc")
                    || imgHint.contains("down")
                    || (links.size() == 1 && rawTh.contains("▲"));
            boolean asc = !desc && (txt.contains("▲")
                    || href.toLowerCase(Locale.US).contains("asc")
                    || imgHint.contains("asc")
                    || imgHint.contains("up"));

            String label = txt.replace("▼", "").replace("▲", "").trim();
            if (label.isEmpty()) {
                label = baseText + (desc ? " ↓" : (asc ? " ↑" : ""));
            } else if (desc) {
                label = label + " ↓";
            } else if (asc) {
                label = label + " ↑";
            }
            if (label.isEmpty()) {
                continue;
            }
            boolean exists = false;
            for (SortOption opt : sortOptions) {
                if (opt.url.equals(href)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                sortOptions.add(new SortOption(label, href, desc));
            }
        }
    }

    private static void parseBatchForm(@NonNull Document doc, @NonNull String baseUrl,
                                       @NonNull EhUploadDetail detail) {
        for (Element form : doc.select("form")) {
            Elements selects = form.select("select");
            if (selects.isEmpty()) {
                continue;
            }
            String action = form.absUrl("action");
            detail.formActionUrl = action.isEmpty() ? baseUrl : action;

            for (Element hidden : form.select("input[type=hidden]")) {
                String name = hidden.attr("name");
                if (!name.isEmpty()) {
                    detail.hiddenInputs.put(name, hidden.attr("value"));
                }
            }

            for (Element select : selects) {
                String name = select.attr("name");
                List<SelectOption> opts = new ArrayList<>();
                for (Element opt : select.select("option")) {
                    opts.add(new SelectOption(clean(opt.text()), opt.attr("value"), opt.hasAttr("selected")));
                }
                if (opts.isEmpty()) {
                    continue;
                }
                String lowerName = name.toLowerCase(Locale.US);
                if (lowerName.contains("folder") || lowerName.contains("fid") || lowerName.contains("target")) {
                    if (detail.targetFolderSelectName == null) {
                        detail.targetFolderSelectName = name;
                        detail.targetFolders.addAll(opts);
                    }
                } else {
                    if (detail.batchActionSelectName == null) {
                        detail.batchActionSelectName = name;
                        detail.batchActions.addAll(opts);
                    }
                }
            }
            if (!detail.batchActions.isEmpty()) {
                break;
            }
        }
    }

    private static void parsePagination(@NonNull Document doc, @NonNull String baseUrl,
                                        @NonNull EhUploadDetail detail) {
        Element ptt = doc.selectFirst("table.ptt, div.ptt, ul.pagination");
        if (ptt == null) {
            return;
        }
        for (Element td : ptt.select("td, li, a")) {
            Element a = "a".equalsIgnoreCase(td.tagName()) ? td : td.selectFirst("a[href]");
            if (a != null && td.tagName().equalsIgnoreCase("td")) {
                String label = clean(a.text());
                String href = resolveHref(a, baseUrl);
                boolean current = td.hasClass("ptds");
                if (!label.isEmpty() && !href.isEmpty()) {
                    detail.pageLinks.add(new PageLink(label, href, current));
                }
            }
        }
    }

    @NonNull
    private static List<Element> directRows(@NonNull Element table) {
        List<Element> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            if (tr.closest("table") == table) {
                rows.add(tr);
            }
        }
        return rows;
    }

    @NonNull
    private static Elements directCells(@NonNull Element tr, @NonNull String tag) {
        Elements result = new Elements();
        for (Element c : tr.children()) {
            if (tag.equalsIgnoreCase(c.tagName())) {
                result.add(c);
            }
        }
        return result;
    }

    @NonNull
    private static String resolveHref(@NonNull Element el, @NonNull String baseUrl) {
        String abs = el.absUrl("href");
        String resolved = abs.isEmpty() ? el.attr("href") : abs;
        if (resolved.startsWith("/")) {
            if (baseUrl.contains("upld.")) {
                resolved = EhUrl.HOST_UPLD_E.replaceAll("/$", "") + resolved;
            } else {
                resolved = EhUrl.REFERER_E + resolved;
            }
        }
        if (resolved.contains("upld.exhentai.org/upld/")) {
            resolved = resolved.replace("https://upld.exhentai.org/upld/", EhUrl.HOST_UPLD_E)
                    .replace("http://upld.exhentai.org/upld/", EhUrl.HOST_UPLD_E);
        }
        return resolved.trim();
    }

    private static int parseIntSafe(@NonNull String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^\\d]", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long parseLongSafe(@NonNull String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    @NonNull
    private static String clean(@Nullable String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').trim();
    }
}
