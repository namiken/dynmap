package org.dynmap.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.lang.reflect.Field;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.dynmap.DynmapChunk;
import org.dynmap.Log;

import java.util.List;

/**
 * Container for managing chunks - dependent upon using chunk snapshots, since rendering is off server thread
 */
public class LegacyMapChunkCache implements MapChunkCache {
    private static Method getchunkdata = null;
    private static Method gethandle = null;
    private static Method poppreservedchunk = null;
    private static Field heightmap = null;
    private static boolean initialized = false;
    
    private int x_min, x_max, z_min, z_max;
    private int x_dim;
    private HiddenChunkStyle hidestyle = HiddenChunkStyle.FILL_AIR;
    private List<VisibilityLimit> visible_limits = null;
    
    private LegacyChunkSnapshot[] snaparray; /* Index = (x-x_min) + ((z-z_min)*x_dim) */
    
    /**
     * Iterator for traversing map chunk cache (base is for non-snapshot)
     */
    public class OurMapIterator implements MapIterator {
        private int x, y, z;  
        private LegacyChunkSnapshot snap;

        OurMapIterator(int x0, int y0, int z0) {
            initialize(x0, y0, z0);
        }
        public final void initialize(int x0, int y0, int z0) {
            this.x = x0;
            this.y = y0;
            this.z = z0;
            try {
                snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
            } catch (ArrayIndexOutOfBoundsException aioobx) {
                snap = EMPTY;
            }
        }
        public final int getBlockTypeID() {
            return snap.getBlockTypeId(x & 0xF, y, z & 0xF);
        }
        public final int getBlockData() {
            return snap.getBlockData(x & 0xF, y, z & 0xF);
        }
        public final int getHighestBlockYAt() {
            return snap.getHighestBlockYAt(x & 0xF, z & 0xF);
        }
        public final int getBlockSkyLight() {
            return snap.getBlockSkyLight(x & 0xF, y, z & 0xF);
        }
        public final int getBlockEmittedLight() {
            return snap.getBlockEmittedLight(x & 0xF, y, z & 0xF);
        }
        public final void incrementX() {
            x++;
            if((x & 0xF) == 0) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void decrementX() {
            x--;
            if((x & 0xF) == 15) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void incrementY() {
            y++;
        }
        public final void decrementY() {
            y--;
        }
        public final void incrementZ() {
            z++;
            if((z & 0xF) == 0) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void decrementZ() {
            z--;
            if((z & 0xF) == 15) {  /* Next chunk? */
                try {
                    snap = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
                } catch (ArrayIndexOutOfBoundsException aioobx) {
                    snap = EMPTY;
                }
            }
        }
        public final void setY(int y) {
            this.y = y;
        }
        public final int getY() {
            return y;
        }
     }

    /**
     * Chunk cache for representing unloaded chunk (or air chunk)
     */
    private static class EmptyChunk implements LegacyChunkSnapshot {
        public final int getBlockTypeId(int x, int y, int z) {
            return 0;
        }
        public final int getBlockData(int x, int y, int z) {
            return 0;
        }
        public final int getBlockSkyLight(int x, int y, int z) {
            return 15;
        }
        public final int getBlockEmittedLight(int x, int y, int z) {
            return 0;
        }
        public final int getHighestBlockYAt(int x, int z) {
            return 0;
        }
    }

    /**
     * Chunk cache for representing hidden chunk as stone
     */
    private static class PlainChunk implements LegacyChunkSnapshot {
        private int fillid;
        PlainChunk(int fillid) { this.fillid = fillid; }
        
        public final int getBlockTypeId(int x, int y, int z) {
            if(y < 64)
                return fillid;
            return 0;
        }
        public final int getBlockData(int x, int y, int z) {
            return 0;
        }
        public final int getBlockSkyLight(int x, int y, int z) {
            if(y < 64)
                return 0;
            return 15;
        }
        public final int getBlockEmittedLight(int x, int y, int z) {
            return 0;
        }
        public final int getHighestBlockYAt(int x, int z) {
            return 64;
        }
    }

    private static final EmptyChunk EMPTY = new EmptyChunk();
    private static final PlainChunk STONE = new PlainChunk(1);
    private static final PlainChunk OCEAN = new PlainChunk(9);
    
    
    /**
     * Construct empty cache
     */
    public LegacyMapChunkCache() {
    }
    /**
     * Create chunk cache container
     * @param w - world
     * @param x_min - minimum chunk x coordinate
     * @param z_min - minimum chunk z coordinate
     * @param x_max - maximum chunk x coordinate
     * @param z_max - maximum chunk z coordinate
     */
    @SuppressWarnings({ "unchecked" })
    public void loadChunks(World w, DynmapChunk[] chunks) {
        /* Compute range */
        if(chunks.length == 0) {
            this.x_min = 0;
            this.x_max = 0;
            this.z_min = 0;
            this.z_max = 0;
            x_dim = 1;            
        }
        else {
            x_min = x_max = chunks[0].x;
            z_min = z_max = chunks[0].z;
            for(int i = 1; i < chunks.length; i++) {
                if(chunks[i].x > x_max)
                    x_max = chunks[i].x;
                if(chunks[i].x < x_min)
                    x_min = chunks[i].x;
                if(chunks[i].z > z_max)
                    z_max = chunks[i].z;
                if(chunks[i].z < z_min)
                    z_min = chunks[i].z;
            }
            x_dim = x_max - x_min + 1;            
        }
    
        if(!initialized) {
            try {
                Class c = Class.forName("net.minecraft.server.Chunk");
                getchunkdata = c.getDeclaredMethod("a", new Class[] { byte[].class, int.class, 
                    int.class, int.class, int.class, int.class, int.class, int.class });
                heightmap = c.getDeclaredField("h");
                c = Class.forName("org.bukkit.craftbukkit.CraftChunk");
                gethandle = c.getDeclaredMethod("getHandle", new Class[0]);
            } catch (ClassNotFoundException cnfx) {
            } catch (NoSuchMethodException nsmx) {
            } catch (NoSuchFieldException nsfx) {
            }
            /* Get CraftWorld.popPreservedChunk(x,z) - reduces memory bloat from map traversals (optional) */
            try {
                Class c = Class.forName("org.bukkit.craftbukkit.CraftWorld");
                poppreservedchunk = c.getDeclaredMethod("popPreservedChunk", new Class[] { int.class, int.class });
            } catch (ClassNotFoundException cnfx) {
            } catch (NoSuchMethodException nsmx) {
            }
            initialized = true;
            if(gethandle != null)
                Log.info("Chunk snapshot support enabled");
            else {
                Log.severe("ERROR: Chunk snapshot support not found - rendering not functiona!l");
                return;
            }
        }
        snaparray = new LegacyChunkSnapshot[x_dim * (z_max-z_min+1)];
        if(gethandle != null) {
            // Load the required chunks.
            for (DynmapChunk chunk : chunks) {
                boolean vis = true;
                if(visible_limits != null) {
                    vis = false;
                    for(VisibilityLimit limit : visible_limits) {
                        if((chunk.x >= limit.x0) && (chunk.x <= limit.x1) && (chunk.z >= limit.z0) && (chunk.z <= limit.z1)) {
                            vis = true;
                            break;
                        }
                    }
                }
                boolean wasLoaded = w.isChunkLoaded(chunk.x, chunk.z);
                boolean didload = w.loadChunk(chunk.x, chunk.z, false);
                /* If it did load, make cache of it */
                if(didload) {
                    LegacyChunkSnapshot ss = null;
                    if(!vis) {
                        if(hidestyle == HiddenChunkStyle.FILL_STONE_PLAIN)
                            ss = STONE;
                        else if(hidestyle == HiddenChunkStyle.FILL_OCEAN)
                            ss = OCEAN;
                        else
                            ss = EMPTY;
                    }
                    else {
                        Chunk c = w.getChunkAt(chunk.x, chunk.z);
                        try {
                            Object cc = gethandle.invoke(c);
                            byte[] buf = new byte[32768 + 16384 + 16384 + 16384];   /* Get big enough buffer for whole chunk */
                            getchunkdata.invoke(cc, buf, 0, 0, 0, 16, 128, 16, 0);
                            byte[] h = (byte[])heightmap.get(cc);
                            byte[] hmap = new byte[256];
                            System.arraycopy(h, 0, hmap, 0, 256);
                            ss = new CraftChunkSnapshot(chunk.x, chunk.z, buf, hmap); 
                        } catch (Exception x) {
                        }
                    }
                    snaparray[(chunk.x-x_min) + (chunk.z - z_min)*x_dim] = ss;
                }
                if ((!wasLoaded) && didload) {
                    /* It looks like bukkit "leaks" entities - they don't get removed from the world-level table
                     * when chunks are unloaded but not saved - removing them seems to do the trick */
                    Chunk cc = w.getChunkAt(chunk.x, chunk.z);
                    if(cc != null) {
                        for(Entity e: cc.getEntities())
                            e.remove();
                    }
                    /* Since we only remember ones we loaded, and we're synchronous, no player has
                     * moved, so it must be safe (also prevent chunk leak, which appears to happen
                     * because isChunkInUse defined "in use" as being within 256 blocks of a player,
                     * while the actual in-use chunk area for a player where the chunks are managed
                     * by the MC base server is 21x21 (or about a 160 block radius) */
                    w.unloadChunk(chunk.x, chunk.z, false, false);
                    /* And pop preserved chunk - this is a bad leak in Bukkit for map traversals like us */
                    try {
                        if(poppreservedchunk != null)
                            poppreservedchunk.invoke(w, chunk.x, chunk.z);
                    } catch (Exception x) {
                        Log.severe("Cannot pop preserved chunk - " + x.toString());
                    }
                }
            }
        }
        /* Fill missing chunks with empty dummy chunk */
        for(int i = 0; i < snaparray.length; i++) {
            if(snaparray[i] == null)
                snaparray[i] = EMPTY;
        }
    }
    /**
     * Unload chunks
     */
    public void unloadChunks() {
        if(snaparray != null) {
            for(int i = 0; i < snaparray.length; i++) {
                snaparray[i] = null;
            }
            snaparray = null;
        }
    }
    /**
     * Get block ID at coordinates
     */
    public int getBlockTypeID(int x, int y, int z) {
        LegacyChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBlockTypeId(x & 0xF, y, z & 0xF);
    }
    /**
     * Get block data at coordiates
     */
    public byte getBlockData(int x, int y, int z) {
        LegacyChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return (byte)ss.getBlockData(x & 0xF, y, z & 0xF);
    }
    /* Get highest block Y
     * 
     */
    public int getHighestBlockYAt(int x, int z) {
        LegacyChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getHighestBlockYAt(x & 0xF, z & 0xF);
    }
    /* Get sky light level
     */
    public int getBlockSkyLight(int x, int y, int z) {
        LegacyChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBlockSkyLight(x & 0xF, y, z & 0xF);
    }
    /* Get emitted light level
     */
    public int getBlockEmittedLight(int x, int y, int z) {
        LegacyChunkSnapshot ss = snaparray[((x>>4) - x_min) + ((z>>4) - z_min) * x_dim];
        return ss.getBlockEmittedLight(x & 0xF, y, z & 0xF);
    }
    /**
     * Get cache iterator
     */
    public MapIterator getIterator(int x, int y, int z) {
        return new OurMapIterator(x, y, z);
    }
    /**
     * Set hidden chunk style (default is FILL_AIR)
     */
    public void setHiddenFillStyle(HiddenChunkStyle style) {
        this.hidestyle = style;
    }
    /**
     * Add visible area limit - can be called more than once 
     * Needs to be set before chunks are loaded
     * Coordinates are block coordinates
     */
    public void setVisibleRange(VisibilityLimit lim) {
        VisibilityLimit limit = new VisibilityLimit();
        if(lim.x0 > lim.x1) {
            limit.x0 = (lim.x1 >> 4); limit.x1 = ((lim.x0+15) >> 4);
        }
        else {
            limit.x0 = (lim.x0 >> 4); limit.x1 = ((lim.x1+15) >> 4);
        }
        if(lim.z0 > lim.z1) {
            limit.z0 = (lim.z1 >> 4); limit.z1 = ((lim.z0+15) >> 4);
        }
        else {
            limit.z0 = (lim.z0 >> 4); limit.z1 = ((lim.z1+15) >> 4);
        }
        if(visible_limits == null)
            visible_limits = new ArrayList<VisibilityLimit>();
        visible_limits.add(limit);
    }
}
