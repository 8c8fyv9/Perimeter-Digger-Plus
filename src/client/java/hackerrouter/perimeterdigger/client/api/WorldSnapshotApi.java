package hackerrouter.perimeterdigger.client.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntFunction;

/**
 * Optional read-only client chunk/entity snapshot API (inspired by world-snapshot-exporter).
 * Always runs on the Minecraft client thread and only reads already-received client chunks.
 */
public final class WorldSnapshotApi {
    private static final Field STORAGE = findField(ClientChunkCache.class, "storage");
    private static final Field CHUNKS = findField(STORAGE.getType(), "chunks");

    private record Palette<T>(List<T> palette, int[] indices) {
        static <T> Palette<T> encode(int count, IntFunction<T> values) {
            Map<T, Integer> ids = new LinkedHashMap<>();
            int[] indices = new int[count];
            for (int i = 0; i < count; i++) {
                indices[i] = ids.computeIfAbsent(values.apply(i), key -> ids.size());
            }
            return new Palette<>(List.copyOf(ids.keySet()), indices);
        }
    }

    private static Field findField(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final boolean enabled;

    public WorldSnapshotApi(boolean enabled) {
        this.enabled = enabled;
    }

    public Object route(String method, String path, JsonObject query) {
        if (!enabled) {
            throw new IllegalStateException("WORLD_SNAPSHOT_DISABLED");
        }
        if (!"GET".equals(method)) {
            throw new IllegalArgumentException("Unsupported route or method");
        }
        return switch (path) {
            case "/v1/world" -> world();
            case "/v1/chunks" -> chunksSnapshot(query);
            case "/v1/chunk" -> singleChunkSnapshot(query);
            case "/v1/entities" -> Map.of("world", world(), "entities", entities(isTruthy(query, "items")));
            default -> throw new IllegalArgumentException("Unsupported route or method");
        };
    }

    private static ClientLevel requireLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("NO_WORLD");
        }
        return level;
    }

    private static List<LevelChunk> loadedChunks(ClientLevel level) {
        AtomicReferenceArray<?> slots;
        try {
            slots = (AtomicReferenceArray<?>) CHUNKS.get(STORAGE.get(level.getChunkSource()));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        List<LevelChunk> result = new ArrayList<>();
        for (int i = 0; i < slots.length(); i++) {
            LevelChunk chunk = (LevelChunk) slots.get(i);
            if (chunk != null) {
                result.add(chunk);
            }
        }
        result.sort(Comparator.comparingInt((LevelChunk c) -> c.getPos().x())
                .thenComparingInt(c -> c.getPos().z()));
        return result;
    }

    private static Map<String, Object> world() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Map.of("connected", false);
        }
        ClientLevel level = mc.level;
        String identity = mc.getSingleplayerServer() != null
                ? mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString()
                : mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", true);
        out.put("world", identity);
        out.put("dimension", level.dimension().identifier().toString());
        out.put("gameTick", level.getGameTime());
        out.put("minY", level.getMinY());
        out.put("maxY", level.getMaxY());
        if (mc.player != null) {
            out.put("playerPosition", List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
            out.put("playerChunk", List.of(mc.player.chunkPosition().x(), mc.player.chunkPosition().z()));
        }
        out.put("scope", "client_received_data_only");
        return out;
    }

    private static Map<String, Object> chunksSnapshot(JsonObject query) {
        ClientLevel level = requireLevel();
        Minecraft mc = Minecraft.getInstance();
        Integer radius = optionalInt(query, "radius");
        Integer centerCx = mc.player != null ? mc.player.chunkPosition().x() : null;
        Integer centerCz = mc.player != null ? mc.player.chunkPosition().z() : null;
        List<List<Integer>> coords = new ArrayList<>();
        for (LevelChunk chunk : loadedChunks(level)) {
            int cx = chunk.getPos().x();
            int cz = chunk.getPos().z();
            if (radius != null && radius >= 0 && centerCx != null && centerCz != null) {
                if (Math.max(Math.abs(cx - centerCx), Math.abs(cz - centerCz)) > radius) {
                    continue;
                }
            }
            coords.add(List.of(cx, cz));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("world", world());
        out.put("chunks", coords);
        out.put("count", coords.size());
        return out;
    }

    private static Map<String, Object> singleChunkSnapshot(JsonObject query) {
        ClientLevel level = requireLevel();
        int cx = requireInt(query, "cx");
        int cz = requireInt(query, "cz");
        LevelChunk target = null;
        for (LevelChunk chunk : loadedChunks(level)) {
            if (chunk.getPos().x() == cx && chunk.getPos().z() == cz) {
                target = chunk;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("CHUNK_NOT_LOADED");
        }
        return serializeChunk(target, level);
    }

    private static Map<String, Object> serializeChunk(LevelChunk chunk, ClientLevel level) {
        List<Object> sections = new ArrayList<>();
        RegistryOps<JsonElement> registryOps = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var source = chunk.getSections();
        for (int s = 0; s < source.length; s++) {
            var section = source[s];
            Palette<BlockState> blocks = Palette.encode(4096, i -> section.getBlockState(i & 15, i >> 8, (i >> 4) & 15));
            List<JsonElement> palette = blocks.palette().stream()
                    .map(state -> BlockState.CODEC.encodeStart(registryOps, state).getOrThrow())
                    .toList();
            Palette<String> biomes = Palette.encode(64,
                    i -> section.getNoiseBiome(i & 3, i >> 4, (i >> 2) & 3).unwrapKey().orElseThrow().identifier().toString());
            sections.add(Map.of(
                    "sectionY", level.getMinSectionY() + s,
                    "palette", palette,
                    "blocks", blocks.indices(),
                    "biomes", biomes
            ));
        }
        List<Object> blockEntities = new ArrayList<>();
        for (var entity : chunk.getBlockEntities().values()) {
            var pos = entity.getBlockPos();
            blockEntities.add(Map.of(
                    "position", List.of(pos.getX(), pos.getY(), pos.getZ()),
                    "type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString(),
                    "clientDataSnbt", entity.saveWithFullMetadata(level.registryAccess()).toString(),
                    "inventoryAuthoritative", false
            ));
        }
        return Map.of(
                "x", chunk.getPos().x(),
                "z", chunk.getPos().z(),
                "capturedTick", level.getGameTime(),
                "sections", sections,
                "blockEntities", blockEntities
        );
    }

    private static JsonElement encodeItem(ItemStack stack, ClientLevel level) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()), stack).getOrThrow();
    }

    private static List<Map<String, Object>> entities(boolean itemsOnly) {
        ClientLevel level = requireLevel();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entity : level.entitiesForRendering()) {
            if ((entity instanceof ItemEntity) != itemsOnly) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", entity.getUUID().toString());
            row.put("id", entity.getId());
            row.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            row.put("name", entity.getName().getString());
            row.put("dimension", level.dimension().identifier().toString());
            row.put("position", List.of(entity.getX(), entity.getY(), entity.getZ()));
            var v = entity.getDeltaMovement();
            row.put("velocity", List.of(v.x, v.y, v.z));
            row.put("yaw", entity.getYRot());
            row.put("pitch", entity.getXRot());
            row.put("onGround", entity.onGround());
            var box = entity.getBoundingBox();
            row.put("bounds", List.of(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
            if (entity instanceof LivingEntity living) {
                row.put("health", living.getHealth());
                Map<String, JsonElement> equipment = new LinkedHashMap<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    equipment.put(slot.getName(), encodeItem(living.getItemBySlot(slot), level));
                }
                row.put("equipment", equipment);
            }
            if (entity instanceof ItemEntity drop) {
                row.put("item", encodeItem(drop.getItem(), level));
            }
            result.add(row);
        }
        return result;
    }

    private static Integer optionalInt(JsonObject query, String key) {
        if (query == null || !query.has(key)) return null;
        try {
            return Integer.parseInt(query.get(key).getAsString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid integer parameter: " + key);
        }
    }

    private static int requireInt(JsonObject query, String key) {
        Integer value = optionalInt(query, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing parameter: " + key);
        }
        return value;
    }

    private static boolean isTruthy(JsonObject query, String key) {
        return query != null && query.has(key) && "true".equalsIgnoreCase(query.get(key).getAsString());
    }
}
