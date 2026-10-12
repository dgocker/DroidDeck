// Differential test for x86-64 translation (FEX): one static x86-64 program that prints the
// results of integer, flag, SIMD, x87, atomic, self-modifying-code, signal and syscall cases as
// text. Run it natively on an x86-64 machine (the reference) and under the translator; the two
// outputs must be equal line for line. tools/fexdiff/run.py builds, runs and compares.
//
// Only architecturally defined results are printed: for each instruction the flags the Intel SDM
// leaves undefined are masked out, and results that are implementation-defined (rcpps/rsqrtps
// approximations, x87 transcendentals, timestamps) are checked against a tolerance and printed as
// ok/bad instead of bits. Lines starting with '#' are information (CPUID, timings) and are not
// compared.
//
//   gcc -O1 -static -pthread -o diffx86 diffx86.c -lm
#define _GNU_SOURCE
#include <cpuid.h>
#include <errno.h>
#include <fenv.h>
#include <float.h>
#include <immintrin.h>
#include <inttypes.h>
#include <math.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#include <linux/futex.h>

#define CF 0x001u
#define PF 0x004u
#define AF 0x010u
#define ZF 0x040u
#define SF 0x080u
#define OF 0x800u
#define ALL6 (CF | PF | AF | ZF | SF | OF)

static const char *only; // run one section (argv[1])
static int section(const char *name) { return !only || strcmp(only, name) == 0; }

static int has_bmi1, has_bmi2, has_adx, has_lzcnt, has_popcnt, has_ssse3, has_sse41, has_sse42, has_avx, has_avx2, has_fma, has_f16c;

static void detect(void) {
  unsigned a, b, c, d;
  char vendor[13] = {0};
  __cpuid(0, a, b, c, d);
  memcpy(vendor, &b, 4); memcpy(vendor + 4, &d, 4); memcpy(vendor + 8, &c, 4);
  unsigned max = a;
  __cpuid(1, a, b, c, d);
  has_ssse3 = !!(c & bit_SSSE3); has_sse41 = !!(c & bit_SSE4_1); has_sse42 = !!(c & bit_SSE4_2);
  has_popcnt = !!(c & bit_POPCNT); has_fma = !!(c & bit_FMA); has_f16c = !!(c & bit_F16C);
  int osxsave = !!(c & bit_OSXSAVE);
  has_avx = !!(c & bit_AVX) && osxsave;
  printf("# cpuid vendor=%s leaf1 ecx=%08x edx=%08x\n", vendor, c, d);
  if (max >= 7) {
    __cpuid_count(7, 0, a, b, c, d);
    has_bmi1 = !!(b & bit_BMI); has_bmi2 = !!(b & bit_BMI2); has_adx = !!(b & bit_ADX); has_avx2 = !!(b & bit_AVX2) && has_avx;
    printf("# cpuid leaf7 ebx=%08x ecx=%08x edx=%08x\n", b, c, d);
  }
  __cpuid(0x80000001, a, b, c, d);
  has_lzcnt = !!(c & bit_LZCNT);
  if (has_avx) {
    unsigned lo, hi;
    __asm__ volatile("xgetbv" : "=a"(lo), "=d"(hi) : "c"(0));
    if ((lo & 6) != 6) has_avx = has_avx2 = has_fma = 0;
  }
  printf("# features bmi1=%d bmi2=%d adx=%d lzcnt=%d popcnt=%d ssse3=%d sse41=%d sse42=%d avx=%d avx2=%d fma=%d f16c=%d\n",
         has_bmi1, has_bmi2, has_adx, has_lzcnt, has_popcnt, has_ssse3, has_sse41, has_sse42, has_avx, has_avx2, has_fma, has_f16c);
}

// ---------------------------------------------------------------- integer and flags

static const uint64_t VALS[] = {
  0, 1, 2, 0x7f, 0x80, 0xff, 0x7fff, 0x8000, 0xffff, 0x7fffffffu, 0x80000000u, 0xffffffffu,
  0x7fffffffffffffffull, 0x8000000000000000ull, 0xffffffffffffffffull, 0x123456789abcdef0ull, 0x0f0f0f0f0f0f0f0full,
};
#define NV (sizeof(VALS) / sizeof(VALS[0]))

// in: flags to start with (CF matters for adc/sbb/rcl/rcr and for inc/dec preserving it).
#define BINOP(name, insn, sfx, mod)                                                              \
  static uint64_t name(uint64_t a, uint64_t b, uint64_t in, uint64_t *fl) {                      \
    uint64_t f = in;                                                                             \
    __asm__ volatile("pushq %[f]\n\tpopfq\n\t" insn " %" mod "[b], %" mod "[a]\n\tpushfq\n\tpopq %[f]" \
                     : [a] "+r"(a), [f] "+r"(f) : [b] "r"(b) : "cc");                            \
    *fl = f;                                                                                     \
    return a;                                                                                    \
  }
#define BINOPS(op) BINOP(op##8, #op "b", "b", "b") BINOP(op##16, #op "w", "w", "w") BINOP(op##32, #op "l", "l", "k") BINOP(op##64, #op "q", "q", "q")
BINOPS(add) BINOPS(adc) BINOPS(sub) BINOPS(sbb) BINOPS(and) BINOPS(or) BINOPS(xor) BINOPS(cmp)

#define UNOP(name, insn, mod)                                                                    \
  static uint64_t name(uint64_t a, uint64_t in, uint64_t *fl) {                                  \
    uint64_t f = in;                                                                             \
    __asm__ volatile("pushq %[f]\n\tpopfq\n\t" insn " %" mod "[a]\n\tpushfq\n\tpopq %[f]"        \
                     : [a] "+r"(a), [f] "+r"(f) : : "cc");                                       \
    *fl = f;                                                                                     \
    return a;                                                                                    \
  }
#define UNOPS(op) UNOP(op##8, #op "b", "b") UNOP(op##16, #op "w", "w") UNOP(op##32, #op "l", "k") UNOP(op##64, #op "q", "q")
UNOPS(inc) UNOPS(dec) UNOPS(neg) UNOPS(not)

// Shifts and rotates by CL.
#define SHOP(name, insn, mod)                                                                    \
  static uint64_t name(uint64_t a, uint64_t c, uint64_t in, uint64_t *fl) {                      \
    uint64_t f = in;                                                                             \
    __asm__ volatile("pushq %[f]\n\tpopfq\n\t" insn " %%cl, %" mod "[a]\n\tpushfq\n\tpopq %[f]"  \
                     : [a] "+r"(a), [f] "+r"(f) : "c"(c) : "cc");                                \
    *fl = f;                                                                                     \
    return a;                                                                                    \
  }
#define SHOPS(op) SHOP(op##8, #op "b", "b") SHOP(op##16, #op "w", "w") SHOP(op##32, #op "l", "k") SHOP(op##64, #op "q", "q")
SHOPS(shl) SHOPS(shr) SHOPS(sar) SHOPS(rol) SHOPS(ror) SHOPS(rcl) SHOPS(rcr)

#define DSHOP(name, insn, mod)                                                                   \
  static uint64_t name(uint64_t a, uint64_t b, uint64_t c, uint64_t in, uint64_t *fl) {          \
    uint64_t f = in;                                                                             \
    __asm__ volatile("pushq %[f]\n\tpopfq\n\t" insn " %%cl, %" mod "[b], %" mod "[a]\n\tpushfq\n\tpopq %[f]" \
                     : [a] "+r"(a), [f] "+r"(f) : [b] "r"(b), "c"(c) : "cc");                    \
    *fl = f;                                                                                     \
    return a;                                                                                    \
  }
DSHOP(shld16, "shldw", "w") DSHOP(shld32, "shldl", "k") DSHOP(shld64, "shldq", "q")
DSHOP(shrd16, "shrdw", "w") DSHOP(shrd32, "shrdl", "k") DSHOP(shrd64, "shrdq", "q")

static uint64_t mask_bits(int bits) { return bits == 64 ? ~0ull : ((1ull << bits) - 1); }

static void print_flags_line(const char *op, int bits, uint64_t a, uint64_t b, uint64_t r, uint64_t fl, unsigned defined) {
  printf("int.%s%d %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", op, bits, a & mask_bits(bits), b & mask_bits(bits),
         r & mask_bits(bits), (unsigned)(fl & defined));
}

static void test_alu(void) {
  typedef uint64_t (*bin_t)(uint64_t, uint64_t, uint64_t, uint64_t *);
  struct { const char *name; bin_t f[4]; unsigned defined; } ops[] = {
    {"add", {add8, add16, add32, add64}, ALL6}, {"adc", {adc8, adc16, adc32, adc64}, ALL6},
    {"sub", {sub8, sub16, sub32, sub64}, ALL6}, {"sbb", {sbb8, sbb16, sbb32, sbb64}, ALL6},
    {"cmp", {cmp8, cmp16, cmp32, cmp64}, ALL6},
    // Logic: AF undefined; OF and CF cleared.
    {"and", {and8, and16, and32, and64}, ALL6 & ~AF}, {"or", {or8, or16, or32, or64}, ALL6 & ~AF},
    {"xor", {xor8, xor16, xor32, xor64}, ALL6 & ~AF},
  };
  const int widths[4] = {8, 16, 32, 64};
  for (size_t o = 0; o < sizeof(ops) / sizeof(ops[0]); o++)
    for (int w = 0; w < 4; w++)
      for (size_t i = 0; i < NV; i++)
        for (size_t j = 0; j < NV; j++)
          for (uint64_t cin = 0; cin < 2; cin++) {
            if (cin && strcmp(ops[o].name, "adc") && strcmp(ops[o].name, "sbb")) continue;
            uint64_t fl;
            uint64_t r = ops[o].f[w](VALS[i], VALS[j], 0x202 | cin, &fl);
            char name[16];
            snprintf(name, sizeof(name), "%s%s", ops[o].name, cin ? "c" : "");
            print_flags_line(name, widths[w], VALS[i], VALS[j], r, fl, ops[o].defined);
          }
  typedef uint64_t (*un_t)(uint64_t, uint64_t, uint64_t *);
  struct { const char *name; un_t f[4]; unsigned defined; } uops[] = {
    // inc/dec leave CF as it was: start with CF set and cleared, all flags compared.
    {"inc", {inc8, inc16, inc32, inc64}, ALL6}, {"dec", {dec8, dec16, dec32, dec64}, ALL6},
    {"neg", {neg8, neg16, neg32, neg64}, ALL6}, {"not", {not8, not16, not32, not64}, ALL6},
  };
  for (size_t o = 0; o < sizeof(uops) / sizeof(uops[0]); o++)
    for (int w = 0; w < 4; w++)
      for (size_t i = 0; i < NV; i++)
        for (uint64_t in = 0x202; in <= 0x203 + 0x8d4; in += 0x8d5) { // all six clear / all six set
          uint64_t fl;
          uint64_t r = uops[o].f[w](VALS[i], in, &fl);
          print_flags_line(uops[o].name, widths[w], VALS[i], in, r, fl, uops[o].defined);
        }
}

static void test_shifts(void) {
  typedef uint64_t (*sh_t)(uint64_t, uint64_t, uint64_t, uint64_t *);
  struct { const char *name; sh_t f[4]; int rotate, through; } ops[] = {
    {"shl", {shl8, shl16, shl32, shl64}, 0, 0}, {"shr", {shr8, shr16, shr32, shr64}, 0, 0},
    {"sar", {sar8, sar16, sar32, sar64}, 0, 0}, {"rol", {rol8, rol16, rol32, rol64}, 1, 0},
    {"ror", {ror8, ror16, ror32, ror64}, 1, 0}, {"rcl", {rcl8, rcl16, rcl32, rcl64}, 1, 1},
    {"rcr", {rcr8, rcr16, rcr32, rcr64}, 1, 1},
  };
  const int widths[4] = {8, 16, 32, 64};
  const uint64_t counts[] = {0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 255};
  for (size_t o = 0; o < sizeof(ops) / sizeof(ops[0]); o++)
    for (int w = 0; w < 4; w++)
      for (size_t i = 0; i < NV; i++)
        for (size_t k = 0; k < sizeof(counts) / sizeof(counts[0]); k++)
          for (uint64_t in = 0x202; in <= 0x203 + 0x8d4; in += 0x8d5) {
            int bits = widths[w];
            unsigned masked = (unsigned)(counts[k] & (bits == 64 ? 63 : 31));
            uint64_t fl;
            uint64_t r = ops[o].f[w](VALS[i], counts[k], in, &fl);
            unsigned defined;
            if (masked == 0) {
              defined = ALL6; // nothing changes, flags included
            } else if (!ops[o].rotate) {
              // CF: the last bit out, undefined once the count reaches the operand size (8/16 bit).
              defined = SF | ZF | PF | ((int)masked < bits || bits >= 32 ? CF : 0) | (masked == 1 ? OF : 0);
              if ((int)masked >= bits) defined &= ~(SF | ZF | PF) | (bits >= 32 ? (SF | ZF | PF) : 0); // result defined (zero/sign fill)
            } else if (!ops[o].through) {
              defined = CF | (masked == 1 ? OF : 0) | SF | ZF | AF | PF; // rotates leave SF ZF AF PF alone
            } else {
              // RCL/RCR rotate through CF modulo size+1 for 8/16 bits.
              defined = CF | (masked == 1 ? OF : 0) | SF | ZF | AF | PF;
            }
            print_flags_line(ops[o].name, bits, VALS[i], counts[k] | (in << 8), r, fl, defined);
          }
  typedef uint64_t (*dsh_t)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t *);
  struct { const char *name; dsh_t f[3]; } dops[] = {{"shld", {shld16, shld32, shld64}}, {"shrd", {shrd16, shrd32, shrd64}}};
  const int dw[3] = {16, 32, 64};
  for (size_t o = 0; o < 2; o++)
    for (int w = 0; w < 3; w++)
      for (size_t i = 0; i < NV; i++)
        for (size_t j = 0; j < NV; j += 3)
          for (size_t k = 0; k < sizeof(counts) / sizeof(counts[0]); k++) {
            int bits = dw[w];
            unsigned masked = (unsigned)(counts[k] & (bits == 64 ? 63 : 31));
            // 16-bit with a count over 16: result and flags undefined.
            if (bits == 16 && masked > 16) continue;
            uint64_t fl;
            uint64_t r = dops[o].f[w](VALS[i], VALS[j], counts[k], 0x202, &fl);
            unsigned defined = masked == 0 ? ALL6 : (CF | SF | ZF | PF | (masked == 1 ? OF : 0));
            if (bits == 16 && masked == 16) defined &= ~CF; // CF undefined when the count equals the size
            printf("int.%s%d %016" PRIx64 " %016" PRIx64 " c=%u -> %016" PRIx64 " fl=%03x\n", dops[o].name, bits,
                   VALS[i] & mask_bits(bits), VALS[j] & mask_bits(bits), masked, r & mask_bits(bits), (unsigned)(fl & defined));
          }
}

static void test_muldiv(void) {
  for (size_t i = 0; i < NV; i++)
    for (size_t j = 0; j < NV; j++) {
      uint64_t a = VALS[i], b = VALS[j], lo, hi, f;
      __asm__ volatile("mulq %[b]\n\tpushfq\n\tpopq %[f]" : "=a"(lo), "=d"(hi), [f] "=r"(f) : "a"(a), [b] "r"(b) : "cc");
      printf("int.mul64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 ":%016" PRIx64 " fl=%03x\n", a, b, hi, lo, (unsigned)(f & (CF | OF)));
      __asm__ volatile("imulq %[b]\n\tpushfq\n\tpopq %[f]" : "=a"(lo), "=d"(hi), [f] "=r"(f) : "a"(a), [b] "r"(b) : "cc");
      printf("int.imul64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 ":%016" PRIx64 " fl=%03x\n", a, b, hi, lo, (unsigned)(f & (CF | OF)));
      uint32_t a32 = (uint32_t)a, r32;
      __asm__ volatile("imull %[b], %[a]\n\tpushfq\n\tpopq %[f]" : [a] "=r"(r32), [f] "=r"(f) : "0"(a32), [b] "r"((uint32_t)b) : "cc");
      printf("int.imul32_2op %08x %08x -> %08x fl=%03x\n", a32, (uint32_t)b, r32, (unsigned)(f & (CF | OF)));
      uint16_t a16 = (uint16_t)a, r16;
      __asm__ volatile("imulw %[b], %[a]\n\tpushfq\n\tpopq %[f]" : [a] "=r"(r16), [f] "=r"(f) : "0"(a16), [b] "r"((uint16_t)b) : "cc");
      printf("int.imul16_2op %04x %04x -> %04x fl=%03x\n", a16, (uint16_t)b, r16, (unsigned)(f & (CF | OF)));
      uint8_t a8 = (uint8_t)a; uint16_t ax;
      __asm__ volatile("mulb %[b]\n\tpushfq\n\tpopq %[f]" : "=a"(ax), [f] "=r"(f) : "a"(a8), [b] "q"((uint8_t)b) : "cc");
      printf("int.mul8 %02x %02x -> %04x fl=%03x\n", a8, (uint8_t)b, ax, (unsigned)(f & (CF | OF)));
      if (b != 0) {
        uint64_t q, rem;
        __asm__ volatile("divq %[b]" : "=a"(q), "=d"(rem) : "a"(a), "d"(0ull), [b] "r"(b));
        printf("int.div64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 " r %016" PRIx64 "\n", a, b, q, rem);
        int64_t sa = (int64_t)a, sb = (int64_t)b;
        if (!(sa == INT64_MIN && sb == -1)) {
          int64_t sq, sr;
          __asm__ volatile("cqto\n\tidivq %[b]" : "=a"(sq), "=&d"(sr) : "a"(sa), [b] "r"(sb));
          printf("int.idiv64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 " r %016" PRIx64 "\n", a, b, (uint64_t)sq, (uint64_t)sr);
        }
        uint32_t q32, r32b, d32 = (uint32_t)b;
        if (d32) {
          __asm__ volatile("divl %[b]" : "=a"(q32), "=d"(r32b) : "a"((uint32_t)a), "d"(0u), [b] "r"(d32));
          printf("int.div32 %08x %08x -> %08x r %08x\n", (uint32_t)a, d32, q32, r32b);
        }
      }
    }
}

static void test_bitops(void) {
  for (size_t i = 0; i < NV; i++) {
    uint64_t a = VALS[i], r, f;
    if (a) {
      __asm__ volatile("bsfq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.bsf64 %016" PRIx64 " -> %" PRIu64 " zf=%d\n", a, r, !!(f & ZF));
      __asm__ volatile("bsrq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.bsr64 %016" PRIx64 " -> %" PRIu64 " zf=%d\n", a, r, !!(f & ZF));
    } else {
      __asm__ volatile("bsfq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a), "0"(0x55ull) : "cc");
      printf("bit.bsf64 0 zf=%d\n", !!(f & ZF));
    }
    for (uint64_t bit = 0; bit < 64; bit += 13) {
      uint64_t x = a;
      __asm__ volatile("btq %[b], %[x]\n\tpushfq\n\tpopq %[f]" : [f] "=r"(f) : [x] "r"(x), [b] "r"(bit) : "cc");
      printf("bit.bt64 %016" PRIx64 " %" PRIu64 " cf=%d\n", a, bit, !!(f & CF));
      __asm__ volatile("btsq %[b], %[x]\n\tpushfq\n\tpopq %[f]" : [x] "+r"(x), [f] "=r"(f) : [b] "r"(bit) : "cc");
      printf("bit.bts64 %016" PRIx64 " %" PRIu64 " -> %016" PRIx64 " cf=%d\n", a, bit, x, !!(f & CF));
      __asm__ volatile("btcq %[b], %[x]\n\tpushfq\n\tpopq %[f]" : [x] "+r"(x), [f] "=r"(f) : [b] "r"(bit + 1) : "cc");
      printf("bit.btc64 -> %016" PRIx64 " cf=%d\n", x, !!(f & CF));
      __asm__ volatile("btrq %[b], %[x]\n\tpushfq\n\tpopq %[f]" : [x] "+r"(x), [f] "=r"(f) : [b] "r"(bit) : "cc");
      printf("bit.btr64 -> %016" PRIx64 " cf=%d\n", x, !!(f & CF));
    }
    if (has_popcnt) {
      __asm__ volatile("popcntq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.popcnt64 %016" PRIx64 " -> %" PRIu64 " fl=%03x\n", a, r, (unsigned)(f & ALL6));
    }
    if (has_lzcnt) {
      __asm__ volatile("lzcntq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.lzcnt64 %016" PRIx64 " -> %" PRIu64 " fl=%03x\n", a, r, (unsigned)(f & (CF | ZF)));
      uint32_t r32, f32;
      __asm__ volatile("lzcntl %[a], %[r]\n\tpushfq\n\tpopq %q[f]" : [r] "=r"(r32), [f] "=r"(f32) : [a] "r"((uint32_t)a) : "cc");
      printf("bit.lzcnt32 %08x -> %u fl=%03x\n", (uint32_t)a, r32, f32 & (CF | ZF));
    }
    if (has_bmi1) {
      __asm__ volatile("tzcntq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.tzcnt64 %016" PRIx64 " -> %" PRIu64 " fl=%03x\n", a, r, (unsigned)(f & (CF | ZF)));
      __asm__ volatile("blsrq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.blsr64 %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, r, (unsigned)(f & (CF | ZF | SF | OF)));
      __asm__ volatile("blsmskq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.blsmsk64 %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, r, (unsigned)(f & (CF | ZF | SF | OF)));
      __asm__ volatile("blsiq %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a) : "cc");
      printf("bit.blsi64 %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, r, (unsigned)(f & (CF | ZF | SF | OF)));
      uint32_t r32, f32;
      __asm__ volatile("blsrl %[a], %[r]\n\tpushfq\n\tpopq %q[f]" : [r] "=r"(r32), [f] "=r"(f32) : [a] "r"((uint32_t)a) : "cc");
      printf("bit.blsr32 %08x -> %08x fl=%03x\n", (uint32_t)a, r32, f32 & (CF | ZF | SF | OF));
      __asm__ volatile("blsmskl %[a], %[r]\n\tpushfq\n\tpopq %q[f]" : [r] "=r"(r32), [f] "=r"(f32) : [a] "r"((uint32_t)a) : "cc");
      printf("bit.blsmsk32 %08x -> %08x fl=%03x\n", (uint32_t)a, r32, f32 & (CF | ZF | SF | OF));
      for (size_t j = 0; j < NV; j += 4) {
        uint64_t b = VALS[j];
        __asm__ volatile("andnq %[b], %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a), [b] "r"(b) : "cc");
        printf("bit.andn64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, b, r, (unsigned)(f & (CF | ZF | SF | OF)));
        uint64_t ctl = (b & 0xff) | ((b >> 8 & 0xff) << 8);
        __asm__ volatile("bextrq %[c], %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a), [c] "r"(ctl) : "cc");
        printf("bit.bextr64 %016" PRIx64 " %04" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, ctl, r, (unsigned)(f & (CF | ZF | OF)));
      }
    }
    if (has_bmi2) {
      for (size_t j = 0; j < NV; j += 2) {
        uint64_t b = VALS[j], fin = 0x202 | ALL6;
        f = fin;
        __asm__ volatile("pushq %[f]\n\tpopfq\n\tpdepq %[b], %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=&r"(r), [f] "+r"(f) : [a] "r"(a), [b] "r"(b) : "cc");
        printf("bit.pdep64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, b, r, (unsigned)(f & ALL6));
        f = fin;
        __asm__ volatile("pushq %[f]\n\tpopfq\n\tpextq %[b], %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=&r"(r), [f] "+r"(f) : [a] "r"(a), [b] "r"(b) : "cc");
        printf("bit.pext64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, b, r, (unsigned)(f & ALL6));
        uint64_t n = b & 0xff;
        __asm__ volatile("bzhiq %[n], %[a], %[r]\n\tpushfq\n\tpopq %[f]" : [r] "=r"(r), [f] "=r"(f) : [a] "r"(a), [n] "r"(n) : "cc");
        printf("bit.bzhi64 %016" PRIx64 " %02" PRIx64 " -> %016" PRIx64 " fl=%03x\n", a, n, r, (unsigned)(f & (CF | ZF | SF | OF)));
        uint64_t s = b & 0xff;
        __asm__ volatile("shlxq %[s], %[a], %[r]" : [r] "=r"(r) : [a] "r"(a), [s] "r"(s));
        printf("bit.shlx64 %016" PRIx64 " %02" PRIx64 " -> %016" PRIx64 "\n", a, s, r);
        __asm__ volatile("sarxq %[s], %[a], %[r]" : [r] "=r"(r) : [a] "r"(a), [s] "r"(s));
        printf("bit.sarx64 %016" PRIx64 " %02" PRIx64 " -> %016" PRIx64 "\n", a, s, r);
        uint32_t r32;
        __asm__ volatile("rorxl $13, %[a], %[r]" : [r] "=r"(r32) : [a] "r"((uint32_t)a));
        printf("bit.rorx32 %08x -> %08x\n", (uint32_t)a, r32);
        uint64_t hi, lo;
        __asm__ volatile("mulxq %[b], %[lo], %[hi]" : [lo] "=r"(lo), [hi] "=r"(hi) : "d"(a), [b] "r"(b));
        printf("bit.mulx64 %016" PRIx64 " %016" PRIx64 " -> %016" PRIx64 ":%016" PRIx64 "\n", a, b, hi, lo);
      }
    }
    if (has_adx) {
      for (size_t j = 0; j < NV; j += 3)
        for (uint64_t cin = 0; cin < 2; cin++) {
          uint64_t b = VALS[j], x = a;
          f = 0x202 | (cin ? (CF | OF) : 0);
          __asm__ volatile("pushq %[f]\n\tpopfq\n\tadcxq %[b], %[x]\n\tpushfq\n\tpopq %[f]" : [x] "+r"(x), [f] "+r"(f) : [b] "r"(b) : "cc");
          printf("bit.adcx64 %016" PRIx64 " %016" PRIx64 " c=%" PRIu64 " -> %016" PRIx64 " fl=%03x\n", a, b, cin, x, (unsigned)(f & ALL6));
          x = a;
          f = 0x202 | (cin ? (CF | OF) : 0);
          __asm__ volatile("pushq %[f]\n\tpopfq\n\tadoxq %[b], %[x]\n\tpushfq\n\tpopq %[f]" : [x] "+r"(x), [f] "+r"(f) : [b] "r"(b) : "cc");
          printf("bit.adox64 %016" PRIx64 " %016" PRIx64 " o=%" PRIu64 " -> %016" PRIx64 " fl=%03x\n", a, b, cin, x, (unsigned)(f & ALL6));
        }
    }
  }
  // Every condition code after compares of interesting pairs.
  for (size_t i = 0; i < NV; i += 2)
    for (size_t j = 0; j < NV; j += 2) {
      uint64_t a = VALS[i], b = VALS[j];
      uint32_t set = 0;
      uint8_t v;
#define CC(n, s) __asm__ volatile("cmpq %[b], %[a]\n\tset" s " %[v]" : [v] "=q"(v) : [a] "r"(a), [b] "r"(b) : "cc"); set |= (uint32_t)v << n;
      CC(0, "o") CC(1, "no") CC(2, "b") CC(3, "ae") CC(4, "e") CC(5, "ne") CC(6, "be") CC(7, "a")
      CC(8, "s") CC(9, "ns") CC(10, "p") CC(11, "np") CC(12, "l") CC(13, "ge") CC(14, "le") CC(15, "g")
#undef CC
      uint64_t cm = 0x1111, r = 0x2222;
      __asm__ volatile("cmpq %[b], %[a]\n\tcmovl %[cm], %[r]" : [r] "+r"(r) : [a] "r"(a), [b] "r"(b), [cm] "r"(cm) : "cc");
      printf("cc.cmp64 %016" PRIx64 " %016" PRIx64 " set=%04x cmovl=%" PRIx64 "\n", a, b, set, r);
    }
  // LAHF/SAHF round trip.
  for (unsigned ah = 0; ah < 256; ah += 37) {
    uint64_t rax = (uint64_t)ah << 8;
    __asm__ volatile("sahf\n\tmovq $0, %%rax\n\tlahf" : "+a"(rax) : : "cc");
    printf("cc.sahf_lahf %02x -> %02" PRIx64 "\n", ah, (rax >> 8) & 0xd5);
  }
}

// ---------------------------------------------------------------- SSE / AVX

static const uint32_t FBITS[] = {
  0x00000000, 0x80000000, 0x3f800000, 0xbf800000, 0x3fc00000, 0x40200000, 0xc0200000, 0x3f000000, 0xbf000000,
  0x00000001, 0x80000001, 0x007fffff, 0x00800000, 0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000,
  0x7fc00000, 0xffc00000, 0x7f800001, 0x7fa00000, 0x4f000000, 0xcf000000, 0x4effffff, 0xcf000001, 0x5f000000, 0x501502f9,
};
#define NF (sizeof(FBITS) / sizeof(FBITS[0]))
static const uint64_t DBITS[] = {
  0x0000000000000000ull, 0x8000000000000000ull, 0x3ff0000000000000ull, 0xbff0000000000000ull, 0x3ff8000000000000ull,
  0x4004000000000000ull, 0xc004000000000000ull, 0x0000000000000001ull, 0x000fffffffffffffull, 0x7fefffffffffffffull,
  0x7ff0000000000000ull, 0xfff0000000000000ull, 0x7ff8000000000000ull, 0xfff8000000000000ull, 0x7ff0000000000001ull,
  0x41dfffffffc00000ull, 0xc1e0000000000000ull, 0x41e0000000000000ull, 0xc1e0000000200000ull, 0x43e0000000000000ull,
  0xc3e0000000000000ull, 0x41dfffffffffffffull, 0x4341c37937e08000ull,
};
#define ND (sizeof(DBITS) / sizeof(DBITS[0]))

static float f_of(uint32_t b) { float f; memcpy(&f, &b, 4); return f; }
static double d_of(uint64_t b) { double d; memcpy(&d, &b, 8); return d; }
static uint32_t bits_f(float f) { uint32_t b; memcpy(&b, &f, 4); return b; }
static uint64_t bits_d(double d) { uint64_t b; memcpy(&b, &d, 8); return b; }

static void print128(const char *name, __m128i v) {
  uint32_t w[4];
  _mm_storeu_si128((__m128i *)w, v);
  printf("%s %08x %08x %08x %08x\n", name, w[3], w[2], w[1], w[0]);
}

static const unsigned ROUNDING[4] = {_MM_ROUND_NEAREST, _MM_ROUND_DOWN, _MM_ROUND_UP, _MM_ROUND_TOWARD_ZERO};

static void test_sse_fp(void) {
  unsigned saved = _mm_getcsr();
  for (int rm = 0; rm < 4; rm++) {
    _mm_setcsr((saved & ~(0x6000u | 0x8040u)) | ROUNDING[rm]);
    for (size_t i = 0; i < NF; i++)
      for (size_t j = 0; j < NF; j++) {
        __m128 a = _mm_set1_ps(f_of(FBITS[i])), b = _mm_set1_ps(f_of(FBITS[j]));
        _mm_setcsr((_mm_getcsr() & ~0x3fu));
        uint32_t add = bits_f(_mm_cvtss_f32(_mm_add_ss(a, b)));
        uint32_t sub = bits_f(_mm_cvtss_f32(_mm_sub_ss(a, b)));
        uint32_t mul = bits_f(_mm_cvtss_f32(_mm_mul_ss(a, b)));
        uint32_t dv = bits_f(_mm_cvtss_f32(_mm_div_ss(a, b)));
        uint32_t mn = bits_f(_mm_cvtss_f32(_mm_min_ss(a, b)));
        uint32_t mx = bits_f(_mm_cvtss_f32(_mm_max_ss(a, b)));
        unsigned exc = _mm_getcsr() & 0x3f;
        uint32_t cmp[8];
        for (int p = 0; p < 8; p++) {
          __m128 c;
          switch (p) {
            case 0: c = _mm_cmpeq_ss(a, b); break; case 1: c = _mm_cmplt_ss(a, b); break;
            case 2: c = _mm_cmple_ss(a, b); break; case 3: c = _mm_cmpunord_ss(a, b); break;
            case 4: c = _mm_cmpneq_ss(a, b); break; case 5: c = _mm_cmpnlt_ss(a, b); break;
            case 6: c = _mm_cmpnle_ss(a, b); break; default: c = _mm_cmpord_ss(a, b); break;
          }
          cmp[p] = bits_f(_mm_cvtss_f32(c)) & 1;
        }
        int ci = _mm_comilt_ss(a, b) | _mm_ucomieq_ss(a, b) << 1 | _mm_comigt_ss(a, b) << 2;
        printf("sse.ss rm=%d %08x %08x add=%08x sub=%08x mul=%08x div=%08x min=%08x max=%08x exc=%02x cmp=%d%d%d%d%d%d%d%d comi=%d\n",
               rm, FBITS[i], FBITS[j], add, sub, mul, dv, mn, mx, exc, cmp[0], cmp[1], cmp[2], cmp[3], cmp[4], cmp[5], cmp[6], cmp[7], ci);
      }
    for (size_t i = 0; i < NF; i++) {
      __m128 a = _mm_set1_ps(f_of(FBITS[i]));
      _mm_setcsr((_mm_getcsr() & ~0x3fu));
      uint32_t sq = bits_f(_mm_cvtss_f32(_mm_sqrt_ss(a)));
      int32_t cvt = _mm_cvtss_si32(a), cvtt = _mm_cvttss_si32(a);
      int64_t cvt64 = _mm_cvtss_si64(a), cvtt64 = _mm_cvttss_si64(a);
      __m128i dq = _mm_cvtps_epi32(a), tdq = _mm_cvttps_epi32(a);
      double dd = _mm_cvtsd_f64(_mm_cvtss_sd(_mm_setzero_pd(), a));
      unsigned exc = _mm_getcsr() & 0x3f;
      printf("sse.cvt rm=%d %08x sqrt=%08x si32=%08x tsi32=%08x si64=%016" PRIx64 " tsi64=%016" PRIx64 " dq=%08x tdq=%08x sd=%016" PRIx64 " exc=%02x\n",
             rm, FBITS[i], sq, (uint32_t)cvt, (uint32_t)cvtt, (uint64_t)cvt64, (uint64_t)cvtt64, (uint32_t)_mm_cvtsi128_si32(dq),
             (uint32_t)_mm_cvtsi128_si32(tdq), bits_d(dd), exc);
    }
    for (size_t i = 0; i < ND; i++) {
      __m128d a = _mm_set1_pd(d_of(DBITS[i]));
      _mm_setcsr((_mm_getcsr() & ~0x3fu));
      int32_t cvt = _mm_cvtsd_si32(a), cvtt = _mm_cvttsd_si32(a);
      int64_t cvt64 = _mm_cvtsd_si64(a), cvtt64 = _mm_cvttsd_si64(a);
      __m128i dq = _mm_cvtpd_epi32(a), tdq = _mm_cvttpd_epi32(a);
      uint32_t ss = bits_f(_mm_cvtss_f32(_mm_cvtpd_ps(a)));
      uint64_t sq = bits_d(_mm_cvtsd_f64(_mm_sqrt_sd(a, a)));
      unsigned exc = _mm_getcsr() & 0x3f;
      printf("sse.cvtd rm=%d %016" PRIx64 " si32=%08x tsi32=%08x si64=%016" PRIx64 " tsi64=%016" PRIx64 " pd2dq=%08x %08x tpd2dq=%08x %08x ps=%08x sqrt=%016" PRIx64 " exc=%02x\n",
             rm, DBITS[i], (uint32_t)cvt, (uint32_t)cvtt, (uint64_t)cvt64, (uint64_t)cvtt64, (uint32_t)_mm_cvtsi128_si32(_mm_shuffle_epi32(dq, 1)),
             (uint32_t)_mm_cvtsi128_si32(dq), (uint32_t)_mm_cvtsi128_si32(_mm_shuffle_epi32(tdq, 1)), (uint32_t)_mm_cvtsi128_si32(tdq), ss, sq, exc);
      for (size_t j = 0; j < ND; j += 2) {
        __m128d b = _mm_set1_pd(d_of(DBITS[j]));
        printf("sse.sd rm=%d %016" PRIx64 " %016" PRIx64 " add=%016" PRIx64 " mul=%016" PRIx64 " div=%016" PRIx64 " min=%016" PRIx64 " max=%016" PRIx64 "\n",
               rm, DBITS[i], DBITS[j], bits_d(_mm_cvtsd_f64(_mm_add_sd(a, b))), bits_d(_mm_cvtsd_f64(_mm_mul_sd(a, b))),
               bits_d(_mm_cvtsd_f64(_mm_div_sd(a, b))), bits_d(_mm_cvtsd_f64(_mm_min_sd(a, b))), bits_d(_mm_cvtsd_f64(_mm_max_sd(a, b))));
      }
    }
    // cvtsi2ss / cvtsi2sd: rounding of integers that are not representable.
    const int64_t ints[] = {0, 1, -1, 16777217, -16777217, 2147483647, INT64_MAX, INT64_MIN, 9007199254740993ll, -9007199254740993ll};
    for (size_t i = 0; i < sizeof(ints) / sizeof(ints[0]); i++)
      printf("sse.si2f rm=%d %016" PRIx64 " ss=%08x sd=%016" PRIx64 " ss32=%08x\n", rm, (uint64_t)ints[i],
             bits_f(_mm_cvtss_f32(_mm_cvtsi64_ss(_mm_setzero_ps(), ints[i]))), bits_d(_mm_cvtsd_f64(_mm_cvtsi64_sd(_mm_setzero_pd(), ints[i]))),
             bits_f(_mm_cvtss_f32(_mm_cvtsi32_ss(_mm_setzero_ps(), (int32_t)ints[i]))));
  }
  // FTZ and DAZ.
  for (unsigned mode = 0; mode < 4; mode++) {
    _mm_setcsr((saved & ~0x8040u) | (mode & 1 ? 0x8000u : 0) | (mode & 2 ? 0x0040u : 0));
    __m128 tiny = _mm_set1_ps(f_of(0x00400000)), small = _mm_set1_ps(f_of(0x00800000)), half = _mm_set1_ps(0.5f);
    printf("sse.ftzdaz mode=%u add=%08x mul=%08x cmp=%08x cvt=%08x\n", mode, bits_f(_mm_cvtss_f32(_mm_add_ss(tiny, tiny))),
           bits_f(_mm_cvtss_f32(_mm_mul_ss(small, half))), bits_f(_mm_cvtss_f32(_mm_cmpeq_ss(tiny, _mm_setzero_ps()))),
           bits_d(_mm_cvtsd_f64(_mm_cvtss_sd(_mm_setzero_pd(), tiny))) != 0);
  }
  _mm_setcsr(saved);
  // rcpps / rsqrtps: implementation-defined approximations; only the bound the SDM gives is checked.
  int bad = 0;
  for (float x = 1e-3f; x < 1e6f; x *= 1.37f) {
    float r = _mm_cvtss_f32(_mm_rcp_ss(_mm_set1_ps(x))), s = _mm_cvtss_f32(_mm_rsqrt_ss(_mm_set1_ps(x)));
    if (fabs(r * x - 1.0) > 1.5 * 0x1p-12 || fabs(s * sqrtf(x) - 1.0) > 1.5 * 0x1p-12) bad++;
  }
  printf("sse.rcp_rsqrt_within_sdm_bound %s\n", bad ? "bad" : "ok");
}

__attribute__((target("ssse3,sse4.1,sse4.2,pclmul"))) static void test_sse_int(void) {
  const uint8_t pat[32] = {0x00, 0x01, 0x7f, 0x80, 0x81, 0xff, 0xfe, 0x40, 0x3f, 0xc0, 0x55, 0xaa, 0x10, 0xef, 0x02, 0x8f,
                           0x80, 0x7f, 0x00, 0xff, 0x01, 0xfe, 0x33, 0xcc, 0x90, 0x6f, 0x11, 0xee, 0x22, 0xdd, 0x44, 0xbb};
  __m128i a = _mm_loadu_si128((const __m128i *)pat), b = _mm_loadu_si128((const __m128i *)(pat + 16));
  print128("simd.paddsb", _mm_adds_epi8(a, b)); print128("simd.paddusb", _mm_adds_epu8(a, b));
  print128("simd.psubsw", _mm_subs_epi16(a, b)); print128("simd.psubusw", _mm_subs_epu16(a, b));
  print128("simd.pmaddwd", _mm_madd_epi16(a, b)); print128("simd.pmulhw", _mm_mulhi_epi16(a, b));
  print128("simd.pmulhuw", _mm_mulhi_epu16(a, b)); print128("simd.pmuludq", _mm_mul_epu32(a, b));
  print128("simd.psadbw", _mm_sad_epu8(a, b)); print128("simd.pavgb", _mm_avg_epu8(a, b));
  print128("simd.packsswb", _mm_packs_epi16(a, b)); print128("simd.packuswb", _mm_packus_epi16(a, b));
  print128("simd.packssdw", _mm_packs_epi32(a, b));
  print128("simd.pcmpgtb", _mm_cmpgt_epi8(a, b)); print128("simd.punpcklbw", _mm_unpacklo_epi8(a, b));
  for (int s = 0; s < 70; s += 7) {
    __m128i cnt = _mm_cvtsi32_si128(s);
    print128("simd.psllq", _mm_sll_epi64(a, cnt)); print128("simd.psraw", _mm_sra_epi16(a, cnt)); print128("simd.psrld", _mm_srl_epi32(a, cnt));
  }
  printf("simd.pmovmskb %04x movmskps %x movmskpd %x\n", _mm_movemask_epi8(a), _mm_movemask_ps(_mm_castsi128_ps(a)), _mm_movemask_pd(_mm_castsi128_pd(b)));
  if (has_ssse3) {
    print128("simd.pshufb", _mm_shuffle_epi8(a, b)); print128("simd.pmaddubsw", _mm_maddubs_epi16(a, b));
    print128("simd.pmulhrsw", _mm_mulhrs_epi16(a, b)); print128("simd.phaddsw", _mm_hadds_epi16(a, b));
    print128("simd.psignb", _mm_sign_epi8(a, b)); print128("simd.pabsb", _mm_abs_epi8(a));
    print128("simd.palignr5", _mm_alignr_epi8(a, b, 5)); print128("simd.palignr19", _mm_alignr_epi8(a, b, 19));
  }
  if (has_sse41) {
    print128("simd.pmulld", _mm_mullo_epi32(a, b)); print128("simd.pminud", _mm_min_epu32(a, b)); print128("simd.pmaxsb", _mm_max_epi8(a, b));
    print128("simd.packusdw", _mm_packus_epi32(a, b)); print128("simd.pblendvb", _mm_blendv_epi8(a, b, a));
    print128("simd.pmovsxbw", _mm_cvtepi8_epi16(a)); print128("simd.phminposuw", _mm_minpos_epu16(b));
    print128("simd.mpsadbw5", _mm_mpsadbw_epu8(a, b, 5));
    print128("simd.pcmpeqq", _mm_cmpeq_epi64(a, a));
    printf("simd.ptest z=%d c=%d nzc=%d\n", _mm_testz_si128(a, b), _mm_testc_si128(a, b), _mm_testnzc_si128(a, b));
    unsigned saved = _mm_getcsr();
    for (int rm = 0; rm < 4; rm++) {
      _mm_setcsr((saved & ~0x6000u) | ROUNDING[rm]);
      for (size_t i = 0; i < NF; i++) {
        __m128 v = _mm_set1_ps(f_of(FBITS[i]));
        printf("simd.roundps rm=%d %08x n=%08x f=%08x c=%08x t=%08x cur=%08x\n", rm, FBITS[i],
               bits_f(_mm_cvtss_f32(_mm_round_ps(v, _MM_FROUND_TO_NEAREST_INT | _MM_FROUND_NO_EXC))),
               bits_f(_mm_cvtss_f32(_mm_round_ps(v, _MM_FROUND_TO_NEG_INF | _MM_FROUND_NO_EXC))),
               bits_f(_mm_cvtss_f32(_mm_round_ps(v, _MM_FROUND_TO_POS_INF | _MM_FROUND_NO_EXC))),
               bits_f(_mm_cvtss_f32(_mm_round_ps(v, _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC))),
               bits_f(_mm_cvtss_f32(_mm_round_ps(v, _MM_FROUND_CUR_DIRECTION))));
      }
    }
    _mm_setcsr(saved);
    __m128 x = _mm_set_ps(1.5f, -2.25f, 3.0e10f, 1e-30f), y = _mm_set_ps(0.75f, 4.5f, -2.0e-10f, 7.0f);
    printf("simd.dpps f1 %08x 71 %08x\n", bits_f(_mm_cvtss_f32(_mm_dp_ps(x, y, 0xf1))), bits_f(_mm_cvtss_f32(_mm_dp_ps(x, y, 0x71))));
    printf("simd.insertps %08x extractps %08x\n", bits_f(_mm_cvtss_f32(_mm_insert_ps(x, y, 0x9a))), (uint32_t)_mm_extract_ps(x, 2));
  }
  if (has_sse42) {
    const char *strs[] = {"hello world", "lo wo", "abcdefghijklmnop", "\0xyz", "a-z", "Hello, World! 123"};
    for (int i = 0; i < 6; i++)
      for (int j = 0; j < 6; j++) {
        __m128i s1 = _mm_loadu_si128((const __m128i *)(const void *)strs[i]), s2 = _mm_loadu_si128((const __m128i *)(const void *)strs[j]);
        char buf1[32] = {0}, buf2[32] = {0};
        memcpy(buf1, strs[i], strlen(strs[i])); memcpy(buf2, strs[j], strlen(strs[j]));
        s1 = _mm_loadu_si128((const __m128i *)buf1); s2 = _mm_loadu_si128((const __m128i *)buf2);
        int l1 = (int)strlen(strs[i]), l2 = (int)strlen(strs[j]);
#define PCM(mode) printf("simd.pcmpistri %d %d %02x -> %d c=%d z=%d s=%d o=%d estri=%d\n", i, j, mode, _mm_cmpistri(s1, s2, mode), \
        _mm_cmpistrc(s1, s2, mode), _mm_cmpistrz(s1, s2, mode), _mm_cmpistrs(s1, s2, mode), _mm_cmpistro(s1, s2, mode), \
        _mm_cmpestri(s1, l1 > 16 ? 16 : l1, s2, l2 > 16 ? 16 : l2, mode)); \
        print128("simd.pcmpistrm", _mm_cmpistrm(s1, s2, mode));
        PCM(0x00) PCM(0x04) PCM(0x08) PCM(0x0c) PCM(0x40) PCM(0x18) PCM(0x44) PCM(0x71)
#undef PCM
      }
    uint64_t c = 0;
    for (size_t i = 0; i < NV; i++) c = _mm_crc32_u64(c, VALS[i]);
    printf("simd.crc32 %016" PRIx64 " %08x %08x\n", c, _mm_crc32_u8(0xffffffff, 0x5a), _mm_crc32_u16(0x1234, 0xbeef));
    print128("simd.pclmul00", _mm_clmulepi64_si128(a, b, 0x00)); print128("simd.pclmul11", _mm_clmulepi64_si128(a, b, 0x11));
  }
}

__attribute__((target("avx2,fma,f16c"))) static void test_avx(void) {
  if (!has_avx2) { printf("avx.skipped\n"); return; }
  int32_t src[16];
  for (int i = 0; i < 16; i++) src[i] = (int32_t)(0x01010101u * (unsigned)i) ^ (i & 1 ? 0x80000000 : 0);
  __m256i a = _mm256_loadu_si256((const __m256i *)src), b = _mm256_loadu_si256((const __m256i *)(src + 8));
  int32_t out[8];
#define P256(name, v) do { _mm256_storeu_si256((__m256i *)out, v); printf("avx.%s", name); for (int k = 7; k >= 0; k--) printf(" %08x", (uint32_t)out[k]); printf("\n"); } while (0)
  P256("vpermd", _mm256_permutevar8x32_epi32(a, b)); P256("vpermq", _mm256_permute4x64_epi64(a, 0x1b));
  P256("vperm2i128", _mm256_permute2x128_si256(a, b, 0x21)); P256("vpshufb", _mm256_shuffle_epi8(a, b));
  P256("vpsllvd", _mm256_sllv_epi32(a, _mm256_set_epi32(0, 1, 31, 32, 33, 255, -1, 16)));
  P256("vpsravd", _mm256_srav_epi32(a, _mm256_set_epi32(0, 1, 31, 32, 33, 255, -1, 16)));
  P256("vpsrlvq", _mm256_srlv_epi64(a, _mm256_set_epi64x(0, 63, 64, 1000)));
  P256("vpmaddubsw", _mm256_maddubs_epi16(a, b)); P256("vpackusdw", _mm256_packus_epi32(a, b));
  P256("vpbroadcastd", _mm256_broadcastd_epi32(_mm256_castsi256_si128(b)));
  P256("vpblendd", _mm256_blend_epi32(a, b, 0xa5));
  int32_t table[64];
  for (int i = 0; i < 64; i++) table[i] = i * 0x01000193;
  __m256i idx = _mm256_set_epi32(63, 0, 7, 33, 2, 61, 17, 5);
  P256("vpgatherdd", _mm256_i32gather_epi32(table, idx, 4));
  P256("vpgatherdd_mask", _mm256_mask_i32gather_epi32(_mm256_set1_epi32(-1), table, idx, _mm256_set_epi32(-1, 0, -1, 0, 0, -1, -1, 0), 4));
  __m256 x = _mm256_set_ps(1.0f, 0x1.000002p0f, -3.5f, 1e30f, 1e-30f, 0.1f, 7.0f, -0.0f);
  __m256 y = _mm256_set_ps(0x1.fffffep-1f, 0x1.000002p0f, 2.0f, 1e10f, 1e-10f, 0.3f, -7.0f, 1.0f);
  __m256 z = _mm256_set_ps(-1.0f, -1.0f, 7.0f, -3e38f, 0.0f, -0.03f, 49.0f, 0.0f);
  P256("vfmadd231ps", _mm256_castps_si256(_mm256_fmadd_ps(x, y, z)));
  P256("vfnmsub231ps", _mm256_castps_si256(_mm256_fnmsub_ps(x, y, z)));
  __m256d xd = _mm256_set_pd(1.0 + 0x1p-52, 0.1, -1e300, 3.0), yd = _mm256_set_pd(1.0 - 0x1p-53, 10.0, 1e10, 1.0 / 3.0), zd = _mm256_set_pd(-1.0, -1.0, 0.0, -1.0);
  P256("vfmadd231pd", _mm256_castpd_si256(_mm256_fmadd_pd(xd, yd, zd)));
  __m128i h = _mm256_cvtps_ph(x, _MM_FROUND_TO_NEAREST_INT); print128("avx.vcvtps2ph_rn", h);
  print128("avx.vcvtps2ph_rz", _mm256_cvtps_ph(x, _MM_FROUND_TO_ZERO));
  P256("vcvtph2ps", _mm256_castps_si256(_mm256_cvtph_ps(_mm_set_epi16(0x7c00, 0xfc00, 0x7e00, 0x0001, 0x03ff, 0x3c00, (short)0xc000, 0x8000))));
  P256("vcvttps2dq", _mm256_cvttps_epi32(x)); P256("vcvtps2dq", _mm256_cvtps_epi32(y));
  __m256 n = _mm256_set_ps(NAN, -NAN, INFINITY, -0.0f, 0.0f, 1.0f, -1.0f, NAN);
  P256("vminps", _mm256_castps_si256(_mm256_min_ps(n, x))); P256("vmaxps", _mm256_castps_si256(_mm256_max_ps(x, n)));
  P256("vblendvps", _mm256_castps_si256(_mm256_blendv_ps(x, y, n)));
  P256("vcmpps_nge_uq", _mm256_castps_si256(_mm256_cmp_ps(x, n, _CMP_NGE_UQ)));
  printf("avx.vptest %d %d vtestps %d\n", _mm256_testz_si256(a, b), _mm256_testc_si256(a, b), _mm256_testz_ps(x, n));
  printf("avx.vmovmskps %x vpmovmskb %08x\n", _mm256_movemask_ps(n), (uint32_t)_mm256_movemask_epi8(a));
  // 128-bit operations on registers whose upper 128 bits hold other data: a translator that works on
  // whole host vector registers wider than 128 bits must not take those bits in. The legacy-SSE form
  // keeps the upper half, the VEX.128 form zeroes it, vcvtdq2pd ymm widens the low 128 bits only.
  int32_t lanes[3][8];
  __asm__ volatile(
      "vmovdqu (%3), %%ymm0\n\tvmovdqu 32(%3), %%ymm1\n\t"
      "pmaddubsw %%xmm1, %%xmm0\n\t"
      "vmovdqu %%ymm0, (%0)\n\t"
      "vmovdqu (%3), %%ymm2\n\tvmovdqu 32(%3), %%ymm3\n\t"
      "vpmaddubsw %%xmm3, %%xmm2, %%xmm2\n\t"
      "vmovdqu %%ymm2, (%1)\n\t"
      "vmovdqu 32(%3), %%ymm4\n\t"
      "vcvtdq2pd %%xmm4, %%ymm5\n\t"
      "vmovdqu %%ymm5, (%2)\n\t"
      : : "r"(lanes[0]), "r"(lanes[1]), "r"(lanes[2]), "r"(src)
      : "xmm0", "xmm1", "xmm2", "xmm3", "xmm4", "xmm5", "memory");
  for (int t = 0; t < 3; t++) {
    static const char *const names[] = {"pmaddubsw_keeps_upper", "vpmaddubsw_xmm", "vcvtdq2pd_ymm"};
    printf("avx.lanes.%s", names[t]);
    for (int k = 7; k >= 0; k--) printf(" %08x", (uint32_t)lanes[t][k]);
    printf("\n");
  }
#undef P256
}

// ---------------------------------------------------------------- x87

static void fld80(const uint8_t *p) { __asm__ volatile("fldt %0" : : "m"(*(const uint8_t(*)[10])p)); }

static unsigned fxam_of(const uint8_t *p, int empty) {
  uint16_t sw;
  if (empty) {
    __asm__ volatile("fninit\n\tfxam\n\tfnstsw %0" : "=a"(sw));
  } else {
    __asm__ volatile("fninit" ::: "memory");
    fld80(p);
    __asm__ volatile("fxam\n\tfnstsw %0\n\tfstp %%st(0)" : "=a"(sw));
  }
  // C3 C2 C1 C0 = bits 14 10 9 8
  return ((sw >> 14) & 1) << 3 | ((sw >> 10) & 1) << 2 | ((sw >> 9) & 1) << 1 | ((sw >> 8) & 1);
}

static void test_x87(void) {
  // 80-bit encodings: mantissa (8 bytes, little endian) then sign|exponent (2 bytes).
  struct { const char *name; uint8_t v[10]; } vals[] = {
    {"+0", {0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x00}},
    {"-0", {0, 0, 0, 0, 0, 0, 0, 0, 0x00, 0x80}},
    {"+1", {0, 0, 0, 0, 0, 0, 0, 0x80, 0xff, 0x3f}},
    {"-2.5", {0, 0, 0, 0, 0, 0, 0, 0xa0, 0x00, 0xc0}},
    {"+inf", {0, 0, 0, 0, 0, 0, 0, 0x80, 0xff, 0x7f}},
    {"-inf", {0, 0, 0, 0, 0, 0, 0, 0x80, 0xff, 0xff}},
    {"qnan", {0, 0, 0, 0, 0, 0, 0, 0xc0, 0xff, 0x7f}},
    {"-qnan", {0, 0, 0, 0, 0, 0, 0, 0xc0, 0xff, 0xff}},
    {"denormal", {1, 0, 0, 0, 0, 0, 0, 0x00, 0x00, 0x00}},
    {"-denormal", {0, 0, 0, 0, 0, 0, 0, 0x40, 0x00, 0x80}},
    {"max", {0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xfe, 0x7f}},
  };
  for (size_t i = 0; i < sizeof(vals) / sizeof(vals[0]); i++)
    printf("x87.fxam %s c3c2c1c0=%x\n", vals[i].name, fxam_of(vals[i].v, 0));
  printf("x87.fxam empty c3c2c1c0=%x\n", fxam_of(NULL, 1) & 0xd); // C1 undefined for empty

  // Precision: 1/3 in 80 bits, stored whole.
  uint8_t out[10];
  __asm__ volatile("fninit\n\tfld1\n\tpushq $3\n\tfildl (%%rsp)\n\tpopq %%rax\n\tfdivrp\n\tfstpt %0" : "=m"(*(uint8_t(*)[10])out) : : "rax", "memory");
  printf("x87.third80");
  for (int i = 9; i >= 0; i--) printf(" %02x", out[i]);
  printf("\n");
  // Rounding control on frndint and fistp, and integer indefinite on overflow.
  const double ins[] = {2.5, 3.5, -2.5, -0.5, 1e20, -1e20, 0.49999999999999994, NAN, 40000.5, -3e9};
  for (int rc = 0; rc < 4; rc++) {
    uint16_t cw = (uint16_t)(0x037f | rc << 10);
    for (size_t i = 0; i < sizeof(ins) / sizeof(ins[0]); i++) {
      double r;
      int64_t q;
      int32_t q32;
      __asm__ volatile("fninit\n\tfldcw %3\n\tfldl %2\n\tfrndint\n\tfstpl %0\n\tfldl %2\n\tfistpll %1"
                       : "=m"(r), "=m"(q) : "m"(ins[i]), "m"(cw) : "memory");
      __asm__ volatile("fninit\n\tfldcw %2\n\tfldl %1\n\tfistpl %0" : "=m"(q32) : "m"(ins[i]), "m"(cw) : "memory");
      int16_t q16, t16;
      int32_t t32;
      int64_t t64;
      __asm__ volatile("fninit\n\tfldcw %2\n\tfldl %1\n\tfistps %0" : "=m"(q16) : "m"(ins[i]), "m"(cw) : "memory");
      // fisttp (SSE3) truncates whatever the rounding control says.
      __asm__ volatile("fninit\n\tfldcw %4\n\tfldl %3\n\tfisttpll %0\n\tfldl %3\n\tfisttpl %1\n\tfldl %3\n\tfisttps %2"
                       : "=m"(t64), "=m"(t32), "=m"(t16) : "m"(ins[i]), "m"(cw) : "memory");
      printf("x87.round rc=%d %016" PRIx64 " frndint=%016" PRIx64 " fistp64=%016" PRIx64 " fistp32=%08x fistp16=%04x"
             " fisttp64=%016" PRIx64 " fisttp32=%08x fisttp16=%04x\n", rc, bits_d(ins[i]), bits_d(r), (uint64_t)q, (uint32_t)q32,
             (uint16_t)q16, (uint64_t)t64, (uint32_t)t32, (uint16_t)t16);
    }
  }
  __asm__ volatile("fninit");
  // Compares with NaN: fcomi raises IE and sets ZF PF CF; fucomi does too but quietly.
  const double cmps[][2] = {{1, 2}, {2, 1}, {1, 1}, {NAN, 1}, {-0.0, 0.0}, {INFINITY, INFINITY}};
  for (size_t i = 0; i < 6; i++) {
    uint64_t f1, f2;
    uint16_t sw;
    __asm__ volatile("fninit\n\tfldl %3\n\tfldl %2\n\tfucomip %%st(1), %%st\n\tpushfq\n\tpopq %0\n\tfstp %%st(0)"
                     : "=r"(f1), "=r"(sw) : "m"(cmps[i][0]), "m"(cmps[i][1]) : "cc", "memory");
    __asm__ volatile("fninit\n\tfldl %3\n\tfldl %2\n\tfcomip %%st(1), %%st\n\tpushfq\n\tpopq %0\n\tfstp %%st(0)\n\tfnstsw %1"
                     : "=r"(f2), "=a"(sw) : "m"(cmps[i][0]), "m"(cmps[i][1]) : "cc", "memory");
    printf("x87.fcomi %016" PRIx64 " %016" PRIx64 " fucomi=%03x fcomi=%03x ie=%d\n", bits_d(cmps[i][0]), bits_d(cmps[i][1]),
           (unsigned)(f1 & (ZF | PF | CF)), (unsigned)(f2 & (ZF | PF | CF)), sw & 1);
  }
  // fprem / fprem1 / fsqrt / fscale / fxtract: exactly defined results.
  const double pr[][2] = {{10.0, 3.0}, {-10.0, 3.0}, {1e30, 7.0}, {5.5, 2.0}, {7.0, -2.0}};
  for (size_t i = 0; i < 5; i++) {
    double r1, r2, sq;
    uint16_t sw1;
    __asm__ volatile("fninit\n\tfldl %4\n\tfldl %3\n\t1: fprem\n\tfnstsw %%ax\n\ttestw $0x400, %%ax\n\tjnz 1b\n\tmovw %%ax, %2\n\tfstpl %0\n\tfstp %%st(0)\n\t"
                     "fldl %4\n\tfldl %3\n\t2: fprem1\n\tfnstsw %%ax\n\ttestw $0x400, %%ax\n\tjnz 2b\n\tfstpl %1\n\tfstp %%st(0)"
                     : "=m"(r1), "=m"(r2), "=m"(sw1) : "m"(pr[i][0]), "m"(pr[i][1]) : "rax", "memory");
    __asm__ volatile("fninit\n\tfldl %1\n\tfsqrt\n\tfstpl %0" : "=m"(sq) : "m"(pr[i][0]) : "memory");
    printf("x87.fprem %016" PRIx64 " %016" PRIx64 " fprem=%016" PRIx64 " q=%x fprem1=%016" PRIx64 " fsqrt=%016" PRIx64 "\n", bits_d(pr[i][0]), bits_d(pr[i][1]),
           bits_d(r1), ((sw1 >> 14) & 1) | ((sw1 >> 8) & 1) << 1 | ((sw1 >> 9) & 1) << 2, bits_d(r2), bits_d(sq));
  }
  // Tag word after loads, a self-store and pops.
  uint8_t env[28];
  __asm__ volatile("fninit\n\tfld1\n\tfldz\n\tfldpi\n\tfst %%st(0)\n\tfstp %%st(1)\n\tfnstenv %0\n\tfninit" : "=m"(*(uint8_t(*)[28])env) : : "memory");
  printf("x87.tagword %02x%02x top=%d\n", env[9], env[8], (env[5] >> 3) & 7);
  // Transcendentals: implementation-defined last bits; checked against libm in double.
  int bad = 0;
  for (double x = -10; x < 10; x += 0.37) {
    double s, c, t;
    __asm__ volatile("fninit\n\tfldl %3\n\tfsin\n\tfstpl %0\n\tfldl %3\n\tfcos\n\tfstpl %1\n\tfldl %3\n\tfptan\n\tfstp %%st(0)\n\tfstpl %2"
                     : "=m"(s), "=m"(c), "=m"(t) : "m"(x) : "memory");
    if (fabs(s - sin(x)) > 1e-15 || fabs(c - cos(x)) > 1e-15 || fabs(t - tan(x)) > 1e-12 * fmax(1, fabs(tan(x)))) bad++;
  }
  printf("x87.transcendental_close_to_libm %s\n", bad ? "bad" : "ok");
  long double ld = 1.0L;
  for (int i = 0; i < 100; i++) ld = ld * 1.0000001L + 1e-19L;
  printf("x87.longdouble_loop %016" PRIx64 "\n", bits_d((double)ld));
}

// ---------------------------------------------------------------- atomics and threads

static _Atomic uint64_t counter;
static uint64_t plain_counter;
static uint8_t split_buffer[256] __attribute__((aligned(64)));
#define ITERS 200000

static void *count_thread(void *arg) {
  uint64_t *split = (uint64_t *)arg;
  for (int i = 0; i < ITERS; i++) {
    atomic_fetch_add_explicit(&counter, 1, memory_order_relaxed);
    __asm__ volatile("lock incq %0" : "+m"(plain_counter));
    // A lock'd add on an 8-byte value at offset 60 of a cache line: it crosses the 64-byte boundary
    // (a split lock). x86 keeps it atomic; a translator must too.
    __asm__ volatile("lock addq $1, %0" : "+m"(*split));
  }
  return NULL;
}

static void *count_thread_16(void *arg) {
  uint32_t *v = (uint32_t *)arg;
  for (int i = 0; i < ITERS; i++) __asm__ volatile("lock addl $1, %0" : "+m"(*v)); // crosses a 16-byte boundary
  return NULL;
}

static void test_atomics(void) {
  uint64_t x = 5, old;
  __asm__ volatile("lock xaddq %0, %1" : "=r"(old), "+m"(x) : "0"(7ull));
  printf("atomic.xadd old=%" PRIu64 " new=%" PRIu64 "\n", old, x);
  uint64_t expected = 12, desired = 99, f;
  __asm__ volatile("lock cmpxchgq %3, %1\n\tpushfq\n\tpopq %2" : "+a"(expected), "+m"(x), "=r"(f) : "r"(desired) : "cc");
  printf("atomic.cmpxchg ok x=%" PRIu64 " rax=%" PRIu64 " zf=%d\n", x, expected, !!(f & ZF));
  expected = 1;
  __asm__ volatile("lock cmpxchgq %3, %1\n\tpushfq\n\tpopq %2" : "+a"(expected), "+m"(x), "=r"(f) : "r"(desired) : "cc");
  printf("atomic.cmpxchg fail x=%" PRIu64 " rax=%" PRIu64 " zf=%d fl=%03x\n", x, expected, !!(f & ZF), (unsigned)(f & ALL6));
  __attribute__((aligned(16))) uint64_t pair[2] = {1, 2};
  uint64_t lo = 1, hi = 2;
  uint8_t ok;
  __asm__ volatile("lock cmpxchg16b %1\n\tsetz %2" : "+a"(lo), "+m"(*(__int128 *)pair), "=q"(ok), "+d"(hi) : "b"(3ull), "c"(4ull) : "cc", "memory");
  printf("atomic.cmpxchg16b ok=%d %" PRIu64 " %" PRIu64 "\n", ok, pair[0], pair[1]);
  uint64_t xc = 0x1234;
  __asm__ volatile("xchgq %0, %1" : "+r"(xc), "+m"(x));
  printf("atomic.xchg %" PRIx64 " %" PRIx64 "\n", xc, x);

  uint64_t *split = (uint64_t *)(split_buffer + 60);
  uint32_t *split16 = (uint32_t *)(split_buffer + 128 + 14);
  *split = 0;
  *split16 = 0;
  pthread_t t[4], u[4];
  for (int i = 0; i < 4; i++) pthread_create(&t[i], NULL, count_thread, split);
  for (int i = 0; i < 4; i++) pthread_create(&u[i], NULL, count_thread_16, split16);
  for (int i = 0; i < 4; i++) { pthread_join(t[i], NULL); pthread_join(u[i], NULL); }
  printf("atomic.threads counter=%" PRIu64 " lockinc=%" PRIu64 " splitlock_cacheline=%" PRIu64 " splitlock_16=%u (want %d)\n",
         (uint64_t)counter, plain_counter, *split, *split16, 4 * ITERS);
}

// ---------------------------------------------------------------- self-modifying code

typedef int (*fn_t)(void);

static void emit_mov_ret(uint8_t *p, int v) { p[0] = 0xb8; memcpy(p + 1, &v, 4); p[5] = 0xc3; }

static void test_smc(void) {
  long pg = sysconf(_SC_PAGESIZE);
  uint8_t *code = mmap(NULL, (size_t)pg, PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  emit_mov_ret(code, 1);
  int a = ((fn_t)code)();
  emit_mov_ret(code, 2); // same page, same address, already run once
  int b = ((fn_t)code)();
  for (int i = 0; i < 1000; i++) { emit_mov_ret(code, i); if (((fn_t)code)() != i) { b = -1000 - i; break; } }
  printf("smc.rwx first=%d patched=%d loop=%s\n", a, b, b < 0 ? "stale" : "ok");
  // A function that patches the instruction it is about to run (same block).
  // mov byte [rip+1+imm-offset], 7 ; mov eax, 0 ; ret  -> patches the imm of the following mov.
  uint8_t self[] = {0xc6, 0x05, 0x01, 0x00, 0x00, 0x00, 0x07, /* mov byte [rip+1], 7 */ 0xb8, 0x00, 0x00, 0x00, 0x00, 0xc3};
  memcpy(code + 64, self, sizeof(self));
  printf("smc.same_block %d\n", ((fn_t)(code + 64))());
  // W^X via mprotect.
  uint8_t *wx = mmap(NULL, (size_t)pg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  emit_mov_ret(wx, 11);
  mprotect(wx, (size_t)pg, PROT_READ | PROT_EXEC);
  int c = ((fn_t)wx)();
  mprotect(wx, (size_t)pg, PROT_READ | PROT_WRITE);
  emit_mov_ret(wx, 12);
  mprotect(wx, (size_t)pg, PROT_READ | PROT_EXEC);
  int d = ((fn_t)wx)();
  printf("smc.mprotect %d %d\n", c, d);
  // Double mapping (what .NET's W^X does): written through one view, run through another.
  int fd = (int)syscall(SYS_memfd_create, "diffx86", 0);
  if (fd >= 0 && ftruncate(fd, pg) == 0) {
    uint8_t *rw = mmap(NULL, (size_t)pg, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    uint8_t *rx = mmap(NULL, (size_t)pg, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
    emit_mov_ret(rw, 21);
    int e = ((fn_t)rx)();
    emit_mov_ret(rw, 22);
    int g = ((fn_t)rx)();
    printf("smc.double_mapping %d %d\n", e, g);
  }
}

// ---------------------------------------------------------------- signals and syscalls

static volatile sig_atomic_t got;
static volatile uintptr_t fault_addr;
static volatile int fault_code;

static void on_signal(int sig, siginfo_t *si, void *uc_) {
  ucontext_t *uc = uc_;
  got = sig;
  fault_addr = (uintptr_t)si->si_addr;
  fault_code = si->si_code;
  // Skip the faulting instruction; the test puts its length in r15.
  uc->uc_mcontext.gregs[REG_RIP] += uc->uc_mcontext.gregs[REG_R15];
  uc->uc_mcontext.gregs[REG_RBX] = 0x5151; // a register written by the handler must reach the program
}

static void test_signals(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = on_signal;
  sa.sa_flags = SA_SIGINFO;
  sigaction(SIGSEGV, &sa, NULL); sigaction(SIGFPE, &sa, NULL); sigaction(SIGILL, &sa, NULL); sigaction(SIGTRAP, &sa, NULL);
  uint64_t rbx;
  // mov rax, [0x10]  (8 bytes: 48 8b 04 25 10 00 00 00)
  __asm__ volatile("movq $8, %%r15\n\tmovq $0, %%rbx\n\t.byte 0x48, 0x8b, 0x04, 0x25, 0x10, 0x00, 0x00, 0x00\n\tmovq %%rbx, %0" : "=r"(rbx) : : "rax", "rbx", "r15", "memory");
  printf("sig.segv sig=%d addr=%#lx code=%d rbx=%#lx\n", got, (unsigned long)fault_addr, fault_code, (unsigned long)rbx);
  // Divide errors (#DE): by zero at each width, and quotients that do not fit (INT_MIN / -1,
  // an unsigned quotient over 32 bits). x86 raises SIGFPE with FPE_INTDIV for all of them.
#define DE(name, len, setup, bytes)                                                                       \
  got = 0; fault_code = 0;                                                                                \
  __asm__ volatile("movq $" #len ", %%r15\n\tmovq $0, %%rbx\n\t" setup "\n\t.byte " bytes "\n\tmovq %%rbx, %0" \
                   : "=r"(rbx) : : "rax", "rbx", "rcx", "rdx", "r15", "cc");                             \
  printf("sig.fpe %s sig=%d code=%d rbx=%#lx\n", name, got, got ? fault_code : 0, (unsigned long)rbx);
  DE("idiv32_by_zero", 2, "xorl %%ecx, %%ecx\n\tmovl $5, %%eax\n\tcltd", "0xf7, 0xf9")
  DE("div32_by_zero", 2, "xorl %%ecx, %%ecx\n\tmovl $5, %%eax\n\txorl %%edx, %%edx", "0xf7, 0xf1")
  DE("div64_by_zero", 3, "xorl %%ecx, %%ecx\n\tmovl $5, %%eax\n\txorl %%edx, %%edx", "0x48, 0xf7, 0xf1")
  DE("idiv64_by_zero", 3, "xorl %%ecx, %%ecx\n\tmovl $5, %%eax\n\tcqto", "0x48, 0xf7, 0xf9")
  DE("div8_by_zero", 2, "xorl %%ecx, %%ecx\n\tmovl $5, %%eax", "0xf6, 0xf1")
  DE("idiv16_by_zero", 3, "xorl %%ecx, %%ecx\n\tmovl $5, %%eax\n\tcwtd", "0x66, 0xf7, 0xf9")
  DE("idiv32_int_min_by_minus_1", 2, "movl $-1, %%ecx\n\tmovl $0x80000000, %%eax\n\tcltd", "0xf7, 0xf9")
  DE("idiv64_int_min_by_minus_1", 3, "movq $-1, %%rcx\n\tmovabsq $0x8000000000000000, %%rax\n\tcqto", "0x48, 0xf7, 0xf9")
  DE("div32_quotient_overflow", 2, "movl $1, %%ecx\n\tmovl $0, %%eax\n\tmovl $1, %%edx", "0xf7, 0xf1")
#undef DE
  got = 0;
  __asm__ volatile("movq $2, %%r15\n\tmovq $0, %%rbx\n\tud2\n\tmovq %%rbx, %0" : "=r"(rbx) : : "rbx", "r15");
  printf("sig.ill sig=%d code=%d rbx=%#lx\n", got, fault_code, (unsigned long)rbx);
  got = 0;
  __asm__ volatile("movq $0, %%r15\n\tmovq $0, %%rbx\n\tint3\n\tmovq %%rbx, %0" : "=r"(rbx) : : "rbx", "r15");
  printf("sig.trap sig=%d rbx=%#lx\n", got, (unsigned long)rbx);
  // A signal sent to ourselves, with a blocked-then-pending cycle.
  sigset_t set, old;
  sigemptyset(&set); sigaddset(&set, SIGUSR1);
  struct sigaction su;
  memset(&su, 0, sizeof(su));
  su.sa_handler = (void (*)(int))(void *)on_signal; // not used: plain handler below
  signal(SIGUSR1, SIG_IGN);
  sigprocmask(SIG_BLOCK, &set, &old);
  kill(getpid(), SIGUSR1);
  sigset_t pending;
  sigpending(&pending);
  printf("sig.pending usr1=%d\n", sigismember(&pending, SIGUSR1));
  sigprocmask(SIG_SETMASK, &old, NULL);
}

static int futex_word;
static void *pong(void *arg) {
  (void)arg;
  for (int i = 0; i < 2000; i++) {
    while (__atomic_load_n(&futex_word, __ATOMIC_ACQUIRE) != 1) syscall(SYS_futex, &futex_word, FUTEX_WAIT_PRIVATE, 0, NULL, NULL, 0);
    __atomic_store_n(&futex_word, 0, __ATOMIC_RELEASE);
    syscall(SYS_futex, &futex_word, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
  }
  return NULL;
}

static void test_syscalls(void) {
  pthread_t t;
  pthread_create(&t, NULL, pong, NULL);
  for (int i = 0; i < 2000; i++) {
    __atomic_store_n(&futex_word, 1, __ATOMIC_RELEASE);
    syscall(SYS_futex, &futex_word, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0);
    while (__atomic_load_n(&futex_word, __ATOMIC_ACQUIRE) != 0) syscall(SYS_futex, &futex_word, FUTEX_WAIT_PRIVATE, 1, NULL, NULL, 0);
  }
  pthread_join(t, NULL);
  printf("sys.futex_pingpong done\n");
  struct timespec a, b;
  clock_gettime(CLOCK_MONOTONIC, &a);
  struct timespec ns = {0, 2000000};
  nanosleep(&ns, NULL);
  clock_gettime(CLOCK_MONOTONIC, &b);
  long dt = (b.tv_sec - a.tv_sec) * 1000000000L + (b.tv_nsec - a.tv_nsec);
  printf("sys.monotonic_sleep_at_least_2ms %s\n", dt >= 2000000 ? "ok" : "bad");
  unsigned lo1, hi1, lo2, hi2;
  __asm__ volatile("rdtsc" : "=a"(lo1), "=d"(hi1));
  __asm__ volatile("rdtsc" : "=a"(lo2), "=d"(hi2));
  printf("sys.rdtsc_monotonic %s\n", ((uint64_t)hi2 << 32 | lo2) >= ((uint64_t)hi1 << 32 | lo1) ? "ok" : "bad");
  void *p = mmap(NULL, 1 << 20, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  memset(p, 0x5a, 1 << 20);
  void *q = mremap(p, 1 << 20, 4 << 20, MREMAP_MAYMOVE);
  printf("sys.mremap kept=%d\n", q != MAP_FAILED && ((uint8_t *)q)[12345] == 0x5a && ((uint8_t *)q)[(1 << 20) - 1] == 0x5a);
  printf("sys.getpid_matches %d\n", (pid_t)syscall(SYS_getpid) == getpid());
  errno = 0;
  long r = syscall(SYS_close, -1);
  printf("sys.close_bad r=%ld errno=%d\n", r, errno);
}

// ---------------------------------------------------------------- JIT reuse and reproducibility

__attribute__((noinline)) static uint64_t mix(uint64_t s, int n) {
  __m128i v = _mm_set_epi64x((long long)s, (long long)~s);
  for (int i = 0; i < n; i++) {
    s ^= s << 13; s ^= s >> 7; s ^= s << 17;
    v = _mm_add_epi32(v, _mm_set_epi64x((long long)s, (long long)(s * 0x9e3779b97f4a7c15ull)));
    v = _mm_shuffle_epi32(v, 0x4e);
    s += (uint64_t)_mm_cvtsi128_si64(v) + (s >> 3 | s << 61);
    if (s & 1) s = s * 3 + 1; else s >>= 1;
  }
  return s;
}

static void test_reuse(void) {
  uint64_t first = mix(42, 100000);
  int same = 1;
  for (int i = 0; i < 20; i++) same &= mix(42, 100000) == first;
  printf("jit.mix %016" PRIx64 " repeated_same=%d\n", first, same);
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IOFBF, 1 << 20);
  only = argc > 1 ? argv[1] : NULL;
  detect();
  if (section("int")) { test_alu(); test_shifts(); test_muldiv(); test_bitops(); }
  if (section("sse")) { test_sse_fp(); test_sse_int(); }
  if (section("avx")) test_avx();
  if (section("x87")) test_x87();
  if (section("atomic")) test_atomics();
  if (section("smc")) test_smc();
  if (section("sig")) test_signals();
  if (section("sys")) test_syscalls();
  if (section("jit")) test_reuse();
  printf("end\n");
  return 0;
}
