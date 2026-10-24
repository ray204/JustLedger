"""Verify destructive UI confirmation on an attached test phone using a labelled fixture.
Requires adb on PATH (or ADB env path) and adb forward tcp:52046 tcp:52045.
Temporarily sets the legacy confirmation preference false; restores it and removes only its fixture.
"""
import os,re,subprocess,time,unittest,xml.etree.ElementTree as ET
from ocr_enrichment import api
ADB=os.environ.get('ADB','adb')

def adb(*args):
 return subprocess.check_output([ADB,*args],stderr=subprocess.DEVNULL)

def tree():
 adb('shell','uiautomator','dump','/sdcard/justledger-test-ui.xml')
 raw=adb('exec-out','cat','/sdcard/justledger-test-ui.xml')
 adb('shell','rm','/sdcard/justledger-test-ui.xml')
 return ET.fromstring(raw)

def node(text=None,rid=None):
 for n in tree().iter('node'):
  if (text is None or n.get('text')==text) and (rid is None or n.get('resource-id')==rid):return n
 raise AssertionError('Expected UI element is missing')

def tap(n,long=False):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));x=str((x1+x2)//2);y=str((y1+y2)//2)
 adb('shell','input',*(['swipe',x,y,x,y,'900'] if long else ['tap',x,y]))

class DeleteConfirmationTests(unittest.TestCase):
 def test_long_press_cancel_back_and_confirm_even_when_legacy_setting_is_off(self):
  key='confirm_delete_bill';old=api('/setting/get?key='+key,'')['data'];bill_id=None
  mark='JL_DELETE_CONFIRM_'+str(time.time_ns())
  try:
   api('/setting/set?key='+key,'false')
   result=api('/bill/put',{'money':.01,'type':'Expend','currency':'CNY','time':int(time.time()*1000),
      'state':'Edited','shopName':mark,'cateName':'其他','bookName':'默认账本','remark':'Deletion confirmation UI test fixture'})
   self.assertEqual(200,result['code']);bill_id=result['data']
   adb('shell','am','start','-n','net.ankio.auto/.ui.activity.MainActivity')
   tap(node(rid='net.ankio.auto:id/homeFragment'));tap(node(rid='net.ankio.auto:id/orderFragment'))
   # Long press itself must not delete; cancel and Back must also preserve the saved record.
   for action in ['取消','back']:
    tap(node(text=mark),long=True);node(text='删除确认')
    self.assertEqual(mark,api('/bill/get?id='+str(bill_id))['data']['shopName'])
    if action=='back':adb('shell','input','keyevent','4')
    else:tap(node(rid='net.ankio.auto:id/negativeButton'))
    self.assertEqual(mark,api('/bill/get?id='+str(bill_id))['data']['shopName'])
   tap(node(text=mark),long=True);node(text='删除确认');tap(node(rid='net.ankio.auto:id/positiveButton'))
   for _ in range(20):
    if not api('/bill/get?id='+str(bill_id)).get('data'):break
    time.sleep(.1)
   self.assertIsNone(api('/bill/get?id='+str(bill_id)).get('data'))
  finally:
   if bill_id:
    b=api('/bill/get?id='+str(bill_id)).get('data')
    if b and b.get('shopName')==mark:api('/bill/remove',{'id':bill_id})
   api('/setting/set?key='+key,old)

if __name__=='__main__':unittest.main(verbosity=2)
