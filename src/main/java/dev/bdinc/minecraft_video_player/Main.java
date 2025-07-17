package dev.bdinc.minecraft_video_player;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_21_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_21_R3.util.CraftMagicNumbers;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class Main extends JavaPlugin {

    private static Main instance;

    public static int MAX_WIDTH = 100;
    public static int MAX_HEIGHT = 100;
    public static int MAX_FPS = 30;
    public static boolean speedMode = true;

    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int BLOCK_BATCH_SIZE = 1000;

    private ExecutorService downloadExecutor;
    private ExecutorService processingExecutor;
    private ScheduledExecutorService scheduledExecutor;

    private final ConcurrentHashMap<String, Future<?>> activeTasks = new ConcurrentHashMap<>();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    public static List<Location> latestLocation = new ArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        initializeThreadPools();
        ColorManager.setupColorMap();
        registerCommands();
        getLogger().info("Video Player Plugin enabled with optimized performance!");
    }

    @Override
    public void onDisable() {
        shutdownThreadPools();
        getLogger().info("Video Player Plugin disabled!");
    }

    private void initializeThreadPools() {
        downloadExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "VideoPlayer-Download");
            t.setDaemon(true);
            return t;
        });

        processingExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r, "VideoPlayer-Processing");
            t.setDaemon(true);
            return t;
        });

        scheduledExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "VideoPlayer-Scheduled");
            t.setDaemon(true);
            return t;
        });
    }

    private void shutdownThreadPools() {
        isShuttingDown.set(true);

        activeTasks.values().forEach(future -> future.cancel(true));
        activeTasks.clear();

        shutdownExecutor(scheduledExecutor, "Scheduled");
        shutdownExecutor(downloadExecutor, "Download");
        shutdownExecutor(processingExecutor, "Processing");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning(name + " executor didn't terminate gracefully, forcing shutdown");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("processimage")).setExecutor(new ProcessImageCommand());
        Objects.requireNonNull(getCommand("processvideo")).setExecutor(new ProcessVideoCommand());
        Objects.requireNonNull(getCommand("processstream")).setExecutor(new ProcessStreamCommand());
        Objects.requireNonNull(getCommand("setres")).setExecutor(new SetResCommand());
        Objects.requireNonNull(getCommand("undoimage")).setExecutor(new UndoCommand());
    }

    public static Main getInstance() {
        return instance;
    }

    public void processImageAsync(BufferedImage image, Location location) {
        String taskId = "image_" + System.currentTimeMillis();

        Future<?> task = processingExecutor.submit(() -> {
            try {
                processImageInternal(image, location);
            } catch (Exception e) {
                getLogger().severe("Error processing image: " + e.getMessage());
                e.printStackTrace();
            } finally {
                activeTasks.remove(taskId);
            }
        });

        activeTasks.put(taskId, task);
    }

    private void processImageInternal(BufferedImage image, Location location) {
        if (isShuttingDown.get()) return;

        World world = location.getWorld();
        if (world == null) return;

        synchronized (latestLocation) {
            latestLocation.add(location.clone());
        }

        int x = location.getBlockX() - MAX_WIDTH / 2;
        int y = location.getBlockY() - 5;
        int z = location.getBlockZ() - MAX_HEIGHT / 2;

        BufferedImage resizedImage = resizeImageOptimized(image);

        pasteImageAsync(world, x, y, z, resizedImage);
    }

    private BufferedImage resizeImageOptimized(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width <= MAX_WIDTH && height <= MAX_HEIGHT) {
            return image;
        }

        double ratio = (double) width / height;
        int newWidth, newHeight;

        if (ratio > 1) {
            newWidth = MAX_WIDTH;
            newHeight = (int) (MAX_WIDTH / ratio);
        } else {
            newHeight = MAX_HEIGHT;
            newWidth = (int) (MAX_HEIGHT * ratio);
        }

        BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resized.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);

        g2d.drawImage(image, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        return resized;
    }

    public void processVideoAsync(URL url, Location location) {
        String taskId = "video_" + System.currentTimeMillis();

        Future<?> initialSetupTask = processingExecutor.submit(() -> {
            try {
                processVideoInternal(url, location, taskId);
            } catch (Exception e) {
                getLogger().severe("Error processing video (initial setup): " + e.getMessage());
                e.printStackTrace();
                Bukkit.getScheduler().runTask(this, () ->
                        Bukkit.broadcastMessage("§cError processing video: " + e.getMessage()));
                activeTasks.remove(taskId);
            }
        });

        activeTasks.put(taskId, initialSetupTask);
    }

    // 新增 taskId 参数
    private void processVideoInternal(URL url, Location location, String taskId) {
        File videoFile;
        File resizedFile;
        if (isShuttingDown.get()) {
            activeTasks.remove(taskId);
            return;
        }

        World world = location.getWorld();
        if (world == null) {
            activeTasks.remove(taskId);
            return;
        }

        synchronized (latestLocation) {
            latestLocation.add(location.clone());
        }

        int x = location.getBlockX() - MAX_WIDTH / 2;
        int y = location.getBlockY() - 10;
        int z = location.getBlockZ() - MAX_HEIGHT / 2;
        // 如果视频链接以file://开头则读取本地的
        if (!url.toString().startsWith("file://")) {
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.broadcastMessage("§aDownloading video..."));

            videoFile = downloadVideoAsync(url).join();
            if (videoFile == null || isShuttingDown.get()) {
                activeTasks.remove(taskId);
                return;
            }
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.broadcastMessage("§aResizing video..."));

            resizedFile = resizeVideoAsync(videoFile).join();
            if (resizedFile == null || isShuttingDown.get()) {
                // 如果 resizedFile 为空，videoFile 理论上已经被 resizeVideoAsync 删除。
                // 目前 resizeVideoOptimized 内部会删除原始文件，所以这里只检查 resizedFile
                activeTasks.remove(taskId);
                return;
            }
        }else{
            // 从插件目录
            videoFile = new File(getDataFolder() + "\\" + url.toString().replace("file://", ""));
            resizedFile = videoFile;
        }

        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.broadcastMessage("§aProcessing video..."));

        processVideoFrames(resizedFile, world, x, y, z, taskId);
    }

    private CompletableFuture<File> downloadVideoAsync(URL url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                String fileName = "video_" + System.currentTimeMillis() + ".mp4";
                File file = new File(getDataFolder(), fileName);

                if (!getDataFolder().exists()) {
                    getDataFolder().mkdirs();
                }

                FileUtils.copyURLToFile(url, file);

                long endTime = System.currentTimeMillis();
                Bukkit.getScheduler().runTask(this, () ->
                        Bukkit.broadcastMessage("§aDownload completed in " + (endTime - startTime) + "ms"));

                return file;
            } catch (Exception e) {
                getLogger().severe("Error downloading video: " + e.getMessage());
                return null;
            }
        }, downloadExecutor);
    }

    private CompletableFuture<File> resizeVideoAsync(File video) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return resizeVideoOptimized(video);
            } catch (Exception e) {
                getLogger().severe("Error resizing video: " + e.getMessage());
                return null;
            }
        }, processingExecutor);
    }

    private File resizeVideoOptimized(File video) throws FrameGrabber.Exception, FrameRecorder.Exception {
        long startTime = System.currentTimeMillis();

        try (FFmpegFrameGrabber frameGrabber = new FFmpegFrameGrabber(video)) {
            frameGrabber.start();

            double aspectRatio = (double) frameGrabber.getImageWidth() / frameGrabber.getImageHeight();
            int newWidth, newHeight;
            double targetAspectRatio = (double) MAX_WIDTH / MAX_HEIGHT;

            if (aspectRatio > targetAspectRatio) {
                newWidth = MAX_WIDTH;
                newHeight = (int) (MAX_WIDTH / aspectRatio);
            } else {
                newWidth = (int) (MAX_HEIGHT * aspectRatio);
                newHeight = MAX_HEIGHT;
            }

            // 尺寸至少为1x1
            newWidth = Math.max(1, newWidth);
            newHeight = Math.max(1, newHeight);

            String outputFilename = video.getAbsolutePath().replace(".mp4", "_resized.mp4");

            try (FFmpegFrameRecorder frameRecorder = new FFmpegFrameRecorder(outputFilename, newWidth, newHeight)) {
                frameRecorder.setVideoCodec(frameGrabber.getVideoCodec());
                frameRecorder.setFormat("mp4");
                frameRecorder.setFrameRate(Math.min(frameGrabber.getFrameRate(), MAX_FPS));
                frameRecorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
                frameRecorder.start();

                OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();
                Frame frame;

                while ((frame = frameGrabber.grab()) != null && !isShuttingDown.get()) {
                    Mat mat = converter.convertToMat(frame);
                    if (mat != null) {
                        Mat resizedMat = new Mat();
                        org.bytedeco.opencv.global.opencv_imgproc.resize(mat, resizedMat, new Size(newWidth, newHeight));
                        Frame resizedFrame = converter.convert(resizedMat);
                        frameRecorder.record(resizedFrame);

                        mat.release();
                        resizedMat.release();
                    }
                }

                frameRecorder.stop();
            }

            frameGrabber.stop();
        }

        try {
            FileUtils.forceDelete(video);
        } catch (IOException e) {
            getLogger().warning("Could not delete original video file: " + e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.broadcastMessage("§aVideo resized in " + (endTime - startTime) + "ms"));

        return new File(video.getAbsolutePath().replace(".mp4", "_resized.mp4"));
    }

    private void processVideoFrames(File videoFile, World world, int x, int y, int z, String taskId) {
        final FFmpegFrameGrabber grabber;
        try {
            grabber = new FFmpegFrameGrabber(videoFile);
            grabber.start();

            Java2DFrameConverter converter = new Java2DFrameConverter();
            double frameRate = Math.min(grabber.getFrameRate(), MAX_FPS);
            long frameDelay = Math.max(1, Math.round(1000.0 / frameRate));

            VideoFrameProcessor processor = new VideoFrameProcessor(this, world, x, y, z);
            processor.start();

            final AtomicReference<ScheduledFuture<?>> selfCancellingFutureRef = new AtomicReference<>();

            Runnable videoFrameTask = () -> {
                try {
                    if (isShuttingDown.get() || grabber.getFrameNumber() >= grabber.getLengthInFrames()) {
                        try {
                            grabber.stop();
                            grabber.release();
                            getLogger().info("Video playback for task " + taskId + " finished or stopped.");
                            Bukkit.getScheduler().runTask(this, () ->
                                    Bukkit.broadcastMessage("§aVideo playback finished for task " + taskId + "."));

                            if (videoFile.exists()) {
                                try {
                                    FileUtils.forceDelete(videoFile);
                                    getLogger().info("Deleted temporary video file: " + videoFile.getName());
                                } catch (IOException e) {
                                    getLogger().warning("Could not delete temporary video file " + videoFile.getName() + ": " + e.getMessage());
                                }
                            }
                            // if (processor != null) processor.stop();

                        } catch (FrameGrabber.Exception ex) {
                            getLogger().severe("Error stopping/releasing grabber for task " + taskId + ": " + ex.getMessage());
                        } finally {
                            ScheduledFuture<?> currentFuture = selfCancellingFutureRef.get();
                            if (currentFuture != null) {
                                currentFuture.cancel(false);
                            }
                            activeTasks.remove(taskId);
                        }
                        return;
                    }

                    Frame frame = grabber.grab();
                    if (frame != null) {
                        BufferedImage image = converter.getBufferedImage(frame);
                        if (image != null) {
                            processor.addFrame(image);
                        }
                    }
                } catch (Exception e) {
                    getLogger().severe("Error processing video frame for task " + taskId + ": " + e.getMessage());
                    try {
                        grabber.stop();
                        grabber.release();
                        // if (processor != null) processor.stop();
                    } catch (FrameGrabber.Exception ex) {
                        getLogger().severe("Error stopping/releasing grabber after frame error for task " + taskId + ": " + ex.getMessage());
                    } finally {
                        ScheduledFuture<?> currentFuture = selfCancellingFutureRef.get();
                        if (currentFuture != null) {
                            currentFuture.cancel(false);
                        }
                        activeTasks.remove(taskId);
                    }
                }
            };

            ScheduledFuture<?> scheduledPlaybackFuture = scheduledExecutor.scheduleAtFixedRate(
                    videoFrameTask, 0, frameDelay, TimeUnit.MILLISECONDS);
            selfCancellingFutureRef.set(scheduledPlaybackFuture);

            activeTasks.put(taskId, scheduledPlaybackFuture);

        } catch (Exception e) {
            getLogger().severe("Error setting up video processing for task " + taskId + ": " + e.getMessage());
            activeTasks.remove(taskId);
        }
    }

    void pasteImageAsync(World world, int x, int y, int z, BufferedImage image) {
        List<BlockUpdate> blockUpdates = new ArrayList<>();

        for (int i = 0; i < image.getWidth(); i++) {
            for (int j = 0; j < image.getHeight(); j++) {
                Color color = new Color(image.getRGB(i, j));
                Material material = ColorManager.getBlock(color);

                if (!world.getBlockAt(x + i, y, z + j).getType().equals(material)) {
                    blockUpdates.add(new BlockUpdate(x + i, y, z + j, material));
                }
            }
        }

        applyBlockUpdatesAsync(world, blockUpdates);
    }

    private void applyBlockUpdatesAsync(World world, List<BlockUpdate> updates) {
        List<List<BlockUpdate>> batches = partitionList(updates, BLOCK_BATCH_SIZE);

        for (List<BlockUpdate> batch : batches) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (isShuttingDown.get()) {
                        cancel();
                        return;
                    }

                    ServerLevel nmsWorld = ((CraftWorld) world).getHandle();
                    for (BlockUpdate update : batch) {
                        BlockPos pos = new BlockPos(update.x, update.y, update.z);
                        // setBlock 第二个参数是 BlockState，第三个是 flag
                        // flag 2: 不重新渲染方块, 不触发方块更新 (效率高)
                        // flag 3: 重新渲染方块, 触发方块更新 (标准)
                        nmsWorld.setBlock(pos,
                                CraftMagicNumbers.getBlock(update.material).defaultBlockState(),
                                speedMode ? 2 : 3);
                    }
                }
            }.runTask(this);

            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static <T> List<List<T>> partitionList(List<T> list, int partitionSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += partitionSize) {
            partitions.add(list.subList(i, Math.min(i + partitionSize, list.size())));
        }
        return partitions;
    }

    public void undoLastImageAsync() {
        processingExecutor.submit(() -> {
            try {
                synchronized (latestLocation) {
                    if (latestLocation.isEmpty()) return;

                    Location location = latestLocation.get(latestLocation.size() - 1);
                    World world = location.getWorld();
                    if (world == null) return;

                    int x = location.getBlockX() - MAX_WIDTH / 2;
                    int y = location.getBlockY() - 10;
                    int z = location.getBlockZ() - MAX_HEIGHT / 2;

                    List<BlockUpdate> clearUpdates = new ArrayList<>();
                    for (int i = 0; i < MAX_WIDTH; i++) {
                        for (int j = 0; j < MAX_HEIGHT; j++) {
                            clearUpdates.add(new BlockUpdate(x + i, y, z + j, Material.AIR));
                        }
                    }

                    applyBlockUpdatesAsync(world, clearUpdates);
                    latestLocation.remove(latestLocation.size() - 1);
                }
            } catch (Exception e) {
                getLogger().severe("Error undoing last image: " + e.getMessage());
            }
        });
    }

    public BufferedImage getImageFromURL(URL url) {
        try {
            return ImageIO.read(url);
        } catch (Exception e) {
            getLogger().severe("Error loading image from URL: " + e.getMessage());
        }
        return null;
    }

    private static class BlockUpdate {
        final int x, y, z;
        final Material material;

        BlockUpdate(int x, int y, int z, Material material) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }
    }

}
