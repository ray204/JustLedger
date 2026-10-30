# JustLedger 界面预览

`preview.html` 是独立交互演示，与 0.1.5 界面同步：概览保留单一标题，收入和结余默认隐藏并可独立点眼睛查看，展示日常开销及含偶发总额、渠道与分类下拉筛选、日常／偶发／全部切换、可点选并带引线的分类图表、房租与偶发支出编辑、疑似重复、分页入账确认、三项 AI 配置、页面识别偏好及应用更新入口。数据全部虚构，不访问支付账户或模型服务，不保存密钥。

## 打开

浏览器直接打开 `design/preview.html`，或访问项目 GitHub Pages。支持窄屏与深色模式。原生 App 是 Kotlin Android 实现，HTML 用于交流设计，不是 App 内嵌页面。

## 生成 README 图片

安装 Node.js 和 Chrome，在仓库根目录执行：

```sh
npm install --prefix .local/preview playwright@1.64.1
node scripts/render-preview.mjs
```

工具自动启动仅本机可访问的临时 HTTP 服务，生成 `docs/images/overview.png`、`docs/images/occasional.png` 和 `docs/images/recognition.png`。可用 `CHROME_PATH` 指定浏览器；无本机 Chrome 时需通过 Playwright 安装 Chromium。

图片由 HTML 渲染，标明演示数据，不包含真实手机截图。更新界面后重新生成并提交图片。

## 交互验证

执行 `node --test tests/preview.test.mjs`，检查筛选组合、图表点选、房租默认值与手动覆盖、空状态和金额隐私。使用与渲染脚本相同的本地 Playwright／Chrome 环境。
