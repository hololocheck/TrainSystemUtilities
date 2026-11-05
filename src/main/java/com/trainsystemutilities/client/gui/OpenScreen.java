package com.trainsystemutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Whether the screen asking for an effect the wiki's containment does not see is the screen the player has open (second
 * reading, 2026-10-07).
 *
 * <p>The wiki builds stand-ins of TSU's screens and draws them inside itself, so a stand-in is never the open screen. An
 * effect behind this check - a native dialog ({@link NativeFileDialog}), the profile icon written to disk, a preset
 * published over HTTP - therefore never starts from one, whatever its wiki mode does: each stand-in answers those buttons
 * itself in its wiki mode, and this is the second, independent guard in front of the effect. NativeFileDialogTest holds
 * the rule.
 */
public final class OpenScreen {

    private OpenScreen() {}

    /** Whether {@code requester} is the screen the player has open. */
    public static boolean asks(Screen requester) {
        return asks(requester, Minecraft.getInstance().screen);
    }

    /** {@link #asks(Screen)}'s rule, on its parts. */
    static boolean asks(Object requester, Object open) {
        return requester != null && requester == open;
    }
}
