# Working Video Player in Minecraft

> Hey, I'm glad you're interested in this plugin! However, after porting it to version 1.21.4 and testing, I found it's not exactly as I hoped. Currently, it only runs on the 1.21.4 server version. This issue traces back to NMS (Netty Minecraft Server) problems. The original author did not provide multi-version NMS adaptation, and I haven't had the need to support other server versions. If you require a version for your server, please leave me a message! I'll build a plugin compatible with your server version.

This plugin allows you to play videos/streams and paste images in Minecraft. It uses a new thread to process videos/streams, supporting any FPS (limited by your hardware). Tested at 60 FPS with 20 TPS on Ryzen 5 5600X (no overclocking).

**Requirements:**
- Minecraft Server 1.21.4
- Java 21

**Note:** Processed videos are automatically resized and saved to `plugins/Minecraft-Video-Player`. You can reference local files using `file://` URLs (e.g., `/processvideo file://video_1752790133248_resized.mp4`) to skip download time!

## How to Use
- `/processvideo [url]`  
- `/processimage [url]`  
- `/processstream`  
- `/setres [width] [height] [fps]`  
*(Only FPS updates dynamically during playback. Resolution changes require reprocessing)*

## Streaming Setup
- *Documentation coming soon*

## How to Build
1. Clone the repository  
2. Build using:  
   `mvn package -Djavacpp.platform=[your-platform]`  
   Example: `mvn package -Djavacpp.platform=linux-x86_64`  
   *(See [JavaCV platforms](https://github.com/bytedeco/javacpp-presets#downloads))*  
3. Requires JDK 21

## How to Install
1. Set up Paper/Spigot server for 1.21.4 ([Guide](https://youtu.be/M5SOwijvXZ0))  
2. Download plugin from [Releases](https://github.com/DarkSavci/minecraft-video-player/releases)  
3. Place in `plugins` folder  
4. Start server  

## TO-DO
- [ ] Add config file for max FPS/resolution control
