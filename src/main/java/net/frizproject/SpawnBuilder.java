package net.frizproject;

import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.level.Level;

/** Generates the FrizMine hub in the selected loaded world at the caller's position. */
public final class SpawnBuilder {
    private final Level level;
    private final int cx, cy, cz;
    public SpawnBuilder(Level level, int cx, int cy, int cz) { this.level = level; this.cx = cx; this.cy = cy; this.cz = cz; }
    private void put(int x, int y, int z, int id) { level.setBlock(cx + x, cy + y, cz + z, Block.get(id), false, false); }
    public void build() {
        // 65 x 65 quartz plaza with a stepped, symmetric border.
        for (int x=-32; x<=32; x++) for (int z=-32; z<=32; z++) {
            int edge = Math.max(Math.abs(x), Math.abs(z));
            int id = edge >= 30 ? BlockID.GOLD_BLOCK : (edge >= 27 ? BlockID.QUARTZ_BLOCK : BlockID.QUARTZ_BLOCK);
            if (Math.abs(x) <= 2 || Math.abs(z) <= 2) id = BlockID.SEA_LANTERN;
            if (Math.max(Math.abs(x), Math.abs(z)) <= 7) id = BlockID.PRISMARINE;
            put(x, 0, z, id);
        }
        // Four small landscaped gardens and light pillars frame the square.
        tree(-23,-23); tree(23,-23); tree(-23,23); tree(23,23);
        for(int lx : new int[]{-8,8}) for(int lz : new int[]{-8,8}) { put(lx,1,lz,BlockID.QUARTZ_BLOCK); put(lx,2,lz,BlockID.QUARTZ_BLOCK); put(lx,3,lz,BlockID.SEA_LANTERN); }
        logo();
        // Four colored direction halls and landmark pads.
        for (int i=-30; i<=30; i++) for (int w=-3; w<=3; w++) {
            put(i, 0, w, BlockID.QUARTZ_BLOCK); put(w, 0, i, BlockID.QUARTZ_BLOCK);
        }
        pad(-18, -12, BlockID.EMERALD_BLOCK); pad(18, -12, BlockID.GOLD_BLOCK);
        pad(-18, 12, BlockID.IRON_BLOCK); pad(18, 12, BlockID.DIAMOND_BLOCK);
        // Central fountain, four basins and a raised illuminated core.
        for (int x=-5; x<=5; x++) for (int z=-5; z<=5; z++) {
            int d = Math.max(Math.abs(x), Math.abs(z));
            if (d == 5) put(x, 1, z, BlockID.QUARTZ_BLOCK);
            else if (d <= 3) put(x, 1, z, BlockID.WATER);
        }
        put(0,1,0,BlockID.GOLD_BLOCK); put(0,2,0,BlockID.SEA_LANTERN); put(0,3,0,BlockID.SEA_LANTERN);
        for (int sx : new int[]{-25,25}) for (int sz : new int[]{-25,25}) {
            for(int y=1;y<=7;y++) put(sx,y,sz, y%2==0?BlockID.QUARTZ_BLOCK:BlockID.SEA_LANTERN);
            for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) if(Math.max(Math.abs(x),Math.abs(z))==2) put(sx+x,8,sz+z,BlockID.GOLD_BLOCK);
        }
        // Portal and event pads at the far end of each marked route.
        for (int x=-6;x<=6;x++) for(int z=-6;z<=6;z++) {
            if(Math.max(Math.abs(x),Math.abs(z))>=5) put(x,0,32+z,BlockID.OBSIDIAN);
        }
        for (int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) if(Math.max(Math.abs(x),Math.abs(z))==4) put(x,0,-32+z,BlockID.GLOWSTONE);
        // Clear a 3x3 arrival point on the western shopping route.
        for(int y=1;y<=4;y++) for(int x=9;x<=11;x++) for(int z=-1;z<=1;z++) put(x,y,z,BlockID.AIR);
        // Safe arrival clearing above the fountain.
        for(int y=1;y<=4;y++) for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) put(x,y,z,BlockID.AIR);
    }
    private void tree(int x,int z) {
        for(int y=1;y<=4;y++) put(x,y,z,BlockID.LOG);
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) for(int dy=3;dy<=5;dy++)
            if(Math.abs(dx)+Math.abs(dz)+Math.abs(dy-4)<=5 && !(dx==0&&dz==0&&dy<4)) put(x+dx,dy,z+dz,BlockID.LEAVES);
    }
    private void logo() {
        String[][] glyphs={
            {"111","100","110","100","111"}, {"110","101","110","101","110"},
            {"111","010","010","010","111"}, {"011","100","110","001","110"},
            {"101","111","111","101","101"}, {"101","111","111","111","101"},
            {"100","100","100","100","111"}, {"111","100","110","100","100"}
        };
        int cursor=-20;
        for(String[] glyph:glyphs){for(int row=0;row<5;row++)for(int col=0;col<3;col++)if(glyph[row].charAt(col)=='1')put(cursor+col,5+4-row,24,BlockID.GOLD_BLOCK);cursor+=5;}
    }
    private void pad(int x,int z,int id) { for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++) put(x+dx,0,z+dz,Math.max(Math.abs(dx),Math.abs(dz))==4?BlockID.GOLD_BLOCK:id); }
}




