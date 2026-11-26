package hackerrouter.perimeterdigger.client.api;

import com.google.gson.*;
import hackerrouter.perimeterdigger.client.config.*;
import java.util.Set;

/** Inclusive, block-aligned bounds. Never rounds to chunk boundaries. */
public record CuboidTask(String taskId, String world, String dimension,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    private static final Set<String> KEYS = Set.of("taskId", "world", "dimension", "from", "to");
    public CuboidTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("Invalid taskId");
        if (world == null || world.isBlank() || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) throw new IllegalArgumentException("world and dimension are required");
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Inverted bounds");
        if (minX < -29999984 || maxX > 29999983 || minZ < -29999984 || maxZ > 29999983) throw new IllegalArgumentException("Outside supported world coordinates");
        volume(minX, minY, minZ, maxX, maxY, maxZ); // Check arithmetic, not an arbitrary task size limit.
    }
    private static long volume(int x,int y,int z,int xx,int yy,int zz) {
        try { return Math.multiplyExact(Math.multiplyExact((long)xx-x+1,(long)yy-y+1),(long)zz-z+1); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Volume overflow"); }
    }
    public long volume() { return volume(minX,minY,minZ,maxX,maxY,maxZ); }
    public static CuboidTask parse(JsonObject input) {
        for (String key:input.keySet()) if(!KEYS.contains(key)) throw new IllegalArgumentException("Unknown field: "+key);
        int[] a=point(input,"from"), b=point(input,"to");
        return new CuboidTask(string(input,"taskId"),string(input,"world"),string(input,"dimension"),
            Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.min(a[2],b[2]),Math.max(a[0],b[0]),Math.max(a[1],b[1]),Math.max(a[2],b[2]));
    }
    public static String string(JsonObject o,String key) {
        var v=o.get(key); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("String required: "+key);
        return v.getAsString();
    }
    private static int[] point(JsonObject o,String key) {
        var v=o.get(key);if(v==null||!v.isJsonArray()||v.getAsJsonArray().size()!=3)throw new IllegalArgumentException("Three integer coordinates required: "+key);
        int[] p=new int[3];for(int i=0;i<3;i++) {var n=v.getAsJsonArray().get(i);if(!n.isJsonPrimitive()||!n.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Integer coordinates required");try{p[i]=n.getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("Integer coordinate out of range");}}return p;
    }
    public void apply(PerimeterConfig config) {
        var area=new DetectedAreaConfig();area.boundaryBlock="agent_cuboid";area.boundaryY=maxY;
        area.minX=minX;area.maxX=maxX;area.minZ=minZ;area.maxZ=maxZ;
        area.columnCount=((long)maxX-minX+1)*((long)maxZ-minZ+1);
        for(int z=minZ;;z++){area.scanlines.add(new ScanlineConfig(z,minX,maxX));if(z==maxZ)break;}
        config.detectedArea=area;config.diggingMinY=minY;config.diggingMaxY=maxY;
    }
}
