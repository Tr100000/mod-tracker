package io.github.tr100000.modtracker;

import java.io.File;
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
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;

public class ModTracker implements ClientModInitializer {
	public static final String MODID = "modtracker";
	public static final Logger LOGGER = LoggerFactory.getLogger("Mod Tracker");

	public static final Path DATA_PATH = QuiltLoader.getCacheDir().resolve("modtracker.json");
    public static final Path CONFIG_PATH = QuiltLoader.getConfigDir().resolve("modtracker.json");
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final Identifier PACKET_SERVER_MODS = new Identifier(MODID, "info");

	public static Map<String, String> previous;
    public static Map<String, String> previousNames;
    public static Map<String, String> current;
    public static List<ChangedMod> changed;

    public static boolean trackModsFolder = false;
    public static List<String> previousModFiles;
    public static List<String> modFiles;
    public static List<ChangedModFile> changedFiles;

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
        loadCurrentData();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
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

                if (JsonHelper.hasArray(json, "blacklist")) {
                    JsonHelper.getArray(json, "blacklist").forEach(element -> ModFilters.BLACKLIST.add(element.getAsString()));
                }
                if (JsonHelper.hasArray(json, "whitelist")) {
                    JsonHelper.getArray(json, "blacklist").forEach(element -> ModFilters.WHITELIST.add(element.getAsString()));
                }

                trackModsFolder = JsonHelper.getBoolean(json, "trackModsFolder", false);
            }
            else {
                Files.writeString(CONFIG_PATH, "{}");
            }
        }
        catch (Exception e) {
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

            if (trackModsFolder) {
                modFiles = new ArrayList<>();
                File[] fileList = QuiltLoader.getGameDir().resolve("mods").toFile().listFiles();
                for (int i = 0; i < fileList.length; i++) {
                    if (fileList[i].isFile()) {
                        modFiles.add(fileList[i].getName());
                    }
                    else if (fileList[i].isDirectory()) {
                        LOGGER.warn("Mod files in subfolders are not supported by Mod Tracker!");
                    }
                    if (fileList[i].isHidden()) {
                        LOGGER.warn("Found a hidden file ({}) in the mods folder! Do you know about this?", fileList[i].getName());
                    }
                }
            }
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

                if (trackModsFolder && JsonHelper.hasArray(json, "files")) {
                    previousModFiles = readJsonList(json, "files", JsonElement::getAsString);
                }
            }
            else {
                previous = current;
                if (trackModsFolder) {
                    previousModFiles = modFiles;
                }
            }
        }
        catch (Exception e) {
            LOGGER.warn("Failed to load data!", e);
        }
    }

	private static void compareData() {
        if (current != null && previous != null && previousNames != null) {
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

        changedFiles = new ArrayList<>();
        if (trackModsFolder && modFiles != null && previousModFiles != null) {
            modFiles.forEach(file -> {
                if (!previousModFiles.contains(file)) {
                    changedFiles.add(new ChangedModFile(file, ModChangedType.ADD));
                }
            });
            previousModFiles.forEach(file -> {
                if (!modFiles.contains(file)) {
                    changedFiles.add(new ChangedModFile(file, ModChangedType.REMOVE));
                }
            });
        }
    }

    private static void saveCurrentData() {
        try {
            JsonObject json = new JsonObject();
            writeJsonObjectList(json, "mods", current.entrySet(), ModMetadata::toJson);
            if (trackModsFolder) {
                writeJsonElementList(json, "files", modFiles, JsonArray::add);
            }
            Files.writeString(DATA_PATH, GSON.toJson(json));
        }
        catch (IOException e) {
            LOGGER.warn("Failed to save data!", e);
        }
    }

	public static void sendMessageToPlayer(ClientPlayerEntity player) {
        if ((trackModsFolder && changed.isEmpty() && changedFiles.isEmpty()) || (!trackModsFolder && changed.isEmpty())) {
            player.sendSystemMessage(Text.literal("No changed mods since last session."));
        }
        else {
            player.sendSystemMessage(Text.literal("Changed mods since last session:"));
            changed.forEach(changed -> player.sendSystemMessage(changed.toText()));

            if (trackModsFolder) {
                player.sendSystemMessage(Text.literal("Changed files since last session:"));
                changedFiles.forEach(changed -> player.sendSystemMessage(changed.toText()));
            }
        }
    }


	public static <T> List<T> readJsonList(JsonObject json, String element, Function<JsonElement, T> reader) {
		return jsonArrayToList(JsonHelper.getArray(json, element)).stream().map(reader).collect(Collectors.toList());
	}

	public static <T> void writeJsonObjectList(JsonObject json, String element, Collection<T> list, BiConsumer<JsonObject, T> writer) {
        writeJsonElementList(json, element, list, item -> {
            JsonObject jsonObject = new JsonObject();
            writer.accept(jsonObject, item);
            return jsonObject;
        });
	}

    public static <T> void writeJsonElementList(JsonObject json, String element, Collection<T> list, Function<T, JsonElement> writer) {
        JsonArray jsonArray = new JsonArray(list.size());
        list.forEach(item -> {
            jsonArray.add(writer.apply(item));
        });
        json.add(element, jsonArray);
    }

    public static <T> void writeJsonElementList(JsonObject json, String element, Collection<T> list, BiConsumer<JsonArray, T> writer) {
        JsonArray jsonArray = new JsonArray(list.size());
        list.forEach(item -> {
            writer.accept(jsonArray, item);
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

    public static class ChangedModFile {
        public final String file;
        public final ModChangedType type;

        public ChangedModFile(String file, ModChangedType type) {
            this.file = file;
            this.type = type;
        }

        public Text toText() {
            switch (type) {
                case ADD:
                    return Text.literal("  (+)").formatted(Formatting.DARK_GREEN).append(file);
                case REMOVE:
                    return Text.literal("  (-)").formatted(Formatting.RED).append(file);
                default:
                    return null;
            }
        }
    }

    public enum ModChangedType {
        ADD,
        REMOVE,
        UPDATE
    }
}
