package dev.nyx.alert;

import dev.nyx.Nyx;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class DiscordWebhook {

    private final Nyx plugin;

    public DiscordWebhook(Nyx plugin) {
        this.plugin = plugin;
    }

    public void sendAlert(String playerName, String checkName, int vl, String info) {
        String webhookUrl = plugin.getNyxConfig().getDiscordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        if (!plugin.getNyxConfig().isDiscordEnabled()) return;

        String json = buildJson(playerName, checkName, vl, info);
        sendRequest(webhookUrl, json);
    }

    private String buildJson(String playerName, String checkName, int vl, String info) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            {
              "embeds": [{
                "title": "Nyx Anticheat Alert",
                "color": 16720980,
                "fields": [
                  {"name": "Player", "value": "%s", "inline": true},
                  {"name": "Check", "value": "%s", "inline": true},
                  {"name": "Violations", "value": "%d", "inline": true},
                  {"name": "Server", "value": "%s", "inline": true}
                ],
                "footer": {"text": "Nyx Anticheat v1.0.1"},
                "timestamp": "%s"
              }]
            }
            """.formatted(
                escapeJson(playerName),
                escapeJson(checkName),
                vl,
                escapeJson(plugin.getServer().getName()),
                java.time.Instant.now().toString()
            ));

        if (!info.isBlank()) {
            int insertIdx = sb.indexOf("\"fields\"");
            if (insertIdx > 0) {
                String field = "{\"name\": \"Info\", \"value\": \"%s\", \"inline\": false},".formatted(escapeJson(info));
                sb.insert(insertIdx, field);
            }
        }

        return sb.toString();
    }

    private void sendRequest(String url, String json) {
        try {
            URI uri = URI.create(url);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Nyx-Anticheat/1.0.1");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = json.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                plugin.getLogger().warning("Discord webhook returned: " + responseCode);
            }

            conn.disconnect();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to send Discord webhook: " + e.getMessage());
        }
    }

    private String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
