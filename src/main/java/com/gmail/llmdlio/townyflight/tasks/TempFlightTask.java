package com.gmail.llmdlio.townyflight.tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.gmail.llmdlio.townyflight.TownyFlightAPI;
import com.gmail.llmdlio.townyflight.util.Message;
import com.gmail.llmdlio.townyflight.util.MetaData;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;

public class TempFlightTask implements Runnable {

	private static Map<UUID, Long> playerUUIDSecondsMap = new ConcurrentHashMap<>();
	private int cycles = 0;

	@Override
	public void run() {
		cycles++;

		removeFlightFromPlayersWithNoTimeLeft();

		Set<UUID> uuidsToDecrement = new HashSet<>();
		for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
			if (!player.getAllowFlight() || !player.isFlying())
				continue;
			UUID uuid = player.getUniqueId();
			if (!playerUUIDSecondsMap.containsKey(uuid))
				continue;
			uuidsToDecrement.add(uuid);
		}

		uuidsToDecrement.forEach(uuid -> decrementSeconds(uuid));
		if (cycles % 10 == 0)
			cycles = 0;
	}

	private void decrementSeconds(UUID uuid) {
		long seconds = playerUUIDSecondsMap.get(uuid);
		playerUUIDSecondsMap.put(uuid, --seconds);
		// Save players every 10 seconds;
		if (cycles % 10 == 0) {
			Resident resident = TownyAPI.getInstance().getResident(uuid);
			if (resident == null)
				return;
			MetaData.setSeconds(resident, seconds, true);
		}
	}

	private void removeFlightFromPlayersWithNoTimeLeft() {
		Set<UUID> uuidsToRemove = playerUUIDSecondsMap.entrySet().stream()
				.filter(e -> e.getValue() <= 0)
				.map(e -> e.getKey())
				.collect(Collectors.toSet());
		uuidsToRemove.forEach(uuid -> {
			removeFlight(uuid);
			Player player = Bukkit.getPlayer(uuid);
			if (player != null && player.isOnline())
				Message.of(String.format(Message.getLangString("yourTempFlightHasExpired"))).to(player);
		});
	}

	private void removeFlight(UUID uuid) {
		playerUUIDSecondsMap.remove(uuid);
		Player player = Bukkit.getPlayer(uuid);
		if (player != null && player.isOnline())
			TownyFlightAPI.getInstance().removeFlight(player, false, true, "time");
		MetaData.removeFlightMeta(uuid);
	}

	public static long getSeconds(UUID uuid) {
		return playerUUIDSecondsMap.containsKey(uuid) ? playerUUIDSecondsMap.get(uuid) : 0L;
	}

	public static void addPlayerTempFlightSeconds(UUID uuid, long seconds) {
		long existingSeconds = playerUUIDSecondsMap.containsKey(uuid) ? playerUUIDSecondsMap.get(uuid) : 0L;
		playerUUIDSecondsMap.put(uuid, existingSeconds + seconds);
	}
package com.gmail.llmdlio.townyflight.tasks;

import com.gmail.llmdlio.townyflight.TownyFlight;
import com.gmail.llmdlio.townyflight.TownyFlightAPI;
import com.gmail.llmdlio.townyflight.util.MetaData;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TempFlightTask implements Runnable {

	private static final Map<UUID, Long> playerUUIDSecondsMap = new ConcurrentHashMap<>();
	private static final Map<UUID, Integer> warningPlayers = new ConcurrentHashMap<>();
	private int cycles = 0;

	@Override
	public void run() {
		cycles++;

		removeFlightFromPlayersWithNoTimeLeft();
		decrementTempFlightTimeForFlyingPlayers();

		if (cycles % 20 == 0) {
			saveAllPlayersMetaData();
			cycles = 0;
		}

		checkPlayerLocations();
	}

	private void checkPlayerLocations() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			UUID uuid = player.getUniqueId();

			if (!player.isFlying() || !player.getAllowFlight() || player.hasPermission("townyflight.bypass") || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
				warningPlayers.remove(uuid);
				continue;
			}

			if (isPlayerInAllowedZone(player)) {
				if (warningPlayers.containsKey(uuid)) {
					warningPlayers.remove(uuid);
				}
			} else {
				if (warningPlayers.containsKey(uuid)) {
					int timeLeft = warningPlayers.get(uuid) - 1;
					if (timeLeft <= 0) {
						warningPlayers.remove(uuid);
						runOnPlayerThread(player, () -> {
							TownyFlightAPI.getInstance().removeFlight(player, false, false, "Region Exit");
						});
					} else {
						warningPlayers.put(uuid, timeLeft);
					}
				} else {
					warningPlayers.put(uuid, 4);
					player.sendMessage(formatMessage("&6[Towny] &8[&aTowny Uçuş&8] uçuş bölgesine dönmek için &62 &8saniyeniz var."));
				}
			}
		}
	}

	private boolean isPlayerInAllowedZone(Player player) {
		TownyAPI api = TownyAPI.getInstance();
		TownBlock townBlock = api.getTownBlock(player.getLocation());

		if (townBlock == null || !townBlock.hasTown()) return false;

		try {
			Resident resident = api.getResident(player.getUniqueId());
			if (resident == null || !resident.hasTown()) return false;

			if (townBlock.getTown().equals(resident.getTown())) {
				return true;
			}

			if (resident.hasNation()) {
				Town nationCapital = resident.getNation().getCapital();
				if (townBlock.getTown().equals(nationCapital)) {
					return true;
				}
			}
		} catch (TownyException e) {
			return false;
		}

		return false;
	}

	private void decrementTempFlightTimeForFlyingPlayers() {
		if (cycles % 2 != 0) return;

		Set<UUID> uuidsToDecrement = new HashSet<>();
		for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
			if (!player.getAllowFlight() || !player.isFlying()) continue;
			if (playerUUIDSecondsMap.containsKey(player.getUniqueId())) {
				uuidsToDecrement.add(player.getUniqueId());
			}
		}
		uuidsToDecrement.forEach(this::decrementSeconds);
	}

	private void saveAllPlayersMetaData() {
		playerUUIDSecondsMap.forEach((uuid, seconds) -> {
			Resident resident = TownyAPI.getInstance().getResident(uuid);
			if (resident != null) {
				MetaData.setSeconds(resident, seconds, true);
			}
		});
	}

	private void decrementSeconds(UUID uuid) {
		long seconds = playerUUIDSecondsMap.get(uuid);
		if (seconds > 0) {
			playerUUIDSecondsMap.put(uuid, --seconds);
		}
	}

	private void removeFlightFromPlayersWithNoTimeLeft() {
		Set<UUID> uuidsToRemove = playerUUIDSecondsMap.entrySet().stream()
				.filter(e -> e.getValue() <= 0)
				.map(Map.Entry::getKey)
				.collect(Collectors.toSet());

		uuidsToRemove.forEach(uuid -> {
			Player player = Bukkit.getPlayer(uuid);
			if (player != null && player.isOnline()) {
				removeFlight(player);
				player.sendMessage(formatMessage("&8[&aTowny Uçuş&8] &eSüreli uçuş hakkınız sona erdi."));
			} else {
				playerUUIDSecondsMap.remove(uuid);
				MetaData.removeFlightMeta(uuid);
			}
		});
	}

	private void removeFlight(Player player) {
		playerUUIDSecondsMap.remove(player.getUniqueId());
		TownyFlightAPI.getInstance().removeFlight(player, false, true, "time");
		MetaData.removeFlightMeta(player.getUniqueId());
	}

	public static long getSeconds(UUID uuid) {
		return playerUUIDSecondsMap.getOrDefault(uuid, 0L);
	}

	public static void addPlayerTempFlightSeconds(UUID uuid, long seconds) {
		long existingSeconds = getSeconds(uuid);
		playerUUIDSecondsMap.put(uuid, existingSeconds + seconds);
	}

	public static void removeAllPlayerTempFlightSeconds(UUID uuid) {
		playerUUIDSecondsMap.put(uuid, 0L);
	}

	public static void logOutPlayerWithRemainingTempFlight(Player player) {
		UUID uuid = player.getUniqueId();
		if (!playerUUIDSecondsMap.containsKey(uuid)) return;
		long seconds = getSeconds(uuid);
		if (seconds <= 0L) return;

		Resident resident = TownyAPI.getInstance().getResident(player);
		if (resident != null) {
			MetaData.setSeconds(resident, seconds, true);
		}
		playerUUIDSecondsMap.remove(uuid);
	}

	private String formatMessage(String message) {
		return ChatColor.translateAlternateColorCodes('&', message);
	}

	private void runOnPlayerThread(Player player, Runnable runnable) {
		if (player.isOnline()) {
			player.getScheduler().run(TownyFlight.getPlugin(), (task) -> runnable.run(), null);
		}
	}
}
	public static void removeAllPlayerTempFlightSeconds(UUID uuid) {
		playerUUIDSecondsMap.put(uuid, 0L);
	}

	public static void logOutPlayerWithRemainingTempFlight(Player player) {
		if (!playerUUIDSecondsMap.containsKey(player.getUniqueId()))
			return;
		long seconds = playerUUIDSecondsMap.get(player.getUniqueId());
		if (seconds <= 0L)
			return;
		Resident resident = TownyAPI.getInstance().getResident(player);
		if (resident == null)
			return;
		MetaData.setSeconds(resident, seconds, true);
		playerUUIDSecondsMap.remove(player.getUniqueId());
	}
}
