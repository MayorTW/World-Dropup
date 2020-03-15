package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.bukkit.block.BlockState;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.World;

import com.onarandombox.MultiverseCore.api.MVPlugin;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;

import tw.mayortw.dropup.util.BookUtil;
import tw.mayortw.dropup.util.GoogleDriveUtil;

public class DropupPlugin extends JavaPlugin implements Listener, BlockLogger.Callback {

    private WorldUploader worldUploader;
    private WorldDownloader worldDownloader;
    private MVWorldManager mvWorldManager;
    private BlockLogger blockLogger = new BlockLogger(this, this);
    private GoogleDriveUtil drive = new GoogleDriveUtil();

    private boolean disabled = false;
    private String disabledReason;

    @Override
    public void onEnable() {
        PluginManager pluginManager = getServer().getPluginManager();
        // Multiverse load/unload support
        MVPlugin mvPlugin = (MVPlugin) pluginManager.getPlugin("Multiverse-Core");
        if(mvPlugin == null)
            getLogger().warning("Multiverse-Core not found or not enabled, won't be able to hot-restore");
        else
            mvWorldManager = mvPlugin.getCore().getMVWorldManager();

        saveDefaultConfig();
        driveSignIn();
        pluginManager.registerEvents(this, this);
        pluginManager.registerEvents(blockLogger, this);
    }

    private void driveSignIn() {
        String token = getConfig().getString("drive_token");

        if(token == null) {
            loginFailed();
        } else {
            try {
                drive.loginToken(token);
                loginSuccess();
                getLogger().info("Logged into Google Drive as " + drive.getLoginName());
            } catch(GoogleDriveUtil.GoogleDriveException e) {
                loginFailed();
            }
        }
    }

    private void loginFailed() {
        getLogger().warning("Google Drive not signed in. Go to " + drive.getAuthUrl() + " to get the authorization code and type /dropup signin <code> to sign in");
        worldUploader = null;
        worldDownloader = null;
        disabled = true;
        disabledReason = "Google Drive 登入錯誤";
    }

    private void loginSuccess() {
        worldUploader = new WorldUploader(this, drive, blockLogger::reset);
        worldDownloader = new WorldDownloader(this, drive, mvWorldManager);
        disabled = false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if(args.length < 1) return false;
        if(!drive.loggedIn() && !args[0].equalsIgnoreCase("signin")) {
            sender.sendMessage("Google Drive 尚未登入，請用 /dropup signin 來登入");
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
                    } else if(worldUploader.isAwaiting(world)) {
                        sender.sendMessage(world.getName() + " 已經在等待備份了");
                    } else {
                        sender.sendMessage("準備備份 " + world.getName());
                        worldUploader.backupWorld(world);
                    }
                    return true;
                }
            case "backupall":
            case "bkall":
                {
                    if(!checkCommandPermission(sender, "dropup.backup")) return true;
                    sender.sendMessage("開始備份所有世界");
                    for(World world : getServer().getWorlds()) {
                        worldUploader.backupWorld(world);
                    }
                    return true;
                }
            case "backuptime":
            case "bktime":
                if(!checkCommandPermission(sender, "dropup.setting")) return true;
                if(args.length <= 1) {
                    int time = getConfig().getInt("min_interval");
                    sender.sendMessage("最快自動備份時間： " + time + "秒");
                    return true;
                }
                try {
                    int time = Integer.parseInt(args[1]);
                    getConfig().set("min_interval", time);
                    sender.sendMessage("最快自動備份時間設為： " + time + "秒");
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "maxbackup":
            case "maxbk":
                if(!checkCommandPermission(sender, "dropup.setting")) return true;
                if(args.length <= 1) {
                    int maxSaves = getConfig().getInt("max_saves");
                    sender.sendMessage("最多備份數量： " + maxSaves + "個");
                    return true;
                }
                try {
                    int maxSaves = Integer.parseInt(args[1]);
                    getConfig().set("max_saves", maxSaves);
                    sender.sendMessage("最多備份數量設為： " + maxSaves + "個");
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "restore":
            case "re":
                {
                /*
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

                    if(worldDownloader.getCurrentWorld() != null) {
                        sender.sendMessage("還有世界在回復，晚點再試");
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

                    // Cancel future backup
                    worldUploader.stopBackupWorldLater(world);

                    // wait for current backup task the restore
                    getServer().getScheduler().runTaskAsynchronously(this, () -> {
                        sender.sendMessage("準備恢復 " + world.getName());
                        worldUploader.waitForBackup(world);

                        // Now download and restore
                        getServer().getScheduler().runTask(this, () -> {
                            worldDownloader.restoreWorld(world, backup);
                        });
                    });

                    */
                    return true;
                }

            case "delete":
                {
                    if(!checkCommandPermission(sender, "dropup.delete")) return true;

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

                    worldUploader.deleteBackup(world, args[2] + ".zip", false);

                    return true;
                }

            case "uploadspeed":
            case "us":
                if(!checkCommandPermission(sender, "dropup.setting")) return true;
                if(args.length <= 1) {
                    int speed = getConfig().getInt("upload_speed");
                    sender.sendMessage("上傳速度： " + (speed > 0 ? speed + "kb/s" : "無限制"));
                    return true;
                }
                try {
                    int speed = Integer.parseInt(args[1]);
                    getConfig().set("upload_speed", speed);
                    worldUploader.setUploadSpeed(speed);
                    sender.sendMessage("上傳速度設為： " + (speed > 0 ? speed + "kb/s" : "無限制"));
                } catch(NumberFormatException e) {
                    sender.sendMessage(args[1] + " 不是一個數字");
                }
                return true;
            case "downloadspeed":
            case "ds":
                if(!checkCommandPermission(sender, "dropup.setting")) return true;
                if(args.length <= 1) {
                    int speed = getConfig().getInt("download_speed");
                    sender.sendMessage("下載速度：" + (speed > 0 ? speed + "kb/s" : "無限制"));
                    return true;
                }
                try {
                    int speed = Integer.parseInt(args[1]);
                    getConfig().set("download_speed", speed);
                    worldDownloader.setDownloadSpeed(speed);
                    sender.sendMessage("下載速度設為：" + (speed > 0 ? speed + "kb/s" : "無限制"));
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

            case "reload":
            case "rl":
                if(!checkCommandPermission(sender, "dropup.setting")) return true;
                reloadConfig();
                worldDownloader.setDownloadSpeed(getConfig().getInt("download_speed"));
                worldUploader.setUploadSpeed(getConfig().getInt("upload_speed"));
                sender.sendMessage("已重新載入設定檔");
                return true;

            case "list":
            case "ls":
                if(!checkCommandPermission(sender, "dropup.list")) return true;
                if(args.length > 1) {
                    World world = getServer().getWorld(args[1]);
                    if(world == null) {
                        sender.sendMessage("找不到世界");
                        return true;
                    }

                    int maxLines = 10;
                    int skip = 0;

                    if(args.length > 2) {
                        try {
                            skip = Integer.parseInt(args[2]);
                        } catch(NumberFormatException e) {}
                    }

                    sender.sendMessage("備份列表：");
                    List<String> files = worldDownloader.listBackups(world);
                    files.stream()
                        //.map(meta -> meta.getName().replaceAll("\\.[^.]*$", ""))
                        .sorted(Collections.reverseOrder())
                        .skip(skip)
                        .limit(maxLines)
                        .forEach(name -> {
                            sender.sendMessage(name);
                        });
                    if(files.size() - skip > maxLines)
                        sender.sendMessage("More...");

                    return true;
                } else if(mvWorldManager != null) {
                    sender.sendMessage("世界列表：");
                    for(com.onarandombox.MultiverseCore.api.MultiverseWorld world : mvWorldManager.getMVWorlds()) {
                        sender.sendMessage(world.getName());
                    }
                    return true;
                }
                break;

            case "status":
            case "st":
                if(!checkCommandPermission(sender, "dropup.list")) return true;

                sender.sendMessage("§f備份中：");
                World uploading = worldUploader.getCurrentWorld();
                if(uploading != null)
                    sender.sendMessage("§e    " + uploading.getName());
                else
                    sender.sendMessage("    無");

                sender.sendMessage("§f等待中：");
                World[] awaiting = worldUploader.getAwaitingWorlds();
                if(awaiting.length > 0) {
                    for(World world : awaiting) {
                        sender.sendMessage("§e    " + world.getName());
                    }
                } else
                    sender.sendMessage("    無");

                sender.sendMessage("§f恢復中：");
                World restoring = worldDownloader.getCurrentWorld();
                if(restoring != null)
                    sender.sendMessage("§e    " + restoring.getName());
                else
                    sender.sendMessage("    無");

                return true;

            case "menu":
            case "me":
                if(!checkCommandPermission(sender, "dropup.list")) return true;
                if(sender instanceof Player) {
                    ArrayList<String> lines = new ArrayList<>();

                    if(args.length <= 1 && mvWorldManager != null) {
                            lines.add("{\"text\":\"世界列表:\\n\",\"color\":\"light_purple\",\"bold\":true}");
                            mvWorldManager.getMVWorlds().stream()
                                .map(world -> {
                                    String name = world.getName();
                                    String alias = world.getAlias();

                                    final int maxLength = 12;
                                    if(alias.length() > maxLength) {
                                        alias = alias.substring(0, maxLength - 6) + "…" + alias.substring(alias.length() - 6);
                                    }
                                    alias = org.apache.commons.lang.StringEscapeUtils.escapeJavaScript(alias);

                                    return String.format("{\"text\":\"%s\\n\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du me %s\"}}", alias, name);
                                })
                                .sorted().forEach(lines::add);
                    } else {
                        World world = getServer().getWorld(args[1]);
                        if(world == null) {
                            sender.sendMessage("找不到世界");
                            return true;
                        }

                        if(args.length <= 2) {

                            lines.add("{\"text\":\"返回\\n\",\"color\":\"dark_gray\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du me\"}}");

                            if(sender.hasPermission("dropup.backup"))
                                lines.add(String.format("{\"text\":\"立刻備份\\n\",\"color\":\"dark_green\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du bk %s\"}}", world.getName()));
                            if(sender.hasPermission("dropup.restore"))
                                lines.add(String.format("{\"text\":\"回復模式\\n\",\"color\":\"blue\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du me %s restore\"}}", world.getName()));
                            if(sender.hasPermission("dropup.delete"))
                                lines.add(String.format("{\"text\":\"刪除模式\\n\",\"color\":\"red\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du me %s delete\"}}", world.getName()));

                            lines.add("{\"text\":\"備份列表:\\n\",\"color\":\"light_purple\",\"bold\":true}");

                            if(sender.hasPermission("dropup.list")) {
                                /*
                                worldDownloader.listBackups(world).stream()
                                    .map(m -> m.getName().replaceAll("\\.[^.]*$", ""))
                                    .map(s -> String.format("{\"text\":\"%s\\n\"}", s))
                                    .sorted(Collections.reverseOrder())
                                    .forEachOrdered(lines::add);
                                    */
                            } else {
                                lines.add("{\"text\":\"沒有權限\\n\",\"color\":\"red\",\"bold\":true}");
                            }
                        } else {
                            lines.add(String.format("{\"text\":\"返回\\n\",\"color\":\"dark_gray\",\"bold\":true,\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du me %s\"}}", world.getName()));
                            switch(args[2]) {
                                case "restore":
                                    /*
                                    if(checkCommandPermission(sender, "dropup.restore")) {
                                        lines.add("{\"text\":\"回復:\\n\",\"color\":\"blue\",\"bold\":true}");
                                        worldDownloader.listBackups(world).stream()
                                            .map(m -> m.getName().replaceAll("\\.[^.]*$", ""))
                                            .map(s -> String.format("{\"text\":\"%s\\n\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du re %s %s\"}}", s, world.getName(), s))
                                            .sorted(Collections.reverseOrder())
                                            .forEachOrdered(lines::add);
                                    }
                                    */
                                    break;
                                case "delete":
                                    /*
                                    if(checkCommandPermission(sender, "dropup.delete")) {
                                        lines.add("{\"text\":\"刪除備份:\\n\",\"color\":\"red\",\"bold\":true}");
                                        worldDownloader.listBackups(world).stream()
                                            .map(m -> m.getName().replaceAll("\\.[^.]*$", ""))
                                            .map(s -> String.format("{\"text\":\"%s\\n\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/du delete %s %s\"}}", s, world.getName(), s))
                                            .sorted(Collections.reverseOrder())
                                            .forEachOrdered(lines::add);
                                    }
                                    */
                                    break;
                            }
                        }
                    }

                    BookUtil.openBook(BookUtil.createBook(lines), (Player) sender);
                } else {
                    sender.sendMessage("只有玩家才能使用");
                }
                return true;

            case "signin":
                if(!checkCommandPermission(sender, "dropup.signin")) return true;
                if(args.length <= 1) {
                    sender.sendMessage("請到以下網址取得認証碼，然後用 /dropup signin <認証碼> 登入");
                    sender.sendMessage(drive.getAuthUrl());
                    return true;
                }

                try {
                    drive.loginCode(args[1].trim());
                    getConfig().set("drive_token", drive.getToken());
                    sender.sendMessage("已登入到 " + drive.getLoginName() + " 的 Google Drive");
                    loginSuccess();
                } catch(GoogleDriveUtil.GoogleDriveException e) {
                    sender.sendMessage("無法登入 Google Drive: " + e.getMessage());
                }
                return true;

            default:
                return false;
        }

        return false;
    }

    private boolean checkCommandPermission(CommandSender sender, String perm) {
        if(!(sender instanceof ConsoleCommandSender) && !sender.hasPermission(perm)) {
            sender.sendMessage("你沒有權限做這件事。需要 " + perm);
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if(args.length == 1) {
            return Arrays.asList(Arrays.stream(new String[] {
                "backup", "bk", "backupall", "bkall",
                "backuptime", "bktime", "maxbackup", "maxbk",
                "restore", "re", "delete",
                "uploadspeed", "us", "downloadspeed", "ds",
                "disable", "enable", "reload", "rl",
                "list", "ls", "status", "st", "menu", "me", "signin"
            }).filter(s -> s.startsWith(args[0].toLowerCase())).toArray(String[]::new));
        } else if(args.length == 2) {
            if(mvWorldManager != null) {
                switch(args[0].toLowerCase()) {
                    case "backup":  case "bk":
                    case "restore": case "re":
                    case "delete":
                    case "list":    case "ls":
                    case "menu":    case "me":
                        if(!sender.hasPermission("dropup.list")) break;
                        return Arrays.asList(mvWorldManager.getMVWorlds().stream().map(MultiverseWorld::getName)
                                .filter(s -> s.startsWith(args[1])).toArray(String[]::new));
                }
            }
        } else if(args.length == 3) {
            switch(args[0].toLowerCase()) {
                case "restore": case "re":
                case "delete":
                    if(!sender.hasPermission("dropup.list")) break;
                    /*
                    World world = getServer().getWorld(args[1]);
                    if(world != null) {
                        return Arrays.asList(worldDownloader.listBackups(world).stream()
                                .map(m -> m.getName().replaceAll("\\.[^.]*$", ""))
                                .filter(s -> s.startsWith(args[2]))
                                .toArray(String[]::new));
                    }
                    */
            }
        }

        return null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent eve) {
        Player player = eve.getPlayer();
        if(disabled && player.hasPermission("dropup.disable"))
            eve.getPlayer().sendMessage(String.format("[§e%s§r] §f自動備份已被暫停。原因： §b%s", getName(), disabledReason));
    }

    @Override
    public void onWorldChanged(World world, int changeCount) {
        if(!disabled && changeCount > 0)
            worldUploader.backupWorldLater(world);
        else
            worldUploader.stopBackupWorldLater(world);
    }

    public void onDisable() {
        if(worldUploader != null) {
            worldUploader.finishAllBackups();
            worldUploader.stopWorker();
        }
        if(worldDownloader != null) {
            worldDownloader.stopAllDownloads();
            worldDownloader.removeDownloadDir();
        }

        saveConfig();
    }
}
