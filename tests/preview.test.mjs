import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { chromium }=createRequire(path.join(root,'.local/preview/package.json'))('playwright');
let browser,server,url;
before(async()=>{
 const html=await readFile(path.join(root,'design/preview.html'));
 server=createServer((req,res)=>{res.setHeader('Content-Type','text/html; charset=utf-8');res.end(html)});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 url=`http://127.0.0.1:${server.address().port}`;
 const executablePath=process.env.CHROME_PATH||['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome','/usr/bin/google-chrome','/usr/bin/chromium'].find(existsSync);
 browser=await chromium.launch({headless:true,...(executablePath?{executablePath}:{})});
});
after(async()=>{await browser?.close();if(server)await new Promise(resolve=>server.close(resolve))});
async function pageFor(t){const page=await browser.newPage({viewport:{width:390,height:844},reducedMotion:'reduce'});const errors=[];page.on('pageerror',e=>errors.push(e.message));t.after(async()=>{await page.close();assert.deepEqual(errors,[])});await page.goto(url);return page}
const bills=p=>p.locator('.nav [data-page=bills]').click();
const filter=(p,key,value)=>p.locator(`[data-filter=${key}]`).selectOption(value);
const center=p=>p.locator('.pie-center').textContent();

test('overviewDefaultsSecondaryAmountsHiddenAndKeepsExpenseVisibilityIndependent',async t=>{
 const p=await pageFor(t);
 assert.equal(await p.locator('.device .home-title').innerText(),'每一笔，都清楚。\n演示数据');
 assert.equal(await p.locator('.device .greeting').count(),0);
 assert.equal(await p.locator('.total').innerText(),'332.00');
 assert.match(await p.locator('.hero-inclusive').innerText(),/3,332.00.*3,000.00/);
 assert.equal(await p.locator('.income-amount').innerText(),'••••');
 assert.equal(await p.locator('.balance-amount').innerText(),'••••');
 await p.getByRole('button',{name:'隐藏支出金额',exact:true}).click();
 assert.doesNotMatch(await p.locator('.hero').innerText(),/332|000|668/);
 await p.getByRole('button',{name:'显示支出金额',exact:true}).click();
 assert.equal(await p.locator('.total').innerText(),'332.00');
});
test('incomeAndBalanceEyesToggleIndependentlyAndResetWhenLeavingOverview',async t=>{
 const p=await pageFor(t);
 await p.getByRole('button',{name:'显示月收入金额',exact:true}).click();
 assert.equal(await p.locator('.income-amount').innerText(),'8,000.00');
 assert.equal(await p.locator('.balance-amount').innerText(),'••••');
 assert.equal(await p.locator('.total').innerText(),'332.00');
 await p.getByRole('button',{name:'显示本月结余金额',exact:true}).click();
 assert.equal(await p.locator('.balance-amount').innerText(),'4,668.00');
 await p.getByRole('button',{name:'隐藏月收入金额',exact:true}).click();
 assert.equal(await p.locator('.income-amount').innerText(),'••••');
 assert.equal(await p.locator('.balance-amount').innerText(),'4,668.00');
 await p.getByRole('button',{name:'隐藏支出金额',exact:true}).click();
 assert.equal(await p.locator('.total').innerText(),'••••');
 assert.equal(await p.locator('.balance-amount').innerText(),'4,668.00');
 await bills(p);await p.locator('.nav [data-page=overview]').click();
 assert.equal(await p.locator('.income-amount').innerText(),'••••');
 assert.equal(await p.locator('.balance-amount').innerText(),'••••');
 await p.getByRole('button',{name:'显示本月结余金额',exact:true}).click();
 await p.getByRole('button',{name:'隐藏本月结余金额',exact:true}).click();
 assert.equal(await p.locator('.balance-amount').innerText(),'••••');
 await p.getByRole('button',{name:'显示月收入金额',exact:true}).click();
 await p.reload();assert.equal(await p.locator('.income-amount').innerText(),'••••');
});
test('scopeChannelAndCategoryFiltersIntersectAndKeepInclusiveTotal',async t=>{
 const p=await pageFor(t);await bills(p);
 assert.equal(await p.locator('[data-bill]').count(),7);
 assert.equal(await p.locator('[data-bill="8"]').count(),0);
 assert.match(await center(p),/332.00/);
 await filter(p,'scope','偶发');assert.equal(await p.locator('[data-bill]').count(),1);assert.match(await center(p),/3,000.00/);
 await filter(p,'channel','微信');assert.equal(await p.locator('[data-bill]').count(),0);assert.match(await center(p),/0.00/);assert.equal(await p.locator('.pie-slice').count(),0);
 await filter(p,'scope','日常');assert.equal(await p.locator('[data-bill]').count(),3);assert.match(await center(p),/102.00/);
 await filter(p,'category','餐饮');assert.equal(await p.locator('[data-bill]').count(),1);assert.match(await center(p),/36.00/);
 await filter(p,'channel','全部');await filter(p,'category','全部');await filter(p,'scope','全部');
 assert.equal(await p.locator('[data-bill]').count(),8);assert.match(await center(p),/3,332.00/);
 await p.getByRole('searchbox').fill('房租');assert.equal(await p.locator('[data-bill]').count(),1);assert.equal(await p.locator('.row-count').innerText(),'1 笔');
 await p.getByRole('searchbox').fill('不存在的商户');assert.equal(await p.locator('[data-bill]').count(),0);assert.equal(await p.locator('.row-count').innerText(),'0 笔');
});
test('pieSelectionShowsCategoryAmountWithLeaderLabelAndResetsOnFilter',async t=>{
 const p=await pageFor(t);await bills(p);const shopping=p.locator('.pie-slice[data-slice="购物"]');
 await shopping.locator('text').click();assert.equal(await shopping.getAttribute('aria-pressed'),'true');assert.match(await center(p),/购物128.0039%/);
 assert.equal(await shopping.locator('circle').getAttribute('stroke-width'),'28');assert.equal(await shopping.locator('path').count(),1);
 await shopping.focus();await p.keyboard.press('Enter');assert.match(await center(p),/日常支出332.00/);
 await shopping.locator('text').click();await p.locator('.pie-center').click();assert.match(await center(p),/日常支出332.00/);
 await shopping.locator('text').click();await filter(p,'scope','偶发');assert.match(await center(p),/偶发支出3,000.00/);assert.equal(await p.locator('.pie-slice[aria-pressed=true]').count(),0);
});
test('rentDefaultsOccasionalAndExplicitDailyOverridePersistsAfterEditing',async t=>{
 const p=await pageFor(t);await bills(p);await filter(p,'scope','偶发');await p.locator('[data-bill="8"]').click();
 const toggle=p.getByRole('switch',{name:'偶发支出',exact:true});assert.equal(await toggle.getAttribute('aria-checked'),'true');
 await toggle.click();await p.getByRole('button',{name:'保存',exact:true}).click();assert.equal(await p.locator('[data-bill="8"]').count(),0);
 await filter(p,'scope','日常');await p.locator('[data-bill="8"]').click();assert.equal(await toggle.getAttribute('aria-checked'),'false');
 await p.locator('[data-action=categories]').click();await p.locator('[data-category="居家"]').click();await p.getByRole('button',{name:'完成',exact:true}).click();
 await p.locator('[data-action=categories]').click();await p.locator('[data-category="房租"]').click();await p.getByRole('button',{name:'完成',exact:true}).click();assert.equal(await toggle.getAttribute('aria-checked'),'false');
 await p.getByRole('button',{name:'保存',exact:true}).click();
 await p.locator('[data-bill="1"]').click();await p.locator('[data-action=categories]').click();await p.locator('[data-category="房租"]').click();await p.getByRole('button',{name:'完成',exact:true}).click();assert.equal(await toggle.getAttribute('aria-checked'),'true');
 await p.getByRole('combobox',{name:'收支类型'}).selectOption('收入');assert.equal(await toggle.count(),0);
 await p.getByRole('combobox',{name:'收支类型'}).selectOption('支出');assert.equal(await toggle.getAttribute('aria-checked'),'true');
 await p.getByRole('button',{name:'保存',exact:true}).click();await filter(p,'scope','偶发');assert.equal(await p.locator('[data-bill]').count(),1);assert.equal(await p.locator('[data-bill="1"]').count(),1);
});

test('aboutOffersUpdateAndAutoCheckWithoutDownloadingInDemo',async t=>{
 const p=await pageFor(t);await p.locator('.nav [data-page=settings]').click();
 await p.locator('[data-action=about]').click();
 const github=p.locator('.sheet a').filter({hasText:'GitHub'});
 assert.equal(await github.getAttribute('href'),'https://github.com/ray204/JustLedger');
 assert.equal(await github.getAttribute('target'),'_blank');assert.doesNotMatch(await p.locator('.sheet').innerText(),/AutoAccounting|开源致谢/);
 const auto=p.getByRole('switch',{name:'自动检查更新',exact:true});assert.equal(await auto.getAttribute('aria-checked'),'true');
 await auto.click();assert.equal(await auto.getAttribute('aria-checked'),'false');
 await p.locator('[data-action=update]').click();assert.match(await p.locator('.sheet').innerText(),/安装需系统确认/);
 await p.locator('[data-action=demo-update]').click();assert.match(await p.getByRole('status').innerText(),/演示：下载校验完成/);
});
