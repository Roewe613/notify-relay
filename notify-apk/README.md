# 通知中转 APK

一个极简的 Android 应用，监听 9530 端口，接收 HTTP POST 请求发 Android 通知。

## 特点
- **不需要 root**
- **不需要关 SELinux**
- **不依赖 Minis**
- App 有自己的通知渠道，正常弹横幅通知
- 开机自启 + 前台服务保活
- 内置 Web UI（http://127.0.0.1:9530/）

## 编译方法

### 方法1: Android Studio (电脑)
1. 用 Android Studio 打开 `notify-apk/` 目录
2. Build → Build APK
3. 把生成的 APK 装到手机

### 方法2: 在线编译
1. 把代码上传到 GitHub
2. 用 [GitHub Actions](https://github.com/marketplace/actions/android-actions) 编译
3. 下载 APK

### 方法3: AIDE (手机)
1. 装 AIDE 从 Play Store
2. 打开 `notify-apk/` 项目
3. 编译安装

## API

```
POST http://127.0.0.1:9530/
Content-Type: application/json
{"title": "标题", "body": "内容"}

GET http://127.0.0.1:9530/         → Web UI
GET http://127.0.0.1:9530/health   → 健康检查
GET http://127.0.0.1:9530/recent   → 最近通知
```
