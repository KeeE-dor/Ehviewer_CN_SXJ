package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.EhUploadDetail;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadFolderGroup;
import com.hippo.ehviewer.client.data.EhUploadDetail.UploadGalleryItem;

import org.junit.Test;

public class EhUploadParserTest {

    @Test
    public void testParseManageGalleriesAndActions() {
        String html = "<html><body>"
                + "<div class=\"stuffbox\">"
                + "<p><a href=\"https://upld.e-hentai.org/manage?collapse=1\">Collapse Open Folders</a></p>"
                + "<h2>Published Galleries</h2>"
                + "<form action=\"https://upld.e-hentai.org/manage\" method=\"post\">"
                + "<input type=\"hidden\" name=\"token\" value=\"xyz\" />"
                + "<table>"
                + "<tr>"
                + "  <th><a href=\"?sort=title\">Gallery Name</a></th>"
                + "  <th><a href=\"?sort=added\">Date Added ▼</a></th>"
                + "  <th>Files</th>"
                + "  <th>Public Category</th>"
                + "  <th>Available Actions</th>"
                + "</tr>"
                + "<tr><td colspan=\"5\">Unsorted <span>[ - ]</span></td></tr>"
                + "<tr>"
                + "  <td><input type=\"checkbox\" name=\"modify[]\" value=\"10001\" /> "
                + "      <a href=\"https://upld.e-hentai.org/managegallery?gid=10001&t=abcdef1234\">Sample Doujinshi [Chinese]</a></td>"
                + "  <td>26-02-15 17:42</td>"
                + "  <td>312</td>"
                + "  <td>Artist CG</td>"
                + "  <td>[<a href=\"https://e-hentai.org/g/10001/abcdef1234/\">View</a>] "
                + "      [<a href=\"https://e-hentai.org/stats.php?gid=10001&t=abcdef1234\">Stats</a>] "
                + "      [<a href=\"https://upld.e-hentai.org/managegallery?gid=10001&t=abcdef1234\">Manage</a>] "
                + "      [<a href=\"https://upld.e-hentai.org/managegallery?gid=10001&t=abcdef1234&act=disown\">Disown</a>]</td>"
                + "</tr>"
                + "</table>"
                + "<select name=\"do\"><option value=\"move\">Move Selected</option></select>"
                + "<select name=\"fid\"><option value=\"2\">Translated</option></select>"
                + "</form>"
                + "</div></body></html>";

        EhUploadDetail detail = EhUploadParser.parseManage(html, "https://upld.e-hentai.org/manage");
        assertNotNull(detail.collapseAllUrl);
        assertEquals(2, detail.sortOptions.size());
        assertEquals(1, detail.folders.size());

        UploadFolderGroup folder = detail.folders.get(0);
        assertEquals("Unsorted", folder.name);
        assertFalse(folder.collapsed);
        assertEquals(1, folder.galleries.size());

        UploadGalleryItem g = folder.galleries.get(0);
        assertEquals(10001L, g.gid);
        assertEquals("abcdef1234", g.token);
        assertEquals("Sample Doujinshi [Chinese]", g.title);
        assertEquals("26-02-15 17:42", g.dateAdded);
        assertEquals(312, g.filesCount);
        assertEquals("Artist CG", g.category);
        assertEquals("https://e-hentai.org/g/10001/abcdef1234/", g.viewUrl);
        assertEquals("https://e-hentai.org/stats.php?gid=10001&t=abcdef1234", g.statsUrl);
        assertEquals("https://upld.e-hentai.org/managegallery?gid=10001&t=abcdef1234", g.manageUrl);
        assertEquals("https://upld.e-hentai.org/managegallery?gid=10001&t=abcdef1234&act=disown", g.disownUrl);
        assertEquals("10001", g.checkboxValue);
        assertTrue(g.published);

        assertEquals(1, detail.batchActions.size());
        assertEquals("move", detail.batchActions.get(0).value);
        assertEquals(1, detail.targetFolders.size());
    }

    @Test
    public void testUnpublishedAppearsAfterPublishedAndFolderNamesCleaned() {
        String html = "<html><body><div class=\"stuffbox\">"
                + "<h2>Unpublished Galleries</h2>"
                + "<table>"
                + "<tr><th>Gallery Name</th><th>Date Added</th><th>Files</th><th>Public Category</th><th>Available Actions</th></tr>"
                + "<tr><td colspan=\"5\">3 Unsorted <a>+ All</a> <a>- All</a></td></tr>"
                + "<tr><td><input type=\"checkbox\" name=\"modify[]\" value=\"11\" /> <a href=\"https://upld.e-hentai.org/managegallery?gid=11&t=aaa\">Draft Early</a></td>"
                + "    <td>2026-08-12 03:45</td><td>24</td><td>Doujinshi</td><td>[View] [<a href=\"https://upld.e-hentai.org/managegallery?gid=11&t=aaa\">Manage</a>]</td></tr>"
                + "</table>"
                + "<h2>Published Galleries</h2>"
                + "<table>"
                + "<tr><th>Gallery Name <a href=\"?s=t&d=d\">▼</a></th><th>Date Added <a href=\"?s=d&d=d\">▼</a></th><th>Files</th><th>Public Category</th><th>Available Actions</th></tr>"
                + "<tr><td colspan=\"5\">105 Unsorted <span>[ - ]</span></td></tr>"
                + "<tr><td><input type=\"checkbox\" name=\"modify[]\" value=\"22\" /> <a href=\"https://upld.e-hentai.org/managegallery?gid=22&t=bbb\">Published Older</a></td>"
                + "    <td>2026-02-15 17:42</td><td>100</td><td>Artist CG</td><td>[<a href=\"https://e-hentai.org/g/22/bbb/\">View</a>] [<a href=\"https://upld.e-hentai.org/managegallery?gid=22&t=bbb\">Manage</a>]</td></tr>"
                + "<tr><td><input type=\"checkbox\" name=\"modify[]\" value=\"33\" /> <a href=\"https://upld.e-hentai.org/managegallery?gid=33&t=ccc\">Published Newer</a></td>"
                + "    <td>2026-11-18 20:00</td><td>120</td><td>Manga</td><td>[<a href=\"https://e-hentai.org/g/33/ccc/\">View</a>] [<a href=\"https://upld.e-hentai.org/managegallery?gid=33&t=ccc\">Manage</a>]</td></tr>"
                + "</table>"
                + "</div></body></html>";

        EhUploadDetail detail = EhUploadParser.parseManage(html, "https://upld.e-hentai.org/manage");
        assertEquals(2, detail.folders.size());

        // 已发布必须排在未发布之前
        UploadFolderGroup first = detail.folders.get(0);
        UploadFolderGroup second = detail.folders.get(1);
        assertFalse(first.unpublishedSection);
        assertEquals("Unsorted", first.name);
        assertEquals(105, first.serverCount);
        assertEquals(2, first.galleries.size());

        assertTrue(second.unpublishedSection);
        assertEquals("Unsorted", second.name);
        assertEquals(3, second.serverCount);
        assertEquals(1, second.galleries.size());

        // 验证日期归一化可用于降序排列
        assertTrue(EhUploadDetail.normalizeSortableDate("26-11-18 20:00")
                .compareTo(EhUploadDetail.normalizeSortableDate("2026-02-15 17:42")) > 0);
    }
}
