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

import org.havi.ui.event.HActionEvent;
import org.havi.ui.event.HActionListener;
import org.havi.ui.event.HFocusEvent;
import org.havi.ui.event.HFocusListener;

import org.videolan.BDJXletContext;

public class HGraphicButton extends HIcon implements HActionable {

    private HActionableHelper actionHelper;

    public HGraphicButton() {
        super();
        initAction();
    }

    public HGraphicButton(Image image, int x, int y, int width, int height) {
        super(image, x, y, width, height);
        initAction();
    }

    public HGraphicButton(Image imageNormal, Image imageFocused,
            Image imageActioned, int x, int y, int width, int height) {
        super(imageNormal, imageFocused, x, y, width, height);
        setGraphicContent(imageActioned, ACTIONED_STATE);
        initAction();
    }

    public HGraphicButton(Image image) {
        super(image);
        initAction();
    }

    public HGraphicButton(Image imageNormal, Image imageFocused,
            Image imageActioned) {
        this(imageNormal, imageFocused, imageActioned, 0, 0, 0, 0);
    }

    private void initAction() {
        actionHelper = new HActionableHelper(this);
    }

    public static void setDefaultLook(HGraphicLook hlook) {
        BDJXletContext.setXletDefaultLook(PROPERTY_LOOK, hlook);
    }

    public static HGraphicLook getDefaultLook() {
        return (HGraphicLook)BDJXletContext.getXletDefaultLook(PROPERTY_LOOK, DEFAULT_LOOK);
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

    // HNavigable methods are inherited from HIcon
    // Focus handling is inherited from HIcon

    /**
     * Process key events for HAVI navigation and action.
     * Handles ENTER via actionHelper, delegates navigation to parent.
     */
    protected void processKeyEvent(KeyEvent e) {
        // Let actionHelper handle ENTER key
        if (actionHelper.processKeyEvent(e)) {
            e.consume();
            return;
        }

        // Delegate navigation (arrows) to parent HIcon
        super.processKeyEvent(e);
    }

    static final Class DEFAULT_LOOK = HGraphicLook.class;
    private static final String PROPERTY_LOOK = HGraphicButton.class.getName();

    private static final long serialVersionUID = 5167775411684840800L;
}
