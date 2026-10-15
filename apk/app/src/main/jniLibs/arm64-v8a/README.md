FFmpeg 原生库(arm64-v8a)与 assets 中的 ffmpeg/cupfox_ffmpeg 二进制体积过大(约 17 MB),不随仓库分发。

本地构建 release APK 前,需自备:
- jniLibs/arm64-v8a/libffmpeg.so(及 armeabi-v7a 对应文件,如启用该 ABI)
- assets/ffmpeg、assets/cupfox_ffmpeg

构建脚本:见仓库根目录 README 的"本地构建"章节。
