"""Native ledger regression with labelled synthetic fixtures; use ANDROID_SERIAL, ADB, port 52046."""
import os,re,time,unittest
from pathlib import Path
from delete_confirmation import adb,tree,node,tap
from ocr_enrichment import api
P='net.ankio.auto:id/'

class OccasionalUiTests(unittest.TestCase):
 def setUp(self):
  self.mark='JL_OCC_UI_'+str(time.time_ns());self.ids=[];self.now=int(time.time()*1000)
 def tearDown(self):
  adb('shell','input','keyevent','4')
  for identity in reversed(self.ids):
   b=api('/bill/get?id='+str(identity)).get('data')
   if b and b.get('remark')==self.mark:api('/bill/remove',{'id':identity})
 def put(self,name,category,amount,offset=0):
  r=api('/bill/put',dict(money=amount,cateName=category,shopName=name,remark=self.mark,type='Expend',
     currency='CNY',bookName='默认账本',state='Edited',time=self.now+offset,app='com.tencent.mm'))
  self.assertEqual(200,r['code']);self.ids.append(r['data']);return r['data']
 def open(self):
  adb('shell','am','force-stop','net.ankio.auto')
  adb('shell','am','start','-W','-n','net.ankio.auto/.ui.activity.MainActivity')
  tap(node(rid=P+'homeFragment'));tap(node(rid=P+'orderFragment'))
  tap(node(rid=P+'action_search'));adb('shell','input','text',self.mark);time.sleep(.6)
  adb('shell','input','keyevent','4');time.sleep(.3)
 def scope(self,label):
  tap(node(rid=P+'frequencyFilter'));tap(node(text=label));time.sleep(.3)
  for attempt in range(5):
   if node(rid=P+'frequencyFilter').get('text')==label.replace('账单',''):break
   if attempt==4:self.fail('Selected scope did not render')
   time.sleep(.2)
 def visible(self):
  return ' '.join(n.get('text','')+' '+n.get('content-desc','') for n in tree().iter('node'))
 def test_occasional_filter_editor_override_and_inclusive_total(self):
  rent=self.put('测试房租','房租',3000);self.put('测试午餐','餐饮',30,1000)
  self.open();description=self.visible()
  self.assertIn('测试午餐',description);self.assertNotIn('测试房租',description)
  self.assertIn('3030.00',description.replace(',',''));self.assertIn('3000.00',description.replace(',',''))
  self.scope('偶发账单');self.assertIn('测试房租',self.visible());self.assertNotIn('测试午餐',self.visible())
  tap(node(text='测试房租'));switch=node(rid=P+'occasional_switch');self.assertEqual('true',switch.get('checked'))
  tap(switch);tap(node(rid=P+'confirm_button'));time.sleep(.4)
  saved=api('/bill/get?id='+str(rent))['data'];self.assertTrue(saved['flag']&8);self.assertFalse(saved['flag']&4)
  self.scope('日常账单');self.assertIn('测试房租',self.visible())
  tap(node(text='测试房租'));self.assertEqual('false',node(rid=P+'occasional_switch').get('checked'))
  adb('shell','input','keyevent','4')
  tap(node(text='测试午餐'));self.assertEqual('false',node(rid=P+'occasional_switch').get('checked'))
  tap(node(rid=P+'occasional_switch'));tap(node(rid=P+'confirm_button'));time.sleep(.4)
  self.scope('偶发账单');self.assertIn('测试午餐',self.visible());self.assertNotIn('测试房租',self.visible())
  self.scope('全部账单')
  self.assertIn('测试午餐',self.visible());self.assertIn('测试房租',self.visible())
  if os.environ.get('LEDGER_SCREENSHOT_DIR'):
   folder=Path(os.environ['LEDGER_SCREENSHOT_DIR']);folder.mkdir(parents=True,exist_ok=True)
   (folder/'occasional-all.png').write_bytes(adb('exec-out','screencap','-p'))
 def test_long_daily_list_scrolls_both_directions_before_top_refresh(self):
  for i in range(45):self.put('滑动测试%02d'%i,'餐饮',1,-i*1000)
  self.open()
  def visible_indices():
   return sorted({int(m.group(1)) for n in tree().iter('node') for m in [re.search(r'滑动测试(\d+)',n.get('text','')+' '+n.get('content-desc',''))] if m})
  first=visible_indices();self.assertIn(0,first)
  area=node(rid=P+'swipe_refresh_layout');x1,y1,x2,y2=map(int,re.findall(r'\d+',area.get('bounds')))
  x=(x1+x2)//2;top=y1+50;bottom=y2-80
  for _ in range(3):adb('shell','input','swipe',str(x),str(bottom),str(x),str(top),'550')
  after=visible_indices();self.assertGreater(min(after),min(first)+4)
  adb('shell','input','swipe',str(x),str(top+20),str(x),str(bottom-20),'650')
  back=visible_indices();self.assertLess(min(back),min(after));self.assertGreater(min(back),0,'A mid-list downward gesture must scroll, not jump to refreshed top')
  for _ in range(12):
   if 0 in visible_indices():break
   adb('shell','input','swipe',str(x),str(top),str(x),str(bottom),'350')
  self.assertIn(0,visible_indices())
  # A record added while at the top appears after a deliberate pull-to-refresh.
  self.put('刷新测试新账单','餐饮',2,2000)
  adb('shell','input','swipe',str(x),str(top),str(x),str(bottom),'700');time.sleep(.6)
  self.assertIn('刷新测试新账单',self.visible())

if __name__=='__main__':unittest.main(verbosity=2)
