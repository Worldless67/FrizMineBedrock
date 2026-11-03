package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Persistent player reports and help requests, shared by /report, /reports and /helpop. */
public final class PlayerReportManager {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final FrizProjectPlugin plugin;
    private final Config data;

    public PlayerReportManager(FrizProjectPlugin plugin) {
        this.plugin = plugin;
        this.data = new Config(new File(plugin.getDataFolder(), "reports.yml"), Config.YAML);
        data.set("next-id", Math.max(1, data.getInt("next-id", 1)));
        data.save();
    }

    public boolean execute(CommandSender sender, String command, String[] args) {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "report" -> report(sender, args);
            case "reports" -> list(sender, false);
            case "helpop" -> helpop(sender, args);
            default -> false;
        };
    }

    private boolean report(CommandSender sender, String[] args) {
        if (args.length > 0 && isStaffAction(args[0])) return staffReport(sender, args);
        if (!(sender instanceof Player player) || args.length < 2) {
            sender.sendMessage("§eИспользование: /report <игрок> <причина> | /report info|accept|close <номер>");
            return true;
        }
        String target = args[0];
        if (!validName(target)) {
            sender.sendMessage("§cНик должен содержать 1–16 латинских букв, цифр или _. ");
            return true;
        }
        if (target.equalsIgnoreCase(player.getName())) {
            sender.sendMessage("§cНельзя пожаловаться на самого себя.");
            return true;
        }
        String reason = join(args, 1);
        if (reason.length() > 240) {
            sender.sendMessage("§cПричина слишком длинная (максимум 240 символов).");
            return true;
        }
        int id = create("report", player.getName(), target, reason);
        sender.sendMessage("§aЖалоба №" + id + " отправлена персоналу.");
        notifyStaff("§c[Жалоба №" + id + "] §f" + player.getName() + " → " + target + ": " + reason);
        return true;
    }

    private boolean helpop(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) return list(sender, true);
        if (args.length > 0 && args[0].equalsIgnoreCase("answer")) {
            if (args.length < 3) {
                sender.sendMessage("§eИспользование: /helpop answer <номер> <ответ>");
                return true;
            }
            if (!mayManage(sender)) return denied(sender);
            int id = parseId(args[1]);
            if (!isType(id, "helpop") || isClosed(id)) {
                sender.sendMessage("§cАктивное обращение helpop с таким номером не найдено.");
                return true;
            }
            String answer = join(args, 2);
            if (answer.length() > 240) {
                sender.sendMessage("§cОтвет слишком длинный (максимум 240 символов).");
                return true;
            }
            data.set(path(id) + ".answer", answer);
            data.set(path(id) + ".answered-by", sender.getName());
            data.set(path(id) + ".updated-at", System.currentTimeMillis());
            data.save();
            sender.sendMessage("§aОтвет на обращение №" + id + " сохранён.");
            Player reporter = plugin.getServer().getPlayer(data.getString(path(id) + ".reporter", ""));
            if (reporter != null) reporter.sendMessage("§b[HelpOp №" + id + "] §f" + answer);
            return true;
        }
        if (!(sender instanceof Player player) || args.length == 0) {
            sender.sendMessage("§eИспользование: /helpop <сообщение> | /helpop list | /helpop answer <номер> <ответ>");
            return true;
        }
        String message = join(args, 0);
        if (message.length() > 240) {
            sender.sendMessage("§cСообщение слишком длинное (максимум 240 символов).");
            return true;
        }
        int id = create("helpop", player.getName(), "", message);
        sender.sendMessage("§aОбращение №" + id + " отправлено персоналу.");
        notifyStaff("§b[HelpOp №" + id + "] §f" + player.getName() + ": " + message);
        return true;
    }

    private boolean staffReport(CommandSender sender, String[] args) {
        if (!mayManage(sender)) return denied(sender);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) return list(sender, false);
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /report info|accept|close <номер>");
            return true;
        }
        int id = parseId(args[1]);
        if (!exists(id)) {
            sender.sendMessage("§cЗаявка №" + id + " не найдена.");
            return true;
        }
        if (action.equals("info")) {
            sender.sendMessage("§6Заявка №" + id + " §7(" + data.getString(path(id) + ".type", "report") + ")");
            sender.sendMessage("§7Статус: §f" + data.getString(path(id) + ".status", "open") + "§7; автор: §f" + data.getString(path(id) + ".reporter", "?"));
            String target = data.getString(path(id) + ".target", "");
            if (!target.isBlank()) sender.sendMessage("§7Игрок: §f" + target);
            sender.sendMessage("§7Создано: §f" + format(data.getLong(path(id) + ".created-at", 0)));
            sender.sendMessage("§7Текст: §f" + data.getString(path(id) + ".message", ""));
            String answer = data.getString(path(id) + ".answer", "");
            if (!answer.isBlank()) sender.sendMessage("§7Ответ: §f" + answer);
            return true;
        }
        if (action.equals("accept")) {
            if (isClosed(id)) {
                sender.sendMessage("§cЗаявка уже закрыта.");
                return true;
            }
            data.set(path(id) + ".status", "accepted");
            data.set(path(id) + ".assigned-to", sender.getName());
            data.set(path(id) + ".updated-at", System.currentTimeMillis());
            data.save();
            sender.sendMessage("§aЗаявка №" + id + " принята.");
            return true;
        }
        if (action.equals("close")) {
            data.set(path(id) + ".status", "closed");
            data.set(path(id) + ".closed-by", sender.getName());
            data.set(path(id) + ".updated-at", System.currentTimeMillis());
            data.save();
            sender.sendMessage("§aЗаявка №" + id + " закрыта.");
            Player reporter = plugin.getServer().getPlayer(data.getString(path(id) + ".reporter", ""));
            if (reporter != null) reporter.sendMessage("§eВаша заявка №" + id + " закрыта персоналом.");
            return true;
        }
        sender.sendMessage("§eИспользование: /report info|accept|close <номер>");
        return true;
    }

    private boolean list(CommandSender sender, boolean helpopOnly) {
        if (!mayManage(sender)) return denied(sender);
        List<Integer> ids = new ArrayList<>();
        var reports = data.getSection("reports");
        if (reports != null) for (String key : reports.getKeys(false)) {
            int id = parseId(key);
            if (id > 0 && !isClosed(id) && (!helpopOnly || isType(id, "helpop"))) ids.add(id);
        }
        ids.sort(Comparator.reverseOrder());
        sender.sendMessage(helpopOnly ? "§6Открытые helpop обращения:" : "§6Открытые жалобы и обращения:");
        if (ids.isEmpty()) sender.sendMessage("§7Нет открытых заявок.");
        for (int id : ids.stream().limit(20).toList()) {
            String type = data.getString(path(id) + ".type", "report");
            String from = data.getString(path(id) + ".reporter", "?");
            String target = data.getString(path(id) + ".target", "");
            String message = data.getString(path(id) + ".message", "");
            sender.sendMessage("§e#" + id + " §7[" + type + "] §f" + from + (target.isBlank() ? "" : " → " + target) + "§7: " + truncate(message, 80));
        }
        return true;
    }

    private int create(String type, String reporter, String target, String message) {
        int id = Math.max(1, data.getInt("next-id", 1));
        data.set("next-id", id + 1);
        String p = path(id);
        data.set(p + ".type", type);
        data.set(p + ".reporter", reporter);
        data.set(p + ".target", target);
        data.set(p + ".message", message);
        data.set(p + ".status", "open");
        data.set(p + ".created-at", System.currentTimeMillis());
        data.save();
        return id;
    }

    private void notifyStaff(String message) {
        for (Player player : plugin.getServer().getOnlinePlayers().values())
            if (plugin.hasPermission(player, "friz.staff.reports")) player.sendMessage(message);
    }

    private boolean mayManage(CommandSender sender) {
        return !(sender instanceof Player player) || plugin.hasPermission(player, "friz.staff.reports");
    }

    private boolean denied(CommandSender sender) {
        sender.sendMessage("§cУ вас нет прав на просмотр или обработку заявок.");
        return true;
    }

    private boolean exists(int id) { return id > 0 && data.exists(path(id) + ".status"); }
    private boolean isClosed(int id) { return data.getString(path(id) + ".status", "").equalsIgnoreCase("closed"); }
    private boolean isType(int id, String type) { return data.getString(path(id) + ".type", "").equalsIgnoreCase(type); }
    private static boolean isStaffAction(String action) { return List.of("list", "info", "accept", "close").contains(action.toLowerCase(Locale.ROOT)); }
    private static int parseId(String raw) { try { return Integer.parseInt(raw); } catch (Exception ignored) { return -1; } }
    private static boolean validName(String name) { return name.matches("[A-Za-z0-9_]{1,16}"); }
    private static String path(int id) { return "reports." + id; }
    private static String join(String[] args, int start) { return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)).trim(); }
    private static String truncate(String text, int size) { return text.length() <= size ? text : text.substring(0, size - 1) + "…"; }
    private static String format(long time) { return time <= 0 ? "—" : TIME.format(Instant.ofEpochMilli(time)); }
}
