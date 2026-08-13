#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"
#include <cstdint>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
  #include <arm_neon.h>
  #define RAWV_HAVE_NEON 1
#elif defined(RAWV_USE_NEON2SSE)
  #include "NEON_2_SSE.h"
  #define RAWV_HAVE_NEON 1
#endif

TEST_CASE("ARM_NEON_2_SSE/NEON integer ops are bit-exact for our predictor kernel") {
#if RAWV_HAVE_NEON
  // left/up/upleft/actual chosen so clamp engages both bounds and one lane
  // yields a negative residual (exercises the arithmetic shift in zigzag).
  int32_t L[4] = {100, 5000, 16383, 0};
  int32_t U[4] = {120, 4000, 16000, 4};
  int32_t UL[4] = {110, 4500, 15000, 2};
  int32_t A[4] = {130, 3000, 16383, 1};
  int32x4_t l = vld1q_s32(L), u = vld1q_s32(U), ul = vld1q_s32(UL), a = vld1q_s32(A);
  int32x4_t linear = vsubq_s32(vaddq_s32(l, u), ul);
  int32x4_t pred = vmaxq_s32(vminq_s32(l, u), vminq_s32(linear, vmaxq_s32(l, u)));
  int32x4_t r = vsubq_s32(a, pred);
  int32x4_t z = veorq_s32(vshlq_n_s32(r, 1), vshrq_n_s32(r, 31));
  uint32_t got[4];
  vst1q_u32(got, vreinterpretq_u32_s32(z));
  for (int i = 0; i < 4; i++) {
    int32_t lo = L[i] < U[i] ? L[i] : U[i];
    int32_t hi = L[i] < U[i] ? U[i] : L[i];
    int32_t lin = L[i] + U[i] - UL[i];
    int32_t p = lin < lo ? lo : (lin > hi ? hi : lin);
    int32_t rr = A[i] - p;
    uint32_t want = (static_cast<uint32_t>(rr) << 1) ^ static_cast<uint32_t>(rr >> 31);
    CHECK(got[i] == want);
  }
#else
  MESSAGE("RAWV_HAVE_NEON not defined -- SIMD path unavailable on this build");
  CHECK(false);  // on host this MUST be defined; failing here is the go/no-go signal
#endif
}
