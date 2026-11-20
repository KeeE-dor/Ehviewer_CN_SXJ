package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.HomeDetail;
import com.hippo.ehviewer.client.exception.EhException;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import okio.BufferedSource;
import okio.Okio;

public class EhHomeParserTest {

    private static String readFixture(String name) throws IOException {
        try (InputStream resource = EhHomeParserTest.class.getResourceAsStream(name)) {
            if (resource == null) {
                throw new IOException("Fixture not found: " + name);
            }
            BufferedSource source = Okio.buffer(Okio.source(resource));
            return source.readUtf8();
        }
    }

    @Test
    public void testParseIpLimitedHome() throws IOException, EhException {
        String body = readFixture("Home.html");
        HomeDetail detail = EhHomeParser.parse(body);

        assertTrue(detail.isIpLimited());
        assertEquals(0L, detail.getUsed());
        assertEquals(0L, detail.getTotal());
        assertEquals(20000L, detail.resetCost());
        assertEquals("Unlock Quota", detail.getImageResetActionValue());

        assertEquals("0.00 B", detail.getTrackerUploaded());
        assertEquals("0.00 B", detail.getTrackerDownloaded());
        assertEquals("-", detail.getTrackerRatio());
        assertEquals(0L, detail.getTrackerTorrentCompletes());
        assertEquals(0L, detail.getTrackerGalleryCompletes());
        assertEquals(0L, detail.getTrackerSeedmins());
        assertEquals("https://e-hentai.org/torrents.php?u=6254716", detail.getShowMyTorrentsUrl());
        assertEquals("X1JhhfQ1cM", detail.getTorrentKey());
        assertEquals("Reset Torrent Key", detail.getResetTorrentKeyValue());

        assertEquals(0L, detail.rawFromGalleryVisits());
        assertEquals(0L, detail.rawFromTorrentCompletions());
        assertEquals(0L, detail.rawFromArchiveDownloads());
        assertEquals(0L, detail.rawFromHentaiAtHome());
        assertTrue(detail.getToplists().isEmpty());

        assertEquals("6", detail.getCurrentModerationPower());
        assertEquals(4, detail.getModerationLeftItems().size());
        assertEquals("Base", detail.getModerationLeftItems().get(0).label);
        assertEquals("+1", detail.getModerationLeftItems().get(0).value);
        assertEquals(6, detail.getModerationRightItems().size());
        assertEquals("Account Age", detail.getModerationRightItems().get(4).label);
        assertEquals("+5", detail.getModerationRightItems().get(4).value);
    }

    @Test
    public void testParseHomePageNew() throws IOException, EhException {
        String body = readFixture("HomePageNew.html");
        HomeDetail detail = EhHomeParser.parse(body);

        assertFalse(detail.isIpLimited());
        assertEquals(538L, detail.getUsed());
        assertEquals(50000L, detail.getTotal());
        assertEquals(1076L, detail.resetCost());
        assertEquals("Reset Quota", detail.getImageResetActionValue());

        assertEquals("1.82 GiB", detail.getTrackerUploaded());
        assertEquals("0.00 B", detail.getTrackerDownloaded());
        assertEquals("\u221E", detail.getTrackerRatio());
        assertEquals(282L, detail.getTrackerTorrentCompletes());
        assertEquals(622L, detail.getTrackerGalleryCompletes());
        assertEquals(66853L, detail.getTrackerSeedmins());
        assertEquals("https://e-hentai.org/torrents.php?u=4596468", detail.getShowMyTorrentsUrl());
        assertEquals("gGwtEuYww9", detail.getTorrentKey());

        assertEquals(306261L, detail.rawFromGalleryVisits());
        assertEquals(27176L, detail.rawFromTorrentCompletions());
        assertEquals(75999L, detail.rawFromArchiveDownloads());
        assertEquals(0L, detail.rawFromHentaiAtHome());
        assertEquals(306261L + 27176L + 75999L, detail.getTotalGpGainedSum());
        assertEquals("13", detail.getCurrentModerationPower());
    }

    @Test
    public void testParseNestedTwoColumnToplistsWithoutDuplicates() throws EhException {
        String nestedHtml = "<html><body><div class=\"stuffbox\">"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"><table style=\"text-align:left; margin:auto\">"
                + "<tr><td style=\"vertical-align:top; padding-top:4px\">You are currently: &nbsp;</td>"
                + "<td><table>"
                + "<tr><td style=\"font-weight:bold; text-align:right\">#759</td><td>on the <a href=\"https://e-hentai.org/toplist.php?tl=12\">Uploader Past Year</a> toplist</td></tr>"
                + "<tr><td style=\"font-weight:bold; text-align:right\">#999</td><td>on the <a href=\"https://e-hentai.org/toplist.php?tl=13\">Uploader Past Month</a> toplist</td></tr>"
                + "<tr><td style=\"font-weight:bold; text-align:right\">#118</td><td>on the <a href=\"https://e-hentai.org/toplist.php?tl=15\">Uploader Yesterday</a> toplist</td></tr>"
                + "<tr><td style=\"font-weight:bold; text-align:right\">#699</td><td>on the <a href=\"https://e-hentai.org/toplist.php?tl=17\">Tagging Past Year</a> toplist</td></tr>"
                + "</table></td></tr>"
                + "</table></div>"
                + "<div class=\"homebox\"></div>"
                + "</div></body></html>";

        HomeDetail detail = EhHomeParser.parse(nestedHtml);
        List<HomeDetail.ToplistItem> items = detail.getToplists();
        assertEquals(4, items.size());
        assertEquals("#759 on the Uploader Past Year toplist", items.get(0).text);
        assertEquals("https://e-hentai.org/toplist.php?tl=12", items.get(0).url);
        assertEquals("#999 on the Uploader Past Month toplist", items.get(1).text);
        assertEquals("#118 on the Uploader Yesterday toplist", items.get(2).text);
        assertEquals("#699 on the Tagging Past Year toplist", items.get(3).text);
        assertEquals("https://e-hentai.org/toplist.php?tl=17", items.get(3).url);
    }

    @Test
    public void testParseLegacyMyHomePage() throws IOException, EhException {
        String body = readFixture("MyHomePage.html");
        HomeDetail detail = EhHomeParser.parse(body);

        assertFalse(detail.isIpLimited());
        assertEquals(4672L, detail.getUsed());
        assertEquals(5000L, detail.getTotal());
        assertEquals(9344L, detail.resetCost());
        assertEquals("Reset Limit", detail.getImageResetActionValue());
        assertEquals("584.1 MiB", detail.getTrackerUploaded());
        assertEquals(26L, detail.getTrackerTorrentCompletes());
        assertEquals(43L, detail.getTrackerGalleryCompletes());
        assertEquals(2975L, detail.getTrackerSeedmins());
    }

    @Test
    public void testParseActiveToplistsMultiRowAndBlocks() throws EhException {
        String multiRowHtml = "<html><body><div class=\"stuffbox\">"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"><table style=\"text-align:left; margin:auto\">"
                + "<tr><td>You are currently: </td><td><strong>#759</strong> (on the <a href=\"https://e-hentai.org/toplist.php?tl=12\">Uploader Yearly Toplist</a>)</td></tr>"
                + "<tr><td></td><td><strong>#999</strong> (on the <a href=\"https://e-hentai.org/toplist.php?tl=13\">Uploader Monthly Toplist</a>)</td></tr>"
                + "</table></div>"
                + "<div class=\"homebox\"></div>"
                + "</div></body></html>";

        HomeDetail multiRowDetail = EhHomeParser.parse(multiRowHtml);
        List<HomeDetail.ToplistItem> items = multiRowDetail.getToplists();
        assertEquals(2, items.size());
        assertEquals("#759 (on the Uploader Yearly Toplist)", items.get(0).text);
        assertEquals("https://e-hentai.org/toplist.php?tl=12", items.get(0).url);
        assertEquals("#999 (on the Uploader Monthly Toplist)", items.get(1).text);
        assertEquals("https://e-hentai.org/toplist.php?tl=13", items.get(1).url);

        String blockHtml = "<html><body><div class=\"stuffbox\">"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"></div>"
                + "<div class=\"homebox\"><table><tr><td>You are currently:</td><td>"
                + "<p>#118 (on the <a href=\"https://e-hentai.org/toplist.php?tl=15\">Uploader Daily Toplist</a>)</p>"
                + "<p>#699 (on the <a href=\"https://e-hentai.org/toplist.php?tl=17\">Tagging Yearly Toplist</a>)</p>"
                + "</td></tr></table></div>"
                + "</div></body></html>";

        HomeDetail blockDetail = EhHomeParser.parse(blockHtml);
        List<HomeDetail.ToplistItem> blockItems = blockDetail.getToplists();
        assertEquals(2, blockItems.size());
        assertEquals("#118 (on the Uploader Daily Toplist)", blockItems.get(0).text);
        assertEquals("https://e-hentai.org/toplist.php?tl=15", blockItems.get(0).url);
        assertEquals("#699 (on the Tagging Yearly Toplist)", blockItems.get(1).text);
        assertEquals("https://e-hentai.org/toplist.php?tl=17", blockItems.get(1).url);
    }
}
