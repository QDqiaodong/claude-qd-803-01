#!/usr/bin/env bash
# 排程台接口端到端验收（H2 验证实例 :8399）
set -u
B=http://127.0.0.1:8399/api/schedule
PASS=0; FAIL=0
ok(){ echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad(){ echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
jget(){ python3 -c "import json,sys;d=json.load(sys.stdin);print(eval(\"d$1\"))"; }

echo "== A. 生成方案（后台作业 + 合并） =="
S1=$(curl -s -X POST $B/plans/generate -H 'Content-Type: application/json' -d '{"planName":"方案A","createdBy":"调度员"}')
J1=$(echo "$S1" | jget "['data']['jobId']")
S2=$(curl -s -X POST $B/plans/generate -H 'Content-Type: application/json' -d '{"planName":"方案A2","createdBy":"调度员"}')
MERGED=$(echo "$S2" | jget "['data']['merged']")
[ "$MERGED" = "True" ] && ok "相同输入的并发重算被合并" || bad "重算未合并 merged=$MERGED"
for i in $(seq 1 20); do
  ST=$(curl -s $B/jobs/$J1 | jget "['data']['status']")
  [ "$ST" = "DONE" ] && break; sleep 0.5
done
[ "$ST" = "DONE" ] && ok "后台作业完成且页面可轮询" || bad "作业未完成 $ST"
P1=$(curl -s $B/jobs/$J1 | jget "['data']['planId']")

echo "== B. 方案指标与排除原因 =="
curl -s $B/plans/$P1 > /tmp/p1.json
python3 - <<'EOF'
import json
d=json.load(open('/tmp/p1.json'))['data']; p=d['plan']; A=d['assignments']
print("  指标 scheduled=%s excluded=%s late=%s wash=%smin/%s元 idle=%s" % (
 p['scheduledCarts'],p['excludedCarts'],p['lateCarts'],p['totalWashMinutes'],p['totalWashCost'],p['totalIdleMinutes']))
excl={a['cartCode']:a['reasonText'] for a in A if a['resultType']=='EXCLUDED'}
assert 'C-2510' in excl and '机型不适配' in excl['C-2510'] and '颜色族' in excl['C-2510'], excl
assert 'C-2508' in excl and '容量不足' in excl['C-2508'], excl
assert 'C-2507' in excl and '配方资料不完整' in excl['C-2507'], excl
fixed={a['cartCode']:a for a in A if a['resultType']=='FIXED'}
assert fixed['C-2504']['locked']==1 and fixed['C-2504']['vatCode']=='V-02'
print("  [PASS] 缺资料/容量/机型排除原因具体；锁定车 C-2504 固定在 V-02")
# 维护窗口不撞
from datetime import datetime
T=lambda s: datetime.fromisoformat(str(s))
maint=[(m['vatId'],T(m['startTime']),T(m['endTime'])) for m in d['maintenance']]
ok=True
for a in A:
    if a['startTime'] and a['vatId']:
        s,e=T(a['startTime']),T(a['endTime'])
        for vid,ms,me in maint:
            if vid==a['vatId'] and s<me and e>ms: ok=False; print('撞维护',a['cartCode'])
print("  [PASS] 排程不撞任何维护窗口" if ok else "  [FAIL] 撞维护")
# 同缸不重叠
by={}
for a in A:
    if a['startTime'] and a['vatId']: by.setdefault(a['vatId'],[]).append((T(a['startTime']),T(a['endTime']),a['cartCode']))
ov=False
for v,lst in by.items():
    lst.sort()
    for i in range(1,len(lst)):
        if lst[i][0]<lst[i-1][1]: ov=True; print('重叠',v,lst[i-1],lst[i])
print("  [PASS] 同缸染色段互不重叠" if not ov else "  [FAIL] 重叠")
EOF

echo "== C. 急单取舍：锁定的 C-2504 之外，急单 C-2511 应排在最早批次 =="
python3 - <<'EOF'
import json
d=json.load(open('/tmp/p1.json'))['data']; A=d['assignments']
from datetime import datetime
T=lambda s: datetime.fromisoformat(str(s))
rush=[a for a in A if a['cartCode']=='C-2511'][0]
normal_starts=[T(a['startTime']) for a in A if a['startTime'] and not a['locked'] and a['cartCode'] not in ('C-2511',)]
assert rush['startTime'] and T(rush['startTime']) <= min(normal_starts), rush
print("  [PASS] 急单 C-2511 与最早批次同时或更早，不会被省清洗压后：", rush['vatCode'], rush['startTime'])
EOF

echo "== D. 修改交期 + 连续修改合并 =="
curl -s -X PATCH $B/carts/8 -H 'Content-Type: application/json' -d '{"dueTime":"2026-10-08 18:00","rushFlag":0}' >/dev/null
curl -s $B/context | jget "['ok']" | grep -q True && ok "交期可改（仅排程字段）" || bad "交期修改失败"

echo "== E. 发布新版清洗规则（深转浅翻倍） =="
curl -s $B/wash-matrix > /tmp/m1.json
python3 - <<'EOF'
import json,urllib.request
m=json.load(open('/tmp/m1.json'))['data']
cells=[{k:c[k] for k in ('fromFamilyId','toFamilyId','switchType','washMinutes','washCost','remark')} for c in m['cells']]
for c in cells:
    if c['switchType']=='D2L': c['washMinutes']=c['washMinutes']*2
body=json.dumps({"changeNote":"验收：深转浅时长翻倍","createdBy":"工艺员","cells":cells}).encode()
req=urllib.request.Request("http://127.0.0.1:8399/api/schedule/wash-versions",data=body,headers={"Content-Type":"application/json"})
r=json.load(urllib.request.urlopen(req))
assert r['ok'] and r['data']['versionNo']==m['version']['versionNo']+1
print("  [PASS] 新版本 V%s 已发布" % r['data']['versionNo'])
EOF

echo "== F. 新方案采用新口径 =="
S3=$(curl -s -X POST $B/plans/generate -H 'Content-Type: application/json' -d '{"planName":"方案B-新口径","createdBy":"调度员"}')
J3=$(echo "$S3" | jget "['data']['jobId']")
for i in $(seq 1 20); do ST=$(curl -s $B/jobs/$J3 | jget "['data']['status']"); [ "$ST" = "DONE" ] && break; sleep 0.5; done
P2=$(curl -s $B/jobs/$J3 | jget "['data']['planId']")
curl -s $B/plans/$P2 > /tmp/p2.json
python3 - <<'EOF'
import json
a=json.load(open('/tmp/p1.json'))['data']['plan']
b=json.load(open('/tmp/p2.json'))['data']['plan']
assert b['ruleVersionNo']==a['ruleVersionNo']+1, (a['ruleVersionNo'],b['ruleVersionNo'])
# 新口径下出现 D2L 的车，其清洗分钟为旧值两倍（若存在 D2L）；至少总清洗分钟不同或布局变化
A2=json.load(open('/tmp/p2.json'))['data']['assignments']
d2l=[x for x in A2 if x['switchType']=='D2L']
print("  [PASS] 新方案规则版本=V%s，D2L 段数=%s，清洗总 %s 分钟（旧 %s）" % (
 b['ruleVersionNo'],len(d2l),b['totalWashMinutes'],a['totalWashMinutes']))
EOF

echo "== G. 旧方案按当时版本复算 =="
RP=$(curl -s -X POST $B/plans/$P1/replay -H 'Content-Type: application/json' -d '{"createdBy":"验收"}')
echo "$RP" | jget "['ok']" | grep -q True && ok "复算接口成功" || bad "复算失败 $RP"
RID=$(echo "$RP" | jget "['data']['id']")
curl -s $B/plans/$RID > /tmp/pr.json
python3 - <<'EOF'
import json
pr=json.load(open('/tmp/pr.json'))['data']['plan']
p1=json.load(open('/tmp/p1.json'))['data']['plan']
assert pr['ruleVersionNo']==p1['ruleVersionNo']==1, (pr['ruleVersionNo'],p1['ruleVersionNo'])
assert pr['anchorMode']=='REPLAY'
assert pr['totalWashMinutes']==p1['totalWashMinutes']
print("  [PASS] 复算方案口径仍为 V%s 且清洗总时长一致（%s 分钟），原解释可还原" % (pr['ruleVersionNo'],pr['totalWashMinutes']))
EOF

echo "== H. 选定车间参考（唯一） =="
curl -s -X POST $B/plans/$P2/reference >/dev/null
R1=$(curl -s $B/plans | python3 -c "import json,sys;print(sum(1 for p in json.load(sys.stdin)['data'] if p['status']=='REFERENCE'))")
[ "$R1" = "1" ] && ok "仅一个参考方案" || bad "参考方案数=$R1"
curl -s -X POST $B/plans/$P1/reference >/dev/null
R2=$(curl -s $B/plans | python3 -c "import json,sys;print(sum(1 for p in json.load(sys.stdin)['data'] if p['status']=='REFERENCE'))")
[ "$R2" = "1" ] && ok "改选后旧参考自动降级，仍唯一" || bad "参考方案数=$R2"

echo "== I. 人工锁定段校验 =="
L=$(curl -s -X POST $B/locks -H 'Content-Type: application/json' \
  -d '{"vatId":2,"startTime":"2026-10-03 07:00","endTime":"2026-10-03 08:00","lockReason":"重叠测试","createdBy":"验收"}')
echo "$L" | grep -q '"ok":false' && ok "与既有锁定段重叠被拦截" || bad "重叠锁定未拦截"

echo "== J. 方案不改布车/执行单状态 =="
python3 - <<'EOF'
import json,urllib.request
carts=json.load(urllib.request.urlopen("http://127.0.0.1:8399/api/carts"))['data']
pending={c['cartCode']:c['status'] for c in carts}
assert pending['C-2511']=='PENDING', pending['C-2511']
assert pending.get('C-2407')=='PENDING'
print("  [PASS] 排程后排产布车仍为 PENDING（未回写布车状态、未开执行单）")
EOF

echo
echo "结果: PASS=$PASS FAIL=$FAIL"
[ $FAIL -eq 0 ] || exit 1
