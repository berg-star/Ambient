"""Independently verify a real emulator export with Python's standard CSV reader."""
import csv
import datetime as dt
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = path.read_bytes()
assert data.startswith(b'\xef\xbb\xbf'), 'UTF-8 BOM missing'
assert b'\r\n' in data, 'CRLF rows missing'
with path.open('r', encoding='utf-8-sig', newline='') as stream:
    rows = list(csv.DictReader(stream, strict=True))
assert len(rows) > 5, 'Expected all environmental metric rows'
assert all(len(row) == 19 and None not in row for row in rows), 'Wrong CSV column count'
assert {row['导出格式版本'] for row in rows} == {'1'}
assert len({row['快照ID'] for row in rows}) == 1, 'Expected one test snapshot'
assert {row['区域'] for row in rows} == {'北京市 · 北京'}
assert {row['备注'] for row in rows} == {'csv,"verify"'}, 'Comma/quote note changed'
assert {row['缓存快照'] for row in rows} == {'false'}
assert {row['演示快照'] for row in rows} == {'false'}
assert {row['观测时间(UTC)'] for row in rows} == {''}, 'Missing observation time invented'
for row in rows:
    saved = dt.datetime.fromisoformat(row['保存时间(UTC)'].replace('Z', '+00:00'))
    fetched = dt.datetime.fromisoformat(row['数据获取时间(UTC)'].replace('Z', '+00:00'))
    assert saved >= fetched and fetched.tzinfo is not None
    assert row['数据来源代码'] in {'REGIONAL_API', 'ESTIMATED'}
    assert row['数据来源'] and row['指标说明']
by_id = {row['指标ID']: row for row in rows}
assert len(by_id) == len(rows), 'Duplicate metric rows'
assert by_id['temperature']['单位'] == '°C'
assert float(by_id['temperature']['数值']) > -100
assert by_id['humidity']['单位'] == '%'
assert by_id['altitude']['数值'] == '—', 'Missing altitude must not become zero'
assert '和风' in by_id['temperature']['指标说明'], 'Chinese description missing'
print(f'PASS independent Python CSV parse: {len(rows)} metric rows, 19 columns, Chinese/BOM/quoted note/times/sources/missing values retained')
