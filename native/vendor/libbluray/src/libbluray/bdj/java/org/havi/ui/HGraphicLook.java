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
 *
 * Rendering implementation based on XletView by Martin Sveden.
 */

package org.havi.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Insets;

public class HGraphicLook implements HExtendedLook {

    private static final Insets DEFAULT_INSETS = new Insets(2, 2, 2, 2);
    private static final Insets NO_INSETS = new Insets(0, 0, 0, 0);

    public HGraphicLook() {
    }

    public void fillBackground(Graphics g, HVisible visible, int state) {
        if (visible.getBackgroundMode() == HVisible.BACKGROUND_FILL) {
            Color bg = visible.getBackground();
            if (bg != null) {
                Dimension size = visible.getSize();
                Insets insets = getInsets(visible);
                g.setColor(bg);
                // Fill only the content area, not the border/inset area
                g.fillRect(insets.left, insets.top,
                    size.width - insets.left - insets.right,
                    size.height - insets.top - insets.bottom);
            }
        }
    }

    public void renderBorders(Graphics g, HVisible visible, int state) {
        if (!visible.getBordersEnabled()) {
            return;
        }

        // Draw focus border when component is focused
        if ((state & HState.FOCUSED_STATE_BIT) != 0) {
            Color fg = visible.getForeground();
            if (fg != null) {
                Dimension size = visible.getSize();
                Insets insets = getInsets(visible);
                g.setColor(fg);

                // Top border
                g.fillRect(0, 0, size.width, insets.top);

                // Right border
                g.fillRect(size.width - insets.right, 0, insets.right, size.height);

                // Bottom border
                g.fillRect(0, size.height - insets.bottom, size.width, insets.bottom);

                // Left border
                g.fillRect(0, 0, insets.left, size.height);
            }
        }
    }

    public void renderVisible(Graphics g, HVisible visible, int state) {
        // Get the image for this state
        Image imageToDraw = visible.getGraphicContent(state);
        if (imageToDraw != null) {
            Insets insets = getInsets(visible);
            drawImage(g, imageToDraw, visible, insets);
        }
    }

    /**
     * Main rendering method that draws the HVisible according to the HAVI spec.
     * Order of operations:
     * 1. Fill background (if BACKGROUND_FILL mode)
     * 2. Render state-based content
     * 3. Render borders (if focused and borders enabled)
     */
    public void showLook(Graphics g, HVisible visible, int state) {
        // 1. Fill background
        fillBackground(g, visible, state);

        // 2. Render the graphic content for this state
        renderVisible(g, visible, state);

        // 3. Render focus borders
        renderBorders(g, visible, state);
    }

    /**
     * Draws an image onto the HVisible with proper alignment.
     * Uses the HVisible's horizontal and vertical alignment settings.
     * Renders within the content area (excluding insets/borders).
     *
     * Note: This implementation only supports RESIZE_NONE mode as per HAVI spec
     * minimum requirements. Scaling support is optional.
     *
     * @param g Graphics context
     * @param imageToDraw The image to draw
     * @param visible The HVisible owner
     * @param insets The insets defining the border area
     * @return true if the image was drawn successfully
     */
    protected static boolean drawImage(Graphics g, Image imageToDraw, HVisible visible, Insets insets) {
        int hAlign = visible.getHorizontalAlignment();
        int vAlign = visible.getVerticalAlignment();

        // Calculate content area (excluding insets)
        // Using contentX/contentY for consistency with contentWidth/contentHeight
        int contentX = insets.left;
        int contentY = insets.top;
        int contentWidth = visible.getWidth() - insets.left - insets.right;
        int contentHeight = visible.getHeight() - insets.top - insets.bottom;

        if (contentWidth <= 0 || contentHeight <= 0) {
            return false;
        }

        int imgWidth = imageToDraw.getWidth(visible);
        int imgHeight = imageToDraw.getHeight(visible);

        // If image dimensions aren't available yet, use content area size
        if (imgWidth <= 0) imgWidth = contentWidth;
        if (imgHeight <= 0) imgHeight = contentHeight;

        /*
         * Per HAVI spec: "Note that the results of applying the VALIGN_JUSTIFY
         * and HALIGN_JUSTIFY alignment modes for graphical content are defined
         * to be identical to VALIGN_CENTER and HALIGN_CENTER modes respectively,
         * as justification is meaningless in this context."
         */

        int drawX = contentX;
        int drawY = contentY;

        // Calculate horizontal position within content area
        switch (hAlign) {
            case HVisible.HALIGN_CENTER:
            case HVisible.HALIGN_JUSTIFY:
                drawX = contentX + (contentWidth - imgWidth) / 2;
                break;
            case HVisible.HALIGN_LEFT:
                drawX = contentX;
                break;
            case HVisible.HALIGN_RIGHT:
                drawX = contentX + contentWidth - imgWidth;
                break;
        }

        // Calculate vertical position within content area
        switch (vAlign) {
            case HVisible.VALIGN_CENTER:
            case HVisible.VALIGN_JUSTIFY:
                drawY = contentY + (contentHeight - imgHeight) / 2;
                break;
            case HVisible.VALIGN_TOP:
                drawY = contentY;
                break;
            case HVisible.VALIGN_BOTTOM:
                drawY = contentY + contentHeight - imgHeight;
                break;
        }

        return g.drawImage(imageToDraw, drawX, drawY, visible);
    }

    public void widgetChanged(HVisible visible, HChangeData[] changes) {
        /*
         * Per HAVI spec: "Note that implementations of HLook may not actually
         * implement more efficient drawing code for a given hint. In particular,
         * simply repainting the entire HVisible is a valid implementation option."
         *
         * "A minimum implementation of this method could simply call visible.repaint()"
         */
        if (visible.isVisible()) {
            visible.repaint();
        }
    }

    /**
     * Gets the maximum content size across all states.
     * Returns the widest width and tallest height to fit all content without scaling.
     *
     * Note: This implementation only supports RESIZE_NONE (no scaling).
     *
     * @param visible The HVisible to examine
     * @return Content dimensions, or null if no content with valid dimensions
     */
    private Dimension getContentSize(HVisible visible) {
        int maxWidth = 0;
        int maxHeight = 0;
        boolean foundContent = false;

        for (int state = HState.FIRST_STATE; state <= HState.LAST_STATE; state++) {
            Image img = visible.getGraphicContent(state);
            if (img != null) {
                int w = img.getWidth(visible);
                int h = img.getHeight(visible);
                if (w > 0 && h > 0) {
                    foundContent = true;
                    if (w > maxWidth) maxWidth = w;
                    if (h > maxHeight) maxHeight = h;
                }
            }
        }

        if (!foundContent) {
            return null;
        }
        return new Dimension(maxWidth, maxHeight);
    }

    public Dimension getMinimumSize(HVisible visible) {
        Insets insets = getInsets(visible);
        int insetsWidth = insets.left + insets.right;
        int insetsHeight = insets.top + insets.bottom;

        // Step 1: HTextLook-specific - skip for HGraphicLook
        // Step 2: Scaling - skip (this implementation only supports RESIZE_NONE)

        // Step 3: No scaling, content set - return size large enough for all content
        Dimension contentSize = getContentSize(visible);
        if (contentSize != null) {
            return new Dimension(contentSize.width + insetsWidth,
                               contentSize.height + insetsHeight);
        }

        // Step 4: No content but default size set
        Dimension defaultSize = visible.getDefaultSize();
        if (defaultSize != null &&
            defaultSize.width != HVisible.NO_DEFAULT_WIDTH &&
            defaultSize.height != HVisible.NO_DEFAULT_HEIGHT) {
            return new Dimension(defaultSize.width + insetsWidth,
                               defaultSize.height + insetsHeight);
        }

        // Step 5: No content or default size - implementation-specific minimum
        return new Dimension(insetsWidth, insetsHeight);
    }

    public Dimension getPreferredSize(HVisible visible) {
        Insets insets = getInsets(visible);
        int insetsWidth = insets.left + insets.right;
        int insetsHeight = insets.top + insets.bottom;

        // Step 1: If default size set, return it + insets
        Dimension defaultSize = visible.getDefaultSize();
        if (defaultSize != null) {
            int w = defaultSize.width;
            int h = defaultSize.height;

            boolean hasDefaultWidth = (w != HVisible.NO_DEFAULT_WIDTH);
            boolean hasDefaultHeight = (h != HVisible.NO_DEFAULT_HEIGHT);

            if (hasDefaultWidth && hasDefaultHeight) {
                // Full default size is set
                return new Dimension(w + insetsWidth, h + insetsHeight);
            }

            // Handle NO_DEFAULT_WIDTH or NO_DEFAULT_HEIGHT cases
            if (hasDefaultWidth || hasDefaultHeight) {
                Dimension contentSize = getContentSize(visible);
                if (contentSize != null) {
                    if (!hasDefaultWidth) {
                        w = contentSize.width;
                    }
                    if (!hasDefaultHeight) {
                        h = contentSize.height;
                    }
                    return new Dimension(w + insetsWidth, h + insetsHeight);
                }
                // No content - use current size for missing dimension
                Dimension currentSize = visible.getSize();
                if (!hasDefaultWidth) {
                    w = Math.max(0, currentSize.width - insetsWidth);
                }
                if (!hasDefaultHeight) {
                    h = Math.max(0, currentSize.height - insetsHeight);
                }
                return new Dimension(w + insetsWidth, h + insetsHeight);
            }
        }

        // Step 2: HTextLook-specific - skip for HGraphicLook

        // Step 3: No scaling, content present - return size large enough for content
        // (Step 4 scaling case skipped - this implementation only supports RESIZE_NONE)
        Dimension contentSize = getContentSize(visible);
        if (contentSize != null) {
            return new Dimension(contentSize.width + insetsWidth,
                               contentSize.height + insetsHeight);
        }

        // Step 5: No content and no default size - return current size
        return visible.getSize();
    }

    public Dimension getMaximumSize(HVisible visible) {
        Insets insets = getInsets(visible);
        int insetsWidth = insets.left + insets.right;
        int insetsHeight = insets.top + insets.bottom;

        // Step 1: HTextLook-specific - skip for HGraphicLook
        // Step 2: Scaling - skip (this implementation only supports RESIZE_NONE)

        // Step 3: No scaling, content set - return size large enough for content
        Dimension contentSize = getContentSize(visible);
        if (contentSize != null) {
            return new Dimension(contentSize.width + insetsWidth,
                               contentSize.height + insetsHeight);
        }

        // Step 4: No content - return Short.MAX_VALUE
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
