#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

/* The compositor's frame-pacing histogram, run on the host with synthetic frame streams. */
#include "frame_stats.c"

#define MS(x) ((int64_t)((x) * 1000000.0))

static struct frame_hist h;

static int near(double a, double b) { return fabs(a - b) < 1e-9; }

static void reset(void) { memset(&h, 0, sizeof(h)); }

/* A steady 60 fps stream: every percentile is one frame interval, to the bin above it. */
static void steady(void) {
    reset();
    int64_t t = MS(1000);
    for (int i = 0; i <= 600; i++, t += MS(16.666)) frame_hist_tick(&h, &h, t);
    assert(h.count == 600);
    assert(near(frame_hist_ms(&h, 50), 16.7) && near(frame_hist_ms(&h, 99), 16.7));
    assert(frame_hist_at_least(&h, 2.0 * frame_hist_ms(&h, 50)) == 0);
}

/* 60 fps on a 144 Hz panel: shown frames alternate 2 and 3 refreshes (13.9 / 20.8 ms). The
 * average is still 60, the percentiles show the judder. */
static void judder(void) {
    reset();
    int64_t t = MS(1000);
    frame_hist_tick(&h, &h, t);
    for (int i = 0; i < 600; i++) {
        t += (i % 2) ? MS(20.833) : MS(13.889);
        frame_hist_tick(&h, &h, t);
    }
    assert(h.count == 600);
    assert(near(frame_hist_ms(&h, 50), 13.9));
    assert(near(frame_hist_ms(&h, 51), 20.9));
    assert(near(frame_hist_ms(&h, 99), 20.9));
}

/* 1 % of hitches: p95 stays on the steady interval, p99 and the count find them. */
static void hitches(void) {
    reset();
    int64_t t = MS(1000);
    frame_hist_tick(&h, &h, t);
    for (int i = 0; i < 1000; i++) {
        t += (i % 100 == 99) ? MS(50) : MS(16.6);
        frame_hist_tick(&h, &h, t);
    }
    assert(near(frame_hist_ms(&h, 95), 16.7));
    assert(near(frame_hist_ms(&h, 99), 16.7));
    assert(near(frame_hist_ms(&h, 99.5), 50.1));
    assert(frame_hist_at_least(&h, 2.0 * frame_hist_ms(&h, 50)) == 10);
}

/* A loading pause longer than the histogram: the overflow bin holds it and the maximum is exact. */
static void overflow(void) {
    reset();
    int64_t t = MS(1000);
    frame_hist_tick(&h, &h, t);
    for (int i = 0; i < 99; i++) frame_hist_tick(&h, &h, t += MS(10));
    frame_hist_tick(&h, &h, t += MS(2500));
    assert(h.count == 100 && h.over == 1);
    assert(near(frame_hist_ms(&h, 100), 2500.0));
    assert(near(frame_hist_ms(&h, 99), 10.1));
    assert(frame_hist_at_least(&h, 150.0) == 1);
}

/* Another window's frames never make an interval with this one's, and a new window keeps the clock. */
static void owners_and_windows(void) {
    reset();
    int a, b;
    frame_hist_tick(&h, &a, MS(1000));
    frame_hist_tick(&h, &a, MS(1016));
    frame_hist_tick(&h, &b, MS(1020));  /* restarts on b */
    frame_hist_tick(&h, &b, MS(1030));
    assert(h.count == 2 && near(frame_hist_ms(&h, 100), 16.1));
    frame_hist_window(&h);
    assert(h.count == 0 && h.max_ns == 0 && near(frame_hist_ms(&h, 50), 0.0));
    frame_hist_tick(&h, &b, MS(1045));  /* the interval across the window boundary still counts */
    assert(h.count == 1 && near(frame_hist_ms(&h, 50), 15.1));
    frame_hist_tick(&h, &b, MS(1040));  /* a clock going backwards adds nothing */
    assert(h.count == 1);
}

static void empty(void) {
    reset();
    assert(near(frame_hist_ms(&h, 50), 0.0) && frame_hist_at_least(&h, 0.0) == 0);
    frame_hist_add(&h, -5);             /* clamped, never a negative bin */
    assert(h.count == 1 && h.bins[0] == 1);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *c = argv[1];
    if (!strcmp(c, "steady")) steady();
    else if (!strcmp(c, "judder")) judder();
    else if (!strcmp(c, "hitches")) hitches();
    else if (!strcmp(c, "overflow")) overflow();
    else if (!strcmp(c, "owners")) owners_and_windows();
    else if (!strcmp(c, "empty")) empty();
    else return 2;
    puts("PASS");
    return 0;
}
