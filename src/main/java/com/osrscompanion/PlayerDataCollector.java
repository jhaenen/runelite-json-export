package com.osrscompanion;

import com.osrscompanion.model.PlayerSyncData;
import com.osrscompanion.model.PlayerSyncData.*;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects player data from the RuneLite Client API.
 * Maintains in-memory state that is updated via events and periodic polling.
 */
@Slf4j
public class PlayerDataCollector
{
	private final Client client;

	// Cached data
	private final Map<String, SkillEntry> skills = new LinkedHashMap<>();
	private List<BankTab> bankTabs = null;
	private int bankTotalItems = 0;
	private List<PotionStorageEntry> potionStorage = null;
	private List<InventoryItem> inventory = new ArrayList<>();
	private final Map<String, ItemEntry> equipment = new LinkedHashMap<>();
	private List<QuestEntry> quests = null;
	private Map<String, DiaryRegion> diaries = null;
	private Map<Integer, Integer> diaryTaskVarps = null;
	private Map<Integer, Integer> diaryTaskVarbits = null;
	private CombatAchievementData combatAchievements = null;
	private CollectionLogCategoryCount collectionLogTotal = null;
	private Map<String, CollectionLogCategoryCount> collectionLogCategories = null;
	// Ever-growing within this plugin instance's lifetime only (cleared on
	// client/plugin restart, not on logout) - see CollectionLogData's
	// javadoc in PlayerSyncData for why this is best-effort, not exhaustive.
	private final Set<String> obtainedCollectionLogItems = new LinkedHashSet<>();

	// Equipment slot names in order
	private static final String[] EQUIPMENT_SLOTS = {
		"HEAD", "CAPE", "AMULET", "WEAPON", "BODY",
		"SHIELD", "LEGS", "GLOVES", "BOOTS", "RING", "AMMO"
	};

	// Achievement diary varbit IDs: [easy, medium, hard, elite]
	private static final Map<String, int[]> DIARY_VARBITS = new LinkedHashMap<>();
	static
	{
		DIARY_VARBITS.put("ARDOUGNE", new int[]{4458, 4459, 4460, 4461});
		DIARY_VARBITS.put("FALADOR", new int[]{4462, 4463, 4464, 4465});
		DIARY_VARBITS.put("WILDERNESS", new int[]{4466, 4467, 4468, 4469});
		DIARY_VARBITS.put("WESTERN_PROVINCES", new int[]{4471, 4472, 4473, 4474});
		DIARY_VARBITS.put("KANDARIN", new int[]{4475, 4476, 4477, 4478});
		DIARY_VARBITS.put("VARROCK", new int[]{4479, 4480, 4481, 4482});
		DIARY_VARBITS.put("DESERT", new int[]{4483, 4484, 4485, 4486});
		DIARY_VARBITS.put("MORYTANIA", new int[]{4487, 4488, 4489, 4490});
		DIARY_VARBITS.put("FREMENNIK", new int[]{4491, 4492, 4493, 4494});
		DIARY_VARBITS.put("LUMBRIDGE_DRAYNOR", new int[]{4495, 4496, 4497, 4498});
		DIARY_VARBITS.put("KOUREND_KEBOS", new int[]{7925, 7926, 7927, 7928});
	}

	// Karamja is special - uses count-based varbits
	private static final int KARAMJA_EASY_COUNT_VARBIT = 3578;
	private static final int KARAMJA_MED_COUNT_VARBIT = 3599;
	private static final int KARAMJA_HARD_COUNT_VARBIT = 3611;
	private static final int KARAMJA_ELITE_COMPLETE_VARBIT = 4566;
	private static final int KARAMJA_EASY_TOTAL = 10;
	private static final int KARAMJA_MED_TOTAL = 19;
	private static final int KARAMJA_HARD_TOTAL = 10;

	// Per-task achievement diary state (distinct from the tier-complete
	// DIARY_VARBITS above). Individual tasks are packed as bits in these
	// varplayers/varbits - decoding (which bit means which task) happens
	// server-side in osrs-companion against osrs-companion/src/data/
	// achievementDiaryTasks.json, NOT here, so the task LUT can be
	// corrected without a plugin rebuild+redeploy. This plugin only reads
	// the raw values. Exact ID list computed from that same JSON - see
	// [[project-osrs-diary-tasks-feature]] memory for provenance/caveats
	// (some of these, esp. outside the Karamja 3566-3610 range, come from
	// an unverified third-party LUT and haven't been cross-checked against
	// live gameplay yet).
	private static final int[] DIARY_TASK_VARPS = {
		1176, 1177, 1178, 1179, 1180, 1181, 1182, 1183, 1184, 1185, 1186, 1187,
		1192, 1193, 1194, 1195, 1196, 1197, 1198, 1199, 1200, 2085, 2086
	};
	private static final int[] DIARY_TASK_VARBITS = {
		3566, 3567, 3568, 3569, 3570, 3571, 3572, 3573, 3574, 3575, 3577,
		3579, 3580, 3581, 3582, 3583, 3584, 3585, 3586, 3587, 3588, 3589,
		3590, 3591, 3592, 3593, 3594, 3595, 3596, 3597, 3598,
		3600, 3601, 3602, 3603, 3604, 3605, 3606, 3607, 3608, 3609, 3610,
		4499, 4500, 4501, 4502, 4503, 4504, 4505, 4506, 4507, 4508, 4509, 4510,
		4515, 4516, 4517, 4518, 4519, 4520, 4521, 4522, 4523, 4524, 4525,
		4526, 4527, 4528, 4529, 4530, 4531, 4532, 4533, 4534, 4535, 4536,
		4537, 4538, 4567, 7929, 7930, 7931, 7932
	};

	// Combat achievement tier threshold varbits
	private static final int CA_EASY_THRESHOLD = 4132;
	private static final int CA_MEDIUM_THRESHOLD = 10660;
	private static final int CA_HARD_THRESHOLD = 10661;
	private static final int CA_ELITE_THRESHOLD = 10662;

	// Collection log completion count varplayers. Confirmed against the
	// actual pinned runelite-api jar (net.runelite.api.gameval.VarPlayerID),
	// and cross-checked against real usage in two actively-maintained
	// third-party plugins (Dink's MetaNotifier#notifyLogin,
	// weirdgloop/WikiSync) - unlike diaries/CAs there's no core RuneLite
	// plugin for this to reference. These update without the Collection Log
	// interface ever being opened, unlike everything below in this file
	// related to individual item names.
	private static final int CLOG_TOTAL = net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT;
	private static final int CLOG_TOTAL_MAX = net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_MAX;
	private static final Map<String, int[]> CLOG_CATEGORY_VARPS = new LinkedHashMap<>();
	static
	{
		CLOG_CATEGORY_VARPS.put("BOSSES", new int[]{
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_BOSSES,
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_BOSSES_MAX});
		CLOG_CATEGORY_VARPS.put("RAIDS", new int[]{
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_RAIDS,
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_RAIDS_MAX});
		CLOG_CATEGORY_VARPS.put("CLUES", new int[]{
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_CLUES,
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_CLUES_MAX});
		CLOG_CATEGORY_VARPS.put("MINIGAMES", new int[]{
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_MINIGAMES,
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX});
		CLOG_CATEGORY_VARPS.put("OTHER", new int[]{
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_OTHER,
			net.runelite.api.gameval.VarPlayerID.COLLECTION_COUNT_OTHER_MAX});
	}

	// Fired by the game's own clientscript once per obtained item, only
	// while a Collection Log category page is open in-game (verified via
	// weirdgloop/WikiSync's onScriptPreFired, the OSRS Wiki's own client
	// plugin that uses this exact script id to build its item-completion
	// bitset). Independent of the "New addition" chat setting the regex
	// below depends on.
	static final int COLLECTION_LOG_ITEM_SCRIPT_ID = 4100;

	// Matches RuneLite's ChatMessageType.GAMEMESSAGE text for a new unlock,
	// which requires the player's "Collection log - New addition
	// notification: Chat message" game setting to be enabled (see
	// weirdgloop/WikiSync and Dink's CollectionNotifier, both of which
	// document this same dependency). Only fires for items unlocked while
	// this client is running and listening.
	private static final Pattern COLLECTION_LOG_CHAT_PATTERN =
		Pattern.compile("New item added to your collection log: (.+)");

	public PlayerDataCollector(Client client)
	{
		this.client = client;
	}

	/**
	 * Update a single skill from a StatChanged event.
	 */
	public void updateSkill(Skill skill, int level, int xp)
	{
		skills.put(skill.getName().toUpperCase(), new SkillEntry(level, xp));
	}

	/**
	 * Poll all skills from the client.
	 */
	public void pollAllSkills()
	{
		int totalLevel = 0;
		int totalXp = 0;
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				continue;
			}
			int level = client.getRealSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			skills.put(skill.getName().toUpperCase(), new SkillEntry(level, xp));
			totalLevel += level;
			totalXp += xp;
		}
		skills.put("OVERALL", new SkillEntry(totalLevel, totalXp));
	}

	/**
	 * Update bank contents from an ItemContainerChanged event.
	 * Bank tabs are separated by items with ID -1 in the container.
	 */
	public void updateBank(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}

		Item[] items = container.getItems();
		List<BankTab> tabs = new ArrayList<>();
		List<ItemEntry> currentTabItems = new ArrayList<>();
		int tabIndex = 0;
		int totalItems = 0;

		for (Item item : items)
		{
			if (item.getId() == -1)
			{
				// Tab separator
				if (!currentTabItems.isEmpty() || tabIndex == 0)
				{
					tabs.add(new BankTab(tabIndex, currentTabItems));
					tabIndex++;
					currentTabItems = new ArrayList<>();
				}
				continue;
			}

			if (item.getId() > 0 && item.getQuantity() > 0 && !isPlaceholder(item.getId()))
			{
				String name = getItemName(item.getId());
				currentTabItems.add(new ItemEntry(item.getId(), name, item.getQuantity()));
				totalItems++;
			}
		}

		// Add the last tab
		if (!currentTabItems.isEmpty())
		{
			tabs.add(new BankTab(tabIndex, currentTabItems));
		}

		// If no tab separators were found, put everything in tab 0
		if (tabs.isEmpty() && totalItems > 0)
		{
			tabs.add(new BankTab(0, currentTabItems));
		}

		this.bankTabs = tabs;
		this.bankTotalItems = totalItems;
	}

	/**
	 * Read Potion Storage contents (a separate bank feature, its own tab in
	 * the bank UI, but NOT backed by an ItemContainer - unlike every other
	 * category here). Storage is tracked as a handful of varplayers
	 * (doses per potion "family") rather than discrete item stacks, and the
	 * doses-to-item-id mapping depends on cache-defined enums plus the
	 * player's per-potion withdraw dose-tier setting. Ported from
	 * RuneLite's own core Bank Tags plugin
	 * (PotionStorage#rebuildPotions in
	 * runelite-client/.../plugins/banktags/tabs/PotionStorage.java), which
	 * is the authoritative source for this - the alternative is reverse
	 * engineering the varp bit-packing blind, which nothing here needs to
	 * do since the client already exposes the decoded result via these
	 * clientscripts.
	 *
	 * Call this whenever the bank is synced (same trigger as updateBank) -
	 * like bank tabs, this is only meaningful to read while the bank
	 * interface is open.
	 */
	public void updatePotionStorage()
	{
		try
		{
			List<PotionStorageEntry> entries = new ArrayList<>();

			EnumComposition potionSlots = client.getEnum(EnumID.POTIONSTORE_POTIONS);
			EnumComposition unfinishedPotionSlots = client.getEnum(EnumID.POTIONSTORE_UNFINISHED_POTIONS);

			for (EnumComposition slots : new EnumComposition[]{potionSlots, unfinishedPotionSlots})
			{
				if (slots == null)
				{
					continue;
				}

				for (int potionEnumId : slots.getIntVals())
				{
					EnumComposition potionEnum = client.getEnum(potionEnumId);
					if (potionEnum == null)
					{
						continue;
					}

					client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
					int doses = client.getIntStack()[0];
					client.runScript(ScriptID.POTIONSTORE_WITHDRAW_DOSES, potionEnumId);
					int withdrawDoses = client.getIntStack()[0];

					if (doses > 0 && withdrawDoses > 0)
					{
						int itemId = potionEnum.getIntValue(withdrawDoses);
						entries.add(new PotionStorageEntry(
							itemId, getItemName(itemId), doses / withdrawDoses, doses, withdrawDoses));
					}
				}
			}

			int vials = client.getVarpValue(net.runelite.api.gameval.VarPlayerID.POTIONSTORE_VIALS);
			if (vials > 0)
			{
				int vialItemId = net.runelite.api.gameval.ItemID.VIAL_EMPTY;
				entries.add(new PotionStorageEntry(vialItemId, getItemName(vialItemId), vials, vials, 1));
			}

			this.potionStorage = entries;
		}
		catch (Exception e)
		{
			log.debug("Failed to read potion storage", e);
		}
	}

	/**
	 * Update inventory from an ItemContainerChanged event.
	 */
	public void updateInventory(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}

		List<InventoryItem> inv = new ArrayList<>();
		Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			String name = item.getId() > 0 ? getItemName(item.getId()) : null;
			inv.add(new InventoryItem(item.getId(), name, item.getQuantity(), slot));
		}
		this.inventory = inv;
	}

	/**
	 * Update equipment from an ItemContainerChanged event.
	 */
	public void updateEquipment(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}

		Item[] items = container.getItems();
		for (int i = 0; i < EQUIPMENT_SLOTS.length && i < items.length; i++)
		{
			Item item = items[i];
			String name = item.getId() > 0 ? getItemName(item.getId()) : null;
			equipment.put(EQUIPMENT_SLOTS[i], new ItemEntry(item.getId(), name, item.getQuantity()));
		}
	}

	/**
	 * Poll quest completion status. Must be called on the client thread.
	 */
	public void pollQuests()
	{
		List<QuestEntry> questList = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			try
			{
				QuestState state = quest.getState(client);
				String stateName;
				switch (state)
				{
					case FINISHED:
						stateName = "FINISHED";
						break;
					case IN_PROGRESS:
						stateName = "IN_PROGRESS";
						break;
					default:
						stateName = "NOT_STARTED";
						break;
				}
				questList.add(new QuestEntry(quest.name(), quest.getName(), stateName));
			}
			catch (Exception e)
			{
				// Some quests may not be queryable
			}
		}
		this.quests = questList;
	}

	/**
	 * Poll achievement diary completion status via varbits.
	 */
	public void pollDiaries()
	{
		Map<String, DiaryRegion> diaryMap = new LinkedHashMap<>();

		// Standard diaries (varbit = 1 means complete)
		for (Map.Entry<String, int[]> entry : DIARY_VARBITS.entrySet())
		{
			int[] varbits = entry.getValue();
			diaryMap.put(entry.getKey(), new DiaryRegion(
				client.getVarbitValue(varbits[0]) == 1,
				client.getVarbitValue(varbits[1]) == 1,
				client.getVarbitValue(varbits[2]) == 1,
				client.getVarbitValue(varbits[3]) == 1
			));
		}

		// Karamja special case - count-based for easy/med/hard
		diaryMap.put("KARAMJA", new DiaryRegion(
			client.getVarbitValue(KARAMJA_EASY_COUNT_VARBIT) >= KARAMJA_EASY_TOTAL,
			client.getVarbitValue(KARAMJA_MED_COUNT_VARBIT) >= KARAMJA_MED_TOTAL,
			client.getVarbitValue(KARAMJA_HARD_COUNT_VARBIT) >= KARAMJA_HARD_TOTAL,
			client.getVarbitValue(KARAMJA_ELITE_COMPLETE_VARBIT) == 1
		));

		this.diaries = diaryMap;

		// Raw per-task state - see DIARY_TASK_VARPS/DIARY_TASK_VARBITS above.
		Map<Integer, Integer> varpMap = new LinkedHashMap<>();
		for (int varpId : DIARY_TASK_VARPS)
		{
			varpMap.put(varpId, client.getVarpValue(varpId));
		}
		this.diaryTaskVarps = varpMap;

		Map<Integer, Integer> varbitMap = new LinkedHashMap<>();
		for (int varbitId : DIARY_TASK_VARBITS)
		{
			varbitMap.put(varbitId, client.getVarbitValue(varbitId));
		}
		this.diaryTaskVarbits = varbitMap;
	}

	/**
	 * Poll combat achievement tier completion.
	 * Individual task tracking is expensive (~399 varbits), so we just track tier completion.
	 */
	public void pollCombatAchievements()
	{
		boolean easyDone = client.getVarbitValue(CA_EASY_THRESHOLD) == 1;
		boolean medDone = client.getVarbitValue(CA_MEDIUM_THRESHOLD) == 1;
		boolean hardDone = client.getVarbitValue(CA_HARD_THRESHOLD) == 1;
		boolean eliteDone = client.getVarbitValue(CA_ELITE_THRESHOLD) == 1;

		// For now, just track tier completion (not individual tasks - too many varbits)
		this.combatAchievements = new CombatAchievementData(
			easyDone, medDone, hardDone, eliteDone,
			Collections.emptyList()
		);
	}

	/**
	 * Poll collection log completion counts via varplayers. Cheap and always
	 * in sync - unlike combat achievements/diaries this needs no clientscript
	 * or widget interaction at all. COLLECTION_COUNT_MAX being 0 means the
	 * client hasn't populated these varps yet (e.g. very early after login);
	 * skip rather than record a bogus 0/0 in that case.
	 */
	public void pollCollectionLog()
	{
		int totalMax = client.getVarpValue(CLOG_TOTAL_MAX);
		if (totalMax <= 0)
		{
			return;
		}

		this.collectionLogTotal = new CollectionLogCategoryCount(client.getVarpValue(CLOG_TOTAL), totalMax);

		Map<String, CollectionLogCategoryCount> categories = new LinkedHashMap<>();
		for (Map.Entry<String, int[]> entry : CLOG_CATEGORY_VARPS.entrySet())
		{
			int[] varps = entry.getValue();
			categories.put(entry.getKey(), new CollectionLogCategoryCount(
				client.getVarpValue(varps[0]), client.getVarpValue(varps[1])));
		}
		this.collectionLogCategories = categories;
	}

	/**
	 * Check a game-message chat line for a collection log unlock. Call for
	 * every ChatMessageType.GAMEMESSAGE line - cheap regex miss on anything
	 * else.
	 */
	public void onCollectionLogChatMessage(String message)
	{
		if (message == null)
		{
			return;
		}
		Matcher matcher = COLLECTION_LOG_CHAT_PATTERN.matcher(message);
		if (matcher.find())
		{
			obtainedCollectionLogItems.add(matcher.group(1).trim());
		}
	}

	/**
	 * Record an item observed as obtained via the collection log's own
	 * item-population clientscript (see COLLECTION_LOG_ITEM_SCRIPT_ID).
	 */
	public void onCollectionLogItemScriptFired(int itemId)
	{
		String name = getItemName(itemId);
		if (name != null && !"Unknown".equals(name))
		{
			obtainedCollectionLogItems.add(name);
		}
	}

	/**
	 * Build the full snapshot for saving.
	 */
	public PlayerSyncData buildSnapshot()
	{
		PlayerSyncData data = new PlayerSyncData();
		data.lastUpdated = Instant.now().toString();

		// Player info
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer != null)
		{
			data.player = new PlayerInfo(
				localPlayer.getName(),
				localPlayer.getCombatLevel(),
				client.getWorld()
			);
		}

		// Skills
		if (!skills.isEmpty())
		{
			data.skills = new LinkedHashMap<>(skills);
		}

		// Bank
		if (bankTabs != null)
		{
			data.bank = new BankData(bankTotalItems, bankTabs);
		}

		// Potion Storage
		if (potionStorage != null)
		{
			data.potionStorage = potionStorage;
		}

		// Inventory
		data.inventory = new ArrayList<>(inventory);

		// Equipment
		if (!equipment.isEmpty())
		{
			data.equipment = new LinkedHashMap<>(equipment);
		}

		// Quests
		data.quests = quests;

		// Diaries
		data.achievementDiaries = diaries;
		data.diaryTaskVarps = diaryTaskVarps;
		data.diaryTaskVarbits = diaryTaskVarbits;

		// Combat achievements
		data.combatAchievements = combatAchievements;

		// Collection log - only send once at least one valid count poll has
		// happened; obtainedItems rides along with it (empty list until the
		// first chat/script observation, never null once counts exist).
		if (collectionLogTotal != null)
		{
			CollectionLogData clog = new CollectionLogData();
			clog.total = collectionLogTotal;
			clog.categories = collectionLogCategories;
			clog.obtainedItems = new ArrayList<>(obtainedCollectionLogItems);
			data.collectionLog = clog;
		}

		return data;
	}

	/**
	 * Get the human-readable item name from the client's item definitions.
	 */
	private String getItemName(int itemId)
	{
		try
		{
			ItemComposition def = client.getItemDefinition(itemId);
			return def != null ? def.getName() : "Unknown";
		}
		catch (Exception e)
		{
			return "Unknown";
		}
	}

	/**
	 * Bank placeholders (left behind via "Item > Placeholder" to keep an
	 * item's slot position after withdrawing all of it) occupy a real slot
	 * in the bank's ItemContainer with a positive quantity - RuneLite
	 * reports them at quantity 1, not 0, so the qty>0 check above doesn't
	 * catch them. They're a distinct item ID from the real item though
	 * (Jagex gives every placeholder-eligible item a separate placeholder
	 * variant ID with the same display name), detectable via
	 * ItemComposition#getPlaceholderTemplateId(): -1 for a real item,
	 * the base item's ID for a placeholder.
	 */
	private boolean isPlaceholder(int itemId)
	{
		try
		{
			ItemComposition def = client.getItemDefinition(itemId);
			return def != null && def.getPlaceholderTemplateId() != -1;
		}
		catch (Exception e)
		{
			return false;
		}
	}
}
