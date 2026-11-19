package org.polyfrost.compose.minecraft;

import java.util.List;

public class PolyComposeOptionalMixinPlugin extends PolyComposeMixinPlugin {
    @Override
    protected boolean isOptional() {
        return true;
    }

    @Override
    public List<String> getMixins() {
        return optionalMixins();
    }
}
