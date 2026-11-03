package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityHanging;
import cn.nukkit.item.Item;
import cn.nukkit.inventory.Inventory;
import cn.nukkit.inventory.InventoryHolder;
import cn.nukkit.level.Level;
import cn.nukkit.math.SimpleAxisAlignedBB;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.plugin.PluginBase;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Crash-recoverable, temporary 40-cube obsidian trap. Original block and block-entity state is journaled before edits. */
public final class TrapkaManager implements Listener {
    private static final int SIDE = 40;
    private static final int VOLUME = SIDE * SIDE * SIDE;
    private static final int MAGIC = 0x46525A54;
    private static final int FORMAT = 1;
    private static final int MAX_SLICE_PER_TICK = 1024;
    private static final long LIFETIME_MILLIS = 60_000L;

    private final PluginBase plugin;
    private final FrizProjectPlugin friz;
    private final DeliveryManager deliveries;
    private final File journalFolder;
    private final Map<String, Trap> traps = new ConcurrentHashMap<>();
    private final Set<String> activationLocks = ConcurrentHashMap.newKeySet();
    private int ticks;
    private boolean stopped;
    private final Set<String> verifiedSnapshots = ConcurrentHashMap.newKeySet();

    public TrapkaManager(PluginBase plugin, FrizProjectPlugin friz, DeliveryManager deliveries) {
        this.plugin = plugin;
        this.friz = friz;
        this.deliveries = deliveries;
        this.journalFolder = new File(plugin.getDataFolder(), "trapka-journal");
    }

    public void start() {
        if (!journalFolder.exists() && !journalFolder.mkdirs()) {
            plugin.getLogger().error("Не удалось создать журнал трапок: " + journalFolder);
        }
        loadJournal();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 1);
    }

    /** Returns true only after reserving a valid trap operation; the caller then consumes exactly one item. */
    public synchronized boolean activate(Player player, Item trapkaItem) {
        if (stopped || player == null || !isTrapka(trapkaItem)) return false;
        return activateAt(player.getName(), player.getLevel(), player.getFloorX(), player.getFloorY(), player.getFloorZ());
    }

    private synchronized boolean activateAt(String owner, Level level, int cx, int cy, int cz) {
        if (stopped || level == null || owner == null || owner.isBlank()) return false;
        String ownerKey = owner.toLowerCase(Locale.ROOT);
        if (!activationLocks.add(ownerKey)) {
            tell(owner, "§eВаша предыдущая трапка ещё обрабатывается.");
            return false;
        }
        Bounds bounds = new Bounds(cx - SIDE / 2, cy - SIDE / 2, cz - SIDE / 2,
                cx - SIDE / 2 + SIDE - 1, cy - SIDE / 2 + SIDE - 1, cz - SIDE / 2 + SIDE - 1);
        String refusal = validate(level, bounds);
        if (refusal != null) {
            activationLocks.remove(ownerKey);
            tell(owner, "§cТрапка не активирована: " + refusal);
            return false;
        }

        String id = UUID.randomUUID().toString();
        Trap trap = new Trap(id, owner, level.getName(), bounds, level.getProvider().getMaximumLayer() + 1,
                new File(journalFolder, id + ".snapshot.gz"), new File(journalFolder, id + ".snapshot.tmp"),
                new File(journalFolder, id + ".properties"));
        trap.level = level;
        trap.status = Status.PREPARING;
        trap.activationLock = ownerKey;
        trap.createdAt = System.currentTimeMillis();
        try {
            persist(trap); // durable reservation is written before the world can be changed
            trap.writer = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(
                    Files.newOutputStream(trap.tempSnapshot.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))));
            trap.writer.writeInt(MAGIC);
            trap.writer.writeInt(FORMAT);
            trap.writer.writeInt(SIDE);
            trap.writer.writeInt(trap.layers);
            trap.writer.writeInt(bounds.minX); trap.writer.writeInt(bounds.minY); trap.writer.writeInt(bounds.minZ);
            traps.put(id, trap);
            tell(owner, "§eТрапка принята. Сохраняю область до установки обсидиана; не размещайте и не ломайте блоки в кубе.");
            return true;
        } catch (Exception ex) {
            activationLocks.remove(ownerKey);
            closeWriter(trap);
            deleteQuietly(trap.tempSnapshot);
            deleteQuietly(trap.metaFile);
            plugin.getLogger().error("Не удалось начать журнал трапки " + id, ex);
            tell(owner, "§cНе удалось безопасно сохранить область; предмет не потрачен.");
            return false;
        }
    }

    private void tell(String playerName, String message) {
        Player player = plugin.getServer().getPlayer(playerName);
        if (player != null) player.sendMessage(message);
    }

    public void stop() {
        stopped = true;
        for (Trap trap : traps.values()) {
            closeWriter(trap);
            closeReader(trap);
        }
    }

    private boolean isTrapka(Item item) {
        ItemCatalog.Entry entry = ItemCatalog.findForItem(item);
        return entry != null && entry.key().equals("trapka");
    }

    private String validate(Level level, Bounds b) {
        if (b.minY < level.getMinBlockY() || b.maxY > level.getMaxBlockY()) return "куб 40×40×40 выходит за вертикальные границы мира";
        for (Trap active : traps.values()) {
            if (active.world.equalsIgnoreCase(level.getName()) && active.bounds.intersects(b)) return "область пересекает другую активную трапку";
        }
        int minChunkX = b.minX >> 4, maxChunkX = b.maxX >> 4;
        int minChunkZ = b.minZ >> 4, maxChunkZ = b.maxZ >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkIfLoaded(chunkX, chunkZ) == null) return "не все чанки области загружены; подойдите к месту и попробуйте снова";
            }
        }
        for (int x = b.minX; x <= b.maxX; x++) {
            for (int z = b.minZ; z <= b.maxZ; z++) {
                if (friz.isProtected(level, x, z)) return "куб пересекает защищённую область спавна";
            }
        }
        for (Entity entity : level.getNearbyEntities(new SimpleAxisAlignedBB(b.minX, b.minY, b.minZ, b.maxX + 1, b.maxY + 1, b.maxZ + 1))) {
            if (entity instanceof EntityHanging) return "в кубе есть картина или рамка; перенесите их перед установкой трапки";
        }
        return null;
    }

    private void tick() {
        if (stopped) return;
        ticks++;
        int budget = MAX_SLICE_PER_TICK * 4;
        for (Trap trap : new ArrayList<>(traps.values())) {
            if (budget <= 0) break;
            try {
                if (trap.level == null) trap.level = plugin.getServer().getLevelByName(trap.world);
                if (trap.level == null) continue;
                switch (trap.status) {
                    case PREPARING -> budget -= snapshotSlice(trap, Math.min(MAX_SLICE_PER_TICK, budget));
                    case APPLYING -> budget -= applySlice(trap, Math.min(MAX_SLICE_PER_TICK, budget));
                    case ACTIVE -> { if (System.currentTimeMillis() >= trap.expiresAt) beginRestore(trap, false); }
                    case RESTORING -> budget -= restoreSlice(trap, Math.min(MAX_SLICE_PER_TICK, budget));
                }
            } catch (Exception ex) {
                plugin.getLogger().error("Ошибка трапки " + trap.id + " («" + trap.status + "»); журнал оставлен для повтора восстановления", ex);
                closeReader(trap);
                closeWriter(trap);
                trap.lastErrorTick = ticks;
                if (trap.status == Status.PREPARING) failBeforeWorldMutation(trap, "ошибка сохранения исходных блоков");
                else if (trap.status == Status.APPLYING) beginRestore(trap, true);
                else if (trap.status == Status.RESTORING) trap.cursor = 0;
                // RESTORING remains locked; next tick retries from the beginning of the snapshot.
            }
        }
    }

    private int snapshotSlice(Trap trap, int limit) throws IOException {
        int done = 0;
        while (done < limit && trap.cursor < VOLUME) {
            int x = trap.bounds.minX + trap.cursor % SIDE;
            int z = trap.bounds.minZ + (trap.cursor / SIDE) % SIDE;
            int y = trap.bounds.minY + trap.cursor / (SIDE * SIDE);
            for (int layer = 0; layer < trap.layers; layer++) {
                Block block = trap.level.getBlock(x, y, z, layer);
                trap.writer.writeInt(block.getId());
                trap.writer.writeInt(block.getDamage());
            }
            BlockEntity entity = trap.level.getBlockEntity(new cn.nukkit.math.Vector3(x, y, z));
            if (entity == null) {
                trap.writer.writeInt(-1);
            } else {
                entity.saveNBT();
                byte[] nbt = NBTIO.write(entity.namedTag.clone());
                trap.writer.writeInt(nbt.length);
                trap.writer.write(nbt);
                if (entity instanceof InventoryHolder holder) {
                    Inventory inventory = holder.getInventory();
                    for (Player viewer : new ArrayList<>(inventory.getViewers())) viewer.removeWindow(inventory);
                }
            }
            trap.cursor++;
            done++;
        }
        if (trap.cursor == VOLUME) finishSnapshot(trap);
        return done;
    }

    private void finishSnapshot(Trap trap) throws IOException {
        closeWriter(trap);
        forceFile(trap.tempSnapshot);
        Files.move(trap.tempSnapshot.toPath(), trap.snapshot.toPath(), StandardCopyOption.ATOMIC_MOVE);
        trap.snapshotSha256 = sha256(trap.snapshot);
        verifySnapshot(trap);
        trap.status = Status.APPLYING;
        trap.cursor = 0;
        persist(trap);
    }

    private int applySlice(Trap trap, int limit) throws IOException {
        verifySnapshot(trap); // validate the complete archive before touching any block
        int done = 0;
        while (done < limit && trap.cursor < VOLUME) {
            int x = trap.bounds.minX + trap.cursor % SIDE;
            int z = trap.bounds.minZ + (trap.cursor / SIDE) % SIDE;
            int y = trap.bounds.minY + trap.cursor / (SIDE * SIDE);
            closeBlockEntity(trap.level, x, y, z);
            boolean shell = x == trap.bounds.minX || x == trap.bounds.maxX || y == trap.bounds.minY || y == trap.bounds.maxY || z == trap.bounds.minZ || z == trap.bounds.maxZ;
            for (int layer = 0; layer < trap.layers; layer++) {
                Block replacement = layer == 0 && shell ? Block.get(Block.OBSIDIAN) : Block.get(Block.AIR);
                trap.level.setBlock(x, y, z, layer, replacement, false, true);
            }
            trap.cursor++;
            done++;
        }
        if (trap.cursor == VOLUME) {
            trap.status = Status.ACTIVE;
            trap.cursor = 0;
            trap.expiresAt = System.currentTimeMillis() + LIFETIME_MILLIS;
            persist(trap);
            if (trap.activationLock != null) activationLocks.remove(trap.activationLock);
            trap.activationLock = null;
            Player owner = plugin.getServer().getPlayer(trap.owner);
            if (owner != null) owner.sendMessage("§5Трапка создана из обсидиана. Куб 40×40×40 восстановится через 60 секунд.");
        }
        return done;
    }

    private int restoreSlice(Trap trap, int limit) throws IOException {
        if (trap.reader == null) openSnapshotReader(trap);
        int done = 0;
        while (done < limit && trap.cursor < VOLUME) {
            int x = trap.bounds.minX + trap.cursor % SIDE;
            int z = trap.bounds.minZ + (trap.cursor / SIDE) % SIDE;
            int y = trap.bounds.minY + trap.cursor / (SIDE * SIDE);
            int[] ids = new int[trap.layers], data = new int[trap.layers];
            for (int layer = 0; layer < trap.layers; layer++) { ids[layer] = trap.reader.readInt(); data[layer] = trap.reader.readInt(); }
            int nbtLength = trap.reader.readInt();
            byte[] entityNbt = null;
            if (nbtLength >= 0) {
                if (nbtLength > 8 * 1024 * 1024) throw new IOException("NBT блока превышает безопасный предел: " + nbtLength);
                entityNbt = new byte[nbtLength];
                trap.reader.readFully(entityNbt);
            }
            closeBlockEntity(trap.level, x, y, z);
            for (int layer = 0; layer < trap.layers; layer++) {
                Block original = Block.get(ids[layer], data[layer]);
                if (original == null || original.getId() != ids[layer]) throw new IOException("неизвестный исходный блок ID " + ids[layer]);
                if (!trap.level.setBlock(x, y, z, layer, original, false, true)) {
                    Block after = trap.level.getBlock(x, y, z, layer);
                    if (after.getId() != ids[layer] || after.getDamage() != data[layer]) throw new IOException("ядро отказалось восстановить блок " + x + "," + y + "," + z);
                }
            }
            if (entityNbt != null) restoreBlockEntity(trap.level, x, y, z, entityNbt);
            trap.cursor++;
            done++;
        }
        if (trap.cursor == VOLUME) finishRestore(trap);
        return done;
    }

    private void restoreBlockEntity(Level level, int x, int y, int z, byte[] data) throws IOException {
        CompoundTag tag = NBTIO.read(data);
        String type = tag.getString("id");
        if (type.isBlank()) throw new IOException("у блока-сущности отсутствует ID");
        tag.putInt("x", x).putInt("y", y).putInt("z", z);
        if (BlockEntity.createBlockEntity(type, level.getChunk(x >> 4, z >> 4, true), tag) == null) {
            throw new IOException("ядро не восстановило блок-сущность типа " + type + " в " + x + "," + y + "," + z);
        }
    }

    private void beginRestore(Trap trap, boolean refundItem) {
        if (trap.status == Status.RESTORING) return;
        try {
            trap.refundOnFinish = refundItem;
            trap.status = Status.RESTORING;
            trap.cursor = 0;
            persist(trap);
            closeReader(trap);
        } catch (IOException ex) {
            plugin.getLogger().error("Не удалось записать состояние восстановления трапки " + trap.id, ex);
        }
    }

    private void finishRestore(Trap trap) throws IOException {
        closeReader(trap);
        if (trap.refundOnFinish && !trap.refunded) {
            trap.refunded = true;
            persist(trap); // at-most-once refund: crash cannot duplicate a consumed trapka
            Item item = ItemCatalog.create("trapka", 1);
            if (item != null) deliveries.give(null, trap.owner, item, java.math.BigInteger.ONE);
        }
        Files.deleteIfExists(trap.metaFile.toPath());
        deleteQuietly(trap.snapshot);
        verifiedSnapshots.remove(trap.id);
        traps.remove(trap.id);
        if (trap.activationLock != null) activationLocks.remove(trap.activationLock);
        Player owner = plugin.getServer().getPlayer(trap.owner);
        if (owner != null) owner.sendMessage(trap.refundOnFinish ? "§cТрапка прервана из-за ошибки; исходные блоки восстановлены, предмет отправлен в почту." : "§aВремя трапки истекло: исходные блоки и содержимое блоков восстановлены.");
    }

    private void failBeforeWorldMutation(Trap trap, String reason) {
        closeWriter(trap);
        deleteQuietly(trap.tempSnapshot);
        deleteQuietly(trap.snapshot);
        deleteQuietly(trap.metaFile);
        traps.remove(trap.id);
        if (trap.activationLock != null) activationLocks.remove(trap.activationLock);
        Player owner = plugin.getServer().getPlayer(trap.owner);
        if (owner != null) owner.sendMessage("§cТрапка отменена: " + reason + ". Исходный мир не менялся; предмет возвращён.");
        Item item = ItemCatalog.create("trapka", 1);
        if (item != null) deliveries.give(owner, trap.owner, item, java.math.BigInteger.ONE);
    }

    private void openSnapshotReader(Trap trap) throws IOException {
        verifySnapshot(trap);
        DataInputStream input = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(trap.snapshot.toPath()))));
        if (input.readInt() != MAGIC || input.readInt() != FORMAT || input.readInt() != SIDE || input.readInt() != trap.layers
                || input.readInt() != trap.bounds.minX || input.readInt() != trap.bounds.minY || input.readInt() != trap.bounds.minZ) {
            input.close();
            throw new IOException("заголовок журнала не совпадает с областью трапки");
        }
        trap.reader = input;
    }

    private void verifySnapshot(Trap trap) throws IOException {
        if (verifiedSnapshots.contains(trap.id)) return;
        if (!trap.snapshot.isFile() || trap.snapshotSha256 == null || !trap.snapshotSha256.equals(sha256(trap.snapshot))) {
            throw new IOException("контрольная сумма журнала не совпадает; область оставлена защищённой");
        }
        verifiedSnapshots.add(trap.id);
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void persist(Trap trap) throws IOException {
        Properties p = new Properties();
        p.setProperty("id", trap.id); p.setProperty("owner", trap.owner); p.setProperty("world", trap.world);
        p.setProperty("status", trap.status.name()); p.setProperty("minX", Integer.toString(trap.bounds.minX));
        p.setProperty("minY", Integer.toString(trap.bounds.minY)); p.setProperty("minZ", Integer.toString(trap.bounds.minZ));
        p.setProperty("maxX", Integer.toString(trap.bounds.maxX)); p.setProperty("maxY", Integer.toString(trap.bounds.maxY));
        p.setProperty("maxZ", Integer.toString(trap.bounds.maxZ)); p.setProperty("layers", Integer.toString(trap.layers));
        p.setProperty("createdAt", Long.toString(trap.createdAt)); p.setProperty("expiresAt", Long.toString(trap.expiresAt));
        p.setProperty("sha256", trap.snapshotSha256 == null ? "" : trap.snapshotSha256);
        p.setProperty("refundOnFinish", Boolean.toString(trap.refundOnFinish)); p.setProperty("refunded", Boolean.toString(trap.refunded));
        File temp = new File(journalFolder, trap.id + ".properties.tmp");
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) { p.store(output, "FrizProject Trapka recovery journal"); }
        forceFile(temp);
        moveReplace(temp, trap.metaFile);
    }

    private void loadJournal() {
        File[] files = journalFolder.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) return;
        for (File file : files) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file.toPath())) { p.load(in); }
            catch (Exception ex) { plugin.getLogger().error("Повреждён файл журнала трапки: " + file, ex); continue; }
            try {
                String id = p.getProperty("id");
                if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new IOException("неверный ID");
                File snapshot = new File(journalFolder, id + ".snapshot.gz");
                File temp = new File(journalFolder, id + ".snapshot.tmp");
                Status status = Status.valueOf(p.getProperty("status"));
                Trap trap = new Trap(id, p.getProperty("owner", ""), p.getProperty("world", ""),
                        new Bounds(Integer.parseInt(p.getProperty("minX")), Integer.parseInt(p.getProperty("minY")), Integer.parseInt(p.getProperty("minZ")),
                                Integer.parseInt(p.getProperty("maxX")), Integer.parseInt(p.getProperty("maxY")), Integer.parseInt(p.getProperty("maxZ"))),
                        Integer.parseInt(p.getProperty("layers")), snapshot, temp, file);
                trap.status = status; trap.createdAt = Long.parseLong(p.getProperty("createdAt", "0"));
                trap.expiresAt = Long.parseLong(p.getProperty("expiresAt", "0")); trap.snapshotSha256 = p.getProperty("sha256", "");
                trap.refundOnFinish = Boolean.parseBoolean(p.getProperty("refundOnFinish", "false"));
                trap.refunded = Boolean.parseBoolean(p.getProperty("refunded", "false"));
                if (status == Status.PREPARING) {
                    // No world write occurs before the snapshot is finalized and APPLYING is persisted.
                    deleteQuietly(temp); deleteQuietly(snapshot); deleteQuietly(file);
                    continue;
                }
                traps.put(id, trap);
                if (!snapshot.isFile() || trap.snapshotSha256.isBlank()) throw new IOException("отсутствует полный снимок; область остаётся закрытой для игроков");
                plugin.getLogger().warning("Найдена трапка " + id + " со статусом " + status + "; восстановление продолжится после загрузки мира " + trap.world);
            } catch (Exception ex) {
                plugin.getLogger().error("Не удалось прочитать журнал трапки " + file + "; файл оставлен для ручного восстановления", ex);
            }
        }
    }

    private static void closeBlockEntity(Level level, int x, int y, int z) {
        BlockEntity existing = level.getBlockEntity(new cn.nukkit.math.Vector3(x, y, z));
        if (existing != null) existing.close();
    }

    private static void forceFile(File file) throws IOException {
        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void moveReplace(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void closeWriter(Trap trap) { if (trap.writer != null) { try { trap.writer.close(); } catch (IOException ignored) { } trap.writer = null; } }
    private static void closeReader(Trap trap) { if (trap.reader != null) { try { trap.reader.close(); } catch (IOException ignored) { } trap.reader = null; } }
    private static void deleteQuietly(File file) { if (file != null && file.exists() && !file.delete()) file.deleteOnExit(); }

    private boolean locked(Block block) { return block != null && locked(block.getLevel(), block.getFloorX(), block.getFloorY(), block.getFloorZ()); }
    private boolean locked(Level level, int x, int y, int z) {
        if (level == null) return false;
        for (Trap trap : traps.values()) if (trap.world.equalsIgnoreCase(level.getName()) && trap.bounds.contains(x, y, z)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTrapkaUse(PlayerInteractEvent event) {
        if (event.isCancelled()) return;
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK && event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_AIR) return;
        Player player = event.getPlayer();
        String ownerKey = player.getName().toLowerCase(Locale.ROOT);
        if (activationLocks.contains(ownerKey)) { event.setCancelled(true); return; }
        Item hand = player.getInventory().getItemInHand();
        if (!isTrapka(hand)) return;
        event.setCancelled(true); // never let the carrier obsidian be placed as an ordinary block
        if (!activate(player, hand.clone())) return;
        int count = hand.getCount();
        if (count <= 1) player.getInventory().setItem(player.getInventory().getHeldItemIndex(), Item.get(Block.AIR));
        else { hand.setCount(count - 1); player.getInventory().setItem(player.getInventory().getHeldItemIndex(), hand); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (locked(player.getLevel(), player.getFloorX(), player.getFloorY(), player.getFloorZ()) || locked(event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUpdate(BlockUpdateEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBurn(BlockBurnEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFade(BlockFadeEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onForm(BlockFormEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGrow(BlockGrowEvent event) { if (locked(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpread(BlockSpreadEvent event) { if (locked(event.getBlock()) || locked(event.getSource())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFromTo(BlockFromToEvent event) { if (locked(event.getFrom()) || locked(event.getTo())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLiquidFlow(LiquidFlowEvent event) { if (locked(event.getBlock()) || locked(event.getTo())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFall(BlockFallEvent event) { if (locked(event.getBlock()) || locked(event.getBlock().down())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPiston(BlockPistonEvent event) {
        if (locked(event.getBlock())) { event.setCancelled(true); return; }
        for (Block block : event.getBlocks()) if (locked(block)) { event.setCancelled(true); return; }
        for (Block block : event.getDestroyedBlocks()) if (locked(block)) { event.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplosion(BlockExplodeEvent event) {
        if (locked(event.getBlock())) { event.setCancelled(true); return; }
        for (Block block : event.getAffectedBlocks()) if (locked(block)) { event.setCancelled(true); return; }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplosion(EntityExplodeEvent event) {
        for (Block block : event.getBlockList()) if (locked(block)) { event.setCancelled(true); return; }
    }

    private enum Status { PREPARING, APPLYING, ACTIVE, RESTORING }
    private static final class Trap {
        final String id, owner, world;
        final Bounds bounds;
        final int layers;
        final File snapshot, tempSnapshot, metaFile;
        Status status;
        Level level;
        String snapshotSha256;
        String activationLock;
        long createdAt, expiresAt, lastErrorTick;
        int cursor;
        boolean refundOnFinish, refunded;
        DataOutputStream writer;
        DataInputStream reader;
        Trap(String id, String owner, String world, Bounds bounds, int layers, File snapshot, File tempSnapshot, File metaFile) {
            this.id=id; this.owner=owner; this.world=world; this.bounds=bounds; this.layers=Math.max(1, Math.min(2, layers));
            this.snapshot=snapshot; this.tempSnapshot=tempSnapshot; this.metaFile=metaFile;
        }
    }
    private record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        boolean contains(int x,int y,int z){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
        boolean intersects(Bounds b){return minX<=b.maxX&&maxX>=b.minX&&minY<=b.maxY&&maxY>=b.minY&&minZ<=b.maxZ&&maxZ>=b.minZ;}
    }
}
