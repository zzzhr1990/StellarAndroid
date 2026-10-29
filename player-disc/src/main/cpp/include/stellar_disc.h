#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// Opaque handles: allocated, read and freed by the same library/runtime.
void* stellar_disc_create(int bluray);
int stellar_disc_open(void* stream, const char* address);
int64_t stellar_disc_read(void* stream, uint8_t* bytes, int64_t length);
int64_t stellar_disc_seek(void* stream, int64_t offset, int origin);
int64_t stellar_disc_size(void* stream);
int64_t stellar_disc_position(void* stream);
void stellar_disc_abort(void* stream);
void stellar_disc_close(void* stream);
int stellar_disc_palette(const char* address, char* output, int capacity);
#ifdef __cplusplus
}
#endif
