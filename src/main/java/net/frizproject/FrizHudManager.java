package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityItem;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.network.protocol.types.DisplaySlot;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scoreboard.scoreboard.Scoreboard;
import cn.nukkit.utils.Config;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sidebar HUD and configured dropped-item cleanup schedule. */
public final class FrizHudManager implements Listener {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");
    private final FrizProjectPlugin plugin;
    private final EconomyManager economy;
    private final PlayerSocialManager social;
    private final Config ranks;
    private final Config users;
    private final Config config;
    private final Map<String, Scoreboard> boards = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lastLines = new ConcurrentHashMap<>();
    private final long cleanupIntervalSeconds;
    private long nextCleanupAt;

    public FrizHudManager(FrizProjectPlugin plugin, EconomyManager economy, PlayerSocialManager social, Config ranks, Config users) {
        this.plugin = plugin;
        this.economy = economy;
        this.social = social;
        this.ranks = ranks;
        this.users = users;
        this.config = new Config(new File(plugin.getDataFolder(), "hud.yml"), Config.YAML);
        this.cleanupIntervalSeconds = Math.max(0, config.getLong("item-clear-interval-seconds", 600));
        long saved = config.getLong("schedule.next-clear-at", 0L);
        long now = System.currentTimeMillis() / 1000L;
        this.nextCleanupAt = cleanupIntervalSeconds == 0 ? 0 : (saved > now ? saved : now + cleanupIntervalSeconds);
        config.set("schedule.next-clear-at", nextCleanupAt);
        config.save();
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 20);
    }

    public void stop() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) hide(player);
        config.set("schedule.next-clear-at", nextCleanupAt);
        config.save();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> update(event.getPlayer()), 20);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { hide(event.getPlayer()); }

    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) update(player);
        if (cleanupIntervalSeconds == 0 || nextCleanupAt == 0) return;
        long now = System.currentTimeMillis() / 1000L;
        if (now < nextCleanupAt) return;
        int removed = clearDroppedItems();
        do { nextCleanupAt += cleanupIntervalSeconds; } while (nextCleanupAt <= now);
        config.set("schedule.next-clear-at", nextCleanupAt);
        config.save();
        plugin.getServer().broadcastMessage("§b[FrizMine] §fОчистка предметов завершена. Удалено: §e" + removed + "§f.");
    }

    private int clearDroppedItems() {
        int removed = 0;
        for (var level : plugin.getServer().getLevels().values()) {
            for (Entity entity : level.getEntities()) {
                if (entity instanceof EntityItem && !entity.isClosed()) {
                    entity.close();
                    removed++;
                }
            }
        }
        return removed;
    }

    private void update(Player player) {
        if (!player.isOnline() || !config.getBoolean("enabled", true)) return;
        String name = player.getName();
        String key = name.toLowerCase(Locale.ROOT);
        String rank = users.getString("users." + key + ".donor-rank", "player");
        String rankName = ranks.getString("donor-ranks." + rank + ".display", "Игрок");
        String rankColor = switch (rank.toLowerCase(Locale.ROOT)) {
            case "warrior" -> "§a"; case "wanderer" -> "§b"; case "lord" -> "§c";
            case "legend" -> "§6"; case "prince" -> "§d"; case "magister" -> "§5";
            case "overlord" -> "§4"; case "guardian" -> "§e"; default -> "§7";
        };
        long remaining = Math.max(0, nextCleanupAt - System.currentTimeMillis() / 1000L);
        List<String> lines = new ArrayList<>();
        lines.add("§fНик: §b" + name);
        lines.add("§fРанг: " + rankColor + rankName);
        lines.add("§fБаланс: §a" + economy.balance(String.valueOf(player.getUniqueId())) + " FK");
        lines.add("§fНаиграно: §9" + shortTime(social.playtimeSeconds(name)));
        lines.add(String.format(Locale.US, "§eX: %d Y: %d Z: %d", player.getFloorX(), player.getFloorY(), player.getFloorZ()));
        lines.add("§cУбийства: " + social.kills(name) + " §7| §cСмерти: " + social.deaths(name));
        lines.add("§bДо очистки: " + (cleanupIntervalSeconds == 0 ? "§7—" : "§b" + clock(remaining)));
        lines.add("§fОнлайн: §a" + plugin.getServer().getOnlinePlayers().size() + "§7/§f" + plugin.getServer().getMaxPlayers());
        lines.add("§7" + LocalDateTime.now().format(DATE_FORMAT));
        lines.add("§8Сервер #" + config.getInt("server-number", 1));

        Scoreboard board = boards.computeIfAbsent(key, ignored -> new Scoreboard("friz_" + Integer.toHexString(key.hashCode()), "§5Friz§fMine"));
        if (!lines.equals(lastLines.get(key))) {
            board.setLines(lines);
            lastLines.put(key, List.copyOf(lines));
        }
        player.display(board, DisplaySlot.SIDEBAR);
    }

    private void hide(Player player) {
        String key = player.getName().toLowerCase(Locale.ROOT);
        Scoreboard board = boards.remove(key);
        lastLines.remove(key);
        if (board != null) player.removeScoreboard(board);
    }

    private static String shortTime(long seconds) {
        long hours = seconds / 3600, minutes = (seconds % 3600) / 60;
        if (hours > 0) return hours + "ч " + minutes + "м";
        return minutes + "м";
    }

    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
