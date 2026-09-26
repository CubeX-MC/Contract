# Contract R1 锁定重放验证 — 2026-09-23

`ContractEscrowServiceImpl.lock` 原来先执行 WAGER 当前资格检查，再读取已持久化的锁。一次比赛结算或合同进入争议后，同一 operation id 的锁定重放会被误报 `NOT_ELIGIBLE`。现在先核对已落盘的锁、场地和 operation id：两者相同返回 `REPLAYED`；不同 ID 或场地返回 `LOCK_CONFLICT`。没有已落盘锁时仍执行原来的资格检查。`check` 对已结算或争议 WAGER 继续返回 `NOT_ELIGIBLE`，不会开放新比赛。

新增自动化验证：已结算后从 `contract.yml` 重新加载再重放锁，不重复记锁或付款；不同 ID／场地均被拒绝；争议后同 ID 重放成功确认旧锁，但不重试付款。PowerShell 执行 `.\gradlew.bat :Contract:build :Contract:jarGate` 通过；174 项测试、0 failures、0 errors、0 skipped。`jarGate` 报告 `mode=EMBEDDED`、`unrelocatedKotlin=0`、`reflectImpl=0`、自有类字节码 major 61。

本机隔离目录 `build/framework-r1-smoke-20260923`，Paper 1.21.11 build 132、Java 21.0.5，仅监听 `127.0.0.1:25580`。新 Contract JAR 与 Regions、Vault 1.7.3、EssentialsX 2.20.1 联合启用；Contract 成功连接 Vault，Regions 校验不存在的 WAGER 返回 `CONTRACT_NOT_FOUND`；`contract admin reload`、`regions reload` 和正常关闭通过。EssentialsX 提示此 Paper 版本不受支持。没有玩家连接，未创建实际 WAGER，也未测试 Vault 余额流动。

产物 `Contract/build/libs/contract-0.1.0.jar`，2,650,201 字节；SHA-256 `A2DB88F42E677B969E506C6DD215DA51833735923E77A0CB99CFBAA8C652984D`。真实 WAGER 余额、Vault 提供方卸载、进程故障、`REVIEW_REQUIRED` 人工复核与 Folia 仍须按 [R1 清单](../../REAL_SERVER_TEST.md) 验收。
