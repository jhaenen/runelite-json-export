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

## Running / Building

### Quick dev-mode testing

`src/test/java/com/osrscompanion/OsrsCompanionPluginTest.java` is RuneLite's
standard dev-mode harness — it registers this plugin as a builtin
(`ExternalPluginManager.loadBuiltin`) and launches an actual RuneLite client
built from source:

```
./gradlew run
```

This needs a JDK RuneLite's Gradle wrapper can actually run — check the
pinned version in `gradle/wrapper/gradle-wrapper.properties` against your
installed JDK before assuming a mismatch is something else; a JDK too new
for that Gradle version fails with `Unsupported class file major version
NN`, not an obviously-JDK-related error.

**Logging in with a Jagex account in this mode** doesn't work out of the
box — a locally-built/dev-mode client can't do the Jagex OAuth handshake on
its own; only the official RuneLite Launcher (or something reimplementing
its protocol) can. One-time bootstrap:

1. In the official RuneLite Launcher (not Jagex's launcher, not a
   third-party one), run with `--configure`, add
   `--insecure-write-credentials` to Client arguments, save.
2. Launch it once and log in — this writes `~/.runelite/credentials.properties`
   (a session that bypasses your password — treat it like one).
3. `./gradlew run` picks that file up automatically. **Delete it after
   you're done** (`rm ~/.runelite/credentials.properties`) and remove the
   `--insecure-write-credentials` arg — it has no reason to persist.
   ([RuneLite wiki: Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts))

### Running as your actual daily client (e.g. via Bolt)

`~/.runelite/sideloaded-plugins/` — the officially documented way to load an
unpublished plugin jar into a normal client in developer mode — **does not
exist in any released RuneLite client yet** as of this writing. It's real
code on RuneLite's `master` branch, but grepping the actual deployed
`client-*.jar` for `sideload` comes up empty; don't trust GitHub source
against a version number without checking the real jar first.

What does work: build a **self-contained jar** (this plugin's classes +
RuneLite + all deps, since `client`/`gson` are `compileOnly` and normally
supplied by the host client, but a standalone jar needs everything present
itself) and have your launcher run that instead of a vanilla client:

```
./gradlew shadowJar
# -> build/libs/osrs-companion-1.0-SNAPSHOT-all.jar
```

For **Bolt** specifically (github.com/Adamcake/Bolt, an alternative Jagex
Launcher for Linux):

1. Bolt's Flatpak sandbox has no general filesystem access — only its own
   `~/.var/app/com.adamcake.Bolt/` data dir. Copy the shadowJar there rather
   than pointing at it in place:
   ```
   cp build/libs/osrs-companion-1.0-SNAPSHOT-all.jar \
      ~/.var/app/com.adamcake.Bolt/data/bolt-launcher/osrs-companion-standalone.jar
   ```
2. Point Bolt at it in `~/.var/app/com.adamcake.Bolt/config/bolt-launcher/launcher.json`:
   ```json
   "runelite_use_custom_jar": true,
   "runelite_custom_jar": "/home/<you>/.var/app/com.adamcake.Bolt/data/bolt-launcher/osrs-companion-standalone.jar"
   ```
3. `ExternalPluginManager.loadBuiltin()` asserts that JVM assertions are
   enabled and refuses to run without them (`-ea`). Bolt's own
   `jvmArguments`/`clientArguments` settings did **not** reliably reach this
   custom-jar launch path when tested (Bolt version bundling RuneLite
   1.12.36/launcher 2.8.0) — use a Flatpak-level env override instead, which
   any JVM Bolt spawns inherits regardless of Bolt's own arg handling:
   ```
   flatpak override --user --env=JAVA_TOOL_OPTIONS="-ea -Dsun.java2d.uiScale=3" com.adamcake.Bolt
   ```
   (drop `-Dsun.java2d.uiScale=3` or change it if you don't need HiDPI
   scaling — Bolt normally sets this itself for a vanilla launch, but that
   also doesn't reach the custom-jar path.)
4. Bolt hardcodes a `-J...`-style argument for this launch path even with
   both settings above emptied out. `-J<opt>` is a convention understood by
   `net.runelite.launcher.Launcher` (translated into a real JVM flag before
   the client's `main()` runs) — but a standalone jar's `main()` skips that
   launcher entirely, so it arrives as a literal, unrecognized application
   argument and RuneLite's own arg parser (`joptsimple`) throws
   `UnrecognizedOptionException: J is not a recognized option` immediately
   on startup. `OsrsCompanionPluginTest.main()` filters out any `-J`-prefixed
   arg before calling `RuneLite.main()` to handle this - if you rename or
   replace that test/harness class, keep the filter.
5. Relaunch Bolt. Confirm the plugin actually loaded by checking for
   `OSRS Companion started` in
   `~/.var/app/com.adamcake.Bolt/data/bolt-launcher/.runelite/logs/client.log`
   (or the equivalent log path for whatever launcher you're using) rather
   than only checking the plugin list UI — a crash before the window opens
   looks identical to "nothing happened" from the outside, and the log is
   how every issue above was actually diagnosed.

Whenever you change the plugin, redo the `shadowJar` build and the `cp`
step above (step 2 doesn't need repeating — `launcher.json` still points at
the same path).

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
