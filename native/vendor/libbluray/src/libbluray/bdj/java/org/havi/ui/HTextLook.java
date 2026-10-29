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
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;

public class HTextLook implements HExtendedLook {

    private static final Insets DEFAULT_INSETS = new Insets(2, 2, 2, 2);
    private static final Insets NO_INSETS = new Insets(0, 0, 0, 0);

    public HTextLook() {
    }

    public void fillBackground(Graphics g, HVisible visible, int state) {
        if (visible.getBackgroundMode() == HVisible.BACKGROUND_FILL) {
            Color color = visible.getBackground();
            if (color != null) {
                Dimension dimension = visible.getSize();
                g.setColor(color);
                g.fillRect(0, 0, dimension.width, dimension.height);
            }
        }
    }

    public void renderBorders(Graphics g, HVisible visible, int state) {
        if (!visible.getBordersEnabled()) {
            return;
        }

        // Only draw borders when focused
        if ((state & HState.FOCUSED_STATE_BIT) == 0) {
            return;
        }

        Insets insets = DEFAULT_INSETS;
        Color fg = visible.getForeground();
        Dimension dimension = visible.getSize();

        if (fg != null) {
            g.setColor(fg);
            // Top border
            g.fillRect(0, 0, dimension.width, insets.top);
            // Right border
            g.fillRect(dimension.width - insets.right, 0, insets.right, dimension.height);
            // Bottom border
            g.fillRect(0, dimension.height - insets.bottom, dimension.width, insets.bottom);
            // Left border
            g.fillRect(0, 0, insets.left, dimension.height);
        }
    }

    public void renderVisible(Graphics g, HVisible visible, int state) {
        HTextLayoutManager tlm = visible.getTextLayoutManager();
        if (tlm == null) {
            return;
        }

        String text = visible.getTextContent(state);
        if (text == null || text.length() == 0) {
            return;
        }

        Dimension size = visible.getSize();
        Insets insets = getInsets(visible);

        // Calculate available area for text
        int availWidth = size.width - insets.left - insets.right;
        int availHeight = size.height - insets.top - insets.bottom;

        if (availWidth <= 0 || availHeight <= 0) {
            return;
        }

        tlm.render(text, g, visible, insets);
    }

    public void showLook(Graphics g, HVisible visible, int state) {
        fillBackground(g, visible, state);
        renderVisible(g, visible, state);
        renderBorders(g, visible, state);
    }

    public void widgetChanged(HVisible visible, HChangeData[] changes) {
        if (visible.isVisible()) {
            visible.repaint();
        }
    }

    private Dimension clampShortDimension(int width, int height) {
        int w = Math.max(0, Math.min(width, Short.MAX_VALUE));
        int h = Math.max(0, Math.min(height, Short.MAX_VALUE));

        return new Dimension(w, h);
    }

    public Dimension getMinimumSize(HVisible visible) {
        Insets insets = getInsets(visible);
        int insetsWidth = (insets != null) ? insets.left + insets.right : 0;
        int insetsHeight = (insets != null) ? insets.top + insets.bottom : 0;

        // Step 1: If HDefaultTextLayoutManager, delegate to its getMinimumSize()
        HTextLayoutManager tlm = visible.getTextLayoutManager();
        if (tlm instanceof HDefaultTextLayoutManager) {
            Dimension size = ((HDefaultTextLayoutManager) tlm).getMinimumSize(visible);
            if (size != null && (size.width > 0 || size.height > 0)) {
                return clampShortDimension(size.width + insetsWidth, size.height + insetsHeight);
            }
        }
        // If not HDefaultTextLayoutManager or returns zero, proceed

        // Steps 2-3: HTextLook does not support scaling, content sizing handled by TLM

        // Step 4: If no content but default size set
        Dimension defaultSize = visible.getDefaultSize();
        if (defaultSize != null &&
            defaultSize.width != HVisible.NO_DEFAULT_WIDTH &&
            defaultSize.height != HVisible.NO_DEFAULT_HEIGHT) {
            return clampShortDimension(defaultSize.width + insetsWidth, defaultSize.height + insetsHeight);
        }

        // Step 5: Implementation-specific minimum (0,0)
        return new Dimension(insetsWidth, insetsHeight);
    }

    public Dimension getPreferredSize(HVisible visible) {
        Insets insets = getInsets(visible);
        int insetsWidth = (insets != null) ? insets.left + insets.right : 0;
        int insetsHeight = (insets != null) ? insets.top + insets.bottom : 0;

        Dimension defaultSize = visible.getDefaultSize();

        // Step 1: Check if default size is set (must check BEFORE delegating to TLM)
        if (defaultSize != null) {
            int w = defaultSize.width;
            int h = defaultSize.height;

            boolean hasDefaultWidth = (w != HVisible.NO_DEFAULT_WIDTH);
            boolean hasDefaultHeight = (h != HVisible.NO_DEFAULT_HEIGHT);

            if (hasDefaultWidth && hasDefaultHeight) {
                // Full default size is set
                return clampShortDimension(w + insetsWidth, h + insetsHeight);
            }

            // Handle NO_DEFAULT_WIDTH or NO_DEFAULT_HEIGHT cases
            if (hasDefaultWidth || hasDefaultHeight) {
                Dimension contentSize = getContentPreferredSize(visible);
                if (!hasDefaultWidth) {
                    w = contentSize.width;
                }
                if (!hasDefaultHeight) {
                    h = contentSize.height;
                }
                return clampShortDimension(w + insetsWidth, h + insetsHeight);
            }
        }

        // Step 2: If HDefaultTextLayoutManager, delegate to its getPreferredSize()
        HTextLayoutManager tlm = visible.getTextLayoutManager();
        if (tlm instanceof HDefaultTextLayoutManager) {
            Dimension size = ((HDefaultTextLayoutManager) tlm).getPreferredSize(visible);
            if (size != null && (size.width > 0 || size.height > 0)) {
                return clampShortDimension(size.width + insetsWidth, size.height + insetsHeight);
            }
        }
        // If not HDefaultTextLayoutManager or returns zero, proceed

        // Steps 3-4: HTextLook does not support scaling

        // Step 5: Return current size of HVisible
        return visible.getSize();
    }

    /**
     * Helper method to get preferred content size from the text layout manager.
     */
    private Dimension getContentPreferredSize(HVisible visible) {
        HTextLayoutManager tlm = visible.getTextLayoutManager();
        if (tlm instanceof HDefaultTextLayoutManager) {
            Dimension size = ((HDefaultTextLayoutManager) tlm).getPreferredSize(visible);
            if (size != null && (size.width > 0 || size.height > 0)) {
                return size;
            }
        }
        // Fallback: use current size (without insets, as caller adds them)
        Dimension currentSize = visible.getSize();
        Insets insets = getInsets(visible);
        int w = currentSize.width - (insets != null ? insets.left + insets.right : 0);
        int h = currentSize.height - (insets != null ? insets.top + insets.bottom : 0);
        return new Dimension(Math.max(0, w), Math.max(0, h));
    }

    public Dimension getMaximumSize(HVisible visible) {
        Insets insets = getInsets(visible);
        int insetsWidth = (insets != null) ? insets.left + insets.right : 0;
        int insetsHeight = (insets != null) ? insets.top + insets.bottom : 0;

        // Step 1: If HDefaultTextLayoutManager, delegate to its getMaximumSize()
        HTextLayoutManager tlm = visible.getTextLayoutManager();
        if (tlm instanceof HDefaultTextLayoutManager) {
            Dimension size = ((HDefaultTextLayoutManager) tlm).getMaximumSize(visible);
            if (size != null && (size.width > 0 || size.height > 0)) {
                return clampShortDimension(size.width + insetsWidth, size.height + insetsHeight);
            }
        }
        // If not HDefaultTextLayoutManager or returns zero, proceed

        // Steps 2-3: HTextLook does not support scaling

        // Step 4: No content, return Short.MAX_VALUE
        return new Dimension(Short.MAX_VALUE, Short.MAX_VALUE);
    }

    public boolean isOpaque(HVisible visible) {
        // Component is opaque if background fill is enabled and has opaque background color
        if (visible.getBackgroundMode() != HVisible.BACKGROUND_FILL) {
            return false;
        }

        Color bg = visible.getBackground();
        if ((bg == null) || (bg.getAlpha() < 255)) {
            return false;
        }

        return true;
    }

    public Insets getInsets(HVisible visible) {
        if (!visible.getBordersEnabled()) {
            return NO_INSETS;
        }
        return DEFAULT_INSETS;
    }
}
