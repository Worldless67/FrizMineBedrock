package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Player-to-player travel, private messages, ignore list, sessions and basic statistics. */
public final class PlayerSocialManager implements Listener {
    private static final long TPA_TTL_MS = 60_000L;
    private final PluginBase plugin;
    private final Config data;
    private final Map<String, TeleportRequest> teleportRequests = new ConcurrentHashMap<>();
    private final Map<String, String> replyTargets = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStarts = new ConcurrentHashMap<>();
    private static final DateTimeFormatter SEEN_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public PlayerSocialManager(PluginBase plugin) {
        this.plugin = plugin;
        this.data = new Config(new File(plugin.getDataFolder(), "social.yml"), Config.YAML);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, data::save, 1200);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String k = key(player.getName());
        sessionStarts.put(k, System.currentTimeMillis());
        data.set("players." + k + ".name", player.getName());
        data.save();
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        saveSession(player);
        String k = key(player.getName());
        teleportRequests.remove(k);
        teleportRequests.entrySet().removeIf(e -> key(e.getValue().requester).equals(k));
        replyTargets.remove(k);
        replyTargets.entrySet().removeIf(e -> key(e.getValue()).equals(k));
    }

    public void stop() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) saveSession(player);
        data.save();
    }

    public long playtimeSeconds(String name) {
        String k = key(name); long stored = data.getLong("players." + k + ".playtime-seconds", 0L);
        Long start = sessionStarts.get(k);
        return stored + (start == null ? 0 : Math.max(0, (System.currentTimeMillis() - start) / 1000L));
    }
    public long kills(String name) { return data.getLong("players." + key(name) + ".kills", 0L); }
    public long deaths(String name) { return data.getLong("players." + key(name) + ".deaths", 0L); }

    boolean execute(CommandSender sender, String label, String[] args) {
        if (label.equalsIgnoreCase("top")) return top(sender);
        if (label.equalsIgnoreCase("stats")) {
            if (args.length == 0 && sender instanceof Player player) return stats(sender, player.getName());
            if (args.length == 1 && validName(args[0])) return stats(sender, args[0]);
            sender.sendMessage("§eИспользование: /stats [игрок]");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭта команда доступна только игроку.");
            return true;
        }
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "tpa" -> tpa(player, args);
            case "tpaccept" -> tpAccept(player);
            case "tpdeny" -> tpDeny(player);
            case "msg" -> message(player, args);
            case "r" -> reply(player, args);
            case "ignore" -> ignore(player, args);
            case "ignorelist" -> ignoreList(player);
            case "near" -> near(player, args);
            case "ping" -> ping(player);
            case "seen" -> seen(player, args);
            case "playtime" -> playtime(player, args);
            default -> false;
        };
    }

    @EventHandler public void onBlockBreak(BlockBreakEvent event) {
        if (!event.isCancelled()) increment(event.getPlayer().getName(), "blocks-broken");
    }

    @EventHandler public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        increment(victim.getName(), "deaths");
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent damage && damage.getDamager() instanceof Player killer && !killer.equals(victim))
            increment(killer.getName(), "kills");
    }

    private boolean stats(CommandSender sender, String name) {
        String k = key(name);
        Player online = plugin.getServer().getPlayer(name);
        long seconds = data.getLong("players." + k + ".playtime-seconds", 0L);
        if (online != null) seconds += Math.max(0, (System.currentTimeMillis() - sessionStarts.getOrDefault(k, System.currentTimeMillis())) / 1000L);
        sender.sendMessage("§6Статистика §f" + data.getString("players." + k + ".name", online == null ? name : online.getName()) + ":");
        sender.sendMessage("§7Убийств: §e" + data.getLong("players." + k + ".kills", 0L) + " §7| Смертей: §e" + data.getLong("players." + k + ".deaths", 0L));
        sender.sendMessage("§7Сломано блоков: §e" + data.getLong("players." + k + ".blocks-broken", 0L) + " §7| В игре: §e" + formatDuration(seconds));
        return true;
    }

    private boolean top(CommandSender sender) {
        var section = data.getSection("players");
        if (section == null || section.getKeys(false).isEmpty()) {
            sender.sendMessage("§7Статистика ещё не накоплена.");
            return true;
        }
        List<String> players = new ArrayList<>(section.getKeys(false));
        players.sort((a, b) -> Long.compare(data.getLong("players." + b + ".kills", 0L), data.getLong("players." + a + ".kills", 0L)));
        sender.sendMessage("§6Топ игроков по убийствам:");
        int position = 1;
        for (String player : players.stream().limit(10).toList()) {
            String name = data.getString("players." + player + ".name", player);
            sender.sendMessage("§e" + position++ + ". §f" + name + " §7— §c" + data.getLong("players." + player + ".kills", 0L));
        }
        return true;
    }

    private void increment(String name, String field) {
        String path = "players." + key(name);
        data.set(path + ".name", name);
        data.set(path + "." + field, data.getLong(path + "." + field, 0L) + 1L);
    }

    private boolean tpa(Player requester, String[] args) {
        if (args.length < 1) {
            requester.sendMessage("§eИспользование: /tpa <игрок>");
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            requester.sendMessage("§cИгрок не найден онлайн.");
            return true;
        }
        if (target.equals(requester)) {
            requester.sendMessage("§cНельзя отправить запрос самому себе.");
            return true;
        }
        if (ignored(requester, target) || ignored(target, requester)) {
            requester.sendMessage("§cОдин из игроков ограничил личные запросы.");
            return true;
        }
        teleportRequests.put(key(target.getName()), new TeleportRequest(requester.getName(), System.currentTimeMillis() + TPA_TTL_MS));
        requester.sendMessage("§aЗапрос отправлен игроку " + target.getName() + ". Он действует 60 секунд.");
        target.sendMessage("§e" + requester.getName() + " хочет телепортироваться к вам. Используйте /tpaccept или /tpdeny.");
        return true;
    }

    private boolean tpAccept(Player target) {
        TeleportRequest request = teleportRequests.remove(key(target.getName()));
        if (request == null || request.expiresAt < System.currentTimeMillis()) {
            target.sendMessage("§cАктивных запросов на телепортацию нет.");
            return true;
        }
        Player requester = plugin.getServer().getPlayer(request.requester);
        if (requester == null || ignored(requester, target) || ignored(target, requester)) {
            target.sendMessage("§cИгрок уже вышел или запрос заблокирован.");
            return true;
        }
        Location destination = target.getLocation();
        requester.teleport(destination);
        requester.sendMessage("§aЗапрос принят. Вы телепортированы к " + target.getName() + ".");
        target.sendMessage("§aЗапрос игрока " + requester.getName() + " принят.");
        return true;
    }

    private boolean tpDeny(Player target) {
        TeleportRequest request = teleportRequests.remove(key(target.getName()));
        if (request == null || request.expiresAt < System.currentTimeMillis()) {
            target.sendMessage("§cАктивных запросов на телепортацию нет.");
            return true;
        }
        target.sendMessage("§eЗапрос игрока " + request.requester + " отклонён.");
        Player requester = plugin.getServer().getPlayer(request.requester);
        if (requester != null) requester.sendMessage("§cИгрок " + target.getName() + " отклонил запрос.");
        return true;
    }

    private boolean message(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /msg <игрок> <сообщение>");
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null || target.equals(sender)) {
            sender.sendMessage("§cПолучатель не найден или совпадает с вами.");
            return true;
        }
        if (ignored(sender, target) || ignored(target, sender)) {
            sender.sendMessage("§cОдин из игроков ограничил личные сообщения.");
            return true;
        }
        String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        sender.sendMessage("§d[Вы → " + target.getName() + "] §f" + text);
        target.sendMessage("§d[" + sender.getName() + " → Вы] §f" + text);
        replyTargets.put(key(sender.getName()), target.getName());
        replyTargets.put(key(target.getName()), sender.getName());
        return true;
    }

    private boolean reply(Player sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eИспользование: /r <сообщение>");
            return true;
        }
        String recipient = replyTargets.get(key(sender.getName()));
        Player target = recipient == null ? null : plugin.getServer().getPlayer(recipient);
        if (target == null) {
            sender.sendMessage("§cНет доступного адресата для ответа.");
            return true;
        }
        return message(sender, prepend(target.getName(), args));
    }

    private boolean ignore(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("§eИспользование: /ignore <игрок>");
            return true;
        }
        String name = args[0];
        if (!validName(name) || name.equalsIgnoreCase(player.getName())) {
            player.sendMessage("§cУкажите другой корректный ник.");
            return true;
        }
        String path = "players." + key(player.getName()) + ".ignored";
        List<String> ignored = new ArrayList<>(data.getStringList(path));
        String existing = ignored.stream().filter(n -> n.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (existing == null) {
            ignored.add(name);
            player.sendMessage("§eИгрок " + name + " добавлен в игнор-лист.");
        } else {
            ignored.remove(existing);
            player.sendMessage("§aИгрок " + existing + " удалён из игнор-листа.");
        }
        data.set(path, ignored);
        data.save();
        return true;
    }

    private boolean ignoreList(Player player) {
        List<String> ignored = data.getStringList("players." + key(player.getName()) + ".ignored");
        player.sendMessage(ignored.isEmpty() ? "§7Игнор-лист пуст." : "§6Игнор-лист: §f" + String.join(", ", ignored));
        return true;
    }

    private boolean near(Player player, String[] args) {
        int radius = 50;
        if (args.length > 0) {
            try { radius = Integer.parseInt(args[0]); } catch (NumberFormatException ex) { radius = 0; }
        }
        if (radius < 1 || radius > 128) {
            player.sendMessage("§cРадиус должен быть от 1 до 128 блоков.");
            return true;
        }
        List<String> names = new ArrayList<>();
        for (Player other : plugin.getServer().getOnlinePlayers().values()) {
            if (other.equals(player) || !other.getLevel().equals(player.getLevel())) continue;
            double dx = player.getX() - other.getX(), dy = player.getY() - other.getY(), dz = player.getZ() - other.getZ();
            if (dx * dx + dy * dy + dz * dz <= (double)radius * radius) names.add(other.getName());
        }
        player.sendMessage(names.isEmpty() ? "§7Рядом игроков нет." : "§6Рядом (" + radius + " блоков): §f" + String.join(", ", names));
        return true;
    }

    private boolean ping(Player player) {
        player.sendMessage("§6Ваш ping: §e" + Math.max(0, player.getPing()) + " мс.");
        return true;
    }

    private boolean seen(Player sender, String[] args) {
        if (args.length == 0 || !validName(args[0])) {
            sender.sendMessage("§eИспользование: /seen <игрок>");
            return true;
        }
        Player online = plugin.getServer().getPlayer(args[0]);
        if (online != null) {
            sender.sendMessage("§aИгрок " + online.getName() + " сейчас в сети.");
            return true;
        }
        String k = key(args[0]);
        long time = data.getLong("players." + k + ".last-seen", 0L);
        if (time <= 0) sender.sendMessage("§7Нет сохранённых данных о входе игрока " + args[0] + ".");
        else sender.sendMessage("§7Игрок " + data.getString("players." + k + ".name", args[0]) + " был в сети " + SEEN_FORMAT.format(Instant.ofEpochSecond(time)) + ".");
        return true;
    }

    private boolean playtime(Player sender, String[] args) {
        Player target = args.length == 0 ? sender : plugin.getServer().getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cУкажите игрока онлайн; для офлайн-игроков используйте /seen.");
            return true;
        }
        String k = key(target.getName());
        long stored = data.getLong("players." + k + ".playtime-seconds", 0L);
        long start = sessionStarts.getOrDefault(k, System.currentTimeMillis());
        long total = Math.max(0, stored + (System.currentTimeMillis() - start) / 1000L);
        sender.sendMessage("§6Время игры " + target.getName() + ": §e" + formatDuration(total) + ".");
        return true;
    }

    private void saveSession(Player player) {
        String k = key(player.getName());
        long now = System.currentTimeMillis();
        Long start = sessionStarts.remove(k);
        if (start != null) data.set("players." + k + ".playtime-seconds", data.getLong("players." + k + ".playtime-seconds", 0L) + Math.max(0, (now - start) / 1000L));
        data.set("players." + k + ".name", player.getName());
        data.set("players." + k + ".last-seen", now / 1000L);
        data.save();
    }

    private boolean ignored(Player owner, Player other) {
        return data.getStringList("players." + key(owner.getName()) + ".ignored").stream().anyMatch(n -> n.equalsIgnoreCase(other.getName()));
    }

    private static String[] prepend(String head, String[] tail) {
        String[] result = new String[tail.length + 1];
        result[0] = head;
        System.arraycopy(tail, 0, result, 1, tail.length);
        return result;
    }

    private static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_]{1,16}"); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String formatDuration(long seconds) {
        long days = seconds / 86_400; seconds %= 86_400;
        long hours = seconds / 3_600; seconds %= 3_600;
        long minutes = seconds / 60;
        if (days > 0) return days + " д. " + hours + " ч. " + minutes + " мин.";
        if (hours > 0) return hours + " ч. " + minutes + " мин.";
        return minutes + " мин.";
    }

    private record TeleportRequest(String requester, long expiresAt) { }
}
