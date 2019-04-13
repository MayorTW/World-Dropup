package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.DbxClientV2;

public class WorldDownloader {

    private Plugin plugin;
    private DbxClientV2 dbxClient;

    public WorldDownloader(Plugin plugin, DbxClientV2 dbxClient) {
        this.plugin = plugin;
        this.dbxClient = dbxClient;
    }

    public List<FileMetadata> listBackups(World world) {
        List<FileMetadata> rst = new ArrayList<>();
        String dbxPath = plugin.getConfig().get("dropbox_path", "/backup") + "/" + world.getName();
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
        } catch(DbxException e) {
            //Bukkit.getLogger().warning("Can't get folder content for " + dbxPath);
        }

        return rst;
    }
}
