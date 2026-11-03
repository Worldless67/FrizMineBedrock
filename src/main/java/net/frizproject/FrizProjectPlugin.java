package net.frizproject;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandExecutor;
import cn.nukkit.command.PluginCommand;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.item.Item;
import cn.nukkit.item.ItemArmor;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.item.trim.ItemTrimMaterialType;
import cn.nukkit.item.trim.ItemTrimPatternType;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

import java.io.File;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class FrizProjectPlugin extends PluginBase implements CommandExecutor, Listener {
    private Config ranks, users, kits, cooldowns, spawn, shopConfig;
    private final Set<String> coordinateHud = ConcurrentHashMap.newKeySet();
    private final Set<String> spawnBypass = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<Integer, KitFormAction>> kitFormActions = new ConcurrentHashMap<>();
    private final Set<String> kitsBeingGranted = ConcurrentHashMap.newKeySet();
    private EconomyManager economy;
    private DeliveryManager deliveries;
    private AutoMineManager autoMine;
    private PlayerSocialManager social;
    private PlayerReportManager reports;
    private RandomTeleportManager randomTeleport;
    private RelicManager relics;
    private DonorUtilityManager donorUtilities;
    private FrizHudManager hud;
    private GuardianKitManager guardianKitManager;
    private TrapkaManager trapkaManager;

    @Override public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        saveResource("ranks.yml", false); saveResource("kits.yml", false); saveResource("mine.yml", false); saveResource("rtp.yml", false);
        ranks = new Config(new File(getDataFolder(), "ranks.yml"), Config.YAML);
        users = new Config(new File(getDataFolder(), "users.yml"), Config.YAML);
        kits = new Config(new File(getDataFolder(), "kits.yml"), Config.YAML);
        cooldowns = new Config(new File(getDataFolder(), "kit-cooldowns.yml"), Config.YAML);
        spawn = new Config(new File(getDataFolder(), "spawn.yml"), Config.YAML);
        saveResource("shop.yml", false); shopConfig = new Config(new File(getDataFolder(), "shop.yml"), Config.YAML);
        economy = new EconomyManager(getDataFolder()); deliveries = new DeliveryManager(getDataFolder());
        autoMine = new AutoMineManager(this);
        social = new PlayerSocialManager(this);
        reports = new PlayerReportManager(this);
        randomTeleport = new RandomTeleportManager(this);
        relics = new RelicManager(this);
        donorUtilities = new DonorUtilityManager(this);
        saveResource("hud.yml", false);
        hud = new FrizHudManager(this,economy,social,ranks,users);
        guardianKitManager = new GuardianKitManager(this);
        trapkaManager = new TrapkaManager(this, this, deliveries);
        String[] names = {"coords","stafftp","tphere","tpall","beacon","staffrank","rankset","kit","kits","staffchat","creator","spawn","setspawn","balance","fk","pay","shop","donateshop","mine","rtp","tpa","tpaccept","tpdeny","msg","r","ignore","ignorelist","near","ping","seen","playtime","stats","top","report","reports","helpop","feed","heal","ec","fix","repair","help","rules"};
        for (String name : names) { var registered=getCommand(name); if(registered instanceof PluginCommand<?> c)c.setExecutor(this); else getLogger().warning("Команда не зарегистрирована в plugin.yml: "+name); }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new SpawnProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(autoMine, this);
        getServer().getPluginManager().registerEvents(social, this);
        getServer().getPluginManager().registerEvents(relics, this);
        autoMine.start();
        relics.start();
        hud.start();
        guardianKitManager.start();
        trapkaManager.start();
        getServer().getScheduler().scheduleRepeatingTask(this, () -> {
            for (Player player : getServer().getOnlinePlayers().values()) if(coordinateHud.contains(key(player.getName()))) {
                String rank=users.getString("users."+key(player.getName())+".donor-rank","player");
                player.sendTip(String.format(Locale.US,"§bFrizMine §8| §f%s §7[%s] §6FK %d §8| §fY %.0f §8| §b%d/%d §8| §7%s",
                        player.getName(),rank,economy.balance(identity(player)),player.getY(),getServer().getOnlinePlayers().size(),getServer().getMaxPlayers(),LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))));
            }
        },20);
        getLogger().info("FrizProject 1.4.0 enabled. Core systems: creator grants, FK ledger, spawn protection, auto-mine, safe RTP, social commands and persistent reports.");
    }
    @Override public void onDisable(){ if(autoMine!=null)autoMine.stop(); if(social!=null)social.stop(); if(relics!=null)relics.stop(); if(hud!=null)hud.stop(); if(trapkaManager!=null)trapkaManager.stop(); if(users!=null)users.save(); if(cooldowns!=null)cooldowns.save(); if(spawn!=null)spawn.save(); }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player p=event.getPlayer(); int delivered=deliveries.deliver(p);
        if(delivered>0) p.sendMessage("§aПочта выдачи: доставлено стеков — "+delivered+".");
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { kitFormActions.remove(key(event.getPlayer().getName())); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch(command.getName().toLowerCase(Locale.ROOT)) {
            case "coords" -> coords(sender,args); case "stafftp" -> staffTp(sender,args); case "tphere" -> tpHere(sender,args); case "tpall" -> tpAll(sender);
            case "beacon" -> beacon(sender,args); case "staffrank" -> setStaffRank(sender,args); case "rankset" -> setDonorRank(sender,args);
            case "kit" -> giveKit(sender,args); case "kits" -> listKits(sender); case "staffchat" -> staffChat(sender,args);
            case "creator" -> creator(sender,args); case "spawn" -> spawnCommand(sender,args); case "setspawn" -> spawnCommand(sender,new String[]{"set"}); case "mine" -> mineCommand(sender,args);
            case "balance","fk" -> balance(sender,args); case "pay" -> pay(sender,args); case "shop" -> shop(sender,args,false); case "donateshop" -> shop(sender,args,true);
            case "tpa","tpaccept","tpdeny","msg","r","ignore","ignorelist","near","ping","seen","playtime","stats","top" -> social.execute(sender,command.getName(),args);
            case "report","reports","helpop" -> reports.execute(sender,command.getName(),args);
            case "rtp" -> randomTeleport.execute(sender,args);
            case "feed","heal","ec","fix","repair" -> donorUtilities.execute(sender,command.getName(),args);
            case "help" -> help(sender); case "rules" -> { sender.sendMessage("§6Правила: уважайте игроков, не используйте читы и дюпы, следуйте указаниям персонала. Полный текст задаётся сервером."); yield true; }
            default -> {sender.sendMessage("§cНеизвестная команда."); yield true;}
        };
    }
    private boolean coords(CommandSender sender,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("Команда доступна игроку в мире.");return true;} String k=key(p.getName());
        if(args.length==0){p.sendMessage(String.format(Locale.US,"§bКоординаты: X %.1f, Y %.1f, Z %.1f §7Мир: §f%s",p.getX(),p.getY(),p.getZ(),p.getLevel().getName()));return true;}
        if(args[0].equalsIgnoreCase("on")){coordinateHud.add(k);p.sendMessage("§aHUD координат включён.");} else if(args[0].equalsIgnoreCase("off")){coordinateHud.remove(k);p.sendMessage("§eHUD координат выключен.");} else p.sendMessage("§eИспользование: /coords [on|off]"); return true;
    }
    private boolean staffTp(CommandSender sender,String[] args){if(!require(sender,"friz.staff.tp"))return true;if(!(sender instanceof Player actor)||args.length<1){sender.sendMessage("§eИспользование: /stafftp <игрок>");return true;}Player t=getServer().getPlayer(args[0]);if(t==null){sender.sendMessage("§cИгрок не найден онлайн.");return true;}actor.teleport(t.getLocation());sender.sendMessage("§aТелепортация к "+t.getName()+".");return true;}
    private boolean tpHere(CommandSender sender,String[] args){if(!require(sender,"friz.staff.tphere"))return true;if(!(sender instanceof Player actor)||args.length<1){sender.sendMessage("§eИспользование: /tphere <игрок>");return true;}Player t=getServer().getPlayer(args[0]);if(t==null){sender.sendMessage("§cИгрок не найден онлайн.");return true;}t.teleport(actor.getLocation());sender.sendMessage("§aИгрок "+t.getName()+" телепортирован к вам.");return true;}
    private boolean tpAll(CommandSender sender){if(!require(sender,"friz.staff.tpall"))return true;if(!(sender instanceof Player actor)){sender.sendMessage("Команда доступна игроку.");return true;}for(Player t:getServer().getOnlinePlayers().values())if(!t.equals(actor))t.teleport(actor.getLocation());sender.sendMessage("§aВсе игроки телепортированы к вам.");return true;}
    private boolean beacon(CommandSender sender,String[] args){if(!require(sender,"friz.creator.beacon"))return true;if(!(sender instanceof Player p)||args.length!=1||!args[0].equalsIgnoreCase("spawn")){sender.sendMessage("§eИспользование: /beacon spawn");return true;}int x=p.getFloorX(),y=p.getFloorY()+1,z=p.getFloorZ();boolean ok=getServer().dispatchCommand(getServer().getConsoleSender(),"setblock "+x+" "+y+" "+z+" beacon");sender.sendMessage(ok?"§aКоманда установки маяка отправлена.":"§cСервер не выполнил setblock.");return true;}
    private boolean setStaffRank(CommandSender sender,String[] args){if(!require(sender,"friz.staff.rankset"))return true;if(args.length<2){sender.sendMessage("§eИспользование: /staffrank <ник> <роль>");return true;}String role=args[1].toLowerCase(Locale.ROOT);if(!ranks.exists("staff-ranks."+role)){sender.sendMessage("§cТакой должности нет в ranks.yml.");return true;}users.set("users."+key(args[0])+".staff-rank",role);users.save();sender.sendMessage("§aДолжность игрока "+args[0]+" установлена: "+role+".");return true;}
    private boolean setDonorRank(CommandSender sender,String[] args){if(!require(sender,"friz.staff.rankset"))return true;if(args.length<2){sender.sendMessage("§eИспользование: /rankset <ник> <привилегия>");return true;}String rank=args[1].toLowerCase(Locale.ROOT);if(!ranks.exists("donor-ranks."+rank)){sender.sendMessage("§cТакой привилегии нет в ranks.yml.");return true;}users.set("users."+key(args[0])+".donor-rank",rank);users.save();sender.sendMessage("§aПривилегия игрока "+args[0]+" установлена: "+rank+".");return true;}
    private boolean listKits(CommandSender sender){
        if(sender instanceof Player p){showKitCategories(p);return true;}
        sender.sendMessage("§6Обычные наборы:");for(String k:kitKeys(false))sender.sendMessage("§e/kit "+k+" §7— "+kitDisplay(k));
        sender.sendMessage("§dМедиа-наборы:");for(String k:kitKeys(true))sender.sendMessage("§e/kit "+k+" §7— "+kitDisplay(k));return true;
    }
    private boolean giveKit(CommandSender sender,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("Команда доступна игроку.");return true;}
        if(args.length<1){showKitCategories(p);return true;}return grantKit(sender,p.getName(),p,args[0],false);
    }
    private boolean grantKit(CommandSender sender,String targetName,Player target,String rawKit,boolean creatorGrant){
        String kit=key(rawKit);if(!kits.exists("kits."+kit)){sender.sendMessage("§cТакого набора нет. Используйте /kits.");return true;}
        List<KitItem> items=parseKitItems(kit,sender);if(items==null)return true;
        if(items.isEmpty()){sender.sendMessage("§cСостав этого набора ещё не настроен; кулдаун не установлен.");return true;}
        boolean creatorAccess=creatorGrant||(target!=null&&isCreator(target));
        String exactRank=kits.getString("kits."+kit+".required_donor_rank","").toLowerCase(Locale.ROOT);
        boolean rankAllowed=target!=null&&(exactRank.isBlank()?donorWeight(target.getName())>=kits.getInt("kits."+kit+".rank_weight",0)||hasPermission(target,"friz.staff.*"):users.getString("users."+key(target.getName())+".donor-rank","player").equalsIgnoreCase(exactRank));
        if(!creatorAccess&&!rankAllowed){sender.sendMessage(exactRank.isBlank()?"§cНедостаточная привилегия для этого набора.":"§cЭтот набор доступен только привилегии «"+rankDisplay(kits.getInt("kits."+kit+".rank_weight",0))+"» и Создателю.");return true;}
        List<String> unresolved=unresolvedKitItems(kit);List<String> unresolvedBehaviors=unresolvedKitBehaviors(kit);if(!creatorAccess&&(!unresolved.isEmpty()||!unresolvedBehaviors.isEmpty())){sender.sendMessage("§cПолный кит пока нельзя выдать: есть неуточнённые предметы или неподтверждённые игровые эффекты. Откройте «Посмотреть содержимое».");return true;}
        long now=System.currentTimeMillis()/1000L;long last=cooldowns.getLong("cooldowns."+key(targetName)+"."+kit,0L);long global=kits.getLong("cooldown_seconds",86400L);long cd=kits.getLong("kits."+kit+".cooldown_seconds",global);
        if(!creatorAccess&&target!=null&&now-last<cd&&!hasPermission(target,"friz.staff.*")){sender.sendMessage("§cНабор будет доступен через "+formatDuration(cd-(now-last))+".");return true;}
        String claimKey=key(targetName)+":"+kit;if(!kitsBeingGranted.add(claimKey)){sender.sendMessage("§eВыдача этого набора уже выполняется.");return true;}
        try{
            // Persist the claim before placing any item so a repeated form submission cannot duplicate a kit.
            if(!creatorAccess){cooldowns.set("cooldowns."+key(targetName)+"."+kit,now);cooldowns.save();}
            for(KitItem item:items)deliveries.give(target,targetName,item.prototype(),item.amount());
            if(creatorAccess) sender.sendMessage("§aНастроенные позиции набора выданы без кулдауна: "+kitDisplay(kit)+(unresolved.isEmpty()?"":". §eНе включены позиции: "+String.join("; ",unresolved))+(unresolvedBehaviors.isEmpty()?"":". §eНе реализованы/не подтверждены эффекты: "+String.join("; ",unresolvedBehaviors)));
            else sender.sendMessage("§aНабор выдан: "+kitDisplay(kit)+". Остаток при переполнении сохранён в почте выдачи.");
        }catch(RuntimeException ex){sender.sendMessage("§cВыдача набора прервана; кулдаун сохранён, обратитесь к Создателю перед повтором.");getLogger().warning("Kit delivery failed for "+targetName+"/"+kit+": "+ex.getMessage());}
        finally{kitsBeingGranted.remove(claimKey);}return true;
    }

    private record KitItem(Item prototype,BigInteger amount,String description) {}
    private record KitFormAction(String action,String kit,boolean media) {}

    private List<KitItem> parseKitItems(String kit,CommandSender sender){
        List<KitItem> out=new ArrayList<>();
        List<String> specs=kits.getStringList("kits."+kit+".items");
        if(!specs.isEmpty()){
            for(String raw:specs){
                String[] parts=raw.split("\\|",-1);if(parts.length<2||parts.length>4){sender.sendMessage("§cОшибка записи в kits.yml для "+kit+": ожидалось item-id|количество|чары|название.");return null;}
                String itemId=parts[0].trim();BigInteger amount=parsePositiveBig(parts[1].trim());if(amount==null){sender.sendMessage("§cНеверное количество в kits.yml для "+itemId+".");return null;}
                Item prototype=null;String catalogId="";
                try{if(itemId.startsWith("catalog:")){catalogId=itemId.substring("catalog:".length());prototype=ItemCatalog.create(catalogId,1);}else if(itemId.startsWith("minecraft:"))prototype=Item.fromString(itemId.substring("minecraft:".length()));else{sender.sendMessage("§cНеизвестный тип предмета в kits.yml: "+itemId);return null;}}catch(RuntimeException ignored){}
                if(prototype==null||prototype.isNull()||prototype.getId()==0){sender.sendMessage("§cПредмет не найден в API Nukkit-MOT: "+itemId);return null;}
                String display=parts.length>=4?parts[3].trim():prototype.getName();if(!display.isBlank())prototype.setCustomName(display);
                if(itemId.startsWith("minecraft:netherite_")&&prototype instanceof ItemArmor armor){armor.setTrim(ItemTrimPatternType.SILENCE_ARMOR_TRIM,ItemTrimMaterialType.MATERIAL_NETHERITE);}
                List<String> lore=new ArrayList<>(Arrays.asList(prototype.getLore()));String custom=applyKitEnchantments(prototype,parts.length>=3?parts[2]:"",lore);
                if(itemId.startsWith("minecraft:netherite_")){lore.add("§7Отделка: Тишина");}
                if(!custom.isBlank())prototype.getOrCreateNamedTag().putString("FrizCustomEnchantments",custom);
                String stableId=(catalogId.isBlank()?itemId.substring(itemId.indexOf(':')+1):catalogId).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]","_");
                prototype.getOrCreateNamedTag().putString("FrizKitId",kit+":"+stableId).putString("FrizItemId","friz:"+kit+"/"+stableId);
                if(!lore.isEmpty())prototype.setLore(lore.toArray(String[]::new));prototype.setCount(1);
                String ench=parts.length>=3?parts[2].trim():"";String description=display+(ench.isBlank()?"":" §7["+formatKitEnchantments(ench)+"§7]");
                out.add(new KitItem(prototype,amount,description));
            }
            return out;
        }
        for(String raw:kits.getStringList("kits."+kit+".commands")){
            String[] parts=raw.trim().split("\\s+");
            if(parts.length!=4||!parts[0].equalsIgnoreCase("give")||!parts[1].equalsIgnoreCase("%player%")){
                sender.sendMessage("§cНабор не выдан: поддерживаются только проверенные команды `give %player% <предмет> <количество>`. Исправьте kits.yml; кулдаун не установлен.");return null;
            }
            BigInteger amount=parsePositiveBig(parts[3]);Item prototype;
            try{prototype=Item.fromString(parts[2]);}catch(RuntimeException ex){prototype=null;}
            if(amount==null||prototype==null||prototype.isNull()||prototype.getId()==0){sender.sendMessage("§cНабор не выдан: неверный предмет или количество в kits.yml; кулдаун не установлен.");return null;}
            prototype.setCount(1);out.add(new KitItem(prototype,amount,prototype.getName()));
        }
        return out;
    }
    private String applyKitEnchantments(Item item,String raw,List<String> lore){
        if(raw==null||raw.isBlank())return "";List<String> custom=new ArrayList<>();
        for(String token:raw.split(",")){String[] pair=token.trim().split(":",2);if(pair.length!=2)continue;String name=pair[0].trim().toLowerCase(Locale.ROOT);int level;try{level=Integer.parseInt(pair[1].trim());}catch(NumberFormatException ex){continue;}
            if(name.equals("unbreaking")&&level==0){item.setUnbreakable(true);lore.add("§cНеразрушимость — прочность не теряется");continue;}
            Enchantment enchantment=Enchantment.getEnchantment(name);
            if(level>0&&enchantment!=null){item.addEnchantment(enchantment.setLevel(level));lore.add("§7"+enchantmentRu(name)+" "+roman(level));}
            else if(level>0){custom.add(name+":"+level);lore.add("§d"+enchantmentRu(name)+" "+roman(level)+" §8(эффект уточняется)");}
        }
        return String.join(",",custom);
    }
    private String formatKitEnchantments(String raw){List<String> out=new ArrayList<>();for(String token:raw.split(",")){String[] p=token.trim().split(":",2);if(p.length==2){try{int level=Integer.parseInt(p[1]);out.add(enchantmentRu(p[0])+" "+(level==0?"∞":roman(level)));}catch(NumberFormatException ignored){}}}return String.join("; ",out);}
    private static String enchantmentRu(String key){return switch(key.toLowerCase(Locale.ROOT)){case "quick_charge"->"Быстрая перезарядка";case "multishot"->"Залп";case "piercing"->"Бронебойность";case "sharpness"->"Острота";case "fire_aspect"->"Облик огня";case "power"->"Мощь";case "punch"->"Удар";case "flame"->"Воспламенение";case "infinity"->"Бесконечность";case "unbreaking"->"Неразрушимость";case "mending"->"Починка";case "fortune"->"Удача";case "efficiency"->"Эффективность";case "protection"->"Защита";case "projectile_protection"->"Защита от снарядов";case "blast_protection"->"Взрывоустойчивость";case "fire_protection"->"Огнеупорность";case "feather_falling"->"Лёгкость";case "soul_speed"->"Скорость души";case "depth_strider"->"Странник глубин";case "swift_sneak"->"Быстрое подкрадывание";case "impaling"->"Пронзание";case "loyalty"->"Верность";case "channeling"->"Проводник";case "sniper"->"Снайпер";case "demolition"->"Подрывник";case "scout"->"Разведчик";case "stupor"->"Ступор";case "revival"->"Оживление";case "dodge"->"Уклонение";default->key;};}
    private static String roman(int value){return switch(value){case 1->"I";case 2->"II";case 3->"III";case 4->"IV";case 5->"V";case 6->"VI";case 7->"VII";case 8->"VIII";case 9->"IX";case 10->"X";default->String.valueOf(value);};}
    private List<String> kitKeys(boolean media){
        List<String> keys=new ArrayList<>();String[] preferred=media?new String[]{"media1","media2"}:new String[]{"start","warrior","wanderer","lord","legend","prince","magister","overlord","guardian"};
        for(String k:preferred)if(kits.exists("kits."+k))keys.add(k);return keys;
    }
    private String kitDisplay(String kit){String display=kits.getString("kits."+kit+".display",kit);if(display.toLowerCase(Locale.ROOT).contains("уточняется")){if(kit.equals("start"))return "Кит Старт";if(kit.equals("media1"))return "Медиа #1";if(kit.equals("media2"))return "Медиа #2";}return display;}
    private List<String> unresolvedKitItems(String kit){return kits.getStringList("kits."+kit+".unresolved");}
    private List<String> unresolvedKitBehaviors(String kit){return kits.getStringList("kits."+kit+".unresolved_behaviors");}
    private int configuredKitItemCount(String kit){List<String> items=kits.getStringList("kits."+kit+".items");return items.isEmpty()?kits.getStringList("kits."+kit+".commands").size():items.size();}
    private String rankDisplay(int weight){
        if(ranks==null)return "Игрок";for(String rank:ranks.getSection("donor-ranks").keySet())if(ranks.getInt("donor-ranks."+rank+".weight",0)==weight)return ranks.getString("donor-ranks."+rank+".display",rank);return "Игрок";
    }
    private long kitRemaining(String player,String kit){long now=System.currentTimeMillis()/1000L,last=cooldowns.getLong("cooldowns."+key(player)+"."+kit,0L);long cd=kits.getLong("kits."+kit+".cooldown_seconds",kits.getLong("cooldown_seconds",86400L));return Math.max(0,cd-(now-last));}
    private static String formatDuration(long seconds){long h=seconds/3600,m=(seconds%3600)/60,s=seconds%60;if(h>0)return h+" ч. "+m+" мин.";if(m>0)return m+" мин.";return Math.max(1,s)+" сек.";}
    private void showKitCategories(Player player){
        List<ElementButton> buttons=List.of(new ElementButton("§aОбычные наборы\n§7Наборов: "+kitKeys(false).size()),new ElementButton("§dМедиа наборы\n§7Наборов: "+kitKeys(true).size()));
        FormWindowSimple form=new FormWindowSimple("КИТЫ","Выберите категорию",buttons);trackKitForm(player,form,new KitFormAction("categories","",false));
    }
    private void showKitList(Player player,boolean media){
        List<String> names=kitKeys(media);List<ElementButton> buttons=new ArrayList<>();
        for(String kit:names)buttons.add(new ElementButton(kitDisplay(kit)+"\n§7Нажмите, чтобы посмотреть"));
        buttons.add(new ElementButton("§8Назад"));FormWindowSimple form=new FormWindowSimple(media?"МЕДИА НАБОРЫ":"ОБЫЧНЫЕ НАБОРЫ","Выберите набор",buttons);
        trackKitForm(player,form,new KitFormAction("list","",media));
    }
    private void showKitDetail(Player player,String kit){
        boolean creator=isCreator(player);int rank=kits.getInt("kits."+kit+".rank_weight",0);String required=kits.getString("kits."+kit+".required_donor_rank","").toLowerCase(Locale.ROOT);boolean access=creator|| (required.isBlank()?donorWeight(player.getName())>=rank||hasPermission(player,"friz.staff.*"):users.getString("users."+key(player.getName())+".donor-rank","player").equalsIgnoreCase(required));
        long remaining=creator?0:kitRemaining(player.getName(),kit);List<String> unresolved=unresolvedKitItems(kit);List<String> unresolvedBehaviors=unresolvedKitBehaviors(kit);boolean complete=unresolved.isEmpty()&&unresolvedBehaviors.isEmpty();String accessLine=creator?"§aДоступ Создателя: всегда доступен, без кулдауна.":access?"§fДоступ: §aпривилегия «"+rankDisplay(rank)+"»":"§cДоступ только для привилегии «"+rankDisplay(rank)+"» и Создателя.";
        String cooldownLine=creator?"§aПолучение без кулдауна и лимита.":remaining>0?"§fПовторно через: §a"+formatDuration(remaining):complete?"§aКулдаун истёк; можно получить сейчас":"§eКулдаун истёк; получение полного состава откроется после уточнения всех позиций.";
        String stateLine=complete?"§7Позиции состава: "+configuredKitItemCount(kit):creator?"§eСоздателю выдаются настроенные позиции ("+configuredKitItemCount(kit)+"); отсутствуют: "+unresolved.size()+", неподтверждённых механик: "+unresolvedBehaviors.size()+".":"§cПолный набор не готов: неуточнённых позиций — "+unresolved.size()+", неподтверждённых механик — "+unresolvedBehaviors.size()+". Выдача приостановлена.";
        String body="§f"+kitDisplay(kit)+"\n"+accessLine+"\n"+cooldownLine+"\n"+stateLine;
        FormWindowSimple form=new FormWindowSimple(kitDisplay(kit),body,List.of(new ElementButton("§aПолучить кит"),new ElementButton("§eПосмотреть содержимое"),new ElementButton("§8Назад")));
        trackKitForm(player,form,new KitFormAction("detail",kit,kit.startsWith("media")));
    }
    private void showKitPreview(Player player,String kit){
        List<String> specs=kits.getStringList("kits."+kit+".items");List<String> commands=kits.getStringList("kits."+kit+".commands");StringBuilder body=new StringBuilder();
        if(!specs.isEmpty()){for(String spec:specs){String[] p=spec.split("\\|",-1);if(p.length<2)continue;String display=p.length>=4?p[3]:p[0];body.append("§f• ").append(display).append(" × ").append(p[1]);if(p.length>=3&&!p[2].isBlank())body.append("\n  §7").append(formatKitEnchantments(p[2]));body.append('\n');}}
        else {if(commands.isEmpty())body.append("§cСостав не настроен.\n");for(String command:commands){String[] p=command.trim().split("\\s+");if(p.length==4&&p[0].equalsIgnoreCase("give")&&p[1].equalsIgnoreCase("%player%")){String display=p[2];try{Item item=Item.fromString(p[2]);if(item!=null&&!item.isNull())display=item.getName();}catch(RuntimeException ignored){}body.append("§f• ").append(display).append(" × ").append(p[3]).append('\n');}else body.append("§7• ").append(command).append('\n');}}
        for(String unresolved:unresolvedKitItems(kit))body.append("§c• Не уточнено: ").append(unresolved).append('\n');
        for(String unresolved:unresolvedKitBehaviors(kit))body.append("§c• Эффект не готов/не проверен: ").append(unresolved).append('\n');
        if("guardian".equals(kit))body.append("§8§oЭффекты с пометкой «уточняется» не применяются.");
        FormWindowSimple form=new FormWindowSimple(kitDisplay(kit)+" — содержимое",body.toString(),List.of(new ElementButton("§8Назад")));
        trackKitForm(player,form,new KitFormAction("preview",kit,kit.startsWith("media")));
    }
    private void trackKitForm(Player player,FormWindowSimple form,KitFormAction action){int id=player.showFormWindow(form);kitFormActions.computeIfAbsent(key(player.getName()),ignored->new ConcurrentHashMap<>()).put(id,action);}
    @EventHandler public void onKitFormResponse(PlayerFormRespondedEvent event){
        Player player=event.getPlayer();String playerKey=key(player.getName());Map<Integer,KitFormAction> playerForms=kitFormActions.get(playerKey);KitFormAction action=playerForms==null?null:playerForms.remove(event.getFormID());if(playerForms!=null&&playerForms.isEmpty())kitFormActions.remove(playerKey,playerForms);if(action==null||event.wasClosed()||!(event.getResponse() instanceof FormResponseSimple response))return;
        int selected=response.getClickedButtonId();
        switch(action.action()){
            case "categories"->{if(selected==0)showKitList(player,false);else if(selected==1)showKitList(player,true);}
            case "list"->{List<String> keys=kitKeys(action.media());if(selected==keys.size())showKitCategories(player);else if(selected>=0&&selected<keys.size())showKitDetail(player,keys.get(selected));}
            case "detail"->{if(selected==0){grantKit(player,player.getName(),player,action.kit(),false);showKitDetail(player,action.kit());}else if(selected==1)showKitPreview(player,action.kit());else if(selected==2)showKitList(player,action.media());}
            case "preview"->showKitDetail(player,action.kit());
            default->{ }
        }
    }
    private boolean staffChat(CommandSender sender,String[] args){if(!require(sender,"friz.staff.chat"))return true;if(args.length==0){sender.sendMessage("§eИспользование: /staffchat <сообщение>");return true;}String msg=String.join(" ",args);for(Player p:getServer().getOnlinePlayers().values())if(hasPermission(p,"friz.staff.chat"))p.sendMessage("§c[STAFF] §f"+sender.getName()+": "+msg);return true;}
    private boolean balance(CommandSender sender,String[] args){if(args.length>0&&args[0].equalsIgnoreCase("top")){for(var row:economy.top(10))sender.sendMessage("§6"+displayIdentity(row.getKey())+" §7— §e"+row.getValue()+" FK");return true;}if(!(sender instanceof Player p)){sender.sendMessage("§eИспользование: /balance [top]");return true;}sender.sendMessage("§6Ваш баланс: §e"+economy.balance(identity(p))+" FK");return true;}
    private boolean pay(CommandSender sender,String[] args){if(!(sender instanceof Player p)||args.length<2){sender.sendMessage("§eИспользование: /pay <игрок> <FK>");return true;}Player target=getServer().getPlayer(args[0]);if(target==null){sender.sendMessage("§cПолучатель должен быть в сети.");return true;}long amount=parsePositive(args[1]);if(amount<=0){sender.sendMessage("§cСумма должна быть положительным целым числом.");return true;}if(!economy.transfer(identity(p),identity(target),amount)){sender.sendMessage("§cПеревод невозможен: проверьте баланс и сумму.");return true;}p.sendMessage("§aПереведено "+amount+" FK игроку "+target.getName()+".");target.sendMessage("§aПолучено "+amount+" FK от "+p.getName()+".");return true;}
    private boolean shop(CommandSender sender,String[] args,boolean donate){
        if(args.length>=2&&args[0].equalsIgnoreCase("buy")){if(!(sender instanceof Player p)){sender.sendMessage("Команда доступна игроку.");return true;}ItemCatalog.Entry e=ItemCatalog.find(args[1]);if(e==null){sender.sendMessage("§cНет такого предмета в каталоге.");return true;}long price=shopConfig.getLong("prices."+e.key(),e.price());if(price<=0){sender.sendMessage("§eДля этого предмета цена не задана; попросите Создателя настроить shop.yml.");return true;}boolean charged=false;try{economy.spend(identity(p),price,e.key());charged=true;Item item=ItemCatalog.create(e.key(),1);if(item==null)throw new IllegalStateException("catalog item missing");deliveries.give(p,p.getName(),item,BigInteger.ONE);p.sendMessage("§aПокупка принята: "+e.display()+" §7за "+price+" FK. При заполненном инвентаре предмет попадёт в почту выдачи.");}catch(Exception ex){if(charged)economy.change("shop",identity(p),price,"refund after failed grant");p.sendMessage("§cПокупка не выполнена: недостаточно FK или ошибка сохранения.");}return true;}
        sender.sendMessage(donate?"§6Донат-магазин — цены в FK (тестовая конфигурация):":"§6Магазин:");for(ItemCatalog.Entry e:ItemCatalog.all()){long price=shopConfig.getLong("prices."+e.key(),e.price());if(price>0)sender.sendMessage("§e/donateshop buy "+e.key()+" §7— "+e.display()+" §8("+price+" FK)");}sender.sendMessage("§7Неуказанные в ТЗ цены оставлены пустыми.");return true;
    }
    private boolean help(CommandSender sender){sender.sendMessage("§6FrizMine — доступные функции проекта");sender.sendMessage("§e/spawn, /rtp [радиус], /coords, /mine info, /help, /rules");sender.sendMessage("§e/balance, /fk top, /pay, /shop, /donateshop buy <id>");sender.sendMessage("§e/tpa, /tpaccept, /tpdeny, /msg, /r, /ignore, /near, /ping, /seen, /playtime, /stats, /top");sender.sendMessage("§e/feed, /heal, /ec, /fix, /repair hand|all");sender.sendMessage("§e/report <игрок> <причина>, /helpop <сообщение>");sender.sendMessage("§e/kits, /kit <id>, /stafftp, /tphere, /tpall, /staffchat");if(sender instanceof Player p&&isCreator(p))sender.sendMessage("§e/creator help §7— команды Создателя");return true;}
    private boolean mineCommand(CommandSender sender,String[] args){if(args.length==0||args[0].equalsIgnoreCase("info")||args[0].equalsIgnoreCase("help"))return autoMine.command(sender,args);if(!require(sender,"friz.creator.mine"))return true;return autoMine.command(sender,args);}
    private boolean spawnCommand(CommandSender sender,String[] args){
        if(args.length==0){if(!(sender instanceof Player p)){sender.sendMessage("Команда доступна игроку.");return true;}return teleportSpawn(p);}
        if(args[0].equalsIgnoreCase("bypass")){if(!(sender instanceof Player p)||!require(sender,"friz.creator.spawn"))return true;String k=key(p.getName());if(!spawnBypass.add(k)){spawnBypass.remove(k);p.sendMessage("§eОбход защиты выключен.");}else p.sendMessage("§aОбход защиты включён до выхода.");return true;}
        if(args[0].equalsIgnoreCase("set")||args[0].equalsIgnoreCase("build")||args[0].equalsIgnoreCase("protect")||args[0].equalsIgnoreCase("info")||args[0].equalsIgnoreCase("reset")||args[0].equalsIgnoreCase("expand")||args[0].equalsIgnoreCase("setregion"))return creatorSpawn(sender,args);
        sender.sendMessage("§eИспользование: /spawn [set|build|info|protect|bypass|expand <радиус>]");return true;
    }
    private boolean teleportSpawn(Player p){
        if(!spawn.exists("location.world")){p.teleport(p.getServer().getDefaultLevel().getSafeSpawn());p.sendMessage("§eТочка спавна не настроена; использована безопасная точка мира.");return true;}
        Level level=getServer().getLevelByName(spawn.getString("location.world"));if(level==null){p.sendMessage("§cМир спавна не загружен.");return true;}
        p.teleport(new Location(spawn.getDouble("location.x"),spawn.getDouble("location.y"),spawn.getDouble("location.z"),spawn.getDouble("location.yaw"),spawn.getDouble("location.pitch"),level));return true;
    }
    private boolean creatorSpawn(CommandSender sender,String[] args){
        if(!require(sender,"friz.creator.spawn"))return true;
        if(args.length==0){sender.sendMessage("§eИспользование: /creator spawn set|build|info|protect|expand <радиус>|reset");return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(!(sender instanceof Player p)){
            if(!sub.equals("build")||args.length<5){sender.sendMessage("§eИз консоли: /creator spawn build <загруженный-мир> <x> <y-основания> <z>");return true;}
            Level level=getServer().getLevelByName(args[1]);int x,y,z;try{x=Integer.parseInt(args[2]);y=Integer.parseInt(args[3]);z=Integer.parseInt(args[4]);}catch(Exception ex){sender.sendMessage("§cКоординаты должны быть целыми числами.");return true;}
            if(level==null){sender.sendMessage("§cМир не загружен: "+args[1]);return true;}
            new SpawnBuilder(level,x,y,z).build();saveSpawn(level.getName(),x+10.5,y+1,z+0.5,0,0);spawn.set("protection.enabled",true);spawn.set("protection.radius",48);spawn.save();sender.sendMessage("§aПостроен спавн 65×65 в "+level.getName()+" около "+x+","+y+","+z+"; точка появления и защита сохранены.");return true;
        }
        switch(sub){
            case "set"->{setSpawn(p,p.getFloorX(),p.getFloorY()-1,p.getFloorZ());p.sendMessage("§aТочка спавна сохранена.");}
            case "build"->{int x=p.getFloorX(),y=p.getFloorY()-1,z=p.getFloorZ();new SpawnBuilder(p.getLevel(),x,y,z).build();setSpawn(p,x+10,y,z);spawn.set("protection.enabled",true);spawn.set("protection.radius",48);spawn.save();p.sendMessage("§aПостроен спавн FrizMine 65×65 в мире "+p.getLevel().getName()+". Точка появления сохранена.");}
            case "info"->{p.sendMessage("§6Спавн: "+spawn.getString("location.world","не задан")+" "+spawn.getInt("location.x")+", "+spawn.getInt("location.y")+", "+spawn.getInt("location.z")+"; радиус защиты "+spawn.getInt("protection.radius",48));}
            case "protect"->{boolean on=!spawn.getBoolean("protection.enabled",false);spawn.set("protection.enabled",on);spawn.save();p.sendMessage(on?"§aЗащита спавна включена.":"§eЗащита спавна выключена.");}
            case "expand","setregion"->{if(args.length<2){p.sendMessage("§eИспользование: /spawn expand <радиус>");break;}int r;try{r=Integer.parseInt(args[1]);}catch(Exception e){r=0;}if(r<8||r>512){p.sendMessage("§cРадиус: 8–512.");break;}spawn.set("protection.radius",r);spawn.save();p.sendMessage("§aРадиус защиты установлен: "+r+" блоков.");}
            case "reset"->{spawn.remove("location");spawn.save();p.sendMessage("§eТочка спавна удалена.");}
            default -> p.sendMessage("§eИспользование: /spawn set|build|info|protect|expand <радиус>|reset");
        }
        return true;
    }
    private void saveSpawn(String world,double x,double y,double z,float yaw,float pitch){spawn.set("location.world",world);spawn.set("location.x",x);spawn.set("location.y",y);spawn.set("location.z",z);spawn.set("location.yaw",yaw);spawn.set("location.pitch",pitch);Level level=getServer().getLevelByName(world);if(level!=null)level.setSpawnLocation(new Vector3(x,y,z));}
    private void setSpawn(Player p,int x,int y,int z){saveSpawn(p.getLevel().getName(),x+0.5,y+1,z+0.5,(float)p.getYaw(),(float)p.getPitch());spawn.save();}
    private boolean creator(CommandSender sender,String[] args){if(!require(sender,"friz.creator.*"))return true;if(args.length==0)return creatorHelp(sender);String area=args[0].toLowerCase(Locale.ROOT);
        if(area.equals("help"))return creatorHelp(sender);
        if(area.equals("status")){sender.sendMessage("§6FrizProject status: §aFK ledger, item catalog, overflow mail, spawn builder/protection, auto-mine, safe RTP, offhand sphere/talisman stats, donor utilities, social commands, persistent reports, existing staff/kits.");sender.sendMessage("§eCustom enchantments, NPC, auction, events and quests are not enabled in this build.");return true;}
        if(area.equals("serverinfo")){sender.sendMessage("§6Online "+getServer().getOnlinePlayers().size()+"/"+getServer().getMaxPlayers()+"; worlds: "+getServer().getLevels().keySet());return true;}
        if(area.equals("announce")||area.equals("broadcast")){if(args.length<2){sender.sendMessage("§eИспользование: /creator "+area+" <текст>");return true;}getServer().broadcastMessage("§b[FrizMine] §f"+String.join(" ",Arrays.copyOfRange(args,1,args.length)));return true;}
        if(area.equals("give"))return creatorGive(sender,args);
        if(area.equals("giveall"))return creatorGiveAll(sender,args);
        if(area.equals("mine"))return autoMine.command(sender,Arrays.copyOfRange(args,1,args.length));
        if(area.equals("sphere")||area.equals("talisman")){if(args.length<4||!args[1].equalsIgnoreCase("give")){sender.sendMessage("§eИспользование: /creator "+area+" give <ник> <название>");return true;}String category=area.equals("sphere")?"spheres":"talismans";return giveCatalog(sender,args[2],args[3],category);}
        if(area.equals("shop"))return creatorShop(sender,args);
        if(area.equals("iteminfo"))return itemInfo(sender);
        if(area.equals("itemedit"))return itemEdit(sender,args);
        if(area.equals("kit"))return creatorKit(sender,args);
        if(area.equals("fk"))return creatorFk(sender,args);
        if(area.equals("spawn"))return creatorSpawn(sender,Arrays.copyOfRange(args,1,args.length));
        if(area.equals("maintenance")){if(args.length<2){sender.sendMessage("§eИспользование: /creator maintenance on|off");return true;}boolean on=args[1].equalsIgnoreCase("on");spawn.set("maintenance",on);spawn.save();sender.sendMessage(on?"§eMaintenance flag set. Admission enforcement is not implemented.":"§aMaintenance flag cleared.");return true;}
        if(area.equals("reload")){if(args.length<2||args[1].equalsIgnoreCase("all")||args[1].equalsIgnoreCase("spawn")){spawn.reload();ranks.reload();kits.reload();users.reload();sender.sendMessage("§aКонфигурации перечитаны.");}else sender.sendMessage("§cНеизвестный модуль. Доступны: all, spawn.");return true;}
        sender.sendMessage("§cЭта команда пока не реализована: /creator "+area+". Полный список готовых веток: /creator help.");return true;
    }
    private boolean creatorHelp(CommandSender s){s.sendMessage("§6Команды Creator: /creator give <ник> <предмет> <количество>, giveall [категория] <ник>, sphere give, talisman give, iteminfo, itemedit, fk give|take|set|reset, kit give, mine pos1|pos2|reset|setlevel|settimer|addore|removeore|reload, spawn set|build|info|protect|bypass|expand, status, serverinfo, announce, broadcast, maintenance, reload.");s.sendMessage("§7Выдача Creator не имеет кулдаунов или лимита суммы; переполнение уходит в deliveries.yml.");return true;}
    private boolean creatorGive(CommandSender s,String[] args){if(args.length<4){s.sendMessage("§eИспользование: /creator give <ник> <предмет|id> <количество>");return true;}BigInteger amount=parsePositiveBig(args[3]);if(amount==null){s.sendMessage("§cКоличество должно быть положительным целым числом.");return true;}return giveAny(s,args[1],args[2],amount);}
    private boolean creatorGiveAll(CommandSender s,String[] args){String category="all",target;if(args.length==2)target=args[1];else if(args.length>=3){category=args[1].toLowerCase(Locale.ROOT);target=args[2];}else{s.sendMessage("§eИспользование: /creator giveall [spheres|talismans|weapons|armor|tools|consumables|potions] <ник>");return true;}
        if(category.equals("enchants")){s.sendMessage("§cПакет тестовых книг уникальных зачарований не выдан: эти эффекты не зарегистрированы в сборке. Неизвестные эффекты из ТЗ помечены как TBD.");return true;}
        List<ItemCatalog.Entry> entries=category.equals("all")?new ArrayList<>(ItemCatalog.all()):ItemCatalog.category(category);if(entries.isEmpty()){s.sendMessage("§cПустая или неизвестная категория: "+category);return true;}
        String name=validName(target,s);if(name==null)return true;Player online=getServer().getPlayer(name);BigInteger queued=BigInteger.ZERO;int granted=0;for(ItemCatalog.Entry e:entries){Item item=ItemCatalog.create(e.key(),1);if(item==null)continue;queued=queued.add(deliveries.give(online,name,item,BigInteger.ONE));granted++;}
        s.sendMessage("§aВыдано позиций каталога: "+granted+"; ожидает в почте предметов/стеков: "+queued+".");return true;
    }
    private boolean giveCatalog(CommandSender s,String target,String raw,String category){ItemCatalog.Entry e=ItemCatalog.find(raw);if(e==null||!e.category().equals(category)){s.sendMessage("§cНе найден предмет категории "+category+": "+raw);return true;}return giveAny(s,target,e.key(),BigInteger.ONE);}
    private boolean giveAny(CommandSender s,String rawTarget,String rawItem,BigInteger amount){String target=validName(rawTarget,s);if(target==null)return true;Item item=ItemCatalog.create(rawItem,1);if(item==null){s.sendMessage("§cПредмет не найден: "+rawItem+". Используйте vanilla ID или ID каталога.");return true;}Player online=getServer().getPlayer(target);BigInteger left=deliveries.give(online,target,item,amount);s.sendMessage("§aЗапрошена выдача "+amount+" × "+item.getName()+" игроку "+target+". §7В очереди: "+left+".");if(online!=null&&left.signum()>0)online.sendMessage("§eИнвентарь заполнен; "+left+" предметов сохранено в почте выдачи.");return true;}
    private String validName(String raw,CommandSender s){if(!raw.matches("[A-Za-z0-9_]{1,16}")){s.sendMessage("§cНик должен содержать 1–16 латинских букв, цифр или _.");return null;}Player p=getServer().getPlayer(raw);return p==null?raw:p.getName();}
    private boolean creatorKit(CommandSender s,String[] args){if(args.length<4||!args[1].equalsIgnoreCase("give")){s.sendMessage("§eИспользование: /creator kit give <ник> <набор>");return true;}String name=validName(args[2],s);if(name==null)return true;Player target=getServer().getPlayer(name);return grantKit(s,target==null?name:target.getName(),target,args[3],true);}
    private boolean creatorFk(CommandSender s,String[] args){if(args.length<2){s.sendMessage("§eИспользование: /creator fk give|take|set|reset <ник> [сумма]");return true;}String op=args[1].toLowerCase(Locale.ROOT);if(args.length<3){s.sendMessage("§eУкажите ник игрока.");return true;}Player p=getServer().getPlayer(args[2]);if(p==null){s.sendMessage("§cFK операции сейчас доступны только игрокам онлайн.");return true;}String id=identity(p);long n=args.length>3?parsePositive(args[3]):0;if((op.equals("give")||op.equals("take"))&&n<=0){s.sendMessage("§cУкажите положительное целое количество.");return true;}if((op.equals("give")||op.equals("take")||op.equals("set"))&&args.length<4){s.sendMessage("§eУкажите сумму.");return true;}boolean ok=switch(op){case "give"->economy.change(s.getName(),id,n,"creator grant");case "take"->economy.change(s.getName(),id,-n,"creator take");case "set"->economy.set(s.getName(),id,n,"creator set");case "reset"->economy.set(s.getName(),id,0,"creator reset");default->false;};s.sendMessage(ok?"§aFK операция выполнена.":"§cНе удалось выполнить FK операцию.");return true;}
    private boolean itemInfo(CommandSender s){if(!(s instanceof Player p)){s.sendMessage("Команда доступна игроку.");return true;}Item i=p.getInventory().getItemInHand();ItemCatalog.Entry entry=ItemCatalog.findForItem(i);s.sendMessage("§6Предмет: "+i.getName()+"; ID "+i.getId()+":"+i.getDamage()+(entry==null?"":"; Friz ID "+entry.key())+"; количество "+i.getCount()+"; макс. стек "+i.getMaxStackSize());if(entry!=null)for(String line:entry.lore())s.sendMessage("§7"+line);return true;}
    private boolean itemEdit(CommandSender s,String[] args){if(!(s instanceof Player p)||args.length<2){s.sendMessage("§eИспользование: /creator itemedit <название>");return true;}Item i=p.getInventory().getItemInHand();if(i.getId()==0){s.sendMessage("§cВозьмите предмет в руку.");return true;}i.setCustomName(String.join(" ",Arrays.copyOfRange(args,1,args.length)));p.getInventory().setItem(p.getInventory().getHeldItemIndex(),i);s.sendMessage("§aИмя предмета изменено.");return true;}
    private boolean creatorShop(CommandSender s,String[] args){
        if(args.length>=4&&args[1].equalsIgnoreCase("price")){ItemCatalog.Entry e=ItemCatalog.find(args[2]);long price;try{price=Long.parseLong(args[3]);}catch(Exception ex){price=-1;}if(e==null||price<0){s.sendMessage("§eИспользование: /creator shop price <id> <FK >= 0>");return true;}shopConfig.set("prices."+e.key(),price);shopConfig.save();s.sendMessage("§aЦена "+e.key()+" установлена: "+price+" FK.");return true;}
        if(args.length>=2&&args[1].equalsIgnoreCase("reload")){shopConfig.reload();s.sendMessage("§ashop.yml перечитан.");return true;}
        s.sendMessage("§eИспользование: /creator shop price <id> <FK> | reload");return true;
    }
    private boolean isCreator(Player p){String role=users.getString("users."+key(p.getName())+".staff-rank","");return role.equalsIgnoreCase("creator")||role.equalsIgnoreCase("owner");}
    private boolean require(CommandSender s,String permission){if(!(s instanceof Player p)||hasPermission(p,permission))return true;s.sendMessage("§cУ вас нет прав на эту команду.");return false;}
    boolean hasPermission(Player p,String permission){String role=users.getString("users."+key(p.getName())+".staff-rank","");if(role.equals("creator")||role.equals("owner"))return true;if(role.isBlank())return false;for(String node:ranks.getStringList("staff-ranks."+role+".permissions"))if(node.equals("*")||node.equals(permission)||(node.endsWith(".*")&&permission.startsWith(node.substring(0,node.length()-1))))return true;return false;}
    int donorWeight(String name){return ranks.getInt("donor-ranks."+users.getString("users."+key(name)+".donor-rank","player")+".weight",0);}
    boolean hasDonorPermission(Player p,String permission){if(isCreator(p))return true;int weight=donorWeight(p.getName());var section=ranks.getSection("donor-ranks");if(section==null)return false;for(String donor:section.getKeys(false)){if(ranks.getInt("donor-ranks."+donor+".weight",0)>weight)continue;for(String node:ranks.getStringList("donor-ranks."+donor+".permissions"))if(matches(node,permission))return true;}return false;}
    private static boolean matches(String node,String permission){return node.equals("*")||node.equals(permission)||(node.endsWith(".*")&&permission.startsWith(node.substring(0,node.length()-1)));}
    boolean hasSpawnBypass(Player p){return isCreator(p)||spawnBypass.contains(key(p.getName()));}
    boolean isProtected(Level level,int x,int z){if(!spawn.getBoolean("protection.enabled",false)||!spawn.exists("location.world"))return false;if(!level.getName().equalsIgnoreCase(spawn.getString("location.world")))return false;int r=spawn.getInt("protection.radius",48);return Math.abs(x-spawn.getInt("location.x"))<=r&&Math.abs(z-spawn.getInt("location.z"))<=r;}
    private String identity(Player p){String id=String.valueOf(p.getUniqueId());users.set("users."+key(p.getName())+".uuid",id);users.save();return id;}
    private String displayIdentity(String uuid){var section=users.getSection("users");if(section!=null)for(String name:section.getKeys(false))if(users.getString("users."+name+".uuid","").equals(uuid))return name;return uuid;}
    private static String key(String s){return s.toLowerCase(Locale.ROOT);}
    private static BigInteger parsePositiveBig(String s){try{BigInteger n=new BigInteger(s);return n.signum()>0?n:null;}catch(Exception e){return null;}}
    private static long parsePositive(String s){try{long n=Long.parseLong(s);return n>0?n:0;}catch(Exception e){return 0;}}
}














