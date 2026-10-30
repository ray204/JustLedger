"""Real in-place update test on a disposable emulator, never a personal phone.
Install the unpublished lower-version client containing the updater, finish onboarding,
forward 52046:52045; set TARGET_VERSION to the existing public formal release.
Downloads from the real JustLedger Release and approves the system installer on this emulator.
Verifies cancel, restart/resume, permission refusal/retry and preservation of a labelled bill.
"""
import os,re,time,unittest,urllib.request
from delete_confirmation import adb,tree,node,tap
from ocr_enrichment import api
P='net.ankio.auto:id/'
TARGET=os.environ.get('TARGET_VERSION','0.1.2')

def wait_node(predicate, timeout=60):
 end=time.monotonic()+timeout
 last=[]
 while time.monotonic()<end:
  nodes=list(tree().iter('node'));last=[n.get('text','') for n in nodes if n.get('text')]
  found=next((n for n in nodes if predicate(n)),None)
  if found is not None:return found
  time.sleep(.2)
 raise AssertionError('Expected UI not reached; visible labels: '+repr(last))

def start():
 adb('shell','am','start','-W','-n','net.ankio.auto/.ui.activity.MainActivity')
 # Launcher resumes the previous page. Return from this test's update/settings screens.
 for _ in range(5):
  nodes=list(tree().iter('node'))
  if any(n.get('resource-id')==P+'homeFragment' for n in nodes):return
  if any(n.get('package') in ('net.ankio.auto','com.android.settings','com.google.android.packageinstaller','com.android.packageinstaller') for n in nodes):
   adb('shell','input','keyevent','4')
  else:adb('shell','am','start','-W','-n','net.ankio.auto/.ui.activity.MainActivity')
 wait_node(lambda n:n.get('resource-id')==P+'homeFragment')

def open_update():
 tap(node(rid=P+'settingFragment'));tap(node(rid=P+'setting_about'))
 tap(wait_node(lambda n:n.get('text')=='检查更新'))

def action(text):return wait_node(lambda n:n.get('resource-id')==P+'updateAction' and text in n.get('text',''))

class AppUpdateTests(unittest.TestCase):
 def test_download_resume_permission_retry_and_system_upgrade_preserve_bill(self):
  self.assertTrue(os.environ.get('ANDROID_SERIAL','').startswith('emulator-'),'Use a disposable emulator only')
  start();mark='JL_UPDATE_'+str(time.time_ns());bill_id=None
  try:
   r=api('/bill/put',dict(type='Expend',money=.23,cateName='餐饮',state='Edited',currency='CNY',bookName='默认账本',time=int(time.time()*1000),shopName=mark,remark=mark))
   self.assertEqual(200,r['code']);bill_id=r['data'];before=api('/bill/get?id='+str(bill_id))['data']
   open_update()
   tap(wait_node(lambda n:n.get('resource-id')==P+'updateCancel' and n.get('text')=='重新检查'))
   button=action('一键更新')
   self.assertEqual('JustLedger '+TARGET,node(rid=P+'updateTitle').get('text'))
   print('PHASE available',flush=True)
   tap(button)
   cancel=wait_node(lambda n:n.get('resource-id')==P+'updateCancel' and n.get('text')=='取消下载')
   tap(cancel);action('一键更新');self.assertEqual('下载已取消',node(rid=P+'updateStatus').get('text'))
   print('PHASE cancel verified',flush=True)
   tap(action('一键更新'));action('正在下载')
   adb('shell','am','force-stop','net.ankio.auto');start();open_update()
   ready=action('安装更新');self.assertEqual('下载完成，已通过校验',node(rid=P+'updateStatus').get('text'))
   print('PHASE restored ready',flush=True)
   tap(ready)
   # First refuse installation permission; the app must remain ready, not loop or redownload.
   wait_node(lambda n:'Allow from this source' in n.get('text','') or '允许来自此来源' in n.get('text','') or n.get('resource-id')=='android:id/switch_widget')
   adb('shell','input','keyevent','4');action('安装更新')
   self.assertIn('未允许安装',node(rid=P+'updateStatus').get('text'))
   tap(action('安装更新'))
   switch=wait_node(lambda n:n.get('checkable')=='true' and n.get('package')=='com.android.settings')
   self.assertEqual('false',switch.get('checked'));tap(switch);adb('shell','input','keyevent','4')
   wait_node(lambda n:n.get('text') in ('Update','更新','Install','安装'))
   adb('shell','input','keyevent','4');action('安装更新')
   print('PHASE permission and installer cancellation verified',flush=True)
   tap(action('安装更新'))
   install=wait_node(lambda n:n.get('text') in ('Update','更新','Install','安装'))
   tap(install)
   for _ in range(90):
    info=adb('shell','dumpsys','package','net.ankio.auto').decode()
    if re.search(r'versionName='+re.escape(TARGET)+r'\s',info):break
    time.sleep(.5)
   else:self.fail('System installation did not reach target version')
   start()
   for attempt in range(40):
    try:after=api('/bill/get?id='+str(bill_id))['data'];break
    except OSError:
     if attempt==39:raise
     time.sleep(.25)
   for key in ('id','money','shopName','remark','cateName','time','state'):
    self.assertEqual(before[key],after[key],key)
   print('PHASE installed '+TARGET+' and preserved bill',flush=True)
  finally:
   if bill_id:
    adb('shell','am','start','-W','-n','net.ankio.auto/.ui.activity.MainActivity')
    bill=api('/bill/get?id='+str(bill_id)).get('data')
    if bill and bill.get('remark')==mark:api('/bill/remove',{'id':bill_id})

if __name__=='__main__':unittest.main(verbosity=2)
