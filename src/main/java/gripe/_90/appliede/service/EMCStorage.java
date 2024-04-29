package gripe._90.appliede.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;

import net.minecraft.network.chat.Component;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import gripe._90.appliede.AppliedE;
import gripe._90.appliede.key.EMCKey;

public record EMCStorage(KnowledgeService service) implements MEStorage {
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
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount == 0 || !(what instanceof EMCKey emc)) {
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
                var provider = providers.get(p).get();
                provider.setEmc(provider.getEmc().add(quotient.add(p < remainder ? BigInteger.ONE : BigInteger.ZERO)));
            }

            service.syncEmc();
        }

        return amount;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (amount == 0 || !(what instanceof EMCKey emc)) {
            return 0;
        }

        var extracted = 0L;
        var multiplier = AppliedE.TIER_LIMIT.pow(emc.getTier() - 1);

        var providers = new ArrayList<>(service.getProviders());

        while (!providers.isEmpty() && extracted < amount) {
            Collections.shuffle(providers);

            var toExtract = BigInteger.valueOf(amount - extracted).multiply(multiplier);
            var divisor = BigInteger.valueOf(service.getProviders().size());
            var quotient = toExtract.divide(divisor);
            var remainder = toExtract.remainder(divisor).longValue();

            for (var p = 0; p < providers.size(); p++) {
                var provider = providers.get(p);

                var currentEmc = provider.get().getEmc();
                var toExtractFrom = quotient.add(p < remainder ? BigInteger.ONE : BigInteger.ZERO);

                if (currentEmc.compareTo(toExtractFrom) <= 0) {
                    if (mode == Actionable.MODULATE) {
                        provider.get().setEmc(BigInteger.ZERO);
                    }

                    extracted += currentEmc.divide(multiplier).longValue();
                    // provider exhausted, remove from providers and re-extract deficit from remaining providers
                    providers.remove(provider);
                } else {
                    if (mode == Actionable.MODULATE) {
                        provider.get().setEmc(currentEmc.subtract(toExtractFrom));
                    }

                    extracted += toExtractFrom.divide(multiplier).longValue();
                }
            }
        }

        if (mode == Actionable.MODULATE) {
            service.syncEmc();
        }

        return extracted;
    }

    @Override
    public Component getDescription() {
        return AppliedE.EMC_MODULE.get().getDescription();
    }
}
