package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import cn.nukkit.utils.Config;
import java.io.File;
import java.math.BigInteger;
import java.util.*;

/** Offline overflow mail. Quantities are arbitrary precision and stay compact in YAML. */
public final class DeliveryManager {
    private final Config queue;
    public DeliveryManager(File folder) { queue = new Config(new File(folder, "deliveries.yml"), Config.YAML); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    public synchronized BigInteger give(Player receiver, String offlineName, Item prototype, BigInteger amount) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        BigInteger left = amount;
        if (receiver != null) {
            int attempts = 0;
            while (left.signum() > 0 && attempts++ < 54) {
                int count = left.min(BigInteger.valueOf(Math.max(1, prototype.getMaxStackSize()))).intValue();
                Item stack = prototype.clone(); stack.setCount(count);
                Item[] overflow = receiver.getInventory().addItem(stack);
                if (overflow.length == 0) { left = left.subtract(BigInteger.valueOf(count)); continue; }
                long inserted = count;
                for (Item rest : overflow) inserted -= Math.max(0, rest.getCount());
                left = left.subtract(BigInteger.valueOf(Math.max(0, inserted)));
                break;
            }
        }
        if (left.signum() > 0) enqueue(offlineName, prototype, left);
        return left;
    }
    private void enqueue(String name, Item item, BigInteger amount) {
        String path = "pending." + key(name);
        List<Map<String,Object>> rows = read(path);
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", item.getId()); row.put("damage", item.getDamage()); row.put("name", item.hasCustomName() ? item.getCustomName() : "");
        row.put("lore", Arrays.asList(item.getLore())); row.put("amount", amount.toString());
        if (item.hasCompoundTag()) row.put("nbt-base64", Base64.getEncoder().encodeToString(item.getCompoundTag()));
        ItemCatalog.Entry catalogEntry = ItemCatalog.findForItem(item);
        if (catalogEntry != null) row.put("catalog-id", catalogEntry.key());
        rows.add(row); queue.set(path, rows); queue.save();
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> read(String path) {
        List<?> raw = queue.getList(path, new ArrayList<>()); List<Map<String,Object>> out = new ArrayList<>();
        for (Object value : raw) if (value instanceof Map<?,?> map) { Map<String,Object> row = new LinkedHashMap<>(); map.forEach((k,v)->row.put(String.valueOf(k),v)); out.add(row); }
        return out;
    }
    public synchronized int deliver(Player player) {
        String path = "pending." + key(player.getName()); List<Map<String,Object>> rows = read(path); if (rows.isEmpty()) return 0;
        List<Map<String,Object>> remaining = new ArrayList<>(); int delivered = 0;
        for (Map<String,Object> row : rows) {
            BigInteger left = bigNumber(row.get("amount")); Item prototype = Item.get((int)number(row.get("id")), (int)number(row.get("damage")), 1);
            String nbt = String.valueOf(row.getOrDefault("nbt-base64", ""));
            if (!nbt.isBlank()) { try { prototype.setCompoundTag(Base64.getDecoder().decode(nbt)); } catch (IllegalArgumentException ignored) { } }
            String name = String.valueOf(row.getOrDefault("name", "")); if (!name.isBlank()) prototype.setCustomName(name);
            Object lore = row.get("lore"); if (lore instanceof List<?> list) prototype.setLore(list.stream().map(String::valueOf).toArray(String[]::new));
            String catalogId = String.valueOf(row.getOrDefault("catalog-id", ""));
            if (!catalogId.isBlank()) prototype.setNamedTag(prototype.getOrCreateNamedTag().putString("FrizCatalogId", catalogId));
            int attempts=0;
            while(left.signum()>0 && attempts++<54) {
                int count=left.min(BigInteger.valueOf(Math.max(1,prototype.getMaxStackSize()))).intValue(); Item stack=prototype.clone(); stack.setCount(count);
                Item[] overflow=player.getInventory().addItem(stack);
                if(overflow.length==0){left=left.subtract(BigInteger.valueOf(count));delivered++;continue;}
                long inserted=count; for(Item rest:overflow) inserted-=Math.max(0,rest.getCount());
                left=left.subtract(BigInteger.valueOf(Math.max(0,inserted))); break;
            }
            if(left.signum()>0){Map<String,Object> keep=new LinkedHashMap<>(row);keep.put("amount",left.toString());remaining.add(keep);}
        }
        if(remaining.isEmpty())queue.remove(path);else queue.set(path,remaining);queue.save();return delivered;
    }
    public synchronized BigInteger pending(String name) { BigInteger sum=BigInteger.ZERO;for(Map<String,Object> r:read("pending."+key(name)))sum=sum.add(bigNumber(r.get("amount")));return sum; }
    private static BigInteger bigNumber(Object value) { if(value instanceof BigInteger b)return b;if(value instanceof Number n)return BigInteger.valueOf(n.longValue());try{return new BigInteger(String.valueOf(value));}catch(Exception e){return BigInteger.ZERO;} }
    private static long number(Object value) { if(value instanceof Number n)return n.longValue();try{return Long.parseLong(String.valueOf(value));}catch(Exception e){return 0;} }
}
