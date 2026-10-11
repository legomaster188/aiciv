package com.aiciv;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiCiv implements ModInitializer {
    public static final String MOD_ID = "aiciv";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("AiCiv initialized");
    }
}
