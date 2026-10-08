package dev.recipebooksplitter.harness;

import net.fabricmc.api.ClientModInitializer;

public final class HarnessInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Harness.init();
    }
}
