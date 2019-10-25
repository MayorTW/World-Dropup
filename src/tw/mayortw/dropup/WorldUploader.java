package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;
import org.bukkit.World;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;

import tw.mayortw.dropup.util.*;

public class WorldUploader implements Runnable {

    private DbxClientV2 dbxClient;
    private Plugin plugin;
    private Callback cb;

    private ConcurrentHashMap<World, Integer> scheduledBackups = new ConcurrentHashMap<>();
    private LinkedBlockingQueue<World> awaiting = new LinkedBlockingQueue<>();
    private UploadInfo uploading = null;
    private int uploadSpeed;

    private Thread workThread;

    public WorldUploader(Plugin plugin, DbxClientV2 dbxClient, Callback cb) {
        this.plugin = plugin;
        this.dbxClient = dbxClient;
        this.cb = cb;
        this.uploadSpeed = plugin.getConfig().getInt("upload_speed") * 1024; // kb to byte

        Bukkit.getScheduler().runTaskAsynchronously(plugin, this);
    }

    public void stopWorker() {
        workThread.interrupt();
    }

    /*
     * Start scheduled backups, speed them up, and wait
     */
    public void finishAllBackups() {
        // Speed up upload
        setUploadSpeed(-1);

        // Backup scheduled backups
        for(World world : scheduledBackups.keySet()) {
            backupWorld(world);
        }

        // Wait for uploads
        plugin.getLogger().info("Waiting for all uploads to finish");
        waitForAllBackups();
        plugin.getLogger().info("All uploads are finished");
    }

    /*
     * Wait for all backups to finish
     */
    public void waitForAllBackups() {
        while(true) {
            try {
                synchronized(this) {
                    if(awaiting.size() == 0 && uploading == null) break;
                    this.wait();
                }
            } catch(InterruptedException e) {}
        }
    }

    /*
     * Wait for a single world's backup to finish
     */
    public void waitForBackup(World world) {
        while(true) {
            try {
                synchronized(this) {
                    if(!awaiting.contains(world) && (uploading == null || !uploading.world.equals(world)))
                        break;
                    this.wait();
                }
            } catch(InterruptedException e) {}
        }
    }

    /*
     * Sets max upload speed
     * This changes the current uploading stream too
     */
    public void setUploadSpeed(int speed) {
        this.uploadSpeed = speed * 1024; // kb to byte

        if(uploading != null && uploading.stream != null)
            uploading.stream.setRate(uploadSpeed);
    }

    public World getCurrentWorld() {
        if(uploading != null)
            return uploading.world;
        return null;
    }

    /*
     * Return true if the world is awaiting or being upaloded
     */
    public boolean isAwaiting(World world) {
        return awaiting.contains(world);
    }

    public World[] getAwaitingWorlds() {
        return awaiting.toArray(new World[awaiting.size()]);
    }

    public void backupWorld(World world) {
        if(isAwaiting(world)) return;
        stopBackupWorldLater(world);
        awaiting.offer(world);
    }

    public void backupAllWorlds() {
        for(World world : Bukkit.getWorlds()) {
            backupWorld(world);
        }
    }

    public void backupWorldLater(World world) {
        if(scheduledBackups.containsKey(world)) return;

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            backupWorld(world);
        }, plugin.getConfig().getInt("min_interval") * 20);

        scheduledBackups.put(world, task.getTaskId());
    }

    public void stopBackupWorldLater(World world) {
        BukkitScheduler scheduler = Bukkit.getScheduler();
        Integer backupTaskId = scheduledBackups.remove(world);
        if(backupTaskId != null && scheduler.isQueued(backupTaskId))
            scheduler.cancelTask(backupTaskId);
    }

    // Work thread that does the uploading
    @Override
    public void run() {
        // Save the thread so it can be interrupted
        workThread = Thread.currentThread();

        while(!Thread.interrupted()) {

            // Pre backup tasks

            // Wait for a world
            World world;
            try {
                uploading = new UploadInfo(awaiting.take());
                world = uploading.world;
            } catch(InterruptedException e) {
                break;
            }

            // Do stuff that needs to be done in main thread
            if(plugin.isEnabled()) {
                try {
                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        cb.preWorldBackup(world);
                        flushSave(world);
                        return null;
                    }).get();
                } catch (InterruptedException | ExecutionException e) {
                    break;
                }
            } else {
                // Disabling, can't start new scheduler
                // So we have to save the world in this thread
                // the main thread is waiting anyways
                // And not call callback
                flushSave(world);
            }

            // Backup
            Bukkit.broadcastMessage(String.format("[§e%s§r] §f正在備份 §a%s", plugin.getName(), world.getName()));

            DropboxUploadSession session;
            try {
                session = new DropboxUploadSession(dbxClient);
            } catch(DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§r] §fDropbox錯誤： §c%s", plugin.getName(), e.getMessage()));
                return;
            }

            SplitOutputStream splitOut = new SplitOutputStream(session.out, 1024 * 10, offset -> {
                try {
                    session.nextSession(offset);
                    return session.out;
                } catch(DbxException e) {
                    Bukkit.broadcastMessage(String.format("[§e%s§r] §fDropbox錯誤： §c%s", plugin.getName(), e.getMessage()));
                    e.printStackTrace();
                }
                return null; // And it triggers NullPointerException
            });

            try(LimitedOutputStream limitedOut = new LimitedOutputStream(splitOut, uploadSpeed)) {

                // Save the stream so it can be sped up later
                uploading.stream = limitedOut;

                // Copy the world directory to a temp folder
                File worldFolder = world.getWorldFolder();
                Path tempPath = Files.createTempDirectory("dropup-" + world.getUID());
                File tempDir = tempPath.toFile();
                FileUtil.copyDirectory(worldFolder, tempDir);

                try {
                    // Zip and upload
                    FileUtil.zipFiles(limitedOut, tempDir);

                    // Finish Dropbox session
                    String uploadPath = String.format("%s/%s/%s.zip", plugin.getConfig().get("dropbox_path"),
                            world.getUID().toString(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")));
                    String uploaded = session.finishSession(uploadPath, splitOut.getWrittenBytes()).getPathDisplay();

                    Bukkit.broadcastMessage(String.format("[§e%s§r] §a%s §f已備份到 §a%s", plugin.getName(), world.getName(), uploaded));
                } finally {
                    // Delete temp dir
                    try {
                        FileUtil.deleteDirectory(tempPath);
                    } catch(IOException e) {
                        plugin.getLogger().warning("Cannot delete temporary folder: " + e.getMessage());
                    }
                }

            } catch(IOException | NullPointerException | IllegalArgumentException | DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§r] §f備份錯誤： §c%s", plugin.getName(), e.getMessage()));
            }

            // Tell whoever's waiting that it has finished
            synchronized(this) {
                uploading = null;
                this.notifyAll();
            }
        }
        plugin.getLogger().info("Backup worker thread stopped");
    }

    private void flushSave(World world) {
        if(world instanceof org.bukkit.craftbukkit.v1_14_R1.CraftWorld) {
            try {
                ((org.bukkit.craftbukkit.v1_14_R1.CraftWorld) world).getHandle().save(null, true, false);
            } catch(net.minecraft.server.v1_14_R1.ExceptionWorldConflict e) {}
        } else {
            world.save();
            if(world instanceof org.bukkit.craftbukkit.v1_11_R1.CraftWorld)
                ((org.bukkit.craftbukkit.v1_11_R1.CraftWorld) world).getHandle().flushSave();
            else if(world instanceof org.bukkit.craftbukkit.v1_12_R1.CraftWorld)
                ((org.bukkit.craftbukkit.v1_12_R1.CraftWorld) world).getHandle().flushSave();
            else if(world instanceof org.bukkit.craftbukkit.v1_13_R1.CraftWorld)
                ((org.bukkit.craftbukkit.v1_13_R1.CraftWorld) world).getHandle().flushSave();
            else if(world instanceof org.bukkit.craftbukkit.v1_13_R2.CraftWorld)
                ((org.bukkit.craftbukkit.v1_13_R2.CraftWorld) world).getHandle().flushSave();
        }
    }

    public static interface Callback {
        public void preWorldBackup(World world);
    }

    // POD to store world and its uploading stream
    private static class UploadInfo {
        World world;
        LimitedOutputStream stream;
        UploadInfo(World world) {
            this.world = world;
        }
    }
}
