package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;

public final class SpawnProtectionListener implements Listener {
    private final FrizProjectPlugin plugin;
    public SpawnProtectionListener(FrizProjectPlugin plugin) { this.plugin = plugin; }
    @EventHandler public void onBreak(BlockBreakEvent event) {
        if (plugin.isProtected(event.getBlock().getLevel(), event.getBlock().getFloorX(), event.getBlock().getFloorZ()) && !plugin.hasSpawnBypass(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onPlace(BlockPlaceEvent event) {
        if (plugin.isProtected(event.getBlock().getLevel(), event.getBlock().getFloorX(), event.getBlock().getFloorZ()) && !plugin.hasSpawnBypass(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (event.getBlock() != null && plugin.isProtected(event.getBlock().getLevel(), event.getBlock().getFloorX(), event.getBlock().getFloorZ()) && !plugin.hasSpawnBypass(event.getPlayer())) event.setCancelled(true);
    }
    @EventHandler public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && plugin.isProtected(attacker.getLevel(), attacker.getFloorX(), attacker.getFloorZ()) && !plugin.hasSpawnBypass(attacker)) event.setCancelled(true);
        if (event.getEntity() instanceof Player target && plugin.isProtected(target.getLevel(), target.getFloorX(), target.getFloorZ()) && !plugin.hasSpawnBypass(target)) event.setCancelled(true);
    }
}
