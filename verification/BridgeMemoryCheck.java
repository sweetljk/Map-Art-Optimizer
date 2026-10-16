import dev.mapframes.xaero.XaeroBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import xaero.map.WorldMap;
import xaero.map.pool.buffer.TextureDirectBufferPool;
import xaero.map.pool.buffer.PoolTextureDirectBufferUnit;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.region.texture.LeafRegionTexture;
import xaero.map.region.texture.RegionTexture;

public final class BridgeMemoryCheck {
    public static void main(String[] args) throws Exception {
        MapRegion region = new MapRegion("world:one", "null", "mw$default", null,
                -1, -1, Integer.MAX_VALUE, 1, true, null);
        MapTileChunk chunk = new MapTileChunk(region, -1, -1);
        LeafRegionTexture texture = chunk.getLeafTexture();
        PoolTextureDirectBufferUnit unit = new PoolTextureDirectBufferUnit(true);
        Field bufferField = RegionTexture.class.getDeclaredField("colorBuffer");
        bufferField.setAccessible(true);
        bufferField.set(texture, unit);
        ByteBuffer buffer = texture.getDirectColorBuffer();
        chunk.putColour(0, 0, 255, 0, 0, 0, buffer, 64);
        chunk.putColour(1, 0, 0, 255, 0, 255, buffer, 64);
        chunk.putColour(2, 0, 0, 0, 255, 127, buffer, 64);
        chunk.putColour(3, 0, 0, 0, 0, 0, buffer, 64);
        chunk.putColour(63, 63, 17, 34, 51, 0, buffer, 64);
        for (int x = 0; x < 4; x++) texture.putHeight(x, 0, -64);
        texture.putHeight(63, 63, 320);
        buffer.position(13);
        XaeroBridge.clear();
        XaeroBridge.remember(texture);
        if (XaeroBridge.lastFailure() != null) throw new AssertionError(XaeroBridge.lastFailure());
        if (buffer.position() != 13) throw new AssertionError("Source position was changed");
        Field tilesField = XaeroBridge.class.getDeclaredField("TILES");
        tilesField.setAccessible(true);
        Map<?, ?> tiles = (Map<?, ?>) tilesField.get(null);
        if (tiles.size() != 1) throw new AssertionError("Missing snapshot");
        int[] pixels = (int[]) tiles.values().iterator().next();
        int[] expected = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xff000000};
        for (int i = 0; i < expected.length; i++) {
            if (pixels[i] != expected[i]) throw new AssertionError("Color/light mismatch at " + i);
        }
        if (pixels[4] != 0) throw new AssertionError("Unexplored height must stay transparent");
        if (pixels[4095] != 0xff112233) throw new AssertionError("Tile edge was transposed");
        Object key = tiles.keySet().iterator().next();
        Method tileX = key.getClass().getDeclaredMethod("x");
        Method tileZ = key.getClass().getDeclaredMethod("z");
        tileX.setAccessible(true);
        tileZ.setAccessible(true);
        if (!tileX.invoke(key).equals(-1) || !tileZ.invoke(key).equals(-1)) {
            throw new AssertionError("Negative global tile coordinate was lost");
        }
        texture.putHeight(4, 0, 10);
        chunk.putColour(4, 0, 33, 44, 55, 123, buffer, 64);
        XaeroBridge.remember(texture);
        int[] changed = (int[]) tiles.values().iterator().next();
        if (changed[4] != 0xff212c37 || pixels[4] != 0) {
            throw new AssertionError("Snapshots are not immutable across updates");
        }
        // Exercise Xaero 1.41.3's real cache serializer and decoder, including
        // its persisted 13-bit known-height mask, without a Minecraft window.
        ByteArrayOutputStream cache = new ByteArrayOutputStream();
        buffer.position(0);
        texture.writeCacheMapData(new DataOutputStream(cache), new byte[16384], new byte[4], region);
        MapTileChunk decodedChunk = new MapTileChunk(region, -1, -1);
        LeafRegionTexture decoded = decodedChunk.getLeafTexture();
        WorldMap.textureDirectBufferPool = new TextureDirectBufferPool();
        decoded.readCacheData(24, 2, new DataInputStream(new ByteArrayInputStream(cache.toByteArray())),
                new byte[16384], new byte[4], region, null, 7, 7, false);
        if (decoded.getHeight(0, 0) != -64 || decoded.getHeight(63, 63) != 320
                || decoded.getHeight(5, 0) != 32767 || decodedChunk.getLoadState() != 2) {
            throw new AssertionError("Historical cache height mask was decoded incorrectly");
        }
        XaeroBridge.clear();
        XaeroBridge.remember(decoded);
        int[] restored = (int[]) tiles.values().iterator().next();
        if (!java.util.Arrays.equals(changed, restored)) {
            throw new AssertionError("Historical cache RGB/mask does not match live pixels");
        }
        XaeroBridge.clear();
        if (!tiles.isEmpty()) throw new AssertionError("Clear left tiles behind");
        System.out.println("PASS: actual Xaero native pixel encoding, black/light, height mask, negative tile, edge, immutable update, real historical cache roundtrip, clear");
    }
}
