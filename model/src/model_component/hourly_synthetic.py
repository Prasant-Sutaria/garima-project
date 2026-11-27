"""Synthetic hour-of-day phone usage by profession, for pattern learning and demos only.

Real snapshots hold daily totals only, so these rows stand in for hourly data. They carry
source=synthetic, live in the separate `synthetic` Postgres schema, and never mix with real
rows.

Each profession has its own weekday and weekend rhythm built from circular Gaussian bumps,
and every profession leans towards night (21:00-23:59) and late-night (00:00-02:59) use.
Participants differ by body clock (bumps shifted together), bump strength, daily volume, and
day-to-day noise. Each hour is capped at 60 minutes and unlocks scale with screen time.

Each participant is also tagged with a synthetic addiction level (pre, mild, high). The tag is
a generator label, not a measurement or diagnosis; higher levels get more daily screen time,
a stronger night/late-night lean, and more frequent unlocks.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

SOURCE = "synthetic"
RNG_SEED = 20261124
HOURS = np.arange(24)
START_DATE = pd.Timestamp("2026-01-05")  # a Monday
PROFESSIONS = ("student", "working_professional", "homemaker")
PROFESSION_PROBABILITIES = (0.35, 0.40, 0.25)

# (centre hour, width in hours, relative weight)
Bump = tuple[float, float, float]
RHYTHMS: dict[str, dict[str, tuple[Bump, ...]]] = {
    "student": {
        # Light use around classes, a post-class bump, then heavy night and late-night use.
        "weekday": ((7.5, 0.7, 0.35), (13.0, 1.0, 0.55), (17.5, 1.2, 0.9),
                    (22.5, 1.5, 1.9), (1.0, 1.3, 1.5)),
        "weekend": ((11.5, 1.5, 0.6), (15.5, 2.0, 0.9), (22.5, 1.8, 1.8), (1.5, 1.6, 1.8)),
    },
    "working_professional": {
        # Wake-up check, commute, lunch break, commute home, then night scrolling.
        "weekday": ((7.0, 0.8, 0.6), (9.0, 0.6, 0.35), (13.3, 0.7, 0.6),
                    (18.5, 0.8, 0.65), (21.8, 1.2, 1.6), (0.0, 1.0, 1.0)),
        "weekend": ((10.0, 1.5, 0.7), (15.0, 2.0, 0.8), (21.8, 1.5, 1.6), (0.5, 1.3, 1.2)),
    },
    "homemaker": {
        # Use after morning chores and during the afternoon lull, then after dinner.
        "weekday": ((6.5, 0.6, 0.3), (11.0, 1.2, 0.85), (15.0, 1.3, 1.0),
                    (21.5, 1.2, 1.4), (23.6, 1.0, 1.0)),
        "weekend": ((12.0, 1.5, 0.6), (16.0, 1.5, 0.7), (22.0, 1.4, 1.4), (0.2, 1.0, 0.9)),
    },
}
DAILY_MINUTES_MEDIAN = {"student": 400.0, "working_professional": 270.0, "homemaker": 320.0}
WEEKEND_MULTIPLIER = {"student": 1.15, "working_professional": 1.25, "homemaker": 0.95}
AGE_BANDS = {
    "student": (("18-24", 0.85), ("25-34", 0.15)),
    "working_professional": (("18-24", 0.1), ("25-34", 0.45), ("35-44", 0.3), ("45+", 0.15)),
    "homemaker": (("25-34", 0.35), ("35-44", 0.4), ("45+", 0.25)),
}
ADDICTION_LEVELS = ("pre", "mild", "high")
ADDICTION_PROBABILITIES = {
    "student": (0.30, 0.40, 0.30),
    "working_professional": (0.50, 0.35, 0.15),
    "homemaker": (0.45, 0.40, 0.15),
}


@dataclass(frozen=True)
class AddictionEffect:
    volume: float  # multiplier on daily screen time
    night_weight: float  # multiplier on night and late-night bumps
    bedtime_delay: float  # hours added to night and late-night bump centres
    after_midnight: float  # weight of an extra scrolling bump around 02:00 (before delay)
    daytime_checking: float  # flat extra intensity over 08:00-19:59 (frequent checks)
    quiet_from: int  # first hour of the 01:00-06:59 sleep window that is damped
    quiet_factor: float  # multiplier on the damped sleep hours
    unlocks_per_minute: float


# Routine (work, classes, chores) stays fixed; the level changes bedtime, how far scrolling
# runs past midnight, how quiet the sleep hours are, and how often the phone is checked by day.
ADDICTION_EFFECTS = {
    "pre": AddictionEffect(
        volume=0.8, night_weight=0.7, bedtime_delay=-0.75, after_midnight=0.0,
        daytime_checking=0.0, quiet_from=1, quiet_factor=0.25, unlocks_per_minute=0.22,
    ),
    "mild": AddictionEffect(
        volume=1.1, night_weight=1.2, bedtime_delay=0.5, after_midnight=0.5,
        daytime_checking=0.04, quiet_from=3, quiet_factor=0.5, unlocks_per_minute=0.28,
    ),
    "high": AddictionEffect(
        volume=1.5, night_weight=1.8, bedtime_delay=1.5, after_midnight=1.4,
        daytime_checking=0.08, quiet_from=6, quiet_factor=1.0, unlocks_per_minute=0.36,
    ),
}
FLOOR = 0.03
CONCENTRATION = 60.0


@dataclass(frozen=True)
class SyntheticHourlyDataset:
    participants: pd.DataFrame
    hourly_usage: pd.DataFrame


def _is_night(centre: float) -> bool:
    return centre >= 21 or centre < 3


def rhythm_profile(bumps: tuple[Bump, ...], shift_hours: float = 0.0) -> np.ndarray:
    """Normalised 24-hour intensity from circular Gaussian bumps shifted by `shift_hours`."""
    weights = np.full(24, FLOOR)
    for centre, width, weight in bumps:
        distance = np.abs(HOURS - (centre + shift_hours)) % 24
        distance = np.minimum(distance, 24 - distance)
        weights = weights + weight * np.exp(-0.5 * (distance / width) ** 2)
    return weights / weights.sum()


def addiction_profile(
    bumps: tuple[Bump, ...], effect: AddictionEffect, shift_hours: float = 0.0
) -> np.ndarray:
    """Apply an addiction level to a routine: later, heavier night use and a longer tail past
    midnight, plus daytime checking and less quiet sleep hours for higher levels."""
    adjusted = tuple(
        (centre + effect.bedtime_delay, width, weight * effect.night_weight)
        if _is_night(centre)
        else (centre, width, weight)
        for centre, width, weight in bumps
    )
    if effect.after_midnight > 0:
        adjusted += ((2.0 + effect.bedtime_delay, 1.0, effect.after_midnight),)
    weights = rhythm_profile(adjusted, shift_hours)
    weights[8:20] += effect.daytime_checking / 12
    sleep_hours = np.arange(effect.quiet_from, 7)
    weights[sleep_hours] *= effect.quiet_factor
    return weights / weights.sum()


def generate_hourly_usage(
    participants: int = 300,
    days: int = 28,
    seed: int = RNG_SEED,
) -> SyntheticHourlyDataset:
    if participants < 30:
        raise ValueError("participants must be at least 30")
    if days < 7:
        raise ValueError("days must be at least 7")

    rng = np.random.default_rng(seed)
    dates = pd.date_range(START_DATE, periods=days, freq="D")
    is_weekend = dates.day_of_week >= 5
    people: list[dict[str, object]] = []
    blocks: list[pd.DataFrame] = []

    for synthetic_id in range(1, participants + 1):
        profession = PROFESSIONS[rng.choice(len(PROFESSIONS), p=PROFESSION_PROBABILITIES)]
        bands, band_p = zip(*AGE_BANDS[profession], strict=True)
        age_band = bands[rng.choice(len(bands), p=band_p)]
        addiction_level = ADDICTION_LEVELS[
            rng.choice(len(ADDICTION_LEVELS), p=ADDICTION_PROBABILITIES[profession])
        ]
        effect = ADDICTION_EFFECTS[addiction_level]
        people.append(
            {
                "synthetic_id": synthetic_id,
                "profession": profession,
                "age_band": age_band,
                "addiction_level": addiction_level,
                "source": SOURCE,
            }
        )

        shift = float(np.clip(rng.normal(0.0, 1.25), -3.0, 3.0))
        profiles = {}
        for day_type, bumps in RHYTHMS[profession].items():
            jittered = tuple(
                (centre, width, weight * float(rng.lognormal(0.0, 0.5)))
                for centre, width, weight in bumps
            )
            profiles[day_type] = addiction_profile(jittered, effect, shift)
        base_minutes = (
            DAILY_MINUTES_MEDIAN[profession] * effect.volume * float(rng.lognormal(0.0, 0.25))
        )

        minutes = np.empty((days, 24))
        for day_index, weekend in enumerate(is_weekend):
            total = base_minutes * float(rng.lognormal(0.0, 0.2))
            if weekend:
                total *= WEEKEND_MULTIPLIER[profession]
            if rng.random() < 0.05:
                total *= 0.4  # occasional light day (travel, illness, phone away)
            profile = profiles["weekend" if weekend else "weekday"]
            minutes[day_index] = np.minimum(total * rng.dirichlet(profile * CONCENTRATION), 60.0)
        minutes = np.round(minutes, 1)
        unlocks = rng.poisson(effect.unlocks_per_minute * minutes + 0.3 * (minutes > 0.5))

        blocks.append(
            pd.DataFrame(
                {
                    "synthetic_id": synthetic_id,
                    "usage_date": np.repeat(dates, 24),
                    "hour": np.tile(HOURS, days),
                    "screen_minutes": minutes.ravel(),
                    "unlock_count": unlocks.ravel(),
                }
            )
        )

    hourly = pd.concat(blocks, ignore_index=True)
    hourly["source"] = SOURCE
    return SyntheticHourlyDataset(
        participants=pd.DataFrame.from_records(people),
        hourly_usage=hourly,
    )


PARTICIPANT_COLUMNS = ["synthetic_id", "profession", "age_band", "addiction_level"]
HOURLY_COLUMNS = ["synthetic_id", "usage_date", "hour", "screen_minutes", "unlock_count"]


def write_csv(dataset: SyntheticHourlyDataset, directory: Path) -> None:
    """Write participants.csv and hourly_usage.csv in the column order of the Postgres tables."""
    directory.mkdir(parents=True, exist_ok=True)
    dataset.participants[PARTICIPANT_COLUMNS].to_csv(
        directory / "participants.csv", index=False, lineterminator="\n"
    )
    hourly = dataset.hourly_usage[HOURLY_COLUMNS].copy()
    hourly["usage_date"] = hourly["usage_date"].dt.strftime("%Y-%m-%d")
    hourly.to_csv(
        directory / "hourly_usage.csv", index=False, float_format="%.1f", lineterminator="\n"
    )


def tag_dataset(participants: pd.DataFrame, hourly: pd.DataFrame) -> SyntheticHourlyDataset:
    """Normalise types of loaded rows and tag them source=synthetic."""
    hourly = hourly.assign(
        usage_date=pd.to_datetime(hourly["usage_date"]),
        screen_minutes=hourly["screen_minutes"].astype(float),
        source=SOURCE,
    )
    return SyntheticHourlyDataset(
        participants=participants.assign(source=SOURCE), hourly_usage=hourly
    )


def read_csv(directory: Path) -> SyntheticHourlyDataset:
    return tag_dataset(
        pd.read_csv(directory / "participants.csv"),
        pd.read_csv(directory / "hourly_usage.csv"),
    )
