package com.hippo.ehviewer.client.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E-Hentai upld.e-hentai.org/manage 上传管理页结构化数据模型。
 */
public class EhUploadDetail {

    public static class SortOption {
        @NonNull
        public final String label;
        @NonNull
        public final String url;
        public final boolean descending;

        public SortOption(@NonNull String label, @NonNull String url, boolean descending) {
            this.label = label;
            this.url = url;
            this.descending = descending;
        }
    }

    public static class SelectOption {
        @NonNull
        public final String label;
        @NonNull
        public final String value;
        public final boolean selected;

        public SelectOption(@NonNull String label, @NonNull String value, boolean selected) {
            this.label = label;
            this.value = value;
            this.selected = selected;
        }
    }

    public static class UploadGalleryItem {
        public long gid = -1L;
        @Nullable
        public String token;
        @NonNull
        public String title = "";
        @NonNull
        public String dateAdded = "";
        public int filesCount = 0;
        @NonNull
        public String category = "";
        @NonNull
        public String folderName = "";
        public boolean published = true;

        @Nullable
        public String viewUrl;
        @Nullable
        public String statsUrl;
        @Nullable
        public String manageUrl;
        @Nullable
        public String disownUrl;
        @Nullable
        public String disownOnClick;

        @Nullable
        public String checkboxName;
        @Nullable
        public String checkboxValue;
    }

    public static class UploadFolderGroup {
        @NonNull
        public String name = "";
        @Nullable
        public String toggleUrl;
        public boolean collapsed = false;
        public boolean unpublishedSection = false;
        public int serverCount = -1;
        @NonNull
        public final List<UploadGalleryItem> galleries = new ArrayList<>();

        @NonNull
        public String getFilterKey() {
            return (unpublishedSection ? "unpub:" : "pub:") + name;
        }
    }

    @NonNull
    public static String normalizeSortableDate(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        // 将 2 位年份（如 26-02-15 17:42）归一化为 2026-02-15 17:42，便于字典序比较
        if (trimmed.matches("^\\d{2}-\\d{2}-\\d{2}.*")) {
            return "20" + trimmed;
        }
        return trimmed;
    }

    public static class PageLink {
        @NonNull
        public final String label;
        @Nullable
        public final String url;
        public final boolean current;

        public PageLink(@NonNull String label, @Nullable String url, boolean current) {
            this.label = label;
            this.url = url;
            this.current = current;
        }
    }

    @Nullable
    public String expandAllUrl;
    @Nullable
    public String collapseAllUrl;
    @Nullable
    public String formActionUrl;
    @Nullable
    public String batchActionSelectName;
    @Nullable
    public String targetFolderSelectName;
    @NonNull
    public String noticeMessage = "";

    @NonNull
    public final Map<String, String> hiddenInputs = new LinkedHashMap<>();
    @NonNull
    public final List<SortOption> sortOptions = new ArrayList<>();
    @NonNull
    public final List<UploadFolderGroup> folders = new ArrayList<>();
    @NonNull
    public final List<SelectOption> batchActions = new ArrayList<>();
    @NonNull
    public final List<SelectOption> targetFolders = new ArrayList<>();
    @NonNull
    public final List<PageLink> pageLinks = new ArrayList<>();

    public int getTotalGalleryCount() {
        int total = 0;
        for (UploadFolderGroup f : folders) {
            total += f.galleries.size();
        }
        return total;
    }
}
