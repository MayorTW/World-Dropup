package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
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
        String drivePath = plugin.getConfig().getString("dropbox_path") + "/" + world.getUID().toString();
        try {
            return drive.listFileNames(drivePath);
        } catch(GoogleDriveUtil.GoogleDriveException e) {
            return null;
        }
    }

    // backupFile is the zip file name in Dropbox's world name folder
    public void restoreWorld(World world, String backupFile) {
        /*
        if(mvWorldManager == null) {
            plugin.getLogger().warning("Can't restore world without MultiVerse");
            return;
        }

        if(downloading != null) return;
        downloading = new DownloadInfo(world);

        // Unload the world first
        if(mvWorldManager.unloadWorld(world.getName(), true)) {
            Bukkit.broadcastMessage(String.format("[§e%s] §f已卸載世界 §a%s§f，回復完成前請不要加載", plugin.getName(), world.getName()));
        } else {
            Bukkit.broadcastMessage(String.format("[§e%s] §f無法卸載世界 §a%s", plugin.getName(), world.getName()));
            return;
        }

        String drivePath = String.format("%s/%s/%s",
                plugin.getConfig().getString("dropbox_path"),
                world.getUID().toString(), backupFile);

        // Run in async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Bukkit.broadcastMessage(String.format("[§e%s] §f正在回復 §a%s", plugin.getName(), world.getName()));

            try(DbxDownloader<FileMetadata> downloader = dbxClient.files().download(dbxPath);
                    LimitedInputStream in = new LimitedInputStream(downloader.getInputStream(), downloadSpeed)) {

                // Save the stream so the speed can be changed later
                downloading.stream = in;

                // Get the destinations
                File worldDir = world.getWorldFolder();
                Path worldPath = worldDir.toPath();
                Path dloadPath = worldPath
                    .resolveSibling(plugin.getConfig().getString("download_path"))
                    .resolve(world.getName());
                File dloadDir = dloadPath.toFile();

                // Remove download directory if there's one
                if(dloadDir.isDirectory())
                    FileUtil.deleteDirectory(dloadPath);
                else
                    dloadDir.delete();

                dloadDir.mkdirs();

                // download and unzip
                FileUtil.unzipFiles(in, dloadPath);

                // When success, delete old world folder and rename new one to old
                FileUtil.deleteDirectory(worldPath);
                if(!dloadDir.renameTo(worldDir)) {
                    // Cannot rename, have to copy it then delete the source
                    FileUtil.copyDirectory(dloadDir, worldDir);
                    FileUtil.deleteDirectory(dloadPath);
                }

                Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    if(mvWorldManager.loadWorld(world.getName()))
                        Bukkit.broadcastMessage(String.format("[§e%s] §f已從 §a%s §f回復 §a%s", plugin.getName(), dbxPath, world.getName()));
                    else
                        Bukkit.broadcastMessage(String.format("[§e%s] §f世界 §a%s §f已下載，但無法載入", plugin.getName(), world.getName()));
                    return null;
                }).get();

            } catch(IOException | DbxException | ExecutionException | InterruptedException | IllegalArgumentException e) {
                Bukkit.broadcastMessage(String.format("[§e%s] §f回復錯誤： §c%s", plugin.getName(), e.getMessage()));
                // Retries is handled already for download so don't need to do it

                if(!(e instanceof DownloadErrorException)) {
                    plugin.getLogger().warning("Dropbox error when downloading " + dbxPath + ": " + e.getMessage());
                }
            }

            downloading = null;
            synchronized(lock) {
                lock.notify();
            }
        });
    */
    }

    private static class DownloadInfo {
        World world;
        LimitedInputStream stream;
        DownloadInfo(World world) {
            this.world = world;
        }
    }
}
