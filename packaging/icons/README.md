# 报销助手 EA 图标

底图取自用户指定的 `/Users/owner/sw/ico/app-cinnabar.ico`，副本位于 [source/app-cinnabar.ico](source/app-cinnabar.ico)。
原图 SHA-256 为 `649ed5239689a2747ce884c997ee124caedbf84c2ba6cdc1a83f604cad026cf2`，
包含 16、20、24、32、40、48、64、128、256 像素九档 RGBA PNG 帧。

右下角黑色斜条以图像宽高各 75.5% 处为中心，沿左下到右上的 45° 方向。
白色 `EA` 使用 Java2D 描绘字形路径，20 和 24 像素档加粗，16 像素档以整像素对齐的字形沿斜条布置。
每档 ICO 使用底图对应尺寸并逐像素保留透明度。macOS 的 512 和 1024 像素档由 256 像素底图做双三次缩放后叠加按需绘制的斜条和字形。
工作台 PNG 直接使用生成后的 256 像素帧。

## 离线复现

从仓库根目录使用 JDK 21，在 macOS 上运行以下命令。

```sh
icon_work=$(mktemp -d)
shasum -a 256 packaging/icons/source/app-cinnabar.ico
java -Djava.awt.headless=true packaging/icons/GenerateIcons.java \
  packaging/icons/source/app-cinnabar.ico "$icon_work"
iconutil -c icns "$icon_work/ExpenseAssistant.iconset" -o "$icon_work/expense-assistant.icns"
iconutil -c iconset "$icon_work/expense-assistant.icns" -o "$icon_work/inspect.iconset"
file "$icon_work/expense-assistant.ico" "$icon_work/expense-assistant.png" "$icon_work/inspect.iconset/"*.png
```

核对 ICO 九档、解出的 ICNS 常规和 Retina 图像及 JavaFX PNG 的真实尺寸、斜条、字形和透明边缘后复制成品。

```sh
cp "$icon_work/expense-assistant.ico" "$icon_work/expense-assistant.icns" packaging/icons/
cp "$icon_work/expense-assistant.png" src/main/resources/ui/expense-assistant.png
```
