package com.osrscompanion;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.osrscompanion.model.PlayerSyncData;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@PluginDescriptor(
	name = "OSRS MCP Companion",
	description = "Pushes player data as JSON to a configured endpoint for use with AI assistants via MCP",
	tags = {"sync", "data", "export", "mcp", "ai"}
)
public class OsrsCompanionPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private OsrsCompanionConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Gson gson;

	private PlayerDataCollector collector;
	private PlayerDataWriter writer;
	private boolean dirty = false;
	private int tickCounter = 0;
	private int syncTickThreshold = 100; // recalculated from config
	private boolean initialCollectionDone = false;
	private Thread shutdownHook;

	@Override
	protected void startUp()
	{
		collector = new PlayerDataCollector(client);
		writer = new PlayerDataWriter(gson, config);
		recalcSyncThreshold();

		// Best-effort final flush if the client is killed/crashes rather
		// than cleanly logging out or being disabled - neither
		// onGameStateChanged(LOGIN_SCREEN) nor shutDown() fires on a hard
		// kill, so this is otherwise a gap.
		shutdownHook = new Thread(this::shutdownFlush, "osrs-companion-shutdown-flush");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		log.info("OSRS Companion started — pushing to configured ingest endpoint");
	}

	@Override
	protected void shutDown()
	{
		if (shutdownHook != null)
		{
			try
			{
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			}
			catch (IllegalStateException ignored)
			{
				// JVM is already shutting down - the hook will run itself
			}
			shutdownHook = null;
		}

		// Final save on shutdown
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			doSave();
		}
		collector = null;
		writer = null;
		log.info("OSRS Companion stopped");
	}

	/**
	 * Runs on the JVM shutdown hook thread, so it must not depend on the
	 * plugin's executor (which may already be shutting down). The JVM waits
	 * for this thread to return before exiting, so the actual network call
	 * runs on a separate daemon thread with a bounded wait here - a hung or
	 * misbehaving HTTP call must never be able to block shutdown forever,
	 * only delay it by at most SHUTDOWN_FLUSH_TIMEOUT_MS.
	 */
	private static final long SHUTDOWN_FLUSH_TIMEOUT_MS = 5000;

	private void shutdownFlush()
	{
		try
		{
			if (client.getGameState() != GameState.LOGGED_IN || collector == null || writer == null)
			{
				return;
			}

			PlayerSyncData snapshot = collector.buildSnapshot();
			if (snapshot.player == null)
			{
				return;
			}

			Thread worker = new Thread(() -> writer.write(snapshot), "osrs-companion-shutdown-flush-worker");
			worker.setDaemon(true);
			worker.start();
			worker.join(SHUTDOWN_FLUSH_TIMEOUT_MS);
		}
		catch (Exception e)
		{
			log.warn("OSRS Companion: shutdown flush failed", e);
		}
	}

	@Provides
	OsrsCompanionConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsCompanionConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Delay initial collection to let the client populate data
			tickCounter = -10;
			initialCollectionDone = false;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Save before logout
			doSave();
			initialCollectionDone = false;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (collector != null && config.syncSkills())
		{
			collector.updateSkill(event.getSkill(), event.getLevel(), event.getXp());
			saveNow();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (collector == null)
		{
			return;
		}

		int containerId = event.getContainerId();

		if (containerId == InventoryID.BANK.getId() && config.syncBank())
		{
			collector.updateBank(event.getItemContainer());
			collector.updatePotionStorage();
			saveNow();
		}
		else if (containerId == InventoryID.INVENTORY.getId() && config.syncInventory())
		{
			collector.updateInventory(event.getItemContainer());
			saveNow();
		}
		else if (containerId == InventoryID.EQUIPMENT.getId() && config.syncEquipment())
		{
			collector.updateEquipment(event.getItemContainer());
			saveNow();
		}
	}

	// Note: varbits change far more often than actual quest/diary/combat
	// achievement completions (many are unrelated UI/game state), so those
	// categories are intentionally left on the periodic poll in
	// onGameTick() below rather than saved on every VarbitChanged event -
	// doing that would fire an HTTP push on nearly every tick during
	// normal play.

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (collector == null || !config.syncCollectionLog())
		{
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		// Collection log observations only touch the in-memory obtained-item
		// set here, not a full poll - left for the next periodic tick below
		// to actually push, same as quests/diaries/CAs. Opening a log page
		// full of already-owned items can fire many of these (or the
		// script-fired handler below) in a single tick; deferring to the
		// poll interval avoids turning that into an HTTP push per item.
		collector.onCollectionLogChatMessage(event.getMessage());
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (collector == null || !config.syncCollectionLog())
		{
			return;
		}
		if (event.getScriptId() != PlayerDataCollector.COLLECTION_LOG_ITEM_SCRIPT_ID)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args.length > 1 && args[1] instanceof Integer)
		{
			collector.onCollectionLogItemScriptFired((Integer) args[1]);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN || collector == null)
		{
			return;
		}

		tickCounter++;

		// Initial full collection after login (after a short delay)
		if (!initialCollectionDone && tickCounter >= 0)
		{
			doFullCollection();
			initialCollectionDone = true;
			dirty = true;
			// Save immediately after initial collection
			doSave();
			return;
		}

		// Quests/diaries/combat achievements only change via polling (no
		// change event exists for them), so they're synced on the
		// configured poll interval rather than write-through like the
		// other categories.
		if (tickCounter >= syncTickThreshold)
		{
			tickCounter = 0;
			boolean polled = false;

			if (config.syncQuests())
			{
				collector.pollQuests();
				polled = true;
			}
			if (config.syncDiaries())
			{
				collector.pollDiaries();
				polled = true;
			}
			if (config.syncCombatAchievements())
			{
				collector.pollCombatAchievements();
				polled = true;
			}
			if (config.syncCollectionLog())
			{
				collector.pollCollectionLog();
				polled = true;
			}

			if (polled)
			{
				saveNow();
			}
		}
	}

	/**
	 * Perform a full collection of all data sources.
	 */
	private void doFullCollection()
	{
		if (config.syncSkills())
		{
			collector.pollAllSkills();
		}

		if (config.syncBank())
		{
			ItemContainer bank = client.getItemContainer(InventoryID.BANK);
			if (bank != null)
			{
				collector.updateBank(bank);
				collector.updatePotionStorage();
			}
		}

		if (config.syncInventory())
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				collector.updateInventory(inv);
			}
		}

		if (config.syncEquipment())
		{
			ItemContainer equip = client.getItemContainer(InventoryID.EQUIPMENT);
			if (equip != null)
			{
				collector.updateEquipment(equip);
			}
		}

		if (config.syncQuests())
		{
			collector.pollQuests();
		}

		if (config.syncDiaries())
		{
			collector.pollDiaries();
		}

		if (config.syncCombatAchievements())
		{
			collector.pollCombatAchievements();
		}

		if (config.syncCollectionLog())
		{
			collector.pollCollectionLog();
		}
	}

	/**
	 * Mark data dirty and push it immediately (write-through) rather than
	 * waiting for the next periodic save.
	 */
	private void saveNow()
	{
		dirty = true;
		doSave();
	}

	/**
	 * Build snapshot and push it to the ingest endpoint in the background.
	 */
	private void doSave()
	{
		if (collector == null || writer == null || !dirty)
		{
			return;
		}

		PlayerSyncData snapshot = collector.buildSnapshot();
		if (snapshot.player == null)
		{
			return;
		}

		dirty = false;

		executor.submit(() ->
		{
			try
			{
				writer.write(snapshot);
			}
			catch (Exception e)
			{
				log.warn("OSRS Companion: Save error", e);
			}
		});
	}

	/**
	 * Recalculate the game tick threshold for sync interval.
	 * 1 game tick ≈ 0.6 seconds.
	 */
	private void recalcSyncThreshold()
	{
		int seconds = Math.max(30, config.syncIntervalSeconds());
		syncTickThreshold = (int) (seconds / 0.6);
	}
}
