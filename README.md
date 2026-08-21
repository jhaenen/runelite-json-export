# OSRS MCP Companion — RuneLite Plugin

A RuneLite plugin that pushes your player data as JSON to a server you
control, enabling AI assistants to give personalized Old School RuneScape
advice through the Model Context Protocol (MCP).

This is a fork of the original [osrs-companion RuneLite plugin](https://github.com/isaachansen/runelite-osrs-companion),
changed to **push** data over HTTP to a remote ingest endpoint instead of
writing a local file — useful when the MCP server doesn't run on the same
machine as the RuneLite client. See [osrs-companion](https://github.com/isaachansen/osrs-companion)
(and its fork) for the server side.

## What It Does

Sends a snapshot of your player data as JSON, via an authenticated HTTPS
POST, to an ingest endpoint you configure. That endpoint stores the latest
snapshot, and a companion MCP server reads it to give AI assistants context
about your account.

**You control where this goes.** The plugin only talks to the URL you put in
its config — the ingest URL is empty by default, so nothing is sent unless
you set one.

## What Gets Synced

| Data                | Trigger                                             |
|---------------------|------------------------------------------------------|
| Skill levels & XP   | On login + immediately when stats change             |
| Bank contents       | Immediately when you open your bank                  |
| Inventory           | Immediately on item changes                           |
| Equipment           | Immediately on equipment changes                      |
| Quest status        | Polled and synced on the configured poll interval     |
| Achievement Diaries | Polled and synced on the configured poll interval     |
| Combat Achievements | Polled and synced on the configured poll interval     |

Quests/diaries/combat achievements have no change event in the RuneLite API,
so they're polled periodically rather than pushed on every change (avoids a
push on nearly every game tick). Skills, bank, inventory, and equipment push
immediately when they actually change.

A final push is also attempted on logout, on plugin shutdown, and via a JVM
shutdown hook (best-effort, in case the client is killed or crashes rather
than exiting cleanly).

All data categories can be individually toggled in the plugin settings.

## Configuration

- **Ingest URL**: Full URL of your ingest endpoint's `/snapshot` route
- **Ingest Token**: Bearer token configured on that endpoint (masked in the UI)
- **Poll Interval**: How often to poll/sync quests, diaries, and combat
  achievements (default: 60 seconds, minimum: 30)
- **Data Toggles**: Enable/disable syncing for each data category

## Using with an MCP Server

This plugin is one part of a three-piece system:

1. This plugin — pushes snapshots to your ingest endpoint
2. A small ingest service — receives and stores the latest snapshot per player
3. **[osrs-companion](https://github.com/isaachansen/osrs-companion)** (or
   its fork) — an MCP server that reads those snapshots and exposes them as
   tools, plus wiki search, page summaries, and GE price lookups

See the MCP server repo's README for how to deploy the ingest endpoint and
connect an MCP client.

## Privacy

- Data is sent only to the URL you configure — nothing by default
- The connection is authenticated with a bearer token you control
- You control exactly what gets synced via the config panel, and where it goes

## License

BSD 2-Clause "Simplified" License. See [LICENSE](LICENSE).
