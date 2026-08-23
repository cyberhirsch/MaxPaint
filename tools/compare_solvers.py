#!/usr/bin/env python3
"""
Measures Jacobi against red-black Gauss-Seidel for the pressure solve, on a real
GLES 3.1 driver. Reports divergence removed per sweep at matched sweep counts.

The pressure solve dominates frame cost, so its convergence rate sets the
resolution ceiling. This is the measurement behind that choice.
"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
from verify_solver import (make_context, compile_compute, uni, Sim, rms, gstr)
from OpenGL.GLES3 import *


def removed(use_rb, sweeps, res, dt=1 / 60.0):
    s = Sim(res, iters=sweeps, use_rb=use_rb)
    s.splat(0.5, 0.5, 3.0, 0.0, (0.9, 0.3, 0.2))
    s.compute_divergence()
    d0 = rms(s.div.read()[:, :, 0])
    s.project(dt)
    s.compute_divergence()
    d1 = rms(s.div.read()[:, :, 0])
    return 100 * (1 - d1 / max(d0, 1e-12))


def sweeps_for(target_pct, res, use_rb=True, cap=1200):
    """Smallest sweep count clearing target_pct of divergence, by doubling search."""
    n = 4
    while n <= cap:
        if removed(use_rb, n, res) >= target_pct:
            lo, hi = n // 2, n
            while lo + 1 < hi:                      # refine
                mid = (lo + hi) // 2
                if removed(use_rb, mid, res) >= target_pct:
                    hi = mid
                else:
                    lo = mid
            return hi
        n *= 2
    return None


def scaling_report():
    print("Sweeps needed to clear 50% of divergence, red-black Gauss-Seidel\n")
    print("  grid     sweeps   vs previous   cost for equal quality")
    print("  -------------------------------------------------------")
    prev_n = prev_res = None
    for res in (32, 64, 128, 256):
        n = sweeps_for(50.0, res)
        if n is None:
            print(f"  {res:>4}²   >1200")
            continue
        if prev_n:
            ratio = n / prev_n
            # cost = cells x sweeps
            cost = (res / prev_res) ** 2 * ratio
            print(f"  {res:>4}²   {n:>6}   {ratio:>10.1f}x   {cost:>6.1f}x")
        else:
            print(f"  {res:>4}²   {n:>6}   {'-':>10}    {'-':>6}")
        prev_n, prev_res = n, res
    print()
    print("  Sweeps scale ~quadratically with grid width, so holding quality")
    print("  while doubling resolution costs roughly 16x, not 4x. This is the")
    print("  real ceiling on simulation resolution -- and the argument for")
    print("  multigrid, whose convergence is resolution-independent.")


def main():
    make_context()
    print(f"Driver: {gstr(glGetString(GL_RENDERER))}")
    print(f"        {gstr(glGetString(GL_VERSION))}\n")

    if "--scaling" in sys.argv:
        scaling_report()
        return

    res = int(sys.argv[1]) if len(sys.argv) > 1 else 128
    print(f"Divergence removed by the pressure solve, {res}x{res}, cold start\n")
    print("  sweeps   Jacobi    RB-Gauss-Seidel   advantage")
    print("  ---------------------------------------------")

    rows = []
    for n in (5, 10, 20, 30, 60, 100):
        j = removed(False, n, res)
        g = removed(True, n, res)
        rows.append((n, j, g))
        print(f"  {n:>5}   {j:6.1f}%   {g:13.1f}%   {g - j:+7.1f}pp")

    print()
    # how many Jacobi sweeps to match RB-GS at 30?
    target = next(g for n, j, g in rows if n == 30)
    print(f"RB-GS at 30 sweeps removes {target:.1f}% of divergence.")
    for n in (30, 60, 100, 150, 220, 300):
        j = removed(False, n, res)
        if j >= target:
            print(f"Jacobi needs ~{n} sweeps to match it ({j:.1f}%) "
                  f"-- about {n/30:.1f}x the work.")
            break
    else:
        print("Jacobi did not match it within 300 sweeps.")


if __name__ == "__main__":
    main()
