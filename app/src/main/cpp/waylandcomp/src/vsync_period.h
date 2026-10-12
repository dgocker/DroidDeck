/*
 * The screen's refresh period for the release pacer (compositor.c), from the Choreographer's frame
 * times. Those are vsync timestamps: they carry none of the delay with which a tick reaches the
 * compositor thread, which is what the old estimate measured (and a late tick followed by an early
 * one moved it up, never down: the early one fell under its 3 ms floor). A vsync the app did not get a
 * callback for - its UI thread was late, or two ticks merged in a full input pipe - only makes an
 * interval LONGER, a whole number of periods, never shorter. So the period is the shortest interval
 * among the recent ones: exact at once when the panel speeds up, and after VSYNC_WINDOW ticks when it
 * slows down (a mode switch, an adaptive-refresh panel dropping its rate).
 * Limit: a tick stream the system itself thins out for good (a frame-rate override for the app) reads
 * as a slower panel, as it did before.
 */
#ifndef VSYNC_PERIOD_H
#define VSYNC_PERIOD_H
#include <stdint.h>

#define VSYNC_WINDOW 32
#define VSYNC_MIN_NS 2000000LL    /* 500 Hz: anything shorter is not a refresh */
#define VSYNC_MAX_NS 100000000LL  /* 10 Hz: anything longer is a pause, not a refresh */

struct vsync_period {
    int64_t last_ns;             /* the previous frame time, 0 = none yet */
    int64_t window[VSYNC_WINDOW];
    unsigned n, next;
    int64_t period_ns;           /* 0 until the first usable interval */
};

/* A tick with this frame time. Returns the period in ns, 0 while unknown. */
int64_t vsync_period_tick(struct vsync_period *v, int64_t frame_time_ns);

#endif
