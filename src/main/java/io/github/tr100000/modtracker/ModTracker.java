package io.github.tr100000.modtracker;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModTracker implements ModInitializer {
	public static final String MODID = "modtracker";
	public static final Logger LOGGER = LoggerFactory.getLogger("Mod Tracker");

	@Override
	public void onInitialize(ModContainer mod) {
		LOGGER.info("Hello Quilt world from {}!", mod.metadata().name());
	}
}
