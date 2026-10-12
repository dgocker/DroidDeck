#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The compositor's shared fence and command buffer (vk_present.c) against a fake Vulkan device that
 * knows what is still running on its "GPU" and aborts on every reset or destroy the spec forbids while
 * it runs (VUID-vkResetFences-pFences-01123, VUID-vkResetCommandBuffer-commandBuffer-00045,
 * VUID-vkDestroyImage-image-01000). No Android surface, no GPU. */
#include "vk_loader.h"
struct vk_api g_vk;
#include "vk_present.c"

/* ---- what the rest of the compositor provides; none of it is reached on these paths but by name */
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }
int32_t ANativeWindow_getWidth(ANativeWindow *w) { (void)w; return 0; }
int32_t ANativeWindow_getHeight(ANativeWindow *w) { (void)w; return 0; }
void ANativeWindow_release(ANativeWindow *w) { (void)w; }
static unsigned logged;
void droiddeck_log(const char *tag, const char *fmt, ...) { (void)tag; (void)fmt; logged++; }
void droiddeck_on_first_frame(void) {}
int droiddeck_color_requested(void) { return 0; }
float droiddeck_color_sdr_white(void) { return 203.0f; }
void blendp_begin_frame(void) {}
void blendp_bind_device(VkDevice d) { (void)d; }
int blendp_draw(VkCommandBuffer c, VkImage s, VkFormat sf, int sw, int sh, int lin, VkImage t, VkFormat tf, int tw, int th,
                const VkImageBlit *b) { (void)c; (void)s; (void)sf; (void)sw; (void)sh; (void)lin; (void)t; (void)tf;
                (void)tw; (void)th; (void)b; return -1; }
void blendp_forget_image(VkImage i) { (void)i; }
void hdrc_bind_device(VkDevice d, const VkPhysicalDeviceMemoryProperties *m) { (void)d; (void)m; }
int hdrc_encode(VkCommandBuffer c, VkImage m, VkImage o, VkFormat f, int w, int h, const struct hdrc_params *p) {
    (void)c; (void)m; (void)o; (void)f; (void)w; (void)h; (void)p; return -1; }
void hdrc_forget_image(VkImage i) { (void)i; }
void sc_layer_window_gone(void) {}
PFN_vkGetInstanceProcAddr vk_loader_gipa(void) { return NULL; }
void vk_loader_load_device(VkDevice d) { (void)d; }
void vk_loader_load_instance(VkInstance i) { (void)i; }
int vk_loader_open(const char *a, const char *b, const char *c) { (void)a; (void)b; (void)c; return -1; }
int vkp_effects_active(void) { return 0; }
void vkp_effects_bind_device(VkDevice d, VkPhysicalDevice p, const VkPhysicalDeviceMemoryProperties *m) { (void)d; (void)p; (void)m; }
VkFilter vkp_effects_blit_filter(void) { return VK_FILTER_LINEAR; }
void vkp_effects_forget_image(VkImage i) { (void)i; }
VkImage vkp_effects_run(VkCommandBuffer c, VkImage s, int sw, int sh, int mw, int mh, int *rw, int *rh) {
    (void)c; (void)sw; (void)sh; (void)mw; (void)mh; (void)rw; (void)rh; return s; }
void vkp_effects_set_formats(VkFormat a, VkFormat b) { (void)a; (void)b; }
int vkp_framegen_active(void) { return 0; }
const void *vkp_framegen_device_features(VkInstance i, PFN_vkGetInstanceProcAddr g, VkPhysicalDevice p) {
    (void)i; (void)g; (void)p; return NULL; }
void vkp_framegen_device_lost(void) {}
void vkp_framegen_device_ready(VkDevice d, VkQueue q, uint32_t f, int lsfg) { (void)d; (void)q; (void)f; (void)lsfg; }
int vkp_framegen_extra_images(void) { return 0; }
int vkp_framegen_format_ok(VkFormat f) { (void)f; return 0; }
void vkp_framegen_presented(int n) { (void)n; }
int vkp_framegen_run(VkCommandBuffer c, VkImage s, VkImageView v, int w, int h, VkFormat f,
                     VkImage o[VKP_FG_MAX_GENERATIONS]) {
    (void)c; (void)s; (void)v; (void)w; (void)h; (void)f; (void)o; return 0; }

/* ---- the fake GPU */
#define MAX_IMAGES 64
static int gpu_slow;            /* submitted work does not finish until finish_gpu() */
static int gpu_lost;            /* status queries answer DEVICE_LOST */
static int fence_pending;       /* g_fence's submission is still running */
static int cmd0_pending;        /* command buffer slot 0 is still running */
static VkImage recorded[MAX_IMAGES], running[MAX_IMAGES];
static int n_recorded, n_running;
static unsigned resets, submits, destroyed, acquires, surfaces;
static VkImage last_destroyed;

static void finish_gpu(void) { fence_pending = 0; cmd0_pending = 0; n_running = 0; }

static VkResult VKAPI_CALL f_reset_fences(VkDevice d, uint32_t n, const VkFence *f) {
    (void)d; (void)n; (void)f;
    assert(!fence_pending && "vkResetFences on a fence whose work is still running");
    resets++;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL f_reset_cmd(VkCommandBuffer c, VkCommandBufferResetFlags fl) {
    (void)fl;
    assert(!(c == g_cmds[0] && cmd0_pending) && "vkResetCommandBuffer on a command buffer still running");
    n_recorded = 0;
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL f_begin(VkCommandBuffer c, const VkCommandBufferBeginInfo *b) { (void)c; (void)b; return VK_SUCCESS; }
static VkResult VKAPI_CALL f_end(VkCommandBuffer c) { (void)c; return VK_SUCCESS; }
static void VKAPI_CALL f_barrier(VkCommandBuffer c, VkPipelineStageFlags a, VkPipelineStageFlags b, VkDependencyFlags d,
                                 uint32_t nm, const VkMemoryBarrier *m, uint32_t nb, const VkBufferMemoryBarrier *bb,
                                 uint32_t ni, const VkImageMemoryBarrier *ib) {
    (void)c; (void)a; (void)b; (void)d; (void)nm; (void)m; (void)nb; (void)bb; (void)ni; (void)ib; }
static void VKAPI_CALL f_blit(VkCommandBuffer c, VkImage s, VkImageLayout sl, VkImage d, VkImageLayout dl, uint32_t n,
                              const VkImageBlit *r, VkFilter f) {
    (void)c; (void)sl; (void)dl; (void)n; (void)r; (void)f;
    assert(n_recorded + 2 <= MAX_IMAGES);
    recorded[n_recorded++] = s;
    recorded[n_recorded++] = d;
}
static VkResult VKAPI_CALL f_submit(VkQueue q, uint32_t n, const VkSubmitInfo *si, VkFence f) {
    (void)q; (void)n; (void)si;
    submits++;
    if (f != VK_NULL_HANDLE) {
        fence_pending = cmd0_pending = 1;
        memcpy(running, recorded, sizeof(recorded));
        n_running = n_recorded;
        if (!gpu_slow) finish_gpu();
    }
    return VK_SUCCESS;
}
static VkResult VKAPI_CALL f_wait(VkDevice d, uint32_t n, const VkFence *f, VkBool32 all, uint64_t timeout) {
    (void)d; (void)n; (void)f; (void)all;
    if (!fence_pending) return VK_SUCCESS;
    if (timeout == UINT64_MAX) { finish_gpu(); return VK_SUCCESS; } /* an unbounded wait sees it finish */
    return VK_TIMEOUT;
}
static VkResult VKAPI_CALL f_status(VkDevice d, VkFence f) {
    (void)d; (void)f;
    if (gpu_lost) return VK_ERROR_DEVICE_LOST;
    return fence_pending ? VK_NOT_READY : VK_SUCCESS;
}
static void VKAPI_CALL f_destroy_image(VkDevice d, VkImage img, const VkAllocationCallbacks *a) {
    (void)d; (void)a;
    for (int i = 0; i < n_running && !gpu_lost; i++)
        assert(running[i] != img && "vkDestroyImage on an image the GPU is still using");
    destroyed++;
    last_destroyed = img;
}
static void VKAPI_CALL f_free_memory(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks *a) { (void)d; (void)m; (void)a; }
static VkResult VKAPI_CALL f_acquire(VkDevice d, VkSwapchainKHR s, uint64_t t, VkSemaphore sem, VkFence f, uint32_t *i) {
    (void)d; (void)s; (void)t; (void)sem; (void)f; (void)i; acquires++; return VK_ERROR_OUT_OF_DATE_KHR; }
static VkResult VKAPI_CALL f_surface(VkInstance i, const VkAndroidSurfaceCreateInfoKHR *ci, const VkAllocationCallbacks *a,
                                     VkSurfaceKHR *s) { (void)i; (void)ci; (void)a; (void)s; surfaces++; return VK_ERROR_SURFACE_LOST_KHR; }
static void VKAPI_CALL f_destroy_surface(VkInstance i, VkSurfaceKHR s, const VkAllocationCallbacks *a) { (void)i; (void)s; (void)a; }
static VkResult VKAPI_CALL f_idle(VkDevice d) { (void)d; finish_gpu(); return VK_SUCCESS; }
static void VKAPI_CALL f_destroy_sem(VkDevice d, VkSemaphore s, const VkAllocationCallbacks *a) { (void)d; (void)s; (void)a; }
static VkResult VKAPI_CALL f_create_sem(VkDevice d, const VkSemaphoreCreateInfo *ci, const VkAllocationCallbacks *a,
                                        VkSemaphore *s) { (void)d; (void)ci; (void)a; *s = (VkSemaphore)(uintptr_t)7; return VK_SUCCESS; }

static struct vkp_image *image(uintptr_t handle, int dst) {
    struct vkp_image *img = calloc(1, sizeof(*img));
    img->image = (VkImage)handle;
    img->mem = (VkDeviceMemory)(handle + 1000);
    img->w = 64; img->h = 64; img->dmabuf = 1; img->blit_dst = dst;
    return img;
}

static void device_up(void) {
    g_vk.ResetFences = f_reset_fences; g_vk.ResetCommandBuffer = f_reset_cmd;
    g_vk.BeginCommandBuffer = f_begin; g_vk.EndCommandBuffer = f_end; g_vk.CmdPipelineBarrier = f_barrier;
    g_vk.CmdBlitImage = f_blit; g_vk.QueueSubmit = f_submit; g_vk.WaitForFences = f_wait;
    g_vk.GetFenceStatus = f_status; g_vk.DestroyImage = f_destroy_image; g_vk.FreeMemory = f_free_memory;
    g_vk.AcquireNextImageKHR = f_acquire; g_vk.CreateAndroidSurfaceKHR = f_surface;
    g_vk.DestroySurfaceKHR = f_destroy_surface; g_vk.DeviceWaitIdle = f_idle;
    g_vk.DestroySemaphore = f_destroy_sem; g_vk.CreateSemaphore = f_create_sem;
    g_dev = (VkDevice)(uintptr_t)1;
    g_queue = (VkQueue)(uintptr_t)2;
    g_fence = (VkFence)(uintptr_t)3;
    g_cmds[0] = (VkCommandBuffer)(uintptr_t)4;
    g_dev_state = 1;
}

/* A layer copy that gives up after its second leaves the fence and slot 0 to the GPU: the next copy is
 * skipped without touching them, an image destroyed meanwhile waits, and both resume once it is done. */
static void timeout_then_skip(void) {
    device_up();
    struct vkp_image *src = image(100, 0), *dst = image(200, 1), *src2 = image(300, 0);
    gpu_slow = 1;
    assert(vkp_blit_image(src, dst, -1) == -1);
    assert(g_perf.gpu_wait_timeouts == 1 && fence_pending);
    assert(vkp_gpu_ready() == 0);
    assert(vkp_blit_image(src2, dst, -1) == -1);           /* skipped: no reset, no submit */
    assert(g_perf.gpu_busy_skips >= 1 && submits == 1);
    vkp_image_destroy(src);                                /* still read by the running copy */
    assert(destroyed == 0 && g_perf.deferred_destroys == 1);
    finish_gpu();
    gpu_slow = 0;
    assert(vkp_gpu_ready() == 1);
    assert(destroyed == 1 && last_destroyed == (VkImage)(uintptr_t)100);
    assert(vkp_blit_image(src2, dst, -1) == 0 && submits == 2);
}

/* The frame path asks the same fence first: no swapchain is touched while the copy still runs. */
static void frame_waits_for_the_copy(void) {
    device_up();
    struct vkp_image *src = image(100, 0), *dst = image(200, 1);
    g_window = (ANativeWindow *)(uintptr_t)0x10;
    gpu_slow = 1;
    assert(vkp_blit_image(src, dst, -1) == -1);
    assert(vkp_render(64, 64, NULL, 0) == -1);
    assert(acquires == 0 && surfaces == 0);
    finish_gpu();
    gpu_slow = 0;
    assert(vkp_render(64, 64, NULL, 0) == -1);            /* goes on to the surface (the fake refuses it) */
    assert(surfaces == 1);
}

/* A device lost while the copy runs: nothing waits for it any more, deferred images go at once. */
static void lost_while_busy(void) {
    device_up();
    struct vkp_image *src = image(100, 0), *dst = image(200, 1);
    gpu_slow = 1;
    assert(vkp_blit_image(src, dst, -1) == -1);
    vkp_image_destroy(src);
    assert(destroyed == 0);
    gpu_lost = 1;
    assert(vkp_gpu_ready() == 0);
    assert(vkp_device_lost() && destroyed == 1);
    vkp_image_destroy(dst);                                /* a lost device frees at once */
    assert(destroyed == 2);
}

/* Work that finishes in time changes nothing: every copy submits, nothing is skipped or deferred. */
static void in_time(void) {
    device_up();
    struct vkp_image *src = image(100, 0), *dst = image(200, 1);
    for (int i = 0; i < 5; i++) assert(vkp_blit_image(src, dst, -1) == 0);
    vkp_image_destroy(src);
    assert(submits == 5 && resets == 5 && destroyed == 1);
    assert(g_perf.gpu_busy_skips == 0 && g_perf.deferred_destroys == 0 && g_perf.gpu_wait_timeouts == 0);
}

int main(int argc, char **argv) {
    assert(argc == 2);
    if (!strcmp(argv[1], "timeout")) timeout_then_skip();
    else if (!strcmp(argv[1], "frame")) frame_waits_for_the_copy();
    else if (!strcmp(argv[1], "lost")) lost_while_busy();
    else if (!strcmp(argv[1], "intime")) in_time();
    else return 2;
    puts("PASS");
    return 0;
}
