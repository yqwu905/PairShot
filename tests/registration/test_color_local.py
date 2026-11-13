"""Behavioral invariants for the selected bounded local correction."""
from pathlib import Path
import sys
import unittest
import cv2
import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'app/src/main/assets/registration'))
from color_local import masked_gaussian_residual, GAUSSIAN_SIGMA_1920
from engine import image_metrics


class LocalColorTests(unittest.TestCase):
    def test_identical_pair_and_empty_support_preserve_source(self):
        rgb=np.random.default_rng(8).integers(0,256,(73,97,3),dtype=np.uint8)
        source=rgb.astype(np.float32)/255
        for target,mask in ((rgb,np.ones((73,97),bool)),
                            (255-rgb,np.zeros((73,97),bool))):
            output,field,diag=masked_gaussian_residual(source,target,mask)
            np.testing.assert_array_equal(output,source)
            np.testing.assert_array_equal(field,np.zeros_like(source))
            self.assertEqual(diag['clipped_output_fraction'],0)

    def test_large_color_offset_is_bounded_before_and_after_smoothing(self):
        source=np.full((64,64,3),.2,np.float32)
        target=np.full((64,64,3),255,np.uint8)
        output,field,diag=masked_gaussian_residual(source,target,np.ones((64,64),bool))
        # A uniform residual above the cap should yield only the capped bias.
        np.testing.assert_allclose(field,.18/(1+1e-5),atol=1e-6,rtol=0)
        self.assertLessEqual(float(field.max()),.18)
        np.testing.assert_allclose(output,source+field,atol=1e-7,rtol=0)

    def test_masked_target_structure_cannot_fit_from_zero_support(self):
        source=np.full((192,192,3),.4,np.float32)
        reference=np.full((192,192,3),102,np.uint8)
        reference[:,80:112]=200
        mask=np.ones((192,192),bool);mask[:,64:128]=False
        output,field,_=masked_gaussian_residual(source,reference,mask,sigma=4)
        np.testing.assert_array_equal(field,np.zeros_like(source))
        np.testing.assert_array_equal(output,source)

    def test_output_clipping_diagnostic_and_empty_metrics(self):
        source=np.full((64,64,3),.99,np.float32);source[32,32]=.5
        reference=np.full((64,64,3),255,np.uint8)
        output,field,diag=masked_gaussian_residual(source,reference,np.ones((64,64),bool),sigma=4)
        self.assertGreater(diag['clipped_output_fraction'],0)
        self.assertLessEqual(float(output.max()),1.)
        self.assertIsNone(image_metrics(reference,reference,np.zeros((64,64),bool)))
        self.assertAlmostEqual(GAUSSIAN_SIGMA_1920,13.0575648572,places=9)


if __name__=='__main__':unittest.main()
