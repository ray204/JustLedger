/* JustLedger local notification rules. No network, activation or shared common.js.
 * NotificationService supplies {title,text,t}; app identity is selected by RuleGenerator.
 * Only these explicit successful-payment formats are supported. Missing/redacted
 * amounts, refunds, transfers and OCR are deliberately not interpreted as expenses.
 */
var JustLedgerPaymentNotice = {
    get: function (app, input) {
        var notice;
        try { notice = JSON.parse(input); } catch (_) { return null; }
        if (!notice || typeof notice.title !== 'string' || typeof notice.text !== 'string' ||
            typeof notice.t !== 'number' || !isFinite(notice.t) || notice.t <= 0) return null;
        var title = notice.title.trim();
        var text = notice.text.trim();
        var all = title + '\n' + text;
        if (/退款|退回|撤销|取消|失败|待付|未付|处理中|申请|转账|收款到账|收款成功|验证码|优惠券|红包/.test(all)) return null;
        var amount = '', merchant = '', channel = '', account = '';
        if (app === 'com.sankuai.meituan') {
            var match = /^您已成功付款((?:0|[1-9]\d{0,8})(?:\.\d{1,2})?)元$/.exec(title);
            if (!match || !/^您的美团订单已支付成功[，,]点击查看详情[>＞]?$/.test(text)) return null;
            amount = match[1];
            merchant = '美团';
            // The order notification does not identify WeChat/Alipay/bank payment.
            channel = '美团';
        } else {
            if (app === 'com.tencent.mm' && /^(微信支付|微信支付凭证)$/.test(title)) {
                channel = '微信';
            } else if (app === 'com.eg.android.AlipayGphone' && /^(支付宝|支付宝支付凭证|支付成功|付款成功)$/.test(title)) {
                channel = '支付宝';
            } else return null;
            var lines = text.split(/\r?\n/).map(function (line) { return line.trim(); }).filter(Boolean);
            if (!lines.some(function (line) { return /^(支付成功|付款成功)$/.test(line); }) &&
                !/^(支付成功|付款成功)$/.test(title)) return null;
            var amounts = [], merchants = [], accounts = [];
            for (var i = 0; i < lines.length; i++) {
                var line = lines[i];
                if (/^(支付成功|付款成功)$/.test(line)) continue;
                var field = /^(支付金额|付款金额|收款方|商户名称|付款方式|支付方式)[：:]\s*(.+)$/.exec(line);
                // Reject unknown free text, summary notifications and concatenated messages.
                if (!field) return null;
                if (/金额$/.test(field[1])) amounts.push(field[2]);
                else if (/方式$/.test(field[1])) accounts.push(field[2]);
                else merchants.push(field[2]);
            }
            if (amounts.length !== 1 || merchants.length !== 1 || accounts.length > 1) return null;
            var money = /^(?:[￥¥]\s*((?:0|[1-9]\d{0,8})(?:\.\d{1,2})?)|((?:0|[1-9]\d{0,8})(?:\.\d{1,2})?)\s*元)$/.exec(amounts[0]);
            if (!money) return null;
            amount = money[1] || money[2];
            merchant = merchants[0];
            account = accounts[0] || '';
        }
        if (!(Number(amount) > 0)) return null;
        return {
            type: 'Expend', money: Number(amount), fee: 0, time: notice.t,
            shopName: merchant, shopItem: '', accountNameFrom: account,
            accountNameTo: '', channel: channel, currency: 'CNY', eventStatus: 'COMPLETED',
            // Amount/time are not transaction identity. Cross-source ambiguity is
            // handled by DuplicateDetector and requires review instead of a second count.
            transactionId: '', transactionNamespace: app
        };
    }
};
