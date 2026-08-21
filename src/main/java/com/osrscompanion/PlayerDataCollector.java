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
	private List<InventoryItem> inventory = new ArrayList<>();
	private final Map<String, ItemEntry> equipment = new LinkedHashMap<>();
	private List<QuestEntry> quests = null;
	private Map<String, DiaryRegion> diaries = null;
	private CombatAchievementData combatAchievements = null;

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

	// Combat achievement tier threshold varbits
	private static final int CA_EASY_THRESHOLD = 4132;
	private static final int CA_MEDIUM_THRESHOLD = 10660;
	private static final int CA_HARD_THRESHOLD = 10661;
	private static final int CA_ELITE_THRESHOLD = 10662;

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

		// Combat achievements
		data.combatAchievements = combatAchievements;

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
