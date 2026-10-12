/* See frame_stats.h. */
#include "frame_stats.h"
#include <string.h>

void frame_hist_add(struct frame_hist *h, int64_t ns) {
    if (ns < 0) ns = 0;
    int64_t bin = ns / (FRAME_HIST_BIN_US * 1000LL);
    if (bin < FRAME_HIST_BINS) h->bins[bin]++;
    else h->over++;
    if (ns > h->max_ns) h->max_ns = ns;
    h->count++;
}

void frame_hist_tick(struct frame_hist *h, const void *owner, int64_t now_ns) {
    if (h->last_ns && h->owner == owner && now_ns >= h->last_ns) frame_hist_add(h, now_ns - h->last_ns);
    h->owner = owner;
    h->last_ns = now_ns;
}

double frame_hist_ms(const struct frame_hist *h, double p) {
    if (!h->count || p <= 0.0) return 0.0;
    if (p > 100.0) p = 100.0;
    /* Nearest rank: the smallest sample with at least p % of all samples at or below it. */
    uint32_t rank = (uint32_t)(p / 100.0 * h->count);
    if ((double)rank < p / 100.0 * h->count) rank++;
    if (rank < 1) rank = 1;
    uint32_t seen = 0;
    for (int i = 0; i < FRAME_HIST_BINS; i++) {
        seen += h->bins[i];
        if (seen >= rank) return (double)(i + 1) * FRAME_HIST_BIN_US / 1000.0;
    }
    return (double)h->max_ns / 1e6;
}

uint32_t frame_hist_at_least(const struct frame_hist *h, double ms) {
    if (ms <= 0.0) return h->count;
    int64_t first = (int64_t)(ms * 1000.0 / FRAME_HIST_BIN_US);
    if ((double)first * FRAME_HIST_BIN_US < ms * 1000.0) first++;
    uint32_t n = h->over;
    for (int64_t i = first; i < FRAME_HIST_BINS; i++) n += h->bins[i];
    return n;
}

void frame_hist_window(struct frame_hist *h) {
    memset(h->bins, 0, sizeof(h->bins));
    h->count = 0;
    h->over = 0;
    h->max_ns = 0;
}
