package com.hippo.ehviewer.client.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class HomeDetail {

    public static class ToplistItem {
        @NonNull
        public final String text;
        @Nullable
        public final String url;

        public ToplistItem(@NonNull String text, @Nullable String url) {
            this.text = text;
            this.url = url;
        }
    }

    public static class PowerItem {
        @NonNull
        public final String label;
        @NonNull
        public final String value;

        public PowerItem(@NonNull String label, @NonNull String value) {
            this.label = label;
            this.value = value;
        }
    }

    private long used = 0L;
    private long total = 0L;
    private long resetCost = 0L;
    private boolean ipLimited = false;
    private String imageResetActionName = "reset_imagelimit";
    private String imageResetActionValue = "Reset Quota";

    private String trackerUploaded = "0.00 B";
    private String trackerDownloaded = "0.00 B";
    private String trackerRatio = "-";
    private long trackerTorrentCompletes = 0L;
    private long trackerGalleryCompletes = 0L;
    private long trackerSeedmins = 0L;
    @Nullable
    private String showMyTorrentsUrl = null;
    private String torrentKey = "";
    private String resetTorrentKeyValue = "Reset Torrent Key";

    private long fromGalleryVisits = 0L;
    private long fromTorrentCompletions = 0L;
    private long fromArchiveDownloads = 0L;
    private long fromHentaiAtHome = 0L;

    private final List<ToplistItem> toplists = new ArrayList<>();

    private long currentModerationPower = 0L;
    private final List<PowerItem> moderationLeftItems = new ArrayList<>();
    private final List<PowerItem> moderationRightItems = new ArrayList<>();

    public HomeDetail() {

    }

    public long getUsed() {
        return used;
    }

    public void setUsed(long used) {
        this.used = used;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public String getResetCost() {
        return withGp(resetCost);
    }

    public long resetCost() {
        return resetCost;
    }

    public void setResetCost(long resetCost) {
        this.resetCost = resetCost;
    }

    public boolean isIpLimited() {
        return ipLimited;
    }

    public void setIpLimited(boolean ipLimited) {
        this.ipLimited = ipLimited;
    }

    @NonNull
    public String getImageResetActionName() {
        return imageResetActionName;
    }

    public void setImageResetActionName(@NonNull String imageResetActionName) {
        this.imageResetActionName = imageResetActionName;
    }

    @NonNull
    public String getImageResetActionValue() {
        return imageResetActionValue;
    }

    public void setImageResetActionValue(@NonNull String imageResetActionValue) {
        this.imageResetActionValue = imageResetActionValue;
    }

    @NonNull
    public String getTrackerUploaded() {
        return trackerUploaded;
    }

    public void setTrackerUploaded(@NonNull String trackerUploaded) {
        this.trackerUploaded = trackerUploaded;
    }

    @NonNull
    public String getTrackerDownloaded() {
        return trackerDownloaded;
    }

    public void setTrackerDownloaded(@NonNull String trackerDownloaded) {
        this.trackerDownloaded = trackerDownloaded;
    }

    @NonNull
    public String getTrackerRatio() {
        return trackerRatio;
    }

    public void setTrackerRatio(@NonNull String trackerRatio) {
        this.trackerRatio = trackerRatio;
    }

    public long getTrackerTorrentCompletes() {
        return trackerTorrentCompletes;
    }

    public void setTrackerTorrentCompletes(long trackerTorrentCompletes) {
        this.trackerTorrentCompletes = trackerTorrentCompletes;
    }

    public long getTrackerGalleryCompletes() {
        return trackerGalleryCompletes;
    }

    public void setTrackerGalleryCompletes(long trackerGalleryCompletes) {
        this.trackerGalleryCompletes = trackerGalleryCompletes;
    }

    public long getTrackerSeedmins() {
        return trackerSeedmins;
    }

    public void setTrackerSeedmins(long trackerSeedmins) {
        this.trackerSeedmins = trackerSeedmins;
    }

    @Nullable
    public String getShowMyTorrentsUrl() {
        return showMyTorrentsUrl;
    }

    public void setShowMyTorrentsUrl(@Nullable String showMyTorrentsUrl) {
        this.showMyTorrentsUrl = showMyTorrentsUrl;
    }

    @NonNull
    public String getTorrentKey() {
        return torrentKey;
    }

    public void setTorrentKey(@NonNull String torrentKey) {
        this.torrentKey = torrentKey;
    }

    @NonNull
    public String getResetTorrentKeyValue() {
        return resetTorrentKeyValue;
    }

    public void setResetTorrentKeyValue(@NonNull String resetTorrentKeyValue) {
        this.resetTorrentKeyValue = resetTorrentKeyValue;
    }

    public String getFromGalleryVisits() {
        return withGp(fromGalleryVisits);
    }

    public long rawFromGalleryVisits() {
        return fromGalleryVisits;
    }

    public void setFromGalleryVisits(long fromGalleryVisits) {
        this.fromGalleryVisits = fromGalleryVisits;
    }

    public String getFromTorrentCompletions() {
        return withGp(fromTorrentCompletions);
    }

    public long rawFromTorrentCompletions() {
        return fromTorrentCompletions;
    }

    public void setFromTorrentCompletions(long fromTorrentCompletions) {
        this.fromTorrentCompletions = fromTorrentCompletions;
    }

    public String getFromArchiveDownloads() {
        return withGp(fromArchiveDownloads);
    }

    public long rawFromArchiveDownloads() {
        return fromArchiveDownloads;
    }

    public void setFromArchiveDownloads(long fromArchiveDownloads) {
        this.fromArchiveDownloads = fromArchiveDownloads;
    }

    public String getFromHentaiAtHome() {
        return withGp(fromHentaiAtHome);
    }

    public long rawFromHentaiAtHome() {
        return fromHentaiAtHome;
    }

    public void setFromHentaiAtHome(long fromHentaiAtHome) {
        this.fromHentaiAtHome = fromHentaiAtHome;
    }

    public long getTotalGpGainedSum() {
        return Math.max(0L, fromGalleryVisits)
                + Math.max(0L, fromTorrentCompletions)
                + Math.max(0L, fromArchiveDownloads)
                + Math.max(0L, fromHentaiAtHome);
    }

    @NonNull
    public List<ToplistItem> getToplists() {
        return toplists;
    }

    public String getCurrentModerationPower() {
        return Long.toString(currentModerationPower);
    }

    public void setCurrentModerationPower(long currentModerationPower) {
        this.currentModerationPower = currentModerationPower;
    }

    @NonNull
    public List<PowerItem> getModerationLeftItems() {
        return moderationLeftItems;
    }

    @NonNull
    public List<PowerItem> getModerationRightItems() {
        return moderationRightItems;
    }

    public String getImageLimits(Context context) {
        if (total == 0L) {
            return context.getString(R.string.ip_base_limits);
        }
        return used + "/" + total;
    }

    public static String formatNumber(long value) {
        return NumberFormat.getNumberInstance(Locale.US).format(value);
    }

    private String withGp(long l) {
        return l + " Gp";
    }
}
