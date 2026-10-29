/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
 * Copyright (C) 2013  Petri Hintukainen <phintuka@users.sourceforge.net>
 * Copyright (C) 2026  libbluray project
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library. If not, see
 * <http://www.gnu.org/licenses/>.
 */

package org.havi.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;

import org.havi.ui.event.HFocusEvent;
import org.havi.ui.event.HFocusListener;

import org.videolan.BDJXletContext;
import org.videolan.Logger;

public class HText extends HStaticText implements HNavigable {

    private HNavigableHelper helper;

    public HText() {
        this(null);
    }

    public HText(String text) {
        this(text, text, 0, 0, 0, 0);
    }

    public HText(String textNormal, String textFocus) {
        this(textNormal, textFocus, 0, 0, 0, 0);
    }

    public HText(String text, int x, int y, int width, int height) {
        this(text, text, x, y, width, height);
    }

    public HText(String textNormal, String textFocus, int x, int y, int width,
            int height) {
        super(textNormal, x, y, width, height);
        try {
            setLook(getDefaultLook());
        } catch (HInvalidLookException e) {
            logger.error("failed setting default look");
        }

        if (textFocus != null) {
            super.setTextContent(textFocus, FOCUSED_STATE);
            super.setTextContent(textFocus, ACTIONED_FOCUSED_STATE);
            super.setTextContent(textFocus, DISABLED_FOCUSED_STATE);
            super.setTextContent(textFocus, DISABLED_ACTIONED_FOCUSED_STATE);
        }

        init();
    }

    public HText(String text, Font font, Color foreground, Color background,
            HTextLayoutManager tlm) {
        this(text, text, 0, 0, 0, 0, font, foreground, background, tlm);
    }

    public HText(String textNormal, String textFocus, Font font,
            Color foreground, Color background, HTextLayoutManager tlm) {
        this(textNormal, textFocus, 0, 0, 0, 0, font, foreground, background, tlm);
    }

    public HText(String text, int x, int y, int width, int height, Font font,
            Color foreground, Color background, HTextLayoutManager tlm) {
        this(text, text, x, y, width, height, font, foreground, background, tlm);
    }

    public HText(String textNormal, String textFocus, int x, int y, int width,
            int height, Font font, Color foreground, Color background,
            HTextLayoutManager tlm) {
        this(textNormal, textFocus, x, y, width, height);
        setFont(font);
        setForeground(foreground);
        setBackground(background);
        setTextLayoutManager(tlm);
    }

    private void init() {
        helper = new HNavigableHelper(this);
        // Enable key and focus events so processKeyEvent/processFocusEvent are called
        enableEvents(java.awt.AWTEvent.KEY_EVENT_MASK | java.awt.AWTEvent.FOCUS_EVENT_MASK);
    }

    // --- HNavigable implementation ---

    public void setMove(int keyCode, HNavigable target) {
        helper.setMove(keyCode, target);
    }

    public HNavigable getMove(int keyCode) {
        return helper.getMove(keyCode);
    }

    public void setFocusTraversal(HNavigable up, HNavigable down,
            HNavigable left, HNavigable right) {
        helper.setFocusTraversal(up, down, left, right);
    }

    public boolean isSelected() {
        return helper.isSelected();
    }

    public void setGainFocusSound(HSound sound) {
        helper.setGainFocusSound(sound);
    }

    public void setLoseFocusSound(HSound sound) {
        helper.setLoseFocusSound(sound);
    }

    public HSound getGainFocusSound() {
        return helper.getGainFocusSound();
    }

    public HSound getLoseFocusSound() {
        return helper.getLoseFocusSound();
    }

    public void addHFocusListener(HFocusListener l) {
        helper.addHFocusListener(l);
    }

    public void removeHFocusListener(HFocusListener l) {
        helper.removeHFocusListener(l);
    }

    public int[] getNavigationKeys() {
        return helper.getNavigationKeys();
    }

    // --- Focus event handling ---

    public void processHFocusEvent(HFocusEvent evt) {
        int state = getInteractionState();
        int newState = helper.processHFocusEvent(evt);

        if (state != newState) {
            setInteractionState(newState);
        }
    }

    /**
     * Process key events for HAVI navigation.
     * Delegates to HNavigableHelper for arrow key handling.
     */
    protected void processKeyEvent(KeyEvent e) {
        if (helper.processKeyEvent(e)) {
            e.consume();
            return;
        }
        super.processKeyEvent(e);
    }

    public static void setDefaultLook(HTextLook hlook) {
        BDJXletContext.setXletDefaultLook(PROPERTY_LOOK, hlook);
    }

    public static HTextLook getDefaultLook() {
        return (HTextLook) BDJXletContext.getXletDefaultLook(PROPERTY_LOOK, DEFAULT_LOOK);
    }

    static final Class DEFAULT_LOOK = HTextLook.class;
    private static final String PROPERTY_LOOK = HText.class.getName();

    private static final long serialVersionUID = -8178609258303529066L;

    private static final Logger logger = Logger.getLogger(HText.class.getName());
}
