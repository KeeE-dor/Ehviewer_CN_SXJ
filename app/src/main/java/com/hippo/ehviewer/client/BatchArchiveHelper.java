package com.hippo.ehviewer.client;

import android.content.Context;
import android.text.TextUtils;
import android.widget.Toast;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.ArchiverData;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.ArchiverDownloadCompleter;
import com.hippo.ehviewer.download.ArchiverDownloader;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;

public class BatchArchiveHelper {

    private static BatchArchiveHelper sInstance;

    private boolean mSelectionMode = false;
    private final Map<Long, GalleryInfo> mSelectedGalleries = new LinkedHashMap<>();
    private final ExecutorService mQueueExecutor = Executors.newSingleThreadExecutor();

    public static synchronized BatchArchiveHelper getInstance() {
        if (sInstance == null) {
            sInstance = new BatchArchiveHelper();
        }
        return sInstance;
    }

    public boolean isSelectionMode() {
        return mSelectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        mSelectionMode = selectionMode;
        if (!selectionMode) {
            mSelectedGalleries.clear();
        }
    }

    public boolean isSelected(long gid) {
        return mSelectedGalleries.containsKey(gid);
    }

    public void toggleSelect(GalleryInfo gi) {
        if (gi == null) return;
        if (mSelectedGalleries.containsKey(gi.gid)) {
            mSelectedGalleries.remove(gi.gid);
        } else {
            mSelectedGalleries.put(gi.gid, gi);
        }
    }

    public void select(GalleryInfo gi) {
        if (gi == null) return;
        mSelectedGalleries.put(gi.gid, gi);
    }

    public void unselect(long gid) {
        mSelectedGalleries.remove(gid);
    }

    public void clearSelection() {
        mSelectedGalleries.clear();
    }

    public int getSelectedCount() {
        return mSelectedGalleries.size();
    }

    public List<GalleryInfo> getSelectedList() {
        return new ArrayList<>(mSelectedGalleries.values());
    }

    public void startBatchDownload(Context context, List<GalleryInfo> list) {
        if (list == null || list.isEmpty()) return;
        final Context appContext = context.getApplicationContext();
        final List<GalleryInfo> copyList = new ArrayList<>(list);

        postToast(appContext, "开始批量归档处理，共 " + copyList.size() + " 本画廊...");

        mQueueExecutor.submit(() -> {
            OkHttpClient client = EhApplication.getOkHttpClient(EhApplication.getInstance());
            int successCount = 0;
            int total = copyList.size();

            for (int i = 0; i < total; i++) {
                GalleryInfo gi = copyList.get(i);
                try {
                    // 1. 获取画廊详情，提取 archiveUrl
                    String detailUrl = EhUrl.getGalleryDetailUrl(gi.gid, gi.token);
                    GalleryDetail gd = EhEngine.getGalleryDetail(null, client, detailUrl);

                    if (gd == null || TextUtils.isEmpty(gd.archiveUrl)) {
                        postToast(appContext, "(" + (i + 1) + "/" + total + ") 未找到归档信息: " + gi.title);
                        Thread.sleep(1500);
                        continue;
                    }

                    Thread.sleep(1000);

                    // 2. 获取归档选项 (ArchiverData)
                    ArchiverData data = EhEngine.getArchiver(null, client, gd.archiveUrl, gd.gid, gd.token);
                    if (data == null) {
                        postToast(appContext, "(" + (i + 1) + "/" + total + ") 归档服务器无响应: " + gi.title);
                        Thread.sleep(1500);
                        continue;
                    }

                    // 3. 自动匹配最佳档位：原画档优先，其次重采样档
                    String url = null;
                    String dltype = null;
                    String dlcheck = null;
                    if (!TextUtils.isEmpty(data.originalUrl)) {
                        url = data.originalUrl;
                        dltype = "org";
                        dlcheck = "Download Original Archive";
                    } else if (!TextUtils.isEmpty(data.resampleUrl)) {
                        url = data.resampleUrl;
                        dltype = "res";
                        dlcheck = "Download Resample Archive";
                    }

                    if (url == null) {
                        postToast(appContext, "(" + (i + 1) + "/" + total + ") 暂无可下载档位: " + gi.title);
                        Thread.sleep(1500);
                        continue;
                    }

                    Thread.sleep(1000);

                    // 4. 提交表单获取实际下载直链
                    String downloadUrl = EhEngine.downloadArchiver(null, client, url, gd.archiveUrl, dltype, dlcheck);
                    if (TextUtils.isEmpty(downloadUrl)) {
                        postToast(appContext, "(" + (i + 1) + "/" + total + ") 生成下载直链失败: " + gi.title);
                        Thread.sleep(1500);
                        continue;
                    }

                    // 5. 提交至 ArchiverDownloader 开始后台下载
                    String fileName = ArchiverDownloadCompleter.createFileName(gd.title, gd.gid);
                    ArchiverDownloader downloader = ArchiverDownloader.getInstance(appContext);
                    if (downloader != null) {
                        downloader.start(appContext, gd, downloadUrl, fileName);
                        successCount++;
                        postToast(appContext, "(" + (i + 1) + "/" + total + ") 已加入归档下载: " + gd.title);
                    }

                    Thread.sleep(2000);
                } catch (Throwable t) {
                    t.printStackTrace();
                    postToast(appContext, "(" + (i + 1) + "/" + total + ") 归档出错: " + t.getMessage());
                }
            }

            final int finalSuccess = successCount;
            SimpleHandler.getInstance().post(() -> {
                Toast.makeText(appContext, "✅ 批量归档任务全部提交完毕：成功加入 " + finalSuccess + " / " + total + " 本", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void postToast(Context context, String message) {
        SimpleHandler.getInstance().post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        });
    }
}
