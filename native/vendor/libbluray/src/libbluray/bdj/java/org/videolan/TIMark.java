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

package org.videolan;

public class TIMark {
    public TIMark(int index, int type, long start) {
        this.index = index;
        this.type = type;
        this.start = start;
    }

    public int getIndex() {
        return index;
    }

    public long getStart() {
        return start;
    }

    public long getStartNanoseconds() {
        return start * 100000 / 9;
    }

    public int getType() {
        return type;
    }

    private final int index;
    private final int type;
    private final long start;

    public static final int MARK_TYPE_ENTRY = 1;
    public static final int MARK_TYPE_LINK = 2;
}
