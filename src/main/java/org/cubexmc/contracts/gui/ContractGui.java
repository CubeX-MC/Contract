package org.cubexmc.contracts.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.cubexmc.contracts.ContractsPlugin;
import org.cubexmc.contracts.model.Contract;
import org.cubexmc.contracts.model.ContractStatus;
import org.cubexmc.contracts.model.ContractType;
import org.cubexmc.contracts.model.Participant;
import org.cubexmc.contracts.model.ParticipantRole;
import org.cubexmc.contracts.service.ServiceResult;
import org.cubexmc.contracts.util.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ContractGui implements Listener {
    private static final String BOARD_TITLE = Text.color("&#F4D03F合同大厅");
    private static final String MY_TITLE = Text.color("&#F4D03F我的合同");
    private static final String DETAIL_TITLE_PREFIX = Text.color("&#F4D03F合同详情 ");
    private static final int[] BOARD_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private static final long DISPUTE_PROMPT_TIMEOUT_MS = 60_000L;

    private final ContractsPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, DisputePrompt> disputePrompts = new HashMap<>();

    public ContractGui(ContractsPlugin plugin) {
        this.plugin = plugin;
    }

    public void openBoard(Player player, BoardMode mode, int page) {
        openBoard(player, mode, page, TypeFilter.ALL);
    }

    public void openBoard(Player player, BoardMode mode, int page, TypeFilter filter) {
        List<Contract> contracts = contractsFor(player, mode, filter);
        int pages = Math.max(1, (int) Math.ceil((double) contracts.size() / BOARD_SLOTS.length));
        int currentPage = Math.min(Math.max(1, page), pages);
        Inventory inventory = Bukkit.createInventory(null, 54, mode == BoardMode.MINE ? MY_TITLE : BOARD_TITLE);
        Session session = new Session(ViewType.BOARD, mode, currentPage, filter, null);

        fillBorder(inventory);
        inventory.setItem(1, filterButton(TypeFilter.ALL, filter, Material.COMPASS, "全部"));
        inventory.setItem(2, filterButton(TypeFilter.SERVICE, filter, Material.PAPER, "委托"));
        inventory.setItem(3, filterButton(TypeFilter.WAGER, filter, Material.TARGET, "对赌"));
        inventory.setItem(4, filterButton(TypeFilter.PARTNERSHIP, filter, Material.AMETHYST_CLUSTER, "合作"));

        int start = (currentPage - 1) * BOARD_SLOTS.length;
        int end = Math.min(start + BOARD_SLOTS.length, contracts.size());
        for (int index = start; index < end; index++) {
            int slot = BOARD_SLOTS[index - start];
            Contract contract = contracts.get(index);
            inventory.setItem(slot, contractItem(contract));
            session.slotContracts.put(slot, contract.id());
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));
        inventory.setItem(49, button(Material.BOOK, mode == BoardMode.MINE ? "&#F4D03F我的合同" : "&#F4D03F合同大厅",
            "&#CFD8DC合同数: &#FFFFFF" + contracts.size(),
            "&#CFD8DC点击合同查看详情"));
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));
        inventory.setItem(47, button(Material.CHEST, "&#69DB7C我的合同", "&#CFD8DC查看我发布或接取的合同"));
        inventory.setItem(51, button(Material.EMERALD, "&#69DB7C公开合同", "&#CFD8DC返回合同大厅"));

        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    public void openDetails(Player player, Contract contract, BoardMode backMode, int backPage) {
        openDetails(player, contract, backMode, backPage, TypeFilter.ALL);
    }

    public void openDetails(Player player, Contract contract, BoardMode backMode, int backPage, TypeFilter filter) {
        Inventory inventory = Bukkit.createInventory(null, 27, DETAIL_TITLE_PREFIX + Text.color("&#FFE066#" + contract.shortId()));
        fillBorder(inventory);
        inventory.setItem(13, detailItem(contract));

        boolean mediator = isArbiter(contract, player.getUniqueId());
        if (contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            inventory.setItem(10, button(Material.LECTERN, "&#69DB7C接受中间人职责",
                "&#CFD8DC接受后可在争议或失效时裁决"));
        } else if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
            if (contract.type() == ContractType.SERVICE) {
                inventory.setItem(10, button(Material.EMERALD, "&#69DB7C裁定付款", "&#CFD8DC认定合同有效完成并付款"));
                inventory.setItem(11, button(Material.REDSTONE, "&#E63946裁定退款", "&#CFD8DC认定合同失效并退款"));
            } else if (contract.type() == ContractType.PARTNERSHIP) {
                inventory.setItem(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC甲方获得争议裁决"));
                inventory.setItem(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC乙方获得争议裁决"));
                inventory.setItem(12, button(Material.GOLD_INGOT, "&#FFE066退回双方押注", "&#CFD8DC按失败/失效规则退款"));
            }
        } else if (contract.status() == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
            inventory.setItem(10, button(Material.EMERALD_BLOCK, "&#69DB7C接受邀请",
                "&#CFD8DC将扣除你的押注并进入进行中"));
        } else if (contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.OPEN
            && !contract.ownerUuid().equals(player.getUniqueId())
            && !mediator) {
            inventory.setItem(10, button(Material.EMERALD_BLOCK, "&#69DB7C接下合同", "&#CFD8DC托管奖金: &#69DB7C" + plugin.economy().format(contract.reward())));
        }
        if (contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.IN_PROGRESS
            && player.getUniqueId().equals(contract.contractorUuid())) {
            inventory.setItem(10, button(Material.DIAMOND, "&#CDE0F5提交完成", "&#CFD8DC等待雇主确认后付款"));
        }
        if (contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.SUBMITTED
            && player.getUniqueId().equals(contract.ownerUuid())) {
            inventory.setItem(10, button(Material.EMERALD, "&#69DB7C确认付款", "&#CFD8DC付款: &#69DB7C" + plugin.economy().format(contract.payoutAmount())));
        }
        if (contract.type() == ContractType.PARTNERSHIP
            && contract.status() == ContractStatus.IN_PROGRESS
            && isParty(contract, player.getUniqueId())) {
            inventory.setItem(10, button(Material.EMERALD, "&#69DB7C确认合作完成", "&#CFD8DC双方确认后按合作规则结算"));
        }
        if (contract.type() == ContractType.WAGER
            && (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED)
            && isArbiter(contract, player.getUniqueId())) {
            inventory.setItem(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC将按对赌规则付款给甲方"));
            inventory.setItem(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC将按对赌规则付款给乙方"));
        }
        if (!contract.status().isFinal() && canCancel(player, contract)) {
            inventory.setItem(15, button(Material.BARRIER, "&#E63946取消合同", "&#CFD8DC按规则退款或转入争议"));
        }
        if (canDispute(contract)) {
            inventory.setItem(16, button(Material.REDSTONE_BLOCK, "&#E63946发起争议", "&#CFD8DC点击后在聊天输入原因",
                "&#CFD8DC60 秒内输入,或输 cancel 取消"));
        }
        inventory.setItem(22, button(Material.ARROW, "&#FFE066返回", "&#CFD8DC回到上一页"));

        Session session = new Session(ViewType.DETAILS, backMode, backPage, filter, contract.id());
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    public void closeSessions() {
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && isManagedTitle(player.getOpenInventory().getTitle())) {
                player.closeInventory();
            }
        }
        sessions.clear();
        disputePrompts.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getClickedInventory() == null || event.getView().getTopInventory() != event.getClickedInventory()) {
            return;
        }
        event.setCancelled(true);

        if (session.type == ViewType.BOARD) {
            handleBoardClick(player, session, event.getSlot());
            return;
        }
        handleDetailsClick(player, session, event.getSlot());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !isManagedTitle(player.getOpenInventory().getTitle())) {
                sessions.remove(playerId);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        disputePrompts.remove(event.getPlayer().getUniqueId());
    }

    private void handleBoardClick(Player player, Session session, int slot) {
        if (slot == 1) {
            openBoard(player, session.mode, 1, TypeFilter.ALL);
            return;
        }
        if (slot == 2) {
            openBoard(player, session.mode, 1, TypeFilter.SERVICE);
            return;
        }
        if (slot == 3) {
            openBoard(player, session.mode, 1, TypeFilter.WAGER);
            return;
        }
        if (slot == 4) {
            openBoard(player, session.mode, 1, TypeFilter.PARTNERSHIP);
            return;
        }
        if (slot == 45 && session.page > 1) {
            openBoard(player, session.mode, session.page - 1, session.filter);
            return;
        }
        if (slot == 53) {
            openBoard(player, session.mode, session.page + 1, session.filter);
            return;
        }
        if (slot == 47) {
            openBoard(player, BoardMode.MINE, 1, session.filter);
            return;
        }
        if (slot == 51) {
            openBoard(player, BoardMode.OPEN, 1, session.filter);
            return;
        }
        String contractId = session.slotContracts.get(slot);
        if (contractId == null) {
            return;
        }
        plugin.storage().findByPrefix(contractId)
            .ifPresent(contract -> openDetails(player, contract, session.mode, session.page, session.filter));
    }

    private void handleDetailsClick(Player player, Session session, int slot) {
        Optional<Contract> optional = session.contractId == null ? Optional.empty() : plugin.storage().findByPrefix(session.contractId);
        if (slot == 22) {
            openBoard(player, session.mode, session.page, session.filter);
            return;
        }
        if (optional.isEmpty()) {
            player.closeInventory();
            player.sendMessage(plugin.lang().message("not-found"));
            return;
        }
        Contract contract = optional.get();
        ServiceResult result = null;
        if (slot == 10) {
            if (contract.type() != ContractType.WAGER && isArbiter(contract, player.getUniqueId()) && !contract.arbiterAccepted()) {
                result = plugin.contracts().acceptMediation(player, contract);
            } else if (contract.type() != ContractType.WAGER && isArbiter(contract, player.getUniqueId()) && canMediate(contract)) {
                result = plugin.contracts().mediate(player, contract, contract.type() == ContractType.SERVICE ? "pay" : "a");
            } else if (contract.status() == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
                result = plugin.contracts().accept(player, contract);
            } else if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.OPEN) {
                result = plugin.contracts().accept(player, contract);
            } else if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS) {
                result = plugin.contracts().submit(player, contract);
            } else if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.SUBMITTED) {
                result = plugin.contracts().approve(player, contract);
            } else if (contract.type() == ContractType.PARTNERSHIP && contract.status() == ContractStatus.IN_PROGRESS) {
                result = plugin.contracts().approve(player, contract);
            } else if (contract.type() == ContractType.WAGER && isArbiter(contract, player.getUniqueId())) {
                result = plugin.contracts().resolveWager(player, contract, "a");
            }
        } else if (slot == 11 && isArbiter(contract, player.getUniqueId())) {
            if (contract.type() == ContractType.WAGER) {
                result = plugin.contracts().resolveWager(player, contract, "b");
            } else if (contract.type() == ContractType.SERVICE) {
                result = plugin.contracts().mediate(player, contract, "refund");
            } else if (contract.type() == ContractType.PARTNERSHIP) {
                result = plugin.contracts().mediate(player, contract, "b");
            }
        } else if (slot == 12 && contract.type() == ContractType.PARTNERSHIP && isArbiter(contract, player.getUniqueId())) {
            result = plugin.contracts().mediate(player, contract, "refund");
        } else if (slot == 15) {
            result = plugin.contracts().cancel(player, contract);
        } else if (slot == 16 && canDispute(contract)) {
            beginDisputePrompt(player, contract, session);
            return;
        }
        if (result == null) {
            return;
        }
        if (result.success()) {
            player.sendMessage(Text.color("&#69DB7C操作成功。"));
            openDetails(player, result.contract(), session.mode, session.page, session.filter);
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", Map.of("reason", result.reason())));
        }
    }

    private boolean canDispute(Contract contract) {
        ContractStatus status = contract.status();
        return status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED;
    }

    private void beginDisputePrompt(Player player, Contract contract, Session session) {
        disputePrompts.put(player.getUniqueId(), new DisputePrompt(
            contract.id(),
            session.mode,
            session.page,
            session.filter,
            System.currentTimeMillis() + DISPUTE_PROMPT_TIMEOUT_MS
        ));
        player.closeInventory();
        player.sendMessage(Text.color("&#FFE066请在 60 秒内输入争议原因,或输入 &#E63946cancel &#FFE066取消。"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            DisputePrompt prompt = disputePrompts.get(player.getUniqueId());
            if (prompt != null && prompt.contractId.equals(contract.id())
                && System.currentTimeMillis() >= prompt.expiresAt) {
                disputePrompts.remove(player.getUniqueId());
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current != null) {
                    current.sendMessage(Text.color("&#E63946争议输入超时,已取消。"));
                }
            }
        }, DISPUTE_PROMPT_TIMEOUT_MS / 50L + 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        DisputePrompt prompt = disputePrompts.get(playerId);
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        disputePrompts.remove(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }
            if (System.currentTimeMillis() >= prompt.expiresAt) {
                player.sendMessage(Text.color("&#E63946争议输入超时,已取消。"));
                return;
            }
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(Text.color("&#FFE066已取消争议输入。"));
                return;
            }
            plugin.storage().findByPrefix(prompt.contractId).ifPresentOrElse(contract -> {
                ServiceResult result = plugin.contracts().dispute(player, contract, message);
                if (result.success()) {
                    player.sendMessage(plugin.lang().message("dispute-success"));
                    openDetails(player, contract, prompt.mode, prompt.page, prompt.filter);
                } else {
                    player.sendMessage(plugin.lang().message("operation-failed", Map.of("reason", result.reason())));
                }
            }, () -> player.sendMessage(plugin.lang().message("not-found")));
        });
    }

    private List<Contract> contractsFor(Player player, BoardMode mode, TypeFilter filter) {
        java.util.stream.Stream<Contract> stream;
        if (mode == BoardMode.MINE) {
            stream = plugin.contracts().allContracts().stream()
                .filter(contract -> contract.relatedTo(player.getUniqueId()));
        } else {
            stream = plugin.contracts().openContracts().stream();
        }
        return stream.filter(filter::matches).toList();
    }

    private ItemStack contractItem(Contract contract) {
        List<String> lore = new ArrayList<>();
        lore.add(Text.color("&#CFD8DCID: &#FFE066#" + contract.shortId()));
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF" + plugin.lang().type(contract.type())));
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF" + plugin.lang().status(contract.status())));
        lore.add(Text.color("&#CFD8DC发起: &#FFFFFF" + contract.ownerName()));
        lore.add(Text.color("&#CFD8DC对方: &#FFFFFF" + (contract.contractorName() == null ? "无" : contract.contractorName())));
        lore.add(Text.color("&#CFD8DC金额: &#69DB7C" + plugin.economy().format(contract.reward())));
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF" + DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))));
        lore.add("");
        lore.add(Text.color("&#FFE066点击查看详情"));
        ItemStack item = new ItemStack(materialFor(contract.type(), contract.status()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color("&#F4D03F" + contract.title()));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack detailItem(Contract contract) {
        List<String> lore = new ArrayList<>();
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF" + plugin.lang().type(contract.type())));
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF" + plugin.lang().status(contract.status())));
        for (Participant participant : contract.participants()) {
            lore.add(Text.color("&#CFD8DC" + plugin.lang().role(participant.role()) + ": &#FFFFFF"
                + (participant.displayName() == null ? "无" : participant.displayName())
                + " &#69DB7C" + plugin.economy().format(participant.moneyStake())));
        }
        if (contract.arbiter() != null) {
            lore.add(Text.color("&#CFD8DC仲裁者: &#FFFFFF" + contract.arbiter().displayName()
                + " &#CFD8DC(" + (contract.arbiterAccepted() ? "已接受" : "待接受") + ")"));
        }
        lore.add(Text.color("&#CFD8DC佣金率: &#FFE066" + contract.commissionPercent().toPlainString() + "%"));
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF" + DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))));
        lore.add("");
        lore.add(Text.color("&#F1F5F9" + contract.description()));
        if (contract.disputeReason() != null && !contract.disputeReason().isBlank()) {
            lore.add("");
            lore.add(Text.color("&#E63946争议: &#F1F5F9" + contract.disputeReason()));
        }
        ItemStack item = new ItemStack(materialFor(contract.type(), contract.status()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color("&#F4D03F" + contract.title()));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(Text.color(line));
            }
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBorder(Inventory inventory) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int index = 0; index < inventory.getSize(); index++) {
            int row = index / 9;
            int col = index % 9;
            if (row == 0 || row == inventory.getSize() / 9 - 1 || col == 0 || col == 8) {
                inventory.setItem(index, pane);
            }
        }
    }

    private ItemStack filterButton(TypeFilter filter, TypeFilter selected, Material material, String label) {
        String prefix = filter == selected ? "&#69DB7C" : "&#CFD8DC";
        return button(material, prefix + label, "&#CFD8DC点击筛选此类型");
    }

    private Material materialFor(ContractType type, ContractStatus status) {
        return switch (status) {
            case COMPLETED -> Material.EMERALD;
            case CANCELLED -> Material.BARRIER;
            case EXPIRED -> Material.CLOCK;
            case DISPUTED -> Material.REDSTONE;
            case PENDING_ACCEPT -> Material.YELLOW_BANNER;
            default -> switch (type) {
                case SERVICE -> Material.PAPER;
                case WAGER -> Material.TARGET;
                case PARTNERSHIP -> Material.AMETHYST_CLUSTER;
                case ALLIANCE -> Material.SHIELD;
                case BOUNTY -> Material.CROSSBOW;
                case SALE -> Material.CHEST;
                case LOAN -> Material.GOLD_INGOT;
            };
        };
    }

    private boolean canAcceptInvitation(Player player, Contract contract) {
        return player.getUniqueId().equals(contract.contractorUuid());
    }

    private boolean canCancel(Player player, Contract contract) {
        return contract.participantByUuid(player.getUniqueId()).isPresent();
    }

    private boolean canMediate(Contract contract) {
        return contract.arbiterAccepted()
            && !contract.status().isFinal()
            && contract.status() != ContractStatus.OPEN
            && contract.status() != ContractStatus.PENDING_ACCEPT;
    }

    private boolean isParty(Contract contract, UUID uuid) {
        return contract.participantByUuid(uuid)
            .map(participant -> participant.role() == ParticipantRole.PARTY_A
                || participant.role() == ParticipantRole.PARTY_B)
            .orElse(false);
    }

    private boolean isArbiter(Contract contract, UUID uuid) {
        return contract.arbiter() != null && uuid.equals(contract.arbiter().uuid());
    }

    private boolean isManagedTitle(String title) {
        return title.equals(BOARD_TITLE) || title.equals(MY_TITLE) || title.startsWith(DETAIL_TITLE_PREFIX);
    }

    public enum BoardMode {
        OPEN,
        MINE
    }

    public enum TypeFilter {
        ALL(null),
        SERVICE(ContractType.SERVICE),
        WAGER(ContractType.WAGER),
        PARTNERSHIP(ContractType.PARTNERSHIP);

        private final ContractType type;

        TypeFilter(ContractType type) {
            this.type = type;
        }

        private boolean matches(Contract contract) {
            return type == null || contract.type() == type;
        }
    }

    private enum ViewType {
        BOARD,
        DETAILS
    }

    private static final class DisputePrompt {
        private final String contractId;
        private final BoardMode mode;
        private final int page;
        private final TypeFilter filter;
        private final long expiresAt;

        private DisputePrompt(String contractId, BoardMode mode, int page, TypeFilter filter, long expiresAt) {
            this.contractId = contractId;
            this.mode = mode;
            this.page = page;
            this.filter = filter;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Session {
        private final ViewType type;
        private final BoardMode mode;
        private final int page;
        private final TypeFilter filter;
        private final String contractId;
        private final Map<Integer, String> slotContracts = new HashMap<>();

        private Session(ViewType type, BoardMode mode, int page, TypeFilter filter, String contractId) {
            this.type = type;
            this.mode = mode;
            this.page = page;
            this.filter = filter;
            this.contractId = contractId;
        }
    }
}
