/* See input_queue.h. */
#define _GNU_SOURCE 1
#include "input_queue.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

int input_queue_open(struct input_queue *q) {
    int fds[2];
    if (pipe2(fds, O_CLOEXEC | O_NONBLOCK) != 0) return -1;
    pthread_mutex_lock(&q->lock);
    q->rfd = fds[0];
    q->wfd = fds[1];
    pthread_mutex_unlock(&q->lock);
    return 0;
}

static int write_one(int fd, const struct input_msg *m) {
    ssize_t n;
    do { n = write(fd, m, sizeof(*m)); } while (n < 0 && errno == EINTR);
    return n == (ssize_t)sizeof(*m) ? 0 : -1; /* writes this small are all or nothing on a pipe */
}

/* Fold m into the overflow's last message when it only moves on from it. Lock held. */
static int merge(struct input_queue *q, const struct input_msg *m) {
    if (!q->n) return 0;
    struct input_msg *last = &q->ovf[q->n - 1];
    if (last->type != m->type) return 0;
    switch (m->type) {
    case INPUT_SCENE_MOTION:
        *last = *m;
        return 1;
    case INPUT_POINTER:
        if (last->p1 != 1 || m->p1 != 1) return 0; /* moves only: a press or release stays */
        *last = *m;
        return 1;
    case INPUT_TOUCH:
        if (last->p1 != 1 || m->p1 != 1 || last->p2 != m->p2) return 0; /* the same finger moving */
        *last = *m;
        return 1;
    case INPUT_RELATIVE:
        last->p1 += m->p1;
        last->p2 += m->p2;
        return 1;
    case INPUT_WHEEL:
        last->p1 += m->p1;
        return 1;
    case INPUT_RESIZED:
        return 1; /* re-sends the current size: one is as good as two */
    default:
        return 0;
    }
}

int input_queue_push(struct input_queue *q, const struct input_msg *m) {
    int r = 0;
    pthread_mutex_lock(&q->lock);
    if (q->wfd < 0) {
        r = -1;
    } else if (m->type == INPUT_VSYNC) {
        /* Order does not matter for a tick, and only the latest one means anything. */
        if (write_one(q->wfd, m) != 0) {
            q->tick = *m;
            q->tick_pending = 1;
            q->stats.ticks_merged++;
        }
    } else if (q->n || write_one(q->wfd, m) != 0) {
        /* The pipe is full, or earlier messages already wait in the overflow: queue behind them. A
         * full overflow means the pipe is full too, so the reader is going to run and empty both. */
        if (merge(q, m)) {
            q->stats.merged++;
        } else if (q->n < INPUT_QUEUE_CAP) {
            q->ovf[q->n++] = *m;
            q->stats.queued++;
        } else {
            q->stats.dropped++;
            r = -1;
        }
    }
    pthread_mutex_unlock(&q->lock);
    return r;
}

static int read_one(int fd, struct input_msg *m) {
    ssize_t n;
    do { n = read(fd, m, sizeof(*m)); } while (n < 0 && errno == EINTR);
    return n == (ssize_t)sizeof(*m);
}

void input_queue_drain(struct input_queue *q, void (*deliver)(const struct input_msg *m, void *ctx), void *ctx) {
    struct input_msg m, tick;
    for (;;) {
        while (read_one(q->rfd, &m)) deliver(&m, ctx);
        /* The overflow only holds what was sent after the pipe filled, so it is newer than everything
         * in the pipe - but writers may have refilled the pipe since the read above found it empty. With
         * the lock held nothing can be written: read what came meanwhile, then take the overflow behind
         * it, and deliver both, in that order, once the lock is released. */
        pthread_mutex_lock(&q->lock);
        unsigned k = 0;
        while (k < INPUT_QUEUE_CAP && read_one(q->rfd, &q->taken[k])) k++;
        if (k == INPUT_QUEUE_CAP) {
            /* Still more in the pipe than one copy holds: deliver this much and look again. */
            pthread_mutex_unlock(&q->lock);
            for (unsigned i = 0; i < k; i++) deliver(&q->taken[i], ctx);
            continue;
        }
        const unsigned count = q->n;
        if (count) memcpy(q->taken + k, q->ovf, count * sizeof(q->taken[0]));
        q->n = 0;
        const int have_tick = q->tick_pending;
        tick = q->tick;
        q->tick_pending = 0;
        pthread_mutex_unlock(&q->lock);
        for (unsigned i = 0; i < k + count; i++) deliver(&q->taken[i], ctx);
        if (have_tick) deliver(&tick, ctx);
        return;
    }
}

void input_queue_stats_take(struct input_queue *q, struct input_stats *out) {
    pthread_mutex_lock(&q->lock);
    *out = q->stats;
    memset(&q->stats, 0, sizeof(q->stats));
    pthread_mutex_unlock(&q->lock);
}
