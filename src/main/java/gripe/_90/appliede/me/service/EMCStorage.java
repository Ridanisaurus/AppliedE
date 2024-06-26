package gripe._90.appliede.me.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;

import com.google.common.primitives.Ints;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.core.stats.AeStats;

import gripe._90.appliede.AppliedE;
import gripe._90.appliede.AppliedEConfig;
import gripe._90.appliede.me.key.EMCKey;
import gripe._90.appliede.menu.TransmutationTerminalMenu;

import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.proxy.IEMCProxy;

public final class EMCStorage implements MEStorage {
    private static final int MAX_OPERATIONS = 96;

    private final KnowledgeService service;
    private int highestTier = 1;

    EMCStorage(KnowledgeService service) {
        this.service = service;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        var emc = service.getEmc();
        var currentTier = 1;

        while (emc.divide(AppliedE.TIER_LIMIT).signum() == 1) {
            out.add(EMCKey.tier(currentTier), emc.remainder(AppliedE.TIER_LIMIT).longValue());
            emc = emc.divide(AppliedE.TIER_LIMIT);
            currentTier++;
        }

        out.add(EMCKey.tier(currentTier), emc.longValue());

        if (highestTier != currentTier) {
            highestTier = currentTier;
            service.updatePatterns();
        }
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0 || !(what instanceof EMCKey emc)) {
            return 0;
        }

        if (mode == Actionable.MODULATE) {
            var providers = new ArrayList<>(service.getProviders());
            Collections.shuffle(providers);

            var toInsert = BigInteger.valueOf(amount).multiply(AppliedE.TIER_LIMIT.pow(emc.getTier() - 1));
            var divisor = BigInteger.valueOf(service.getProviders().size());
            var quotient = toInsert.divide(divisor);
            var remainder = toInsert.remainder(divisor).longValue();

            for (var p = 0; p < providers.size(); p++) {
                var provider = providers.get(p);
                provider.setEmc(provider.getEmc().add(quotient.add(p < remainder ? BigInteger.ONE : BigInteger.ZERO)));
            }

            service.syncEmc();
        }

        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0) {
            return 0;
        }

        if (what instanceof AEItemKey item && source.player().isPresent()) {
            return extractItem(item, amount, mode, source, false);
        }

        if (!(what instanceof EMCKey emc)) {
            return 0;
        }

        var multiplier = AppliedE.TIER_LIMIT.pow(emc.getTier() - 1);
        var rawEmc = BigInteger.valueOf(amount).multiply(multiplier);
        var extracted = BigInteger.ZERO;

        var providers = new ArrayList<IKnowledgeProvider>();

        if (source.player().isPresent() && AppliedEConfig.CONFIG.terminalExtractFromOwnEmcOnly()) {
            var provider = service.getProviderFor(source.player().get());
            providers.add(provider.get());
        } else {
            providers.addAll(service.getProviders());
        }

        while (!providers.isEmpty() && extracted.compareTo(rawEmc) < 0) {
            Collections.shuffle(providers);

            var toExtract = rawEmc.subtract(extracted);
            var divisor = BigInteger.valueOf(service.getProviders().size());
            var quotient = toExtract.divide(divisor);
            var remainder = toExtract.remainder(divisor).longValue();

            for (var p = 0; p < providers.size(); p++) {
                var provider = providers.get(p);

                var currentEmc = provider.getEmc();
                var toExtractFrom = quotient.add(p < remainder ? BigInteger.ONE : BigInteger.ZERO);

                if (currentEmc.compareTo(toExtractFrom) <= 0) {
                    if (mode == Actionable.MODULATE) {
                        provider.setEmc(BigInteger.ZERO);
                    }

                    extracted = extracted.add(currentEmc);
                    // provider exhausted, remove from current list to re-extract deficit from remaining providers
                    providers.remove(provider);
                } else {
                    if (mode == Actionable.MODULATE) {
                        provider.setEmc(currentEmc.subtract(toExtractFrom));
                    }

                    extracted = extracted.add(toExtractFrom);
                }
            }
        }

        if (mode == Actionable.MODULATE) {
            service.syncEmc();
        }

        return extracted.divide(multiplier).longValue();
    }

    public long insertItem(
            AEItemKey what, long amount, Actionable mode, IActionSource source, boolean mayLearn, Runnable onLearn) {
        if (amount <= 0 || service.getProviders().isEmpty()) {
            return 0;
        }

        if (!mayLearn && !service.knowsItem(what) || !IEMCProxy.INSTANCE.hasValue(what.toStack())) {
            return 0;
        }

        var playerProvider = source.player().map(service::getProviderFor).orElse(null);
        var machineProvider = source.machine().map(service::getProviderFor).orElse(null);

        if (mayLearn) {
            if (source.player().isPresent() && playerProvider == null) {
                return 0;
            }

            if (source.machine().isPresent() && machineProvider == null) {
                return 0;
            }
        }

        var itemEmc = BigInteger.valueOf(IEMCProxy.INSTANCE.getSellValue(what.toStack()));
        var totalEmc = itemEmc.multiply(BigInteger.valueOf(amount));
        var totalInserted = 0L;

        for (var i = 0; i < MAX_OPERATIONS && totalEmc.compareTo(BigInteger.ZERO) > 0; i++) {
            var canDeposit = itemEmc.longValue();

            if (mode == Actionable.MODULATE) {
                canDeposit = getAmountAfterPowerExpenditure(canDeposit);
                insert(EMCKey.BASE, canDeposit, Actionable.MODULATE, source);
            }

            var inserted = canDeposit >= itemEmc.longValue() ? 1 : 0;

            if (inserted == 0) {
                break;
            }

            totalInserted += inserted;
            source.player().ifPresent(player -> {
                if (mode == Actionable.MODULATE) {
                    AeStats.ItemsInserted.addToPlayer(player, Ints.saturatedCast(inserted));
                }
            });

            totalEmc = totalEmc.subtract(itemEmc);
        }

        if (mode == Actionable.MODULATE && mayLearn && totalInserted > 0) {
            source.player().ifPresent(player -> {
                if (playerProvider != null && !playerProvider.get().hasKnowledge(what.toStack())) {
                    addKnowledge(what, playerProvider.get(), player);
                    onLearn.run();
                }
            });
            source.machine().ifPresent(host -> {
                if (machineProvider != null && !machineProvider.get().hasKnowledge(what.toStack())) {
                    var node = Objects.requireNonNull(host.getActionableNode());
                    var player = IPlayerRegistry.getConnected(node.getLevel().getServer(), node.getOwningPlayerId());
                    addKnowledge(what, machineProvider.get(), player);
                    onLearn.run();
                }
            });
        }

        return totalInserted;
    }

    public long insertItem(AEItemKey what, long amount, Actionable mode, IActionSource source, boolean mayLearn) {
        return insertItem(what, amount, mode, source, mayLearn, () -> {});
    }

    public long extractItem(AEItemKey what, long amount, Actionable mode, IActionSource source, boolean skipStored) {
        if (source.player().isPresent()
                && !(source.player().get().containerMenu instanceof TransmutationTerminalMenu)) {
            return 0;
        }

        if (amount <= 0 || !service.knowsItem(what)) {
            return 0;
        }

        var existingStored = service.getGrid().getStorageService().getCachedInventory();

        if (!skipStored && existingStored.get(what) > 0) {
            return 0;
        }

        var itemEmc = BigInteger.valueOf(IEMCProxy.INSTANCE.getValue(what.toStack()));
        var totalEmc = itemEmc.multiply(BigInteger.valueOf(amount));
        var totalExtracted = 0L;

        for (var i = 0; i < MAX_OPERATIONS && totalEmc.compareTo(BigInteger.ZERO) > 0; i++) {
            var canWithdraw = extract(EMCKey.BASE, itemEmc.longValue(), Actionable.SIMULATE, source);

            if (mode == Actionable.MODULATE) {
                canWithdraw = getAmountAfterPowerExpenditure(canWithdraw);
                extract(EMCKey.BASE, canWithdraw, Actionable.MODULATE, source);
            }

            var extracted = canWithdraw >= itemEmc.longValue() ? 1 : 0;

            if (extracted == 0) {
                break;
            }

            totalExtracted += extracted;
            source.player().ifPresent(player -> {
                if (mode == Actionable.MODULATE) {
                    AeStats.ItemsExtracted.addToPlayer(player, Ints.saturatedCast(extracted));
                }
            });

            totalEmc = totalEmc.subtract(itemEmc);
        }

        return totalExtracted;
    }

    private void addKnowledge(AEItemKey what, IKnowledgeProvider provider, Player player) {
        var stack = what.toStack();
        provider.addKnowledge(stack);

        if (player instanceof ServerPlayer serverPlayer) {
            provider.syncKnowledgeChange(serverPlayer, ItemInfo.fromStack(stack), true);
        }
    }

    private long getAmountAfterPowerExpenditure(long maxAmount) {
        var energyService = service.getGrid().getEnergyService();
        var availablePower = energyService.extractAEPower(maxAmount, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        energyService.extractAEPower(Math.min(maxAmount, availablePower), Actionable.MODULATE, PowerMultiplier.CONFIG);
        return (double) maxAmount <= availablePower ? maxAmount : (long) availablePower;
    }

    int getHighestTier() {
        return highestTier;
    }

    @Override
    public Component getDescription() {
        return AppliedE.EMC_MODULE.get().getDescription();
    }
}
