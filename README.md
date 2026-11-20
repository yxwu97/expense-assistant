# 报销助手

报销助手是面向 macOS 和 Windows 的本地桌面应用。导入报销目录后，可以核对凭证识别结果、编辑费用和附件，从同一草稿版本生成 Excel 报销清单与 Word 打印附件。凭证识别、草稿和文件生成均在本机处理，预览和打印使用 WPS Office。

## 构建与运行

使用 JDK 21 和 Maven。在仓库根目录运行以下命令构建源码。

```sh
mvn -B -ntp -DskipTests package
```

完整启动及导出需要公司原版 Excel 模板。将模板放在 `data/3、报销制度-空白表格模板 .xlsx`，文件名在 `.xlsx` 前有一个空格。应用会检查模板的 SHA-256，期望值见 [组件清单](packaging/components.json)。扫描件及图片识别需要本机 Tesseract 和相应语言包。

```sh
mvn -B -ntp javafx:run
```

macOS 可使用 [启动脚本](启动报销助手.command)。安装包应在对应平台上使用 [macOS 打包脚本](packaging/macos/package.sh) 或 [Windows 打包脚本](packaging/windows/package.ps1) 构建，所需组件及摘要见 [组件清单](packaging/components.json)。

## 测试

仓库包含测试代码。完整 `mvn -B -ntp verify` 需要公司模板、受控样本及匹配的本地 `src/test/resources/corpus-manifest.json`。可使用 `-Dexpenseassistant.sampleRoot` 指定样本目录，使用 `-Dexpenseassistant.ocrRoot` 指定 OCR 组件目录。`-Pdesktop-ui` 启用依赖桌面图形会话的集成测试。
