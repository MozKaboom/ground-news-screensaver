// SPDX-License-Identifier: Apache-2.0
package kiosk.groundnews;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Plain HTTPS GETs with timeouts and a size cap. Only ever called on the plugin's worker thread. */
final class Net {
    static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private Net() {}

    static String text(String url, int maxBytes) throws IOException {
        return new String(bytes(url, maxBytes, "text/html,application/xhtml+xml"), StandardCharsets.UTF_8);
    }

    static byte[] bytes(String url, int maxBytes, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("Accept-Language", "en");
            int code = connection.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            InputStream in = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) in = new GZIPInputStream(in);
            try (InputStream stream = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                    out.write(buffer, 0, read);
                    if (out.size() > maxBytes) throw new IOException("Response too large");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
