"""Numerical regression checks for the browser's bounded global color fit."""
from pathlib import Path
import sys
import unittest
import numpy as np

DIST=Path(__file__).resolve().parents[2]/'app/src/main/assets/registration'
sys.path.insert(0,str(DIST))
import color_global as global_color


class SparseColorTests(unittest.TestCase):
    def test_sparse_irls_matches_dense_with_outliers(self):
        """Dense design is an independent algebraic reference for the fast path."""
        rng=np.random.default_rng(7)
        rgb=rng.uniform(0,1,(913,3))
        # Include exact cube faces/corners, where some interpolation weights vanish.
        rgb[:8]=np.array([[r,g,b] for r in (0.,1.) for g in (0.,1.) for b in (0.,1.)])
        target=np.clip(rgb**np.array([.8,1.1,.9])+.02*rng.normal(size=rgb.shape),0,1)
        target[::11]=rng.uniform(0,1,target[::11].shape)
        size=5
        prior=np.stack(np.meshgrid(*([np.linspace(0,1,size)]*3),indexing='ij'),-1).reshape(-1,3)
        regularizer=global_color.lut_regularizer(size,3.,1.)
        X=global_color.lut_design(rgb,size)
        dense=global_color.irls(X,target,prior,regularizer)
        ids,weights=global_color.lut_indices_weights(rgb,size)
        sparse=global_color.irls_lut_sparse(ids,weights,target,prior,regularizer)
        np.testing.assert_allclose(sparse,dense,atol=2e-11,rtol=2e-11)

    def test_fit_lut_matches_dense_saved_image_estimator(self):
        rng=np.random.default_rng(17)
        source=rng.integers(0,256,(64,64,3),dtype=np.uint8)
        target=np.rint(np.clip((source/255.)**.85*255,0,255)).astype(np.uint8)
        mask=np.ones(source.shape[:2],bool);mask[::7]=False
        model=global_color.fit_lut(source,target,mask,max_samples=1800,sigma=.7)
        a,b=global_color.sample_pairs(source,target,mask,1800,sigma=.7)
        prior=np.stack(np.meshgrid(*([np.linspace(0,1,5)]*3),indexing='ij'),-1).reshape(-1,3)
        expected=global_color.irls(global_color.lut_design(a,5),b,prior,
                                   global_color.lut_regularizer(5,10.,1.)).astype(np.float32)
        np.testing.assert_allclose(model['vertices'],expected,atol=1e-7,rtol=1e-7)

    def test_scaled_holdout_guard_and_affine_fallback(self):
        # No independent color variation: no nonlinear mapping is justified.
        source=np.full((256,384,3),100,np.uint8)
        reference=np.full_like(source,110)
        mask=np.ones(source.shape[:2],bool)
        for scale in (1.,.533333333333,.01):
            model,diag=global_color.choose_model(source,reference,mask,resolution_scale=scale)
            self.assertEqual(model['family'],'affine')
            self.assertEqual(diag['selected'],'affine')
            self.assertGreaterEqual(diag['guard_px'],4*diag['validation_sigma_px'])
            self.assertGreater(diag['block_px'],2*diag['guard_px'])
            self.assertTrue(np.isfinite(global_color.apply_model(source,model)).all())
        _,diag=global_color.choose_model(source,reference,mask)
        self.assertEqual((diag['block_px'],diag['guard_px']),(192,32))
        self.assertEqual((diag['fit_sigma_px'],diag['validation_sigma_px']),(4.,8.))


if __name__=='__main__':unittest.main()
