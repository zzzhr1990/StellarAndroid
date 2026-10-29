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

package org.videolan.ti;

import javax.tv.service.navigation.StreamType;
import org.bluray.ti.CodingType;
import org.blurayx.uhd.ti.UHDCodingType;
import org.blurayx.s3d.ti.StereoscopicCodingType;

public class CodingTypeHelper {
    public static CodingType getCodingType(byte coding_type_byte) {
        switch (coding_type_byte) {
        case (byte)0x02:
            return CodingType.MPEG2_VIDEO;
        case (byte)0x1b:
            return CodingType.MPEG4_AVC_VIDEO;
	case (byte)0x20:
	    return StereoscopicCodingType.MPEG4_MVC_VIDEO;
	case (byte)0x24:
	    return UHDCodingType.HEVC_VIDEO;
        case (byte)0xea:
            return CodingType.SMPTE_VC1_VIDEO;
        case (byte)0x80:
            return CodingType.LPCM_AUDIO;
        case (byte)0x81:
            return CodingType.DOLBY_AC3_AUDIO;
        case (byte)0x82:
            return CodingType.DTS_AUDIO;
        case (byte)0x83:
            return CodingType.DOLBY_LOSSLESS_AUDIO;
        case (byte)0x84:
        case (byte)0xA1:
            return CodingType.DOLBY_DIGITAL_PLUS_AUDIO;
        case (byte)0x85:
            return CodingType.DTS_HD_AUDIO_EXCEPT_XLL;
        case (byte)0x86:
            return CodingType.DTS_HD_AUDIO_XLL;
        case (byte)0xA2:
            return CodingType.DTS_HD_AUDIO_LBR;
        //FIXME:case (byte)0x??:
        //    return CodingType.DRA_AUDIO;
        //FIXME:case (byte)0x??:
        //    return CodingType.DRA_EXTENSION_AUDIO;
        case (byte)0x90:
            return CodingType.PRESENTATION_GRAPHICS;
        case (byte)0x91:
            return CodingType.INTERACTIVE_GRAPHICS;
        case (byte)0x92:
            return CodingType.TEXT_SUBTITLE;
        default:
            return null;
        }
    }
}
