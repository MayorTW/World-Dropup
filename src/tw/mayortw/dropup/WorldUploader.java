package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

import tw.mayortw.dropup.util.ReflectionUtils.PackageType;
import tw.mayortw.dropup.util.*;

public class WorldUploader implements Runnable {

    private static final String DATE_FORMAT = "yyyy-MM-dd-HH-mm-ss";

    private Plugin plugin;
    private GoogleDriveUtil drive;
    private Callback cb;

    private ConcurrentHashMap<World, Integer> scheduledBackups = new ConcurrentHashMap<>();
    private LinkedBlockingQueue<World> awaiting = new LinkedBlockingQueue<>();
    private UploadInfo uploading = null;
    private int uploadSpeed;

    private Thread workThread;

    public WorldUploader(Plugin plugin, GoogleDriveUtil drive, Callback cb) {
        this.plugin = plugin;
        this.drive = drive;
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

        // Save and backup scheduled backups
        for(World world : scheduledBackups.keySet()) {
            flushSave(world);
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
     * Return true if the world is awaiting or being uploaded
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

    public void deleteBackup(World world, String backupFile, boolean silent) {
        String path = String.format("%s/%s/%s",
                plugin.getConfig().getString("drive_path"),
                world.getUID().toString(), backupFile);

        try {
            drive.deleteFile(path);
            if(!silent)
                Bukkit.broadcastMessage(String.format("[§e%s] §f已刪除 §a%s", plugin.getName(), backupFile));
        } catch(GoogleDriveUtil.GoogleDriveException e) {
            if(!silent)
                Bukkit.broadcastMessage(String.format("[§e%s] §f無法刪除 §a%s §c%s", plugin.getName(), backupFile, e.getMessage()));
        }
    }

    private void deleteOldBackups(World world) {
        String path = plugin.getConfig().getString("drive_path") + "/" + world.getUID().toString();

        try {
            drive.listFileNames(path).stream()
                .sorted((a, b) -> {
                    String aDate = a.substring(0, a.lastIndexOf('.'));
                    String bDate = b.substring(0, b.lastIndexOf('.'));
                    try {
                        return LocalDateTime.parse(bDate, DateTimeFormatter.ofPattern(DATE_FORMAT))
                            .compareTo(LocalDateTime.parse(aDate, DateTimeFormatter.ofPattern(DATE_FORMAT)));
                    } catch(java.time.format.DateTimeParseException e) {
                        return 0;
                    }
                })
                .skip(plugin.getConfig().getInt("max_saves"))
                .forEach(file -> {
                    deleteBackup(world, file, true);
                });

        } catch(GoogleDriveUtil.GoogleDriveException e) {
            plugin.getLogger().warning("Can't get folder content for " + path + ": " + e.getMessage());
        }
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
                // When disabling, rely on finishAllBackups to flushSave
                try {
                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        cb.preWorldBackup(world);
                        flushSave(world);
                        return null;
                    }).get();
                } catch (InterruptedException | ExecutionException e) {
                    break;
                }
            }

            // Backup
            Bukkit.broadcastMessage(String.format("[§e%s§r] §f正在備份 §a%s", plugin.getName(), world.getName()));

            Path tempPath = null;
            try {
                // Copy the world directory to a temp folder
                tempPath = Files.createTempDirectory("dropup-" + world.getUID());
                File worldFolder = world.getWorldFolder();
                File zipFile = tempPath.resolve("backup.zip").toFile();

                // Zip file
                FileUtil.zipFiles(zipFile, worldFolder);

                try(LimitedInputStream stream = new LimitedInputStream(new FileInputStream(zipFile), uploadSpeed)) {
                    // Save the stream so it can be sped up later
                    uploading.stream = stream;

                    // Upload
                    String uploadPath = String.format("%s/%s", plugin.getConfig().get("drive_path"), world.getUID().toString());
                    String uploadName = String.format("%s.zip", LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT)));
                    drive.upload(uploadPath, uploadName, stream);

                    // Finish backup
                    deleteOldBackups(world);
                    Bukkit.broadcastMessage(String.format("[§e%s§r] §a%s §f已備份到 §a%s", plugin.getName(), world.getName(), String.format("%s/%s", uploadPath, uploadName)));
                }

            } catch(GoogleDriveUtil.GoogleDriveException | IOException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§r] §f備份錯誤： §c%s", plugin.getName(), e.getMessage()));
                e.printStackTrace();
            } finally {
                // Delete temp dir
                try {
                    if(tempPath != null)
                        FileUtil.deleteDirectory(tempPath);
                } catch(IOException e) {
                    plugin.getLogger().warning("Cannot delete temporary folder: " + e.getMessage());
                }
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
        world.save();

        try {
            Method getHandle = ReflectionUtils.getMethod("CraftWorld", PackageType.CRAFTBUKKIT, "getHandle");
            if(VersionUtil.atLeast("1.14")) {
                Method save = ReflectionUtils.getMethod("WorldServer", PackageType.MINECRAFT_SERVER, "save", PackageType.MINECRAFT_SERVER.getClass("IProgressUpdate"), boolean.class, boolean.class);
                save.invoke(getHandle.invoke(world), null, true, false);
            } else {
                Method flushSave = ReflectionUtils.getMethod("WorldServer", PackageType.MINECRAFT_SERVER, "flushSave");
                flushSave.invoke(getHandle.invoke(world));
            }
        } catch(Exception e) { // ClassNotFoundException, NoSuchMethodException and ExceptionWorldConflict
            plugin.getLogger().warning("Couldn't flush save: " + e.getMessage());
        }
    }

    public static interface Callback {
        public void preWorldBackup(World world);
    }

    // POD to store world and its uploading stream
    private static class UploadInfo {
        World world;
        LimitedInputStream stream;
        UploadInfo(World world) {
            this.world = world;
        }
    }
}
