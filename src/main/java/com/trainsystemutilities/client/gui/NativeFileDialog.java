package com.trainsystemutilities.client.gui;

import com.trainsystemutilities.TrainSystemUtilities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.function.Consumer;

/**
 * TSU's one way to the native file dialog (TinyFileDialogs): opened on a daemon thread of its own, and only for the screen
 * the player has open.
 *
 * <p>The wiki builds stand-ins of TSU's screens and plays scripts on them (Manta's live embeds). Its containment stops
 * packets, slot actions, screen changes and links - not a native dialog, nor what a screen does with the file it picked
 * (the poster and the upload send from a later main-thread task, outside the range). Each stand-in answers its file
 * button itself in its wiki mode; this keeps one that does not - a guard dropped, a factory that never set it - from
 * opening the dialog on the reader's client, because a stand-in is drawn inside the wiki and is never the open screen
 * (second reading, 2026-10-07). {@code NativeFileDialogTest} holds the refusal, and that no other TSU source calls
 * TinyFileDialogs.
 */
public final class NativeFileDialog {

    private NativeFileDialog() {}

    /**
     * Opens the dialog on a daemon thread named {@code thread} and hands the chosen path - null when cancelled - to
     * {@code picked} on that thread; or refuses, opening nothing, when {@code requester} is not the screen the player has
     * open. Answers whether it opened.
     */
    public static boolean open(Screen requester, String thread, String title, String[] patterns, String description,
                               Consumer<String> picked) {
        return launch(requester, Minecraft.getInstance().screen, () -> {
            Thread t = new Thread(() -> picked.accept(pick(title, patterns, description)), thread);
            t.setDaemon(true);
            t.start();
        });
    }

    /** {@link #open}'s rule ({@link OpenScreen}): {@code start} runs only when the requester is the open screen. */
    static boolean launch(Object requester, Object open, Runnable start) {
        if (!OpenScreen.asks(requester, open)) {
            TrainSystemUtilities.LOGGER.info("[NativeFileDialog] refused: {} is not the open screen",
                    requester == null ? "null" : requester.getClass().getSimpleName());
            return false;
        }
        start.run();
        return true;
    }

    private static String pick(String title, String[] patterns, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(patterns.length);
            for (String pattern : patterns) {
                filters.put(stack.UTF8(pattern));
            }
            filters.flip();
            return TinyFileDialogs.tinyfd_openFileDialog(title, "", filters, description, false);
        }
    }
}
