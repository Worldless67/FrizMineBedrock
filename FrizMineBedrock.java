package ru.frizmin;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.potion.Effect;
import cn.nukkit.utils.TextFormat;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;

public class FrizMineBedrock extends PluginBase {

    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        this.luckPerms = LuckPermsProvider.get();
        getLogger().info("FrizMineBedrock включён! Все системы готовы.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "kit":
                handleKit(player);
                return true;
            case "grant":
                handleGrant(player, args);
                return true;
            case "talisman":
                handleTalisman(player, args);
                return true;
            case "tp":
                handleTp(player, args);
                return true;
            case "vanish":
                handleVanish(player);
                return true;
            case "blacktire":
                handleTire(player, "black");
                return true;
            case "watertire":
                handleTire(player, "water");
                return true;
            case "lavatire":
                handleTire(player, "lava");
                return true;
            default:
                return false;
        }
    }

    private void handleKit(Player player) {
        String rank = getPlayerGroup(player);
        if (rank.equalsIgnoreCase("owner")) {
            player.getInventory().addItem(Item.get(Item.NETHERITE_SWORD));
            player.getInventory().addItem(Item.get(Item.NETHERITE_PICKAXE));
            player.getInventory().addItem(Item.get(Item.ENCHANTED_GOLDEN_APPLE, 0, 16));
            player.getInventory().addItem(Item.get(Item.ELYTRA));
            player.sendMessage(TextFormat.GOLD + "Кит Создателя выдан!");
        } else if (rank.equalsIgnoreCase("prince") || rank.equalsIgnoreCase("knyaz")) {
            player.getInventory().addItem(Item.get(Item.DIAMOND_SWORD));
            player.getInventory().addItem(Item.get(Item.DIAMOND_PICKAXE));
            player.getInventory().addItem(Item.get(Item.GOLDEN_APPLE, 0, 8));
            player.getInventory().addItem(createTalisman("crusher"));
            player.sendMessage(TextFormat.LIGHT_PURPLE + "Кит Князя выдан (с талисманом)!");
        } else {
            player.getInventory().addItem(Item.get(Item.STONE_SWORD));
            player.getInventory().addItem(Item.get(Item.STONE_PICKAXE));
            player.getInventory().addItem(Item.get(Item.BREAD, 0, 16));
            player.sendMessage(TextFormat.GREEN + "Стартовый кит обычного игрока выдан.");
        }
    }

    private void handleGrant(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(TextFormat.RED + "Использование: /grant <игрок> <привилегия>");
            player.sendMessage(TextFormat.YELLOW + "Доступно: warrior, wanderer, lord, legend, prince, magister, overlord, keeper, owner");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(TextFormat.RED + "Игрок не найден.");
            return;
        }
        String groupName = args[1].toLowerCase();
        setPlayerGroup(target, groupName);
        player.sendMessage(TextFormat.GREEN + "Привилегия " + groupName + " выдана игроку " + target.getName());
        target.sendMessage(TextFormat.GOLD + "Вам выдана привилегия: " + groupName);
    }

    private void handleTalisman(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(TextFormat.RED + "Использование: /talisman <crusher|punisher|wrath|vortex|discord|tyrant|gloom|demon>");
            return;
        }
        Item talisman = createTalisman(args[0].toLowerCase());
        if (talisman == null) {
            player.sendMessage(TextFormat.RED + "Талисман не найден.");
            return;
        }
        player.getInventory().addItem(talisman);
        player.sendMessage(TextFormat.GREEN + "Талисман " + args[0] + " выдан.");
    }

    private Item createTalisman(String id) {
        Item item = Item.get(Item.TOTEM_OF_UNDYING);
        switch (id) {
            case "crusher": item.setCustomName(TextFormat.RED + "Талисман Крушителя"); break;
            case "punisher": item.setCustomName(TextFormat.DARK_PURPLE + "Талисман Карателя"); break;
            case "wrath": item.setCustomName(TextFormat.YELLOW + "Талисман Ярости"); break;
            case "vortex": item.setCustomName(TextFormat.AQUA + "Талисман Вихря"); break;
            case "discord": item.setCustomName(TextFormat.LIGHT_PURPLE + "Талисман Раздора"); break;
            case "tyrant": item.setCustomName(TextFormat.GOLD + "Талисман Тирана"); break;
            case "gloom": item.setCustomName(TextFormat.DARK_GRAY + "Талисман Мрака"); break;
            case "demon": item.setCustomName(TextFormat.DARK_RED + "Талисман Демона"); break;
            default: return null;
        }
        return item;
    }

    private void handleTp(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(TextFormat.RED + "Использование: /tp <игрок>");
            return;
        }
        Player target = getServer().getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(TextFormat.RED + "Игрок не найден.");
            return;
        }
        player.teleport(target.getLocation());
        player.sendMessage(TextFormat.GREEN + "Телепорт к " + target.getName());
    }

    private void handleVanish(Player player) {
        if (player.hasEffect(Effect.INVISIBILITY)) {
            player.removeEffect(Effect.INVISIBILITY);
            player.sendMessage(TextFormat.GREEN + "Невидимость выключена.");
        } else {
            player.addEffect(Effect.getEffect(Effect.INVISIBILITY).setDuration(Integer.MAX_VALUE).setVisible(false));
            player.sendMessage(TextFormat.GREEN + "Невидимость включена.");
        }
    }

    private void handleTire(Player player, String type) {
        Item item = Item.get(Item.TNT);
        switch (type) {
            case "black": item.setCustomName(TextFormat.DARK_GRAY + "Black Tire"); break;
            case "water": item.setCustomName(TextFormat.AQUA + "Water Tire"); break;
            case "lava": item.setCustomName(TextFormat.GOLD + "Lava Tire"); break;
        }
        player.getInventory().addItem(item);
        player.sendMessage(TextFormat.GREEN + "Взрывчатка " + type + " выдана.");
    }

    private String getPlayerGroup(Player player) {
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
        return user.getPrimaryGroup();
    }

    private void setPlayerGroup(Player player, String groupName) {
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) return;
        InheritanceNode node = InheritanceNode.builder(groupName).build();
        user.data().add(node);
        luckPerms.getUserManager().saveUser(user);
    }
}
