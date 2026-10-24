"""Phone integration using a local fake model. No real credentials, no paid requests, no saved bills."""
import json,threading,unittest,sys
from http.server import HTTPServer,BaseHTTPRequestHandler
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
from ocr_enrichment import api
keys=['feature_ai_available','ai_bill_recognition','api_provider','api_key','api_uri','api_model']
responses=[];requests=[]
class Model(BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_POST(self):
  payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])));requests.append(payload)
  item=responses.pop(0)
  if isinstance(item,int):
   self.send_response(item);self.end_headers();self.wfile.write(b'{"error":{"message":"temporary model error"}}');return
  self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
  self.wfile.write(json.dumps({'choices':[{'message':{'content':item}}]}).encode())
class AiPriorityTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.saved={key:api('/setting/get?key='+key,'')['data'] for key in keys}
  cls.http=HTTPServer(('127.0.0.1',52047),Model)
  threading.Thread(target=cls.http.serve_forever,daemon=True).start()
 @classmethod
 def tearDownClass(cls):
  for key,value in cls.saved.items(): api('/setting/set?key='+key,value)
  cls.http.shutdown();cls.http.server_close()
 def setUp(self):
  responses.clear();requests.clear()
  for key,value in dict(feature_ai_available='true',ai_bill_recognition='true',api_provider='ChatGPT',api_key='test-only',api_uri='http://127.0.0.1:52047/v1',api_model='test-only').items(): api('/setting/set?key='+key,value)
 def analyze(self):
  r=api('/js/analysis?app=com.tencent.mm&type=OCR&fromAppData=true','支付成功\n￥0.21\n收款方\n测试商户')
  self.assertEqual(200,r['code'],r);self.assertEqual(0,r['data']['billInfoModel']['id']);return r['data']['billInfoModel']
 def test_ai_wins_before_local_rule(self):
  responses.append('{"pageType":"receipt","money":0.23,"type":"Expend","shopName":"模型商户","extra":{"数量":2}}')
  bill=self.analyze();self.assertEqual(.23,bill['money']);self.assertIn('生成',bill['ruleName']);self.assertIn('数量',bill['detailsJson']);self.assertEqual(1,len(requests))
 def test_two_parse_retries_include_output_and_error_before_local_fallback(self):
  responses.extend(['bad-first','bad-second','bad-third'])
  bill=self.analyze();self.assertEqual(.21,bill['money']);self.assertEqual(3,len(requests));self.assertIn('本地',bill['ruleName'])
  prompt=requests[1]['messages'][-1]['content'];self.assertIn('bad-first',prompt);self.assertIn('解析错误',prompt)
  self.assertIn('bad-second',requests[2]['messages'][-1]['content'])
 def test_model_failure_retries_then_recovers(self):
  responses.extend([503,'{"pageType":"receipt","money":0.24,"type":"Expend"}'])
  self.assertEqual(.24,self.analyze()['money']);self.assertEqual(2,len(requests));self.assertIn('模型调用错误',requests[1]['messages'][-1]['content'])
 def test_missing_configuration_uses_local_without_model_request(self):
  api('/setting/set?key=api_key','')
  self.assertEqual(.21,self.analyze()['money']);self.assertEqual([],requests)
 def test_explicit_nonreceipt_does_not_fall_back_to_a_matching_rule(self):
  responses.append('{"pageType":"other","money":0.21,"type":"Expend"}')
  r=api('/js/analysis?app=com.tencent.mm&type=OCR&fromAppData=true','支付成功\n￥0.21\n收款方\n测试商户')
  self.assertEqual(404,r['code']);self.assertIsNone(r.get('data'));self.assertEqual(1,len(requests))
 def test_whole_page_context_reaches_ai_for_any_app_and_rejection_is_terminal(self):
  for app in ['com.tencent.mm','example.shopping','example.bank']:
   responses.append('{"pageType":"other"}')
   content='支付成功\n￥0.21\n收款方\n测试商户'
   context='输入框:1;图片控件:5;控件:发送、表情\n页面文字:朋友发来一张订单图片'
   body=json.dumps({'text':content,'context':context},ensure_ascii=False)
   r=api('/js/analysis?app='+app+'&type=OCR&fromAppData=true&contextual=true',body)
   self.assertEqual(404,r['code']);self.assertIsNone(r.get('data'))
   self.assertIn(context,requests[-1]['messages'][-1]['content'])
   self.assertIn('先判断整页用途',requests[-1]['messages'][0]['content'])
  self.assertEqual(3,len(requests))
 def test_missing_page_kind_retries_with_classification_error_before_rules(self):
  responses.extend(['{"money":0.23,"type":"Expend"}']*3)
  bill=self.analyze();self.assertEqual(.21,bill['money']);self.assertEqual(3,len(requests))
  self.assertIn('pageType',requests[1]['messages'][-1]['content'])
if __name__=='__main__': unittest.main(verbosity=2)
