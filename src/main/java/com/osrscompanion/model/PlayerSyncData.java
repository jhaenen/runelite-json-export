package com.osrscompanion.model;

import java.util.List;
import java.util.Map;

/**
 * Top-level data model written to local JSON sync files.
 * Serialized to JSON via Gson.
 */
public class PlayerSyncData
{
	public int schemaVersion = 1;
	public String lastUpdated;

	public PlayerInfo player;
	public Map<String, SkillEntry> skills;
	public BankData bank;
	// Potion Storage is a separate bank feature (its own tab, but backed by
	// varplayers + clientscripts, not an ItemContainer) - kept as its own
	// field rather than folded into bank.tabs so callers can always tell
	// regular bank items and stored potions apart without guessing at a
	// tab index convention.
	public List<PotionStorageEntry> potionStorage;
	public List<InventoryItem> inventory;
	public Map<String, ItemEntry> equipment;
	public List<QuestEntry> quests;
	public Map<String, DiaryRegion> achievementDiaries;
	// Raw per-task achievement diary bits, keyed by varplayer/varbit ID.
	// Decoded server-side (osrs-companion) against a task LUT, not here -
	// see PlayerDataCollector.DIARY_TASK_VARPS/DIARY_TASK_VARBITS.
	public Map<Integer, Integer> diaryTaskVarps;
	public Map<Integer, Integer> diaryTaskVarbits;
	public CombatAchievementData combatAchievements;
	public CollectionLogData collectionLog;

	public static class PlayerInfo
	{
		public String username;
		public int combatLevel;
		public int world;

		public PlayerInfo(String username, int combatLevel, int world)
		{
			this.username = username;
			this.combatLevel = combatLevel;
			this.world = world;
		}
	}

	public static class SkillEntry
	{
		public int level;
		public int xp;

		public SkillEntry(int level, int xp)
		{
			this.level = level;
			this.xp = xp;
		}
	}

	public static class ItemEntry
	{
		public int itemId;
		public String name;
		public int quantity;

		public ItemEntry(int itemId, String name, int quantity)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
		}
	}

	public static class InventoryItem extends ItemEntry
	{
		public int slot;

		public InventoryItem(int itemId, String name, int quantity, int slot)
		{
			super(itemId, name, quantity);
			this.slot = slot;
		}
	}

	public static class BankTab
	{
		public int tabIndex;
		public List<ItemEntry> items;

		public BankTab(int tabIndex, List<ItemEntry> items)
		{
			this.tabIndex = tabIndex;
			this.items = items;
		}
	}

	public static class BankData
	{
		public int totalItems;
		public List<BankTab> tabs;

		public BankData(int totalItems, List<BankTab> tabs)
		{
			this.totalItems = totalItems;
			this.tabs = tabs;
		}
	}

	public static class PotionStorageEntry
	{
		public int itemId;
		public String name;
		// Whole potions at the currently-configured withdraw dose tier
		// (matches what the in-game Potion Storage interface itself shows -
		// e.g. "Prayer potion(4)" x12 for 48 stored prayer-potion doses at
		// a 4-dose withdraw setting).
		public int quantity;
		// Total individual doses stored, independent of the withdraw
		// setting above - kept alongside quantity since doses is the
		// unambiguous underlying amount if the withdraw tier ever isn't 4.
		public int doses;
		public int doseTier;

		public PotionStorageEntry(int itemId, String name, int quantity, int doses, int doseTier)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.doses = doses;
			this.doseTier = doseTier;
		}
	}

	public static class QuestEntry
	{
		public String name;
		public String displayName;
		public String state;

		public QuestEntry(String name, String displayName, String state)
		{
			this.name = name;
			this.displayName = displayName;
			this.state = state;
		}
	}

	public static class DiaryRegion
	{
		public boolean easy;
		public boolean medium;
		public boolean hard;
		public boolean elite;

		public DiaryRegion(boolean easy, boolean medium, boolean hard, boolean elite)
		{
			this.easy = easy;
			this.medium = medium;
			this.hard = hard;
			this.elite = elite;
		}
	}

	public static class CombatAchievementData
	{
		public boolean easyComplete;
		public boolean mediumComplete;
		public boolean hardComplete;
		public boolean eliteComplete;
		public List<String> completedTasks;

		public CombatAchievementData(
			boolean easyComplete, boolean mediumComplete,
			boolean hardComplete, boolean eliteComplete,
			List<String> completedTasks)
		{
			this.easyComplete = easyComplete;
			this.mediumComplete = mediumComplete;
			this.hardComplete = hardComplete;
			this.eliteComplete = eliteComplete;
			this.completedTasks = completedTasks;
		}
	}

	public static class CollectionLogCategoryCount
	{
		public int completed;
		public int possible;

		public CollectionLogCategoryCount(int completed, int possible)
		{
			this.completed = completed;
			this.possible = possible;
		}
	}

	/**
	 * Collection log data has no full-state bulk API (no varbit/ItemContainer
	 * exposes "every unlocked item ever"), so this combines two sources:
	 * - total/categories: cheap per-tab completion counts from
	 *   VarPlayerID.COLLECTION_COUNT* varps, always in sync (no widget/UI
	 *   interaction needed - see PlayerDataCollector.pollCollectionLog()).
	 * - obtainedItems: a flat, ever-growing set of item names the client has
	 *   actually observed as obtained, from two passive sources: a "New item
	 *   added to your collection log" chat message (real-time, only while
	 *   listening), and COLLECTION_LOG_ITEM_SCRIPT_ID firing (only when an
	 *   external full collection-log export action - e.g. weirdgloop/WikiSync's
	 *   own "sync" button - happens to run while this plugin is also
	 *   listening; this plugin cannot trigger that itself). Neither source,
	 *   nor both together, guarantees full coverage of a player's existing
	 *   unlocks - this is a best-effort, additive log, not a complete
	 *   inventory. Resets to empty on plugin/client restart; the server
	 *   merges it as a union so previously-observed items are never lost.
	 *
	 * Investigated live on 2026-09-01: the game's own collection-log-page-
	 * population clientscript (fired while browsing a page, one call per
	 * item slot - id 1479 on the client build tested) fires for a
	 * category's *entire possible item pool*, obtained or not - verified
	 * against two known-missing items appearing in its output regardless.
	 * Deliberately not used as an obtained signal for that reason, unlike
	 * COLLECTION_LOG_ITEM_SCRIPT_ID above which was verified the same way to
	 * correctly exclude known-missing items.
	 */
	public static class CollectionLogData
	{
		public CollectionLogCategoryCount total;
		public Map<String, CollectionLogCategoryCount> categories;
		public List<String> obtainedItems;
	}
}
