package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

import com.onarandombox.MultiverseCore.api.MVWorldManager;

import tw.mayortw.dropup.util.*;

public class WorldDownloader {

    private Plugin plugin;
    private GoogleDriveUtil drive;
    private MVWorldManager mvWorldManager;
    private Object lock = new Object();

    private DownloadInfo downloading = null;
    private int downloadSpeed;

    public WorldDownloader(Plugin plugin, GoogleDriveUtil drive, MVWorldManager mvWorldManager) {
        this.plugin = plugin;
        this.drive = drive;
        this.mvWorldManager = mvWorldManager;
        this.downloadSpeed = plugin.getConfig().getInt("download_speed") * 1024; // kb to byte
    }

    public void setDownloadSpeed(int speed) {
        this.downloadSpeed = speed * 1024; // kb to byte
        if(downloading != null && downloading.stream != null)
            downloading.stream.setRate(downloadSpeed);
    }

    public void removeDownloadDir() {
        Path downloadDir = Bukkit.getWorldContainer().toPath()
            .resolve(plugin.getConfig().getString("download_path"));
        try {
            if(Files.isDirectory(downloadDir)) {
                FileUtil.deleteDirectory(downloadDir);
            } else {
                Files.deleteIfExists(downloadDir);
            }
        } catch(IOException e) {
            plugin.getLogger().warning("Cannot delete world download folder: " + e);
        }
    }

    public void stopAllDownloads() {
        plugin.getLogger().info("Stopping download");

        // Close the stream and wait
        if(downloading != null && downloading.stream != null) {
            try {
                downloading.stream.close();
            } catch(IOException e) {
                plugin.getLogger().warning("Cannot close input stream");
                e.printStackTrace();
            }
        }

        synchronized(lock) {
            while(downloading != null) {
                try {
                    lock.wait();
                } catch(InterruptedException e) {}
            }
        }
    }

    public World getCurrentWorld() {
        if(downloading != null)
            return downloading.world;
        return null;
    }

    public List<String> listBackups(World world) {
        String drivePath = plugin.getConfig().getString("drive_path") + "/" + world.getUID().toString();
        try {
            return drive.listFileNames(drivePath);
        } catch(GoogleDriveUtil.GoogleDriveException e) {
            plugin.getLogger().warning("Cannot get backup list: " + e.getMessage());
            return null;
        }
    }

    // backupFile is the zip file name on the drive
    public void restoreWorld(World world, String backupFile) {
        if(mvWorldManager == null) {
            plugin.getLogger().warning("Can't restore world without MultiVerse");
            return;
        }

        if(downloading != null) return;

        // Unload the world first
        if(mvWorldManager.unloadWorld(world.getName(), true)) {
            Bukkit.broadcastMessage(String.format("[§e%s] §f已卸載世界 §a%s§f，回復完成前請不要加載", plugin.getName(), world.getName()));
        } else {
            Bukkit.broadcastMessage(String.format("[§e%s] §f無法卸載世界 §a%s", plugin.getName(), world.getName()));
            return;
        }

        String path = String.format("%s/%s/%s",
                plugin.getConfig().getString("drive_path"),
                world.getUID().toString(), backupFile);

        downloading = new DownloadInfo(world);

        // Run in async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Bukkit.broadcastMessage(String.format("[§e%s] §f正在回復 §a%s", plugin.getName(), world.getName()));

            // Get the destinations
            File worldDir = world.getWorldFolder();
            File dloadDir = worldDir.toPath()
                .resolveSibling(plugin.getConfig().getString("download_path"))
                .resolve(world.getUID().toString())
                .toFile();

            try {
                // Prepare download destinations
                if(dloadDir.exists()) {
                    if(dloadDir.isDirectory())
                        FileUtil.deleteDirectory(dloadDir.toPath());
                    else
                        dloadDir.delete();
                }
                dloadDir.mkdirs();

                // Download and unzip
                try(InputStream httpStream = drive.download(path)) {
                    downloading.stream = new LimitedInputStream(httpStream, downloadSpeed); // Save the stream so the speed can be changed later
                    FileUtil.unzipFiles(downloading.stream, dloadDir.toPath());
                }

                // When success, delete old world folder and rename new one to old
                FileUtil.deleteDirectory(worldDir);
                if(!dloadDir.renameTo(worldDir)) {
                    // Cannot rename, have to copy it
                    FileUtil.copyDirectory(dloadDir, worldDir);
                }

                // Load the world
                try {
                    Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        if(mvWorldManager.loadWorld(world.getName()))
                            Bukkit.broadcastMessage(String.format("[§e%s] §f已從 §a%s §f回復 §a%s", plugin.getName(), path, world.getName()));
                        else
                            Bukkit.broadcastMessage(String.format("[§e%s] §f世界 §a%s §f已下載，但無法載入", plugin.getName(), world.getName()));
                        return null;
                    }).get();
                } catch(InterruptedException | ExecutionException e) {}

            } catch(IOException | GoogleDriveUtil.GoogleDriveException e) {
                Bukkit.broadcastMessage(String.format("[§e%s] §f回復錯誤： §c%s", plugin.getName(), e.getMessage()));
            } finally {
                try {
                    FileUtil.deleteDirectory(dloadDir.toPath());
                } catch(IOException e) {}
            }

            downloading = null;
            synchronized(lock) {
                lock.notify();
            }
        });
    }

    private static class DownloadInfo {
        World world;
        LimitedInputStream stream;
        DownloadInfo(World world) {
            this.world = world;
        }
    }
}
