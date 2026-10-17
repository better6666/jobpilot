# 应用图标

- `source.webp`：原图（公文包 + 纸飞机：求职 + 投递）
- `icon-square.png`：从原图裁出的圆角方块，方块外透明（圆角半径按四个角对角线实测，约 26%）
- `icon-1024.png`：macOS 规格，1024 画布、方块 824、带轻投影 → 生成 `JobPilot.icns`
- `icon-full-1024.png`：几乎满版 → 生成 `JobPilot.ico`、`src/main/resources/icon/app-*.png`、`static/favicon.ico`、下载站内嵌图标

用到的地方：CI 的 jpackage `--icon`（.icns / .ico）、`NativeMainWindow.applyAppIcon`（窗口和任务栏）、
本机管理页 `/favicon.ico`、下载站 `ICON_DATA_URI`（标签页图标 + 左上角品牌标）。
