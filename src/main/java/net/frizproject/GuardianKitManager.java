package net.frizproject;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerItemConsumeEvent;
import cn.nukkit.item.Item;
import cn.nukkit.potion.Effect;
import cn.nukkit.plugin.PluginBase;

/** Applies configured effects to custom, non-craftable potion carriers. */
public final class GuardianKitManager implements Listener {
    private final PluginBase plugin;

    public GuardianKitManager(PluginBase plugin) { this.plugin = plugin; }

    public void start() { plugin.getServer().getPluginManager().registerEvents(this, plugin); }

    @EventHandler public void onPotionConsume(PlayerItemConsumeEvent event) {
        Item item = event.getItem();
        ItemCatalog.Entry entry = ItemCatalog.findForItem(item);
        if (entry == null) return;
        int effectId;
        int durationSeconds;
        int amplifier;
        switch (entry.key()) {
            case "potion_strength_iii" -> { effectId = Effect.STRENGTH; amplifier = 2; durationSeconds = 480; }
            case "potion_speed_iii" -> { effectId = Effect.SPEED; amplifier = 2; durationSeconds = 480; }
            case "potion_invisibility_iii" -> { effectId = Effect.INVISIBILITY; amplifier = 0; durationSeconds = 480; }
            case "potion_fire_resistance_iii" -> { effectId = Effect.FIRE_RESISTANCE; amplifier = 0; durationSeconds = 480; }
            case "potion_regeneration_iii" -> { effectId = Effect.REGENERATION; amplifier = 0; durationSeconds = 30; }
            case "potion_health_boost_ii" -> { effectId = Effect.HEALTH_BOOST; amplifier = 1; durationSeconds = 30; }
            case "potion_resistance_ii" -> { effectId = Effect.RESISTANCE; amplifier = 1; durationSeconds = 30; }
            default -> { return; }
        }
        Effect base = Effect.getEffect(effectId);
        if (base == null) return;
        event.getPlayer().addEffect(base.clone().setDuration(durationSeconds * 20).setAmplifier(amplifier).setVisible(true));
    }
}
