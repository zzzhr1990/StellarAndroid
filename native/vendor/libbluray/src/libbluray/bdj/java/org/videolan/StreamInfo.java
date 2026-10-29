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

import java.awt.Dimension;

import org.bluray.ti.CodingType;
import org.videolan.ti.CodingTypeHelper;

public class StreamInfo {

    public static final byte ASPECT_4_3  = 2;
    public static final byte ASPECT_16_9 = 3;

    public StreamInfo(byte coding_type, byte format, byte rate,
                      char char_code, String lang, byte aspect, byte subpath_id) {
        this.coding_type = coding_type;
        this.format = format;
        this.rate = rate;
        this.char_code = char_code;
        this.lang = lang;
        this.aspect = aspect;
        this.subpath_id = subpath_id;
    }

    public CodingType getCodingType() {
        return CodingTypeHelper.getCodingType(coding_type);
    }

    public byte getFormat() {
        return format;
    }

    public Dimension getVideoSize() {
        int width, height;
        switch (format) {
        case (byte)0x01:
        case (byte)0x03:
            width = 720;
            height = 480;
            break;
        case (byte)0x02:
        case (byte)0x07:
            width = 720;
            height = 576;
            break;
        case (byte)0x05:
            width = 1280;
            height = 720;
            break;
        case (byte)0x04:
        case (byte)0x06:
            width = 1920;
            height = 1080;
            break;
        case (byte)0x08:
            width = 3840;
            height = 2160;
        default:
                return null;
        }
        return new Dimension(width, height);
    }

    public byte getVideoAspectRatioCode() {
        return aspect;
    }

    public byte getRate() {
        return rate;
    }

    public char getChar_code() {
        return char_code;
    }

    public String getLang() {
        return lang;
    }

    public int getSubPathId() {
        return subpath_id;
    }

    private final byte coding_type;
    private final byte format;
    private final byte rate;
    private final char char_code;
    private final String lang;
    private final byte aspect;
    private final byte subpath_id;
}
