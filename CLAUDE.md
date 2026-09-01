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
