"""UI regression for refund selection. Requires a test device and local API forwarding.
Use ANDROID_SERIAL to select the device, ADB for the adb executable, and forward
that device's port 52045 to host 52046. Creates labelled fixtures only.
"""
import time,unittest
from delete_confirmation import adb,tree,node,tap
from ocr_enrichment import api
P='net.ankio.auto:id/'

class RefundPickerTests(unittest.TestCase):
 def test_refund_picker_multiple_orders_cancel_then_save_partial_refund(self):
  marker='JL_REFUND_'+str(time.time_ns());ids=[];now=int(time.time()*1000)
  def create(name,amount,kind,when):
   result=api('/bill/put',dict(money=amount,type=kind,currency='CNY',time=when,
    state='Edited',shopName=marker+name,cateName='其他',bookName='默认账本',remark='Refund picker test fixture'))
   self.assertEqual(200,result['code']);ids.append(result['data']);return result['data']
  try:
   original=create('_original',100,'Expend',now-2000)
   other=create('_other',80,'Expend',now-1000)
   draft=create('_refund',30,'Income',now)
   before={i:api('/bill/get?id='+str(i))['data'] for i in [original,other]}
   adb('shell','am','start','-n','net.ankio.auto/.ui.activity.MainActivity')
   tap(node(rid=P+'homeFragment'));tap(node(rid=P+'orderFragment'))
   tap(node(text=marker+'_refund'));tap(node(rid=P+'chip_refund'))
   tap(node(rid=P+'choose_bill_button'))
   node(text='选择退款原账单')
   rows=[n for n in tree().iter('node') if marker in n.get('text','')]
   self.assertEqual(2,len(rows),'Both original payment rows must be visible alongside the message')
   tap(node(rid=P+'negativeButton'))
   self.assertEqual(0,api('/bill/get?id='+str(draft))['data']['originalBillId'])
   self.assertFalse(any(n.get('text','').startswith('已关联本地账单') for n in tree().iter('node')))
   tap(node(rid=P+'choose_bill_button'))
   chosen=next(n for n in tree().iter('node') if marker+'_original' in n.get('text',''))
   tap(chosen);self.assertIn('100',node(rid=P+'choose_bill_button').get('text',''))
   tap(node(rid=P+'confirm_button'))
   for _ in range(30):
    saved=api('/bill/get?id='+str(draft))['data']
    if saved['type']=='IncomeRefund':break
    time.sleep(.1)
   self.assertEqual('IncomeRefund',saved['type']);self.assertEqual(original,saved['originalBillId'])
   self.assertEqual(30,saved['money']);self.assertEqual('Edited',saved['state'])
   for i,b in before.items():self.assertEqual(b,api('/bill/get?id='+str(i))['data'])
   # Editing the same refund must exclude its current amount from the cumulative limit.
   self.assertEqual(200,api('/bill/put',saved)['code'])
   excessive=dict(saved,money=100.01)
   self.assertNotEqual(200,api('/bill/put',excessive)['code'])
   self.assertEqual(30,api('/bill/get?id='+str(draft))['data']['money'])
  finally:
   for i in reversed(ids):
    b=api('/bill/get?id='+str(i)).get('data')
    if b and b.get('shopName','').startswith(marker):
     self.assertEqual(200,api('/bill/remove',{'id':i})['code'])

 def test_detected_original_is_preselected_and_manual_override_is_preserved(self):
  marker='JL_AUTO_REFUND_'+str(time.time_ns());ids=[];now=int(time.time()*1000)
  def put(name,item,money,kind,offset):
   r=api('/bill/put',dict(money=money,type=kind,currency='CNY',bookName='默认账本',state='Edited',
      shopName=name,shopItem=item,time=now+offset,cateName='其他'))
   self.assertEqual(200,r['code']);ids.append(r['data']);return r['data']
  try:
   original=put(marker+'_shop','拿铁',100,'Expend',-2000)
   alternative=put(marker+'_other','蛋糕',80,'Expend',-1000)
   draft=put(marker+'_shop','拿铁',30,'Income',0)
   adb('shell','am','start','-n','net.ankio.auto/.ui.activity.MainActivity')
   tap(node(rid=P+'homeFragment'));tap(node(rid=P+'orderFragment'))
   tap(node(text=marker+'_shop'));tap(node(rid=P+'chip_refund'))
   # No manual picker selection: the matching original must appear as the default.
   for _ in range(5):
    selected=node(rid=P+'choose_bill_button').get('text','')
    if '100' in selected:break
   self.assertIn('100',selected)
   self.assertEqual('Income',api('/bill/get?id='+str(draft))['data']['type'],'Suggestion must not persist an edit')
   tap(node(rid=P+'choose_bill_button'))
   selected=next(n for n in tree().iter('node') if marker+'_other' in n.get('text',''))
   tap(selected);self.assertIn('80',node(rid=P+'choose_bill_button').get('text',''))
   tap(node(rid=P+'confirm_button'))
   for _ in range(20):
    saved=api('/bill/get?id='+str(draft))['data']
    if saved['type']=='IncomeRefund':break
    time.sleep(.1)
   self.assertEqual(alternative,saved['originalBillId']);self.assertEqual(30,saved['money'])
  finally:
   for i in reversed(ids):
    b=api('/bill/get?id='+str(i)).get('data')
    if b and b.get('shopName','').startswith(marker):self.assertEqual(200,api('/bill/remove',{'id':i})['code'])

if __name__=='__main__':unittest.main(verbosity=2)
