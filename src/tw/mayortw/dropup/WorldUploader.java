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
        this.uploadSpeed = plugin.getConfig().getInt("upload_speed");

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
        while(awaiting.size() > 0 || uploading != null) {
            try {
                synchronized(this) {
                    this.wait();
                }
            } catch(InterruptedException e) {}
        }
    }

    /*
     * Wait for a single world's backup to finish
     */
    public void waitForBackup(World world) {
        while(awaiting.contains(world) || uploading != null && uploading.world.equals(world)) {
            plugin.getLogger().info("" + awaiting.contains(world) + " " + (uploading != null ? uploading.world.getName() : "null"));
            try {
                synchronized(this) {
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
        this.uploadSpeed = speed;

        if(uploading != null && uploading.stream != null)
            uploading.stream.setRate(speed);
    }

    public void backupWorld(World world) {
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
                uploading = new UploadInfo(awaiting.take(), null);
                world = uploading.world;
            } catch(InterruptedException e) {
                continue;
            }

            // Do stuff that needs to be done in main thread
            if(plugin.isEnabled()) {
                new MainThreadExec(() -> {
                    cb.preWorldBackup(world);
                    world.save();
                }).exec();
            } else {
                // Disabling, can't start new scheduler
                // So we have to save the world in this thread
                // the main thread is waiting anyways
                // And not call callback
                world.save();
            }

            // Backup
            Bukkit.broadcastMessage(String.format("[§e%s§r] §f正在備份 §a%s", plugin.getName(), world.getName()));

            DropboxUploadSession session;
            try {
                session = new DropboxUploadSession(dbxClient);
            } catch(DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§r] §fDropbox錯誤： §c%s", plugin.getName(), e.getMessage()));
                e.printStackTrace();
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

            LimitedOutputStream limitedOut = new LimitedOutputStream(splitOut, uploadSpeed);

            // Save the stream so it can be sped up later
            uploading.stream = limitedOut;

            // Zip and upload
            try {
                File worldFolder = world.getWorldFolder();
                FileUtil.zipFiles(limitedOut, worldFolder); // TODO Copy folder to temp location if zip directly doesn't work

                // Finish Dropbox session
                String uploadPath = String.format("%s/%s/%s.zip", plugin.getConfig().get("dropbox_path"),
                        world.getName(), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")));
                String uploaded = session.finishSession(uploadPath, splitOut.getWrittenBytes()).getPathDisplay();

                Bukkit.broadcastMessage(String.format("[§e%s§r] §a%s §f已備份到 §a%s", plugin.getName(), world.getName(), uploaded));
            } catch(IOException | NullPointerException | DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§r] §f備份錯誤： §c%s", plugin.getName(), e.getMessage()));
                e.printStackTrace();
            }

            // Tell whoever's waiting that it has finished
            synchronized(this) {
                uploading = null;
                this.notifyAll();
            }
        }
        plugin.getLogger().info("Worker thread stopped");
    }

    public static interface Callback {
        public void preWorldBackup(World world);
    }

    // Run task in main thread and wait for it
    private class MainThreadExec implements Runnable {

        private Runnable run;
        private boolean runned;

        MainThreadExec(Runnable run) {
            this.run = run;
        }

        synchronized void exec() {
            runned = false;
            Bukkit.getScheduler().runTask(WorldUploader.this.plugin, this);
            while(!runned) {
                try {
                    this.wait();
                } catch(InterruptedException e) {}
            }
        }

        @Override
        public void run() {
            synchronized(this) {
                run.run();
                runned = true;
                this.notify();
            }
        }
    }

    // POD to store world and its uploading stream
    private static class UploadInfo {
        World world;
        LimitedOutputStream stream;
        UploadInfo(World world, LimitedOutputStream stream) {
            this.world = world;
            this.stream = stream;
        }
    }
}
