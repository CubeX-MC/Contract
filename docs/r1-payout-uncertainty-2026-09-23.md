# Contract R1 付款回执不确定性 — 2026-09-23

原先 `ContractService.depositWithPending` 在 Vault 的入账调用返回失败时立即删除 `DEPOSIT` 待办，并把该次结算视为没有外部资金影响。Vault 返回失败不能证明余额没有变化；若入账已发生而回执失败，同一 WAGER 可以再次尝试付款，丢失核账证据。

现在区分两种边界：写入付款待办失败时没有发起经济操作，可按原规则返回失败；待办已落盘并调用经济服务后，只要入账回执失败就保留 `DEPOSIT`，将合同转入 `DISPUTED` 并返回 `REVIEW_REQUIRED`。再次提交同一 operation id 不会重复调用经济服务。保守处理也会把实际未入账的失败交给人工核对，以避免不确定时双付。

测试使用真实 `ContractService`、`ContractEscrowServiceImpl`、`ContractStorage` 与 `PendingTransactionStore`，以模拟经济服务注入两类回执：首笔拒绝；第一笔成功、第二笔实际入账后返回失败。两类都保留失败那笔待办、落盘争议状态，重放不再付款。PowerShell 执行 `.\gradlew.bat :Contract:build :Contract:jarGate` 成功；176 项测试，0 failures、0 errors、0 skipped。`jarGate` 报告 `mode=EMBEDDED`、`unrelocatedKotlin=0`、`reflectImpl=0`、自有类 major 61。

最终 JAR 在隔离目录 `build/framework-r1-smoke-20260923` 的 Paper 1.21.11 build 132／Java 21.0.5 上，与 Regions、Vault 1.7.3、EssentialsX 2.20.1 联合启用；Contract 连接 Vault，`contract admin reload`、Regions 经 Contract 返回不存在的 WAGER 及正常关闭通过。仅监听 `127.0.0.1:25580`。EssentialsX 提示该 Paper 版本不受支持。没有玩家或真实 WAGER 余额流动，因此这不是 Vault 真钱故障注入验收。

产物 `Contract/build/libs/contract-0.1.0.jar`，2,652,133 字节；SHA-256 `72A2985253542D09CA1EFD47F1BFAD82535787CF03F5BD4232E09573AF72BBAF`。剩余真实余额守恒、Vault 提供方卸载、进程故障和 `REVIEW_REQUIRED` 人工处理路径见 [R1 清单](../../REAL_SERVER_TEST.md)。
