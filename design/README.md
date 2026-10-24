# JustLedger 界面预览

`preview.html` 是独立交互演示，展示概览、渠道筛选、分类图表、账单编辑、疑似重复、分页入账确认、三项 AI 配置及页面识别偏好。数据全部虚构，不访问支付账户或模型服务，不保存密钥。

## 打开

浏览器直接打开 `design/preview.html`，或访问项目 GitHub Pages。支持窄屏与深色模式。原生 App 是 Kotlin Android 实现，HTML 用于交流设计，不是 App 内嵌页面。

## 生成 README 图片

安装 Node.js 和 Chrome，在仓库根目录执行：

```sh
npm install --prefix .local/preview playwright@1.64.1
node scripts/render-preview.mjs
```

工具自动启动仅本机可访问的临时 HTTP 服务，生成 `docs/images/overview.png` 和 `docs/images/recognition.png`。可用 `CHROME_PATH` 指定浏览器；无本机 Chrome 时需通过 Playwright 安装 Chromium。

图片由 HTML 渲染，标明演示数据，不包含真实手机截图。更新界面后重新生成并提交图片。
