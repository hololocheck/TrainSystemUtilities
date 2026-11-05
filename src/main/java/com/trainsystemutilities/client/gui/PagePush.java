package com.trainsystemutilities.client.gui;

import com.manta.api.anim.Animation;
import com.manta.api.anim.Transition;
import com.manta.api.state.AnimationSlot;
import com.manta.api.state.BoolSlot;
import com.manta.api.state.ColorSlot;
import com.manta.api.state.IconSlot;
import com.manta.api.state.MantaState;
import com.manta.api.state.NumberSlot;
import com.manta.api.state.TextSlot;
import com.manta.api.state.TransitionSlot;

import java.util.HashMap;
import java.util.Map;

/**
 * One page's {@link MantaState}, written by slot name - for the two management screens whose pages
 * carry a couple of hundred slots (Manta 7 Phase 4). A handle is taken on its first write and kept,
 * so a misspelt name is refused the first time the screen pushes (at {@code pageOpened}), as a handle
 * taken there by hand would be. A {@code null} value is none - the node's own value, what the pull
 * said by answering null.
 */
public final class PagePush {
    private final MantaState st;
    private final Map<String, TextSlot> texts = new HashMap<>();
    private final Map<String, ColorSlot> colors = new HashMap<>();
    private final Map<String, NumberSlot> numbers = new HashMap<>();
    private final Map<String, BoolSlot> bools = new HashMap<>();
    private final Map<String, IconSlot> icons = new HashMap<>();
    private final Map<String, AnimationSlot> animations = new HashMap<>();
    private final Map<String, TransitionSlot> transitions = new HashMap<>();

    public PagePush(Object page) {
        this.st = MantaState.of(page);
    }

    public boolean isOpen() {
        return st.isOpen();
    }

    public MantaState state() {
        return st;
    }

    public void textByKeyOnly() {
        st.textByKeyOnly();
    }

    public void text(String name, String v) {
        TextSlot s = texts.computeIfAbsent(name, st::text);
        if (v == null) st.clear(s); else st.set(s, v);
    }

    public void text(String name, int row, String v) {
        TextSlot s = texts.computeIfAbsent(name, st::text);
        if (v == null) st.clear(s, row); else st.set(s, row, v);
    }

    public void color(String name, Integer argb) {
        ColorSlot s = colors.computeIfAbsent(name, st::color);
        if (argb == null) st.clear(s); else st.set(s, argb);
    }

    public void color(String name, int row, Integer argb) {
        ColorSlot s = colors.computeIfAbsent(name, st::color);
        if (argb == null) st.clear(s, row); else st.set(s, row, argb);
    }

    public void number(String name, Integer v) {
        NumberSlot s = numbers.computeIfAbsent(name, st::number);
        if (v == null) st.clear(s); else st.set(s, v);
    }

    /** A box key as an offset from each node's own static value: what a pull answered as {@code default + delta}. */
    public void offset(String name, int delta) {
        st.setOffset(numbers.computeIfAbsent(name, st::number), delta);
    }

    /** One row's delta of a box key in offset mode ({@link #offset} first): {@code default + delta} for that row. */
    public void offset(String name, int row, int delta) {
        st.set(numbers.computeIfAbsent(name, st::number), row, delta);
    }

    public void bool(String name, Boolean v) {
        BoolSlot s = bools.computeIfAbsent(name, st::bool);
        if (v == null) st.clear(s); else st.set(s, v);
    }

    public void bool(String name, int row, Boolean v) {
        BoolSlot s = bools.computeIfAbsent(name, st::bool);
        if (v == null) st.clear(s, row); else st.set(s, row, v);
    }

    public void icon(String name, String id) {
        IconSlot s = icons.computeIfAbsent(name, st::icon);
        if (id == null) st.clear(s); else st.set(s, id);
    }

    public void icon(String name, int row, String id) {
        IconSlot s = icons.computeIfAbsent(name, st::icon);
        if (id == null) st.clear(s, row); else st.set(s, row, id);
    }

    public void animation(String name, Animation a) {
        AnimationSlot s = animations.computeIfAbsent(name, st::animation);
        if (a == null) st.clear(s); else st.set(s, a);
    }

    public void animation(String name, int row, Animation a) {
        AnimationSlot s = animations.computeIfAbsent(name, st::animation);
        if (a == null) st.clear(s, row); else st.set(s, row, a);
    }

    public void transition(String name, Transition t) {
        TransitionSlot s = transitions.computeIfAbsent(name, st::transition);
        if (t == null) st.clear(s); else st.set(s, t);
    }
}
