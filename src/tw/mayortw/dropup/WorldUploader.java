package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
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
import com.dropbox.core.DbxRequestConfig;

public class WorldUploader {

    private DbxClientV2 dbxClient;
    private Plugin plugin;

    private HashMap<UUID, Integer> awaitBackups = new HashMap<>();
    private CopyOnWriteArrayList<LimitedOutputStream> uploadingStreams = new CopyOnWriteArrayList<>();
    private Object lock = new Object();

    public WorldUploader(Plugin plugin) throws DbxException {
        this.plugin = plugin;

        dbxClient = new DbxClientV2(
                DbxRequestConfig.newBuilder("dropup/1.0")
                .withAutoRetryEnabled()
                .build(),
                plugin.getConfig().getString("dropbox_token"));
        Bukkit.getLogger().info("Logged in to Dropbox as " + dbxClient.users().getCurrentAccount().getName().getDisplayName());
    }

    public void finishAllBackups() {
        for(UUID id : awaitBackups.keySet()) {
            backupWorld(Bukkit.getWorld(id), false);
        }

        // Speed up and wait for world that are still uploading in background
        setUploadSpeed(-1);
        Bukkit.getLogger().info("Waiting for all uploads to finish");
        while(uploadingStreams.size() > 0) {
            try {
                synchronized(lock) {
                    lock.wait();
                }
            } catch(InterruptedException e) {}
        }
        Bukkit.getLogger().info("All uploads are finished");
    }

    public void setUploadSpeed(int speed) {
        for(LimitedOutputStream uploading : uploadingStreams) {
            uploading.setRate(speed);
        }
    }

    public void backupWorldLater(World world) {
        UUID id = world.getUID();
        if(awaitBackups.containsKey(id)) return;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            backupWorld(world, true);
        }, plugin.getConfig().getInt("min_interval", 1800) * 20);

        awaitBackups.put(id, task.getTaskId());
    }

    public void backupWorld(World world, boolean async) {
        Bukkit.broadcastMessage(String.format("[§e%s§f] 正在備份 §a%s", plugin.getName(), world.getName()));

        // Cancel awaiting backup task for this world if there's any
        BukkitScheduler scheduler = Bukkit.getScheduler();
        Integer backupTaskId = awaitBackups.remove(world.getUID());
        if(backupTaskId != null && scheduler.isQueued(backupTaskId))
            scheduler.cancelTask(backupTaskId);

        // Save the world
        world.save();

        Consumer<BukkitTask> task = worker -> {
            DropboxSession session;
            try {
                session = new DropboxSession(dbxClient);
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
            LimitedOutputStream limitedOut = new LimitedOutputStream(splitOut, plugin.getConfig().getInt("upload_speed", 1024));
            uploadingStreams.add(limitedOut);

            // Zip and upload
            try {
                File worldFolder = world.getWorldFolder();
                FileUtil.zipFiles(limitedOut, worldFolder); // TODO Copy folder to temp location if zip directly doesn't work

                // Finish Dropbox session
                String uploadPath = String.format("%s/%s/%s.zip", plugin.getConfig().get("dropbox_path", "/backup"),
                        world.getName(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")));
                String uploaded = session.finishSession(uploadPath, splitOut.getWrittenBytes()).getPathDisplay();

                Bukkit.broadcastMessage(String.format("[§e%s§f] §a%s§r 已備份到 %s", plugin.getName(), world.getName(), uploaded));
            } catch(IOException | NullPointerException | DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§f] 備份錯誤： §c%s", plugin.getName(), e.getMessage()));
                e.printStackTrace();
            }

            uploadingStreams.remove(limitedOut);
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
