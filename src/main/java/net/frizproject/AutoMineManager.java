package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.level.Level;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.ConfigSection;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Configurable, region-bound mine with paced resets and editable ore weights. */
public final class AutoMineManager implements Listener {
    private static final int MAX_BLOCKS = 131_072;
    private static final int BATCH_BLOCKS = 1_024;
    private static final Map<String, Integer> ORES = oreIds();

    private final FrizProjectPlugin plugin;
    private final Config config;
    private final Random random = new Random();
    private volatile boolean resetting;
    private long nextResetAt;

    public AutoMineManager(FrizProjectPlugin plugin) {
        this.plugin = plugin;
        this.config = new Config(new File(plugin.getDataFolder(), "mine.yml"), Config.YAML);
    }

    public void start() {
        long saved = config.getLong("mine.next-reset-at", 0L);
        nextResetAt = saved > 0 ? saved : System.currentTimeMillis() / 1000L + timerSeconds();
        if (region() != null && config.getBoolean("mine.enabled", false)) {
            config.set("mine.next-reset-at", nextResetAt);
            config.save();
        }
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 20);
    }

    public void stop() {
        resetting = false;
        if (config != null) config.save();
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) return help(sender);
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "info" -> info(sender);
            case "pos1", "setpos1" -> setCorner(sender, 1);
            case "pos2", "setpos2" -> setCorner(sender, 2);
            case "reset" -> reset(sender, true);
            case "setlevel" -> setLevel(sender, args);
            case "settimer" -> setTimer(sender, args);
            case "addore" -> addOre(sender, args);
            case "removeore" -> removeOre(sender, args);
            case "reload" -> reload(sender);
            case "enable" -> enable(sender, true);
            case "disable" -> enable(sender, false);
            default -> help(sender);
        };
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage("§6Автошахта: /mine info");
        sender.sendMessage("§e/creator mine pos1|pos2 §7— отметить угол региона");
        sender.sendMessage("§e/creator mine reset|setlevel <1–5>|settimer <время>");
        sender.sendMessage("§e/creator mine addore <руда> [вес 1–99]|removeore <руда>|reload|enable|disable");
        sender.sendMessage("§7После отметки углов задайте руды и выполните reset. Время: 120s, 15m, 1h или секунды.");
        return true;
    }

    private boolean info(CommandSender sender) {
        Region r = region();
        sender.sendMessage("§6Автошахта: §f" + (r == null ? "регион не настроен" : r.world.getName() + " " + r.volume + " блоков"));
        sender.sendMessage("§eУровень " + config.getInt("mine.level", 1) + " §7| сброс через " + remainingSeconds() + " сек. §7| " + (resetting ? "сброс выполняется" : "ожидание"));
        ConfigSection ores = currentOres();
        StringBuilder line = new StringBuilder("§7Руды: ");
        for (String key : ores.getKeys(false)) {
            if (line.length() > 7) line.append(", ");
            line.append(key).append("=").append(ores.getInt(key));
        }
        sender.sendMessage(line.toString());
        return true;
    }

    private boolean setCorner(CommandSender sender, int number) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cОтметить угол можно только в игре.");
            return true;
        }
        String other = number == 1 ? "pos2" : "pos1";
        String otherWorld = config.getString("mine." + other + ".world", "");
        if (!otherWorld.isBlank() && !otherWorld.equalsIgnoreCase(player.getLevel().getName())) {
            sender.sendMessage("§cОба угла должны находиться в одном мире.");
            return true;
        }
        String path = "mine.pos" + number;
        config.set(path + ".world", player.getLevel().getName());
        config.set(path + ".x", player.getFloorX());
        config.set(path + ".y", player.getFloorY());
        config.set(path + ".z", player.getFloorZ());
        if (config.getString("mine.pos1.world", "").equalsIgnoreCase(config.getString("mine.pos2.world", ""))) {
            config.set("mine.enabled", true);
            nextResetAt = System.currentTimeMillis() / 1000L + timerSeconds();
            config.set("mine.next-reset-at", nextResetAt);
        }
        config.save();
        sender.sendMessage("§aУгол " + number + " автошахты сохранён: " + player.getFloorX() + ", " + player.getFloorY() + ", " + player.getFloorZ() + ". Выполните /creator mine reset.");
        return true;
    }

    private boolean setLevel(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /creator mine setlevel <1–5>");
            return true;
        }
        int level;
        try { level = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { level = 0; }
        if (level < 1 || level > 5) {
            sender.sendMessage("§cУровень должен быть от 1 до 5.");
            return true;
        }
        config.set("mine.level", level);
        config.save();
        sender.sendMessage("§aАктивен уровень автошахты " + level + ". Состав берётся из mine.yml.");
        return true;
    }

    private boolean setTimer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /creator mine settimer <секунды|120s|15m|1h>");
            return true;
        }
        long seconds = parseDuration(args[1]);
        if (seconds < 60 || seconds > 86_400) {
            sender.sendMessage("§cИнтервал сброса: от 60 секунд до 24 часов.");
            return true;
        }
        config.set("mine.timer-seconds", seconds);
        nextResetAt = System.currentTimeMillis() / 1000L + seconds;
        config.set("mine.next-reset-at", nextResetAt);
        config.save();
        sender.sendMessage("§aИнтервал сброса установлен: " + seconds + " сек.");
        return true;
    }

    private boolean addOre(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /creator mine addore <руда> [вес 1–99]");
            return true;
        }
        String ore = normalizeOre(args[1]);
        if (!ORES.containsKey(ore)) {
            sender.sendMessage("§cНеизвестная руда. Доступны: " + String.join(", ", ORES.keySet()));
            return true;
        }
        int weight = 5;
        if (args.length > 2) {
            try { weight = Integer.parseInt(args[2]); } catch (NumberFormatException ex) { weight = 0; }
        }
        if (weight < 1 || weight > 99) {
            sender.sendMessage("§cВес должен быть от 1 до 99.");
            return true;
        }
        ConfigSection ores = currentOres();
        int total = weight;
        for (String key : ores.getKeys(false)) if (!key.equalsIgnoreCase(ore)) total += ores.getInt(key);
        if (total >= 100) {
            sender.sendMessage("§cСумма весов должна быть меньше 100, чтобы в шахте оставался камень.");
            return true;
        }
        config.set(oresPath() + "." + ore, weight);
        config.save();
        sender.sendMessage("§aРуда " + ore + " добавлена в уровень " + config.getInt("mine.level", 1) + " с весом " + weight + "%.");
        return true;
    }

    private boolean removeOre(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /creator mine removeore <руда>");
            return true;
        }
        String ore = normalizeOre(args[1]);
        if (!ORES.containsKey(ore) || !currentOres().exists(ore)) {
            sender.sendMessage("§cЭта руда не настроена в активном уровне.");
            return true;
        }
        config.remove(oresPath() + "." + ore);
        config.save();
        sender.sendMessage("§aРуда " + ore + " удалена из активного уровня.");
        return true;
    }

    private boolean reload(CommandSender sender) {
        config.reload();
        long saved = config.getLong("mine.next-reset-at", 0L);
        nextResetAt = saved > 0 ? saved : System.currentTimeMillis() / 1000L + timerSeconds();
        sender.sendMessage("§aКонфигурация автошахты перечитана.");
        return true;
    }

    private boolean enable(CommandSender sender, boolean value) {
        if (value && region() == null) {
            sender.sendMessage("§cСначала отметьте оба угла командой /creator mine pos1 и pos2.");
            return true;
        }
        config.set("mine.enabled", value);
        nextResetAt = System.currentTimeMillis() / 1000L + timerSeconds();
        config.set("mine.next-reset-at", nextResetAt);
        config.save();
        sender.sendMessage(value ? "§aАвтошахта включена. Выполните /creator mine reset для первого заполнения." : "§eАвтошахта выключена.");
        return true;
    }

    private boolean reset(CommandSender sender, boolean announce) {
        Region region = region();
        if (region == null) {
            if (sender != null) sender.sendMessage("§cРегион шахты не настроен. Отметьте углы командами /creator mine pos1 и pos2.");
            return true;
        }
        if (resetting) {
            if (sender != null) sender.sendMessage("§eСброс шахты уже выполняется.");
            return true;
        }
        ConfigSection ores = currentOres();
        int totalWeight = 0;
        for (String key : ores.getKeys(false)) totalWeight += Math.max(0, ores.getInt(key));
        if (totalWeight >= 100) {
            if (sender != null) sender.sendMessage("§cСумма весов руд должна быть меньше 100. Исправьте mine.yml.");
            return true;
        }
        resetting = true;
        if (announce) plugin.getServer().broadcastMessage("§bАвтошахта уровня " + config.getInt("mine.level", 1) + " обновляется. Блоки временно защищены.");
        new ResetJob(region, ores, config.getInt("mine.levels." + config.getInt("mine.level", 1) + ".stone-id", BlockID.STONE), totalWeight).schedule();
        return true;
    }

    private void tick() {
        if (!config.getBoolean("mine.enabled", false) || region() == null || resetting) return;
        long remaining = remainingSeconds();
        if (remaining <= 0) {
            reset(null, true);
        } else if (remaining == 60 || remaining == 10 || remaining == 5) {
            plugin.getServer().broadcastMessage("§eСброс автошахты через " + remaining + " сек.");
        }
    }

    private void onResetFinished(Region region) {
        resetting = false;
        nextResetAt = System.currentTimeMillis() / 1000L + timerSeconds();
        config.set("mine.next-reset-at", nextResetAt);
        config.save();
        plugin.getLogger().info("Автошахта уровня " + config.getInt("mine.level", 1) + " заполнена: " + region.volume + " блоков, мир " + region.world.getName());
        plugin.getServer().broadcastMessage("§aАвтошахта уровня " + config.getInt("mine.level", 1) + " обновлена.");
    }

    private void tickResetFailure(String reason) {
        resetting = false;
        nextResetAt = System.currentTimeMillis() / 1000L + timerSeconds();
        config.set("mine.next-reset-at", nextResetAt);
        config.save();
        plugin.getLogger().warning("Сброс автошахты остановлен: " + reason);
        plugin.getServer().broadcastMessage("§cСброс автошахты остановлен. Проверьте конфигурацию и журнал сервера.");
    }

    @EventHandler public void onBreak(BlockBreakEvent event) {
        if (inside(event.getBlock().getLevel(), event.getBlock().getFloorX(), event.getBlock().getFloorY(), event.getBlock().getFloorZ()) && resetting) event.setCancelled(true);
    }

    @EventHandler public void onPlace(BlockPlaceEvent event) {
        if (inside(event.getBlock().getLevel(), event.getBlock().getFloorX(), event.getBlock().getFloorY(), event.getBlock().getFloorZ()) && !plugin.hasPermission(event.getPlayer(), "friz.creator.mine.bypass")) event.setCancelled(true);
    }

    private boolean inside(Level level, int x, int y, int z) {
        Region r = region();
        return r != null && r.world.equals(level) && x >= r.minX && x <= r.maxX && y >= r.minY && y <= r.maxY && z >= r.minZ && z <= r.maxZ;
    }

    private Region region() {
        String world1 = config.getString("mine.pos1.world", "");
        String world2 = config.getString("mine.pos2.world", "");
        if (world1.isBlank() || !world1.equalsIgnoreCase(world2)) return null;
        Level level = plugin.getServer().getLevelByName(world1);
        if (level == null) return null;
        int x1 = config.getInt("mine.pos1.x"), y1 = config.getInt("mine.pos1.y"), z1 = config.getInt("mine.pos1.z");
        int x2 = config.getInt("mine.pos2.x"), y2 = config.getInt("mine.pos2.y"), z2 = config.getInt("mine.pos2.z");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        if (minY < -64 || maxY > 319) return null;
        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume <= 0 || volume > MAX_BLOCKS) return null;
        return new Region(level, minX, maxX, minY, maxY, minZ, maxZ, volume);
    }

    private ConfigSection currentOres() { return config.getSection(oresPath()); }
    private String oresPath() { return "mine.levels." + config.getInt("mine.level", 1) + ".ores"; }
    private int timerSeconds() { return Math.max(60, Math.min(86_400, config.getInt("mine.timer-seconds", 1_800))); }
    private long remainingSeconds() { return nextResetAt <= 0 ? 0 : Math.max(0, nextResetAt - System.currentTimeMillis() / 1000L); }
    private static long parseDuration(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).trim();
        long multiplier = 1;
        if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
        else if (s.endsWith("m")) { multiplier = 60; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("h")) { multiplier = 3_600; s = s.substring(0, s.length() - 1); }
        try { return Math.multiplyExact(Long.parseLong(s), multiplier); } catch (Exception ex) { return -1; }
    }

    private static String normalizeOre(String raw) { return raw.toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_').replace('-', '_'); }
    private static Map<String, Integer> oreIds() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("coal_ore", BlockID.COAL_ORE); map.put("deepslate_coal_ore", BlockID.DEEPSLATE_COAL_ORE);
        map.put("iron_ore", BlockID.IRON_ORE); map.put("deepslate_iron_ore", BlockID.DEEPSLATE_IRON_ORE);
        map.put("copper_ore", BlockID.COPPER_ORE); map.put("deepslate_copper_ore", BlockID.DEEPSLATE_COPPER_ORE);
        map.put("gold_ore", BlockID.GOLD_ORE); map.put("deepslate_gold_ore", BlockID.DEEPSLATE_GOLD_ORE);
        map.put("redstone_ore", BlockID.REDSTONE_ORE); map.put("deepslate_redstone_ore", BlockID.DEEPSLATE_REDSTONE_ORE);
        map.put("lapis_ore", BlockID.LAPIS_ORE); map.put("deepslate_lapis_ore", BlockID.DEEPSLATE_LAPIS_ORE);
        map.put("diamond_ore", BlockID.DIAMOND_ORE); map.put("deepslate_diamond_ore", BlockID.DEEPSLATE_DIAMOND_ORE);
        map.put("emerald_ore", BlockID.EMERALD_ORE); map.put("deepslate_emerald_ore", BlockID.DEEPSLATE_EMERALD_ORE);
        return Map.copyOf(map);
    }

    private record Region(Level world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, long volume) { }

    private final class ResetJob implements Runnable {
        private final Region region;
        private final ConfigSection ores;
        private final int stoneId;
        private final int oreWeight;
        private long cursor;

        private ResetJob(Region region, ConfigSection ores, int stoneId, int oreWeight) {
            this.region = region;
            this.ores = new ConfigSection(ores);
            this.stoneId = stoneId;
            this.oreWeight = oreWeight;
        }

        private void schedule() { plugin.getServer().getScheduler().scheduleDelayedTask(plugin, this, 1); }

        @Override public void run() {
            if (!resetting || plugin.isDisabled()) return;
            try {
                long end = Math.min(region.volume, cursor + BATCH_BLOCKS);
                int dx = region.maxX - region.minX + 1;
                int dy = region.maxY - region.minY + 1;
                for (; cursor < end; cursor++) {
                    int x = region.minX + (int)(cursor % dx);
                    int y = region.minY + (int)((cursor / dx) % dy);
                    int z = region.minZ + (int)(cursor / ((long)dx * dy));
                    int id = chooseBlock();
                    // Nukkit returns false when the target block already matches; that is not an error.
                    region.world.setBlock(x, y, z, Block.get(id), false, false);
                }
                if (cursor < region.volume) schedule(); else onResetFinished(region);
            } catch (Throwable error) {
                tickResetFailure(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }

        private int chooseBlock() {
            int roll = random.nextInt(100);
            int stoneWeight = 100 - oreWeight;
            if (roll < stoneWeight) return stoneId;
            int cursorWeight = stoneWeight;
            for (String ore : ores.getKeys(false)) {
                cursorWeight += Math.max(0, ores.getInt(ore));
                if (roll < cursorWeight) return ORES.getOrDefault(ore, stoneId);
            }
            return stoneId;
        }
    }
}
