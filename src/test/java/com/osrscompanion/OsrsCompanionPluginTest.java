package com.osrscompanion;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import java.util.Arrays;

public class OsrsCompanionPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsCompanionPlugin.class);

		// Launchers built around net.runelite.launcher.Launcher pass JVM
		// options through as "-J<opt>" application arguments and translate
		// them into real JVM flags before invoking the client. We call
		// RuneLite.main() directly, skipping that launcher entirely, so any
		// "-J..." args a launcher still injects (e.g. Bolt hardcodes some
		// for its custom-jar launch path) never get translated and just
		// crash RuneLite's own arg parser. Actual JVM flags for this
		// process must be set another way (JAVA_TOOL_OPTIONS, launch
		// script, etc.) - drop these rather than pass them through.
		String[] filtered = Arrays.stream(args)
			.filter(arg -> !arg.startsWith("-J"))
			.toArray(String[]::new);

		RuneLite.main(filtered);
	}
}
