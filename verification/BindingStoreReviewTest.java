package dev.mapframes;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/** Standalone regression checks; no Minecraft, Fabric, or user game files are needed. */
public final class BindingStoreReviewTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        roundTrip(root.resolve("roundtrip.json"));
        invalidFilesStayUntouched(root.resolve("invalid.json"));
        coordinateBounds();
        failedSaveIsTransactional(root.resolve("transaction.json"));
        System.out.println("PASS: " + checks + " binding persistence/boundary checks");
    }

    private static void roundTrip(Path path) throws IOException {
        BindingStore first = new BindingStore(path);
        check(first.find("server-a/dimension-a", 4) == null, "new store is empty");
        var a = new BindingStore.Binding("server-a/dimension-a", 4, -2112, -64, 2048);
        var b = new BindingStore.Binding("server-b/dimension-a", 4, -320, -576, 256);
        var c = new BindingStore.Binding("server-a/dimension-b", 4, -64, 192, 128);
        first.put(a);
        first.put(b);
        first.put(c);
        BindingStore loaded = new BindingStore(path);
        check(a.equals(loaded.find(a.world(), 4)), "negative coordinates survive persistence");
        check(b.equals(loaded.find(b.world(), 4)), "same map id is isolated by server");
        check(c.equals(loaded.find(c.world(), 4)), "same map id is isolated by dimension");
        check(loaded.find("unknown", 4) == null, "unknown world does not leak another binding");
        var replacement = new BindingStore.Binding(a.world(), 4, -4160, -2112, 2048);
        loaded.put(replacement);
        var updated = new BindingStore(path);
        check(replacement.equals(updated.find(a.world(), 4)), "rebind replaces existing map");
        check(b.equals(updated.find(b.world(), 4)), "rebind preserves other server");
        check(updated.remove(a.world(), 4), "existing binding can be removed");
        check(!updated.remove(a.world(), 4), "absent binding removal is a no-op");
        byte[] beforeNoOp = Files.readAllBytes(path);
        check(!updated.remove("unknown", 555), "unknown binding removal is a no-op");
        check(Arrays.equals(beforeNoOp, Files.readAllBytes(path)), "no-op preserves file bytes");
        check(new BindingStore(path).find(a.world(), 4) == null, "removed binding stays removed");
    }

    private static void invalidFilesStayUntouched(Path path) throws IOException {
        for (String input : List.of(
                "{broken",
                "null",
                "{\"format\":2,\"bindings\":[]}",
                "{\"format\":1,\"bindings\":null}",
                "{\"format\":1,\"bindings\":[null]}",
                "{\"format\":1,\"bindings\":[{\"world\":\"x\",\"mapId\":1,\"size\":129}]}",
                "{\"format\":1,\"bindings\":[{\"world\":\"x\",\"mapId\":-1,\"size\":128}]}",
                "{\"format\":1,\"bindings\":[{\"world\":\" \",\"mapId\":1,\"size\":128}]}",
                "{\"format\":1,\"bindings\":[{\"world\":\"x\",\"mapId\":1,\"minX\":2147483647,\"size\":2048}]}")) {
            Files.writeString(path, input, StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(path);
            boolean failed = false;
            try {
                new BindingStore(path);
            } catch (IOException expected) {
                failed = true;
            }
            check(failed, "invalid configuration is rejected: " + input);
            check(Arrays.equals(before, Files.readAllBytes(path)), "invalid configuration is preserved");
        }
    }

    private static void coordinateBounds() {
        for (int size : List.of(128, 256, 512, 1024, 2048)) {
            check(new BindingStore.Binding("world", 0, -30_000_000, -30_000_000, size).valid(),
                    "minimum negative coordinate accepted, size " + size);
            check(new BindingStore.Binding("world", 0, 30_000_000 - size, 30_000_000 - size, size).valid(),
                    "maximum fitting coordinate accepted, size " + size);
            check(!new BindingStore.Binding("world", 0, 30_000_001 - size, -64, size).valid(),
                    "east overflow rejected, size " + size);
            check(!new BindingStore.Binding("world", 0, -64, 30_000_001 - size, size).valid(),
                    "south overflow rejected, size " + size);
        }
        check(!new BindingStore.Binding("world", 0, -30_000_001, -64, 128).valid(), "west overflow rejected");
        check(!new BindingStore.Binding("world", 0, -64, -30_000_001, 128).valid(), "north overflow rejected");
        check(!new BindingStore.Binding("world", 0, Integer.MAX_VALUE, -64, 2048).valid(), "integer overflow cannot bypass east bound");
        check(!new BindingStore.Binding("world", 0, Integer.MIN_VALUE, -64, 2048).valid(), "integer underflow rejected");
        check(!new BindingStore.Binding("world", 0, 0, 0, 0).valid(), "zero texture size rejected");
    }

    private static void failedSaveIsTransactional(Path path) throws IOException {
        var original = new BindingStore.Binding("world", 1, -64, -64, 128);
        var replacement = new BindingStore.Binding("world", 1, -192, -192, 128);
        BindingStore store = new BindingStore(path);
        store.put(original);
        byte[] before = Files.readAllBytes(path);
        // Windows-specific deterministic filesystem failure: deny rename/delete while reading.
        try (FileChannel ignored = FileChannel.open(path, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            boolean putFailed = false;
            try {
                store.put(replacement);
            } catch (IOException expected) {
                putFailed = true;
            }
            check(putFailed, "save failure is propagated");
            check(original.equals(store.find("world", 1)), "failed put preserves in-memory binding");
            check(Arrays.equals(before, Files.readAllBytes(path)), "failed put preserves old file");
            boolean removeFailed = false;
            try {
                store.remove("world", 1);
            } catch (IOException expected) {
                removeFailed = true;
            }
            check(removeFailed, "remove failure is propagated");
            check(original.equals(store.find("world", 1)), "failed removal preserves in-memory binding");
            check(Arrays.equals(before, Files.readAllBytes(path)), "failed removal preserves old file");
        }
        check(original.equals(new BindingStore(path).find("world", 1)), "old binding remains readable after save failure");
        try (var files = Files.list(path.getParent())) {
            check(files.noneMatch(p -> p.getFileName().toString().startsWith("xaero-map-frames-")),
                    "failed saves clean temporary files");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
