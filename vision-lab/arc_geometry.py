"""Audit known, front-facing partial rings using separate reference points."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from lab_data import write_json


@dataclass(frozen=True)
class ArcFit:
    center: tuple[float, float]
    radius_px: float
    rms_residual_px: float
    coverage_deg: float
    condition: float | None
    control_p95_px: float


@dataclass(frozen=True)
class ArcAudit:
    fit: ArcFit | None
    reasons: tuple[str, ...]


def _jacobian_and_residual(delta, fractions, radius):
    distances = np.linalg.norm(delta, axis=1)
    if (distances < 1e-8).any():
        raise ValueError("An observed ring point coincides with the estimated center")
    jacobian = np.column_stack((-delta/distances[:, None], -fractions))
    return jacobian, distances-fractions*radius


def _fit(samples):
    xy, fractions = samples[:, :2], samples[:, 2]
    mean = xy.mean(axis=0)
    factor = max(1., float(np.ptp(xy, axis=0).max()))
    positions = (xy-mean)/factor
    lhs = np.column_stack((2*positions, fractions**2, np.ones(len(samples))))
    solution, _, rank, _ = np.linalg.lstsq(lhs, np.sum(positions**2, axis=1), rcond=None)
    if rank < 4 or solution[2] <= 0:
        raise ValueError("An initial circle scale is not identifiable")
    estimate = np.r_[solution[:2], np.sqrt(solution[2])]
    for _ in range(20):
        jacobian, residual = _jacobian_and_residual(positions-estimate[:2], fractions, estimate[2])
        step, _, _, _ = np.linalg.lstsq(jacobian, -residual, rcond=None)
        estimate += step
        if not np.isfinite(estimate).all() or estimate[2] <= 0:
            raise ValueError("The circle fit did not converge to a positive radius")
        if np.linalg.norm(step) < 1e-8:
            return mean+estimate[:2]*factor, float(estimate[2]*factor)
    raise ValueError("The circle fit did not converge")


def audit_arcs(fit_observations, check_observations, profile_radii, *, known_front):
    """Observations are (x, y, identified radius fraction) in the oriented image.

    Limits are exploratory controls in working pixels, not product acceptance criteria.
    """
    if not known_front:
        return ArcAudit(None, ("unverified-front-plane",))
    fit, check = np.asarray(fit_observations, float), np.asarray(check_observations, float)
    if any(x.ndim != 2 or x.shape[1] != 3 or not np.isfinite(x).all() for x in (fit, check)):
        return ArcAudit(None, ("invalid-observation",))
    fractions, counts = np.unique(fit[:, 2], return_counts=True)
    if not all(0 < q <= 1 and q in profile_radii for q in (*fractions, *check[:, 2])):
        return ArcAudit(None, ("unknown-ring",))
    if len(fractions) < 2 or (counts < 3).any():
        return ArcAudit(None, ("need-two-identified-rings",))
    if len(check) < 3 or (np.linalg.norm(fit[:, None, :2]-check[None, :, :2], axis=2) < 1.).any():
        return ArcAudit(None, ("need-independent-check-points",))
    try:
        center, radius = _fit(fit)
        delta = fit[:, :2]-center
        jacobian, residual = _jacobian_and_residual(delta, fit[:, 2], radius)
    except (ValueError, np.linalg.LinAlgError):
        return ArcAudit(None, ("unidentifiable-fit",))
    singular = np.linalg.svd(jacobian, compute_uv=False)
    condition = float(singular[0]/singular[-1]) if singular[-1] > 1e-9 else None
    angles = np.sort(np.arctan2(delta[:, 1], delta[:, 0]))
    max_gap = np.diff(np.r_[angles, angles[0]+2*np.pi]).max()
    coverage = float(np.degrees(2*np.pi-max_gap))
    check_error = np.abs(np.linalg.norm(check[:, :2]-center, axis=1)-check[:, 2]*radius)
    stats = ArcFit(tuple(map(float, center)), radius, float(np.sqrt(np.mean(residual**2))), coverage, condition,
                   float(np.quantile(check_error, .95)))
    failed = []
    if coverage < 100:
        failed.append("insufficient-angular-spread")
    if condition is None or condition > 25:
        failed.append("unstable-center-or-scale")
    if stats.rms_residual_px > 3:
        failed.append("fit-disagrees-with-rings")
    if stats.control_p95_px > 4:
        failed.append("independent-rings-disagree")
    return ArcAudit(stats, tuple(failed))


CENTER = np.array([352., 284.])
RADIUS = 204.
PROFILE = (.1, .2, .3, .4, .5, .6, .7, .8, .9, 1.)


def samples(rng, true_radius, identified_as, angles):
    theta = np.deg2rad(angles)
    points = CENTER + RADIUS*true_radius*np.column_stack((np.cos(theta), np.sin(theta)))
    points += rng.normal(0., 1., size=points.shape)
    return np.column_stack((points, np.full(len(theta), identified_as)))


def experiment():
    reports = {}
    for case in ("spread", "short-arcs", "rings-mislabeled"):
        rng = np.random.default_rng(2111)
        accept, residual, radius_error, checks = 0, [], [], []
        reasons = {}
        for _ in range(120):
            narrow = case == "short-arcs"
            angles = np.linspace(-10, 10, 12) if narrow else np.linspace(-140, 138, 12)
            first, second = (.2, .3) if case == "rings-mislabeled" else (.4, .6)
            observations = np.vstack((samples(rng, .4, first, angles), samples(rng, .6, second, angles+5)))
            control = samples(rng, .8, .8, [65, 118, 171, 223, 275])
            inspected = audit_arcs(observations, control, PROFILE, known_front=True)
            if inspected.fit is not None:
                residual.append(inspected.fit.rms_residual_px)
                radius_error.append(abs(inspected.fit.radius_px-RADIUS))
                checks.append(inspected.fit.control_p95_px)
            if not inspected.reasons:
                accept += 1
            for reason in inspected.reasons:
                reasons[reason] = reasons.get(reason, 0) + 1
        reports[case] = {
            "trials": 120, "preview_candidates": accept, "fits": len(residual), "reasons": reasons,
            "fit_residual_median_px": float(np.median(residual)),
            "radius_error_p95_px": float(np.quantile(radius_error, .95)),
            "independent_error_median_px": float(np.median(checks)),
        }
    return reports


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = experiment()
    if args.output.exists():
        parser.error("Select a new result path")
    write_json(args.output, result)
