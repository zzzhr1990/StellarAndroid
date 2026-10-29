/*
 * This file is part of libbluray
 * Copyright (C) 2024 libbluray project
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
 *
 * Based on XletView implementation by Martin Sveden.
 */

package org.havi.ui;

import java.awt.Component;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.Hashtable;

import org.havi.ui.event.HFocusEvent;
import org.havi.ui.event.HFocusListener;

/**
 * Helper class that implements HNavigable functionality.
 * This encapsulates the navigation logic for use by HIcon, HGraphicButton, etc.
 */
public final class HNavigableHelper {

    private Hashtable navTargets = new Hashtable();
    private HSound gainFocusSound;
    private HSound loseFocusSound;
    transient HFocusListener hFocusListener;

    private HVisible hVisible;

    public HNavigableHelper(HVisible hVisible) {
        this.hVisible = hVisible;
    }

    /**
     * Sets a navigation target for the specified key code.
     * @param keyCode The key code (e.g., KeyEvent.VK_UP)
     * @param target The HNavigable to transfer focus to, or null to remove
     */
    public void setMove(int keyCode, HNavigable target) {
        Integer code = Integer.valueOf(keyCode);
        navTargets.remove(code);
        if (target != null) {
            navTargets.put(code, target);
        }
    }

    /**
     * Gets the navigation target for the specified key code.
     * @param keyCode The key code
     * @return The HNavigable target, or null if not set
     */
    public HNavigable getMove(int keyCode) {
        return (HNavigable) navTargets.get(Integer.valueOf(keyCode));
    }

    /**
     * Sets navigation targets for all four arrow keys at once.
     */
    public void setFocusTraversal(HNavigable up, HNavigable down, HNavigable left, HNavigable right) {
        setMove(KeyEvent.VK_UP, up);
        setMove(KeyEvent.VK_DOWN, down);
        setMove(KeyEvent.VK_LEFT, left);
        setMove(KeyEvent.VK_RIGHT, right);
    }

    /**
     * Returns true if this component currently has focus.
     */
    public boolean isSelected() {
        return hVisible.hasFocus();
    }

    public void setGainFocusSound(HSound sound) {
        gainFocusSound = sound;
    }

    public void setLoseFocusSound(HSound sound) {
        loseFocusSound = sound;
    }

    public HSound getGainFocusSound() {
        return gainFocusSound;
    }

    public HSound getLoseFocusSound() {
        return loseFocusSound;
    }

    /**
     * Adds an HFocusListener to receive focus events.
     */
    public synchronized void addHFocusListener(HFocusListener listener) {
        hFocusListener = HEventMulticaster.add(hFocusListener, listener);
    }

    /**
     * Removes an HFocusListener.
     */
    public synchronized void removeHFocusListener(HFocusListener listener) {
        hFocusListener = HEventMulticaster.remove(hFocusListener, listener);
    }

    /**
     * Returns an array of key codes for which navigation targets are set.
     */
    public int[] getNavigationKeys() {
        if (navTargets.size() == 0) {
            return null;
        }
        int[] keyCodes = new int[navTargets.size()];
        java.util.Enumeration keys = navTargets.keys();
        int i = 0;
        while (keys.hasMoreElements()) {
            Integer key = (Integer) keys.nextElement();
            keyCodes[i++] = key.intValue();
        }
        return keyCodes;
    }

    /**
     * Processes an HFocusEvent and returns the new interaction state.
     * This is the core method that handles focus gain/loss and focus transfers.
     *
     * @param evt The HFocusEvent to process
     * @return The new interaction state for the HVisible
     */
    public int processHFocusEvent(HFocusEvent evt) {
        int state = hVisible.getInteractionState();

        if (evt.getID() == FocusEvent.FOCUS_GAINED) {
            // Set focused bit
            state = state | HState.FOCUSED_STATE_BIT;

            // Play gain focus sound
            if (gainFocusSound != null) {
                gainFocusSound.play();
            }

            // Notify HFocusListeners
            if (hFocusListener != null) {
                hFocusListener.focusGained(evt);
            }
        }
        else if (evt.getID() == FocusEvent.FOCUS_LOST) {
            // Clear focused bit (XOR to toggle off)
            state = state & (~HState.FOCUSED_STATE_BIT);

            // Play lose focus sound
            if (loseFocusSound != null) {
                loseFocusSound.play();
            }

            // Notify HFocusListeners
            if (hFocusListener != null) {
                hFocusListener.focusLost(evt);
            }
        }
        else if (evt.getID() == HFocusEvent.FOCUS_TRANSFER &&
                 evt.getTransferId() != HFocusEvent.NO_TRANSFER_ID) {
            // Handle focus transfer to navigation target
            HNavigable newNav = getMove(evt.getTransferId());

            if (newNav instanceof Component) {
                ((Component) newNav).requestFocus();
            }
        }

        return state;
    }

    /**
     * Processes a KeyEvent for navigation.
     * Looks up navigation targets and transfers focus.
     *
     * @param e The KeyEvent to process
     * @return true if the event was handled, false otherwise
     */
    public boolean processKeyEvent(KeyEvent e) {
        // Only handle KEY_PRESSED
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }

        // Look up navigation target for this key
        HNavigable target = getMove(e.getKeyCode());

        if (target != null && target instanceof Component) {
            ((Component) target).requestFocus();
            return true;
        }

        return false;
    }
}
