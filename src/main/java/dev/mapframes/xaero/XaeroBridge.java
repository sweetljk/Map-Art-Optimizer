package dev.mapframes.xaero;

import java.nio.ByteBuffer;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.region.texture.LeafRegionTexture;
import xaero.map.task.MapRunnerTask;

/** A read-only RGB bridge for Xaero's World Map 1.41.3 on Minecraft 26.1.2. */
public final class XaeroBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("map-art-optimizer");
    private static final int SURFACE = Integer.MAX_VALUE;
    private static final int TILE_SIDE = 64;
    private static final int TILE_PIXELS = TILE_SIDE * TILE_SIDE;
    private static final int MAX_CACHED_TILES = 2048; // 32 MiB; an unaligned 2048 map spans 1089 tiles.
    private static final long REQUEST_INTERVAL_NANOS = 500_000_000L;
    private static final long REGION_RETRY_NANOS = 10_000_000_000L;
    private static final Object CACHE_LOCK = new Object();
    private static final LinkedHashMap<TileKey, int[]> TILES = new LinkedHashMap<>(128, 0.75f, true);
    private static final LinkedHashMap<RegionKey, Long> LOAD_REQUESTS = new LinkedHashMap<>(32, 0.75f, true);
    private static volatile String failure;
    private static long nextLoadRequest;
    private static int pendingCacheReads;

    private XaeroBridge() {}

    /** Stable identity includes Xaero's world, selected dimension, and multiworld map. */
    public static String worldKey() {
        try {
            MapProcessor processor = currentProcessor();
            return processor == null ? null : processorKey(processor);
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return null;
        }
    }

    /**
     * Copies one pixel per world block. Unexplored pixels have alpha zero.
     * Call from the Minecraft client thread, with a maximum side length of 2048.
     * Historical regions are queued through Xaero's loader and become available
     * on subsequent calls; this method never waits for disk or GPU readback.
     */
    public static Snapshot capture(int minX, int minZ, int size) {
        if (size < 1 || size > 2048) {
            throw new IllegalArgumentException("Map capture size must be between 1 and 2048");
        }
        int[] pixels = new int[size * size];
        if ((long) minX + size - 1 > Integer.MAX_VALUE || (long) minZ + size - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Map capture exceeds the coordinate range");
        }
        try {
            if (!Minecraft.getInstance().isSameThread()) {
                throw new IllegalStateException("Xaero map capture must run on the Minecraft client thread");
            }
            MapProcessor processor = currentProcessor();
            String world = processor == null ? null : processorKey(processor);
            if (world == null || !processor.getMapSaveLoad().isRegionDetectionComplete()) {
                return new Snapshot(size, pixels, 0);
            }

            int maxX = minX + size - 1;
            int maxZ = minZ + size - 1;
            Map<RegionKey, MapRegion> regions = new LinkedHashMap<>();
            int knownPixels = 0;
            MapRegion requestCandidate = null;

            for (int tileZ = minZ >> 6; tileZ <= maxZ >> 6; tileZ++) {
                for (int tileX = minX >> 6; tileX <= maxX >> 6; tileX++) {
                    TileKey tileKey = new TileKey(world, tileX, tileZ);
                    int[] tile = cachedTile(tileKey);
                    RegionKey regionKey = new RegionKey(world, tileX >> 3, tileZ >> 3);

                    if (tile == null) {
                        MapRegion region;
                        if (regions.containsKey(regionKey)) {
                            region = regions.get(regionKey);
                        } else {
                            boolean exists = processor.regionExists(SURFACE, regionKey.x(), regionKey.z());
                            region = processor.getLeafMapRegion(SURFACE, regionKey.x(), regionKey.z(), exists);
                            if (region != null && !world.equals(regionKey(region))) {
                                region = null;
                            }
                            regions.put(regionKey, region);
                        }
                        if (region != null) {
                            synchronized (region) {
                                MapTileChunk chunk = region.getChunk(tileX & 7, tileZ & 7);
                                if (chunk != null && region.getLoadState() >= 2
                                        && chunk.getLoadState() >= 2 && !chunk.getToUpdateBuffers()) {
                                    remember(chunk.getLeafTexture());
                                }
                                if (requestCandidate == null && canRequest(region, regionKey)) {
                                    requestCandidate = region;
                                }
                            }
                            tile = cachedTile(tileKey);
                        }
                    }

                    if (tile == null) {
                        continue;
                    }
                    int blockX = tileX << 6;
                    int blockZ = tileZ << 6;
                    int left = Math.max(minX, blockX);
                    int top = Math.max(minZ, blockZ);
                    int right = (int) Math.min((long) maxX, (long) blockX + TILE_SIDE - 1);
                    int bottom = (int) Math.min((long) maxZ, (long) blockZ + TILE_SIDE - 1);
                    for (int row = 0; row <= bottom - top; row++) {
                        int z = top + row;
                        int source = (z - blockZ) * TILE_SIDE + left - blockX;
                        int target = (z - minZ) * size + left - minX;
                        int length = right - left + 1;
                        System.arraycopy(tile, source, pixels, target, length);
                        for (int x = 0; x < length; x++) {
                            if ((tile[source + x] >>> 24) != 0) {
                                knownPixels++;
                            }
                        }
                    }
                }
            }
            if (!world.equals(processorKey(processor))) {
                return new Snapshot(size, new int[pixels.length], 0);
            }
            if (requestCandidate != null) {
                queueLoad(processor, requestCandidate);
            }
            return new Snapshot(size, pixels, knownPixels);
        } catch (RuntimeException | LinkageError error) {
            reportFailure(error);
            return new Snapshot(size, new int[pixels.length], 0);
        }
    }

    /**
     * Called by the two Xaero Mixins after live color generation and cache load.
     * Xaero normally releases CPU buffers after upload, so capturing at these
     * points is necessary. Cache loading can call this on Xaero's worker thread.
     */
    public static void remember(LeafRegionTexture texture) {
        try {
            if (texture == null) {
                return;
            }
            MapTileChunk chunk = texture.getTileChunk();
            MapRegion region = chunk.getInRegion();
            if (region.getCaveLayer() != SURFACE) {
                return;
            }
            ByteBuffer source = texture.getDirectColorBuffer();
            if (source == null || source.limit() < TILE_PIXELS * Integer.BYTES) {
                return;
            }
            // Xaero writes native-order ints as B:G:R:light, not ARGB. Light
            // is not opacity; discovered black terrain must remain opaque.
            // Terrain slope shading is already in RGB. We use daytime colors,
            // like Xaero's default PNG export: shader Brightness=1 makes its
            // rgb * max(light, Brightness) formula preserve these RGB values.
            ByteBuffer buffer = source.duplicate().order(source.order());
            int[] argb = new int[TILE_PIXELS];
            for (int z = 0; z < TILE_SIDE; z++) {
                for (int x = 0; x < TILE_SIDE; x++) {
                    int index = z * TILE_SIDE + x;
                    if (texture.getHeight(x, z) == 32767) {
                        continue;
                    }
                    int color = buffer.getInt(index * Integer.BYTES);
                    argb[index] = toArgb(color);
                }
            }
            TileKey key = new TileKey(regionKey(region), chunk.getX(), chunk.getZ());
            synchronized (CACHE_LOCK) {
                TILES.put(key, argb);
                trim(TILES, MAX_CACHED_TILES);
            }
        } catch (RuntimeException | LinkageError error) {
            // A failed integration must not throw back into Xaero's map writer.
            reportFailure(error);
        }
    }

    /** A short diagnostic for the client command; null means no failure observed. */
    public static String lastFailure() {
        return failure;
    }

    public static void clear() {
        synchronized (CACHE_LOCK) {
            TILES.clear();
            LOAD_REQUESTS.clear();
            nextLoadRequest = 0;
        }
        failure = null;
    }

    private static MapProcessor currentProcessor() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session == null || !session.isUsable()) {
            return null;
        }
        MapProcessor processor = session.getMapProcessor();
        return processor != null && processor.isMapWorldUsable() && !processor.isWaitingForWorldUpdate()
                && !processor.isFinalizing() ? processor : null;
    }

    private static String processorKey(MapProcessor processor) {
        if (!processor.isMapWorldUsable() || processor.isWaitingForWorldUpdate()) {
            return null;
        }
        String world = processor.getCurrentWorldId();
        String dimension = processor.getCurrentDimId();
        String multiworld = processor.getCurrentMWId();
        return world == null || dimension == null || multiworld == null ? null : key(world, dimension, multiworld);
    }

    private static String regionKey(MapRegion region) {
        return key(region.getWorldId(), region.getDimId(), region.getMwId());
    }

    private static String key(String world, String dimension, String multiworld) {
        // Length prefixes prevent names containing separators from colliding.
        return component(world) + component(dimension) + component(multiworld);
    }

    private static String component(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private static int[] cachedTile(TileKey key) {
        synchronized (CACHE_LOCK) {
            return TILES.get(key);
        }
    }

    private static int toArgb(int color) {
        int red = color >>> 8 & 255;
        int green = color >>> 16 & 255;
        int blue = color >>> 24 & 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static boolean canRequest(MapRegion region, RegionKey key) {
        boolean cachedOnly = region.getLoadState() == 3 && !region.reloadHasBeenRequested()
                && !region.recacheHasBeenRequested() && !region.isRefreshing();
        if (!region.hasHadTerrain() || !(cachedOnly || region.canRequestReload_unsynced())) {
            return false;
        }
        long now = System.nanoTime();
        synchronized (CACHE_LOCK) {
            Long previous = LOAD_REQUESTS.get(key);
            return now >= nextLoadRequest && (previous == null || now - previous >= REGION_RETRY_NANOS);
        }
    }

    private static void queueLoad(MapProcessor processor, MapRegion region) {
        synchronized (region) {
            RegionKey key = new RegionKey(regionKey(region), region.getRegionX(), region.getRegionZ());
            if (!canRequest(region, key) || processor.getMapSaveLoad().getSizeOfToLoad() >= 2) {
                return;
            }
            if (region.getLoadState() == 3) {
                // State 3 keeps GPU textures but releases CPU pixels. Xaero's
                // requestLoad intentionally ignores it. Re-read a detached
                // region on Xaero's own worker instead of changing the live
                // region's state, clearing textures, or blocking on the GPU.
                synchronized (CACHE_LOCK) {
                    if (pendingCacheReads >= 2) {
                        return;
                    }
                    pendingCacheReads++;
                }
                WorldMap.mapRunner.addTask(new MapRunnerTask() {
                    @Override
                    public void run(MapProcessor activeProcessor) {
                        try {
                            if (activeProcessor == processor && key.world().equals(processorKey(processor))) {
                                readDetachedCache(processor, region);
                            }
                        } catch (IOException | RuntimeException | LinkageError error) {
                            reportFailure(error);
                        } finally {
                            synchronized (CACHE_LOCK) {
                                pendingCacheReads--;
                            }
                        }
                    }
                });
                markRequested(key);
            } else if (region.getLoadState() == 2) {
                region.requestRefresh(processor);
            } else {
                // The non-prioritizing queue leaves Xaero's own requests first.
                processor.getMapSaveLoad().requestLoad(region, "Map Art Optimizer", false);
            }
            if (region.reloadHasBeenRequested() || region.isRefreshing()) {
                markRequested(key);
            }
        }
    }

    private static void readDetachedCache(MapProcessor processor, MapRegion source) throws IOException {
        File cacheFile = source.getCacheFile();
        if (cacheFile == null) {
            cacheFile = source.findCacheFile(processor.getMapSaveLoad());
        }
        if (cacheFile == null || !cacheFile.isFile()) {
            return;
        }
        MapRegion detached = new MapRegion(source.getWorldId(), source.getDimId(), source.getMwId(),
                source.getDim(), source.getRegionX(), source.getRegionZ(), SURFACE,
                source.getInitialVersion(), source.isNormalMapData(), source.getBiomeRegistry());
        detached.setHasHadTerrain();
        detached.setCacheFile(cacheFile);
        try {
            detached.loadCacheTextures(processor, source.getBiomeRegistry(), false, new boolean[8][8],
                    source.getTargetHighlightsHash(), new boolean[1], new boolean[1], 0,
                    WorldMap.oldFormatSupport);
            // Hooks capture each leaf immediately after height/cache decoding.
        } finally {
            detached.deleteBuffers();
        }
    }

    private static void markRequested(RegionKey key) {
        synchronized (CACHE_LOCK) {
            long now = System.nanoTime();
            nextLoadRequest = now + REQUEST_INTERVAL_NANOS;
            LOAD_REQUESTS.put(key, now);
            trim(LOAD_REQUESTS, 128);
        }
    }

    private static <K, V> void trim(LinkedHashMap<K, V> cache, int maximum) {
        while (cache.size() > maximum) {
            var iterator = cache.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    private static void reportFailure(Throwable error) {
        String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        if (failure == null) {
            synchronized (CACHE_LOCK) {
                if (failure == null) {
                    failure = message;
                    LOGGER.warn("Xaero map capture is unavailable; keeping the vanilla map. {}", message);
                }
            }
        }
    }

    public record Snapshot(int size, int[] argb, int knownPixels) {}

    private record TileKey(String world, int x, int z) {}

    private record RegionKey(String world, int x, int z) {}
}
