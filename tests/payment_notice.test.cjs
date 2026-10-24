const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {};
vm.runInNewContext(fs.readFileSync('rules/payment-notice.js', 'utf8'), context);
const time = 1792800000000;
const meituan = { title: '您已成功付款42.10元', text: '您的美团订单已支付成功，点击查看详情>', t: time };
const wechat = { title: '微信支付', text: '支付成功\n收款方：美团\n支付金额：￥42.10\n付款方式：招商银行信用卡', t: time };
const alipay = { title: '支付宝', text: '付款成功\n商户名称：测试商户\n付款金额：18.50元', t: time };
const parse = (app, notice) => context.JustLedgerPaymentNotice.get(app, JSON.stringify(notice));

test('supported_success_notices_preserve_amount_merchant_and_channel', () => {
    for (const [app, notice, amount, merchant, channel, account] of [
        ['com.sankuai.meituan', meituan, 42.1, '美团', '美团', ''],
        ['com.tencent.mm', wechat, 42.1, '美团', '微信', '招商银行信用卡'],
        ['com.eg.android.AlipayGphone', alipay, 18.5, '测试商户', '支付宝', '']
    ]) {
        const bill = parse(app, notice);
        assert.ok(bill);
        assert.deepEqual(JSON.parse(JSON.stringify(bill)), {
            type: 'Expend', money: amount, fee: 0, time, shopName: merchant,
            shopItem: '', accountNameFrom: account, accountNameTo: '', channel,
            currency: 'CNY', eventStatus: 'COMPLETED', transactionId: '', transactionNamespace: app
        });
    }
});

test('nonpayments_and_ambiguous_notices_do_not_create_expenses', () => {
    for (const [title, text] of [
        ['朋友', wechat.text], ['微信支付', '[1条]微信支付凭证'],
        ['微信支付', '待付款\n收款方：美团\n支付金额：￥42.10'],
        ['微信支付', '退款成功\n收款方：美团\n支付金额：￥42.10'],
        ['微信支付', wechat.text + '\n退款处理中'],
        ['微信支付', wechat.text + '\n支付失败'],
        ['微信支付', wechat.text + '\n支付金额：￥99.00'],
        ['微信支付', wechat.text + '\n收款方：另一商户'],
        ['微信支付', wechat.text + '\n朋友发来付款消息'],
        ['微信支付', '支付成功\n支付金额：￥42.10'],
        ['微信支付', '转账成功\n收款方：美团\n支付金额：￥42.10']
    ]) assert.equal(parse('com.tencent.mm', { title, text, t: time }), null);
    assert.equal(parse('com.unknown.app', wechat), null);
    assert.equal(parse('com.tencent.mm', meituan), null);
    assert.equal(parse('com.sankuai.meituan', { ...meituan, text: '请尽快付款' }), null);
    assert.equal(parse('com.sankuai.meituan', { ...meituan, title: '您已成功付款0元' }), null);
});

test('malformed_amounts_and_payloads_are_rejected_without_guessing', () => {
    for (const amount of ['￥0', '￥-1', '￥42.109', '￥1,000', 'USD 42.10', '42.10', '￥NaN', '￥４２.１０', '￥1e3', '￥01.00', '￥42.10\u0000']) {
        assert.equal(parse('com.tencent.mm', { ...wechat, text: '支付成功\n收款方：美团\n支付金额：' + amount }), null);
    }
    for (const notice of [null, [], {}, { ...wechat, t: 0 }, { ...wechat, t: '123' }, { ...wechat, text: 42 }]) {
        assert.equal(parse('com.tencent.mm', notice), null);
    }
    assert.equal(context.JustLedgerPaymentNotice.get('com.tencent.mm', '{'), null);
    assert.equal(parse('com.tencent.mm', { ...wechat, text: '支付成功\n收款方：美团\n支付金额：￥0.01' }).money, 0.01);
});
