package dev.bdinc.minecraft_video_player;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public class StreamFrameProcessor {

    private final Main plugin;
    private final World world;
    private final int x;
    private final int y;
    private final int z;

    public StreamFrameProcessor(Main plugin, World world, int x, int y, int z) {
        this.plugin = plugin;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void start(File streamDirectory) {
        new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path path = streamDirectory.toPath();
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

                while (true) {
                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                    if (key != null) {
                        ArrayList<Path> newImages = new ArrayList<>();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                Path imagePath = path.resolve((Path) event.context());
                                newImages.add(imagePath);
                            }
                        }

                        // Sort the new images by file name
                        newImages.sort(Comparator.comparing(Path::getFileName));

                        // Process the new images in order
                        for (Path imagePath : newImages) {
                            BufferedImage image = ImageIO.read(imagePath.toFile());
                            if (image != null) {
                                // Run the processImage method on the main server thread
                                Bukkit.getScheduler().runTask(plugin, () -> plugin.processImageAsync(image, new Location(world, x, y, z)));
                            }
                        }

                        key.reset();
                    }
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}