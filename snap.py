"""Grab one frame: prints temperature stats, writes thermal.png (colorized, 8x) and visible.jpg."""
import io, json, sys
import numpy as np
from PIL import Image
import flirone_ios

# calib.rsc .calib.extRBF.ds120C_weRB_apNOA_fiNOF_le (built-in lens, -20..120 C range)
R, B, F, J0, J1 = 17595.15, 1435.0, 1.0, 2591.348, 80.0


def thermal_raw(block: bytes) -> np.ndarray:
    """63 Lepton VoSPI packets x 164 B (2-word id/crc + 80 px); rows 0-59 image, 60-62 telemetry."""
    pk = np.frombuffer(block[: 63 * 164], dtype="<u2").reshape(63, 82)
    return pk[:60, 2:].astype(np.float64)


def to_celsius(raw, emissivity=0.95, t_refl=20.0):
    sig = raw * 4.0
    sig_refl = J1 * R / (np.exp(B / (t_refl + 273.15)) - F) + J0
    sig_obj = (sig - (1 - emissivity) * sig_refl) / emissivity
    return B / np.log(J1 * R / (sig_obj - J0) + F) - 273.15


def colorize(t):
    lo, hi = np.percentile(t, 1), np.percentile(t, 99)
    x = np.clip((t - lo) / max(hi - lo, 1e-6), 0, 1)
    # "iron"-like ramp: black -> purple -> red -> yellow -> white
    stops = np.array([[0, 0, 0], [90, 0, 140], [220, 30, 30], [255, 200, 0], [255, 255, 255]], float)
    pos = x * (len(stops) - 1)
    i = np.minimum(pos.astype(int), len(stops) - 2)
    f = (pos - i)[..., None]
    return (stops[i] * (1 - f) + stops[i + 1] * f).astype(np.uint8)


if __name__ == "__main__":
    cam = flirone_ios.FlirOne()
    frames = cam.frames()
    for _ in range(int(sys.argv[1]) if len(sys.argv) > 1 else 15):  # let FFC settle
        f = next(frames)
    cam.close()
    t = to_celsius(thermal_raw(f["thermal"]))
    y, x = np.unravel_index(np.argmax(t), t.shape)
    print(json.dumps(dict(min_c=round(float(t.min()), 1), max_c=round(float(t.max()), 1),
                          center_c=round(float(t[30, 40]), 1), hottest_xy=[int(x), int(y)],
                          status=f["status"]), indent=1))
    Image.fromarray(colorize(t)).resize((640, 480), Image.BICUBIC).save("thermal.png")
    Image.open(io.BytesIO(f["jpeg"])).save("visible.jpg")
