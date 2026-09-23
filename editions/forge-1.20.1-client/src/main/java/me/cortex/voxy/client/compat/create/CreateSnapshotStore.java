package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class CreateSnapshotStore {
    private static final int MAGIC = 0x56435831;
    private static final int MAX_MESH_BYTES = 256 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Voxy Create snapshot storage");
        thread.setDaemon(true);
        return thread;
    });

    record Kinetic(long key, int x, int y, int z, int stateHash, byte[] mesh) {}
    record Contraption(UUID id, double x, double y, double z, long signature, boolean train,
                       Matrix4f local, int light, byte[] mesh) {}

    private CreateSnapshotStore() {}

    static void saveKinetic(ClientLevel level, Kinetic value) {
        Path file = directory(level, "kinetic").resolve(Long.toUnsignedString(value.key(), 16) + ".bin");
        IO.execute(() -> write(file, out -> {
            out.writeInt(2);
            out.writeLong(value.key());
            out.writeInt(value.x());
            out.writeInt(value.y());
            out.writeInt(value.z());
            out.writeInt(value.stateHash());
            writeMesh(out, value.mesh());
        }));
    }

    static void removeKinetic(ClientLevel level, long key) {
        Path file = directory(level, "kinetic").resolve(Long.toUnsignedString(key, 16) + ".bin");
        IO.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (Throwable t) {
                Logger.error("Removing stored Create kinetic snapshot", t);
            }
        });
    }

    static List<Kinetic> loadKinetics(ClientLevel level) {
        flush();
        var result = new ArrayList<Kinetic>();
        readDirectory(directory(level, "kinetic"), in -> {
            if (in.readInt() != 2) return;
            long key = in.readLong();
            result.add(new Kinetic(key, in.readInt(), in.readInt(), in.readInt(), in.readInt(), readMesh(in)));
        });
        return result;
    }

    static void saveContraption(ClientLevel level, UUID id, double x, double y, double z, long signature,
                                boolean train, Matrix4f local, int light, byte[] mesh) {
        Path file = directory(level, "contraption").resolve(id + ".bin");
        IO.execute(() -> write(file, out -> {
            out.writeInt(2);
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            out.writeDouble(x);
            out.writeDouble(y);
            out.writeDouble(z);
            out.writeLong(signature);
            out.writeBoolean(train);
            float[] matrix = new float[16];
            local.get(matrix);
            for (float value : matrix) out.writeFloat(value);
            out.writeInt(light);
            writeMesh(out, mesh);
        }));
    }

    static List<Contraption> loadContraptions(ClientLevel level) {
        flush();
        var result = new ArrayList<Contraption>();
        readDirectory(directory(level, "contraption"), in -> {
            int version = in.readInt();
            if (version < 1 || version > 2) return;
            UUID id = new UUID(in.readLong(), in.readLong());
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            long signature = in.readLong();
            boolean train = in.readBoolean();
            float[] matrix = new float[16];
            for (int i = 0; i < matrix.length; i++) matrix[i] = in.readFloat();
            int light = version >= 2 ? in.readInt() : net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
            result.add(new Contraption(id, x, y, z, signature, train, new Matrix4f().set(matrix), light, readMesh(in)));
        });
        return result;
    }

    private static Path directory(ClientLevel level, String kind) {
        var instance = VoxyCommon.getInstance();
        Path base = instance instanceof VoxyClientInstance client
                ? client.getStorageBasePath()
                : net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        String dim = level.dimension().location().toString().replace(':', '_').replace('/', '_').replace('\\', '_');
        return base.resolve("create-snapshots-1.20.1").resolve(dim).resolve(kind);
    }

    private static void writeMesh(DataOutputStream out, byte[] mesh) throws Exception {
        if (mesh == null || mesh.length == 0 || mesh.length > MAX_MESH_BYTES) throw new IllegalArgumentException("mesh size");
        out.writeInt(mesh.length);
        out.write(mesh);
    }

    private static byte[] readMesh(DataInputStream in) throws Exception {
        int length = in.readInt();
        if (length <= 0 || length > MAX_MESH_BYTES) throw new IllegalArgumentException("mesh size");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new java.io.EOFException();
        return bytes;
    }

    static void flush() {
        try {
            IO.submit(() -> {}).get();
        } catch (Throwable t) {
            Logger.error("Flushing Create LOD snapshots", t);
        }
    }

    private interface Writer { void write(DataOutputStream out) throws Exception; }
    private interface Reader { void read(DataInputStream in) throws Exception; }

    private static void write(Path file, Writer writer) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp))))) {
                out.writeInt(MAGIC);
                writer.write(out);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            Logger.error("Storing Create LOD snapshot", t);
        }
    }

    private static void readDirectory(Path directory, Reader reader) {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".bin")).forEach(path -> {
                try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
                    if (in.readInt() == MAGIC) reader.read(in);
                } catch (Throwable t) {
                    Logger.error("Reading Create LOD snapshot " + path.getFileName(), t);
                }
            });
        } catch (Throwable t) {
            Logger.error("Listing stored Create LOD snapshots", t);
        }
    }
}
