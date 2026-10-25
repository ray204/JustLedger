"""Read-only refund suggestions against labelled fixtures; uses fake AI and restores settings.
Requires forward 52046:52045 and reverse 52047:52047 on the selected test device.
"""
import json,time,threading,unittest
from http.server import HTTPServer
from ai_priority import Model,keys,responses,requests
from ocr_enrichment import api

class RefundMatchingTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.saved={k:api('/setting/get?key='+k,'')['data'] for k in keys}
  cls.http=HTTPServer(('127.0.0.1',52047),Model);threading.Thread(target=cls.http.serve_forever,daemon=True).start()
 @classmethod
 def tearDownClass(cls):
  for k,v in cls.saved.items():api('/setting/set?key='+k,v)
  cls.http.shutdown();cls.http.server_close()
 def setUp(self):
  self.ids=[];self.mark='JL_REFUND_MATCH_'+str(time.time_ns());responses.clear();requests.clear()
  for k,v in dict(feature_ai_available='true',ai_bill_recognition='true',api_provider='ChatGPT',api_key='test-only',api_uri='http://127.0.0.1:52047/v1',api_model='test-only').items():api('/setting/set?key='+k,v)
  self.refund=dict(id=0,type='IncomeRefund',money=30,currency='CNY',bookName=self.mark,time=int(time.time()*1000),shopName='街角咖啡',shopItem='咖啡拿铁',app='test.pay',transactionNamespace='test.pay',channel='测试支付')
 def tearDown(self):
  for i in reversed(self.ids):
   b=api('/bill/get?id='+str(i)).get('data')
   if b and b.get('bookName')==self.mark:self.assertEqual(200,api('/bill/remove',{'id':i})['code'])
 def payment(self,**changes):
  b=dict(self.refund,type='Expend',money=100,state='Edited',time=self.refund['time']-3600000)
  b.update(changes);r=api('/bill/put',b);self.assertEqual(200,r['code']);self.ids.append(r['data']);return api('/bill/get?id='+str(r['data']))['data']
 def match(self,refund=None):
  r=api('/bill/refund/match',refund or self.refund);self.assertEqual(200,r['code']);return r.get('data')
 def test_no_ai_configuration_uses_local_match_without_persisting_refund(self):
  api('/setting/set?key=api_key','');b=self.payment()
  self.assertEqual(b['id'],self.match()['id']);self.assertEqual([],requests)
  self.assertEqual(b,api('/bill/get?id='+str(b['id']))['data'])
 def test_original_id_matches_partial_refund_but_ambiguous_records_require_manual_choice(self):
  b=self.payment(transactionId='PAYMENT12345678')
  r=dict(self.refund,originalTransactionId=b['transactionId'],shopName='美团',shopItem='')
  self.assertEqual(b['id'],self.match(r)['id'])
  self.payment(transactionId='PAYMENT12345678')
  self.assertIsNone(self.match(r));self.assertEqual([],requests)
 def test_original_payment_facts_match_bank_entry_without_order_number(self):
  api('/setting/set?key=api_key','')
  b=self.payment(app='test.bank',transactionNamespace='test.bank',channel='财付通-微信支付',shopName='收款商户',shopItem='',accountNameFrom='')
  source=dict(self.refund,app='com.tencent.mm',transactionNamespace='com.tencent.mm',channel='微信',shopName='美团',shopItem='',
    detailsJson=json.dumps({'原支付金额':'100.00','支付时间':str(b['time']+27000)}))
  self.assertEqual(b['id'],self.match(source)['id']);self.assertEqual([],requests)
  self.assertIsNone(self.match(dict(source,detailsJson='{}')))
 def test_ai_receives_only_filtered_candidates_and_selects_an_existing_original(self):
  b=self.payment(shopName='街角咖啡旗舰店',shopItem='冰拿铁')
  self.payment(currency='USD');self.payment(time=self.refund['time']-91*86400000)
  responses.append('```json\n{"index":1}\n```')
  self.assertEqual(b['id'],self.match()['id']);self.assertEqual(1,len(requests))
  data=json.loads(requests[0]['messages'][-1]['content'])
  self.assertEqual(1,len(data['候选']));self.assertEqual(100,data['候选'][0]['可退金额'])
 def test_model_retries_then_keeps_manual_choice_and_none_does_not_retry(self):
  self.payment(shopName='街角咖啡旗舰店',shopItem='冰拿铁')
  responses.extend(['broken','{"index":99}','bad-again'])
  self.assertIsNone(self.match());self.assertEqual(3,len(requests))
  self.assertIn('broken',requests[1]['messages'][-1]['content']);self.assertIn('错误',requests[1]['messages'][-1]['content'])
  requests.clear();responses.append('{"index":0}')
  self.assertIsNone(self.match());self.assertEqual(1,len(requests))
 def test_exhausted_original_is_excluded_and_manual_selection_is_respected(self):
  a=self.payment();b=self.payment(shopName='另一家店')
  paid=api('/bill/put',dict(self.refund,originalBillId=a['id'],money=100,state='Edited'))
  self.assertEqual(200,paid['code']);self.ids.append(paid['data'])
  responses.append('{"index":0}')
  self.assertIsNone(self.match())
  self.assertEqual(b['id'],self.match(dict(self.refund,originalBillId=b['id']))['id'])

if __name__=='__main__':unittest.main(verbosity=2)
