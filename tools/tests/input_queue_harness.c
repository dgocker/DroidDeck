#define _GNU_SOURCE 1
#include <assert.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* The compositor's input queue (input_queue.c) on the host: a pipe made small so it fills at once. */
#include "input_queue.c"

static struct input_queue q = INPUT_QUEUE_INIT;
static struct input_msg got[200000];
static unsigned n_got;

static void collect(const struct input_msg *m, void *ctx) {
    (void)ctx;
    assert(n_got < sizeof(got) / sizeof(got[0]));
    got[n_got++] = *m;
}

static void open_small(void) {
    memset(&q, 0, sizeof(q));
    q.rfd = q.wfd = -1;
    pthread_mutex_init(&q.lock, NULL);
    assert(input_queue_open(&q) == 0);
    fcntl(q.wfd, F_SETPIPE_SZ, 4096);   /* 204 messages */
    n_got = 0;
}

static struct input_msg msg(int type, int p1, int p2, int p3, int p4) {
    struct input_msg m = {type, p1, p2, p3, p4};
    return m;
}

/* Far more key presses and releases than the pipe holds, nobody reading: every one arrives, in order. */
static void keys_survive_a_full_pipe(void) {
    open_small();
    for (int i = 0; i < 3000; i++) {
        struct input_msg m = msg(INPUT_KEY, 30, i, 0, 0);
        assert(input_queue_push(&q, &m) == 0);
    }
    struct input_stats st;
    input_queue_stats_take(&q, &st);
    assert(st.queued > 0 && st.dropped == 0);
    input_queue_drain(&q, collect, NULL);
    assert(n_got == 3000);
    for (unsigned i = 0; i < n_got; i++) assert(got[i].type == INPUT_KEY && got[i].p2 == (int)i);
}

static void fill_pipe(void) {
    struct input_msg k = msg(INPUT_KEY, 1, 1, 0, 0);
    while (q.n == 0) assert(input_queue_push(&q, &k) == 0);
}

/* Behind a full pipe, moves fold into the move before them but never across a press, a key or another
 * finger; relative motion and wheel steps add up. */
static void motion_merges_without_reordering(void) {
    open_small();
    fill_pipe();                                            /* the last key went to the overflow */
    struct input_msg seq[] = {
        msg(INPUT_POINTER, 1, 10, 10, 0), msg(INPUT_POINTER, 1, 20, 20, 0),   /* -> one move to 20 */
        msg(INPUT_POINTER, 0, 20, 20, 0),                                      /* press stays */
        msg(INPUT_POINTER, 1, 30, 30, 0), msg(INPUT_POINTER, 1, 40, 40, 0),   /* -> one move to 40 */
        msg(INPUT_TOUCH, 1, 1, 5, 5), msg(INPUT_TOUCH, 1, 1, 6, 6),           /* finger 1 -> 6,6 */
        msg(INPUT_TOUCH, 1, 2, 7, 7),                                          /* finger 2 stays */
        msg(INPUT_TOUCH, 2, 2, 7, 7),                                          /* lift stays */
        msg(INPUT_RELATIVE, 256, -256, 0, 0), msg(INPUT_RELATIVE, 512, 0, 0, 0),
        msg(INPUT_WHEEL, -1, 0, 0, 0), msg(INPUT_WHEEL, -2, 0, 0, 0),
        msg(INPUT_KEY, 42, 0, 0, 0),
    };
    for (unsigned i = 0; i < sizeof(seq) / sizeof(seq[0]); i++) assert(input_queue_push(&q, &seq[i]) == 0);
    input_queue_drain(&q, collect, NULL);
    unsigned i = 0;
    while (got[i].type == INPUT_KEY && got[i].p1 == 1) i++;  /* the keys that filled the pipe */
    struct input_msg want[] = {
        msg(INPUT_POINTER, 1, 20, 20, 0), msg(INPUT_POINTER, 0, 20, 20, 0), msg(INPUT_POINTER, 1, 40, 40, 0),
        msg(INPUT_TOUCH, 1, 1, 6, 6), msg(INPUT_TOUCH, 1, 2, 7, 7), msg(INPUT_TOUCH, 2, 2, 7, 7),
        msg(INPUT_RELATIVE, 768, -256, 0, 0), msg(INPUT_WHEEL, -3, 0, 0, 0), msg(INPUT_KEY, 42, 0, 0, 0),
    };
    assert(n_got - i == sizeof(want) / sizeof(want[0]));
    for (unsigned k = 0; k < sizeof(want) / sizeof(want[0]); k++)
        assert(!memcmp(&got[i + k], &want[k], sizeof(want[k])));
}

/* Ticks never queue: a full pipe keeps only the latest one, delivered after everything else. */
static void ticks_keep_the_latest(void) {
    open_small();
    fill_pipe();
    for (int i = 1; i <= 500; i++) {
        struct input_msg t = msg(INPUT_VSYNC, 0, i, 0, 0);
        assert(input_queue_push(&q, &t) == 0);
    }
    assert(q.n == 1);                                       /* only the key that overflowed */
    input_queue_drain(&q, collect, NULL);
    assert(got[n_got - 1].type == INPUT_VSYNC && got[n_got - 1].p2 == 500);
    unsigned ticks = 0;
    for (unsigned i = 0; i < n_got; i++) ticks += got[i].type == INPUT_VSYNC;
    assert(ticks == 1);
    struct input_stats st;
    input_queue_stats_take(&q, &st);
    assert(st.ticks_merged == 500);
}

/* Past the cap, what cannot be merged is dropped - and counted, never silent; merging still works. */
static void past_the_cap_is_counted(void) {
    open_small();
    fill_pipe();
    unsigned refused = 0;
    for (int i = 0; i < INPUT_QUEUE_CAP + 10; i++) {
        struct input_msg k = msg(INPUT_KEY, 2, i, 0, 0);
        refused += input_queue_push(&q, &k) != 0;
    }
    struct input_msg w = msg(INPUT_WHEEL, 1, 0, 0, 0), k2 = msg(INPUT_KEY, 3, 0, 0, 0);
    assert(input_queue_push(&q, &k2) == -1);
    struct input_stats st;
    input_queue_stats_take(&q, &st);
    assert(refused == 11 && st.dropped == 12);
    (void)w;
    input_queue_drain(&q, collect, NULL);
    assert(q.n == 0);
    struct input_msg k3 = msg(INPUT_KEY, 4, 0, 0, 0);
    assert(input_queue_push(&q, &k3) == 0);                 /* drained: the pipe takes it again */
    assert(q.n == 0);
}

/* Four writers against one reader: each writer's messages arrive in its own order, and every message
 * is either delivered or counted as dropped. */
#define WRITERS 4
#define PER_WRITER 40000
static void *writer(void *arg) {
    int id = (int)(intptr_t)arg;
    for (int i = 0; i < PER_WRITER; i++) {
        struct input_msg k = msg(INPUT_KEY, 100 + id, i, 0, 0);
        input_queue_push(&q, &k);
    }
    return NULL;
}
static atomic_int writers_done;
static void *reader(void *arg) {
    (void)arg;
    for (;;) {
        struct pollfd p = {.fd = q.rfd, .events = POLLIN};
        poll(&p, 1, 5);
        input_queue_drain(&q, collect, NULL);
        if (atomic_load(&writers_done)) { input_queue_drain(&q, collect, NULL); return NULL; }
    }
}
static void concurrent(void) {
    open_small();
    pthread_t w[WRITERS], r;
    pthread_create(&r, NULL, reader, NULL);
    for (int i = 0; i < WRITERS; i++) pthread_create(&w[i], NULL, writer, (void *)(intptr_t)i);
    for (int i = 0; i < WRITERS; i++) pthread_join(w[i], NULL);
    atomic_store(&writers_done, 1);
    pthread_join(r, NULL);
    struct input_stats st;
    input_queue_stats_take(&q, &st);
    int last[WRITERS];
    for (int i = 0; i < WRITERS; i++) last[i] = -1;
    for (unsigned i = 0; i < n_got; i++) {
        int id = got[i].p1 - 100;
        assert(got[i].type == INPUT_KEY && id >= 0 && id < WRITERS);
        assert(got[i].p2 > last[id]);
        last[id] = got[i].p2;
    }
    assert(n_got + st.dropped == WRITERS * PER_WRITER);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    const char *c = argv[1];
    if (!strcmp(c, "keys")) keys_survive_a_full_pipe();
    else if (!strcmp(c, "merge")) motion_merges_without_reordering();
    else if (!strcmp(c, "ticks")) ticks_keep_the_latest();
    else if (!strcmp(c, "cap")) past_the_cap_is_counted();
    else if (!strcmp(c, "threads")) concurrent();
    else return 2;
    puts("PASS");
    return 0;
}
