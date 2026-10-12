/*
 * Frame pacing for the 10 s `pacing` line (compositor.c): how frames are spread, not only how many.
 * An average hides a stutter - 60 fps can be even 16.7 ms frames or 10 ms and 23 ms ones - so each
 * duration goes into a fixed histogram (0.1 ms bins up to 100 ms, one overflow bin, the true
 * maximum) and is read back as percentiles once per window. No allocation, O(1) per sample.
 * Compositor thread only.
 */
#ifndef FRAME_STATS_H
#define FRAME_STATS_H
#include <stdint.h>

#define FRAME_HIST_BIN_US 100
#define FRAME_HIST_BINS 1000 /* 0..100 ms; longer samples land in the overflow bin */

struct frame_hist {
    uint32_t count;
    uint32_t over;          /* samples past the last bin */
    int64_t max_ns;
    int64_t last_ns;        /* the previous frame_hist_tick, 0 = none yet */
    const void *owner;      /* whose frames the intervals are between */
    uint32_t bins[FRAME_HIST_BINS];
};

/* One duration. */
void frame_hist_add(struct frame_hist *h, int64_t ns);
/* A frame of `owner` at now_ns: adds the interval since its previous frame. The first frame, and
 * the first after the owner changed, only start the clock. */
void frame_hist_tick(struct frame_hist *h, const void *owner, int64_t now_ns);
/* The p-th percentile (0 < p <= 100) in ms, nearest rank: the upper edge of its bin, or the
 * maximum when it falls among the overflow samples. 0 without samples. */
double frame_hist_ms(const struct frame_hist *h, double p);
/* How many samples are at least ms long (to the bin: a sample counts once its bin starts at ms). */
uint32_t frame_hist_at_least(const struct frame_hist *h, double ms);
/* The next window: samples and maximum cleared, the running clock (last frame, owner) kept. */
void frame_hist_window(struct frame_hist *h);

#endif
