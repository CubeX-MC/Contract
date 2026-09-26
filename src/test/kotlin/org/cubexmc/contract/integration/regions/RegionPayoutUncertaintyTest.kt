package org.cubexmc.contract.integration.regions

import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.api.escrow.ContractEscrowCode
import org.cubexmc.contract.config.LanguageManager
import org.cubexmc.contract.economy.EconomyService
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.service.ContractService
import org.cubexmc.contract.storage.BatchAcceptanceStore
import org.cubexmc.contract.storage.ContractStorage
import org.cubexmc.contract.storage.EventLog
import org.cubexmc.contract.storage.PendingTransactionStore
import org.cubexmc.core.CubexLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class RegionPayoutUncertaintyTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `failed Vault payout retains pending evidence and cannot retry the WAGER`() {
        val fixture = fixture()
        `when`(fixture.economy.deposit(fixture.partyA, BigDecimal("9.50")))
            .thenReturn(EconomyService.TransactionResult.fail("provider reply was not confirmed"))

        val failed = fixture.escrow.settle("match-uncertain", fixture.contract.id(), "arena", fixture.partyA)
        val replay = fixture.escrow.settle("match-uncertain", fixture.contract.id(), "arena", fixture.partyA)

        assertFalse(failed.successful())
        assertEquals(ContractEscrowCode.REVIEW_REQUIRED, failed.code())
        assertEquals(ContractEscrowCode.REVIEW_REQUIRED, replay.code())
        assertEquals(ContractStatus.DISPUTED, fixture.contract.status())
        val depositIntent = fixture.pending.loadAll().single()
        assertEquals(PendingTransactionStore.PendingType.DEPOSIT, depositIntent.type())
        assertEquals(fixture.partyA, depositIntent.playerUuid())
        assertEquals(BigDecimal("9.50"), depositIntent.amount())
        val reloaded = ContractStorage(tempDir.resolve("contract.yml").toFile(), fixture.logger).apply { load() }
        assertEquals(ContractStatus.DISPUTED, reloaded.findById(fixture.contract.id()).orElseThrow().status())
        verify(fixture.economy, times(1)).deposit(fixture.partyA, BigDecimal("9.50"))
    }

    @Test
    fun `partial payout with lost failure reply never pays the same WAGER twice`() {
        val fixture = fixture()
        var credited = BigDecimal.ZERO
        var calls = 0
        `when`(fixture.economy.deposit(fixture.partyA, BigDecimal("9.50"))).thenAnswer {
            calls++
            credited = credited.add(BigDecimal("9.50"))
            if (calls == 1) EconomyService.TransactionResult.ok()
            else EconomyService.TransactionResult.fail("reply lost after credit")
        }

        val failed = fixture.escrow.settle("match-uncertain", fixture.contract.id(), "arena", fixture.partyA)
        val replay = fixture.escrow.settle("match-uncertain", fixture.contract.id(), "arena", fixture.partyA)

        assertEquals(ContractEscrowCode.REVIEW_REQUIRED, failed.code())
        assertEquals(ContractEscrowCode.REVIEW_REQUIRED, replay.code())
        assertEquals(2, calls)
        assertEquals(BigDecimal("19.00"), credited)
        assertEquals(ContractStatus.DISPUTED, fixture.contract.status())
        assertEquals(listOf(PendingTransactionStore.PendingType.DEPOSIT), fixture.pending.loadAll().map { it.type() })
    }

    private fun fixture(): Fixture {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val contract = Contract.createWager(
            "wager-uncertain",
            partyA,
            "Party A",
            partyB,
            "Party B",
            UUID.randomUUID(),
            "Arbiter",
            "Arena wager",
            "Fund a Regions match",
            BigDecimal.TEN,
            BigDecimal("5"),
            1L,
            10_000L,
        )
        contract.status(ContractStatus.IN_PROGRESS)
        val logger = CubexLogger(Logger.getLogger("RegionPayoutUncertaintyTest"))
        val storage = ContractStorage(tempDir.resolve("contract.yml").toFile(), logger)
        storage.put(contract)
        storage.save()
        val pending = PendingTransactionStore(tempDir.resolve("pending-transactions.yml").toFile(), logger)
        val plugin = mock(ContractPlugin::class.java)
        val lang = mock(LanguageManager::class.java)
        `when`(plugin.lang()).thenReturn(lang)
        `when`(plugin.log()).thenReturn(logger)
        `when`(lang.ui(anyString(), anyMap())).thenAnswer { it.getArgument<String>(0) }
        val economy = mock(EconomyService::class.java)
        val contracts = ContractService(
            plugin,
            storage,
            economy,
            pending,
            mock(EventLog::class.java),
            mock(BatchAcceptanceStore::class.java),
        )
        val escrow = ContractEscrowServiceImpl(storage, contracts, logger)
        assertTrue(escrow.lock("match-uncertain", contract.id(), "arena").successful())
        return Fixture(contract, partyA, economy, pending, escrow, logger)
    }

    private data class Fixture(
        val contract: Contract,
        val partyA: UUID,
        val economy: EconomyService,
        val pending: PendingTransactionStore,
        val escrow: ContractEscrowServiceImpl,
        val logger: CubexLogger,
    )
}
