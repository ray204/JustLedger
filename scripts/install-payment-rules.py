#!/usr/bin/env python3
"""Install missing JustLedger local rules via an explicitly forwarded local API.

Usage: adb forward tcp:52046 tcp:52045; python3 scripts/install-payment-rules.py
The app must be running. Existing unrelated/user-modified rules are preserved.
"""
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / 'rules/payment-notice.js').read_text()
BASE = 'http://127.0.0.1:52046'


def request(path, data=None):
    body = None if data is None else json.dumps(data, ensure_ascii=False).encode()
    req = Request(BASE + path, data=body, headers={'Content-Type': 'application/json; charset=utf-8'})
    with urlopen(req, timeout=20) as response:
        result = json.load(response)
    if result['code'] != 200:
        raise RuntimeError(result)
    return result['data']


def main():
    existing = []
    page = 1
    while True:
        batch = request('/rule/list?limit=100&page=' + str(page))
        existing.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    for number, app, label in [
        (910001, 'com.tencent.mm', '微信'),
        (910002, 'com.eg.android.AlipayGphone', '支付宝'),
        (910003, 'com.sankuai.meituan', '美团'),
    ]:
        name = 'JustLedger 本地付款通知 · ' + label
        symbol = 'rule_' + str(number)
        matches = [r for r in existing if r['app'] == app and r['type'] == 'NOTICE' and
                   r['creator'] == 'user' and r['systemRuleName'] == symbol and r['name'] == name]
        if len(matches) > 1:
            raise RuntimeError('存在重复规则，请先人工核对：' + name)
        if matches:
            print('已存在，保留现有规则：' + name)
            continue
        rule = dict(app=app, type='NOTICE', name=name, systemRuleName=symbol,
                    creator='user', enabled=True, autoRecord=True, struct='',
                    js=SOURCE + '\nvar ' + symbol + ' = {get: function(data) {return JustLedgerPaymentNotice.get(' +
                    json.dumps(app) + ', data);}};\n')
        rule_id = request('/rule/add', rule)
        print('已添加：' + name + ' (id=' + str(rule_id) + ')')


if __name__ == '__main__':
    main()
