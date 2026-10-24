"""Explicit device integration tests. Requires a running debug app and adb forward 52046:52045.
Creates labelled fixtures only; preserves pre-existing bills and deletes created fixtures.
Run: python3 tests/device/ocr_enrichment.py
"""
import hashlib,json,time,unittest,urllib.request
BASE='http://127.0.0.1:52046'
MARKER='JLVERIFY_'+str(time.time_ns())

def api(path,data=None):
 body=None if data is None else (data if isinstance(data,str) else json.dumps(data,ensure_ascii=False)).encode()
 r=urllib.request.Request(BASE+path,data=body,headers={'Content-Type':'application/json; charset=utf-8'})
 with urllib.request.urlopen(r,timeout=20) as response: return json.load(response)

def digest(bill):
 return hashlib.md5(json.dumps(bill,ensure_ascii=False,separators=(',',':'),sort_keys=True).encode()).hexdigest()

class OcrEnrichmentDeviceTests(unittest.TestCase):
 def setUp(self):
  self.ids=set()
  self.rules=set()
 def tearDown(self):
  for id in self.rules: self.assertEqual(200,api('/rule/delete',{'id':id})['code'])
  for id in sorted(self.ids,reverse=True):
   bill=api('/bill/get?id='+str(id)).get('data')
   if bill and (MARKER in json.dumps(bill,ensure_ascii=False)):
    result=api('/bill/remove',{'id':id})
    self.assertEqual(200,result['code'],result)
  rows=api('/data/list?limit=100&page=1&app=com.tencent.mm')['data']
  for row in rows:
   if MARKER in row.get('data',''): api('/data/delete',{'id':row['id']})
  self.assertEqual([], [n for n in api('/bill/notices/pending')['data'] if n['billId'] in self.ids])
 def bill(self,id): return api('/bill/get?id='+str(id))['data']
 def target(self,money,identity=''):
  b={'money':money,'type':'Expend','currency':'CNY','time':int(time.time()*1000),'state':'Edited',
     'shopName':MARKER+' target','remark':'User note','cateName':'用户分类','transactionId':identity,
     'transactionNamespace':'wechat:transaction' if identity else '', 'bookName':'默认账本'}
  r=api('/bill/put',b);self.assertEqual(200,r['code'],r);self.ids.add(r['data']);return self.bill(r['data'])
 def scan(self,money,identity=''):
  text='支付成功\n￥'+format(money,'.2f')+'\n收款方\n'+MARKER+' merchant\n商品说明\n'+MARKER+' goods\n支付方式\n测试银行卡'
  if identity: text+='\n交易单号\n'+identity
  r=api('/js/analysis?app=com.tencent.mm&type=OCR',text)
  self.assertEqual(200,r['code'],r)
  b=r['data']['billInfoModel'];self.ids.add(b['id']);return r['data']
 def confirm(self,source,target):
  return api('/bill/enrichment/confirm',dict(sourceId=source['id'],targetId=target['id'],sourceSnapshot=digest(source),targetSnapshot=digest(target)))
 def notices(self,id): return [x for x in api('/bill/notices/pending')['data'] if x['billId']==id]
 def test_confirmed_update_preserves_amount_user_fields_and_is_idempotent(self):
  target=self.target(.11)
  source=self.scan(.11)['billInfoModel']
  self.assertEqual('Wait2Edit',source['state']);self.assertEqual(1,source['flag']&1)
  candidates=api('/bill/enrichment/candidates?id='+str(source['id']))['data']
  self.assertIn(target['id'],[x['id'] for x in candidates])
  r=self.confirm(source,target);self.assertEqual(200,r['code'],r)
  updated=self.bill(target['id'])
  self.assertEqual(target['money'],updated['money']);self.assertEqual(target['time'],updated['time'])
  self.assertEqual(target['remark'],updated['remark']);self.assertEqual(target['cateName'],updated['cateName'])
  self.assertEqual(MARKER+' goods',updated['shopItem']);self.assertEqual('测试银行卡',updated['accountNameFrom'])
  self.assertEqual(target['id'],self.bill(source['id'])['groupId'])
  self.assertEqual(1,len(self.notices(target['id'])))
  self.assertEqual('UPDATED',self.notices(target['id'])[0]['kind'])
  self.assertEqual(200,self.confirm(source,target)['code'])
  self.assertEqual(1,len(self.notices(target['id'])))
  rescanned=self.scan(.11)['billInfoModel']
  self.assertEqual(200,self.confirm(rescanned,self.bill(target['id']))['code'])
  self.assertEqual(1,len(self.notices(target['id'])))
  groups=api('/bill/list-grouped')['data']
  self.assertEqual([target['id']],[b['id'] for g in groups for b in g['bills'] if b['id'] in self.ids and b['state']!='Wait2Edit' and not b['flag']&1])
 def test_stale_snapshot_rejected_without_partial_update(self):
  target=self.target(.12);source=self.scan(.12)['billInfoModel']
  changed=dict(target,remark='New user note')
  self.assertEqual(200,api('/bill/put',changed)['code'])
  self.assertEqual(409,self.confirm(source,target)['code'])
  self.assertEqual('',self.bill(target['id'])['shopItem'])
  self.assertEqual(-1,self.bill(source['id'])['groupId'])
  self.assertEqual([],self.notices(target['id']))
 def test_strong_identity_auto_enriches_and_repeat_scan_does_not_add_expense(self):
  identity='420'+str(time.time_ns());target=self.target(.13,identity)
  for _ in range(2):
   result=self.scan(.13,identity)
   self.assertEqual(target['id'],result['parentInfoModel']['id'])
   self.assertEqual(target['id'],self.bill(result['billInfoModel']['id'])['groupId'])
  self.assertEqual(MARKER+' goods',self.bill(target['id'])['shopItem'])
  self.assertEqual(1,len(self.notices(target['id'])))
  groups=api('/bill/list-grouped')['data']
  counted=[b for g in groups for b in g['bills'] if b['id'] in self.ids and b['state']!='Wait2Edit' and not b['flag']&1]
  self.assertEqual([target['id']],[b['id'] for b in counted])
 def test_conflicting_order_ids_never_merge_even_when_amount_matches(self):
  target=self.target(.14,'4200000000000001');source=self.scan(.14,'4200000000000002')['billInfoModel']
  self.assertEqual(-1,source['groupId'])
  candidates=api('/bill/enrichment/candidates?id='+str(source['id']))['data']
  self.assertNotIn(target['id'],[x['id'] for x in candidates])
  self.assertEqual(409,self.confirm(source,target)['code'])
  self.assertEqual('',self.bill(target['id'])['shopItem'])
 def test_ai_attributed_identity_requires_confirmation_before_enrichment(self):
  # Model-output fixture through the real parser, not an external AI request.
  identity='4200000000000016';target=self.target(.16,identity)
  result={'money':.16,'type':'Expend','shopName':MARKER+' AI merchant','shopItem':MARKER+' AI goods',
          'transactionId':identity,'transactionNamespace':'wechat:transaction'}
  js='var rule_990099={get:function(data){if(data.indexOf('+json.dumps(MARKER+'_AI')+')<0)return null;return '+json.dumps(result)+';}};'
  r=api('/rule/add',dict(app='com.tencent.mm',type='OCR',name=MARKER+' 生成',systemRuleName='rule_990099',creator='user',enabled=True,autoRecord=True,js=js,struct=''))
  self.assertEqual(200,r['code']);self.rules.add(r['data'])
  r=api('/js/analysis?app=com.tencent.mm&type=OCR',MARKER+'_AI')
  self.assertEqual(200,r['code'],r);source=r['data']['billInfoModel'];self.ids.add(source['id'])
  self.assertIsNone(r['data'].get('parentInfoModel'))
  self.assertEqual('Wait2Edit',source['state']);self.assertEqual(1,source['flag']&1)
  self.assertEqual('',self.bill(target['id'])['shopItem'])
  self.assertEqual([],self.notices(target['id']))

if __name__=='__main__': unittest.main(verbosity=2)
