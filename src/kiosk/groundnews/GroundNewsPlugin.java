// SPDX-License-Identifier: Apache-2.0
package kiosk.groundnews;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A screensaver that cycles through the top stories on ground.news. A worker thread fetches the
 * home page and the story photos, then publishes them as one inline document. Callbacks only
 * schedule work, so they return well inside the three-second limit.
 */
public final class GroundNewsPlugin implements KioskPlugin {
    static final String KEY = "top_stories";
    static final String TITLE = "Top Stories";
    static final String TEMPLATE = "screensaver/index.html";
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 400 * 1024;
    private static final int IMAGE_WIDTH = 640;

    private PluginHost host;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> refreshTask;
    private String template;

    // Written on the callback thread, read on the worker.
    private volatile Settings settings;
    // Only touched on the worker.
    private List<Map<String, Object>> stories = Collections.emptyList();
    private Map<String, String> photoCache = Collections.emptyMap(); // image URL to data URI
    private Map<String, String> photos = Collections.emptyMap(); // story ID to data URI
    private long updated;
    private String error;
    private String lastHtml;

    /** Network access, replaced in tests. */
    interface Fetcher {
        String page(String url) throws IOException;
        byte[] image(String url) throws IOException;
    }

    Fetcher fetcher = new Fetcher() {
        @Override public String page(String url) throws IOException {
            return Net.text(url, MAX_PAGE_BYTES);
        }
        @Override public byte[] image(String url) throws IOException {
            return Net.bytes(url, MAX_IMAGE_BYTES, "image/webp,image/jpeg,image/png,image/*");
        }
    };

    @Override
    public void start(PluginHost host, Map<String, Object> values) throws Exception {
        this.host = host;
        this.settings = new Settings(values);
        if (host.isHeadless()) return;
        template = readTemplate(host);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ground-news-screensaver");
            thread.setDaemon(true);
            return thread;
        });
        worker.execute(this::render);
        scheduleRefresh(0);
    }

    @Override
    public void configure(Map<String, Object> values) {
        Settings previous = settings;
        Settings next = new Settings(values);
        settings = next;
        if (worker == null || previous == null) return;
        if (next.refreshMinutes != previous.refreshMinutes || next.storyCount != previous.storyCount
                || next.dailyBriefing != previous.dailyBriefing
                || (next.showImages && !previous.showImages)) {
            scheduleRefresh(0);
        } else if (!next.equals(previous)) {
            worker.execute(this::render);
        }
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("refresh".equals(command) && worker != null) scheduleRefresh(0);
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {}

    @Override
    public void stop() throws Exception {
        ScheduledExecutorService executor = worker;
        worker = null;
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
        try {
            if (host != null && !host.isHeadless()) host.removeScreensaver(KEY);
        } catch (RuntimeException ignored) {
            // The host removes the renderer itself when the session ends.
        }
    }

    private synchronized void scheduleRefresh(long delayMinutes) {
        if (worker == null) return;
        if (refreshTask != null) refreshTask.cancel(false);
        long period = settings.refreshMinutes;
        refreshTask = worker.scheduleWithFixedDelay(this::refresh, delayMinutes, period, TimeUnit.MINUTES);
    }

    /** Fetches the home page and photos, then publishes. Runs on the worker. */
    void refresh() {
        Settings current = settings;
        try {
            List<Map<String, Object>> fresh = StoryFeed.parse(fetcher.page(StoryFeed.HOME), current.storyCount, current.dailyBriefing);
            if (fresh.isEmpty()) throw new IOException("No stories found on ground.news");
            // Photos already fetched for the same URL are reused, so a refresh only downloads new ones.
            Map<String, String> cache = new HashMap<>();
            Map<String, String> byStory = new HashMap<>();
            if (current.showImages) {
                for (Map<String, Object> story : fresh) {
                    if (Thread.currentThread().isInterrupted()) return;
                    String url = (String) story.get("image");
                    if (url == null) continue;
                    String uri = cache.containsKey(url) ? cache.get(url) : photoCache.get(url);
                    if (uri == null) {
                        try {
                            byte[] bytes = fetcher.image(Renderer.resized(url, IMAGE_WIDTH));
                            uri = Renderer.dataUri(imageType(bytes), bytes);
                        } catch (IOException e) {
                            host.log("Photo skipped: " + e.getMessage());
                            continue;
                        }
                    }
                    cache.put(url, uri);
                    byStory.put((String) story.get("id"), uri);
                }
            }
            photoCache = cache;
            photos = byStory;
            stories = fresh;
            updated = System.currentTimeMillis();
            error = null;
            render();
            status(fresh.size() + " stories, updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(updated)), false);
        } catch (IOException | RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) return;
            error = "Couldn't reach ground.news";
            host.log("Refresh failed: " + e);
            status("Refresh failed: " + e.getMessage() + (stories.isEmpty() ? "" : ". Showing the last stories."), true);
            render();
        }
    }

    /** Publishes the current stories with the current settings, unless nothing changed. Runs on the worker. */
    void render() {
        if (template == null) return;
        Settings current = settings;
        Map<String, String> shown = current.showImages ? photos : Collections.<String, String>emptyMap();
        String html = Renderer.html(template, stories, shown, current.options(), updated, error);
        if (html.equals(lastHtml)) return;
        lastHtml = html;
        host.publishScreensaver(KEY, TITLE, html);
    }

    private void status(String message, boolean isError) {
        try {
            host.status(message, isError);
        } catch (RuntimeException ignored) {
            // Status text is a nicety.
        }
    }

    static String imageType(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P') return "image/png";
        if (bytes.length > 11 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[8] == 'W' && bytes[9] == 'E') return "image/webp";
        if (bytes.length > 3 && bytes[0] == 'G' && bytes[1] == 'I') return "image/gif";
        return "image/jpeg";
    }

    static String readTemplate(PluginHost host) throws IOException {
        try (InputStream in = host.openAsset(TEMPLATE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The validated settings. KS checks ranges, but clamp anyway so a bad value can't stall the worker. */
    static final class Settings {
        final int storyCount;
        final int secondsPerStory;
        final int refreshMinutes;
        final boolean dailyBriefing;
        final boolean showImages;
        final boolean showSummary;
        final boolean showBias;
        final boolean showClock;

        Settings(Map<String, Object> values) {
            storyCount = number(values.get("storyCount"), 10, 3, 15);
            secondsPerStory = number(values.get("secondsPerStory"), 15, 5, 120);
            refreshMinutes = number(values.get("refreshMinutes"), 30, 10, 240);
            dailyBriefing = flag(values.get("dailyBriefing"), false);
            showImages = flag(values.get("showImages"), true);
            showSummary = flag(values.get("showSummary"), true);
            showBias = flag(values.get("showBias"), true);
            showClock = flag(values.get("showClock"), true);
        }

        Map<String, Object> options() {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("secondsPerStory", secondsPerStory);
            options.put("showSummary", showSummary);
            options.put("showBias", showBias);
            options.put("showClock", showClock);
            return options;
        }

        private static int number(Object value, int fallback, int min, int max) {
            if (!(value instanceof Number)) return fallback;
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d)) return fallback;
            return (int) Math.max(min, Math.min(max, Math.round(d)));
        }

        private static boolean flag(Object value, boolean fallback) {
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Settings)) return false;
            Settings o = (Settings) other;
            return storyCount == o.storyCount && secondsPerStory == o.secondsPerStory && refreshMinutes == o.refreshMinutes
                && dailyBriefing == o.dailyBriefing && showImages == o.showImages && showSummary == o.showSummary && showBias == o.showBias && showClock == o.showClock;
        }

        @Override public int hashCode() {
            return java.util.Arrays.hashCode(new Object[] {storyCount, secondsPerStory, refreshMinutes, dailyBriefing, showImages, showSummary, showBias, showClock});
        }
    }
}
