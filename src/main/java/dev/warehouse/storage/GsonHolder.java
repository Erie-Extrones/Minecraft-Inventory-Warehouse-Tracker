package dev.warehouse.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;

/** Shared Gson instance with adapters for Minecraft value types. */
public final class GsonHolder {
    public static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter().nullSafe())
            .registerTypeAdapter(Vec3.class, new Vec3Adapter().nullSafe())
            .create();

    /** Compact variant for exports. */
    public static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .registerTypeAdapter(BlockPos.class, new BlockPosAdapter().nullSafe())
            .registerTypeAdapter(Vec3.class, new Vec3Adapter().nullSafe())
            .create();

    private GsonHolder() {}

    private static final class BlockPosAdapter extends TypeAdapter<BlockPos> {
        @Override
        public void write(JsonWriter out, BlockPos value) throws IOException {
            out.beginArray().value(value.getX()).value(value.getY()).value(value.getZ()).endArray();
        }

        @Override
        public BlockPos read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.BEGIN_OBJECT) {
                int x = 0, y = 0, z = 0;
                in.beginObject();
                while (in.hasNext()) {
                    switch (in.nextName()) {
                        case "x" -> x = in.nextInt();
                        case "y" -> y = in.nextInt();
                        case "z" -> z = in.nextInt();
                        default -> in.skipValue();
                    }
                }
                in.endObject();
                return new BlockPos(x, y, z);
            }
            in.beginArray();
            int x = in.nextInt();
            int y = in.nextInt();
            int z = in.nextInt();
            in.endArray();
            return new BlockPos(x, y, z);
        }
    }

    private static final class Vec3Adapter extends TypeAdapter<Vec3> {
        @Override
        public void write(JsonWriter out, Vec3 value) throws IOException {
            out.beginArray().value(value.x).value(value.y).value(value.z).endArray();
        }

        @Override
        public Vec3 read(JsonReader in) throws IOException {
            in.beginArray();
            double x = in.nextDouble();
            double y = in.nextDouble();
            double z = in.nextDouble();
            in.endArray();
            return new Vec3(x, y, z);
        }
    }
}
