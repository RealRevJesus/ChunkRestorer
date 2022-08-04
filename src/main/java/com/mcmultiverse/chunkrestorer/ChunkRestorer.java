package com.mcmultiverse.chunkrestorer;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;

import me.angeschossen.lands.api.integration.LandsIntegration;

public final class ChunkRestorer extends JavaPlugin implements Listener, CommandExecutor {
	
	private boolean hasLands = false;
	private LandsIntegration landsIntegration = null;
	
	File storage = new File(getDataFolder().getPath() + "/storage.yml");
	YamlConfiguration data;
	//boolean dataLock = false;

	private static long CHUNK_ROLLBACK_MS = -1;
	private static boolean DEBUG = false;
	
	public void onEnable() {
		
		this.saveDefaultConfig();
		
		this.getServer().getPluginManager().registerEvents(this, this);
		hasLands = this.getServer().getPluginManager().isPluginEnabled("Lands");
		if (hasLands) this.landsIntegration = new LandsIntegration(this);
		
		try {
			storage.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		data = new YamlConfiguration();
		
		try {
			data.load(storage);
		} catch (IOException | InvalidConfigurationException e) {
			e.printStackTrace();
		}

		CHUNK_ROLLBACK_MS = getConfig().getLong("millisBeforeRollback");
		DEBUG = getConfig().getBoolean("debug");
		
		this.getCommand("rollback-list").setExecutor(this);
		
	}
	
	public void onDisable() {
		
		HandlerList.unregisterAll((Plugin)this);
		
	}
	
	@EventHandler
	public void onBreak(BlockBreakEvent event) {
		
		Chunk chunk = event.getBlock().getChunk();
		String path = chunk.getWorld().getName() + "." + chunk.getX() + "_" + chunk.getZ();
		modifyData(path, System.currentTimeMillis());
		
	}
	
	@EventHandler
	public void onMake(BlockPlaceEvent event) {
		
		Chunk chunk = event.getBlock().getChunk();
		String path = chunk.getWorld().getName() + "." + chunk.getX() + "_" + chunk.getZ();
		modifyData(path, System.currentTimeMillis());
		
	}
	
	
	@EventHandler
	public void onChunkLoad(ChunkLoadEvent event) {
		
		Chunk chunk = event.getChunk();
		if (isClaimed(chunk)) return;
		String path = chunk.getWorld().getName() + "." + chunk.getX() + "_" + chunk.getZ();
		
		if (data.contains(path)) {
			
			if (System.currentTimeMillis() - data.getLong(path) >= CHUNK_ROLLBACK_MS) {
				
				attemptRollbackChunk(path, chunk);
				
			}
			
		} else {

			//modifyData(path, System.currentTimeMillis());
			
		}
		
	}
	
	public BlockVector3 getbv3fromlocation(Location l) {
		
		return BlockVector3.at(l.getBlockX(), l.getBlockY(), l.getBlockZ());
		
	}
	
	public boolean attemptRollbackChunk(String path, Chunk c) {
		
		//System.out.println("attempting: " + path);
		
		if (getConfig().getConfigurationSection("rollbackWorlds").contains(c.getWorld().getName())) {
			
			String wSetS = getConfig().getConfigurationSection("rollbackWorlds").getString(c.getWorld().getName());
			
			World wTemplate = Bukkit.getWorld(wSetS);
			
			if (wTemplate == null) return false;
			
			World wReal = c.getWorld();
			
			Location one = c.getBlock(0, 0, 0).getLocation();
			
			CuboidRegion region = getCuboidRegionFromChunk(c);
			BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
			
			wTemplate.getChunkAt(c.getX(), c.getZ()).load(true);

			try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(wTemplate))) {
			    ForwardExtentCopy forwardExtentCopy = new ForwardExtentCopy(
			        editSession, region, clipboard, region.getMinimumPoint()
			    );
			    // configure here
			    Operations.complete(forwardExtentCopy);
			} catch (WorldEditException e) {
				e.printStackTrace();
			}
			
			try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(wReal))) {
			    Operation operation = new ClipboardHolder(clipboard)
			            .createPaste(editSession)
			            .to(getbv3fromlocation(one))
			            .build();
			    Operations.complete(operation);
			} catch (WorldEditException e) {
				e.printStackTrace();
			}
			
			if (DEBUG) {
				getLogger().log(Level.INFO, "A rollback was completed at ["+path+"] from world ["+wSetS+"]");
			}
			
			modifyData(path, null);
			return true;
			
		} else {
			
			modifyData(path, null);
			return false;
			
		}
		
	}
	
	public CuboidRegion getCuboidRegionFromChunk(Chunk c) {
		
		Location one = c.getBlock(0, 0, 0).getLocation();
		Location two = c.getBlock(15, 255, 15).getLocation();
		
		CuboidRegion region = new CuboidRegion(getbv3fromlocation(one), getbv3fromlocation(two));
		
		return region;
		
	}
	
	public boolean isClaimed(Chunk c) {
		
		RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
		RegionManager regions = container.get(BukkitAdapter.adapt(c.getWorld()));
		if (regions == null) return false;
		
		Location one = c.getBlock(0, 0, 0).getLocation();
		Location two = c.getBlock(15, 255, 15).getLocation();
		
		ProtectedCuboidRegion region = new ProtectedCuboidRegion("dummy", getbv3fromlocation(one), getbv3fromlocation(two));

		ApplicableRegionSet set = regions.getApplicableRegions(region);
		
		for (ProtectedRegion r : set.getRegions()) {
			
			if (!r.getId().equals(ProtectedRegion.GLOBAL_REGION)) return true;
			
		}

		if (hasLands) return landsIntegration.isClaimed(c.getWorld(), c.getX(), c.getZ()); else return false;
		
	}
	
	public void modifyData(String path, Object value) {
		
		data.set(path, value);
		try {
			data.save(storage);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}
	
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

    	ConfigurationSection l = getConfig().getConfigurationSection("rollbackWorlds");
		
    	if (l.getKeys(false).isEmpty()) {
    		sender.sendMessage("§7There are no worlds set to rollback.");
    	} else {
    		sender.sendMessage("§7These are the world rollback links:");
        	for (String s : l.getKeys(false)) {
        		sender.sendMessage("§7"+l.getString(s)+" >> "+s);
        	}
    	}
    	
        return true;
    }

}
