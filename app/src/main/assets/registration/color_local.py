"""Bounded masked Gaussian residual correction, matched to PairShot color trials.

Reference low frequencies can contain broad structure as well as color. The
mask limits fitting support; it is not a guarantee of zero target-content transfer.
"""
import math
import cv2
import numpy as np

GAUSSIAN_SIGMA_1920 = math.sqrt((4 ** 5 - 1) / 6)


def masked_gaussian_residual(source, reference, mask, sigma=GAUSSIAN_SIGMA_1920,
                             limit=.18, support_min=.10):
    """Return corrected RGB, additive field, and diagnostics.

    source is normalized float RGB; reference is uint8 RGB. To reproduce the
    tested pipeline, the caller passes the quantized global output / 255.
    Gaussian sigma is in output pixels. OpenCV's default reflect-101 boundary
    and float32 arithmetic match the offline masked Gaussian experiment.
    """
    if not isinstance(source, np.ndarray) or not np.issubdtype(source.dtype, np.floating):
        raise TypeError('source must be floating RGB in [0,1]')
    if source.ndim != 3 or source.shape[2] != 3 or min(source.shape[:2]) < 1:
        raise ValueError('source must have nonempty shape [H,W,3]')
    if not np.isfinite(source).all() or np.any(source < 0) or np.any(source > 1):
        raise ValueError('source must contain finite values in [0,1]')
    if not isinstance(reference, np.ndarray) or reference.dtype != np.uint8 or reference.shape != source.shape:
        raise ValueError('reference must be uint8 RGB matching source')
    if not isinstance(mask, np.ndarray) or mask.dtype != np.bool_ or mask.shape != source.shape[:2]:
        raise ValueError('mask must be boolean [H,W] matching source')
    for name, value in (('sigma', sigma), ('limit', limit), ('support_min', support_min)):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ValueError(name + ' must be finite and positive')
    if limit > 1 or support_min > 1:
        raise ValueError('limit and support_min must not exceed 1')

    source = source.astype(np.float32, copy=False)
    m = mask.astype(np.float32)
    residual = np.clip(reference.astype(np.float32) / 255 - source, -limit, limit)
    support = cv2.GaussianBlur(m, (0, 0), sigma)
    bias = cv2.GaussianBlur(residual * m[..., None], (0, 0), sigma)
    bias /= support[..., None] + 1e-5
    bias *= np.clip(support[..., None] / support_min, 0, 1)
    bias = np.clip(bias, -limit, limit).astype(np.float32, copy=False)
    raw = source + bias
    diagnostics = {
        'method': 'masked_gaussian_residual',
        'sigma_px': float(sigma), 'residual_limit': float(limit),
        'correction_limit': float(limit), 'support_min': float(support_min),
        'fit_fraction': float(mask.mean()),
        'clipped_output_fraction': float(((raw < 0) | (raw > 1)).any(2).mean()),
        'mean_absolute_correction_255': float(np.abs(bias).mean() * 255),
        'global_input_quantized': True,
    }
    return np.clip(raw, 0, 1).astype(np.float32, copy=False), bias, diagnostics
