package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.model.PlayerSyncData;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Pushes player data as JSON to a remote ingest endpoint over HTTP,
 * authenticated with a bearer token from the plugin config.
 */
@Slf4j
public class PlayerDataWriter
{
	private final Gson gson;
	private final OsrsCompanionConfig config;
	private final HttpClient httpClient;

	public PlayerDataWriter(Gson gson, OsrsCompanionConfig config)
	{
		this.gson = gson;
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	/**
	 * POST player data to the configured ingest endpoint. Blocking - callers
	 * should invoke this off the client thread.
	 *
	 * @return true if the endpoint accepted the snapshot (2xx), false otherwise
	 */
	public boolean write(PlayerSyncData data)
	{
		if (data.player == null || data.player.username == null)
		{
			log.debug("OSRS Companion: No player data available, skipping send");
			return false;
		}

		String url = config.ingestUrl();
		if (url == null || url.isBlank())
		{
			log.warn("OSRS Companion: No ingest URL configured, skipping send");
			return false;
		}

		String token = config.ingestToken();
		String json = gson.toJson(data);

		try
		{
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + (token == null ? "" : token))
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 == 2)
			{
				log.debug("OSRS Companion: Sent data for {} to ingest endpoint", data.player.username);
				return true;
			}

			log.warn("OSRS Companion: Ingest endpoint returned {} for {}", response.statusCode(), data.player.username);
			return false;
		}
		catch (Exception e)
		{
			log.warn("OSRS Companion: Failed to send data to ingest endpoint", e);
			return false;
		}
	}
}
