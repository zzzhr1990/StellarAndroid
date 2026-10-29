/*
 * This file is part of libbluray
 * Copyright (C) 2010  William Hahne
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

import org.videolan.BDJXletContext;

public class HMultilineEntry extends HSinglelineEntry {
    public HMultilineEntry() {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public HMultilineEntry(String text, int x, int y, int width, int height,
            int maxChars, Font font, Color color) {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public HMultilineEntry(int x, int y, int width, int height, int maxChars) {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public HMultilineEntry(String text, int maxChars, Font font, Color color) {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public HMultilineEntry(int maxChars) {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public static void setDefaultLook(HMultilineEntryLook look) {
        BDJXletContext.setXletDefaultLook(PROPERTY_LOOK, look);
    }

    public static HSinglelineEntryLook getDefaultLook() {
        return (HSinglelineEntryLook) BDJXletContext.getXletDefaultLook(PROPERTY_LOOK, DEFAULT_LOOK);
    }

    public void setLook(HLook hlook) throws HInvalidLookException {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public void caretNextLine() {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    public void caretPreviousLine() {
        org.videolan.Logger.unimplemented(HMultilineEntry.class.getName(), "");
    }

    private static final Class DEFAULT_LOOK = HSinglelineEntryLook.class;
    private static final String PROPERTY_LOOK = HMultilineEntry.class.getName();

    private static final long serialVersionUID = 2690386579157062435L;
}
