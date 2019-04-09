package tw.mayortw.dropup;

import java.util.HashMap;
import java.util.UUID;

import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.World;

public class DropupPlugin extends JavaPlugin implements Listener {

    private HashMap<UUID, Integer> backupTimers = new HashMap<>();

    private boolean hasMultiverse = true;

    private boolean disabled = false;
    private String disabledReason;

    @Override
    public void onEnable() {
        if(getServer().getPluginManager().getPlugin("Multiverse-Core") == null) {
            getLogger().warning("Multiverse-Core not found or not enabled, won't be able to hot-restore");
            hasMultiverse = false;
        }

        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(args.length < 1) return false;
        switch(args[0].toLowerCase()) {
            case "backup":
                {
                    if(!checkCommandPermission(sender, "dropup.backup")) return true;
                    World world = null;
                    if(args.length > 1) {
                        world = getServer().getWorld(args[1]);
                    } else if(sender instanceof Entity) {
                        world = ((Entity) sender).getLocation().getWorld();
                    } else {
                        sender.sendMessage("你沒有在一個世界，請指定一個");
                        return false;
                    }
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    backupWorld(world);
                    return true;
                }
            case "restore":
                if(!checkCommandPermission(sender, "dropup.restore")) return true;
                if(!hasMultiverse) {
                    sender.sendMessage("找不到Multiverse-Core，無法線上會回復");
                    return true;
                }
                return true;
            case "uploadspeed":
                break;
            case "downloadspeed":
                break;
            case "disable":
                if(!checkCommandPermission(sender, "dropup.disable")) return true;
                if(args.length > 1) {
                    args[0] = "";
                    disabledReason = String.join(" ", args);
                } else {
                    disabledReason = "None";
                }
                disabled = true;
                sender.sendMessage("已暫停備份。原因： " + disabledReason);
                disabledReason += " - " + sender.getName();

                return true;
            case "enable":
                if(!checkCommandPermission(sender, "dropup.disable")) return true;
                disabled = false;
                sender.sendMessage("已恢復備份");
                break;
            case "list":
                break;
            case "book":
            default:
                return false;
        }

        return false;
    }

    private boolean checkCommandPermission(CommandSender sender, String perm) {
        if(!sender.hasPermission(perm)) {
            sender.sendMessage("你沒有權限做這件事");
            return false;
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent eve) {
        Player player = eve.getPlayer();
        if(disabled && player.hasPermission("dropup.disable"))
            eve.getPlayer().sendMessage(String.format("[§e%s§f] 自動備份已被暫停。原因： §b%s", getName(), disabledReason));
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent eve) {
        if(eve.getMessage().startsWith("//")) {
            backupWorldLater(eve.getPlayer().getLocation().getWorld());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent eve) {
        backupWorldLater(eve.getBlock().getLocation().getWorld());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent eve) {
        backupWorldLater(eve.getBlock().getLocation().getWorld());
    }

    public void onDisable() {
        // Execute all backup timers with full speed
        saveConfig();
    }

    private void backupWorldLater(World world) {
        UUID id = world.getUID();
        if(backupTimers.getOrDefault(id, -1) >= 0) return;

        BukkitTask task = getServer().getScheduler().runTaskLater(this, () -> {
            backupWorld(world);

            backupTimers.put(id, -1);
        }, getConfig().getInt("min_interval", 300) * 20);
        backupTimers.put(id, task.getTaskId());
    }

    private void backupWorld(World world) {
        getServer().broadcastMessage(String.format("[§e%s§f] Backing up §a%s", getName(), world.getName()));

        world.save();
        // world.getWorldFolder()
    }
}
