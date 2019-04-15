package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.bukkit.World;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;

public class WorldUploader {

    private DbxClientV2 dbxClient;
    private Plugin plugin;

    private HashMap<UUID, Integer> awaitBackups = new HashMap<>();
    private ConcurrentHashMap<UUID, LimitedOutputStream> uploadings = new ConcurrentHashMap<>();
    private Object lock = new Object();

    public WorldUploader(Plugin plugin, DbxClientV2 dbxClient) {
        this.plugin = plugin;
        this.dbxClient = dbxClient;
    }

    // Speed up all auploads and wait
    public void finishAllBackups() {
        for(UUID id : awaitBackups.keySet()) {
            backupWorld(Bukkit.getWorld(id), false);
        }

        // Speed up and wait for world that are still uploading in background
        Bukkit.getLogger().info("Waiting for all uploads to finish");
        while(uploadings.size() > 0) {
            setUploadSpeed(-1);
            try {
                synchronized(lock) {
                    lock.wait();
                }
            } catch(InterruptedException e) {}
        }
        Bukkit.getLogger().info("All uploads are finished");
    }

    // Wait for a single world's backup to finish if any
    // This doesn't speed up the upload speed
    public void waitForBackup(World world) {
        while(uploadings.containsKey(world.getUID())) {
            try {
                synchronized(lock) {
                    lock.wait();
                }
            } catch(InterruptedException e) {}
        }
    }

    public void setUploadSpeed(int speed) {
        for(LimitedOutputStream uploading : uploadings.values()) {
            uploading.setRate(speed);
        }
    }

    public void backupWorldLater(World world) {
        UUID id = world.getUID();
        if(awaitBackups.containsKey(id)) return;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            backupWorld(world, true);
        }, plugin.getConfig().getInt("min_interval") * 20);

        awaitBackups.put(id, task.getTaskId());
    }

    public void stopBackupWorldLater(World world) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        Integer backupTaskId = awaitBackups.remove(world.getUID());
        if(backupTaskId != null && scheduler.isQueued(backupTaskId))
            scheduler.cancelTask(backupTaskId);
    }

    public void backupWorld(World world, boolean async) {
        Bukkit.broadcastMessage(String.format("[§e%s§f] 正在備份 §a%s", plugin.getName(), world.getName()));

        // Cancel scheduled backup for this world if there's any
        stopBackupWorldLater(world);

        // Save the world
        world.save();

        Consumer<BukkitTask> task = worker -> {
            DropboxUploadSession session;
            try {
                session = new DropboxUploadSession(dbxClient);
            } catch(DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§f] Dropbox錯誤： §c%s", plugin.getName(), e.getMessage()));
                e.printStackTrace();
                return;
            }

            SplitOutputStream splitOut = new SplitOutputStream(session.out, 1024 * 10, offset -> {
                try {
                    session.nextSession(offset);
                    return session.out;
                } catch(DbxException e) {
                    Bukkit.broadcastMessage(String.format("[§e%s§f] Dropbox錯誤： §c%s", plugin.getName(), e.getMessage()));
                    e.printStackTrace();
                }
                return null; // And it triggers NullPointerException
            });

            // Put the stream object in map so it can be sped up later when needed
            LimitedOutputStream limitedOut = new LimitedOutputStream(splitOut, plugin.getConfig().getInt("upload_speed"));
            uploadings.put(world.getUID(), limitedOut);

            // Zip and upload
            try {
                File worldFolder = world.getWorldFolder();
                FileUtil.zipFiles(limitedOut, worldFolder); // TODO Copy folder to temp location if zip directly doesn't work

                // Finish Dropbox session
                String uploadPath = String.format("%s/%s/%s.zip", plugin.getConfig().get("dropbox_path"),
                        world.getName(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")));
                String uploaded = session.finishSession(uploadPath, splitOut.getWrittenBytes()).getPathDisplay();

                Bukkit.broadcastMessage(String.format("[§e%s§f] §a%s§r 已備份到 %s", plugin.getName(), world.getName(), uploaded));
            } catch(IOException | NullPointerException | DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§f] 備份錯誤： §c%s", plugin.getName(), e.getMessage()));
                e.printStackTrace();
            }

            uploadings.remove(world.getUID());
            synchronized(lock) {
                lock.notify();
            }
        };

        if(async)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        else
            task.accept(null);
    }
}
