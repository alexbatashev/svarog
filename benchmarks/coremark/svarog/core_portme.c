/*
Copyright 2018 Embedded Microprocessor Benchmark Consortium (EEMBC)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Original Author: Shay Gal-on
*/
#include "coremark.h"
#include "core_portme.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

/* Static memory region for CoreMark when MEM_METHOD == MEM_STATIC. */
#if (MEM_METHOD == MEM_STATIC)
static ee_u8 coremark_mem[16 * 1024];
static ee_size_t coremark_mem_idx;
#endif
/* Porting : Timing functions
        How to capture time and convert to seconds must be ported to whatever is
   supported by the platform. e.g. Read value from on board RTC, read value from
   cpu clock cycles performance counter etc. Sample implementation for standard
   time.h and windows.h definitions included.
*/
CORETIMETYPE
barebones_clock()
{
    volatile ee_u32 *mtime_lo = (volatile ee_u32 *)(SVAROG_TIMER_BASE
                                                    + SVAROG_MTIME_LO_OFFSET);
    volatile ee_u32 *mtime_hi = (volatile ee_u32 *)(SVAROG_TIMER_BASE
                                                    + SVAROG_MTIME_HI_OFFSET);
    ee_u32 hi1, lo, hi2;

    /* Read mtime atomically (hi-lo-hi). */
    do
    {
        hi1 = *mtime_hi;
        lo = *mtime_lo;
        hi2 = *mtime_hi;
    } while (hi1 != hi2);

    return ((CORETIMETYPE)hi1 << 32) | lo;
}
/* Define : TIMER_RES_DIVIDER
        Divider to trade off timer resolution and total time that can be
   measured.

        Use lower values to increase resolution, but make sure that overflow
   does not occur. If there are issues with the return value overflowing,
   increase this value.
        */
#define GETMYTIME(_t)              (*_t = barebones_clock())
#define MYTIMEDIFF(fin, ini)       ((fin) - (ini))
#define TIMER_RES_DIVIDER          1
#define SAMPLE_TIME_IMPLEMENTATION 1
#define EE_TICKS_PER_SEC           (SVAROG_RTC_HZ / TIMER_RES_DIVIDER)

/** Define Host specific (POSIX), or target specific global time variables. */
static CORETIMETYPE start_time_val, stop_time_val;
static ee_u64 cycle_start, cycle_end;
static ee_u64 instret_start, instret_end;
static ee_u64 branches_start, branches_end;
static ee_u64 branch_miss_start, branch_miss_end;
static ee_u64 hazard_stall_start, hazard_stall_end;

#if __riscv_xlen == 32
#define DECLARE_READ_COUNTER64(name, low_csr, high_csr)                            \
    static ee_u64 name(void)                                                       \
    {                                                                               \
        ee_u32 hi1, lo, hi2;                                                       \
        do                                                                          \
        {                                                                           \
            asm volatile("csrr %0, " #high_csr : "=r"(hi1));                       \
            asm volatile("csrr %0, " #low_csr : "=r"(lo));                         \
            asm volatile("csrr %0, " #high_csr : "=r"(hi2));                       \
        } while (hi1 != hi2);                                                      \
        return ((ee_u64)hi1 << 32) | lo;                                           \
    }
#else
#define DECLARE_READ_COUNTER64(name, low_csr, high_csr)                            \
    static ee_u64 name(void)                                                       \
    {                                                                               \
        ee_u64 val;                                                                 \
        asm volatile("csrr %0, " #low_csr : "=r"(val));                            \
        return val;                                                                 \
    }
#endif

DECLARE_READ_COUNTER64(read_cycle_counter, 0xC00, 0xC80)
DECLARE_READ_COUNTER64(read_instret_counter, 0xC02, 0xC82)
DECLARE_READ_COUNTER64(read_branches_counter, 0xC03, 0xC83)
DECLARE_READ_COUNTER64(read_branch_miss_counter, 0xC04, 0xC84)
DECLARE_READ_COUNTER64(read_hazard_stall_counter, 0xC05, 0xC85)

/* Function : start_time
        This function will be called right before starting the timed portion of
   the benchmark.

        Implementation may be capturing a system timer (as implemented in the
   example code) or zeroing some system parameters - e.g. setting the cpu clocks
   cycles to 0.
*/
void
start_time(void)
{
    GETMYTIME(&start_time_val);
}
/* Function : stop_time
        This function will be called right after ending the timed portion of the
   benchmark.

        Implementation may be capturing a system timer (as implemented in the
   example code) or other system parameters - e.g. reading the current value of
   cpu cycles counter.
*/
void
stop_time(void)
{
    GETMYTIME(&stop_time_val);
}
/* Function : get_time
        Return an abstract "ticks" number that signifies time on the system.

        Actual value returned may be cpu cycles, milliseconds or any other
   value, as long as it can be converted to seconds by <time_in_secs>. This
   methodology is taken to accommodate any hardware or simulated platform. The
   sample implementation returns millisecs by default, and the resolution is
   controlled by <TIMER_RES_DIVIDER>
*/
CORE_TICKS
get_time(void)
{
    CORE_TICKS elapsed
        = (CORE_TICKS)(MYTIMEDIFF(stop_time_val, start_time_val));
    return elapsed;
}
/* Function : time_in_secs
        Convert the value returned by get_time to seconds.

        The <secs_ret> type is used to accommodate systems with no support for
   floating point. Default implementation implemented by the EE_TICKS_PER_SEC
   macro above.
*/
secs_ret
time_in_secs(CORE_TICKS ticks)
{
    ee_u64   ticks64 = (ee_u64)ticks;
    secs_ret retval
        = (secs_ret)(ticks64 / (ee_u64)EE_TICKS_PER_SEC);
    return retval;
}

ee_u32 default_num_contexts = 1;

/* Function : portable_init
        Target specific initialization code
        Test for some common mistakes.
*/
void
portable_init(core_portable *p, int *argc, char *argv[])
{
    (void)argc; // prevent unused warning
    (void)argv; // prevent unused warning

    if (sizeof(ee_ptr_int) != sizeof(ee_u8 *))
    {
        ee_printf(
            "ERROR! Please define ee_ptr_int to a type that holds a "
            "pointer!\n");
    }
    if (sizeof(ee_u32) != 4)
    {
        ee_printf("ERROR! Please define ee_u32 to a 32b unsigned type!\n");
    }
#if (MEM_METHOD == MEM_STATIC)
    coremark_mem_idx = 0;
#endif
    p->portable_id = 1;

    cycle_start = read_cycle_counter();
    instret_start = read_instret_counter();
    branches_start = read_branches_counter();
    branch_miss_start = read_branch_miss_counter();
    hazard_stall_start = read_hazard_stall_counter();
}
/* Function : portable_fini
        Target specific final code
*/
void
portable_fini(core_portable *p)
{
    p->portable_id = 0;

    cycle_end = read_cycle_counter();
    instret_end = read_instret_counter();
    branches_end = read_branches_counter();
    branch_miss_end = read_branch_miss_counter();
    hazard_stall_end = read_hazard_stall_counter();

    ee_printf("CoreMark cycle count  : %llu\n", cycle_end - cycle_start);
    ee_printf("CoreMark instret count: %llu\n", instret_end - instret_start);
    ee_printf("CoreMark branches retired: %llu\n", branches_end - branches_start);
    ee_printf("CoreMark branch misses   : %llu\n", branch_miss_end - branch_miss_start);
    ee_printf("CoreMark hazard stalls   : %llu\n", hazard_stall_end - hazard_stall_start);
}

void *
portable_malloc(ee_size_t size)
{
#if (MEM_METHOD == MEM_STATIC)
    if (coremark_mem_idx + size > sizeof(coremark_mem))
        return NULL;
    void *ptr = &coremark_mem[coremark_mem_idx];
    coremark_mem_idx += size;
    return ptr;
#else
    (void)size;
    return NULL;
#endif
}

void
portable_free(void *p)
{
    (void)p;
}
