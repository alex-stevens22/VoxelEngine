package world;

import java.util.EnumMap;
import java.util.Map;

public enum Block {
    AIR   (0),
    GRASS (1),
    DIRT  (2),
    STONE (3);

    public final byte id;
    Block(int id){ this.id = (byte)id; }

    public static Block fromId(byte id){
        for (Block b: values()) if (b.id==id) return b;
        return AIR;
    }

    public enum Face { PX, NX, PY, NY, PZ, NZ } // +X,-X,+Y,-Y,+Z,-Z

    // Texture names (atlas keys). Classic MC had “grass_top”, “grass_side”, “dirt”, “stone”.
    private final Map<Face, String> faceTex = new EnumMap<>(Face.class);

    static {
        // default faces for each block type
        GRASS.faceTex.put(Face.PY, "grass_top");
        GRASS.faceTex.put(Face.NY, "dirt");
        for (Face f: Face.values()) if (!GRASS.faceTex.containsKey(f)) GRASS.faceTex.put(f, "grass_side");

        DIRT.faceTex.put(Face.PX,"dirt"); DIRT.faceTex.put(Face.NX,"dirt");
        DIRT.faceTex.put(Face.PY,"dirt"); DIRT.faceTex.put(Face.NY,"dirt");
        DIRT.faceTex.put(Face.PZ,"dirt"); DIRT.faceTex.put(Face.NZ,"dirt");

        STONE.faceTex.put(Face.PX,"stone"); STONE.faceTex.put(Face.NX,"stone");
        STONE.faceTex.put(Face.PY,"stone"); STONE.faceTex.put(Face.NY,"stone");
        STONE.faceTex.put(Face.PZ,"stone"); STONE.faceTex.put(Face.NZ,"stone");
    }

    public String faceTexture(Face f) {
        if (this==AIR) return null;
        String n = faceTex.get(f);
        if (n==null) n = faceTex.getOrDefault(Face.PX, "missing");
        return n;
    }
}
