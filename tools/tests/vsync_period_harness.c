#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The compositor's refresh-period estimate (vsync_period.c) against synthetic tick streams. */
#include "vsync_period.c"

#define P144 6944444LL   /* 144 Hz */
#define P120 8333333LL
#define P60 16666667LL

static int near(int64_t a, int64_t b, int64_t tol) { return llabs(a - b) <= tol; }

/* What on_vsync did before: an EMA of the time between the compositor thread HANDLING two ticks,
 * keeping intervals strictly between 3 and 40 ms. */
static int64_t old_estimate(const int64_t *handled, int n) {
    int64_t est = 16666667, last = 0;
    for (int i = 0; i < n; i++) {
        if (last) {
            int64_t d = handled[i] - last;
            if (d > 3000000LL && d < 40000000LL) est = (est * 7 + d) / 8;
        }
        last = handled[i];
    }
    return est;
}

/* 144 Hz ticks, every fifth one the compositor thread handles 4 ms late (a long frame): the frame
 * times stay exact. The old estimate drifts above the period (the late tick counts, the next one comes
 * under 3 ms and is thrown away); the new one is the period. */
static void late_handling(void) {
    enum { N = 2000 };
    static int64_t frame[N], handled[N];
    struct vsync_period v = {0};
    for (int i = 0; i < N; i++) {
        frame[i] = 1000000000LL + i * P144;
        handled[i] = frame[i] + 500000 + ((i % 5 == 4) ? 4000000 : 0);
        vsync_period_tick(&v, frame[i]);
    }
    const int64_t old = old_estimate(handled, N);
    assert(old > P144 * 105 / 100);              /* the defect: more than 5 % long */
    assert(near(v.period_ns, P144, 1000));
}

/* Ticks the app never got (its UI thread was late, or ticks merged in a full pipe) are whole periods
 * missing: the estimate stays the period. */
static void skipped_ticks(void) {
    struct vsync_period v = {0};
    int64_t t = 5000000000LL;
    for (int i = 0; i < 1000; i++) {
        t += (i % 3 == 0) ? 2 * P144 : (i % 7 == 0) ? 3 * P144 : P144;
        vsync_period_tick(&v, t);
    }
    assert(near(v.period_ns, P144, 1000));
}

/* A panel that speeds up is followed at once; one that slows down once the window has forgotten. */
static void mode_switches(void) {
    struct vsync_period v = {0};
    int64_t t = 1000000000LL;
    for (int i = 0; i < 100; i++) vsync_period_tick(&v, t += P60);
    assert(near(v.period_ns, P60, 1000));
    vsync_period_tick(&v, t += P144);
    assert(near(v.period_ns, P144, 1000));       /* 60 -> 144: the next tick */
    for (int i = 0; i < VSYNC_WINDOW - 1; i++) vsync_period_tick(&v, t += P120);
    assert(near(v.period_ns, P144, 1000));       /* still remembers the 144 Hz interval */
    vsync_period_tick(&v, t += P120);
    assert(near(v.period_ns, P120, 1000));       /* 144 -> 120: after VSYNC_WINDOW ticks */
}

/* Pauses (the app in the background), a clock that jumps back, and the first tick change nothing. */
static void garbage(void) {
    struct vsync_period v = {0};
    assert(vsync_period_tick(&v, 1000000000LL) == 0);
    assert(vsync_period_tick(&v, 1000000000LL + P120) == P120);
    assert(vsync_period_tick(&v, 6000000000LL) == P120);           /* a 5 s pause */
    assert(vsync_period_tick(&v, 5000000000LL) == P120);           /* backwards */
    assert(vsync_period_tick(&v, 5000000000LL + 500000) == P120);  /* 0.5 ms: not a refresh */
    assert(vsync_period_tick(&v, 5000000000LL + 500000 + P120) == P120);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    if (!strcmp(argv[1], "late")) late_handling();
    else if (!strcmp(argv[1], "skipped")) skipped_ticks();
    else if (!strcmp(argv[1], "modes")) mode_switches();
    else if (!strcmp(argv[1], "garbage")) garbage();
    else return 2;
    puts("PASS");
    return 0;
}
