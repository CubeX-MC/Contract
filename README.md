# Contracts

![](https://bstats.org/signatures/bukkit/Contracts.svg)

玩家对玩家合同平台。当前版本提供 SERVICE 委托、WAGER 对赌和 PARTNERSHIP 合作三类合同，重点先保证 Vault 托管资金、接单/接受邀请、提交、确认、裁决、取消退款和管理员仲裁流程正确。

## 依赖

- Vault
- 任意 Vault 经济插件，例如 CMI Economy

运行时不依赖 CMI、QuickShop、Lands、RuleGems 或数据库驱动。

## 构建

```bash
mvn package
```

生成文件：

```text
target/contracts-0.1.0.jar
```

## 玩家命令

```text
/contract help
/contract
/contract gui
/contract service <奖金> <小时> <标题>|<描述>
/contract service <奖金> <小时> --mediator <中间人> <标题>|<描述>
/contract wager <对方> <押注> <小时> <仲裁者> <标题>|<描述>
/contract partner <对方> <我押注> <对方押注> <小时> <标题>|<描述>
/contract partner <对方> <我押注> <对方押注> <小时> --mediator <中间人> <标题>|<描述>
/contract list [页码]
/contract my
/contract info <id>
/contract accept <id>
/contract submit <id>
/contract approve <id>
/contract resolve <id> <a|b>
/contract mediate <id> <accept|pay|refund|owner|contractor>
/contract cancel <id>
/contract dispute <id> <原因>
```

命令别名：

```text
/contracts
/ct
```

## 权限

```text
contracts.use
contracts.create
contracts.accept
contracts.submit
contracts.approve
contracts.cancel
contracts.dispute
contracts.mediate
contracts.admin
contracts.admin.reload
contracts.admin.settle
contracts.admin.view
```

## 管理命令

```text
/contract all [页码]
/contract admin reload
/contract admin pay <id>
/contract admin refund <id>
/contract admin close <id>
```

`admin close` 只关闭合同并写入事件日志，不移动任何资金。需要资金处理时先使用 `admin pay` 或 `admin refund`，或由管理员在线下核对后再 close。

## 合同类型与状态

- `SERVICE`：传统委托。创建者托管奖金，其他玩家接单，接单者提交完成，创建者 approve 后付款。
- `WAGER`：对赌。甲方创建时托管押注，乙方 accept 时托管同额押注，指定仲裁者用 `/contract resolve <id> <a|b>` 裁决胜方。
- `PARTNERSHIP`：合作。甲方创建时托管自己的押注，乙方 accept 时托管自己的押注，双方都 `/contract approve <id>` 后按规则结算。

SERVICE 和 PARTNERSHIP 可选 `--mediator <中间人>`。中间人不是收款方，也不会经手资金；他必须先 `/contract mediate <id> accept` 接受职责，之后可在合同已生效且未结束时裁决：

- `pay` / `contractor`：认定完成或接单方胜，按成功规则付款。
- `refund` / `owner`：认定失效或创建方胜，按失败/退款规则处理。
- PARTNERSHIP 还可用 `a` / `b` 裁定甲方或乙方胜。

WAGER 使用创建时必填的仲裁者和 `/contract resolve <id> <a|b>`，保持原流程。

主要状态：

- `OPEN`：公开 SERVICE，等待接单。
- `PENDING_ACCEPT`：WAGER/PARTNERSHIP 邀请已发出，等待指定对方接受。
- `IN_PROGRESS`：已接单或邀请已接受。
- `SUBMITTED`：SERVICE 已提交完成，等待创建者确认。
- `COMPLETED`、`CANCELLED`、`EXPIRED`、`DISPUTED`：终态或管理员待处理状态。

GUI 合同大厅支持按全部/SERVICE/WAGER/PARTNERSHIP 筛选；“我的合同”会显示与玩家相关的待接受邀请、进行中、争议和历史合同。`/contract admin reload` 会关闭旧 GUI 会话，避免玩家在重载后继续操作旧数据。

## 资金规则

创建合同时立刻扣除：

- 合同奖金
- 创建费

SERVICE 奖金进入插件托管记录。创建费直接作为经济回收。

WAGER 创建时扣除甲方押注；乙方接受时扣除乙方押注。裁决后胜方获得双方押注扣除完成佣金后的金额，佣金作为经济回收。待接受超时或甲方取消时只退还甲方已托管押注，不会给未接受的乙方付款。

PARTNERSHIP 创建时扣除甲方押注；乙方接受时扣除乙方押注。双方确认成功时各自取回自己的押注扣除完成佣金后的金额；取消、超时或管理员退款按当前状态退回已托管押注。

雇主确认后：

- 接单者获得 `奖金 - 完成佣金`
- 完成佣金作为经济回收

公开合同取消或过期：

- 奖金退回雇主
- 创建费不退

进行中合同由接单者取消或到期：

- 奖金退回雇主

进行中或待确认合同由雇主取消：

- 进入争议状态，等待管理员处理

结算付款会先写入 pending settlement/payout 记录，再执行 Vault deposit。重启恢复不会自动重放 deposit；如果发现未完成的 payout 或 settlement，合同会进入争议状态并写入事件日志，等待管理员核对，避免重复付款。

## 配置

主要配置在 `config.yml`：

```yaml
language: zh_CN

economy:
  min-reward: 100.0
  max-reward: 100000.0
  creation-fee: 20.0
  completion-commission-percent: 5.0

limits:
  max-open-contracts: 3
  max-active-accepted-contracts: 3
  max-title-length: 80
  max-description-length: 500
  min-deadline-hours: 1
  max-deadline-hours: 168

expiry:
  cleanup-interval-minutes: 10
  submitted-auto-approve-hours: 72

storage:
  flush-interval-seconds: 30

display:
  page-size: 8
  currency-prefix: "$"
```

语言文件位于 `lang/zh_CN.yml` 和 `lang/en_US.yml`，可通过 `language` 选择。

## 存储

合同数据保存到：

```text
plugins/Contracts/contracts.yml
plugins/Contracts/pending-transactions.yml
plugins/Contracts/events.log
```

当前版本使用 Bukkit YAML 存储，避免引入 SQLite/MySQL 驱动。后续如果合同数量明显变多，再考虑数据库层。
