"""Execute the production migration and DAO SQL with SQLite; not a Room/device substitute."""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / 'server/src/main/java/org/ezbook/server/db'
MIGRATION = (DB / 'AppDatabase.kt').read_text().split('val MIGRATION_21_22 =', 1)[1]
NOTICE_DAO = (DB / 'dao/BillNoticeDao.kt').read_text()
BILL_DAO = (DB / 'dao/BillInfoDao.kt').read_text()


def query(source, method):
    return re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun ' + method + r'\(', source).group(1)


def migrate(connection):
    text_columns = re.search(r'listOf\((.*?)\)\.forEach', MIGRATION, re.S).group(1)
    column_template = re.search(r'database.execSQL\("([^"]+\$it[^"]+)"\)', MIGRATION).group(1)
    for column in re.findall(r'"([^"]+)"', text_columns):
        connection.execute(column_template.replace('$it', column))
    for sql in re.findall(r'database.execSQL\("([^"]+)"\)', MIGRATION):
        if '$it' not in sql:
            connection.execute(sql)


class LedgerStorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'ledger.db'
        self.db = sqlite3.connect(self.path)
        self.db.execute('CREATE TABLE BillInfoModel (id INTEGER PRIMARY KEY, money REAL NOT NULL, type TEXT NOT NULL, groupId INTEGER NOT NULL DEFAULT -1, flag INTEGER NOT NULL DEFAULT 0)')
        self.db.execute("INSERT INTO BillInfoModel(id,money,type) VALUES(1,100,'Expend')")
        migrate(self.db)
        self.db.commit()

    def tearDown(self):
        self.db.close()
        self.temp.cleanup()

    def add_receipt(self, bill=1):
        return self.db.execute("INSERT INTO BillNoticeModel (billId,billJson,createdAt,acknowledgedAt,kind) VALUES (?, '{}', 100, 0, 'AUTO_SAVED')", (bill,)).lastrowid

    def pending(self):
        return self.db.execute(query(NOTICE_DAO, 'pending')).fetchall()

    def test_migration_preserves_existing_bills_without_retroactive_receipts(self):
        self.assertEqual((100.0, '', 0, 'COMPLETED'), self.db.execute('SELECT money,transactionId,originalBillId,eventStatus FROM BillInfoModel WHERE id=1').fetchone())
        self.assertEqual([], self.pending())

    def test_unacknowledged_receipts_survive_restart_in_fifo_order(self):
        first, second = self.add_receipt(1), self.add_receipt(2)
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.assertEqual([first, second], [row[0] for row in self.pending()])
        self.assertEqual([first, second], [row[0] for row in self.pending()])

    def test_explicit_acknowledgement_removes_only_selected_receipt(self):
        first, second = self.add_receipt(1), self.add_receipt(2)
        sql = query(NOTICE_DAO, 'acknowledge')
        self.db.execute(sql, {'id': first, 'time': 500})
        self.db.execute(sql, {'id': first, 'time': 900})
        self.assertEqual([second], [row[0] for row in self.pending()])
        self.assertEqual(500, self.db.execute('SELECT acknowledgedAt FROM BillNoticeModel WHERE id=?', (first,)).fetchone()[0])

    def test_failed_transaction_leaves_neither_bill_nor_success_receipt(self):
        with self.assertRaises(sqlite3.OperationalError):
            with self.db:
                self.db.execute("INSERT INTO BillInfoModel(id,money,type) VALUES(2,20,'Expend')")
                self.add_receipt(2)
                self.db.execute('INSERT INTO table_that_does_not_exist VALUES(1)')
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM BillInfoModel WHERE id=2').fetchone()[0])
        self.assertEqual([], self.pending())

    def test_bill_changes_refresh_only_unacknowledged_receipts(self):
        first, second = self.add_receipt(1), self.add_receipt(1)
        self.db.execute(query(NOTICE_DAO, 'acknowledge'), {'id': first, 'time': 500})
        changed = self.db.execute(query(NOTICE_DAO, 'refreshPending'), {'billId': 1, 'json': '{"money":30}'}).rowcount
        self.assertEqual(1, changed)
        self.assertEqual([(first, '{}', 500), (second, '{"money":30}', 0)], self.db.execute('SELECT id,billJson,acknowledgedAt FROM BillNoticeModel ORDER BY id').fetchall())

    def test_refund_total_excludes_grouped_pending_and_current_refund(self):
        self.db.executemany("INSERT INTO BillInfoModel(id,money,type,originalBillId,groupId,flag) VALUES(?,?,'IncomeRefund',1,?,?)", [(2,30,-1,0), (3,30,2,0), (4,20,-1,1), (5,70,-1,0)])
        total = self.db.execute(query(BILL_DAO, 'refundedAmount'), {'originalId': 1, 'excludeId': 5}).fetchone()[0]
        self.assertEqual(30, total)

    def test_source_replay_lookup_survives_restart(self):
        self.db.execute("UPDATE BillInfoModel SET sourceKey='sms-1' WHERE id=1")
        self.db.commit()
        self.db.close()
        self.db = sqlite3.connect(self.path)
        self.assertEqual(1, self.db.execute(query(BILL_DAO, 'bySource'), {'key': 'sms-1'}).fetchone()[0])
        self.assertIsNone(self.db.execute(query(BILL_DAO, 'bySource'), {'key': ''}).fetchone())


if __name__ == '__main__':
    unittest.main()
