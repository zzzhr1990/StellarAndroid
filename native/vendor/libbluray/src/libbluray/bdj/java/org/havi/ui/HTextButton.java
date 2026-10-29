/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
 * Copyright (C) 2024  libbluray project
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

import org.havi.ui.event.HActionEvent;
import org.havi.ui.event.HActionListener;
import org.havi.ui.event.HFocusEvent;
import org.havi.ui.event.HFocusListener;

import org.videolan.BDJXletContext;

public class HTextButton extends HText implements HActionable {

    private HActionableHelper actionHelper;

    public HTextButton() {
        super();
        initAction();
    }

    public HTextButton(String text, int x, int y, int width, int height) {
        super(text, x, y, width, height);
        initAction();
    }

    public HTextButton(String text, int x, int y, int width, int height,
            Font font, Color foreground, Color background,
            HTextLayoutManager tlm) {
        super(text, x, y, width, height, font, foreground, background, tlm);
        initAction();
    }

    public HTextButton(String text) {
        super(text);
        initAction();
    }

    public HTextButton(String text, Font font, Color foreground,
            Color background, HTextLayoutManager tlm) {
        super(text, font, foreground, background, tlm);
        initAction();
    }

    private void initAction() {
        actionHelper = new HActionableHelper(this);
    }

    public static void setDefaultLook(HTextLook hlook) {
        BDJXletContext.setXletDefaultLook(PROPERTY_LOOK, hlook);
    }

    public static HTextLook getDefaultLook() {
        return (HTextLook) BDJXletContext.getXletDefaultLook(PROPERTY_LOOK, DEFAULT_LOOK);
    }

    // --- HActionable implementation ---

    public void addHActionListener(HActionListener l) {
        actionHelper.addHActionListener(l);
    }

    public void removeHActionListener(HActionListener l) {
        actionHelper.removeHActionListener(l);
    }

    public void setActionCommand(String command) {
        actionHelper.setActionCommand(command);
    }

    public void setActionSound(HSound sound) {
        actionHelper.setActionSound(sound);
    }

    public HSound getActionSound() {
        return actionHelper.getActionSound();
    }

    public void processHActionEvent(HActionEvent evt) {
        actionHelper.processHActionEvent(evt);
    }

    public String getActionCommand() {
        return actionHelper.getActionCommand();
    }

    // HNavigable methods are inherited from HText
    // Focus handling is inherited from HText

    /**
     * Process key events for HAVI navigation and action.
     * Handles ENTER via actionHelper, delegates navigation to parent.
     */
    protected void processKeyEvent(KeyEvent e) {
        if (actionHelper.processKeyEvent(e)) {
            e.consume();
            return;
        }
        super.processKeyEvent(e);
    }

    static final Class DEFAULT_LOOK = HTextLook.class;
    private static final String PROPERTY_LOOK = HTextButton.class.getName();

    private static final long serialVersionUID = 7563558661769889160L;
}
