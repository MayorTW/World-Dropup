package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.util.Arrays;
import java.util.List;

import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;

import com.onarandombox.MultiverseCore.api.MVPlugin;
import com.onarandombox.MultiverseCore.api.MVWorldManager;

import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.DbxClientV2;

public class DropupPlugin extends JavaPlugin implements Listener {

    private DbxClientV2 dbxClient;
    private DbxWebAuth dbxAuth;
    private WorldUploader worldUploader;
    private WorldDownloader worldDownloader;
    private MVWorldManager mvWorldManager;

    private boolean disabled = false;
    private String disabledReason;

    @Override
    public void onEnable() {
        MVPlugin mvPlugin = (MVPlugin) getServer().getPluginManager().getPlugin("Multiverse-Core");
        if(mvPlugin == null) {
            getLogger().warning("Multiverse-Core not found or not enabled, won't be able to hot-restore");
        } else {
            mvWorldManager = mvPlugin.getCore().getMVWorldManager();
        }

        saveDefaultConfig();
        dropboxSignIn();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void dropboxSignIn() {
        // The APP key is in Secret.java locally
        DbxRequestConfig reqConfig = DbxRequestConfig.newBuilder("dropup/1.0")
            .withAutoRetryEnabled() .build();
        DbxAppInfo appInfo = new DbxAppInfo(Secret.APP_KEY, Secret.APP_SECRET);
        dbxAuth = new DbxWebAuth(reqConfig, appInfo);
        String token = getConfig().getString("dropbox_token");

        if(token == null) {
            getLogger().warning("Dropbox not signed in. Go to " + getAuthURL() + " to get the authorization code and type /dropup signin <code> to sign in");
            disabled = true;
            disabledReason = "Dropbox 未登入";
        } else {
            dbxClient = new DbxClientV2(reqConfig, token);
            worldUploader = new WorldUploader(this, dbxClient);
            worldDownloader = new WorldDownloader(this, dbxClient, mvWorldManager);
            disabled = false;
            getLogger().info("Logged in to Dropbox as " + getLoginName()); // There's a login check in getLoginName() too
        }
    }

    private String getAuthURL() {
        return dbxAuth.authorize(DbxWebAuth.newRequestBuilder().withNoRedirect().build());
    }

    private String getLoginName() {
        if(dbxClient == null) return "";
        try {
            return dbxClient.users().getCurrentAccount().getName().getDisplayName();
        } catch (DbxException e) {
            getLogger().warning("Dropbox login error: " + e.getMessage());
            dbxClient = null;
            worldUploader = null;
            worldDownloader = null;
            disabled = true;
            disabledReason = "Dropbox 登入錯誤";
            return "";
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(args.length < 1) return false;
        if(dbxClient == null && !args[0].equalsIgnoreCase("signin")) {
            sender.sendMessage("Dropbox 尚未登入，請用 /dropup signin 來登入");
            return true;
        }
        switch(args[0].toLowerCase()) {
            case "backup":
            case "bk":
                {
                    if(!checkCommandPermission(sender, "dropup.backup")) return true;
                    World world = null;
                    if(args.length > 1) {
                        world = getServer().getWorld(args[1]);
                    } else if(sender instanceof Entity) {
                        world = ((Entity) sender).getLocation().getWorld();
                    } else {
                        sender.sendMessage("你沒有在一個世界，請指定一個");
                        return true;
                    }
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    worldUploader.backupWorld(world, true);
                    return true;
                }
            case "restore":
            case "re":
                {
                    if(!checkCommandPermission(sender, "dropup.restore")) return true;
                    if(mvWorldManager == null) {
                        sender.sendMessage("找不到Multiverse-Core，無法在執行中回復");
                        return true;
                    }

                    if(args.length <= 1) {
                        sender.sendMessage("請指定一個世界");
                        return true;
                    }

                    if(args.length <= 2) {
                        sender.sendMessage("請指定一個備份");
                        return true;
                    }

                    World world = getServer().getWorld(args[1]);
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    // Check if there's player in the world
                    List<Player> players = world.getPlayers();
                    if(players.size() > 0) {
                        if(players.size() <= 5) {
                            sender.sendMessage("世界裡還有玩家： " +
                                    players.stream()
                                    .map(Player::getName)
                                    .collect(java.util.stream.Collectors.joining(", ")));
                        } else {
                            sender.sendMessage("世界裡還有玩家");
                        }
                        return true;
                    }

                    // Check if backup exists
                    String backup = args[2] + ".zip";
                    if(!worldDownloader.listBackups(world).stream()
                            .anyMatch(m -> m.getName().equals(args[2] + ".zip"))) {
                        sender.sendMessage("找不到備份");
                        return true;
                            }

                    // Cancel future backup, wait for current backup task
                    worldUploader.stopBackupWorldLater(world);
                    worldUploader.waitForBackup(world);

                    // Now download and restore
                    worldDownloader.restoreWorld(world, backup);

                    return true;
                }
            case "uploadspeed":
            case "us":
                if(args.length <= 1) {
                    int speed = getConfig().getInt("upload_speed");
                    sender.sendMessage("上傳速度：" + (speed > 0 ? speed : "無限制"));
                    return true;
                }
                try {
                    int speed = Integer.parseInt(args[1]);
                    getConfig().set("upload_speed", speed);
                    worldUploader.setUploadSpeed(speed);
                    sender.sendMessage("上傳速度設為：" + (speed > 0 ? speed : "無限制"));
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "downloadspeed":
            case "ds":
                if(args.length <= 1) {
                    int speed = getConfig().getInt("download_speed");
                    sender.sendMessage("下載速度：" + (speed > 0 ? speed : "無限制"));
                    return true;
                }
                try {
                    int speed = Integer.parseInt(args[1]);
                    getConfig().set("download_speed", speed);
                    worldDownloader.setDownloadSpeed(speed);
                    sender.sendMessage("下載速度設為：" + (speed > 0 ? speed : "無限制"));
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "disable":
                if(!checkCommandPermission(sender, "dropup.disable")) return true;
                if(args.length > 1) {
                    args[0] = "";
                    disabledReason = String.join(" ", args);
                } else {
                    disabledReason = "None";
                }
                disabled = true;
                sender.sendMessage("已暫停自動備份。原因： " + disabledReason);
                disabledReason += " - " + sender.getName();

                return true;
            case "enable":
                if(!checkCommandPermission(sender, "dropup.disable")) return true;
                disabled = false;
                sender.sendMessage("已恢復自動備份");
                return true;
            case "list":
            case "ls":
                if(args.length > 1) {
                    World world = getServer().getWorld(args[1]);
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    int counter = 10;
                    if(args.length > 2) {
                        try {
                            counter = Integer.parseInt(args[2]);
                        } catch(NumberFormatException e) {}
                    }

                    sender.sendMessage("備份列表：");
                    for(FileMetadata meta : worldDownloader.listBackups(world)) {
                        sender.sendMessage(meta.getName());
                        if(counter-- < 0) {
                            sender.sendMessage("More...");
                            break;
                        }
                    }
                    return true;
                } else if(mvWorldManager != null) {
                    sender.sendMessage("世界列表：");
                    for(com.onarandombox.MultiverseCore.api.MultiverseWorld world : mvWorldManager.getMVWorlds()) {
                        sender.sendMessage(world.getName());
                    }
                    return true;
                }
                break;
            case "book":
                break;
            case "signin":
                if(!checkCommandPermission(sender, "dropup.signin")) return true;
                if(args.length <= 1) {
                    sender.sendMessage("請到 " + getAuthURL() + " 取得認証碼，然後用 /dropup signin <認証碼> 登入");
                    return true;
                }

                try {
                    getConfig().set("dropbox_token", dbxAuth.finishFromCode(args[1].trim()).getAccessToken());
                } catch(DbxException e) {
                    sender.sendMessage("認証碼有誤");
                    return true;
                }

                if(!disabled) {
                    sender.sendMessage("登入資訊已修改，重啟後會重新登入");
                } else {
                    dropboxSignIn();
                    if(!disabled)
                        sender.sendMessage("已登入到 " + getLoginName() + " 的Dropbox");
                    else
                        sender.sendMessage("無法登入 Dropbox");
                }
                return true;
            default:
                return false;
        }

        return false;
    }

    private boolean checkCommandPermission(CommandSender sender, String perm) {
        if(!sender.hasPermission(perm)) {
            sender.sendMessage("你沒有權限做這件事");
            return false;
        }
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent eve) {
        Player player = eve.getPlayer();
        if(disabled && player.hasPermission("dropup.disable"))
            eve.getPlayer().sendMessage(String.format("[§e%s§f] 自動備份已被暫停。原因： §b%s", getName(), disabledReason));
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent eve) {
        if(eve.getMessage().startsWith("//")) {
            // TODO only trigger on specific commands
            worldUploader.backupWorldLater(eve.getPlayer().getLocation().getWorld());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent eve) {
        // TODO checking if player undid themself
        // Save the block at pos, then check it
        // maybe in world uploader or here
        worldUploader.backupWorldLater(eve.getBlock().getLocation().getWorld());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent eve) {
        // TODO checking if player undid themself
        worldUploader.backupWorldLater(eve.getBlock().getLocation().getWorld());
    }

    // TODO InventoryMoveItemEvent
    // TODO checking if player undid themself too

    public void onDisable() {
        if(worldUploader != null)
            worldUploader.finishAllBackups();
        if(worldDownloader != null)
            worldDownloader.removeDownloadDir();

        saveConfig();
    }
}
