"""Global paired color regressors; no spatially varying or copied texture terms."""
import math
import cv2
import numpy as np


def features(rgb, family='root2'):
    x=np.asarray(rgb,np.float64)
    r,g,b=np.moveaxis(x,-1,0)
    if family=='affine': cols=[r,g,b,np.ones_like(r)]
    elif family=='root2': cols=[r,g,b,np.sqrt(np.maximum(r*g,0)),np.sqrt(np.maximum(r*b,0)),np.sqrt(np.maximum(g*b,0)),np.ones_like(r)]
    elif family=='poly2': cols=[r,g,b,r*r,g*g,b*b,r*g,r*b,g*b,np.ones_like(r)]
    elif family=='root3': cols=[r,g,b,np.sqrt(np.maximum(r*g,0)),np.sqrt(np.maximum(r*b,0)),np.sqrt(np.maximum(g*b,0)),np.cbrt(r*r*g),np.cbrt(r*r*b),np.cbrt(g*g*r),np.cbrt(g*g*b),np.cbrt(b*b*r),np.cbrt(b*b*g),np.cbrt(r*g*b),np.ones_like(r)]
    else:raise ValueError(family)
    return np.stack(cols,axis=-1)


def sample_pairs(source,reference,mask,max_samples=45000,sigma=4):
    a=cv2.GaussianBlur(source.astype(np.float32)/255,(0,0),sigma)
    b=cv2.GaussianBlur(reference.astype(np.float32)/255,(0,0),sigma)
    yy,xx=np.nonzero(mask)
    if len(yy)<500:raise ValueError('Need500fitpixels')
    index=np.linspace(0,len(yy)-1,min(max_samples,len(yy)),dtype=np.int64)
    return a[yy[index],xx[index]].astype(np.float64),b[yy[index],xx[index]].astype(np.float64)


def irls(X,Y,prior,regularizer,iterations=6,delta=.025):
    weights=np.ones(len(X))
    for _ in range(iterations):
        C=np.linalg.solve(X.T@(weights[:,None]*X)+regularizer,X.T@(weights[:,None]*Y)+regularizer@prior)
        e=np.sqrt(np.mean((X@C-Y)**2,axis=1))
        weights=np.minimum(1,delta/(e+1e-6))
    return C


def fit_polynomial(source,reference,mask,family='root2',ridge=3.,max_samples=45000,sigma=4.):
    a,b=sample_pairs(source,reference,mask,max_samples,sigma=sigma)
    X=features(a,family)
    prior=np.zeros((X.shape[1],3));prior[:3]=np.eye(3)
    reg=np.eye(X.shape[1])*ridge;reg[-1,-1]=ridge*.1
    C=irls(X,b,prior,reg)
    return {'family':family,'C':C,'ridge':ridge}


def apply_polynomial(source,model):
    h,w=source.shape[:2];out=np.empty((h,w,3),np.float32)
    for start in range(0,h,128):
        X=features(source[start:start+128].astype(np.float32)/255,model['family'])
        out[start:start+128]=np.clip(X@model['C'],0,1)
    return out


def lut_indices_weights(rgb,size):
    pos=np.clip(rgb,0,1)*(size-1)
    lo=np.floor(pos).astype(np.int32);lo=np.minimum(lo,size-2);f=pos-lo
    ii=[];ww=[]
    for dr in (0,1):
      for dg in (0,1):
       for db in (0,1):
        ii.append((lo[...,0]+dr)*size*size+(lo[...,1]+dg)*size+lo[...,2]+db)
        ww.append((f[...,0] if dr else 1-f[...,0])*(f[...,1] if dg else 1-f[...,1])*(f[...,2] if db else 1-f[...,2]))
    return np.stack(ii,-1),np.stack(ww,-1)


def lut_design(rgb,size):
    ids,weights=lut_indices_weights(rgb,size);X=np.zeros((len(rgb),size**3),np.float64)
    rows=np.arange(len(rgb))
    for k in range(8):X[rows,ids[:,k]]+=weights[:,k]
    return X


def lut_regularizer(size,smooth,ridge):
    edges=[]
    for r in range(size):
     for g in range(size):
      for b in range(size):
       i=r*size*size+g*size+b
       for axis,v in enumerate((r,g,b)):
        if 0<v<size-1:
         stride=(size*size,size,1)[axis]
         row=np.zeros(size**3);row[i-stride]=1;row[i]=-2;row[i+stride]=1
         edges.append(row)
    L=np.stack(edges)
    return smooth*(L.T@L)+ridge*np.eye(size**3)


def irls_lut_sparse(ids,interpolation,Y,prior,regularizer,iterations=6,delta=.025):
    """Exact eight-corner LUT design algebra without a dense N x 125 matrix.

    np.bincount accumulates X.T W X and X.T W Y from at most eight nonzero
    interpolation weights per sample. Only the upper triangle is accumulated;
    symmetry supplies the lower triangle. The resulting regularized solve and
    robust weights are the same as dense ``irls(lut_design(...), ...)``.
    """
    vertices=len(prior)
    ids=np.asarray(ids,np.intp)
    interpolation=np.asarray(interpolation,np.float64)
    Y=np.asarray(Y,np.float64)
    robust=np.ones(len(ids),np.float64)
    prior_rhs=regularizer@prior
    for _ in range(iterations):
        gram=np.zeros((vertices,vertices),np.float64)
        rhs=np.zeros((vertices,3),np.float64)
        for j in range(8):
            weighted=robust*interpolation[:,j]
            for k in range(j,8):
                values=np.bincount(ids[:,j]*vertices+ids[:,k],
                                   weights=weighted*interpolation[:,k],
                                   minlength=vertices*vertices).reshape(vertices,vertices)
                gram+=values
                if j!=k:gram+=values.T
            for channel in range(3):
                rhs[:,channel]+=np.bincount(ids[:,j],weights=weighted*Y[:,channel],minlength=vertices)
        C=np.linalg.solve(gram+regularizer,rhs+prior_rhs)
        prediction=(C[ids]*interpolation[:,:,None]).sum(axis=1)
        error=np.sqrt(np.mean((prediction-Y)**2,axis=1))
        robust=np.minimum(1.,delta/(error+1e-6))
    return C


def fit_lut(source,reference,mask,size=5,smooth=10.,ridge=1.,max_samples=30000,sigma=4.):
    a,b=sample_pairs(source,reference,mask,max_samples,sigma=sigma)
    # Identity prior on the color cube; smooth second differences preserve linear mappings.
    verts=np.stack(np.meshgrid(*([np.linspace(0,1,size)]*3),indexing='ij'),axis=-1).reshape(-1,3)
    ids,weights=lut_indices_weights(a,size)
    reg=lut_regularizer(size,smooth,ridge)
    C=irls_lut_sparse(ids,weights,b,verts,reg)
    return {'family':'lut','vertices':C.astype(np.float32),'size':size,'smooth':smooth,'ridge':ridge}


def apply_lut(source,model):
    h,w=source.shape[:2];out=np.empty((h,w,3),np.float32)
    for start in range(0,h,128):
        ids,weights=lut_indices_weights(source[start:start+128].astype(np.float32)/255,model['size'])
        out[start:start+128]=np.clip((model['vertices'][ids]*weights[...,None]).sum(-2),0,1)
    return out


def spatial_masks(geometric,source,ref,block=192,guard=20):
    y,x=np.indices(geometric.shape)
    group=(y//block+2*(x//block))%4
    interior=(y%block>=guard)&(y%block<block-guard)&(x%block>=guard)&(x%block<block-guard)
    non=(source.max(2)<250)&(source.min(2)>3)&(ref.max(2)<250)&(ref.min(2)>3)
    return geometric&non&interior&(group!=0), geometric&non&interior&(group==0), geometric&non


def fit_affine(source,reference,mask,sigma=4.):
    a,b=sample_pairs(source,reference,mask,max_samples=90000,sigma=sigma)
    X=features(a,'affine');prior=np.vstack([np.eye(3),np.zeros(3)])
    C=irls(X,b,prior,np.diag([20.,20.,20.,2.]))
    return {'family':'affine','C':C,'ridge_diagonal':[20.,20.,20.,2.]}


def apply_model(source,model):
    model=dict(model)
    if model['family']=='lut':
        model['vertices']=np.asarray(model['vertices'],np.float32)
        return apply_lut(source,model)
    model['C']=np.asarray(model['C'],np.float64)
    return apply_polynomial(source,model)


def serialize_model(model):
    result={k:v.tolist() if isinstance(v,np.ndarray) else v for k,v in model.items()}
    result['feature_definition']={
        'affine':['R','G','B','1'],
        'root2':['R','G','B','sqrt(RG)','sqrt(RB)','sqrt(GB)','1'],
        'poly2':['R','G','B','R^2','G^2','B^2','RG','RB','GB','1'],
        'root3':['R','G','B','sqrt(RG)','sqrt(RB)','sqrt(GB)','cbrt(R^2G)','cbrt(R^2B)','cbrt(G^2R)','cbrt(G^2B)','cbrt(B^2R)','cbrt(B^2G)','cbrt(RGB)','1'],
        'lut':['trilinear R-major / G / B vertex interpolation; RGB grid [0,1]^3'],
    }[model['family']]
    result['rgb_convention']='decoded display RGB normalized to [0,1]; row vectors; clip output to [0,1]'
    return result


def _validation_error(pred,reference,mask,sigma=8.):
    pred=np.asarray(pred,np.float32)*255
    ref=reference.astype(np.float32)
    aa=cv2.GaussianBlur(pred,(0,0),sigma);bb=cv2.GaussianBlur(ref,(0,0),sigma)
    return float(np.abs(aa-bb)[mask].mean())


def choose_model(source,reference,fitmask,block=192,guard=32,min_improvement=.02,
                 resolution_scale=1.,progress=None):
    """Guarded spatial selection followed by a full-support refit.

    Scales are expressed relative to a 1920-pixel output long edge. A Gaussian
    float32 automatic kernel has approximately four-sigma radius; the guard
    is rounded upward to at least the validation radius. Two neighboring
    block interiors are separated by twice the guard, protecting both target
    blur footprints. Selection retains the v1.1 >=2% gain and 1% simplicity
    preferences; final same-pair diagnostics remain separate from this holdout.
    """
    if not math.isfinite(resolution_scale) or resolution_scale<=0:
        raise ValueError('resolution_scale must be finite and positive')
    fit_sigma=max(.5,4.*resolution_scale)
    validation_sigma=max(.5,8.*resolution_scale)
    guard=max(1,int(math.ceil(guard*resolution_scale)),int(math.ceil(4*validation_sigma)))
    block=max(24,int(round(block*resolution_scale)),2*guard+8)
    y=np.arange(fitmask.shape[0],dtype=np.int32)[:,None]
    x=np.arange(fitmask.shape[1],dtype=np.int32)[None,:]
    group=((y//block%4).astype(np.uint8)+(2*(x//block%4)).astype(np.uint8))%4
    interior=(y%block>=guard)&(y%block<block-guard)&(x%block>=guard)&(x%block<block-guard)
    train=fitmask&interior&(group!=0);val=fitmask&interior&(group==0)
    diag={'criterion':'heldout lowpass RGB MAE (0..255)',
          'resolution_scale':float(resolution_scale),'block_px':block,'guard_px':guard,
          'fit_sigma_px':fit_sigma,'validation_sigma_px':validation_sigma,
          'training_pixels':int(train.sum()),'validation_pixels':int(val.sum()),
          'min_relative_improvement':min_improvement,'candidates':{},
          'lut_solver':'8-vertex sparse weighted normal equations; 6 IRLS iterations'}
    if train.sum()<2000 or val.sum()<1000:
        diag.update(selected='affine',reason='Insufficient guarded spatial holdout support')
        return serialize_model(fit_affine(source,reference,fitmask,sigma=fit_sigma)),diag
    indices=np.flatnonzero(train)
    selected_indices=indices[::max(1,len(indices)//10000)].copy()
    del indices
    samples=source.reshape(-1,3)[selected_indices].astype(np.float64)/255
    eig=np.linalg.eigvalsh(np.cov(samples.T));diag['color_cov_eigenvalues']=eig.tolist()
    if eig[-1]<1e-4 or eig[0]/max(eig[-1],1e-10)<1e-4:
        diag.update(selected='affine',reason='Insufficient independent color variation for nonlinear mapping')
        return serialize_model(fit_affine(source,reference,fitmask,sigma=fit_sigma)),diag
    configs=[('affine','affine',0),('root2','root2',5.),('poly2','poly2',5.),('lut5_s15','lut',15.),('lut5_s3','lut',3.)]
    def fit(family,param,mask):
        if family=='affine':return fit_affine(source,reference,mask,sigma=fit_sigma)
        if family=='lut':return fit_lut(source,reference,mask,size=5,smooth=param,ridge=1.,sigma=fit_sigma)
        return fit_polynomial(source,reference,mask,family=family,ridge=param,sigma=fit_sigma)
    for index,(name,family,param) in enumerate(configs):
        if progress is not None:progress(name,index/len(configs))
        model=fit(family,param,train)
        error=_validation_error(apply_model(source,model),reference,val,sigma=validation_sigma)
        diag['candidates'][name]={'lowpass_mae_255':error}
    score=lambda name:diag['candidates'][name]['lowpass_mae_255']
    best=min((c[0] for c in configs),key=score)
    # Ordered candidate list prefers simpler/stronger-regularized mappings.
    near=[c[0] for c in configs if score(c[0])<=score(best)*1.01]
    winner=near[0]
    gain=1-score(winner)/max(score('affine'),1e-8)
    if gain<min_improvement:winner='affine'
    _,family,param=next(c for c in configs if c[0]==winner)
    if progress is not None:progress('refit_'+winner,1.)
    model=fit(family,param,fitmask)
    diag.update(selected=winner,heldout_relative_improvement=1-score(winner)/max(score('affine'),1e-8),
                reason='Held-out improvement >= threshold; refit all reliable pixels' if winner!='affine' else 'Conservative affine fallback')
    return serialize_model(model),diag
