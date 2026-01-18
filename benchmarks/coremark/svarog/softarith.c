/*
 * Minimal soft integer arithmetic helpers to avoid libgcc
 * when building for RV32I/Zmmul targets without hardware div.
 */

#include <stdint.h>

uint32_t __mulsi3(uint32_t a, uint32_t b)
{
    uint32_t res = 0;
    while (b)
    {
        if (b & 1u)
            res += a;
        a <<= 1;
        b >>= 1;
    }
    return res;
}

static uint32_t
udivmod32(uint32_t num, uint32_t den, uint32_t *rem)
{
    uint32_t q = 0;
    uint32_t r = 0;
    for (int i = 31; i >= 0; --i)
    {
        r <<= 1;
        r |= (num >> i) & 1u;
        if (r >= den)
        {
            r -= den;
            q |= (1u << i);
        }
    }
    if (rem)
        *rem = r;
    return q;
}

uint32_t __udivsi3(uint32_t a, uint32_t b)
{
    if (b == 0)
        return 0xffffffffu;
    return udivmod32(a, b, 0);
}

uint32_t __umodsi3(uint32_t a, uint32_t b)
{
    if (b == 0)
        return a;
    uint32_t r = 0;
    (void)udivmod32(a, b, &r);
    return r;
}

int32_t __divsi3(int32_t a, int32_t b)
{
    if (b == 0)
        return (a >= 0) ? 0x7fffffff : (int32_t)0x80000000;
    uint32_t ua = (a < 0) ? (uint32_t)(-a) : (uint32_t)a;
    uint32_t ub = (b < 0) ? (uint32_t)(-b) : (uint32_t)b;
    uint32_t q = udivmod32(ua, ub, 0);
    return (a ^ b) < 0 ? -(int32_t)q : (int32_t)q;
}

int32_t __modsi3(int32_t a, int32_t b)
{
    if (b == 0)
        return a;
    uint32_t ua = (a < 0) ? (uint32_t)(-a) : (uint32_t)a;
    uint32_t ub = (b < 0) ? (uint32_t)(-b) : (uint32_t)b;
    uint32_t r = 0;
    (void)udivmod32(ua, ub, &r);
    return (a < 0) ? -(int32_t)r : (int32_t)r;
}

uint64_t __muldi3(uint64_t a, uint64_t b)
{
    uint64_t res = 0;
    while (b)
    {
        if (b & 1ull)
            res += a;
        a <<= 1;
        b >>= 1;
    }
    return res;
}

static uint64_t
udivmod64(uint64_t num, uint64_t den, uint64_t *rem)
{
    uint64_t q = 0;
    uint64_t r = 0;
    for (int i = 63; i >= 0; --i)
    {
        r <<= 1;
        r |= (num >> i) & 1ull;
        if (r >= den)
        {
            r -= den;
            q |= (1ull << i);
        }
    }
    if (rem)
        *rem = r;
    return q;
}

uint64_t __udivdi3(uint64_t a, uint64_t b)
{
    if (b == 0)
        return 0xffffffffffffffffull;
    return udivmod64(a, b, 0);
}

uint64_t __umoddi3(uint64_t a, uint64_t b)
{
    if (b == 0)
        return a;
    uint64_t r = 0;
    (void)udivmod64(a, b, &r);
    return r;
}

int64_t __divdi3(int64_t a, int64_t b)
{
    if (b == 0)
        return (a >= 0) ? 0x7fffffffffffffffll : (int64_t)0x8000000000000000ull;
    uint64_t ua = (a < 0) ? (uint64_t)(-a) : (uint64_t)a;
    uint64_t ub = (b < 0) ? (uint64_t)(-b) : (uint64_t)b;
    uint64_t q = udivmod64(ua, ub, 0);
    return (a ^ b) < 0 ? -(int64_t)q : (int64_t)q;
}

int64_t __moddi3(int64_t a, int64_t b)
{
    if (b == 0)
        return a;
    uint64_t ua = (a < 0) ? (uint64_t)(-a) : (uint64_t)a;
    uint64_t ub = (b < 0) ? (uint64_t)(-b) : (uint64_t)b;
    uint64_t r = 0;
    (void)udivmod64(ua, ub, &r);
    return (a < 0) ? -(int64_t)r : (int64_t)r;
}
