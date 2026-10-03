#!/usr/bin/env bash
# =============================================================================
# 出库发货「加大板」状态闸门 · 验收脚本
#
# 用法：
#   ./acceptance-delivery.sh                 # 默认后端 http://127.0.0.1:8080
#   BASE=http://localhost:8080 ./acceptance-delivery.sh
#
# 依赖：bash + curl（有 jq 更好，没有则 grep 兜底）。
# 覆盖：
#   1. 待装车正常加板：明细新增、总金额回写、在库大板 → 已预定、列表/首页汇总一致
#   2. 同板重复加入（重复点击）：400，不产生第二行、金额不变、大板状态不变
#   3. 已发运 / 已签收 / 已结款绕过页面直连接口加板：全部 400，
#      原明细、总金额、大板库存状态保持不变
#   4. 状态推进后再次加板仍被拒；刷新（重新 GET）后数据与拒绝前一致
#   5. 并发：发运与加板同时发生，不会出现「已发运单金额被改写 / 大板停在已预定」
# 脚本只新建自己的发货单（DEL-ACC-*），不修改任何历史单据，可重复执行。
# =============================================================================
set -u
BASE="${BASE:-http://127.0.0.1:8080}/api"
PASS=0; FAIL=0
TS=$(date +%s)

ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
title(){ echo; echo "==================== $1 ===================="; }

have_jq=0; command -v jq >/dev/null 2>&1 && have_jq=1

req() { # req <method> <path> [json] -> 打印 "HTTP码|响应体"
  local m="$1" p="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -o /tmp/dlv_resp.json -w '%{http_code}' -X "$m" "$BASE$p" \
      -H 'Content-Type: application/json' -d "$body"
  else
    curl -s -o /tmp/dlv_resp.json -w '%{http_code}' -X "$m" "$BASE$p"
  fi
  echo "|$(cat /tmp/dlv_resp.json)"
}
code() { echo "$1" | cut -d'|' -f1; }
json() { echo "$1" | cut -d'|' -f2-; }
contains() { case "$2" in *"$1"*) return 0;; *) return 1;; esac; }
field() { # field <json> <key>
  echo "$1" | grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"\\?[^,\"}]*" | head -1 | sed "s/.*:[[:space:]]*\"\\?//";
}

slab_status() { field "$(curl -s "$BASE/slabs/$1")" status; }
slab_area()   { field "$(curl -s "$BASE/slabs/$1")" areaM2; }


echo "后端：$BASE"

# ---------------------------------------------------------------------------
title "准备：动态挑 8 片在库、非次品、未被质量冻结的大板（走 /slabs/shippable，与页面同源）"
SHIPPABLE=$(curl -s "$BASE/slabs/shippable?size=200&statuses=INSTOCK")
SLAB_IDS=()
if [ $have_jq -eq 1 ]; then
  while IFS= read -r line; do SLAB_IDS+=("$line"); done < <(echo "$SHIPPABLE" | jq -r '.data.rows[] | select(.grade != "DEFECT" and .qualityHold != true) | .id' 2>/dev/null)
else
  while IFS= read -r line; do SLAB_IDS+=("$line"); done < <(echo "$SHIPPABLE" | grep -o '\{[^{}]*"status":"INSTOCK"[^{}]*\}' | grep -o '"id":[0-9]*' | cut -d: -f2)
fi
# 逐个再查一次详情，只保留此刻确实在库的（兼容脚本重跑 / 演示库已被改动）
INSTOCK_IDS=()
for id in "${SLAB_IDS[@]}"; do
  [ "$(slab_status "$id")" = "INSTOCK" ] && INSTOCK_IDS+=("$id")
  [ "${#INSTOCK_IDS[@]}" -ge 8 ] && break
done
SLAB_IDS=("${INSTOCK_IDS[@]}")
if [ "${#SLAB_IDS[@]}" -lt 8 ]; then
  echo "  ❌ 当前在库、可发货大板不足 8 片（实际 ${#SLAB_IDS[@]}），请重置种子数据（mysql/schema.sql）后再跑"
  exit 1
fi
S1=${SLAB_IDS[0]}; S2=${SLAB_IDS[1]}; S3=${SLAB_IDS[2]}
S4=${SLAB_IDS[3]}; S5=${SLAB_IDS[4]}; S6=${SLAB_IDS[5]}
echo "  选用大板 id：${SLAB_IDS[*]}"


# 新建一张 LOADING 发货单，回显其 id
create_delivery() { # create_delivery <序号>
  local no="DEL-ACC-$TS-$1"
  local r; r=$(req POST /deliveries "{\"deliveryNo\":\"$no\",\"customer\":\"验收客户-$1\"}")
  if [ "$(code "$r")" != 200 ]; then bad "新建发货单 $no 失败: $(json "$r")"; echo ""; return; fi
  field "$(json "$r")" id
}

# 金额合计（items 接口，jq 求和 / grep 兜底）
items_amount_sum() { # items_amount_sum <deliveryId>
  local j; j=$(curl -s "$BASE/deliveries/$1/items")
  if [ $have_jq -eq 1 ]; then
    echo "$j" | jq -r '[.data[].amount] | add // 0'
  else
    echo "$j" | grep -o '"amount":[0-9.]*' | cut -d: -f2 | awk '{s+=$1} END{printf "%.2f", s+0}'
  fi
}
items_count() { # items_count <deliveryId>
  local j; j=$(curl -s "$BASE/deliveries/$1/items")
  if [ $have_jq -eq 1 ]; then echo "$j" | jq -r '.data | length';
  else echo "$j" | grep -o '"id":[0-9]*' | wc -l | tr -d ' '; fi
}
delivery_field() { # delivery_field <id> <key>
  field "$(curl -s "$BASE/deliveries/$1")" "$2"
}

# 期望金额：area * 480（验收统一单价）
expect_amount() { # expect_amount <slabId>
  awk -v a="$(slab_area "$1")" 'BEGIN{printf "%.2f", a*480}'
}

# 对某发货单尝试加板，断言 400 且数据不变
assert_reject_add() { # assert_reject_add <deliveryId> <slabId> <场景说明> <期望明细数> <期望总金额>
  local did="$1" sid="$2" desc="$3" wantCnt="$4" wantAmt="$5"
  local before; before=$(curl -s "$BASE/slabs/$sid")
  local r; r=$(req POST "/deliveries/$did/items" "{\"slabId\":$sid,\"quantity\":1,\"unitPrice\":480}")
  if [ "$(code "$r")" = 400 ]; then ok "$desc：接口 400 明确拒绝"; else bad "$desc：未拒绝，HTTP $(code "$r") $(json "$r")"; fi
  contains "只有待装车" "$(json "$r")" && ok "$desc：提示只有待装车才能加大板" || bad "$desc：拒绝提示不对"
  local cnt; cnt=$(items_count "$did")
  [ "$cnt" = "$wantCnt" ] && ok "$desc：明细数保持 $wantCnt" || bad "$desc：明细数被改写：$cnt ≠ $wantCnt"
  local amt; amt=$(items_amount_sum "$did")
  [ "$amt" = "$wantAmt" ] && ok "$desc：明细金额合计保持 $wantAmt" || bad "$desc：明细金额被改写：$amt ≠ $wantAmt"
  local tot; tot=$(delivery_field "$did" totalAmount)
  [ "$tot" = "$wantAmt" ] && ok "$desc：发货单总金额保持 $wantAmt" || bad "$desc：发货单总金额被改写：$tot ≠ $wantAmt"
  local st; st=$(slab_status "$sid")
  if [ "$st" = "$(field "$before" status)" ]; then ok "$desc：大板库存状态保持 $st（未被改成已预定）"; else bad "$desc：大板状态被改写：$st"; fi
}

# ---------------------------------------------------------------------------
title "场景 1：待装车发货单正常加大板 → 明细 / 总金额 / 大板状态 / 汇总同步"
D1=$(create_delivery 1); [ -n "$D1" ] && ok "发货单 D1 已创建 id=$D1"
A1=$(expect_amount "$S1")
r=$(req POST "/deliveries/$D1/items" "{\"slabId\":$S1,\"quantity\":1,\"unitPrice\":480}")
[ "$(code "$r")" = 200 ] && ok "待装车加板 200" || bad "待装车加板失败: $(code "$r") $(json "$r")"
contains "\"amount\":$A1" "$(json "$r")" && ok "明细金额 = 面积×单价 = $A1" || bad "明细金额不对: $(json "$r")"
[ "$(slab_status "$S1")" = "RESERVED" ] && ok "在库大板 $S1 → 已预定（库存状态联动）" || bad "大板未置已预定: $(slab_status "$S1")"
[ "$(delivery_field "$D1" totalAmount)" = "$A1" ] && ok "发货单总金额回写为 $A1" || bad "总金额回写不对: $(delivery_field "$D1" totalAmount)"
[ "$(delivery_field "$D1" itemCount)" = "1" ] && ok "列表 itemCount=1" || bad "itemCount 不对: $(delivery_field "$D1" itemCount)"
A2=$(expect_amount "$S2")
r=$(req POST "/deliveries/$D1/items" "{\"slabId\":$S2,\"quantity\":1,\"unitPrice\":480}")
[ "$(code "$r")" = 200 ] && ok "待装车再加一片 200" || bad "第二次加板失败: $(json "$r")"
[ "$(slab_status "$S2")" = "RESERVED" ] && ok "大板 $S2 → 已预定" || bad "大板 $S2 状态不对"
TOT12=$(awk -v a="$A1" -v b="$A2" 'BEGIN{printf "%.2f", a+b}')
[ "$(delivery_field "$D1" totalAmount)" = "$TOT12" ] && ok "总金额累加为 $TOT12" || bad "总金额不对: $(delivery_field "$D1" totalAmount)"
[ "$(items_count "$D1")" = "2" ] && ok "明细数=2" || bad "明细数不对"

# 首页发货金额汇总含该单
OV=$(curl -s "$BASE/mill/overview")
contains "\"deliveryCount\"" "$OV" && ok "首页汇总可取" || bad "首页汇总异常: $OV"

# ---------------------------------------------------------------------------
title "场景 2：重复点击 / 重复挂同一大板 → 400，不产生第二行，金额与状态不变"
beforeCnt=$(items_count "$D1"); beforeTot=$(delivery_field "$D1" totalAmount)
r=$(req POST "/deliveries/$D1/items" "{\"slabId\":$S1,\"quantity\":1,\"unitPrice\":480}")
[ "$(code "$r")" = 400 ] && ok "同单重复加大板 400（重复点击幂等拒绝）" || bad "重复加入未拦截: $(code "$r")"
contains "不能重复加入" "$(json "$r")" && ok "提示不能重复加入" || bad "重复提示不对: $(json "$r")"
[ "$(items_count "$D1")" = "$beforeCnt" ] && ok "明细数保持 $beforeCnt（没有第二行）" || bad "重复加入写了第二行"
[ "$(delivery_field "$D1" totalAmount)" = "$beforeTot" ] && ok "总金额保持 $beforeTot" || bad "总金额被重复加板改写"
[ "$(slab_status "$S1")" = "RESERVED" ] && ok "大板 $S1 仍为已预定（无副作用）" || bad "大板状态异常: $(slab_status "$S1")"

# ---------------------------------------------------------------------------
title "场景 3：已发运单绕过页面直连接口加板 → 400，历史明细 / 金额 / 大板状态不动"
D2=$(create_delivery 2)
req POST "/deliveries/$D2/items" "{\"slabId\":$S3,\"quantity\":1,\"unitPrice\":480}" >/dev/null
A3=$(expect_amount "$S3")
[ "$(delivery_field "$D2" totalAmount)" = "$A3" ] && ok "D2 基线金额 $A3" || bad "D2 基线金额不对"
r=$(req POST "/deliveries/$D2/status" '{"status":"SHIPPED"}')
[ "$(code "$r")" = 200 ] && ok "D2 → 已发运 200" || bad "状态流转失败: $(json "$r")"
[ "$(delivery_field "$D2" status)" = "SHIPPED" ] && ok "D2 状态 SHIPPED" || bad "D2 状态不对"
[ "$(slab_status "$S3")" = "OUTBOUND" ] && ok "发运后大板 $S3 → 已出库" || bad "大板未出库: $(slab_status "$S3")"
assert_reject_add "$D2" "$S4" "已发运单" "1" "$A3"
[ "$(slab_status "$S3")" = "OUTBOUND" ] && ok "原明细大板 $S3 仍为已出库（历史不倒改）" || bad "原大板状态被改动"

# ---------------------------------------------------------------------------
title "场景 4：已签收单加板 → 400（明细 / 金额 / 库存不变）"
D3=$(create_delivery 3)
req POST "/deliveries/$D3/items" "{\"slabId\":$S5,\"quantity\":1,\"unitPrice\":480}" >/dev/null
A5=$(expect_amount "$S5")
req POST "/deliveries/$D3/status" '{"status":"SIGNED"}' >/dev/null
[ "$(delivery_field "$D3" status)" = "SIGNED" ] && ok "D3 → 已签收" || bad "D3 签收失败"
assert_reject_add "$D3" "$S4" "已签收单" "1" "$A5"

# ---------------------------------------------------------------------------
title "场景 5：已结款单加板 → 400（原只拦已结款，现在行为提示保持一致）"
D4=$(create_delivery 4)
req POST "/deliveries/$D4/items" "{\"slabId\":$S6,\"quantity\":1,\"unitPrice\":480}" >/dev/null
A6=$(expect_amount "$S6")
req POST "/deliveries/$D4/status" '{"status":"PAID"}' >/dev/null
[ "$(delivery_field "$D4" status)" = "PAID" ] && ok "D4 → 已结款" || bad "D4 结款失败"
assert_reject_add "$D4" "$S4" "已结款单" "1" "$A6"

# ---------------------------------------------------------------------------
title "场景 6：刷新后一致（重新拉列表 / 详情 / 明细 / 大板库存，拒绝痕迹不落库）"
for st in SHIPPED SIGNED PAID; do
  did_var="D$([ "$st" = SHIPPED ] && echo 2 || { [ "$st" = SIGNED ] && echo 3 || echo 4; })"
  did="${!did_var}"
  j=$(curl -s "$BASE/deliveries?size=100&status=$st")
  contains "\"id\":$did" "$j" && ok "列表按 $st 筛选仍含该单" || bad "列表刷新后缺单 $did"
  contains "\"status\":\"$st\"" "$j" && ok "列表中该单状态仍为 $st" || bad "列表状态被改写"
done
# S4 自始至终没有加入任何单：必须仍是在库
[ "$(slab_status "$S4")" = "INSTOCK" ] && ok "被拒大板 $S4 刷新后仍为在库（拒绝零副作用）" || bad "被拒大板状态不对: $(slab_status "$S4")"
# D2~D4 总金额刷新后仍为基线
[ "$(delivery_field "$D2" totalAmount)" = "$A3" ] && ok "已发运单刷新后总金额仍 $A3" || bad "已发运单金额变化"
[ "$(delivery_field "$D3" totalAmount)" = "$A5" ] && ok "已签收单刷新后总金额仍 $A5" || bad "已签收单金额变化"
[ "$(delivery_field "$D4" totalAmount)" = "$A6" ] && ok "已结款单刷新后总金额仍 $A6" || bad "已结款单金额变化"
# D1 仍是待装车，刷新后明细 2 行、金额 TOT12
[ "$(delivery_field "$D1" status)" = "LOADING" ] && ok "D1 仍为待装车" || bad "D1 状态异常"
[ "$(items_count "$D1")" = "2" ] && ok "D1 刷新后明细仍 2 行" || bad "D1 明细数变化"
[ "$(delivery_field "$D1" totalAmount)" = "$TOT12" ] && ok "D1 刷新后总金额仍 $TOT12" || bad "D1 金额变化"

# ---------------------------------------------------------------------------
title "场景 7：并发——发运与加板同时发生，只允许一个确定结果（行锁串行 + 状态闸门）"
D5=$(create_delivery 5)
# 找两片确定在库、未被本脚本使用过的大板：一片做基线，一片做并发竞争
BASE_SLAB=""; CAND=""
for id in "${SLAB_IDS[@]}"; do
  if [ "$(slab_status "$id")" = "INSTOCK" ] && [ "$id" != "$S1" ] && [ "$id" != "$S2" ] \
     && [ "$id" != "$S3" ] && [ "$id" != "$S4" ] && [ "$id" != "$S5" ] && [ "$id" != "$S6" ]; then
    if [ -z "$BASE_SLAB" ]; then BASE_SLAB="$id"; elif [ -z "$CAND" ]; then CAND="$id"; fi
  fi
done
if [ -z "$BASE_SLAB" ] || [ -z "$CAND" ]; then
  echo "  ℹ️ 剩余在库大板不足 2 片，跳过并发场景（状态闸门已在场景 3~5 覆盖）"
else
  r=$(req POST "/deliveries/$D5/items" "{\"slabId\":$BASE_SLAB,\"quantity\":1,\"unitPrice\":480}")
  [ "$(code "$r")" = 200 ] && ok "D5 基线明细已挂大板 $BASE_SLAB" || bad "D5 基线加板失败: $(json "$r")"
  c1=/tmp/dlv_c1; c2=/tmp/dlv_c2
  ( curl -s -o /tmp/dlv_ship.json -w '%{http_code}' -X POST "$BASE/deliveries/$D5/status" \
      -H 'Content-Type: application/json' -d '{"status":"SHIPPED"}' > "$c1" ) &
  ( curl -s -o /tmp/dlv_add.json  -w '%{http_code}' -X POST "$BASE/deliveries/$D5/items" \
      -H 'Content-Type: application/json' -d "{\"slabId\":$CAND,\"quantity\":1,\"unitPrice\":480}" > "$c2" ) &
  wait
  cc1=$(cat "$c1"); cc2=$(cat "$c2")
  echo "  并发 HTTP：发运 $cc1 / 加板 $cc2"
  [ "$cc1" = 200 ] && ok "发运成功 200" || { cat /tmp/dlv_ship.json; bad "发运未成功: $cc1"; }
  if [ "$cc2" = 400 ]; then
    ok "并发加板被状态闸门拒绝 400（发运先拿到行锁）"
    contains "只有待装车" "$(cat /tmp/dlv_add.json)" && ok "拒绝提示正确" || bad "提示不对: $(cat /tmp/dlv_add.json)"
    [ "$(slab_status "$CAND")" = "INSTOCK" ] && ok "竞争大板 $CAND 仍为在库（拒绝零副作用）" || bad "竞争大板状态不对: $(slab_status "$CAND")"
  elif [ "$cc2" = 200 ]; then
    # 加板先拿到锁也是合法的串行结果：随后发运必须把新板一并出库，绝不允许停在「已预定」
    ok "加板先于发运串行成功 200（行锁保证顺序确定）"
    [ "$(slab_status "$CAND")" = "OUTBOUND" ] && ok "竞争大板随单出库（已预定被发运推进为已出库）" || bad "竞争大板未随单出库: $(slab_status "$CAND")"
  else
    bad "并发加板返回异常: $cc2 $(cat /tmp/dlv_add.json)"
  fi
  # 最终一致性断言
  [ "$(delivery_field "$D5" status)" = "SHIPPED" ] && ok "并发后 D5 终态=已发运" || bad "终态不对"
  # 已发运后再补一刀：必拒，明细与金额保持并发后的最终值
  assert_reject_add "$D5" "$S4" "发运完成后补加" "$(items_count "$D5")" "$(items_amount_sum "$D5")"
fi

echo
echo "================================================================"
echo "通过 $PASS 条，失败 $FAIL 条"
[ "$FAIL" -eq 0 ] && echo "🎉 发货单加大板状态闸门全部验收通过" || echo "⚠️ 存在失败用例，请按上面 ❌ 排查"
exit "$FAIL"
