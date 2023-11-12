package io.github.tr100000.modtracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer;
import org.quiltmc.qsl.command.api.client.ClientCommandRegistrationCallback;
import org.quiltmc.qsl.networking.api.client.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.JsonHelper;

public class ModTracker implements ClientModInitializer {
	public static final String MODID = "modtracker";
	public static final Logger LOGGER = LoggerFactory.getLogger("Mod Tracker");

	public static final Path DATA_PATH = QuiltLoader.getCacheDir().resolve("modtracker.json");
    public static final Path CONFIG_PATH = QuiltLoader.getConfigDir().resolve("modtracker.json");
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static Map<String, String> previous;
    public static Map<String, String> previousNames;
    public static Map<String, String> current;
    public static List<ChangedMod> changed;

	@Override
	public void onInitializeClient(ModContainer mod) {
        ClientCommandRegistrationCallback.EVENT.register(ModTrackerCommand::register);

        ModFilters.BLACKLIST.add("java");
        ModFilters.BLACKLIST.add("minecraft");
        ModFilters.BLACKLIST.add("quilt.+");
        ModFilters.WHITELIST.add("quilt_loader");
        ModFilters.WHITELIST.add("quilt_base");
        ModFilters.WHITELIST.add("quilted_fabric_api");

        loadConfig();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            loadCurrentData();
			loadPreviousData();
			compareData();
			sendMessageToPlayer(client.player);
			saveCurrentData();
		});
	}

    private static void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                JsonObject json = GSON.fromJson(Files.readString(CONFIG_PATH), JsonObject.class);
                
                if (json.has("blacklist")) {
                    json.get("blacklist").getAsJsonArray().forEach(element -> ModFilters.BLACKLIST.add(element.getAsString()));
                }
                if (json.has("whitelist")) {
                    json.get("whitelist").getAsJsonArray().forEach(element -> ModFilters.WHITELIST.add(element.getAsString()));
                }
            }
        }
        catch (IOException e) {
            LOGGER.warn("Failed to load config!", e);
        }
    }

	private static void loadCurrentData() {
        try {
            ModFilters.compilePatterns();
            current = new HashMap<>(QuiltLoader.getAllMods().size());
            QuiltLoader.getAllMods().forEach(mod -> {
                if (ModFilters.filter(mod.metadata().id())) {
                    current.put(mod.metadata().id(), mod.metadata().version().raw());
                }
            });
        }
        finally {
            ModFilters.clearCompiledPatterns();
        }
    }

	private static void loadPreviousData() {
        try {
            if (Files.exists(DATA_PATH)) {
                JsonObject json = GSON.fromJson(Files.readString(DATA_PATH), JsonObject.class);
                List<ModMetadata> previousData = readJsonList(json, "mods", ModMetadata::fromJson);
                previous = new HashMap<>(previousData.size());
                previousNames = new HashMap<>(previousData.size());
                previousData.forEach(mod -> {
                    previous.put(mod.id, mod.version);
                    previousNames.put(mod.id, mod.name);
                });
            }
            else {
                previous = current;
            }
        }
        catch (IOException e) {
            LOGGER.warn("Failed to load data!", e);
        }
    }

	private static void compareData() {
        changed = new ArrayList<>();
        current.forEach((id, version) -> {
            if (previous.containsKey(id)) {
                if (!previous.get(id).equals(version)) {
                    changed.add(new UpdatedMod(id, previous.get(id), version));
                }
            }
            else {
                changed.add(new ChangedMod(id, ModChangedType.ADD));
            }
        });
        previous.forEach((id, version) -> {
            if (!current.containsKey(id)) {
                changed.add(new ChangedMod(id, ModChangedType.REMOVE));
            }
        });
    }

    private static void saveCurrentData() {
        try {
            JsonObject json = new JsonObject();
            writeJsonList(json, "mods", current.entrySet(), ModMetadata::toJson);
            Files.writeString(DATA_PATH, GSON.toJson(json));
        }
        catch (IOException e) {
            LOGGER.warn("Failed to save data!", e);
        }
    }

	public static void sendMessageToPlayer(ClientPlayerEntity player) {
        if (changed.isEmpty()) {
            player.sendSystemMessage(Text.literal("No changed mods since last session."));
        }
        else {
            player.sendSystemMessage(Text.literal("Changed mods since last session:"));
            changed.forEach(changed -> {
                player.sendSystemMessage(changed.toText());
            });
        }
    }


	public static <T> List<T> readJsonList(JsonObject json, String element, Function<JsonElement, T> reader) {
		return jsonArrayToList(JsonHelper.getArray(json, element)).stream().map(reader).collect(Collectors.toList());
	}

	public static <T> void writeJsonList(JsonObject json, String element, Collection<T> list, BiConsumer<JsonObject, T> writer) {
		JsonArray jsonArray = new JsonArray(list.size());
		list.forEach(item -> {
			JsonObject jsonObject = new JsonObject();
			writer.accept(jsonObject, item);
			jsonArray.add(jsonObject);
		});
		json.add(element, jsonArray);
	}

	public static List<JsonObject> jsonArrayToList(JsonArray array) {
		List<JsonObject> list = new ArrayList<>(array.size());
		array.forEach(element -> list.add(element.getAsJsonObject()));
		return list;
	}


	public static class ModMetadata {
        public final String id;
        public final String name;
        public final String version;

        public ModMetadata(String id, String name, String version) {
            if (name.isBlank()) {
				name = QuiltLoader.getModContainer(id).map(mod -> mod.metadata().name()).orElse(id);
            }

            this.id = id;
            this.name = name;
            this.version = version;
        }

        public static ModMetadata fromJson(JsonElement element) {
            JsonObject json = element.getAsJsonObject();
            return new ModMetadata(JsonHelper.getString(json, "id"), JsonHelper.getString(json, "name", ""), JsonHelper.getString(json, "version", "?"));
        }

        public static void toJson(JsonObject json, Map.Entry<String, String> entry) {
            json.addProperty("id", entry.getKey());
            json.addProperty("name", QuiltLoader.getModContainer(entry.getKey()).orElseThrow().metadata().name());
            json.addProperty("version", entry.getValue());
        }
    }

	public static class ChangedMod {
        public final String id;
        public final ModChangedType type;

        public ChangedMod(String id, ModChangedType type) {
            this.id = id;
            this.type = type;
        }

        public Text toText() {
            switch (type) {
                case ADD:
                    return Text.literal("  (+) ").formatted(Formatting.DARK_GREEN).append(Text.literal(getModName()));
                case REMOVE:
                    return Text.literal("  (-) ").formatted(Formatting.RED).append(Text.literal(getModName()));
                default:
                    return null;
            }
        }

        protected String getModName() {
			return QuiltLoader.getModContainer(id).map(mod -> mod.metadata().name()).orElse(previousNames.getOrDefault(id, id));
        }
    }

    public static class UpdatedMod extends ChangedMod {
        public final String previousVersion;
        public final String newVersion;

        public UpdatedMod(String id, String previousVersion, String newVersion) {
            super(id, ModChangedType.UPDATE);
            this.previousVersion = previousVersion;
            this.newVersion = newVersion;
        }

        @Override
        public Text toText() {
            return Text.literal("  (*) ").formatted(Formatting.GOLD).append(Text.literal(getModName() + " (" + previousVersion + " -> " + newVersion + ")"));
        }
    }

    public enum ModChangedType {
        ADD,
        REMOVE,
        UPDATE
    }
}
