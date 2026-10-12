import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
WAYLAND = REPO / 'app/src/main/cpp/waylandcomp'

# Just enough of the NDK's headers for vk_present.c to compile on the host; the harness never calls them.
ANDROID_STUBS = {
    'android/log.h': 'int __android_log_print(int prio, const char *tag, const char *fmt, ...);\n'
                     'enum { ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };\n',
    'android/native_window.h': '#include <stdint.h>\ntypedef struct ANativeWindow ANativeWindow;\n'
                               'int32_t ANativeWindow_getWidth(ANativeWindow *w);\n'
                               'int32_t ANativeWindow_getHeight(ANativeWindow *w);\n'
                               'void ANativeWindow_release(ANativeWindow *w);\n',
    'android/hardware_buffer.h': 'typedef struct AHardwareBuffer AHardwareBuffer;\n',
}


class VkFenceTest(unittest.TestCase):
    """The compositor's shared fence and command buffer after a bounded wait gives up (vk_present.c)."""

    @classmethod
    def setUpClass(cls):
        compiler = shutil.which(os.environ.get('CC', 'cc'))
        if compiler is None:
            raise unittest.SkipTest('A host C compiler is required for the fence test')
        candidates = [Path('/usr/include/vulkan'), Path('/opt/homebrew/include/vulkan')]
        for name in ('ANDROID_NDK_HOME', 'ANDROID_NDK_ROOT', 'NDK_HOME'):
            if os.environ.get(name):
                candidates.extend(Path(os.environ[name]).glob('toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan'))
        for sdk in {Path(os.environ.get('ANDROID_HOME', '/nonexistent')),
                    Path(os.environ.get('ANDROID_SDK_ROOT', '/nonexistent')),
                    Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk'}:
            candidates.extend(sdk.glob('ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/vulkan'))
        headers = next((p for p in candidates if (p / 'vulkan.h').is_file()), None)
        if headers is None:
            raise unittest.SkipTest('Vulkan headers from the Android NDK or system SDK are required')
        cls.temp = tempfile.TemporaryDirectory(prefix='droiddeck-vk-fence-')
        cls.addClassCleanup(cls.temp.cleanup)
        folder = Path(cls.temp.name)
        # Copy only Vulkan headers so Android's libc headers cannot shadow the host's libc.
        shutil.copytree(headers, folder / 'include/vulkan', ignore=shutil.ignore_patterns('*.hpp'))
        if (headers.parent / 'vk_video').is_dir():
            shutil.copytree(headers.parent / 'vk_video', folder / 'include/vk_video')
        for name, text in ANDROID_STUBS.items():
            (folder / 'include' / name).parent.mkdir(parents=True, exist_ok=True)
            (folder / 'include' / name).write_text('#pragma once\n' + text)
        cls.binary = folder / 'vk-fence-test'
        build = subprocess.run([compiler, '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
                                '-Wno-missing-field-initializers', '-Wno-unused-function',
                                '-I' + str(folder / 'include'), '-I' + str(WAYLAND / 'src'),
                                '-I' + str(WAYLAND / 'generated'), '-I' + str(WAYLAND / 'prebuilt/include'),
                                str(REPO / 'tools/tests/vk_fence_harness.c'), '-o', str(cls.binary)],
                               capture_output=True, text=True)
        if build.returncode:
            raise RuntimeError(build.stderr)

    def check(self, case):
        result = subprocess.run([str(self.binary), case], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('PASS', result.stdout.strip())

    def test_a_timed_out_copy_is_never_reset_under_the_gpu(self):
        self.check('timeout')

    def test_the_frame_waits_for_a_copy_still_running(self):
        self.check('frame')

    def test_a_device_lost_meanwhile_frees_what_waited(self):
        self.check('lost')

    def test_work_that_finishes_in_time_is_unchanged(self):
        self.check('intime')


if __name__ == '__main__':
    unittest.main()
