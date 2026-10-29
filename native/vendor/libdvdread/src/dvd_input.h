/*
 * Copyright (C) 2001, 2002 Samuel Hocevar <sam@zoy.org>,
 *                          Håkan Hjort <d95hjort@dtek.chalmers.se>
 *
 * This file is part of libdvdread.
 *
 * libdvdread is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * libdvdread is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with libdvdread; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

#ifndef LIBDVDREAD_DVD_INPUT_H
#define LIBDVDREAD_DVD_INPUT_H

/**
 * Defines and flags.  Make sure they fit the libdvdcss API!
 */
#include "dvdread_internal.h"
#define DVDINPUT_NOFLAGS         0

#define DVDINPUT_READ_DECRYPT    (1 << 0)

typedef struct dvd_input_s *dvd_input_t;

/**
 * Function pointers that will be filled in by the input implementation.
 * These functions provide the main API.
 */
extern dvd_input_t (*dvdinput_open)  (void *, dvd_logger_cb *,
                                      const char *,
                                      dvd_reader_stream_cb *,
                                      dvd_reader_filesystem_h *);
extern int         (*dvdinput_close) (dvd_input_t);
extern int         (*dvdinput_seek)  (dvd_input_t, int);
extern int         (*dvdinput_title) (dvd_input_t, int);
extern int         (*dvdinput_read)  (dvd_input_t, void *, int, int);
/* run to initialize DVD-Audio encryption */
extern int         (*dvdinput_init)  (dvd_input_t, uint8_t* mkb);

extern void        dvdinput_set_stream(dvd_input_t, dvd_type_t);
/**
 * Setup function accessed by dvd_reader.c.  Returns 1 if there is CSS support.
 * Otherwise it fallsback to the internal dvdread implementation (without css support)
 * which is basically the same as calling dvdinput_setup_builtin.
 */
/* dvda flag enabled cpxm */
int dvdinput_setup(void *, dvd_logger_cb *, dvd_type_t dvda_flag);

/**
 * Setup function accessed by dvd_reader.c using the builtin libdvdread implementation
 * (without css support)
 */
void dvdinput_setup_builtin(void *, dvd_logger_cb *);

#endif /* LIBDVDREAD_DVD_INPUT_H */
