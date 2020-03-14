package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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

    public void deleteBackup(World world, String backupFile, boolean silent) {/*
        String dbxPath = String.format("%s/%s/%s",
                plugin.getConfig().getString("dropbox_path"),
                world.getUID().toString(), backupFile);

        try {
            dbxClient.files().deleteV2(dbxPath);
            if(!silent)
                Bukkit.broadcastMessage(String.format("[§e%s] §f已刪除 §a%s", plugin.getName(), backupFile));
        } catch(DbxException e) {
            if(!silent)
                Bukkit.broadcastMessage(String.format("[§e%s] §f無法刪除 §a%s §c%s", plugin.getName(), backupFile, e.getMessage()));
        }
*/    }

    private void deleteOldBackups(World world) {/*
        String dbxPath = plugin.getConfig().getString("dropbox_path") + "/" + world.getUID().toString();
        String cursor = null;
        ArrayList<String> files = new ArrayList<>();

        try {
            while(true) {
                ListFolderResult listResult;
                if(cursor == null) {
                    listResult = dbxClient.files().listFolder(dbxPath);
                    cursor = listResult.getCursor();
                } else {
                    listResult = dbxClient.files().listFolderContinue(cursor);
                }

                List<Metadata> entries = listResult.getEntries();
                entries.forEach(meta -> {
                    // Only need files
                    if(meta instanceof FileMetadata) {
                        String path = meta.getPathLower();
                        files.add(path.substring(path.lastIndexOf('/')+1));
                    }
                });

                if(!listResult.getHasMore()) break;
            }
        } catch(IllegalArgumentException | DbxException e) {
            plugin.getLogger().warning("Can't get folder content for " + dbxPath + ": " + e.getMessage());
        }

        files.stream()
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
*/    }

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

            /*
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

            Path tempPath = null;
            try(LimitedOutputStream limitedOut = new LimitedOutputStream(splitOut, uploadSpeed)) {

                // Save the stream so it can be sped up later
                uploading.stream = limitedOut;

                // Copy the world directory to a temp folder
                tempPath = Files.createTempDirectory("dropup-" + world.getUID());
                File worldFolder = world.getWorldFolder();
                File tempDir = tempPath.toFile();
                FileUtil.copyDirectory(worldFolder, tempDir);

                // Zip and upload
                FileUtil.zipFiles(limitedOut, tempDir);

                // Finish Dropbox session
                String uploadPath = String.format("%s/%s/%s.zip", plugin.getConfig().get("dropbox_path"),
                        world.getUID().toString(), LocalDateTime.now().format(DateTimeFormatter.ofPattern(DATE_FORMAT)));
                String uploaded = session.finishSession(uploadPath, splitOut.getWrittenBytes()).getPathDisplay();

                // Clean old saves
                deleteOldBackups(world);

                Bukkit.broadcastMessage(String.format("[§e%s§r] §a%s §f已備份到 §a%s", plugin.getName(), world.getName(), uploaded));

            } catch(IOException | NullPointerException | IllegalArgumentException | DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§r] §f備份錯誤： §c%s", plugin.getName(), e.getMessage()));
            } finally {
                // Delete temp dir
                try {
                    if(tempPath != null)
                        FileUtil.deleteDirectory(tempPath);
                } catch(IOException e) {
                    plugin.getLogger().warning("Cannot delete temporary folder: " + e.getMessage());
                }
            }
            */

            drive.upload("/", "test.txt", new java.io.ByteArrayInputStream("hello".getBytes()));

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
        LimitedOutputStream stream;
        UploadInfo(World world) {
            this.world = world;
        }
    }
}
