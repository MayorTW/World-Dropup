package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

import com.onarandombox.MultiverseCore.api.MVWorldManager;

import com.dropbox.core.DbxDownloader;
import com.dropbox.core.DbxException;
import com.dropbox.core.v2.files.DownloadErrorException;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.ListFolderErrorException;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.DbxClientV2;

public class WorldDownloader {

    private Plugin plugin;
    private DbxClientV2 dbxClient;
    private MVWorldManager mvWorldManager;
    private Object lock = new Object();

    private ConcurrentHashMap<UUID, LimitedInputStream> downloadings = new ConcurrentHashMap<>();

    public WorldDownloader(Plugin plugin, DbxClientV2 dbxClient, MVWorldManager mvWorldManager) {
        this.plugin = plugin;
        this.dbxClient = dbxClient;
        this.mvWorldManager = mvWorldManager;
    }

    public void setDownloadSpeed(int speed) {
        for(LimitedInputStream downloading : downloadings.values()) {
            downloading.setRate(speed);
        }
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
            Bukkit.getLogger().warning("Cannot delete world download folder: " + e);
        }
    }

    public void stopAllDownloads() {
        Bukkit.getLogger().info("Stopping all downloads");

        // Close the stream and wait
        while(downloadings.size() > 0) {
            for(LimitedInputStream downloading : downloadings.values()) {
                try {
                    downloading.close();
                } catch(IOException e) {
                    Bukkit.getLogger().warning("Cannot close input stream");
                    e.printStackTrace();
                }
            }

            try {
                synchronized(lock) {
                    lock.wait();
                }
            } catch(InterruptedException e) {}
        }
    }

    public List<FileMetadata> listBackups(World world) {
        List<FileMetadata> rst = new ArrayList<>();
        String dbxPath = plugin.getConfig().getString("dropbox_path") + "/" + world.getName();
        String cursor = null;

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
                    if(meta instanceof FileMetadata)
                        rst.add((FileMetadata) meta);
                });

                if(!listResult.getHasMore()) break;
            }
        } catch(ListFolderErrorException e) {
        } catch(DbxException e) {
            Bukkit.getLogger().warning("Can't get folder content for " + dbxPath + ": " + e.getMessage());
        }

        return rst;
    }

    // backupFile is the zip file name in Dropbox's world name folder
    public void restoreWorld(World world, String backupFile) {
        // Unload the world first
        if(mvWorldManager.unloadWorld(world.getName(), true)) {
            Bukkit.broadcastMessage(String.format("[§e%s§f] 已卸載世界 §a%s§r，回復完成前請不要加載", plugin.getName(), world.getName()));
        } else {
            Bukkit.broadcastMessage(String.format("[§e%s§f] 無法卸載世界 §a%s", plugin.getName(), world.getName()));
            return;
        }

        // Run in async
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Bukkit.broadcastMessage(String.format("[§e%s§f] 正在回復 §a%s", plugin.getName(), world.getName()));

            String dbxPath = String.format("%s/%s/%s",
                    plugin.getConfig().getString("dropbox_path"),
                    world.getName(), backupFile);

            try(DbxDownloader<FileMetadata> downloader = dbxClient.files().download(dbxPath)) {
                LimitedInputStream in = new LimitedInputStream(downloader.getInputStream(),
                        plugin.getConfig().getInt("download_speed"));

                // Save the stream so the speed can be changed later
                downloadings.put(world.getUID(), in);

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
                        Bukkit.broadcastMessage(String.format("[§e%s§f] 已從 §a%s§r 回復 §a%s§r", plugin.getName(), dbxPath, world.getName()));
                    else
                        Bukkit.broadcastMessage(String.format("[§e%s§f] 世界 §a%s§r 已下載，但無法載入", plugin.getName(), world.getName()));
                    return null;
                });

            } catch(IOException | DbxException e) {
                Bukkit.broadcastMessage(String.format("[§e%s§f] 回復錯誤： §c%s", plugin.getName(), e.getMessage()));
                // Retries is handled already for download so don't need to do it

                if(!(e instanceof DownloadErrorException)) {
                    Bukkit.getLogger().warning("Dropbox error when downloading " + dbxPath + ": " + e.getMessage());
                }
            }

            downloadings.remove(world.getUID());
            synchronized(lock) {
                lock.notify();
            }
        });
    }
}
