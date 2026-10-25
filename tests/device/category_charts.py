"""Category filters and donut interaction on an isolated Android test device.
Requires completed onboarding, adb forward tcp:52046 tcp:52045, ADB and ANDROID_SERIAL.
Creates labelled fixtures, preserves existing bills, and removes only its own fixtures.
"""
import os, re, time, unittest
from ocr_enrichment import api
from delete_confirmation import adb, tree, node, tap

class CategoryChartsTests(unittest.TestCase):
    def setUp(self):
        self.mark = 'JL_CHART_' + str(time.time_ns())
        self.ids = []
        adb('shell', 'am', 'force-stop', 'net.ankio.auto')
        adb('shell', 'am', 'start', '-n', 'net.ankio.auto/.ui.activity.MainActivity')
        for attempt in range(20):
            try:
                api('/setting/get?key=setting_intro_index', ''); break
            except OSError:
                if attempt == 19: raise
                time.sleep(.2)
        for category, amount, app in [('餐饮', 60, 'com.tencent.mm'), ('餐饮', 20, 'com.eg.android.alipaygphone'),
                                      ('交通', 30, 'com.tencent.mm'), ('购物', 15, 'com.tencent.mm'),
                                      ('服务', 10, 'com.tencent.mm'), ('娱乐', 5, 'com.tencent.mm'), ('医疗', 2, 'com.tencent.mm')]:
            result = api('/bill/put', dict(cateName=category, money=amount, app=app, type='Expend', currency='CNY',
                state='Edited', shopName=self.mark + category, remark=self.mark, bookName='默认账本', time=int(time.time()*1000)))
            self.assertEqual(200, result['code']); self.ids.append(result['data'])
        adb('shell', 'am', 'start', '-n', 'net.ankio.auto/.ui.activity.MainActivity')
        tap(node(rid='net.ankio.auto:id/homeFragment')); tap(node(rid='net.ankio.auto:id/orderFragment'))
        # Use search to isolate fixture rows from any existing records on the test device.
        tap(node(rid='net.ankio.auto:id/action_search'))
        adb('shell', 'input', 'text', self.mark)
        time.sleep(.7)
        adb('shell', 'input', 'keyevent', '4'); time.sleep(.3)
    def tearDown(self):
        for identity in self.ids:
            bill = api('/bill/get?id='+str(identity)).get('data')
            if bill and bill.get('remark') == self.mark: api('/bill/remove', {'id': identity})
    def choose(self, rid, label):
        tap(node(rid='net.ankio.auto:id/'+rid)); tap(node(text=label)); time.sleep(.4)
    def description(self):
        return node(rid='net.ankio.auto:id/ledger_chart_plot').get('content-desc')
    def test_category_and_channel_dropdowns_filter_list_totals_and_reset(self):
        self.choose('categoryFilter', '餐饮')
        self.assertIn('80.00', self.description())
        labels=' '.join(n.get('text', '')+' '+n.get('content-desc', '') for n in tree().iter('node'))
        self.assertIn(self.mark+'餐饮', labels); self.assertNotIn(self.mark+'交通', labels)
        self.choose('channelFilter', '微信')
        self.assertIn('60.00', self.description())
        self.choose('categoryFilter', '医疗')
        self.assertIn('2.00', self.description())
        self.choose('channelFilter', '支付宝')
        self.assertIn('共 0.00', self.description())
        self.choose('categoryFilter', '全部分类')
        self.assertIn('20.00', self.description())
        self.choose('channelFilter', '全部渠道')
        self.assertIn('142.00', self.description())
    def test_donut_tap_highlights_category_reports_amount_and_second_tap_restores_total(self):
        chart=node(rid='net.ankio.auto:id/ledger_chart_plot')
        x1,y1,x2,y2=map(int,re.findall(r'\d+',chart.get('bounds')))
        cx=(x1+x2)/2;cy=(y1+y2)/2
        # First slice covers 80/142, so its right-hand ring belongs to dining.
        r=min((y2-y1)/184*55,(x2-x1)*.185)
        x=int(cx+r*.8);y=int(cy)
        adb('shell','input','tap',str(x),str(y));time.sleep(.3)
        description=self.description()
        self.assertIn('已选 餐饮',description);self.assertIn('80.00',description);self.assertIn('56.3%',description)
        folder=os.environ.get('CHART_SCREENSHOT_DIR')
        if folder:
            os.makedirs(folder,exist_ok=True)
            with open(os.path.join(folder,'category-selected.png'),'wb') as f:f.write(adb('exec-out','screencap','-p'))
        adb('shell','input','tap',str(x),str(y));time.sleep(.3)
        self.assertIn('共 142.00',self.description());self.assertNotIn('已选',self.description())
        adb('shell','input','tap',str(x),str(y));time.sleep(.3)
        adb('shell','input','tap',str(int(cx)),str(int(cy)));time.sleep(.3)
        self.assertIn('共 142.00',self.description())
        if folder:
            with open(os.path.join(folder,'category-overview.png'),'wb') as f:f.write(adb('exec-out','screencap','-p'))

if __name__=='__main__': unittest.main(verbosity=2)
