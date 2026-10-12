/* See vsync_period.h. */
#include "vsync_period.h"

int64_t vsync_period_tick(struct vsync_period *v, int64_t frame_time_ns) {
    const int64_t last = v->last_ns;
    v->last_ns = frame_time_ns;
    if (!last || frame_time_ns <= last) return v->period_ns; /* the first tick, or a clock gone back */
    const int64_t d = frame_time_ns - last;
    if (d < VSYNC_MIN_NS || d > VSYNC_MAX_NS) return v->period_ns;
    v->window[v->next] = d;
    v->next = (v->next + 1) % VSYNC_WINDOW;
    if (v->n < VSYNC_WINDOW) v->n++;
    int64_t shortest = v->window[0];
    for (unsigned i = 1; i < v->n; i++)
        if (v->window[i] < shortest) shortest = v->window[i];
    v->period_ns = shortest;
    return shortest;
}
