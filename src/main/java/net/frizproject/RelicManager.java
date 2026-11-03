package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.entity.Attribute;
import cn.nukkit.entity.data.EntityMovementSpeedModifier;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.item.Item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Applies catalog sphere/talisman attributes while the relic is in the Bedrock offhand slot. */
public final class RelicManager implements Listener {
    private static final String SPEED_MODIFIER = "frizproject:offhand_relic";
    private final FrizProjectPlugin plugin;
    private final Map<UUID, ActiveRelic> active = new HashMap<>();

    public RelicManager(FrizProjectPlugin plugin) { this.plugin = plugin; }

    public void start() {
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::updateOnlinePlayers, 10);
    }

    public void stop() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) update(player, Stats.NONE);
        active.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player attacker)) return;
        Stats stats = statsInOffhand(attacker);
        if (stats.damage > 0) event.setDamage(event.getDamage() + stats.damage);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { update(event.getPlayer(), statsInOffhand(event.getPlayer())); }

    @EventHandler public void onQuit(PlayerQuitEvent event) { update(event.getPlayer(), Stats.NONE); }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onIncomingDamage(EntityDamageEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Player player) || !event.canBeReducedByArmor()) return;
        Stats stats = statsInOffhand(player);
        if (stats.armor <= 0) return;
        int armor = 0, toughness = 0;
        for (Item worn : player.getInventory().getArmorContents()) {
            armor += worn.getArmorPoints();
            toughness += worn.getToughness();
        }
        float base = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
        if (base <= 0 || armor >= 30) return;
        float wanted = base * (1f - armorReduction(base, armor + stats.armor, toughness));
        float low = 0f, high = base;
        for (int i = 0; i < 18; i++) {
            float middle = (low + high) / 2f;
            float damageAfterExistingArmor = middle * (1f - armorReduction(middle, armor, toughness));
            if (damageAfterExistingArmor > wanted) high = middle;
            else low = middle;
        }
        event.setDamage((low + high) / 2f);
    }

    private void updateOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers().values()) update(player, statsInOffhand(player));
    }

    private void update(Player player, Stats next) {
        UUID uuid = player.getUniqueId();
        ActiveRelic previous = active.get(uuid);
        Stats old = previous == null ? Stats.NONE : previous.stats;
        if (old.equals(next)) return;

        int baseMaxHealth = player.getRealMaxHealth() - old.health;
        player.setMaxHealth(Math.max(1, baseMaxHealth + next.health));
        int maxHealth = player.getMaxHealth();
        player.setAttribute(Attribute.getAttribute(Attribute.MAX_HEALTH).clone().setValue(maxHealth).setDefaultValue(maxHealth));
        if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());

        player.removeMovementSpeedModifier(SPEED_MODIFIER);
        if (next.speed > 0) {
            player.addMovementSpeedModifier(new EntityMovementSpeedModifier(
                    SPEED_MODIFIER, 1f + next.speed, EntityMovementSpeedModifier.Operation.MULTIPLY));
        }
        if (next == Stats.NONE || (next.speed == 0 && next.damage == 0 && next.armor == 0 && next.health == 0)) active.remove(uuid);
        else active.put(uuid, new ActiveRelic(next));
    }

    private static Stats statsInOffhand(Player player) {
        Item item = player.getOffhandInventory().getItem(0);
        ItemCatalog.Entry entry = ItemCatalog.findForItem(item);
        if (entry == null || !(entry.category().equals("spheres") || entry.category().equals("talismans"))) return Stats.NONE;
        String key = entry.key().replaceFirst("^(sphere|talisman)_", "");
        return switch (key) {
            case "leviathan" -> new Stats(.20f, 0, 2, 0);
            case "necromancer" -> new Stats(.20f, 0, 0, 4);
            case "warlock" -> new Stats(0, 0, 2, 4);
            case "strength" -> new Stats(0, 2, 4, 0);
            case "speed" -> new Stats(.30f, 2, 0, 0);
            case "toughness" -> new Stats(0, 4, 0, 6);
            case "punisher" -> new Stats(.20f, -4, 7, 0);
            case "guardian" -> new Stats(.20f, 4, 3, 4);
            case "athena" -> new Stats(.20f, 2, 4, 0);
            default -> Stats.NONE;
        };
    }

    private static float armorReduction(float damage, int armor, int toughness) {
        int clampedArmor = Math.max(0, Math.min(30, armor));
        int clampedToughness = Math.max(0, Math.min(20, toughness));
        float effective = Math.max(clampedArmor * .2f, clampedArmor - damage / (2f + clampedToughness / 4f));
        return Math.min(20f, effective) / 25f;
    }

    private record ActiveRelic(Stats stats) { }
    private record Stats(float speed, int health, int damage, int armor) {
        private static final Stats NONE = new Stats(0, 0, 0, 0);
    }
}
