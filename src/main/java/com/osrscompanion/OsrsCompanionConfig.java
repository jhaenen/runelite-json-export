package com.osrscompanion;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrscompanion")
public interface OsrsCompanionConfig extends Config
{
	@ConfigSection(
		name = "Sync Destination",
		description = "Where to push synced data",
		position = 0
	)
	String storageSection = "storage";

	@ConfigItem(
		keyName = "ingestUrl",
		name = "Ingest URL",
		description = "Full URL of your ingest endpoint's /snapshot route, e.g. https://your-domain.example/snapshot",
		section = storageSection,
		position = 0
	)
	default String ingestUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ingestToken",
		name = "Ingest Token",
		description = "Bearer token configured on the ingest endpoint",
		section = storageSection,
		position = 1,
		secret = true
	)
	default String ingestToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "syncIntervalSeconds",
		name = "Poll Interval (seconds)",
		description = "How often to poll and sync quest/diary/combat achievement completion (minimum 30). Skills, bank, inventory, and equipment sync immediately when changed.",
		section = storageSection,
		position = 2
	)
	default int syncIntervalSeconds()
	{
		return 60;
	}

	@ConfigSection(
		name = "Data",
		description = "Choose what data to sync",
		position = 1
	)
	String dataSection = "data";

	@ConfigItem(
		keyName = "syncSkills",
		name = "Sync Skills",
		description = "Include skill levels and XP",
		section = dataSection,
		position = 0
	)
	default boolean syncSkills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncBank",
		name = "Sync Bank",
		description = "Include bank contents (captured when bank is opened)",
		section = dataSection,
		position = 1
	)
	default boolean syncBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncInventory",
		name = "Sync Inventory",
		description = "Include current inventory contents",
		section = dataSection,
		position = 2
	)
	default boolean syncInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncEquipment",
		name = "Sync Equipment",
		description = "Include currently worn equipment",
		section = dataSection,
		position = 3
	)
	default boolean syncEquipment()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncQuests",
		name = "Sync Quests",
		description = "Include quest completion status",
		section = dataSection,
		position = 4
	)
	default boolean syncQuests()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncDiaries",
		name = "Sync Achievement Diaries",
		description = "Include achievement diary completion",
		section = dataSection,
		position = 5
	)
	default boolean syncDiaries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCombatAchievements",
		name = "Sync Combat Achievements",
		description = "Include combat achievement tasks",
		section = dataSection,
		position = 6
	)
	default boolean syncCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCollectionLog",
		name = "Sync Collection Log",
		description = "Include collection log completion counts and observed item unlocks",
		section = dataSection,
		position = 7
	)
	default boolean syncCollectionLog()
	{
		return true;
	}
}
