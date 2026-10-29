/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
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

import java.awt.Image;
import java.awt.event.KeyEvent;

import org.havi.ui.event.HFocusEvent;
import org.havi.ui.event.HFocusListener;

import org.videolan.BDJXletContext;

public class HIcon extends HStaticIcon implements HNavigable {

    private HNavigableHelper helper;

    public HIcon() {
        super();
        init();
    }

    public HIcon(Image image) {
        super(image);
        init();
    }

    public HIcon(Image image, int x, int y, int width, int height) {
        super(image, x, y, width, height);
        init();
    }

    public HIcon(Image imageNormal, Image imageFocus, int x, int y, int width, int height) {
        super(imageNormal, x, y, width, height);
        setGraphicContent(imageFocus, FOCUSED_STATE);
        init();
    }

    private void init() {
        helper = new HNavigableHelper(this);
        // Enable key and focus events so processKeyEvent/processFocusEvent are called
        enableEvents(java.awt.AWTEvent.KEY_EVENT_MASK | java.awt.AWTEvent.FOCUS_EVENT_MASK);
    }

    public static void setDefaultLook(HGraphicLook hlook) {
        BDJXletContext.setXletDefaultLook(PROPERTY_LOOK, hlook);
    }

    public static HGraphicLook getDefaultLook() {
        return (HGraphicLook)BDJXletContext.getXletDefaultLook(PROPERTY_LOOK, DEFAULT_LOOK);
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

    /**
     * Process HAVI focus events. Updates interaction state and notifies listeners.
     */
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
        // Let helper handle navigation keys
        if (helper.processKeyEvent(e)) {
            e.consume();
            return;
        }

        super.processKeyEvent(e);
    }

    static final Class DEFAULT_LOOK = HGraphicLook.class;
    private static final String PROPERTY_LOOK = HIcon.class.getName();

    private static final long serialVersionUID = 2006124827619610922L;
}
