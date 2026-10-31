// SPDX-License-Identifier: Apache-2.0
package kiosk.groundnews;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/** Exercises the parser and the plugin against a small page shaped like the ground.news home page. */
public final class GroundNewsTest {
    static final class Host implements PluginHost {
        final List<String> documents = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String status;
        volatile boolean statusError;
        boolean removed;
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { status = message; statusError = error; }
        public void publishScreensaver(String key, String title, String html) {
            assert GroundNewsPlugin.KEY.equals(key) && GroundNewsPlugin.TITLE.equals(title);
            assert html.getBytes(StandardCharsets.UTF_8).length <= 512 * 1024;
            documents.add(html);
        }
        public void removeScreensaver(String key) { removed = true; }
        public InputStream openAsset(String name) {
            assert GroundNewsPlugin.TEMPLATE.equals(name);
            return new ByteArrayInputStream("<script>__GN_DATA__</script>".getBytes(StandardCharsets.UTF_8));
        }
    }

    static String push(String chunk) {
        return "<script>self.__next_f.push([1," + Json.write(chunk) + "])</script>";
    }

    static String page() {
        String carousel = "8e:[\"$\",\"div\",null,{\"children\":{\"stories\":[{\"storyId\":\"s1\",\"title\":\"Top headline\","
            + "\"dek\":\"Top dek\",\"eventSummary\":{\"id\":\"s1\",\"slug\":\"top\",\"title\":\"Raw top\",\"generatedHeadline\":\"Gen top\","
            + "\"sourceCount\":41,\"latestMedia\":{\"url\":\"https://groundnews.b-cdn.net/a.jpg\"}}}]}}]\n";
        String event = "b7:[\"$\",\"div\",null,{\"summary\":{\"id\":\"s2\",\"slug\":\"second\",\"title\":\"Raw <b>second</b>\","
            + "\"generatedHeadline\":\"$undefined\",\"description\":\"Second\u200b description\",\"dek\":\"$undefined\","
            + "\"start\":\"2026-10-31T11:29:00.000Z\",\"sourceCount\":8,\"place\":{\"name\":\"United Kingdom\"},"
            + "\"interests\":[{\"type\":\"place\",\"name\":\"London\"},{\"type\":\"topic\",\"name\":\"Business\"}],"
            + "\"blindspotData\":{\"leftPercent\":14,\"centerPercent\":43,\"rightPercent\":43},"
            + "\"latestMedia\":{\"url\":\"https://groundnews.b-cdn.net/b.jpg\"}}}]\n";
        // The same story again, without a bias split, and one counted from its sources.
        String repeat = "c1:{\"event\":{\"id\":\"s2\",\"slug\":\"second\",\"title\":\"Raw second\",\"generatedHeadline\":\"x\"}}\n";
        String counted = "c2:{\"event\":{\"id\":\"s3\",\"slug\":\"third\",\"title\":\"Third\",\"generatedHeadline\":\"Third gen\","
            + "\"sources\":[" + source("leanLeft") + "," + source("center") + "," + source("right") + "," + source("farRight") + "]}}\n";
        String garbage = "T12,not json at all\n";
        // Split a line across chunks the way the stream does.
        String all = carousel + event + repeat + counted + garbage;
        int cut = all.length() / 2;
        return "<html><a href=\"/article/second\"></a><a href=\"/article/third\"></a>" + push(all.substring(0, cut)) + push(all.substring(cut)) + "</html>";
    }

    static String source(String bias) {
        return "{\"sourceInfo\":{\"bias\":\"" + bias + "\",\"name\":\"N\"}}";
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // JSON round trip, including script-unsafe characters.
        Object parsed = Json.parse("{\"a\":[1,2.5,true,null,\"x\\u00e9\\n\"],\"b\":{}}");
        assert ((List<Object>) ((Map<String, Object>) parsed).get("a")).get(4).equals("x\u00e9\n");
        String written = Json.write(parsed);
        assert Json.parse(written).equals(parsed) : written;
        assert !Json.write("</script>").contains("<");
        assert Renderer.base64("Man".getBytes()).equals("TWFu");
        assert Renderer.base64("Ma".getBytes()).equals("TWE=");
        assert Renderer.base64("M".getBytes()).equals("TQ==");
        assert Renderer.resized("https://groundnews.b-cdn.net/a.jpg", 640).endsWith("?width=640&quality=65");
        assert Renderer.resized("https://example.com/a.jpg", 640).equals("https://example.com/a.jpg");

        List<Map<String, Object>> stories = StoryFeed.parse(page(), 10, true);
        assert stories.size() == 3 : stories;
        Map<String, Object> top = stories.get(0);
        assert "s1".equals(top.get("id")) && "Top headline".equals(top.get("title")) && "Top dek".equals(top.get("summary")) : top;
        assert Integer.valueOf(41).equals(top.get("sources")) && top.get("left") == null;
        assert "https://groundnews.b-cdn.net/a.jpg".equals(top.get("image"));
        Map<String, Object> second = stories.get(1);
        assert "Raw second".equals(second.get("title")) : second;
        assert "Second description".equals(second.get("summary")) : second;
        assert "Business".equals(second.get("topic")) && "United Kingdom".equals(second.get("place"));
        assert Integer.valueOf(14).equals(second.get("left")) && Integer.valueOf(43).equals(second.get("right"));
        Map<String, Object> third = stories.get(2);
        assert Integer.valueOf(25).equals(third.get("left")) && Integer.valueOf(25).equals(third.get("center"))
            && Integer.valueOf(50).equals(third.get("right")) : third;
        assert StoryFeed.parse(page(), 2, true).size() == 2;
        // Without the briefing, only the edition's stories, in the order the page links to them.
        List<Map<String, Object>> edition = StoryFeed.parse("<a href=\"/article/third\">" + page(), 10, false);
        assert edition.size() == 2 && "s3".equals(edition.get(0).get("id")) && "s2".equals(edition.get(1).get("id")) : edition;
        // A link by story ID instead of slug works too.
        assert "s3".equals(StoryFeed.parse("<a href=\"/article/s3\">" + page(), 10, false).get(0).get("id"));
        assert StoryFeed.parse("<html>nothing</html>", 10, true).isEmpty();

        // The plugin publishes a loading document, then the stories with their photos.
        Host host = new Host();
        GroundNewsPlugin plugin = new GroundNewsPlugin();
        final int[] imageFetches = {0};
        plugin.fetcher = new GroundNewsPlugin.Fetcher() {
            public String page(String url) { assert StoryFeed.HOME.equals(url); return GroundNewsTest.page(); }
            public byte[] image(String url) throws IOException {
                imageFetches[0]++;
                if (url.contains("b.jpg")) throw new IOException("404");
                return new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2};
            }
        };
        Map<String, Object> settings = new HashMap<>();
        settings.put("storyCount", 10);
        settings.put("secondsPerStory", 20);
        settings.put("dailyBriefing", true);
        plugin.start(host, settings);
        waitFor(() -> host.documents.size() >= 2 && host.status != null);
        String loading = host.documents.get(0);
        assert loading.contains("\"stories\":[]") : loading;
        String shown = host.documents.get(host.documents.size() - 1);
        assert shown.contains("Top headline") && shown.contains("data:image/jpeg;base64,/9gBAg==") : shown;
        assert shown.contains("\"secondsPerStory\":20");
        assert host.status.startsWith("3 stories") && !host.statusError : host.status;

        // A display-only change re-renders without fetching.
        int fetches = imageFetches[0];
        int published = host.documents.size();
        settings.put("showClock", false);
        plugin.configure(settings);
        waitFor(() -> host.documents.size() > published);
        assert host.documents.get(host.documents.size() - 1).contains("\"showClock\":false");
        assert imageFetches[0] == fetches;

        // A failed refresh keeps the last stories on screen.
        plugin.fetcher = new GroundNewsPlugin.Fetcher() {
            public String page(String url) throws IOException { throw new IOException("offline"); }
            public byte[] image(String url) throws IOException { throw new IOException("offline"); }
        };
        plugin.refresh();
        String stale = host.documents.get(host.documents.size() - 1);
        assert stale.contains("Top headline") && stale.contains("Couldn't reach ground.news") : stale;
        assert host.statusError;

        plugin.stop();
        assert host.removed;
        System.out.println("GroundNewsTest passed");
    }

    interface Condition { boolean met(); }

    static void waitFor(Condition condition) throws InterruptedException {
        for (int i = 0; i < 200 && !condition.met(); i++) Thread.sleep(25);
        assert condition.met() : "Timed out";
    }
}
