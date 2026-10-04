# Checks that the Excel file saved through the app arrived intact (standard library only).
import json, os, re, sys, zipfile

out = sys.argv[1]
exp = json.load(open(os.path.join(out, 'expected.json'), encoding='utf-8'))
z = zipfile.ZipFile(os.path.join(out, 'export.xlsx'))
ok = True


def check(name, cond, extra=''):
    global ok
    ok = ok and bool(cond)
    print(('PASS  ' if cond else 'FAIL  ') + name + (f'  -> {extra}' if extra != '' else ''))


check('zip container is intact', z.testzip() is None)
book = z.read('xl/workbook.xml').decode('utf-8')
check('three sheets', re.findall(r'<sheet name="([^"]+)"', book) == ['Transactions', 'Recurring', 'Budgets'])
sheet = z.read('xl/worksheets/sheet1.xml').decode('utf-8')
rows = len(re.findall(r'<row ', sheet))
check('one row per transaction plus the heading', rows == len(exp['tx']) + 1, f"{rows} rows for {len(exp['tx'])} transactions")
check('entries are in the file', all(t['category'].replace('&', '&amp;') in sheet for t in exp['tx']) and 'Milk &amp; bread' in sheet)
sys.exit(0 if ok else 1)
