"""Monthly income/balance privacy on an isolated emulator with completed onboarding.
Requires ADB, ANDROID_SERIAL and adb forward tcp:52046 tcp:52045.
Creates labelled income/expense fixtures; restores expense privacy and removes its fixtures.
"""
import time,unittest
from ocr_enrichment import api
from delete_confirmation import adb,tree,node,tap
P='net.ankio.auto:id/'

class MonthlyPrivacyTests(unittest.TestCase):
 def setUp(self):
  self.ids=[];self.mark='JL_PRIVACY_'+str(time.time_ns())
  self.oldHidden=None
  self.addCleanup(self.cleanup)
  self.open(restart=True)
  self.oldHidden=node(rid=P+'privacyToggle').get('content-desc')=='显示支出金额'
  if self.oldHidden:self.click('privacyToggle')
  for kind,category,amount in [('Income','工资',8000),('Expend','餐饮',332),('Expend','房租',3000)]:
   r=api('/bill/put',dict(type=kind,cateName=category,money=amount,state='Edited',currency='CNY',bookName='默认账本',time=int(time.time()*1000),shopName=self.mark,remark=self.mark))
   self.assertEqual(200,r['code']);self.ids.append(r['data'])
  self.open(restart=True)
 def cleanup(self):
  for identity in self.ids:
   b=api('/bill/get?id='+str(identity)).get('data')
   if b and b.get('remark')==self.mark:api('/bill/remove',{'id':identity})
  if self.oldHidden is not None:
   self.open()
   hidden=node(rid=P+'privacyToggle').get('content-desc')=='显示支出金额'
   if hidden!=self.oldHidden:self.click('privacyToggle')
 def open(self,restart=False):
  if restart:adb('shell','am','force-stop','net.ankio.auto')
  adb('shell','am','start','-W','-n','net.ankio.auto/.ui.activity.MainActivity')
  tap(node(rid=P+'homeFragment'))
  for attempt in range(15):
   if node(rid=P+'tvExpenseAmount').get('text')!='—':break
   if attempt==14:self.fail('Monthly totals did not load')
   time.sleep(.2)
 def snapshot(self):
  return {n.get('resource-id','').removeprefix(P):n for n in tree().iter('node') if n.get('resource-id','').startswith(P)}
 def assertAmounts(self,income,balance,expense='332.00'):
  nodes=self.snapshot()
  for key,value in [('tvIncomeAmount',income),('tvBalanceAmount',balance),('tvExpenseAmount',expense)]:
   self.assertEqual(value,nodes[key].get('text').replace(',',''),key)
 def click(self,name):tap(node(rid=P+name))
 def reveal(self):
  self.click('incomePrivacyToggle');self.click('balancePrivacyToggle')
  self.assertAmounts('8000.00','4668.00')
 def test_defaults_independent_eyes_and_expense_toggle(self):
  self.assertAmounts('••••','••••')
  self.assertEqual('显示月收入金额',node(rid=P+'incomePrivacyToggle').get('content-desc'))
  self.click('incomePrivacyToggle');self.assertAmounts('8000.00','••••')
  self.assertEqual('隐藏月收入金额',node(rid=P+'incomePrivacyToggle').get('content-desc'))
  self.click('balancePrivacyToggle');self.assertAmounts('8000.00','4668.00')
  self.click('incomePrivacyToggle');self.assertAmounts('••••','4668.00')
  self.click('privacyToggle');self.assertAmounts('••••','4668.00','••••')
  self.assertNotIn('3332',node(rid=P+'totalExpenseHint').get('text').replace(',',''))
  self.click('balancePrivacyToggle');self.assertAmounts('••••','••••','••••')
  self.click('privacyToggle');self.assertAmounts('••••','••••')
 def test_tab_background_and_restart_reset_secondary_amounts(self):
  self.reveal();self.click('orderFragment');self.click('homeFragment');self.assertAmounts('••••','••••')
  self.reveal();adb('shell','input','keyevent','3');self.open();self.assertAmounts('••••','••••')
  self.reveal();self.open(restart=True);self.assertAmounts('••••','••••')

if __name__=='__main__':unittest.main(verbosity=2)
