package tw.mayortw.dropup;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
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
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.bukkit.World;

import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;

public class DropupPlugin extends JavaPlugin implements Listener {

    private boolean hasMultiverse = true;

    DbxClientV2 dbxClient;

    private HashMap<UUID, Integer> awaitBackups = new HashMap<>();
    private HashMap<Integer, LimitedOutputStream> uploadingStreams = new HashMap<>();

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
            getLogger().info("Logged in to Dropbox as " + dbxClient.users().getCurrentAccount().getName().getDisplayName());
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
                        return true;
                    }
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    backupWorld(world, true);
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
        // Execute all awaiting backups
        for(UUID id : awaitBackups.keySet()) {
            backupWorld(getServer().getWorld(id), false);
        }

        // Speed up and wait for world that are still uploading in background
        getLogger().info("Waiting for all uploads to finish");
        for(BukkitWorker worker : getServer().getScheduler().getActiveWorkers()) {
            int id = worker.getTaskId();
            LimitedOutputStream uploading = uploadingStreams.get(id);
            if(uploading != null) {
                uploading.setRate(-1);
                try {
                    while(uploadingStreams.containsKey(id)) { // just in case it did (not) finish
                        synchronized(uploading) {
                            uploading.wait();
                        }
                    }
                } catch(InterruptedException exc) {}
            }
        }
        getLogger().info("All uploads are finished");

        saveConfig();
    }

    private void backupWorldLater(World world) {
        UUID id = world.getUID();
        if(awaitBackups.containsKey(id)) return;

        BukkitTask task = getServer().getScheduler().runTaskLater(this, () -> {
            backupWorld(world, true);
        }, getConfig().getInt("min_interval", 1800) * 20);

        awaitBackups.put(id, task.getTaskId());
    }

    private void backupWorld(World world, boolean async) {
        getServer().broadcastMessage(String.format("[§e%s§f] 正在備份 §a%s", getName(), world.getName()));

        // Cancel awaiting backup task for this world if there's any
        BukkitScheduler scheduler = getServer().getScheduler();
        Integer backupTaskId = awaitBackups.remove(world.getUID());
        if(backupTaskId != null && scheduler.isQueued(backupTaskId))
            scheduler.cancelTask(backupTaskId);

        try {
            // Create a Dropbox session (and OutputStream) to upload file
            // TODO split up big files
            String uploadPath = String.format("%s/%s/%s.zip", getConfig().get("dropbox_path", "/backup"),
                    world.getName(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")));
            OutputStream dbxOut = dbxClient.files().upload(uploadPath).getOutputStream();
            LimitedOutputStream limitedStream = new LimitedOutputStream(dbxOut, getConfig().getInt("upload_rate", 1024));

            // Save the world
            world.save();
            File worldFolder = world.getWorldFolder();

            // this is to be run in background if async is true
            Consumer<BukkitTask> task = worker -> {
                // Put the stream object in map so it can be sped up later when needed
                // Can also act as a wait-notify object
                if(worker != null) uploadingStreams.put(worker.getTaskId(), limitedStream);

                // Zip and upload
                try {
                    FileUtil.zipFiles(limitedStream, worldFolder); // TODO Copy folder to temp location if zip directly doesn't work
                    getServer().broadcastMessage(String.format("[§e%s§f] §a%s§r 已備份到 %s", getName(), world.getName(), uploadPath));
                } catch(IOException e) {
                    getServer().broadcastMessage(String.format("[§e%s§f] 備份錯誤： §c%s", getName(), e.getMessage()));
                    e.printStackTrace();
                }

                if(worker != null) {
                    synchronized(limitedStream) {
                        uploadingStreams.remove(worker.getTaskId());
                        limitedStream.notifyAll();
                    }
                }
            };

            if(async)
                getServer().getScheduler().runTaskAsynchronously(this, task);
            else
                task.accept(null);

        } catch(DbxException e) {
            getServer().broadcastMessage(String.format("[§e%s§f] Dropbox 錯誤： §c%s", getName(), e.getMessage()));
            e.printStackTrace();
        }
    }
}
