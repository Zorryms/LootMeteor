package com.Lino.lootMeteor.listeners;

import com.Lino.lootMeteor.LootMeteor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class MeteorListener implements Listener {

    // === НАЛАШТУВАННЯ ЩИТА ===
    // радіус зони захисту навколо метеора
    private static final double PROTECTION_RADIUS = 10.0D;
    // тривалість щита (5 хвилин)
    private static final long PROTECTION_DURATION_MS = 5L * 60L * 1000L;

    // префікс у стилі Glyphera (можеш змінити як хочеш)
    private static final String PREFIX = ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "☄ Метеор " +
            ChatColor.DARK_GRAY + "» " + ChatColor.RESET;

    // список активних зон захисту
    private static final List<ProtectionZone> ZONES = new LinkedList<>();

    private final LootMeteor plugin;

    public MeteorListener(LootMeteor plugin) {
        this.plugin = plugin;

        // періодична очистка прострочених зон, щоб список не роздувався
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupExpiredZones();
            }
        }.runTaskTimer(plugin, 20L * 30L, 20L * 30L); // кожні 30 секунд
    }

    // ====== ЛОГІКА СТАНДАРТНОГО ПОВЕДІНКИ МЕТЕОРА (як і було) ======

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Fireball) {
            Fireball fireball = (Fireball) entity;
            if ("LootMeteor".equals(fireball.getCustomName())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();

        // 1) старе: не даємо самому метеору робити стандартний вибух
        if (entity instanceof Fireball) {
            Fireball fireball = (Fireball) entity;
            if ("LootMeteor".equals(fireball.getCustomName())) {
                event.setCancelled(true);
                return;
            }
        }

        // 2) нове: блочимо будь-які вибухи в зоні щита
        Location loc = event.getLocation();
        if (isLocationProtected(loc)) {
            event.setCancelled(true);
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.getWorld().equals(loc.getWorld())
                            && p.getLocation().distance(loc) <= 30)
                    .forEach(p -> p.sendMessage(PREFIX + ChatColor.RED +
                            "Вибух заблоковано — зона метеора ще захищена."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof Fireball) {
            Fireball fireball = (Fireball) event.getEntity();
            if ("LootMeteor".equals(fireball.getCustomName())) {
                // як і раніше — відміняємо стандартний хит
                event.setCancelled(true);

                // НОВЕ: створюємо зону захисту навколо точки удару
                Location impact = fireball.getLocation().getBlock().getLocation().add(0.5, 0.0, 0.5);
                addProtectionZone(impact);

                int x = impact.getBlockX();
                int y = impact.getBlockY();
                int z = impact.getBlockZ();

                Bukkit.broadcastMessage(PREFIX + ChatColor.LIGHT_PURPLE +
                        "Щит метеора активовано біля координат: " +
                        ChatColor.WHITE + x + ", " + y + ", " + z +
                        ChatColor.GRAY + " (діятиме ~5 хвилин).");
            }
        }
    }

    // ====== ЗАХИСТ БЛОКІВ У ЗОНІ ЩИТА ======

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (isLocationProtected(loc)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PREFIX + ChatColor.RED +
                    "Ця зона тимчасово захищена метеор-щитом.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Location loc = event.getBlockPlaced().getLocation();
        if (isLocationProtected(loc)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PREFIX + ChatColor.RED +
                    "Тут не можна будувати, поки активний щит метеора.");
        }
    }

    // ====== ВНУТРІШНЯ ЛОГІКА ЗОН ======

    private void addProtectionZone(Location center) {
        if (center == null || center.getWorld() == null) return;
        ZONES.add(new ProtectionZone(center, PROTECTION_RADIUS,
                System.currentTimeMillis() + PROTECTION_DURATION_MS));
    }

    private boolean isLocationProtected(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;

        long now = System.currentTimeMillis();
        boolean protectedHere = false;

        Iterator<ProtectionZone> it = ZONES.iterator();
        while (it.hasNext()) {
            ProtectionZone zone = it.next();
            if (zone.isExpired(now)) {
                it.remove();
                continue;
            }
            if (zone.contains(loc)) {
                protectedHere = true;
            }
        }
        return protectedHere;
    }

    private void cleanupExpiredZones() {
        long now = System.currentTimeMillis();
        Iterator<ProtectionZone> it = ZONES.iterator();
        while (it.hasNext()) {
            ProtectionZone zone = it.next();
            if (zone.isExpired(now)) {
                Location c = zone.getCenter();
                int x = c.getBlockX();
                int y = c.getBlockY();
                int z = c.getBlockZ();
                Bukkit.broadcastMessage(PREFIX + ChatColor.GRAY +
                        "Щит метеора біля координат " +
                        ChatColor.WHITE + x + ", " + y + ", " + z +
                        ChatColor.GRAY + " зник.");
                it.remove();
            }
        }
    }

    // маленький клас-зона
    private static class ProtectionZone {
        private final Location center;
        private final double radius;
        private final long expiresAt;

        ProtectionZone(Location center, double radius, long expiresAt) {
            this.center = center;
            this.radius = radius;
            this.expiresAt = expiresAt;
        }

        Location getCenter() {
            return center;
        }

        boolean isExpired(long now) {
            return now > expiresAt;
        }

        boolean contains(Location loc) {
            return center.getWorld().equals(loc.getWorld())
                    && center.distance(loc) <= radius;
        }
    }
}
