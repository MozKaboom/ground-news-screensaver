// SPDX-License-Identifier: Apache-2.0
package kiosk.groundnews;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the inline screensaver document. The renderer cannot reach the network, so the stories
 * and their photos travel inside the document: the photos as data URIs, everything as one JSON
 * block the template's script reads.
 */
final class Renderer {
    static final String PLACEHOLDER = "__GN_DATA__";
    /** KS takes inline documents up to 512 KiB. Leave room for the template and the text. */
    static final int MAX_DOCUMENT_BYTES = 500 * 1024;

    private Renderer() {}

    static String html(String template, List<Map<String, Object>> stories, Map<String, String> images,
                       Map<String, Object> options, long updated, String error) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> story : stories) {
            Map<String, Object> copy = new LinkedHashMap<>(story);
            copy.put("image", images.get((String) story.get("id")));
            out.add(copy);
        }
        String html = fill(template, out, options, updated, error);
        // Too big: drop photos from the last story up until it fits.
        for (int i = out.size() - 1; i >= 0 && utf8Length(html) > MAX_DOCUMENT_BYTES; i--) {
            if (out.get(i).get("image") == null) continue;
            out.get(i).put("image", null);
            html = fill(template, out, options, updated, error);
        }
        return html;
    }

    private static String fill(String template, List<Map<String, Object>> stories, Map<String, Object> options,
                               long updated, String error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stories", stories);
        data.put("options", options);
        data.put("updated", updated);
        data.put("error", error);
        return template.replace(PLACEHOLDER, Json.write(data));
    }

    static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    static String dataUri(String type, byte[] bytes) {
        return "data:" + type + ";base64," + base64(bytes);
    }

    /** java.util.Base64 needs Android 8. The plugin supports Android 7. */
    static String base64(byte[] in) {
        final String table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder out = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            out.append(table.charAt(b0 >> 2));
            out.append(table.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            out.append(i + 1 < in.length ? table.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            out.append(i + 2 < in.length ? table.charAt(b2 & 63) : '=');
        }
        return out.toString();
    }

    /** Asks the Ground News image CDN for a smaller copy. Other hosts get the original. */
    static String resized(String url, int width) {
        try {
            String host = new java.net.URL(url).getHost();
            if (!host.endsWith(".b-cdn.net") || url.contains("?")) return url;
        } catch (java.net.MalformedURLException e) {
            return url;
        }
        return url + "?width=" + width + "&quality=65";
    }
}
