package net.frizproject;

import cn.nukkit.utils.Config;
import java.io.File;
import java.util.Locale;

/** Synchronized FK ledger; every committed operation records a compact audit row. */
public final class EconomyManager {
    private final Config balances;
    private final File journal;
    public EconomyManager(File folder) {
        this.balances = new Config(new File(folder, "fk.yml"), Config.YAML);
        this.journal = new File(folder, "fk-history.log");
    }
    private static String key(String id) { return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_"); }
    public synchronized long balance(String identity) { return balances.getLong("balances." + key(identity), 0L); }
    public synchronized boolean set(String actor, String target, long amount, String reason) {
        if (amount < 0) return false;
        balances.set("balances." + key(target), amount); balances.save(); record(actor, "set", target, amount, reason); return true;
    }
    public synchronized boolean change(String actor, String target, long delta, String reason) {
        long old = balance(target);
        if (delta < 0 && old < -delta) return false;
        long next;
        try { next = Math.addExact(old, delta); } catch (ArithmeticException ex) { return false; }
        if (next < 0) return false;
        balances.set("balances." + key(target), next); balances.save(); record(actor, delta >= 0 ? "give" : "take", target, delta, reason); return true;
    }
    public synchronized boolean transfer(String sender, String receiver, long amount) {
        if (amount <= 0 || sender.equalsIgnoreCase(receiver) || balance(sender) < amount || balance(receiver) > Long.MAX_VALUE - amount) return false;
        balances.set("balances." + key(sender), balance(sender)-amount);
        balances.set("balances." + key(receiver), balance(receiver)+amount);
        balances.save(); record(sender,"pay",receiver,amount,"player transfer"); return true;
    }
    public synchronized void spend(String actor, long amount, String reason) {
        if (amount < 0 || balance(actor) < amount) throw new IllegalStateException("insufficient FK");
        balances.set("balances." + key(actor), balance(actor)-amount); balances.save(); record(actor,"purchase","catalog",amount,reason);
    }
    public synchronized java.util.List<java.util.Map.Entry<String,Long>> top(int limit) {
        java.util.List<java.util.Map.Entry<String,Long>> rows = new java.util.ArrayList<>();
        var section = balances.getSection("balances"); if (section != null) section.getKeys(false).forEach(k -> rows.add(java.util.Map.entry(k, balances.getLong("balances."+k))));
        rows.sort(java.util.Map.Entry.<String,Long>comparingByValue().reversed()); return rows.subList(0, Math.min(limit, rows.size()));
    }
    private void record(String actor,String action,String target,long amount,String reason) {
        try { java.nio.file.Files.writeString(journal.toPath(), System.currentTimeMillis()+"\t"+actor+"\t"+action+"\t"+target+"\t"+amount+"\t"+reason+System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }
        catch (Exception ignored) { }
    }
}
