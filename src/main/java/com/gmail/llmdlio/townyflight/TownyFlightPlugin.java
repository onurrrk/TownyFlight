package com.gmail.llmdlio.townyflight;

import com.gmail.llmdlio.townyflight.tasks.TempFlightTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class TownyFlightPlugin extends JavaPlugin {

	private TempFlightTask tempFlightTask;

	@Override
	public void onEnable() {
		tempFlightTask = new TempFlightTask();

		Bukkit.getScheduler().runTaskTimer(this, tempFlightTask, 0L, 10L);
	}
}
