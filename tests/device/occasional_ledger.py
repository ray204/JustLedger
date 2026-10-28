"""API regressions for occasional expenses, rent upgrades and ignored investment principal.
Use an isolated test device: forward 52046:52045 and reverse 52047:52047.
Synthetic fixtures only; AI settings restored, created bills/categories/data removed.
"""
import datetime,hashlib,json,threading,time,unittest,urllib.parse
from http.server import HTTPServer
from ai_priority import Model,keys,responses,requests
from ocr_enrichment import api

class OccasionalLedgerTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  for attempt in range(30):
   try: api('/setting/get?key=setting_intro_index',''); break
   except OSError:
    if attempt==29: raise
    time.sleep(.2)
  cls.saved={k:api('/setting/get?key='+k,'')['data'] for k in keys}
  cls.http=HTTPServer(('127.0.0.1',52047),Model);threading.Thread(target=cls.http.serve_forever,daemon=True).start()
 @classmethod
 def tearDownClass(cls):
  for k,v in cls.saved.items():api('/setting/set?key='+k,v)
  cls.http.shutdown();cls.http.server_close()
 def setUp(self):
  self.mark='JL_OCC_'+str(time.time_ns());self.ids=[];requests.clear();responses.clear()
 def tearDown(self):
  for identity in reversed(self.ids):
   bill=api('/bill/get?id='+str(identity)).get('data')
   if bill and bill.get('remark')==self.mark: self.assertEqual(200,api('/bill/remove',{'id':identity})['code'])
  for row in api('/data/list?limit=100&page=1&search='+self.mark)['data']:
   if self.mark in row.get('data',''):api('/data/delete',{'id':row['id']})
 def put(self,**fields):
  b=dict(type='Expend',currency='CNY',money=10,state='Edited',cateName='其他',bookName=self.mark,
     time=int(datetime.datetime(2041,6,12,12).timestamp()*1000),shopName=self.mark,remark=self.mark)
  b.update(fields);r=api('/bill/put',b);self.assertEqual(200,r['code'],r);self.ids.append(r['data']);return r['data']
 def stats(self):return api('/bill/monthly/stats?year=2041&month=6&currency=CNY',{})['data']
 def test_monthly_totals_and_refund_follow_original_after_frequency_edit(self):
  before=self.stats()
  original=self.put(cateName='房租',money=3000,time=int(datetime.datetime(2041,5,12,12).timestamp()*1000))
  self.put(cateName='餐饮',money=100)
  refund=self.put(type='IncomeRefund',money=20,originalBillId=original)
  after=self.stats()
  self.assertAlmostEqual(100,after['expense']-before['expense'])
  self.assertAlmostEqual(80,after['totalExpense']-before['totalExpense'])
  self.assertAlmostEqual(-20,after['occasionalExpense']-before['occasionalExpense'])
  rows=[b for g in api('/bill/list-grouped?year=2041&month=6&keyword='+self.mark)['data'] for b in g['bills']]
  self.assertTrue(next(b for b in rows if b['id']==refund)['flag']&4)
  current=api('/bill/get?id='+str(original))['data'];current['flag']=8
  self.assertEqual(200,api('/bill/put',current)['code'])
  after=self.stats();self.assertAlmostEqual(80,after['expense']-before['expense'])
  self.assertAlmostEqual(0,after['occasionalExpense']-before['occasionalExpense'])
  rows=[b for g in api('/bill/list-grouped?year=2041&month=6&keyword='+self.mark)['data'] for b in g['bills']]
  self.assertTrue(next(b for b in rows if b['id']==refund)['flag']&8)
  self.assertEqual(0,api('/bill/get?id='+str(refund))['data']['flag'],'Read-time inheritance must not overwrite refund storage')
 def test_existing_category_catalog_gets_rent_once_without_restoring_user_deletion(self):
  book=self.mark;md5=hashlib.md5(book.encode()).hexdigest();markers=['justledger.category-defaults.v1.'+md5,'justledger.category-rent.v2.'+md5]
  existing=api('/category/save',dict(name='自定义分类',type='Expend',remoteBookId=book,remoteParentId='-1',remoteId='custom'))['data']
  ids=[existing]
  try:
   api('/setting/set?key='+markers[0],'true')
   path='/category/list?book='+book+'&type=Expend&parent=-1'
   rows=api(path)['data'];self.assertEqual({'自定义分类','房租'},{r['name'] for r in rows})
   rent=next(r['id'] for r in rows if r['name']=='房租');ids.append(rent)
   self.assertEqual(2,len(api(path)['data']))
   api('/category/delete',{'id':rent});ids.remove(rent)
   self.assertEqual(['自定义分类'],[r['name'] for r in api(path)['data']])
  finally:
   for identity in ids:api('/category/delete',{'id':identity})
   for marker in markers:api('/setting/set?key='+marker,'')
 def test_redemption_never_creates_bill_or_notice_with_or_without_ai(self):
  for k,v in dict(feature_ai_available='true',ai_bill_recognition='true',api_provider='ChatGPT',api_key='test-only',api_uri='http://127.0.0.1:52047/v1',api_model='test-only').items():api('/setting/set?key='+k,v)
  notices=api('/bill/notices/pending')['data']
  for enabled in ['true','false']:
   api('/setting/set?key=feature_ai_available',enabled)
   body=json.dumps({'title':'账户变动','text':'您尾号为1234的账户赎回（稳利十四天持有期产品），资金1000元已划入您的账户。'+self.mark,'eventId':self.mark+enabled},ensure_ascii=False)
   r=api('/js/analysis?app=cmb.pb&type=NOTICE',body)
   self.assertEqual(404,r['code'],r);self.assertIn('资产划转',r['msg']);self.assertIsNone(r.get('data'))
  self.assertEqual([],requests,'Clear principal movement should never reach AI')
  self.assertEqual([],api('/bill/list-grouped?keyword='+self.mark)['data'])
  self.assertEqual(notices,api('/bill/notices/pending')['data'])
 def test_ai_receives_principal_rule_and_separate_profit_remains_income(self):
  for k,v in dict(feature_ai_available='true',ai_bill_recognition='true',api_provider='ChatGPT',api_key='test-only',api_uri='http://127.0.0.1:52047/v1',api_model='test-only').items():api('/setting/set?key='+k,v)
  responses.append('{"pageType":"receipt","money":12,"type":"Income","cateName":"理财收益"}')
  r=api('/js/analysis?app=cmb.pb&type=NOTICE&fromAppData=true','理财收益到账12元 '+self.mark)
  self.assertEqual(200,r['code'],r);self.assertEqual('Income',r['data']['billInfoModel']['type']);self.assertEqual(12,r['data']['billInfoModel']['money'])
  self.assertEqual(1,len(requests));self.assertIn('含本金与收益',requests[0]['messages'][0]['content'])
  responses.append('{"pageType":"other"}')
  r=api('/js/analysis?app=cmb.pb&type=NOTICE&fromAppData=true','持仓产品结算，款项已经回到可用账户 '+self.mark)
  self.assertEqual(404,r['code']);self.assertIsNone(r.get('data'));self.assertEqual(2,len(requests))

if __name__=='__main__':unittest.main(verbosity=2)
