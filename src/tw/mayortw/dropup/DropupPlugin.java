package tw.mayortw.dropup;
/*
 * Written by R26
 */

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
import org.bukkit.World;

public class DropupPlugin extends JavaPlugin implements Listener {

    private WorldUploader worldUploader;

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

        try {
            worldUploader = new WorldUploader(this);
        } catch (com.dropbox.core.DbxException e) {
            getLogger().severe("Dropbox login error: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(args.length < 1) return false;
        switch(args[0].toLowerCase()) {
            case "backup":
            case "bk":
                {
                    if(!checkCommandPermission(sender, "dropup.backup")) return true;
                    World world = null;
                    if(args.length > 1) {
                        world = getServer().getWorld(args[1]);
                    } else if(sender instanceof Entity) {
                        world = ((Entity) sender).getLocation().getWorld();
                    } else {
                        sender.sendMessage("你沒有在一個世界，請指定一個");
                        return true;
                    }
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    worldUploader.backupWorld(world, true);
                    return true;
                }
            case "restore":
            case "re":
                if(!checkCommandPermission(sender, "dropup.restore")) return true;
                if(!hasMultiverse) {
                    sender.sendMessage("找不到Multiverse-Core，無法線上會回復");
                    return true;
                }
                return true;
            case "uploadspeed":
            case "us":
                if(args.length <= 1) return false;
                try {
                    int speed = Integer.parseInt(args[1]);
                    getConfig().set("upload_speed", speed);
                    sender.sendMessage("上傳速度設為：" + (speed > 0 ? speed : "無限制"));
                    worldUploader.setUploadSpeed(speed);
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "downloadspeed":
            case "ds":
                if(args.length <= 1) return false;
                try {
                    int speed = Integer.parseInt(args[1]);
                    getConfig().set("download_speed", speed);
                    sender.sendMessage("下載速度設為：" + (speed > 0 ? speed : "無限制"));
                    worldUploader.setUploadSpeed(speed);
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "disable":
                if(!checkCommandPermission(sender, "dropup.disable")) return true;
                if(args.length > 1) {
                    args[0] = "";
                    disabledReason = String.join(" ", args);
                } else {
                    disabledReason = "None";
                }
                disabled = true;
                sender.sendMessage("已暫停自動備份。原因： " + disabledReason);
                disabledReason += " - " + sender.getName();

                return true;
            case "enable":
                if(!checkCommandPermission(sender, "dropup.disable")) return true;
                disabled = false;
                sender.sendMessage("已恢復自動備份");
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
            worldUploader.backupWorldLater(eve.getPlayer().getLocation().getWorld());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent eve) {
        worldUploader.backupWorldLater(eve.getBlock().getLocation().getWorld());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent eve) {
        worldUploader.backupWorldLater(eve.getBlock().getLocation().getWorld());
    }

    public void onDisable() {
        if(worldUploader != null)
            worldUploader.finishAllBackups();
        saveConfig();
    }
}
