package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;

import java.util.Locale;

/** Small donor and staff utility commands with donor-rank inheritance. */
public final class DonorUtilityManager {
    private final FrizProjectPlugin plugin;

    public DonorUtilityManager(FrizProjectPlugin plugin) { this.plugin = plugin; }

    public boolean execute(CommandSender sender, String command, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭта команда доступна игроку.");
            return true;
        }
        String name = command.toLowerCase(Locale.ROOT);
        switch (name) {
            case "feed" -> {
                if (!allowed(player, "friz.donor.feed", "friz.staff.feed")) return denied(player);
                player.getFoodData().setLevel(player.getFoodData().getMaxLevel());
                player.getFoodData().setFoodSaturationLevel(player.getFoodData().getMaxLevel());
                player.sendMessage("§aГолод восстановлен.");
            }
            case "heal" -> {
                if (!allowed(player, "friz.donor.heal", "friz.staff.heal")) return denied(player);
                player.setHealth(player.getMaxHealth());
                player.sendMessage("§aЗдоровье восстановлено.");
            }
            case "ec" -> {
                if (!allowed(player, "friz.donor.ec", null)) return denied(player);
                if (player.addWindow(player.getEnderChestInventory()) < 0) player.sendMessage("§cНе удалось открыть эндер-сундук.");
            }
            case "fix", "repair" -> {
                if (!allowed(player, "friz.donor.repair", null)) return denied(player);
                String mode = name.equals("fix") ? "hand" : args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
                if (mode.equals("hand")) repairHand(player);
                else if (mode.equals("all")) repairAll(player);
                else player.sendMessage("§eИспользование: /repair hand|all");
            }
            default -> { return false; }
        }
        return true;
    }

    private boolean allowed(Player player, String donorNode, String staffNode) {
        return plugin.hasDonorPermission(player, donorNode) || (staffNode != null && plugin.hasPermission(player, staffNode));
    }

    private boolean denied(Player player) {
        player.sendMessage("§cУ вашей привилегии нет доступа к этой команде.");
        return true;
    }

    private void repairHand(Player player) {
        Item item = player.getInventory().getItemInHand();
        if (item == null || item.isNull() || item.getMaxDurability() <= 0) {
            player.sendMessage("§cВ руке нет предмета с прочностью.");
            return;
        }
        item.setDamage(0);
        player.getInventory().setItemInHand(item);
        player.sendMessage("§aПредмет в руке отремонтирован.");
    }

    private void repairAll(Player player) {
        int repaired = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            Item item = inventory.getItem(slot);
            if (item != null && !item.isNull() && item.getMaxDurability() > 0 && item.getDamage() > 0) {
                item.setDamage(0);
                inventory.setItem(slot, item);
                repaired++;
            }
        }
        for (int slot = 0; slot < 4; slot++) {
            Item item = inventory.getArmorItem(slot);
            if (item != null && !item.isNull() && item.getMaxDurability() > 0 && item.getDamage() > 0) {
                item.setDamage(0);
                inventory.setArmorItem(slot, item);
                repaired++;
            }
        }
        player.sendMessage("§aОтремонтировано предметов: " + repaired + ".");
    }
}
