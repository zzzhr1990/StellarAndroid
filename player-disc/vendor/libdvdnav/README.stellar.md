# libdvdnav 7.0.0 Android adapter

Source: https://download.videolan.org/pub/videolan/libdvdnav/7.0.0/libdvdnav-7.0.0.tar.xz
SHA-256: a2a18f5ad36d133c74bf9106b6445806fa253b09141a46392550394b647b221e
License: GPL-2.0-or-later. Preserve upstream source notices and COPYING.

CMakeLists.txt and reader_bridge.c are project adapters. Only vm.c is compiled with
DVDOpenStream2=stellar_dvdnav_reader, handing an existing libdvdread stream into the
upstream VM constructor. The implementation remains unmodified. It is linked into
libstellar_disc.so for MPV. No libdvdcss, decryption keys, or Java runtime is included.
