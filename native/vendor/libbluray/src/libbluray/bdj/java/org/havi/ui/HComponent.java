/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
 * Copyright (C) 2013  Petri Hintukainen <phintuka@users.sourceforge.net>
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

import java.awt.Component;
import java.awt.event.FocusEvent;

import org.dvb.ui.TestOpacity;
import org.havi.ui.event.HFocusEvent;

import java.awt.BDToolkit;

public abstract class HComponent extends Component implements HMatteLayer, TestOpacity {

    public HComponent() {
        this(0, 0, 0, 0);
        BDToolkit.addComponent(this);
    }

    public HComponent(int x, int y, int width, int height) {
        setBounds(x, y, width, height);
    }

    public void setMatte(HMatte m) throws HMatteException {
        matte = m;
    }

    public HMatte getMatte() {
        return matte;
    }

    public boolean isDoubleBuffered() {
        return false;
    }

    public boolean isOpaque() {
        return false;
    }

    public void setEnabled(boolean b) {
        if (b != super.isEnabled()) {
            super.setEnabled(b);
            super.setFocusable(b);
        }
    }

    public boolean isEnabled() {
        return super.isEnabled();
    }

    /**
     * Override AWT focus processing to create HFocusEvent and delegate to processHFocusEvent
     * for all HNavigationInputPreferred implementations.
     */
    protected void processFocusEvent(FocusEvent e) {
        if (this instanceof HNavigationInputPreferred) {
            HFocusEvent hEvent = new HFocusEvent(this, e.getID());
            ((HNavigationInputPreferred) this).processHFocusEvent(hEvent);
        }
        super.processFocusEvent(e);
    }

    private HMatte matte = null;

    private static final long serialVersionUID = -4115249517434074428L;
}
