#!/usr/bin/env python3
"""
stitch.py — convert Samsung Gear 360 (2016) dual-fisheye media to
equirectangular 360 photos/videos, ready for YouTube / Facebook / a headset.

Why this exists: the camera saves *unstitched* dual-fisheye files. Samsung's
discontinued app used to do the stitching. This script does it with ffmpeg's
v360 filter instead, then tags the output as real 360 media.

Usage:
    python3 tools/stitch.py INPUT [OUTPUT] [--fov 200] [--width 7680]

    INPUT   .jpg/.jpeg photo or .mp4 video copied off the camera (microSD,
            USB, or downloaded with the Gear 360 Revive app's Gallery tab)
    OUTPUT  optional; defaults to INPUT_stitched.<ext> next to the input
    --fov   fisheye field of view in degrees. Gear 360 lenses are ~200°.
            If the seam looks pinched/torn, try 190-210.
    --width equirectangular output width (photos default 7680, video 3840)

Requirements:
    - ffmpeg (with the v360 filter, i.e. ffmpeg >= 4.3) on PATH
    - exiftool on PATH for photo GPano metadata (optional but recommended;
      without it the script prints the exact exiftool command to run)

Notes:
    - A straight v360 projection seam will never be as polished as Samsung's
      blending was; keep important subjects away from the seam (the sides of
      the camera) when shooting.
    - Videos: YouTube needs spherical metadata in the MP4. ffmpeg writes the
      equirectangular projection tag; if YouTube still doesn't detect 360,
      run Google's "Spatial Media Metadata Injector" over the output once.
"""
import argparse
import shutil
import subprocess
import sys
from pathlib import Path

PHOTO_EXTS = {".jpg", ".jpeg"}
VIDEO_EXTS = {".mp4", ".mov"}


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", type=Path)
    ap.add_argument("output", type=Path, nargs="?")
    ap.add_argument("--fov", type=int, default=200, help="fisheye FOV in degrees (default 200)")
    ap.add_argument("--width", type=int, default=0, help="output width (default: 7680 photo / 3840 video)")
    args = ap.parse_args()

    src: Path = args.input
    if not src.exists():
        print(f"Input not found: {src}", file=sys.stderr)
        return 1
    if shutil.which("ffmpeg") is None:
        print("ffmpeg not found on PATH. Install it first (e.g. `sudo apt install ffmpeg`).", file=sys.stderr)
        return 1

    ext = src.suffix.lower()
    is_photo = ext in PHOTO_EXTS
    if not is_photo and ext not in VIDEO_EXTS:
        print(f"Unsupported file type: {ext} (expected .jpg photo or .mp4 video)", file=sys.stderr)
        return 1

    out = args.output or src.with_name(src.stem + "_stitched" + src.suffix)
    width = args.width or (7680 if is_photo else 3840)
    height = width // 2

    vfilter = (
        f"v360=input=dfisheye:output=e:ih_fov={args.fov}:iv_fov={args.fov}"
        f":w={width}:h={height}:interp=lanczos"
    )

    try:
        if is_photo:
            run(["ffmpeg", "-y", "-i", str(src), "-vf", vfilter,
                 "-frames:v", "1", "-q:v", "2", str(out)])
        else:
            run(["ffmpeg", "-y", "-i", str(src), "-vf", vfilter,
                 "-c:v", "libx264", "-preset", "medium", "-crf", "18",
                 "-c:a", "copy",
                 "-metadata:s:v:0", "title=Equirectangular 360",
                 str(out)])
    except subprocess.CalledProcessError as e:
        print(f"ffmpeg failed (exit {e.returncode}).", file=sys.stderr)
        return e.returncode

    print(f"\nWrote {out}")

    if is_photo:
        gpano_cmd = [
            "exiftool", "-overwrite_original",
            '-ProjectionType="equirectangular"',
            "-UsePanoramaViewer=True",
            f"-CroppedAreaImageWidthPixels={width}",
            f"-CroppedAreaImageHeightPixels={height}",
            f"-FullPanoWidthPixels={width}",
            f"-FullPanoHeightPixels={height}",
            "-CroppedAreaLeftPixels=0", "-CroppedAreaTopPixels=0",
            "-InitialViewHeadingDegrees=0",
            str(out),
        ]
        if shutil.which("exiftool"):
            try:
                run(gpano_cmd)
                print("GPano 360 metadata written.")
            except subprocess.CalledProcessError:
                print("exiftool failed; run this manually:\n  " + " ".join(gpano_cmd))
        else:
            print(
                "\nexiftool not found, so the photo has no GPano metadata yet.\n"
                "Install exiftool, or run this once to tag it:\n  " + " ".join(gpano_cmd)
            )
    else:
        print(
            "If YouTube/Facebook does not recognise the video as 360, run it once\n"
            "through Google's Spatial Media Metadata Injector (spherical metadata\n"
            "box) — ffmpeg's projection tag alone is sometimes not enough."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
