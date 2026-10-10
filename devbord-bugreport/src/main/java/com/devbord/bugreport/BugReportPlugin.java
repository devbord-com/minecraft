package com.devbord.bugreport;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sends bug reports from players to devbord, where each report becomes a card, and tells players when their
 * report was fixed.
 */
public final class BugReportPlugin extends JavaPlugin {
    private static final Pattern NUMBER = Pattern.compile("\"number\":(\\d+)");
    private static final Pattern RECEIPT = Pattern.compile("\"receipt\":\"([A-Za-z0-9_-]+)\"");
    private static final Pattern STATE = Pattern.compile("\"state\":\"([a-z]+)\"");
    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private final Object followLock = new Object();
    private File followFile;
    private YamlConfiguration followed;
    private String url;
    private String key;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        url = getConfig().getString("url", "").replaceAll("/+$", "");
        key = getConfig().getString("api-key", "");
        followFile = new File(getDataFolder(), "followed.yml");
        followed = YamlConfiguration.loadConfiguration(followFile);
        if (!configured()) {
            getLogger().warning("Not set up: put the address of devbord (https://...) and a Report only API key in config.yml");
            return;
        }
        if (getConfig().getBoolean("fixed.notify", true)) {
            long ticks = Math.max(1, getConfig().getLong("fixed.check-minutes", 5)) * 60L * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::checkFixed, ticks, ticks);
        }
    }

    private boolean configured() {
        return url.startsWith("https://") && key.matches("db_[a-f0-9]+_.+");
    }

    private String message(String name) {
        return getConfig().getString("messages." + name, name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }
        if (!configured()) {
            player.sendMessage(message("not-configured"));
            return true;
        }
        String text = String.join(" ", args).trim();
        if (text.length() < getConfig().getInt("min-length", 8)) {
            player.sendMessage(message("usage"));
            return true;
        }
        long now = System.currentTimeMillis();
        long wait = getConfig().getLong("cooldown", 120) * 1000L;
        Long last = lastReport.get(player.getUniqueId());
        if (last != null && now - last < wait) {
            player.sendMessage(String.format(message("cooldown"), (wait - (now - last)) / 1000L));
            return true;
        }
        lastReport.put(player.getUniqueId(), now);

        Location at = player.getLocation();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("World", at.getWorld() == null ? "?" : at.getWorld().getName());
        fields.put("Position", String.format(Locale.ROOT, "%.1f, %.1f, %.1f", at.getX(), at.getY(), at.getZ()));
        fields.put("Game mode", player.getGameMode().name().toLowerCase(Locale.ROOT));
        fields.put("Server", Bukkit.getName() + " " + Bukkit.getBukkitVersion());
        String body = reportJson(text.length() > 4000 ? text.substring(0, 4000) : text, player, fields);
        UUID id = player.getUniqueId();
        player.sendMessage(message("sending"));
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> send(id, body));
        return true;
    }

    private String reportJson(String text, Player player, Map<String, String> fields) {
        StringBuilder json = new StringBuilder("{\"description\":").append(quote(text));
        json.append(",\"reporter\":{\"ref\":").append(quote(player.getUniqueId().toString())).append(",\"name\":").append(quote(player.getName())).append('}');
        json.append(",\"fields\":{");
        boolean first = true;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append(quote(field.getKey())).append(':').append(quote(field.getValue()));
            first = false;
        }
        json.append("},\"labels\":[");
        List<String> labels = getConfig().getStringList("labels");
        for (int i = 0; i < labels.size(); i++) {
            json.append(i == 0 ? "" : ",").append(quote(labels.get(i)));
        }
        return json.append("]}").toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private HttpResponse<String> post(String path, String body, String idempotencyKey) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + "/api/v1" + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private void send(UUID playerId, String body) {
        String reply;
        try {
            HttpResponse<String> response = post("/reports", body, UUID.randomUUID().toString());
            String number = find(NUMBER, response.body());
            if ((response.statusCode() == 200 || response.statusCode() == 201) && number != null) {
                String receipt = find(RECEIPT, response.body());
                if (receipt != null && getConfig().getBoolean("fixed.notify", true)) {
                    follow(receipt, playerId, number);
                }
                reply = String.format(message(response.statusCode() == 200 ? "sent-duplicate" : "sent"), number);
            } else {
                getLogger().warning("Bug report failed (" + response.statusCode() + "): " + response.body());
                lastReport.remove(playerId);
                reply = message("failed");
            }
        } catch (IOException | InterruptedException e) {
            getLogger().warning("Bug report failed: " + e.getMessage());
            lastReport.remove(playerId);
            reply = message("failed");
        }
        tell(playerId, reply);
    }

    private void tell(UUID playerId, String text) {
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(text);
            }
        });
    }

    private void follow(String receipt, UUID playerId, String number) {
        synchronized (followLock) {
            followed.set(receipt, playerId + ";" + number + ";" + System.currentTimeMillis() / 1000L);
            saveFollowed();
        }
    }

    private void saveFollowed() {
        try {
            followed.save(followFile);
        } catch (IOException e) {
            getLogger().warning("Could not save followed.yml: " + e.getMessage());
        }
    }

    /** Asks devbord which followed reports were completed and tells their reporters, if they are online. */
    private void checkFixed() {
        List<String> receipts;
        synchronized (followLock) {
            receipts = new ArrayList<>(followed.getKeys(false));
        }
        long maxAge = getConfig().getLong("fixed.follow-days", 30) * 86400L;
        for (int from = 0; from < receipts.size(); from += 100) {
            List<String> batch = receipts.subList(from, Math.min(from + 100, receipts.size()));
            StringBuilder body = new StringBuilder("{\"receipts\":[");
            for (int i = 0; i < batch.size(); i++) {
                body.append(i == 0 ? "" : ",").append(quote(batch.get(i)));
            }
            String answer;
            try {
                HttpResponse<String> response = post("/reports/status", body.append("]}").toString(), null);
                if (response.statusCode() != 200) {
                    return;
                }
                answer = response.body();
            } catch (IOException | InterruptedException e) {
                return;
            }
            Matcher item = OBJECT.matcher(answer);
            while (item.find()) {
                String receipt = find(RECEIPT, item.group());
                String state = find(STATE, item.group());
                if (receipt != null && state != null && !state.equals("open")) {
                    settle(receipt, state.equals("completed"), maxAge);
                }
            }
        }
    }

    private void settle(String receipt, boolean fixed, long maxAge) {
        synchronized (followLock) {
            String[] parts = String.valueOf(followed.getString(receipt, "")).split(";");
            if (parts.length == 3 && fixed) {
                UUID playerId = UUID.fromString(parts[0]);
                boolean recent = System.currentTimeMillis() / 1000L - Long.parseLong(parts[2]) < maxAge;
                // a fixed report stays followed until its reporter is online
                if (Bukkit.getPlayer(playerId) == null && recent) {
                    return;
                }
                tell(playerId, String.format(message("fixed"), parts[1]));
            }
            followed.set(receipt, null);
            saveFollowed();
        }
    }
}
