#include <dvdnav/dvdnav.h>
/* libdvdnav 7 has no DVDOpenFiles entry point. Transfer an already-open
 * libdvdread reader during synchronous VM initialization; callbacks and
 * ownership thereafter belong to the normal upstream VM. TLS isolates opens. */
static _Thread_local dvd_reader_t *opening_reader;
dvd_reader_t *stellar_dvdnav_reader(void *p, const dvd_logger_cb *log, dvd_reader_stream_cb *cb) {
    (void)p; (void)log; (void)cb;
    dvd_reader_t *reader = opening_reader;
    opening_reader = NULL;
    return reader;
}
static int unused_read(void *p, void *b, int n) { (void)p; (void)b; (void)n; return -1; }
int stellar_dvdnav_open(dvdnav_t **nav, dvd_reader_t *reader, const dvdnav_logger_cb *log) {
    opening_reader = reader;
    dvdnav_stream_cb cb = { .pf_read = unused_read };
    int result = dvdnav_open_stream2(nav, reader, log, &cb);
    if (opening_reader) DVDClose(opening_reader);
    opening_reader = NULL;
    return result;
}
