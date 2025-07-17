package dev.bdinc.minecraft_video_player;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_21_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_21_R3.block.CraftBlock;
import org.bukkit.craftbukkit.v1_21_R3.util.CraftMagicNumbers;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class ColorManager {

    public static HashMap<Material, Color> colorMap = new HashMap<>();

    public static Color getColor(Block block) {
        CraftBlock cb = (CraftBlock) block;
        BlockState bs = cb.getNMS();
        CraftWorld craftWorld = (CraftWorld) block.getWorld();
        BlockGetter world = craftWorld.getHandle();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());

        MapColor mc = bs.getMapColor(world, pos);
        return new Color(getRGBFromMapColor(mc));
    }

    public static Color getColor(Material material) {
        net.minecraft.world.level.block.Block block = CraftMagicNumbers.getBlock(material);
        BlockState defaultState = block.defaultBlockState();
        MapColor mc = defaultState.getMapColor(null, null); // Nulls are acceptable for default color
        return new Color(getRGBFromMapColor(mc));
    }

    private static int getRGBFromMapColor(MapColor mapColor) {
        // 1.21中获取RGB颜色的正确方式
        return mapColor.col;
    }

    public static void setupColorMap() {
        for (Material material : Material.values()) {
            // 跳过不合适的方块
            if (!material.isBlock() || material.isAir() || !material.isSolid()) continue;
            if (material.name().contains("GLASS") || material.equals(Material.BARRIER)) continue;
            if (material.name().contains("POWDER") || material.name().contains("SAND") || material.name().contains("GRAVEL")) continue;
            if (material.equals(Material.REDSTONE_LAMP) || material.name().contains("SHULKER") || material.name().contains("GLAZED")) continue;

            if (Main.speedMode) {
                String upperName = material.name().toUpperCase();
                if (upperName.contains("WOOL") || upperName.contains("CONCRETE") || upperName.contains("TERRACOTTA")) {
                    colorMap.put(material, getColor(material));
                }
            }
        }
    }

    public static boolean isCube(Block block) {
        VoxelShape voxelShape = block.getCollisionShape();
        BoundingBox boundingBox = block.getBoundingBox();
        return (voxelShape.getBoundingBoxes().size() == 1
                && boundingBox.getWidthX() == 1.0
                && boundingBox.getHeight() == 1.0
                && boundingBox.getWidthZ() == 1.0
        );
    }

    // 缓存最近使用的材质
    public static Material lastMaterial;
    public static Color lastColor;

    public static Material getBlock(Color color) {
        if (lastColor != null && lastColor.equals(color)) {
            return lastMaterial;
        }

        double minDistance = Double.MAX_VALUE;
        Material closestMaterial = Material.AIR;

        for (Map.Entry<Material, Color> entry : colorMap.entrySet()) {
            double distance = getDistance(color, entry.getValue());
            if (distance < minDistance) {
                minDistance = distance;
                closestMaterial = entry.getKey();
            }
        }

        lastMaterial = closestMaterial;
        lastColor = color;
        return closestMaterial;
    }

    public static double getDistance(Color color1, Color color2) {
        double redDistance = Math.pow(color1.getRed() - color2.getRed(), 2);
        double greenDistance = Math.pow(color1.getGreen() - color2.getGreen(), 2);
        double blueDistance = Math.pow(color1.getBlue() - color2.getBlue(), 2);
        return Math.sqrt(redDistance + greenDistance + blueDistance);
    }
}