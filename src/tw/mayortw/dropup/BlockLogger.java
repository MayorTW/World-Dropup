package tw.mayortw.dropup;
/*
 * Written by R26
 */

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.Sign;
import org.bukkit.Bukkit;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.World;

public class BlockLogger implements Listener {

    private HashMap<Location, BlockState> blocksChanged = new HashMap<>();
    private HashSet<World> worldEdited = new HashSet<>();
    private Plugin plugin;
    private Callback cb;

    public BlockLogger(Plugin plugin, Callback cb) {
        this.plugin = plugin;
        this.cb = cb;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent eve) {
        afterEventUpdate(eve);
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent eve) {
        update(eve.getBlockPlaced().getLocation(), eve.getBlockReplacedState(), eve.getBlockPlaced().getState());
    }

    @EventHandler
    public void onSignChange(SignChangeEvent eve) {
        afterEventUpdate(eve);
    }

    @EventHandler
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent eve) {
        onPlayerBucket(eve);
    }

    @EventHandler
    public void onPlayerBucketFill(PlayerBucketFillEvent eve) {
        onPlayerBucket(eve);
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent eve) {
        if(eve.getMessage().startsWith("//")) {
            World world = eve.getPlayer().getWorld();
            worldEdited.add(world);
            cb.onWorldChanged(world, 1);
        }
    }

    public void onPlayerBucket(PlayerBucketEvent eve) {
        BlockState oldBlock = eve.getBlockClicked().getRelative(eve.getBlockFace()).getState();
        // Get the new block after this method finish
        // Then record the change
        Bukkit.getScheduler().runTask(plugin, () -> {
            BlockState newBlock = eve.getBlockClicked().getRelative(eve.getBlockFace()).getState();
            update(newBlock.getLocation(), oldBlock, newBlock);
        });
    }

    // Updater for events that changes block after the event call
    private void afterEventUpdate(BlockEvent eve) {
        BlockState oldBlock = eve.getBlock().getState();
        // Get the new block after this method finish
        // Then record the change
        Bukkit.getScheduler().runTask(plugin, () -> {
            BlockState newBlock = eve.getBlock().getState();
            update(newBlock.getLocation(), oldBlock, newBlock);
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent eve) {
        Container oldChest = toContainer(eve.getView().getTopInventory());
        if(oldChest == null) return;

        // Get the new block after this method finish
        // Then record the change
        Bukkit.getScheduler().runTask(plugin, () -> {
            Container newChest = toContainer(eve.getView().getTopInventory());
            if(newChest == null) return;
            update(newChest.getLocation(), oldChest, newChest);
        });
    }

    private Container toContainer(Inventory inv) {
        InventoryHolder holder = inv.getHolder();
        if(holder instanceof Container)
            return (Container) holder;
        return null;
    }

    public void reset() {
        blocksChanged.clear();
        worldEdited.clear();
    }

    public void reset(World world) {
        blocksChanged.keySet().removeIf(p -> p.getWorld().equals(world));
        worldEdited.remove(world);
    }

    private void update(Location pos, BlockState oldBlock, BlockState newBlock) {
        if(worldEdited.contains(pos.getWorld())) { // WorldEdit command was run in this world, not recording
            return;
        } if(!blocksChanged.containsKey(pos)) { // haven't changed, record the original block
            if(compareBlocks(oldBlock, newBlock)) return; // no change
            blocksChanged.put(pos, oldBlock);
        } else if(compareBlocks(blocksChanged.get(pos), newBlock)) { // changed back, remove from map
            blocksChanged.remove(pos);
        }

        World world = pos.getWorld();
        cb.onWorldChanged(world, (int) blocksChanged.keySet().stream().filter(p -> p.getWorld().equals(world)).count());
    }

    private boolean compareBlocks(BlockState a, BlockState b) {
        if(emptyBlock(a) && emptyBlock(b)) return true;

        if(!a.getType().equals(b.getType())) return false;
        if(!a.getBlockData().matches(b.getBlockData())) return false;

        if((a instanceof Container) && (b instanceof Container)) {
            ItemStack[] aItems = ((Container) a).getSnapshotInventory().getContents(),
                        bItems = ((Container) b).getSnapshotInventory().getContents();
            if(!Arrays.equals(aItems, bItems))
                return false;
        }

        if((a instanceof Sign) && (b instanceof Sign)) {
            if(!Arrays.equals(((Sign) a).getLines(), ((Sign) b).getLines()))
                return false;
        }

        return true;
    }

    // Make air, flowing lava and flowing water empty blocks (treat them as the same)
    private boolean emptyBlock(BlockState block) {
        if(block.getType() == Material.AIR) return true;
        BlockData data = block.getBlockData();
        if(data instanceof Levelled)
            return ((Levelled) data).getLevel() > 0;
        return false;
    }

    public static interface Callback {
        public void onWorldChanged(World world, int changeCount);
    }
}
