package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.CommandSender;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Safe random teleport with a persisted per-player cooldown. */
public final class RandomTeleportManager {
    private final FrizProjectPlugin plugin;
    private final Config config;

    public RandomTeleportManager(FrizProjectPlugin plugin) {
        this.plugin = plugin;
        this.config = new Config(new File(plugin.getDataFolder(), "rtp.yml"), Config.YAML);
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§e/rtp доступна игроку в мире.");
            return true;
        }
        if (!config.getBoolean("enabled", true)) {
            player.sendMessage("§cСлучайная телепортация временно отключена.");
            return true;
        }
        int maxRadius = clamp(config.getInt("max-radius", 3000), 100, 10000);
        int defaultRadius = clamp(config.getInt("default-radius", 1000), 100, maxRadius);
        int minRadius = clamp(config.getInt("min-radius", 100), 100, defaultRadius);
        int radius = defaultRadius;
        if (args.length > 1) {
            player.sendMessage("§eИспользование: /rtp [радиус]");
            return true;
        }
        if (args.length == 1) {
            try { radius = Integer.parseInt(args[0]); }
            catch (NumberFormatException ignored) { radius = -1; }
            if (radius < minRadius || radius > maxRadius) {
                player.sendMessage("§cРадиус должен быть от " + minRadius + " до " + maxRadius + " блоков.");
                return true;
            }
        }
        String key = player.getName().toLowerCase(Locale.ROOT);
        String path = "cooldowns." + key;
        long now = System.currentTimeMillis();
        long until = config.getLong(path, 0L);
        if (until > now) {
            player.sendMessage("§cПовторный /rtp будет доступен через " + formatSeconds((until - now + 999) / 1000) + ".");
            return true;
        }
        Level level = player.getLevel();
        int attempts = clamp(config.getInt("attempts", 16), 1, 64);
        for (int attempt = 0; attempt < attempts; attempt++) {
            double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * radius;
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            int y;
            try { y = level.getHighestBlockAt(x, z, true); }
            catch (RuntimeException ex) { continue; }
            if (y <= level.getMinBlockY() || y + 2 >= level.getMaxBlockY()) continue;
            Block ground = level.getBlock(x, y, z);
            Block feet = level.getBlock(x, y + 1, z);
            Block head = level.getBlock(x, y + 2, z);
            if (!ground.isSolid() || ground.isLiquid() || isHazard(ground)) continue;
            if (feet.isSolid() || feet.isLiquid() || head.isSolid() || head.isLiquid()) continue;
            if (config.getBoolean("avoid-spawn-protection", true) && plugin.isProtected(level, x, z)) continue;
            Location destination = new Location(x + 0.5, y + 1, z + 0.5, player.getYaw(), player.getPitch(), level);
            if (player.teleport(destination)) {
                long cooldown = clamp(config.getInt("cooldown-seconds", 300), 0, 86400);
                config.set(path, now + cooldown * 1000L);
                config.save();
                player.sendMessage("§aСлучайная телепортация: §f" + x + " " + (y + 1) + " " + z + "§a. Радиус: " + radius + ".");
                return true;
            }
        }
        player.sendMessage("§cНе удалось найти безопасное место. Повторите попытку позже.");
        return true;
    }

    private static boolean isHazard(Block block) {
        int id = block.getId();
        return id == 10 || id == 11 || id == 51 || id == 81 || id == 213;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static String formatSeconds(long seconds) {
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return minutes > 0 ? minutes + " мин. " + remainder + " сек." : remainder + " сек.";
    }
}
