// SPDX-License-Identifier: Apache-2.0
package kiosk.groundnews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the top stories out of the ground.news home page. The page is a Next.js app that streams
 * its data as {@code self.__next_f.push([1,"..."])} chunks. Joined, they form lines of
 * {@code id:json}. The Daily Briefing carousel is a list of items with a {@code storyId} and an
 * {@code eventSummary}. It is the same for every edition. Every other story card is an object
 * with a {@code slug} and a {@code generatedHeadline}. Those come from the edition Ground News
 * picks for the kiosk's location, and the page's links to them give their order on screen.
 */
final class StoryFeed {
    static final String HOME = "https://ground.news/";
    private static final String PUSH = "self.__next_f.push([1,";
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    // Rendered markup only: links inside the streamed data have escaped quotes and don't match.
    private static final Pattern LINK = Pattern.compile("href=\"/article/([A-Za-z0-9_-]+)\"");

    private StoryFeed() {}

    /**
     * Stories in the order the home page shows them, at most {@code limit}, with the Daily
     * Briefing first when {@code briefing} is set. Image URLs are not fetched.
     */
    static List<Map<String, Object>> parse(String page, int limit, boolean briefing) {
        String payload = payload(page);
        List<Map<String, Object>> top = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (String line : payload.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0 || colon > 12 || colon + 1 >= line.length()) continue;
            char first = line.charAt(colon + 1);
            if (first != '[' && first != '{') continue;
            Object value;
            try {
                value = Json.parse(line.substring(colon + 1));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            collect(value, top, events, byId, 0);
        }

        List<Map<String, Object>> stories = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> item : briefing ? top : java.util.Collections.<Map<String, Object>>emptyList()) {
            if (stories.size() >= limit) break;
            Map<String, Object> event = map(item.get("eventSummary"));
            String id = text(item.get("storyId"));
            if (id == null && event != null) id = text(event.get("id"));
            Map<String, Object> full = id == null ? null : byId.get(id);
            Map<String, Object> story = story(full != null ? full : event, id);
            if (story == null) continue;
            String headline = first(text(item.get("title")), text(item.get("storyHeadline")), text(item.get("storyTitle")));
            if (headline != null) story.put("title", headline);
            String dek = first(text(item.get("dek")), text(item.get("summaryOneText")));
            if (dek != null) story.put("summary", dek);
            if (story.get("image") == null && event != null) story.put("image", image(event));
            if (seen.add((String) story.get("id"))) stories.add(story);
        }
        // Stories in the order the page links to them, then any the links missed.
        Map<String, Map<String, Object>> bySlug = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            String slug = text(event.get("slug"));
            Map<String, Object> best = byId.get(text(event.get("id")));
            if (slug != null && !bySlug.containsKey(slug)) bySlug.put(slug, best != null ? best : event);
        }
        List<Map<String, Object>> ordered = new ArrayList<>();
        java.util.regex.Matcher link = LINK.matcher(page);
        while (link.find()) {
            String key = link.group(1);
            Map<String, Object> event = bySlug.containsKey(key) ? bySlug.get(key) : byId.get(key);
            if (event != null) ordered.add(event);
        }
        for (Map<String, Object> event : events) {
            Map<String, Object> best = byId.get(text(event.get("id")));
            ordered.add(best != null ? best : event);
        }
        for (Map<String, Object> event : ordered) {
            if (stories.size() >= limit) break;
            Map<String, Object> story = story(event, null);
            if (story != null && seen.add((String) story.get("id"))) stories.add(story);
        }
        return stories;
    }

    /** Joins the streamed chunks. Each chunk is a JSON string literal. */
    static String payload(String page) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while ((at = page.indexOf(PUSH, at)) >= 0) {
            at += PUSH.length();
            if (at < page.length() && page.charAt(at) == '"') {
                try {
                    out.append(Json.stringAt(page, at));
                } catch (IllegalArgumentException ignored) {
                    // A malformed chunk only loses the stories inside it.
                }
            }
        }
        return out.toString();
    }

    private static void collect(Object value, List<Map<String, Object>> top, List<Map<String, Object>> events,
                                Map<String, Map<String, Object>> byId, int depth) {
        if (depth > 60) return;
        if (value instanceof Map) {
            Map<String, Object> map = map(value);
            if (map.get("storyId") != null && map(map.get("eventSummary")) != null) {
                top.add(map);
                return;
            }
            if (text(map.get("slug")) != null && map.containsKey("generatedHeadline") && text(map.get("id")) != null) {
                events.add(map);
                String id = text(map.get("id"));
                Map<String, Object> known = byId.get(id);
                // Keep the copy with the most to show: a bias split and an image.
                if (known == null || score(map) > score(known)) byId.put(id, map);
                return;
            }
            for (Object child : map.values()) collect(child, top, events, byId, depth + 1);
        } else if (value instanceof List) {
            for (Object child : (List<?>) value) collect(child, top, events, byId, depth + 1);
        }
    }

    private static int score(Map<String, Object> event) {
        int score = 0;
        if (map(event.get("blindspotData")) != null) score += 2;
        if (event.get("sources") instanceof List && !((List<?>) event.get("sources")).isEmpty()) score++;
        if (image(event) != null) score++;
        return score;
    }

    private static Map<String, Object> story(Map<String, Object> event, String fallbackId) {
        if (event == null) return null;
        String id = first(text(event.get("id")), fallbackId, text(event.get("slug")));
        String title = first(text(event.get("generatedHeadline")), text(event.get("title")));
        if (id == null || title == null) return null;
        Map<String, Object> story = new LinkedHashMap<>();
        story.put("id", id);
        story.put("title", title);
        String summary = first(text(event.get("dek")), text(event.get("description")), text(event.get("summary")));
        story.put("summary", summary);
        story.put("start", text(event.get("start")));
        Object count = event.get("sourceCount");
        story.put("sources", count instanceof Number ? ((Number) count).intValue() : null);
        story.put("place", place(event.get("place")));
        story.put("topic", topic(event.get("interests")));
        story.put("image", image(event));
        bias(event, story);
        return story;
    }

    private static void bias(Map<String, Object> event, Map<String, Object> story) {
        Map<String, Object> data = map(event.get("blindspotData"));
        if (data != null && data.get("leftPercent") instanceof Number) {
            story.put("left", ((Number) data.get("leftPercent")).intValue());
            story.put("center", data.get("centerPercent") instanceof Number ? ((Number) data.get("centerPercent")).intValue() : 0);
            story.put("right", data.get("rightPercent") instanceof Number ? ((Number) data.get("rightPercent")).intValue() : 0);
            return;
        }
        if (!(event.get("sources") instanceof List)) return;
        int left = 0, center = 0, right = 0;
        for (Object item : (List<?>) event.get("sources")) {
            Map<String, Object> source = map(item);
            Map<String, Object> info = source == null ? null : map(source.get("sourceInfo"));
            String lean = info == null ? null : text(info.get("bias"));
            if (lean == null) continue;
            String l = lean.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("left")) left++;
            else if (l.contains("right")) right++;
            else if (l.equals("center")) center++;
        }
        int total = left + center + right;
        if (total < 3) return;
        int leftPct = Math.round(left * 100f / total);
        int rightPct = Math.round(right * 100f / total);
        story.put("left", leftPct);
        story.put("center", 100 - leftPct - rightPct);
        story.put("right", rightPct);
    }

    static String image(Map<String, Object> event) {
        for (String key : new String[] {"latestMedia", "fallbackMedia"}) {
            Map<String, Object> media = map(event.get(key));
            if (media == null || Boolean.TRUE.equals(media.get("nsfw"))) continue;
            String url = text(media.get("url"));
            if (url != null && url.startsWith("https://")) return url;
        }
        return null;
    }

    private static String place(Object value) {
        if (value instanceof List && !((List<?>) value).isEmpty()) value = ((List<?>) value).get(0);
        Map<String, Object> place = map(value);
        return place == null ? null : text(place.get("name"));
    }

    private static String topic(Object value) {
        if (!(value instanceof List)) return null;
        for (Object item : (List<?>) value) {
            Map<String, Object> interest = map(item);
            if (interest != null && "topic".equals(interest.get("type"))) return text(interest.get("name"));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** A trimmed string without markup, or null. RSC references such as {@code $undefined} count as missing. */
    static String text(Object value) {
        if (!(value instanceof String)) return null;
        String s = (String) value;
        if (s.startsWith("$")) return null;
        s = TAGS.matcher(s).replaceAll("").replace((char) 0x200b, ' ').replace((char) 0x200c, ' ').replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }
}
