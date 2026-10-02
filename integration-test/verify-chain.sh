#!/usr/bin/env bash
# piggymetrics-refactored 全链路联调验证脚本
set -u
cd "$(dirname "$0")"
source .env
GW=http://localhost:14000
OKCNT=0; BADCNT=0

chk() { # chk <名称> <期望码> <实际码>
  if [ "$2" = "$3" ]; then OKCNT=$((OKCNT+1)); echo "PASS  $1 (HTTP $3)";
  else BADCNT=$((BADCNT+1)); echo "FAIL  $1 (期望 $2 实际 $3)"; fi
}
ok()   { OKCNT=$((OKCNT+1)); echo "PASS  $1"; }
bad()  { BADCNT=$((BADCNT+1)); echo "FAIL  $1"; }

echo "======== 0. 容器状态 ========"
docker ps --filter "label=com.docker.compose.project=piggymetrics-it" --format '{{.Names}}\t{{.Status}}'

echo ""
echo "======== 1. Nacos 注册中心 ========"
for s in auth-service account-service statistics-service notification-service gateway; do
  n=$(curl -s "http://localhost:18848/nacos/v1/ns/instance/list?serviceName=$s&namespaceId=it" | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('hosts',[])))" 2>/dev/null)
  if [ "${n:-0}" -ge 1 ]; then ok "$s 已注册 ($n 实例)"; else bad "$s 未注册"; fi
done

echo ""
echo "======== 2. Nacos 配置中心拉取 ========"
for s in gateway auth-service account-service statistics-service notification-service; do
  cid=$(docker ps -q --filter "name=piggymetrics-it-$s-1")
  [ -z "$cid" ] && cid=$(docker ps -q --filter "name=piggymetrics-it-$s")
  cnt=$(docker logs "$cid" 2>&1 | grep -c "Load config\[dataId=.*success" || true)
  if [ "${cnt:-0}" -ge 1 ]; then ok "$s 从 Nacos 加载配置 $cnt 份"; else bad "$s 未从 Nacos 加载配置"; fi
done

echo ""
echo "======== 3. 网关健康与路由 ========"
chk "gateway /actuator/health" 200 "$(curl -s -o /dev/null -w '%{http_code}' $GW/actuator/health)"
chk "未声明路由 /foo (等价 zuul ignoredServices=*)" 404 "$(curl -s -o /dev/null -w '%{http_code}' $GW/foo)"

echo ""
echo "======== 4. 认证链路（JWT）========"
# 幂等：demo 首次开户应 200；重复运行已存在返回 400(account already exists) 亦为正确行为
REG=$(curl -s -o /tmp/reg.json -w '%{http_code}' -X POST $GW/accounts/ -H 'Content-Type: application/json' \
  -d "{\"username\":\"demo\",\"password\":\"$IT_DEMO_PW\"}")
if [ "$REG" = "200" ]; then
  ok "开户 POST /accounts/ (demo) 首次创建成功 (HTTP 200)"
elif [ "$REG" = "400" ]; then
  ok "开户 POST /accounts/ (demo) 幂等：账户已存在返回 400（正确；ErrorHandler 空 body）"
else
  bad "开户 POST /accounts/ (demo) HTTP=$REG body=$(head -c 120 /tmp/reg.json)"
fi

TOK=$(curl -s -X POST "$GW/uaa/oauth2/token" \
  -d "grant_type=password&client_id=browser&username=demo&password=${IT_DEMO_PW}&scope=ui")
AT=$(echo "$TOK" | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
if [ -n "${AT:-}" ]; then ok "password grant 签发 JWT (${#AT} chars)"; else bad "password grant: $(echo $TOK | head -c 200)"; fi

CT=$(curl -s -u "account-service:$IT_ACCOUNT_PW" -d "grant_type=client_credentials&scope=server" \
  "$GW/uaa/oauth2/token" | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
if [ -n "${CT:-}" ]; then ok "client_credentials 签发 server-scope JWT"; else bad "client_credentials"; fi

chk "无 token GET /accounts/current -> 401" 401 "$(curl -s -o /dev/null -w '%{http_code}' $GW/accounts/current)"
BADCODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GW/uaa/oauth2/token" \
  -d "grant_type=password&client_id=browser&username=demo&password=${IT_DEMO_PW}_definitely_wrong&scope=ui")
chk "错误密码 -> 400 invalid_grant" 400 "$BADCODE"

echo ""
echo "======== 5. 账务链路（红线）========"
ACC=$(curl -s -H "Authorization: Bearer $AT" $GW/accounts/current)
if echo "$ACC" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['name']=='demo' and d['saving']['currency']=='USD', d" 2>/dev/null; then
  ok "GET /accounts/current 返回 demo 账户, saving.currency=USD"
else bad "GET /accounts/current: $(echo $ACC | head -c 200)"; fi

PUT=$(curl -s -o /dev/null -w '%{http_code}' -X PUT $GW/accounts/current -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{
  "incomes":[{"title":"salary","amount":1000,"currency":"EUR","period":"MONTH","icon":"wallet"}],
  "expenses":[{"title":"rent","amount":500,"currency":"USD","period":"MONTH","icon":"home"}],
  "saving":{"amount":200,"currency":"USD","interest":0.05,"deposit":true,"capitalization":false},
  "note":"it-chain"}')
chk "PUT /accounts/current (账务变更+统计联动)" 200 "$PUT"

echo ""
echo "======== 6. 统计链路（金额红线，经 rates-mock）========"
sleep 4
STAT=$(curl -s -H "Authorization: Bearer $AT" $GW/statistics/current)
if echo "$STAT" | python3 -c "
import json,sys
d=json.load(sys.stdin)
assert isinstance(d,list) and len(d)>=1, ('no datapoint', d)
dp=d[-1]; st=dp['statistics']
exp_inc=1000*1.1111/30.4368
exp_exp=500*1.0/30.4368
assert abs(float(st['INCOMES_AMOUNT'])-exp_inc)<0.01, ('INCOMES',st['INCOMES_AMOUNT'],exp_inc)
assert abs(float(st['EXPENSES_AMOUNT'])-exp_exp)<0.01, ('EXPENSES',st['EXPENSES_AMOUNT'],exp_exp)
assert abs(float(st['SAVING_AMOUNT'])-200.0)<0.01, ('SAVING',st['SAVING_AMOUNT'])
print('  金额黄金值比对: INCOMES=%s (期望≈%.4f) EXPENSES=%s SAVING=%s rates=%s' % (st['INCOMES_AMOUNT'],exp_inc,st['EXPENSES_AMOUNT'],st['SAVING_AMOUNT'],sorted(dp['rates'].keys())))
" 2>/tmp/stat_err.txt; then ok "DataPoint 生成且金额与黄金值一致"; cat /tmp/stat_err.txt 2>/dev/null
else bad "统计链路: $(echo $STAT | head -c 300)"; fi

echo ""
echo "======== 7. 通知链路 ========"
NSAVE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT $GW/notifications/recipients/current -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{
  "email":"demo@example.com",
  "scheduledNotifications":{"BACKUP":{"active":true,"frequency":"WEEKLY"},"REMIND":{"active":true,"frequency":"MONTHLY"}}}')
chk "PUT /notifications/recipients/current" 200 "$NSAVE"
NGET=$(curl -s -H "Authorization: Bearer $AT" $GW/notifications/recipients/current)
if echo "$NGET" | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['email']=='demo@example.com', d" 2>/dev/null; then
  ok "通知设置读回一致 (Mongo round-trip, Frequency 转换器生效)"
else bad "通知设置: $(echo $NGET | head -c 200)"; fi

echo ""
echo "======== 8. 鉴权红线（不得放宽）========"
chk "ui-scope GET /statistics/somebody -> 403" 403 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $AT" $GW/statistics/somebody)"
chk "demo 例外分支 GET /statistics/demo -> 200" 200 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $AT" $GW/statistics/demo)"
chk "ui-scope PUT /statistics/x -> 403 (仅 server scope)" 403 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $AT" -H 'Content-Type: application/json' -d '{"incomes":[],"expenses":[],"saving":{"amount":1,"currency":"USD","interest":0,"deposit":false,"capitalization":false}}' $GW/statistics/x)"
chk "server-scope PUT /statistics/demo -> 200 (服务间通道)" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $CT" -H 'Content-Type: application/json' -d '{"incomes":[],"expenses":[],"saving":{"amount":1,"currency":"USD","interest":0,"deposit":false,"capitalization":false}}' $GW/statistics/demo)"

echo ""
echo "======== 9. 数据落库核验（直查容器内 mongo）========"
for pair in "auth-mongodb:users" "account-mongodb:accounts" "statistics-mongodb:datapoints" "notification-mongodb:recipients"; do
  db="${pair%%:*}"; col="${pair##*:}"
  cid=$(docker ps -q --filter "name=piggymetrics-it-$db")
  n=$(docker exec "$cid" mongo --quiet -u user -p "$IT_MONGO_PW" --authenticationDatabase admin piggymetrics --eval "db.$col.count({})" 2>/dev/null)
  if [ "${n:-0}" -ge 1 ]; then ok "$db.$col 落库 $n 条"; else bad "$db.$col 空 (n=$n)"; fi
done
cid=$(docker ps -q --filter "name=piggymetrics-it-auth-mongodb")
H=$(docker exec "$cid" mongo --quiet -u user -p "$IT_MONGO_PW" --authenticationDatabase admin piggymetrics --eval 'print(db.users.findOne({_id:"demo"}).password.substring(0,4))' 2>/dev/null)
if [ "$H" = '$2a$' ]; then ok "users 密码为 BCrypt 哈希（非明文）"; else bad "users 密码前缀=$H"; fi

echo ""
echo "================================"
echo "联调结果: PASS=$OKCNT FAIL=$BADCNT"
echo "================================"
[ "$BADCNT" -eq 0 ] && exit 0 || exit 1
