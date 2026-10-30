"""Current-version, offline retry and automatic-check preference on a disposable emulator."""
import os,time,unittest
from delete_confirmation import adb,tree,node,tap
from app_update import start,wait_node,P

def about():
 tap(node(rid=P+'settingFragment'));tap(node(rid=P+'setting_about'))
 wait_node(lambda n:n.get('text')=='自动检查更新')

def auto_switch():return next(n for n in tree().iter('node') if n.get('checkable')=='true')

class AppUpdateCurrentTests(unittest.TestCase):
 def test_current_offline_retry_and_auto_check_preference_persistence(self):
  self.assertTrue(os.environ.get('ANDROID_SERIAL','').startswith('emulator-'))
  start();about();initial=auto_switch().get('checked')
  try:
   tap(node(text='自动检查更新'));expected='false' if initial=='true' else 'true'
   self.assertEqual(expected,auto_switch().get('checked'))
   adb('shell','am','force-stop','net.ankio.auto');start();about()
   self.assertEqual(expected,auto_switch().get('checked'))
  finally:
   if auto_switch().get('checked')!=initial:tap(node(text='自动检查更新'))
  tap(node(text='检查更新'))
  wait_node(lambda n:n.get('resource-id')==P+'updateTitle' and n.get('text')=='已是最新版本')
  self.assertEqual('已是最新正式版',node(rid=P+'updateStatus').get('text'))
  wifi=adb('shell','settings','get','global','wifi_on').decode().strip()
  data=adb('shell','settings','get','global','mobile_data').decode().strip()
  try:
   adb('shell','svc','wifi','disable');adb('shell','svc','data','disable');time.sleep(1)
   tap(node(rid=P+'updateAction'))
   wait_node(lambda n:n.get('resource-id')==P+'updateTitle' and n.get('text')=='检查未完成')
   self.assertIn('无法连接更新服务',node(rid=P+'updateStatus').get('text'))
   self.assertEqual('重新检查',node(rid=P+'updateAction').get('text'))
  finally:
   adb('shell','svc','wifi','enable' if wifi=='1' else 'disable')
   adb('shell','svc','data','enable' if data=='1' else 'disable')
  # Wait for the emulator to reconnect before explicitly retrying the failed check.
  for attempt in range(20):
   state=adb('shell','dumpsys','connectivity').decode()
   if 'VALIDATED' in state:break
   time.sleep(.5)
  tap(node(rid=P+'updateAction'))
  wait_node(lambda n:n.get('resource-id')==P+'updateTitle' and n.get('text')=='已是最新版本')

if __name__=='__main__':unittest.main(verbosity=2)
