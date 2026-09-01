# CLAUDE.md

Guidance for Claude Code (or any agent) working in this repo. Running list
of hard-won gotchas - not a restatement of the README's build/run
instructions, only things worth knowing *before* they cost you a debugging
session (or, in one case, a frozen live game client).

## Never overwrite a running client's jar file in place

**This is the single most expensive lesson from this project.** When
deploying a rebuilt standalone jar (see README's "Running as your actual
daily client" section), staging it with a plain `cp` onto the path Bolt's
`launcher.json` points at - while a client process built from that exact
jar is still running - froze the live game twice.

Why: Java loads classes from a jar lazily, on demand, as code paths are
actually exercised. A running process can carry on working perfectly for
minutes after the backing file's bytes have been replaced underneath it,
right up until it tries to load a class it hasn't touched yet (in the
incidents here, that was the logout flow specifically, which nobody
exercises every session). At that point it reads garbage from the wrong
offset in a file that's changed shape since the process cached its central
directory, and throws `ClassNotFoundException`/`NoClassDefFoundError` from
deep inside the obfuscated game client - which looks exactly like a missing
dependency, not a stale-file-handle problem, and can send you down a
completely wrong investigation (ask me how I know).

**Fix: always stage via write-to-temp-file then atomic rename**, never a
direct in-place `cp`:

```bash
cp build/libs/osrs-companion-1.0-SNAPSHOT-all.jar \
   ~/.var/app/com.adamcake.Bolt/data/bolt-launcher/.osrs-companion-standalone.jar.tmp
mv ~/.var/app/com.adamcake.Bolt/data/bolt-launcher/.osrs-companion-standalone.jar.tmp \
   ~/.var/app/com.adamcake.Bolt/data/bolt-launcher/osrs-companion-standalone.jar
```

`mv` within the same filesystem is an atomic rename - a running process's
already-open file descriptor keeps referencing the original inode's
complete, unmodified content regardless of when the rename happens; a
freshly-started process gets the complete new file. There is no unsafe
window either way. This is standard Unix "atomic deploy" practice for
exactly this reason - use it here every time, no exceptions, even though
the change only affects the *next* client start.

## Checking whether the client is currently running

The atomic-rename deploy above is safe *unconditionally* - it never
depends on whether a client is running. But it's still worth knowing
whether one is, e.g. to tell the user a relaunch is needed to pick up the
new jar (it only affects the *next* start).

**Incident (2026-08-21):** an agent ran `pgrep -af "Bolt\|RuneLite\|runelite"`,
got no match, and told the user no client was running - while Bolt was
actually running, logged in. The user had to notice and kill it manually.
**Root cause: `\|` is not alternation in `pgrep`'s regex dialect.** `pgrep`
matches with POSIX *extended* regex (like `egrep`), where alternation is a
bare `|`; `\|` is a literal escaped pipe character, so the pattern was
actually searching for the literal substring `Bolt|RuneLite|runelite`
(with pipe characters in it) - which of course never appears in a real
command line. This is a `grep`-BRE habit (`\|` for alternation) that
doesn't carry over. It was *not* a Flatpak-sandboxing/process-visibility
issue - the real java process (`.../bolt-launcher/osrs-companion-standalone.jar`)
is fully visible in `ps aux`/`pgrep` from the host, `com.adamcake.Bolt`
included, once the regex is correct:

```bash
pgrep -af "Bolt|RuneLite|runelite"   # correct: bare | for alternation
```

**Prefer `flatpak ps` anyway** - it's simpler, has no regex to get wrong,
and is Flatpak's own authoritative running-instance registry rather than
a process-name guess:

```bash
flatpak ps | grep -q com.adamcake.Bolt && echo "Bolt is running" || echo "Bolt is not running"
```

Either way: verify the check actually works (e.g. against a known
running/not-running state) before trusting a negative result and stating
it to the user as fact - a silent regex mistake here previously produced
a confidently wrong answer.

## Bank placeholders report quantity 1, not 0

A placeholder left via "Item > Placeholder" (to keep an item's bank slot
position after withdrawing all of it) is **not** a quantity-0 row in the
underlying `ItemContainer` - it's a genuinely distinct item ID (Jagex gives
every placeholder-eligible item a separate placeholder-variant ID sharing
the base item's display name) reported at quantity **1**. A `quantity > 0`
filter will never catch these.

Detect via `ItemComposition#getPlaceholderTemplateId()`: `-1` for a real
item, the base item's own ID for a placeholder. See `isPlaceholder()` in
`PlayerDataCollector.java`.

## Potion Storage is not an ItemContainer

Unlike bank/inventory/equipment, Potion Storage has no backing
`ItemContainer` at all - it's a handful of varplayers (`VarPlayerID.POTIONSTORE_*`,
one per potion "family") decoded through the same clientscripts
(`ScriptID.POTIONSTORE_DOSES`, `ScriptID.POTIONSTORE_WITHDRAW_DOSES`) and
cache-defined enums (`EnumID.POTIONSTORE_POTIONS`,
`EnumID.POTIONSTORE_UNFINISHED_POTIONS`) the game client itself uses to
render the potion store UI.

Don't reverse-engineer the varp bit-packing by hand - the authoritative
reference is RuneLite core's own Bank Tags plugin,
`runelite-client/src/main/java/net/runelite/client/plugins/banktags/tabs/PotionStorage.java`
(`PotionStorage#rebuildPotions`), fetchable straight from
`github.com/runelite/runelite`. `updatePotionStorage()` in
`PlayerDataCollector.java` is a simplified, read-only port of that logic
(no widget/layout machinery, since this only needs the decoded values, not
a live-updating UI).

## Verifying a Java change before staging it

`javac`/`java` aren't reliably on `PATH` on every dev machine this gets
built on - check `JAVA_HOME`/installed JDKs before assuming a build failure
is code-related. Once building, `./gradlew compileJava` is enough to catch
real errors against the actual RuneLite API jar (fast); only run
`./gradlew shadowJar` once you're ready to actually stage a deploy, per the
atomic-rename rule above.

## `ScriptPreFired` diagnostic logging needs a hard cap and dedup, always

**Incident (2026-09-01):** a debug `onScriptPreFired` subscriber added to
investigate collection log item detection logged unconditionally while a
sticky "interface was opened at some point" flag stayed true - it never
turned back off, and kept matching ambient always-running scripts (health
orb, minimap, etc.) completely unrelated to what was being investigated.
It flooded the live client's log file at roughly 1000+ lines/second,
rotating through several 10MB log files within about two minutes, on a
character actively logged in and playing. No local relaunch or plugin
disable is instant, so this ran for real time before it could be stopped.

**Fix that actually worked:** don't gate on a sticky flag or a widget-group
check (both were tried and both still let ambient noise through or missed
the real event - see below). Deduplicate by `(scriptId, args.toString())`
into a `HashSet` with a hard cap (a few thousand is fine), and only log on
the *first* time a given combination is seen. Ambient/redraw scripts repeat
identical args every tick and collapse to one line each; a genuine
per-item event (varying args, e.g. an item ID) stays visible. This bounds
worst-case volume regardless of how noisy the surrounding client activity
is - treat any temporary `ScriptPreFired`-wide logging as unsafe without
this, full stop.

## Collection log per-item detection: two scripts, two very different meanings

While building collection log tracking, two different clientscript IDs
looked plausible for "this item was just rendered/obtained" and behaved
completely differently:

- **Script 1479** fires once per item, with the item ID as its second
  argument, while browsing a category page (e.g. clicking "Moons of
  Peril") - but for the category's **entire possible item pool**,
  obtained or not. Verified directly: browsing a page showing "Obtained:
  11/13" with two known-missing items (Eclipse moon chestplate, Blue moon
  tassets, confirmed by asking the player which icons were greyed out)
  still fired 1479 for both of those exact item IDs. **Not usable as an
  obtained signal on its own** - it's a data/icon-population pass, not an
  ownership check.
- **Script 4100** (the ID `weirdgloop/WikiSync` - the OSRS Wiki's own
  client plugin - listens for) never fires during normal page browsing on
  this client build at all. It *does* fire, correctly limited to actually-
  owned items only, when triggered by a full collection-log export action
  - concretely, pressing WikiSync's own in-game "sync" button. Verified the
  same way: the known-missing items above were absent from its output.

Net effect: there is no known way for *this* plugin to trigger 4100 on its
own (it depends on WikiSync's own UI), so per-item detection here is
chat-message-only (`onChatMessage`, the "New item added..." line) - see
`PlayerSyncData.CollectionLogData`'s javadoc. If a one-off historical
backfill of existing unlocks is ever wanted again, having this plugin's
listener present while pressing WikiSync's sync button captures it via the
same event bus, no plugin changes required - RuneLite delivers `ScriptPreFired`
to every registered plugin, not just the one that "owns" the script.
