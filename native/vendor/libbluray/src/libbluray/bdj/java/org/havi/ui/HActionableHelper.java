/*
 * This file is part of libbluray
 * Copyright (C) 2026 libbluray project
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

import java.awt.event.KeyEvent;

import org.havi.ui.event.HActionEvent;
import org.havi.ui.event.HActionListener;

/**
 * Helper class that implements HActionable functionality.
 * This encapsulates the action handling logic for use by HGraphicButton, HTextButton, etc.
 */
public final class HActionableHelper {

    transient HActionListener hActionListener;
    private HSound actionSound;
    private String actionCommand;
    private HVisible hVisible;

    public HActionableHelper(HVisible hVisible) {
        this.hVisible = hVisible;
    }

    /**
     * Adds an HActionListener to receive action events.
     */
    public synchronized void addHActionListener(HActionListener listener) {
        if (listener == null) {
            return;
        }
        hActionListener = HEventMulticaster.add(hActionListener, listener);
    }

    /**
     * Removes an HActionListener.
     */
    public synchronized void removeHActionListener(HActionListener listener) {
        if (listener == null) {
            return;
        }
        hActionListener = HEventMulticaster.remove(hActionListener, listener);
    }

    /**
     * Sets the action command string for action events.
     */
    public void setActionCommand(String command) {
        actionCommand = command;
    }

    /**
     * Gets the action command string.
     */
    public String getActionCommand() {
        return actionCommand;
    }

    /**
     * Sets the sound to play when an action occurs.
     */
    public void setActionSound(HSound sound) {
        actionSound = sound;
    }

    /**
     * Gets the action sound.
     */
    public HSound getActionSound() {
        return actionSound;
    }

    /**
     * Processes an HActionEvent and returns the new interaction state.
     * Notifies registered HActionListeners and plays the action sound.
     *
     * @param evt The HActionEvent to process
     * @return The current interaction state (unchanged by action events)
     */
    public void processHActionEvent(HActionEvent evt) {
        // Play action sound
        if (actionSound != null) {
            actionSound.play();
        }

        // Notify action listeners
        if (hActionListener != null) {
            hActionListener.actionPerformed(evt);
        }
    }

    /**
     * Processes a KeyEvent for action.
     * Handles ENTER key by firing an HActionEvent.
     *
     * @param e The KeyEvent to process
     * @return true if the event was handled, false otherwise
     */
    public boolean processKeyEvent(KeyEvent e) {
        // Only handle KEY_PRESSED
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }

        // Only handle ENTER key
        if (e.getKeyCode() != KeyEvent.VK_ENTER) {
            return false;
        }

        // Fire action event (cast is safe - this helper is only used by HActionable components)
        HActionEvent actionEvent = new HActionEvent(
            (HActionInputPreferred) hVisible, HActionEvent.ACTION_PERFORMED, actionCommand);
        processHActionEvent(actionEvent);

        return true;
    }
}
