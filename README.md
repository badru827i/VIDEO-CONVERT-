# Video Convert

Android video converter focused on H.265/HEVC.

## Features
- H.265 / HEVC, H.264 / AVC and AV1 output options
- AI Smart Optimize: analyzes source resolution, FPS, bitrate and available device encoders
- AI Smart Optimize runs on-device and does not require cloud login
- Automatic bitrate/FPS/resolution/codec recommendations
- Low / Medium / High quality presets
- Bitrate 8–30 Mbps
- FPS 30–120
- 720p / 1080p / 1440p / 2160p
- AAC audio bitrate controls
- FFmpeg engine with MediaCodec hardware-encoder first and software fallback
- Android MediaStore output to Movies/VideoConvert
- Fullscreen and rotation-friendly UI

## AI note
The current AI module is an on-device adaptive optimization engine rather than a large neural enhancement model. It uses video metadata and the device's MediaCodec capabilities to choose practical conversion settings without uploading the video.

## Build
GitHub Actions builds app-debug.apk automatically on push to main.
