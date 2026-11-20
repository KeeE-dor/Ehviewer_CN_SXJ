package com.hippo.ehviewer.client.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.hippo.ehviewer.client.data.EhStatsDetail;
import com.hippo.ehviewer.client.data.EhStatsDetail.StatsPeriod;

import org.junit.Test;

public class EhStatsParserTest {

    @Test
    public void testParseFullStatsPeriodsAndNumbers() {
        String html = "<html><body><div class=\"stuffbox\">"
                + "<h2>Visitor Statistics</h2>"
                + "<p>Your galleries have had a total of <strong>750,110</strong> visits.</p>"
                + "<div><table>"
                + "<tr><td></td><td><img height=\"10\"/></td><td><img height=\"120\"/></td><td><img height=\"40\"/></td></tr>"
                + "<tr><td></td><td>17th</td><td>18th</td><td>19th</td></tr>"
                + "<tr><td>Visits</td><td>5816</td><td>8235</td><td>2875</td></tr>"
                + "<tr><td>Hits</td><td>50.9K</td><td>73.2K</td><td>16.6K</td></tr>"
                + "</table><p>Daily Stats</p></div>"
                + "<div><table>"
                + "<tr><td></td><td><img height=\"0\"/></td><td><img height=\"20\"/></td><td><img height=\"140\"/></td></tr>"
                + "<tr><td></td><td>Dec</td><td>Feb</td><td>Aug</td></tr>"
                + "<tr><td>Visits</td><td>0</td><td>60.6K</td><td>401K</td></tr>"
                + "<tr><td>Hits</td><td>0</td><td>685K</td><td>3.48M</td></tr>"
                + "</table><p>Last 12 Months</p></div>"
                + "<div><table>"
                + "<tr><td></td><td><img height=\"140\"/></td></tr>"
                + "<tr><td></td><td>2026</td></tr>"
                + "<tr><td>Visits</td><td>750K</td></tr>"
                + "<tr><td>Hits</td><td>6.78M</td></tr>"
                + "</table><p>Yearly Stats</p></div>"
                + "</div></body></html>";

        EhStatsDetail detail = EhStatsParser.parse(html, "https://e-hentai.org/stats.php");
        assertFalse(detail.isEmpty());
        assertEquals("Visitor Statistics", detail.headline);
        assertEquals("750,110", detail.totalVisitsNumberText);
        assertEquals(3, detail.periods.size());

        StatsPeriod daily = detail.periods.get(0);
        assertEquals("Daily Stats", daily.title);
        assertEquals(3, daily.entries.size());
        assertEquals("18th", daily.entries.get(1).label);
        assertEquals(8235d, daily.entries.get(1).visitsValue, 0.1);
        assertEquals(73200d, daily.entries.get(1).hitsValue, 0.1);
        assertNotNull(daily.getPeakEntry());
        assertEquals("18th", daily.getPeakEntry().label);

        StatsPeriod monthly = detail.periods.get(1);
        assertEquals("Last 12 Months", monthly.title);
        assertEquals(3, monthly.entries.size());
        assertEquals(401000d, monthly.entries.get(2).visitsValue, 0.1);
        assertEquals(3_480_000d, monthly.entries.get(2).hitsValue, 0.1);

        StatsPeriod yearly = detail.periods.get(2);
        assertEquals("Yearly Stats", yearly.title);
        assertEquals(1, yearly.entries.size());
        assertEquals("2026", yearly.entries.get(0).label);
        assertEquals(750_000d, yearly.entries.get(0).visitsValue, 0.1);
        assertEquals(6_780_000d, yearly.entries.get(0).hitsValue, 0.1);
    }

    @Test
    public void testParseSingleGalleryStatsWithBackLink() {
        String html = "<html><body><div class=\"stuffbox\">"
                + "<h2>Sample Gallery Statistics</h2>"
                + "<p>This gallery has had a total of <strong>12,345</strong> visits.</p>"
                + "<p><a href=\"https://e-hentai.org/stats.php\">Back to All Galleries</a></p>"
                + "<table>"
                + "<tr><td></td><td>2026</td></tr>"
                + "<tr><td>Visits</td><td>12.3K</td></tr>"
                + "<tr><td>Hits</td><td>98.4K</td></tr>"
                + "</table>"
                + "</div></body></html>";

        EhStatsDetail detail = EhStatsParser.parse(html, "https://e-hentai.org/stats.php?gid=123&t=abc");
        assertEquals("https://e-hentai.org/stats.php", detail.backToAllStatsUrl);
        assertEquals("12,345", detail.totalVisitsNumberText);
        assertEquals(1, detail.periods.size());
        assertEquals("Yearly Stats", detail.periods.get(0).title);
    }
}
