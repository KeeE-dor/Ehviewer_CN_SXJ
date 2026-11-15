package com.hippo.ehviewer.client;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.client.parser.GalleryListParser;
import com.hippo.ehviewer.ui.scene.gallery.detail.GalleryDetailScene;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.scene.Announcer;
import com.hippo.scene.StageActivity;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;

public class ChineseSearchHelper {

    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(3);
    private static final LruCache<Long, MatchResult> sCache = new LruCache<>(300);

    public static class MatchResult {
        public final boolean verified;
        public final GalleryInfo bestMatch;

        public MatchResult(boolean verified, GalleryInfo bestMatch) {
            this.verified = verified;
            this.bestMatch = bestMatch;
        }
    }

    public interface CheckCallback {
        void onResult(MatchResult result);
    }

    public static String cleanForSearch(String title) {
        if (TextUtils.isEmpty(title)) return "";
        String core = title.replaceAll("\\[.*?\\]|\\(.*?\\)|【.*?】|<.*?>", "").trim();
        if (core.isEmpty()) core = title;
        return core.replaceAll("[\\s\u3000\u200B-\u200D\uFEFF\u00A0]+", "");
    }

    public static double jaccardSimilarity(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return 0.0;
        Set<Character> setA = new HashSet<>();
        for (char c : a.toCharArray()) setA.add(c);
        Set<Character> setB = new HashSet<>();
        for (char c : b.toCharArray()) setB.add(c);

        int intersection = 0;
        for (Character c : setA) {
            if (setB.contains(c)) intersection++;
        }
        Set<Character> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection / union.size();
    }

    public static boolean isChineseGallery(GalleryInfo gi) {
        if (gi == null) return false;
        if (GalleryInfo.S_LANG_ZH.equalsIgnoreCase(gi.simpleLanguage)) return true;
        String title = gi.title != null ? gi.title.toLowerCase() : "";
        if (title.contains("chinese") || title.contains("汉化") || title.contains("翻譯")
                || title.contains("翻译") || title.contains("translated")) {
            return true;
        }
        if (gi.simpleTags != null) {
            for (String tag : gi.simpleTags) {
                if ("language:chinese".equalsIgnoreCase(tag)) return true;
            }
        }
        if (gi.tgList != null) {
            for (String tag : gi.tgList) {
                if ("language:chinese".equalsIgnoreCase(tag)) return true;
            }
        }
        return false;
    }

    public static List<String> extractAuthors(GalleryInfo gi) {
        List<String> authors = new ArrayList<>();
        if (gi.tgList != null) {
            for (String tag : gi.tgList) {
                String lower = tag.toLowerCase();
                if (lower.startsWith("artist:") || lower.startsWith("group:")) {
                    int colonIdx = lower.indexOf(':');
                    String val = lower.substring(colonIdx + 1).replaceAll("[\\s\u3000_]+", "");
                    if (val.length() > 1) authors.add(val);
                }
            }
        }
        if (gi.simpleTags != null && authors.isEmpty()) {
            for (String tag : gi.simpleTags) {
                String lower = tag.toLowerCase();
                if (lower.startsWith("artist:") || lower.startsWith("group:")) {
                    int colonIdx = lower.indexOf(':');
                    String val = lower.substring(colonIdx + 1).replaceAll("[\\s\u3000_]+", "");
                    if (val.length() > 1) authors.add(val);
                }
            }
        }
        if (authors.isEmpty() && !TextUtils.isEmpty(gi.title)) {
            Matcher m = Pattern.compile("\\[(.*?)\\]").matcher(gi.title);
            if (m.find()) {
                String inner = m.group(1);
                Matcher pm = Pattern.compile("(.*?)\\((.*?)\\)").matcher(inner);
                if (pm.find()) {
                    String a1 = pm.group(1).replaceAll("[\\s\u3000_]+", "").toLowerCase();
                    String a2 = pm.group(2).replaceAll("[\\s\u3000_]+", "").toLowerCase();
                    if (a1.length() > 1) authors.add(a1);
                    if (a2.length() > 1) authors.add(a2);
                } else {
                    String a = inner.replaceAll("[\\s\u3000_]+", "").toLowerCase();
                    if (a.length() > 1) authors.add(a);
                }
            }
        }
        return authors;
    }

    public static int scoreCandidate(GalleryInfo candidate, List<String> originalAuthors, int originalPages, String originalCleanTitle, int positionIndex) {
        // 页数判定：最少不得比原版少 10 页以上
        int pageScore = 4;
        if (originalPages > 0 && candidate.pages > 0) {
            if (candidate.pages < originalPages - 10) {
                return -1;
            }
            int diff = Math.abs(originalPages - candidate.pages);
            if (diff <= 5) pageScore = 8;
            else if (diff <= 15) pageScore = 4;
            else pageScore = 1;
        }

        // 作者校验
        int authorScore = 5;
        if (!originalAuthors.isEmpty()) {
            List<String> cAuthors = extractAuthors(candidate);
            int exactHits = 0;
            int partialHits = 0;
            for (String orig : originalAuthors) {
                for (String c : cAuthors) {
                    if (c.equals(orig)) {
                        exactHits++;
                    } else if (c.contains(orig) || orig.contains(c)) {
                        partialHits++;
                    }
                }
            }
            if (exactHits >= 2) authorScore = 10;
            else if (exactHits >= 1) authorScore = 8;
            else if (partialHits >= 1) authorScore = 5;
            else if (!cAuthors.isEmpty()) {
                return -1; // 明确有作者 tag 但完全对不上
            }
        }

        // 标题字符级 Jaccard 相似度
        String cClean = cleanForSearch(candidate.title);
        int titleScore = (int) Math.round(jaccardSimilarity(originalCleanTitle, cClean) * 6.0);

        // 排位分 (前3名分别 +3/+2/+1)
        int posScore = Math.max(0, 3 - positionIndex);

        return authorScore + pageScore + titleScore + posScore;
    }

    public static void checkTranslation(GalleryInfo gi, CheckCallback callback) {
        MatchResult cached = sCache.get(gi.gid);
        if (cached != null) {
            SimpleHandler.getInstance().post(() -> callback.onResult(cached));
            return;
        }

        sExecutor.submit(() -> {
            MatchResult result = doSearch(gi);
            sCache.put(gi.gid, result);
            SimpleHandler.getInstance().post(() -> callback.onResult(result));
        });
    }

    private static MatchResult doSearch(GalleryInfo gi) {
        String title = !TextUtils.isEmpty(gi.titleJpn) ? gi.titleJpn : gi.title;
        String cleanTitle = cleanForSearch(title);
        if (TextUtils.isEmpty(cleanTitle)) {
            return new MatchResult(false, null);
        }

        List<String> originalAuthors = extractAuthors(gi);
        int originalPages = gi.pages;

        try {
            String keyword = "language:chinese " + cleanTitle;
            String url = EhUrl.getHost() + "?f_search=" + URLEncoder.encode(keyword, "UTF-8");

            OkHttpClient client = EhApplication.getOkHttpClient(EhApplication.getInstance());
            GalleryListParser.Result searchResult = EhEngine.getGalleryList(null, client, url, ListUrlBuilder.MODE_NORMAL);

            if (searchResult != null && searchResult.galleryInfoList != null && !searchResult.galleryInfoList.isEmpty()) {
                GalleryInfo best = null;
                int bestScore = -1;
                int index = 0;
                for (GalleryInfo candidate : searchResult.galleryInfoList) {
                    if (candidate.gid == gi.gid) {
                        index++;
                        continue;
                    }
                    int score = scoreCandidate(candidate, originalAuthors, originalPages, cleanTitle, index);
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                    index++;
                }
                if (best != null) {
                    return new MatchResult(true, best);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return new MatchResult(false, null);
    }

    public static void openGallery(Context context, GalleryInfo gi) {
        if (context instanceof StageActivity) {
            Bundle args = new Bundle();
            args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
            args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi);
            ((StageActivity) context).startScene(new Announcer(GalleryDetailScene.class).setArgs(args));
        } else {
            Toast.makeText(context, "打开画廊失败: 上下文无效", Toast.LENGTH_SHORT).show();
        }
    }

    public static void bindButton(TextView btn, GalleryInfo gi, Context context) {
        if (btn == null || gi == null) return;
        btn.setTag(gi.gid);

        // 已是汉化本
        if (isChineseGallery(gi)) {
            btn.setVisibility(View.VISIBLE);
            btn.setText("已汉化");
            btn.setBackgroundResource(R.drawable.bg_btn_check_none);
            btn.setOnClickListener(v -> Toast.makeText(context, "当前画廊已是汉化版本", Toast.LENGTH_SHORT).show());
            return;
        }

        // 已有缓存
        MatchResult cached = sCache.get(gi.gid);
        if (cached != null) {
            if (cached.verified && cached.bestMatch != null) {
                btn.setVisibility(View.VISIBLE);
                btn.setText("✅ 有汉化");
                btn.setBackgroundResource(R.drawable.bg_btn_check_found);
                btn.setOnClickListener(v -> openGallery(context, cached.bestMatch));
            } else {
                btn.setVisibility(View.VISIBLE);
                btn.setText("❌ 无汉化");
                btn.setBackgroundResource(R.drawable.bg_btn_check_none);
                btn.setOnClickListener(v -> Toast.makeText(context, "未找到对应的汉化版本", Toast.LENGTH_SHORT).show());
            }
            return;
        }

        // 未检测
        btn.setVisibility(View.VISIBLE);
        btn.setText("🔍 查汉化");
        btn.setBackgroundResource(R.drawable.bg_btn_check_idle);
        btn.setOnClickListener(v -> {
            btn.setText("⏳ 检索中");
            btn.setBackgroundResource(R.drawable.bg_btn_check_loading);
            checkTranslation(gi, result -> {
                if (btn.getTag() != null && btn.getTag().equals(gi.gid)) {
                    bindButton(btn, gi, context);
                }
            });
        });
    }
}
