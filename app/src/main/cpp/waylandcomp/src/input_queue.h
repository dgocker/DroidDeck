/*
 * Input from the app's threads to the compositor thread (compositor.c): a non-blocking pipe that the
 * event loop drains, plus an ordered overflow behind it. The pipe holds about 3 200 messages; when it
 * is full - the compositor thread stuck in a long frame, say - a write used to fail and the event was
 * lost without a trace, a key release among them (a key held for ever). Now:
 *   - once one message waits in the overflow, every later one queues behind it, so the order the app
 *     sent them in is the order they arrive in;
 *   - motion (absolute pointer, touch moves of one finger) replaces the motion right before it in the
 *     overflow, and relative motion and wheel steps add up, so a stall costs no memory per finger move;
 *   - the vsync tick never queues: a tick that finds the pipe full leaves only its time, latest wins;
 *   - past INPUT_QUEUE_CAP queued messages, what still cannot be merged is dropped and COUNTED.
 * Writers: any thread. Reader: the compositor thread. The lock is held only for a write, or for the
 * reader's last look at the pipe and its copy of the overflow - never while a message is delivered.
 */
#ifndef INPUT_QUEUE_H
#define INPUT_QUEUE_H
#include <pthread.h>
#include <stdint.h>

/* type: 0 pointer (p1 action: 0 press, 1 move, 2 release; p2,p3 input space), 1 key (p1 evdev, p2
 * state), 2 scene motion (p1,p2), 3 button (p1 code, p2 pressed), 4 wheel (p1 steps), 5 vsync (p1,p2 =
 * frame time hi/lo), 6 relative motion (p1,p2 in 1/256 px), 7 touch (p1 action: 0 down, 1 move, 2 up,
 * 3 cancel; p2 id; p3,p4 position), 8 output resized. */
struct input_msg { int type; int p1; int p2; int p3; int p4; };

enum { INPUT_POINTER = 0, INPUT_KEY = 1, INPUT_SCENE_MOTION = 2, INPUT_BUTTON = 3, INPUT_WHEEL = 4,
       INPUT_VSYNC = 5, INPUT_RELATIVE = 6, INPUT_TOUCH = 7, INPUT_RESIZED = 8 };

#define INPUT_QUEUE_CAP 4096

struct input_stats {
    unsigned queued;     /* went to the overflow because the pipe was full (or held earlier ones) */
    unsigned merged;     /* folded into the overflow's last message */
    unsigned dropped;    /* lost: the overflow was full and the message could not be merged */
    unsigned ticks_merged; /* vsync ticks that found the pipe full (only the latest is delivered) */
};

struct input_queue {
    int rfd, wfd;
    pthread_mutex_t lock;
    unsigned n;
    struct input_msg ovf[INPUT_QUEUE_CAP];
    struct input_msg taken[2 * INPUT_QUEUE_CAP]; /* the reader's copy (late pipe + overflow), delivered unlocked */
    int tick_pending;
    struct input_msg tick;
    struct input_stats stats;
};

#define INPUT_QUEUE_INIT { .rfd = -1, .wfd = -1, .lock = PTHREAD_MUTEX_INITIALIZER }

/* The pipe (non-blocking, close-on-exec). 0 = ok; the read end is q->rfd. */
int input_queue_open(struct input_queue *q);
/* Any thread. 0 = it will be delivered; -1 = the queue is not open, or the message was dropped
 * (counted in the stats). */
int input_queue_push(struct input_queue *q, const struct input_msg *m);
/* The compositor thread, when q->rfd is readable: everything in the pipe, then the overflow, then a
 * merged vsync tick, each in order, to deliver(). Never called with the lock held. */
void input_queue_drain(struct input_queue *q, void (*deliver)(const struct input_msg *m, void *ctx), void *ctx);
/* The counters since the last take. */
void input_queue_stats_take(struct input_queue *q, struct input_stats *out);

#endif
