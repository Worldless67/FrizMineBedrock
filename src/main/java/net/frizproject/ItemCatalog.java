package net.frizproject;

import cn.nukkit.item.Item;
import java.util.*;

/** Stable server-side catalogue. Custom entries use named vanilla carriers until the host
 * exposes a Bedrock custom-item registry; names and lore remain available on every client. */
public final class ItemCatalog {
    public record Entry(String key, String display, String base, String category, long price, List<String> lore) {}
    private static final LinkedHashMap<String, Entry> ITEMS = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();
    private static void add(String key, String display, String base, String category, long price, String... lore) {
        Entry e = new Entry(key, display, base, category, price, List.of(lore));
        ITEMS.put(key, e); ALIASES.put(normalize(key), key); String plain=display.replaceAll("§.", "").trim(); ALIASES.put(normalize(plain), key); ALIASES.put(normalize(plain.replaceFirst("^(Сфера|Талисман)\\s+", "")), key);
    }
    private static String normalize(String s) { return s.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_'); }
    static {
        add("sphere_leviathan", "§bСфера Левиафана", "nautilus_shell", "spheres", 449, "+20% скорость; +2 урона");
        add("sphere_necromancer", "§5Сфера Некроманта", "nautilus_shell", "spheres", 449, "+20% скорость; +4 брони");
        add("sphere_warlock", "§8Сфера Варлока", "nautilus_shell", "spheres", 449, "+2 урона; +4 брони");
        add("sphere_strength", "§cСфера Силы", "nautilus_shell", "spheres", 549, "+2 здоровья; +4 урона");
        add("sphere_speed", "§aСфера Скорости", "nautilus_shell", "spheres", 549, "+30% скорость; +2 здоровья");
        add("sphere_toughness", "§7Сфера Стойкости", "nautilus_shell", "spheres", 549, "+4 здоровья; +6 брони");
        add("sphere_punisher", "§4Сфера Карателя", "nautilus_shell", "spheres", 1099, "+20% скорость; +7 урона; −4 здоровья");
        add("sphere_guardian", "§6Сфера Хранителя", "nautilus_shell", "spheres", 1599, "+20% скорость; +4 здоровья; +3 урона; +4 брони");
        add("sphere_athena", "§eСфера Афины", "nautilus_shell", "spheres", 849, "+20% скорость; +2 здоровья; +4 урона");
        add("sphere_fin", "§fСфера Фин", "nautilus_shell", "spheres", 0, "Характеристики не заданы в ТЗ.");
        add("talisman_leviathan", "§bТалисман Левиафана", "totem", "talismans", 449, "+20% скорость; +2 урона");
        add("talisman_necromancer", "§5Талисман Некроманта", "totem", "talismans", 449, "+20% скорость; +4 брони");
        add("talisman_warlock", "§8Талисман Варлока", "totem", "talismans", 449, "+2 урона; +4 брони");
        add("talisman_strength", "§cТалисман Силы", "totem", "talismans", 549, "+2 здоровья; +4 урона");
        add("talisman_speed", "§aТалисман Скорости", "totem", "talismans", 549, "+30% скорость; +2 здоровья");
        add("talisman_toughness", "§7Талисман Стойкости", "totem", "talismans", 549, "+4 здоровья; +6 брони");
        add("talisman_punisher", "§4Талисман Карателя", "totem", "talismans", 1099, "+20% скорость; +7 урона; −4 здоровья");
        add("talisman_guardian", "§6Талисман Хранителя", "totem", "talismans", 1599, "+20% скорость; +4 здоровья; +3 урона; +4 брони");
        add("talisman_athena", "§eТалисман Афины", "totem", "talismans", 849, "+20% скорость; +2 здоровья; +4 урона");
        add("sword_farmila", "§dМеч Фармилы", "diamond_sword", "weapons", 1499, "Острота X; Небесная кара VII; Добыча V; Разящий клинок V; Неразрушимость III; Починка", "+16 урона");
        add("hammer_ascension", "§6Молот Возвышения", "mace", "weapons", 1699, "Острота VII; Неразрушимость V; Починка", "+15 урона; Вампиризм I; Яд I; Окисление II");
        add("trident_overlord", "§5Трезубец Властелина", "trident", "weapons", 1599, "Пронзание VI; Верность IV; Проводник; Притяжение; Ступор; Неразрушимость V; Починка");
        add("sword_guardian", "§6Меч Хранителя", "diamond_sword", "weapons", 999, "Острота VII; Облик огня III; Окисление II; Вампиризм I; Яд I", "+16 урона");
        add("trident_guardian", "§6Трезубец Хранителя", "trident", "weapons", 999, "Пронзание VII; Верность V; Проводник; Подрывник I; Разведчик II; Ступор III");
        add("pickaxe_bulldozer", "§6Кирка Бульдозер", "diamond_pickaxe", "tools", 1499, "Удача V; Неразрушимость V; Починка; Эффективность X; Магнит I; Опытный III; Автоплавка I; Бульдозер I");
        add("miners_pride", "§bГордость Шахтёра", "diamond_pickaxe", "tools", 999, "Удача IV; Эффективность VII; Автоплавка I; Магнит I");
        add("mega_bulldozer", "§cМега Бульдозер", "diamond_pickaxe", "tools", 1999, "Область 9×9; Бульдозер II; Магнит I");
        add("iron_will", "§7Железная воля", "iron_pickaxe", "tools", 799, "Удача III; Эффективность VII");
        add("gods_touch", "§dБожье касание", "netherite_pickaxe", "tools", 1499, "Ломает спавнеры; шанс получить соответствующего моба: 50%");
        add("fruit_hoe", "§aМотыга плодов", "diamond_hoe", "tools", 699, "Удача III; Эффективность X");
        add("sand_magnate", "§eПесочный магнат", "diamond_shovel", "tools", 699, "Удача III; Эффективность X");
        add("lumberjack_delight", "§2Радость лесоруба", "diamond_axe", "tools", 899, "Дровосек I; 30% шанс удвоить древесину");
add("guardian_helmet", "§6Шлем Хранителя", "diamond_helmet", "armor", 0, "Комплект Хранителя: Защита V; Огнеупорность V; Защита от снарядов V; Взрывоустойчивость V; Оживление I; Нерушимость");
        add("guardian_chestplate", "§6Нагрудник Хранителя", "diamond_chestplate", "armor", 0, "Комплект Хранителя; точные части комплекта описаны в ТЗ частично");
        add("guardian_leggings", "§6Поножи Хранителя", "diamond_leggings", "armor", 0, "Комплект Хранителя; точные части комплекта описаны в ТЗ частично");
        add("guardian_boots", "§6Ботинки Хранителя", "diamond_boots", "armor", 0, "Комплект Хранителя; точные части комплекта описаны в ТЗ частично");
        add("overlord_helmet", "§5Шлем Властелина", "diamond_helmet", "armor", 0, "Защита V; прочие чары по ТЗ");
        add("overlord_chestplate", "§5Нагрудник Властелина", "diamond_chestplate", "armor", 0, "Защита V; прочие чары по ТЗ");
        add("overlord_leggings", "§5Поножи Властелина", "diamond_leggings", "armor", 0, "Быстрое подкрадывание; прочие чары по ТЗ");
        add("overlord_boots", "§5Ботинки Властелина", "diamond_boots", "armor", 0, "Лёгкость IV; Скорость души II; Странник глубин IV");
        add("guardian_elytra", "§6Элитры Хранителя", "elytra", "armor", 999, "Неразрушимость X; Починка");
        add("gods_aura", "§dБожья аура", "amethyst_shard", "consumables", 99, "Радиус 5; очищает негативные эффекты; усиления на 20 секунд");
        add("trapka", "§5Трапка", "obsidian", "consumables", 0, "40×40×40 блоков; обсидиановая коробка на 60 секунд; затем мир восстанавливается");
        add("bright_dust", "§eЯвная пыль", "glowstone_dust", "consumables", 79, "Подсветка игроков в радиусе 10 блоков; боевой режим");
        add("disorientation", "§5Дезориентация", "ender_pearl", "consumables", 99, "Радиус 10; блокировка элитр, жемчуга и хорусов");
        add("fire_tornado", "§cОгненный смерч", "fire_charge", "consumables", 129, "Поджигает игроков в радиусе 10 блоков");
        add("paralysis_snowball", "§bСнежок паралича", "snowball", "consumables", 79, "Медлительность III и слепота на 5 секунд");
        add("layer", "§7Пласт", "obsidian", "consumables", 99, "Обсидиановый слой по направлению взгляда");
        add("repairer", "§aРемонтник", "anvil", "consumables", 99, "При броске под себя ремонтирует надетую броню");
        add("mystic_egg", "§dПризывное мистическое яйцо", "egg", "consumables", 199, "Призывает мистический сундук соответствующей редкости");
        add("mystic_summoner", "§5Призывной мистик", "nether_star", "consumables", 299, "Призывает сундук с легендарным или случайным уровнем лута");
        add("potion_strength_iii", "§cНесоздаваемое зелье Силы III", "potion", "potions", 149, "Сила III — 8 минут");
        add("potion_speed_iii", "§bНесоздаваемое зелье Скорости III", "potion", "potions", 149, "Скорость III — 8 минут");
        add("potion_regeneration_iii", "§dНесоздаваемое зелье Регенерации", "potion", "potions", 149, "Регенерация — 30 секунд");
        add("potion_invisibility_iii", "§7Несоздаваемое зелье Невидимости", "potion", "potions", 149, "Невидимость — 8 минут");
        add("potion_fire_resistance_iii", "§6Несоздаваемое зелье Огнестойкости", "potion", "potions", 149, "Огнестойкость — 8 минут");
        add("potion_health_boost_ii", "§dНесоздаваемое зелье Прилива здоровья II", "potion", "potions", 149, "Прилив здоровья II — 30 секунд");
        add("potion_resistance_ii", "§8Несоздаваемое зелье Сопротивления II", "potion", "potions", 149, "Сопротивление II — 30 секунд");
        // Existing pack assets not otherwise detailed in the attachment remain creatable.
        String[] assets = {"aegis_sphere", "andromeda_sphere", "athena_sphere", "titan_sphere", "athena_talisman", "punisher_talisman", "crusher_talisman", "drill_pickaxe", "sharpness_vii_book", "meteor_shard", "volcano_core", "soul_flower", "dragon_egg", "magic_gate_key", "rare_cache", "event_token", "collector_soul", "ender_tear", "alchemist_potion", "auction_ticket", "buyer_coin", "boss_key", "beacon_core", "warden_eye", "frost_rune", "ember_rune", "void_crystal", "storm_orb", "guardian_charm", "phoenix_feather", "shadow_fragment", "ancient_relic", "mythic_scroll", "rune_case", "reinforced_ingot", "nether_star_fragment", "enchanted_dust", "treasure_map", "arena_pass", "clan_banner", "legendary_chest", "meteor_pickaxe", "soul_compass", "event_crown", "ancient_coin", "abyssal_pearl", "mystic_gem", "friz_token"};
        for (String key : assets) ITEMS.putIfAbsent(key, new Entry(key, "§d" + title(key), "amethyst_shard", "catalog", 0, List.of("Предмет из исходного Resource Pack; эффект не задан в ТЗ.")));
    }
    private static String title(String s) { StringBuilder b = new StringBuilder(); for (String p : s.split("_")) b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' '); return b.toString().trim(); }
    public static Entry find(String raw) { String key = ALIASES.get(normalize(raw)); return key == null ? ITEMS.get(normalize(raw)) : ITEMS.get(key); }
    public static Entry findForItem(Item item) {
        if (item == null || item.isNull()) return null;
        if (item.hasCompoundTag()) {
            var tag = item.getNamedTag();
            if (tag != null && tag.containsString("FrizCatalogId")) {
                Entry tagged = ITEMS.get(tag.getString("FrizCatalogId"));
                if (tagged != null) return tagged;
            }
        }
        if (!item.hasCustomName()) return null;
        return find(item.getCustomName().replaceAll("§.", ""));
    }
    public static Collection<Entry> all() { return Collections.unmodifiableCollection(ITEMS.values()); }
    public static List<Entry> category(String category) { return ITEMS.values().stream().filter(e -> e.category().equalsIgnoreCase(category)).toList(); }
    public static Item create(String raw, int count) {
        Entry e = find(raw);
        try {
            Item item = Item.fromString(e == null ? raw : e.base());
            if (item == null || item.getId() == 0) return null;
            item.setCount(Math.max(1, Math.min(count, item.getMaxStackSize())));
            if (e != null) {
                item.setCustomName(e.display());
                item.setLore(e.lore().toArray(String[]::new));
                item.setNamedTag(item.getOrCreateNamedTag().putString("FrizCatalogId", e.key()));
            }
            return item;
        } catch (RuntimeException ex) { return null; }
    }
    private ItemCatalog() {}
}




