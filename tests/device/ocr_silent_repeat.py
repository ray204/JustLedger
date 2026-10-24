"""Exercise post-AI dedup through the real phone service; fake model, labelled fixtures only.
Requires adb forward tcp:52046 tcp:52045 and adb reverse tcp:52047 tcp:52047.
Restores model settings and removes created fixture bills/captures in finally/tearDown.
"""
import json
import threading
import time
import unittest
from http.server import HTTPServer
from ai_priority import Model, keys, responses, requests
from ocr_enrichment import api

APP = 'com.example.justledger.test'
MARK = 'JL_SILENT_' + str(time.time_ns())


class SilentOcrTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.saved = {key: api('/setting/get?key=' + key, '')['data'] for key in keys}
        cls.http = HTTPServer(('127.0.0.1', 52047), Model)
        threading.Thread(target=cls.http.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        try:
            for key, value in cls.saved.items():
                api('/setting/set?key=' + key, value)
        finally:
            cls.http.shutdown()
            cls.http.server_close()

    def setUp(self):
        self.ids = set()
        requests.clear()
        responses.clear()
        for key, value in dict(feature_ai_available='true', ai_bill_recognition='true',
                              api_provider='ChatGPT', api_key='test-only',
                              api_uri='http://127.0.0.1:52047/v1', api_model='test-only').items():
            api('/setting/set?key=' + key, value)

    def tearDown(self):
        for id in sorted(self.ids, reverse=True):
            bill = api('/bill/get?id=' + str(id)).get('data')
            if bill and MARK in json.dumps(bill):
                self.assertEqual(200, api('/bill/remove', {'id': id})['code'])
        for row in api('/data/list?page=1&limit=100&type=OCR&app=' + APP)['data']:
            if MARK in row['data']:
                api('/data/delete', {'id': row['id']})

    def target(self, amount, **overrides):
        bill = dict(money=amount, type='Expend', currency='CNY', time=1792826930000,
                    state='Edited', app=APP, shopName=MARK + ' shop', shopItem='拿铁',
                    remark=MARK + ' user note', bookName='默认账本')
        bill.update(overrides)
        result = api('/bill/put', bill)
        self.assertEqual(200, result['code'])
        self.ids.add(result['data'])
        return api('/bill/get?id=' + str(result['data']))['data']

    def recognize(self, target, raw, **fields):
        output = dict(pageType='receipt', money=target['money'], type='Expend', currency='CNY',
                      shopName=target['shopName'], shopItem=target['shopItem'])
        output.update(fields)
        responses.append(json.dumps(output, ensure_ascii=False))
        result = api('/js/analysis?app=' + APP + '&type=OCR&manual=false', MARK + '\n' + raw)
        self.assertEqual(200, result['code'], result)
        bill = result['data']['billInfoModel']
        if MARK in json.dumps(bill):
            self.ids.add(bill['id'])
        self.assertEqual(1, len(requests), 'Must actually call AI before deciding to skip')
        self.assertTrue(result['data']['silent'])
        self.assertEqual(target['id'], bill['id'])
        actual = api('/bill/get?id=' + str(target['id']))['data']
        # Identity aliases may be learned silently; all visible user fields must stay untouched.
        before_details = json.loads(target.get('detailsJson', '{}'))
        after_details = json.loads(actual.get('detailsJson', '{}'))
        before_details.pop('_receiptKeysV1', None)
        after_details.pop('_receiptKeysV1', None)
        self.assertEqual(before_details, after_details)
        self.assertEqual({k:v for k,v in target.items() if k != 'detailsJson' and v != ''},
                         {k:actual[k] for k,v in target.items() if k != 'detailsJson' and v != ''})
        self.assertEqual([], [n for n in api('/bill/notices/pending')['data'] if n['billId'] in self.ids])
        return result

    def test_ai_duplicate_returns_existing_bill_without_new_bill_or_notice(self):
        target = self.target(.31, transactionId='ORDER123456789', transactionNamespace='provider:order')
        self.recognize(target, '订单号码ORDER123456789 已付款0.31',
                       transactionId='ORDER123456789', transactionNamespace=APP)
        self.assertEqual({target['id']}, self.ids)

    def test_ai_complete_payment_facts_without_order_id_are_silent(self):
        target = self.target(.32)
        self.recognize(target, target['shopName'] + ' 拿铁 支付时间2026-10-24 15:28:50 实付0.32',
                       timeText='2026-10-24 15:28:50')
        self.assertEqual({target['id']}, self.ids)

    def test_confirmed_identity_alias_resolves_original_without_overwriting_it(self):
        target = self.target(.33, shopName=MARK + ' edited shop')
        alias = self.target(.33, groupId=target['id'], transactionId='ALIAS123456789',
                            transactionNamespace='provider:order')
        self.assertEqual(target['id'], alias['groupId'])
        self.recognize(target, '订单编号ALIAS123456789 已付款0.33',
                       transactionId='ALIAS123456789', transactionNamespace=APP)

    def test_same_order_with_list_price_is_silent_and_retains_paid_amount(self):
        target = self.target(.34, transactionId='PRICE123456789', transactionNamespace=APP)
        self.recognize(target, '订单号PRICE123456789 原价0.35', money=.35,
                       transactionId='PRICE123456789', transactionNamespace=APP)
        self.assertEqual({target['id']}, self.ids)

    def test_second_identifier_is_remembered_and_matches_without_order_number(self):
        target = self.target(.36, transactionId='MULTI123456789', transactionNamespace=APP)
        self.recognize(target, '订单号MULTI123456789 微信支付交易单号PAY123456789',
                       transactionId='MULTI123456789', transactionNamespace=APP)
        refreshed = api('/bill/get?id=' + str(target['id']))['data']
        self.assertEqual('PAY123456789', json.loads(refreshed['detailsJson'])['_receiptKeysV1']['wechat|payment'])
        requests.clear()
        self.recognize(refreshed, '微信支付交易单号PAY123456789', money=.37)
        self.assertEqual({target['id']}, self.ids)

    def test_multiple_matched_bills_require_review_instead_of_silent_selection(self):
        a = self.target(.38, transactionId='AMBIG123456789', transactionNamespace=APP)
        b = self.target(.39, detailsJson=json.dumps({'_receiptKeysV1': {'wechat|payment':'AMBIGPAY12345'}}))
        responses.append(json.dumps(dict(pageType='receipt',money=.38,type='Expend',shopName=MARK,
                                        transactionId='AMBIG123456789',transactionNamespace=APP)))
        r=api('/js/analysis?app='+APP+'&type=OCR&manual=false',
              MARK+' 订单号AMBIG123456789 微信支付交易单号AMBIGPAY12345 实付0.38')
        self.assertEqual(200,r['code'])
        bill=r['data']['billInfoModel']; self.ids.add(bill['id'])
        self.assertFalse(r['data']['silent'])
        self.assertNotIn(bill['id'],[a['id'],b['id']])
        self.assertEqual('Wait2Edit',bill['state'])
        self.assertEqual(a,api('/bill/get?id='+str(a['id']))['data'])
        self.assertEqual(b,api('/bill/get?id='+str(b['id']))['data'])

    def test_ai_category_uses_local_catalog_and_manual_preview_remains_available(self):
        first=api('/category/list?book=&type=Expend&parent=-1')['data']
        self.assertEqual(22,len(first))
        self.assertEqual(first,api('/category/list?book=&type=Expend&parent=-1')['data'])
        self.assertEqual(6,len(api('/category/list?book=&type=Income&parent=-1')['data']))
        responses.append(json.dumps(dict(pageType='receipt',money=.41,type='Expend',shopName=MARK,shopItem='拿铁',cateName='饮品'),ensure_ascii=False))
        r=api('/js/analysis?app='+APP+'&type=OCR&manual=true&fromAppData=true',MARK+' 拿铁 实付0.41')
        self.assertEqual(200,r['code'],r)
        self.assertEqual('饮品',r['data']['billInfoModel']['cateName'])
        self.assertFalse(r['data']['silent'])
        self.assertEqual(1,len(requests))
        self.assertIn('饮品',json.dumps(requests[0],ensure_ascii=False))

    def test_unique_match_enriches_missing_fields_and_manual_opens_original(self):
        target=self.target(.42,transactionId='ENRICH12345678',transactionNamespace=APP,shopItem='')
        self.recognize(target,'订单号ENRICH12345678 拿铁 实付0.42',shopItem='拿铁',
                       transactionId='ENRICH12345678',transactionNamespace=APP)
        updated=api('/bill/get?id='+str(target['id']))['data']
        self.assertEqual('拿铁',updated['shopItem'])
        requests.clear()
        responses.append(json.dumps(dict(pageType='receipt',money=.42,type='Expend',transactionId='ENRICH12345678',transactionNamespace=APP)))
        r=api('/js/analysis?app='+APP+'&type=OCR&manual=true',MARK+' 订单号ENRICH12345678 实付0.42')
        self.assertFalse(r['data']['silent'])
        self.assertTrue(r['data']['existing'])
        self.assertEqual(target['id'],r['data']['billInfoModel']['id'])
        self.assertEqual({target['id']},self.ids)

    def test_fuzzy_candidates_filter_unrelated_bills_and_keep_nearby_bank_timestamp(self):
        target=self.target(.43,shopName=MARK+' coffee',accountNameFrom='测试卡1234')
        unrelated=self.target(.43,shopName=MARK+' taxi')
        old=self.target(.43,shopName=MARK+' coffee',time=target['time']-301000)
        source=self.target(.43,state='Wait2Edit',flag=1,shopName=MARK+' coffee',
                           time=target['time']+120000,app='test.bank.sms')
        candidates=api('/bill/enrichment/candidates?id='+str(source['id']))['data']
        self.assertEqual([target['id']],[b['id'] for b in candidates])
        # A suggestion does not modify or merge either bill.
        self.assertEqual(target,api('/bill/get?id='+str(target['id']))['data'])
        self.assertEqual(-1,api('/bill/get?id='+str(source['id']))['data']['groupId'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
