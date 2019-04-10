package tw.mayortw.dropup;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;

public class DropupPlugin extends JavaPlugin implements Listener {

    private boolean hasMultiverse = true;

    DbxClientV2 dbxClient;

    private HashMap<UUID, Integer> backupTimers = new HashMap<>();

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

        try {
            dbxClient = new DbxClientV2(DbxRequestConfig.newBuilder("dropup/1.0").build(), getConfig().getString("dropbox_token"));
            getLogger().info("Logged into " + dbxClient.users().getCurrentAccount().getName().getDisplayName());
        } catch (DbxException e) {
            getLogger().severe("Dropbox login error: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
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
        getServer().broadcastMessage(String.format("[§e%s§f] 正在備份 §a%s", getName(), world.getName()));
        // TODO backup async

        world.save();
        File worldFolder = world.getWorldFolder();

        try {
            // Dropbox says to split the session with >150Mb files
            // but they didn't me a good api for that
            // so I'm not doing it
            String uploadPath = String.format("%s/%s/%s.zip", getConfig().get("dropbox_path", "/WorldBackup"),
                    world.getName(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")));
            OutputStream dbxOut = dbxClient.files().upload(uploadPath).getOutputStream(); // TODO limit the speed

            // Zip and upload
            FileUtil.zipFiles(dbxOut, worldFolder); // TODO Copy folder to temp location if zip directly doesn't work

            getServer().broadcastMessage(String.format("[§e%s§f] §a%s§r 已備份到 %s", getName(), world.getName(), uploadPath));
        } catch(IOException | DbxException e) {
            getServer().broadcastMessage(String.format("[§e%s§f] 備份錯誤： §c%s", getName(), e.getMessage()));
            e.printStackTrace();
        }
    }
}
