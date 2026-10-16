package dev.mapframes;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.mapframes.BindingStore.Binding;
import dev.mapframes.xaero.XaeroBridge;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Client-only overlay; commands never send map pixels, interactions, or commands to the server. */
public final class MapFramesClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("map-art-optimizer");
    private static final long MAX_TEXTURE_PIXELS = 16L * 1024 * 1024; // 64 MiB GPU + 64 MiB native pixels.
    private static final Map<Integer, TextureEntry> TEXTURES = new HashMap<>();
    private static BindingStore bindings;
    private static String activeWorld;
    private static boolean enabled = true;
    private static long tick;
    private static long nextCaptureTick;

    @Override
    public void onInitializeClient() {
        try {
            // Keep the original filename so existing map bindings survive the rename.
            bindings = new BindingStore(FabricLoader.getInstance().getConfigDir().resolve("xaero-map-frames.json"));
        } catch (IOException error) {
            LOGGER.error("Map Art Optimizer is disabled because its bindings could not be read; the file is preserved", error);
        }
        ClientTickEvents.END_CLIENT_TICK.register(MapFramesClient::endTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> releaseTextures(client));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                literal("xmapframe")
                        .executes(context -> help(context.getSource()))
                        .then(literal("help").executes(context -> help(context.getSource())))
                        .then(literal("bind").then(argument("centerX", IntegerArgumentType.integer(-29_999_000, 29_999_000))
                                .then(argument("centerZ", IntegerArgumentType.integer(-29_999_000, 29_999_000))
                                        .executes(context -> bind(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "centerX"),
                                                IntegerArgumentType.getInteger(context, "centerZ"), false)))))
                        .then(literal("corner").then(argument("minX", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                .then(argument("minZ", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                                        .executes(context -> bind(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "minX"),
                                                IntegerArgumentType.getInteger(context, "minZ"), true)))))
                        .then(literal("here").executes(context -> bindHere(context.getSource())))
                        .then(literal("unbind").executes(context -> unbind(context.getSource())))
                        .then(literal("status").executes(context -> status(context.getSource())))
                        .then(literal("refresh").executes(context -> {
                            releaseTextures(context.getSource().getClient());
                            XaeroBridge.clear();
                            nextCaptureTick = 0;
                            return message(context.getSource(), "已清理高清显示缓存，正在重新读取 Xaero 地表地图。");
                        }))
                        .then(literal("on").executes(context -> {
                            enabled = true;
                            return message(context.getSource(), "高清展示框地图已开启。");
                        }))
                        .then(literal("off").executes(context -> {
                            enabled = false;
                            return message(context.getSource(), "高清覆盖已关闭，展示原版地图。");
                        }))));
        LOGGER.info("Map Art Optimizer initialized for Minecraft 26.1.2 / Xaero 1.41.3");
    }

    /** Invoked while extracting map state. Returns null until a bound map has an actual HD texture. */
    public static Identifier textureFor(MapId id, MapItemSavedData data) {
        if (!enabled || bindings == null || activeWorld == null || id == null || data == null) return null;
        Binding binding = bindings.find(activeWorld, id.id());
        if (binding == null || binding.size() != (128 << data.scale)) return null;
        TextureEntry entry = TEXTURES.computeIfAbsent(id.id(), ignored -> new TextureEntry(binding));
        entry.lastSeen = tick;
        entry.mapData = data;
        return entry.texture == null ? null : entry.identifier;
    }

    private static void endTick(Minecraft client) {
        tick++;
        String world = client.level == null ? null : XaeroBridge.worldKey();
        if (!Objects.equals(world, activeWorld)) {
            releaseTextures(client);
            activeWorld = world;
            nextCaptureTick = 0;
            if (world == null) XaeroBridge.clear();
        }
        if (!enabled || world == null || bindings == null) return;

        TEXTURES.entrySet().removeIf(item -> {
            if (tick - item.getValue().lastSeen <= 1200) return false;
            release(client, item.getValue());
            return true;
        });
        if (tick < nextCaptureTick) return;
        TextureEntry selected = null;
        for (TextureEntry entry : TEXTURES.values()) {
            if (entry.mapData != null && tick - entry.lastSeen <= 40 && tick >= entry.nextRefresh
                    && (selected == null || entry.nextRefresh < selected.nextRefresh)) selected = entry;
        }
        if (selected == null) return;
        // At most one visible map per second; each individual map refreshes at most every five seconds.
        nextCaptureTick = tick + 20;
        selected.nextRefresh = tick + 100;
        try {
            updateTexture(client, selected);
        } catch (RuntimeException | LinkageError error) {
            selected.nextRefresh = tick + 600;
            LOGGER.warn("Could not refresh HD map {}; keeping its previous image", selected.binding.mapId(), error);
        }
    }

    private static void updateTexture(Minecraft client, TextureEntry entry) {
        Binding binding = entry.binding;
        XaeroBridge.Snapshot snapshot = XaeroBridge.capture(binding.minX(), binding.minZ(), binding.size());
        entry.knownPixels = snapshot.knownPixels();
        if (snapshot.knownPixels() < (long) binding.size() * binding.size()) entry.nextRefresh = tick + 20;
        if (snapshot.knownPixels() == 0) return;
        int size = snapshot.size();
        int[] pixels = snapshot.argb();
        int[] fallback = new int[128 * 128];
        for (int index = 0; index < fallback.length; index++) {
            fallback[index] = MapColor.getColorFromPackedId(Byte.toUnsignedInt(entry.mapData.colors[index]));
        }
        int scale = size / 128;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int index = z * size + x;
                if ((pixels[index] >>> 24) == 0) pixels[index] = fallback[(z / scale) * 128 + x / scale];
            }
        }
        int hash = Arrays.hashCode(pixels);
        if (entry.texture != null && hash == entry.lastHash) return;
        if (entry.texture == null) {
            makeRoom(client, (long) size * size, entry);
            NativeImage image = new NativeImage(size, size, false);
            try {
                fillImage(image, pixels, size);
                DynamicTexture texture = new DynamicTexture(() -> "Map Art Optimizer map " + binding.mapId(), image);
                try {
                    client.getTextureManager().register(entry.identifier, texture);
                    entry.texture = texture;
                } catch (RuntimeException | Error error) {
                    texture.close();
                    throw error;
                }
            } catch (RuntimeException | Error error) {
                if (!image.isClosed()) image.close();
                throw error;
            }
        } else {
            fillImage(entry.texture.getPixels(), pixels, size);
            entry.texture.upload();
        }
        entry.lastHash = hash;
    }

    private static void fillImage(NativeImage image, int[] pixels, int size) {
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) image.setPixel(x, z, pixels[z * size + x]);
        }
    }

    private static void makeRoom(Minecraft client, long additional, TextureEntry protectedEntry) {
        while (texturePixels() + additional > MAX_TEXTURE_PIXELS) {
            TextureEntry oldest = null;
            for (TextureEntry entry : TEXTURES.values()) {
                if (entry != protectedEntry && entry.texture != null && (oldest == null || entry.lastSeen < oldest.lastSeen)) oldest = entry;
            }
            if (oldest == null) return;
            release(client, oldest);
            // Avoid immediately allocating an evicted texture again while still over budget.
            oldest.nextRefresh = tick + 200;
        }
    }

    private static long texturePixels() {
        long pixels = 0;
        for (TextureEntry entry : TEXTURES.values()) {
            if (entry.texture != null) pixels += (long) entry.binding.size() * entry.binding.size();
        }
        return pixels;
    }

    private static void releaseTextures(Minecraft client) {
        for (TextureEntry entry : TEXTURES.values()) release(client, entry);
        TEXTURES.clear();
    }

    private static void release(Minecraft client, TextureEntry entry) {
        if (entry.texture != null) client.getTextureManager().release(entry.identifier);
        entry.texture = null;
    }

    private static int bind(FabricClientCommandSource source, int x, int z, boolean corner) {
        Target target = target(source.getClient());
        if (target == null) return error(source, "请对准装有地图的展示框，或把地图拿在手上。");
        if (activeWorld == null || bindings == null) return error(source, "Xaero 地图尚未就绪，或本地绑定配置无法读取。请先按 M 选择正确的地图实例。");
        int size = 128 << target.data.scale;
        Binding binding = new Binding(activeWorld, target.id.id(), corner ? x : x - size / 2, corner ? z : z - size / 2, size);
        if (!binding.valid()) return error(source, "地图范围超出了支持的世界坐标。");
        try {
            bindings.put(binding);
            TextureEntry previous = TEXTURES.remove(target.id.id());
            if (previous != null) release(source.getClient(), previous);
            nextCaptureTick = 0;
            return message(source, "已绑定地图 #" + target.id.id() + "：左上角 (" + binding.minX() + ", " + binding.minZ()
                    + ")，范围 " + size + "×" + size + " 方块。等待 Xaero 地表瓦片加载。");
        } catch (IOException exception) {
            LOGGER.error("Could not save map binding", exception);
            return error(source, "绑定保存失败，请检查日志和配置文件权限。");
        }
    }

    private static int bindHere(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        Target target = target(client);
        if (target == null || client.player == null) return error(source, "请手持地图，站在这张地图实际覆盖的区域内。");
        int size = 128 << target.data.scale;
        int minX = Math.floorDiv(Mth.floor(client.player.getX()) + 64, size) * size - 64;
        int minZ = Math.floorDiv(Mth.floor(client.player.getZ()) + 64, size) * size - 64;
        message(source, "按你当前所在的原版地图网格推算范围；若地图显示其他区域，请改用 bind 或 corner。");
        return bind(source, minX, minZ, true);
    }

    private static int unbind(FabricClientCommandSource source) {
        Target target = target(source.getClient());
        if (target == null || activeWorld == null || bindings == null) return error(source, "请对准或手持已绑定的地图。");
        try {
            if (!bindings.remove(activeWorld, target.id.id())) return message(source, "这张地图没有本地绑定。");
            TextureEntry removed = TEXTURES.remove(target.id.id());
            if (removed != null) release(source.getClient(), removed);
            return message(source, "已取消绑定，恢复原版地图显示。");
        } catch (IOException exception) {
            LOGGER.error("Could not remove map binding", exception);
            return error(source, "取消绑定保存失败，请检查日志。");
        }
    }

    private static int status(FabricClientCommandSource source) {
        message(source, "高清覆盖：" + (enabled ? "开启" : "关闭") + "；Xaero：" + (activeWorld == null ? "尚未就绪" : "就绪")
                + "；已分配高清纹理：" + String.format(Locale.ROOT, "%.1f MiB", texturePixels() * 4.0 / 1048576.0));
        Target target = target(source.getClient());
        if (target != null && bindings != null && activeWorld != null) {
            Binding binding = bindings.find(activeWorld, target.id.id());
            TextureEntry entry = TEXTURES.get(target.id.id());
            if (binding == null) message(source, "地图 #" + target.id.id() + " 未绑定，缩放等级 " + target.data.scale + "。");
            else message(source, "地图 #" + target.id.id() + "，左上角 (" + binding.minX() + ", " + binding.minZ() + ")；高清覆盖 "
                    + String.format(Locale.ROOT, "%.1f%%", entry == null ? 0.0 : entry.knownPixels * 100.0 / ((long) binding.size() * binding.size())) + "。");
        }
        if (XaeroBridge.lastFailure() != null) error(source, "Xaero 读取诊断：" + XaeroBridge.lastFailure());
        return 1;
    }

    private static Target target(Minecraft client) {
        if (client.level == null || client.player == null) return null;
        MapId id = client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof ItemFrame frame
                ? frame.getItem().get(DataComponents.MAP_ID) : null;
        if (id == null) id = client.player.getMainHandItem().get(DataComponents.MAP_ID);
        if (id == null) id = client.player.getOffhandItem().get(DataComponents.MAP_ID);
        if (id == null) return null;
        MapItemSavedData data = client.level.getMapData(id);
        return data == null ? null : new Target(id, data);
    }

    private static int help(FabricClientCommandSource source) {
        return message(source, "/xmapframe bind <中心X> <中心Z>：绑定目标或手持地图\n"
                + "/xmapframe corner <左上角X> <左上角Z>：按西北角绑定\n"
                + "/xmapframe here：仅当你站在地图覆盖区内时推算绑定\n"
                + "/xmapframe unbind | status | refresh | on | off\n"
                + "只替换展示框底图，保留原版标记文字；只读取当前 Xaero 地表地图实例。");
    }

    private static int message(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[Map Art Optimizer] " + message));
        return 1;
    }

    private static int error(FabricClientCommandSource source, String message) {
        source.sendError(Component.literal("[Map Art Optimizer] " + message));
        return 0;
    }

    private record Target(MapId id, MapItemSavedData data) {}

    private static final class TextureEntry {
        final Binding binding;
        final Identifier identifier;
        DynamicTexture texture;
        MapItemSavedData mapData;
        long lastSeen;
        long nextRefresh;
        int lastHash;
        int knownPixels;

        TextureEntry(Binding binding) {
            this.binding = binding;
            identifier = Identifier.fromNamespaceAndPath("map_art_optimizer", "map/" + binding.mapId());
        }
    }
}
