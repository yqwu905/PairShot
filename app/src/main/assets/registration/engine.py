#!/usr/bin/env python3
"""Main-camera -> telephoto registration. Pixels are EXIF-upright RGB.
Classical CPU baseline; no generated pixels or telephoto high-frequency fusion.
"""
import json, time, gc
from io import BytesIO
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageOps
from color_global import choose_model, apply_model
from color_local import masked_gaussian_residual, GAUSSIAN_SIGMA_1920

ALGORITHM_VERSION = 'color-v2'


def read_rgb(path):
    with Image.open(path) as im:
        return np.asarray(ImageOps.exif_transpose(im).convert('RGB')).copy()


def gray(a):
    return cv2.cvtColor(a, cv2.COLOR_RGB2GRAY)


def blur(a, sigma):
    return cv2.GaussianBlur(a, (0, 0), sigma)


def save(path, a):
    a=np.clip(np.rint(a),0,255).astype(np.uint8)
    Image.fromarray(a).save(path,compress_level=3)


def rootsift(a):
    kp, d = cv2.SIFT_create(nfeatures=8000, contrastThreshold=.012, edgeThreshold=15).detectAndCompute(a, None)
    if d is None:
        raise ValueError('Insufficient image texture for SIFT')
    d=np.sqrt(d/(d.sum(1,keepdims=True)+1e-8)).astype(np.float32)
    return kp,d


def coarse_homography(src, ref):
    ks,ds=rootsift(gray(src)); matcher=cv2.BFMatcher(cv2.NORM_L2)
    candidates=[]
    for ix,s in enumerate([.35,.5,.75,1.]):
        notify('features',f'匹配图像特征 · {ix+1}/4',18+ix*6)
        small=cv2.resize(gray(ref),None,fx=s,fy=s,interpolation=cv2.INTER_AREA)
        try: kt,dt=rootsift(small)
        except ValueError: continue
        forward=matcher.knnMatch(ds,dt,k=2)
        reverse=matcher.match(dt,ds)
        good=[a for pair in forward if len(pair)==2 for a,b in [pair]
              if a.distance<.80*b.distance and reverse[a.trainIdx].trainIdx==a.queryIdx]
        if len(good)<12: continue
        p=np.float32([ks[m.queryIdx].pt for m in good]); q=np.float32([kt[m.trainIdx].pt for m in good])
        q=(q+.5)/np.array([small.shape[1]/ref.shape[1],small.shape[0]/ref.shape[0]])-.5
        q=q.astype('float32')
        H,inside=cv2.findHomography(p,q,cv2.USAC_MAGSAC,4.0,maxIters=10000,confidence=.999)
        if H is None: continue
        inside=inside.ravel().astype(bool)
        hull=cv2.convexHull(q[inside]); coverage=cv2.contourArea(hull)/(ref.shape[0]*ref.shape[1])
        score=int(inside.sum())*np.sqrt(max(coverage,.01))
        candidates.append((score,H,p,q,inside,s,coverage))
    if not candidates: raise ValueError('可靠特征匹配不足。请确认两张图片拍摄相同场景，并包含清晰、重叠的纹理。')
    _,H,p,q,inside,s,cov=max(candidates,key=lambda v:v[0])
    if inside.sum()<12 or cov<.01: raise ValueError('匹配点覆盖区域太小，无法可靠配准。请更换视场重叠更多的图片。')
    pred=cv2.perspectiveTransform(p[None],H)[0]; errors=np.linalg.norm(pred-q,axis=1)
    return H,{'sift_matches':len(p),'ransac_inliers':int(inside.sum()),'inlier_hull_fraction':float(cov),
              'selected_reference_scale':s,'sift_inlier_median_error_px':float(np.median(errors[inside])),
              'sift_inlier_p90_error_px':float(np.quantile(errors[inside],.9))},p,q,inside


def grid(h,w):
    x,y=np.meshgrid(np.arange(w,dtype=np.float32),np.arange(h,dtype=np.float32)); return x,y


def compose_map(H, x, y):
    inv=np.linalg.inv(H); den=inv[2,0]*x+inv[2,1]*y+inv[2,2]
    return ((inv[0,0]*x+inv[0,1]*y+inv[0,2])/den).astype('float32'), ((inv[1,0]*x+inv[1,1]*y+inv[1,2])/den).astype('float32')


def warp(src,mx,my):
    return cv2.remap(src,mx,my,cv2.INTER_CUBIC,borderMode=cv2.BORDER_CONSTANT)


def inbounds(mx,my,src):
    return (mx>=2)&(my>=2)&(mx<src.shape[1]-3)&(my<src.shape[0]-3)


def fit_color(a,b,mask,sigma=4.):
    """Robust affine RGB model, fitted at matched low spatial frequencies."""
    aa=blur(a.astype(np.float32)/255.,sigma);bb=blur(b.astype(np.float32)/255.,sigma)
    sample=mask.copy()
    yy,xx=np.nonzero(sample)
    idx=np.arange(0,len(yy),max(1,len(yy)//90000)); yy,xx=yy[idx],xx[idx]
    if len(yy)<500: raise ValueError('Insufficient pixels to fit color')
    X=np.column_stack([aa[yy,xx],np.ones(len(yy))]).astype(np.float64);Y=bb[yy,xx].astype(np.float64)
    C0=np.vstack([np.eye(3),np.zeros(3)]); C=C0.copy();w=np.ones(len(X))
    ridge=np.diag([20.,20.,20.,2.])
    for _ in range(6):
        C=np.linalg.solve(X.T@(X*w[:,None])+ridge,X.T@(Y*w[:,None])+ridge@C0)
        e=np.sqrt(((X@C-Y)**2).mean(1));w=np.minimum(1.,.025/(e+1e-6))
    return C


def apply_color(a,C):
    return np.clip((a.astype(np.float32)/255.)@C[:3]+C[3],0,1).astype(np.float32)


def flow_pair(ref,coarse,scale_px):
    # Blur target to approximately match the bandwidth of enlarged main image.
    g0=blur(gray(ref),max(.8,.55*scale_px)); g1=blur(gray(coarse),.8)
    # A shared image-pair contrast stretch helps dark scenes without independent CLAHE artifacts.
    lo=min(np.percentile(g0,1),np.percentile(g1,1));hi=max(np.percentile(g0,99),np.percentile(g1,99))
    g0=np.clip((g0.astype(float)-lo)*255/max(hi-lo,20),0,255).astype('uint8')
    g1=np.clip((g1.astype(float)-lo)*255/max(hi-lo,20),0,255).astype('uint8')
    def calc(a,b):
        dis=cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
        dis.setFinestScale(2);dis.setGradientDescentIterations(35)
        dis.setVariationalRefinementAlpha(60.)
        dis.setVariationalRefinementIterations(10);dis.setUseMeanNormalization(True)
        f=dis.calc(a,b,None)
        return blur(f,1.2)
    notify('flow','计算正向局部形变',46)
    f=calc(g0,g1)
    notify('flow','计算反向一致性',57)
    return f,calc(g1,g0)


def image_metrics(a,b,mask):
    if not mask.any():return None
    a=a.astype(np.float32);b=b.astype(np.float32);d=(a-b)[mask]
    mse=float(np.mean(d*d)); low=(blur(a,8)-blur(b,8))[mask]
    # CIELAB Euclidean DeltaE76 proxy, not calibrated colorimetry.
    la=cv2.cvtColor(a/255.,cv2.COLOR_RGB2LAB);lb=cv2.cvtColor(b/255.,cv2.COLOR_RGB2LAB)
    de=np.linalg.norm((blur(la,8)-blur(lb,8))[mask],axis=1)
    return {'rgb_mae_255':float(np.abs(d).mean()),'psnr_db':float(10*np.log10(255**2/max(mse,1e-8))),
            'lowpass_mae_255':float(np.abs(low).mean()),'lowpass_deltaE76_mean':float(de.mean())}


def patch_shift(a,b,mask,step=96,window=96):
    """Diagnostic only: local phase correlation, not ground-truth EPE."""
    a=blur(gray(a).astype('float32'),2);b=blur(gray(b).astype('float32'),2)
    h,w=a.shape;out=[];hann=cv2.createHanningWindow((window,window),cv2.CV_32F)
    for y in range(32,h-window,step):
        for x in range(32,w-window,step):
            m=mask[y:y+window,x:x+window]
            aa=a[y:y+window,x:x+window];bb=b[y:y+window,x:x+window]
            if m.mean()<.95 or np.std(bb)<7:continue
            shift,response=cv2.phaseCorrelate(aa.copy(),bb.copy(),hann)
            if response>.25 and np.linalg.norm(shift)<20:
                out.append([x+window/2,y+window/2,*shift,response])
    return np.array(out).reshape(-1,5)


def notify(stage,label,progress):
    callback=globals().get('_progress')
    if callback is not None: callback(stage,label,int(progress))


def prepare_image(path,limit):
    with Image.open(path) as im:
        original=[im.width,im.height]
        orientation=im.getexif().get(274,1)
        if orientation in [5,6,7,8]: original=original[::-1]
        if im.width*im.height>60000000:
            raise ValueError('图片超过6000万像素，请先缩小后再试。')
        # JPEG draft limits decode memory before EXIF normalization.
        im.draft('RGB',(limit,limit))
        im=ImageOps.exif_transpose(im).convert('RGB')
        if max(im.size)>limit:
            ratio=limit/max(im.size)
            im=im.resize((max(1,round(im.width*ratio)),max(1,round(im.height*ratio))),Image.Resampling.LANCZOS)
        if min(im.size)<128:
            raise ValueError('图像短边至少需要128像素。')
        return np.asarray(im).copy(),original


def run_browser(main_path,tele_path,options_json='{}'):
    """Return serialized metadata; PNGs are written to /output for transfer."""
    start=time.perf_counter();options=json.loads(options_json)
    max_side=int(options.get('maxSide',1440))
    if max_side not in [1024,1440,1920]:max_side=1440
    with_local=bool(options.get('localColor',True));with_flow=bool(options.get('flowRefine',True))
    cv2.setNumThreads(1);cv2.setRNGSeed(7)
    notify('decode','读取图片并校正拍摄方向',12)
    # Preserve more source detail than target preview: current 1920px source is untouched.
    src,source_original=prepare_image(main_path,3072)
    ref,reference_original=prepare_image(tele_path,max_side)
    h,w=ref.shape[:2];x,y=grid(h,w)
    H,stats,p,q,inside=coarse_homography(src,ref)
    if not np.isfinite(H).all() or abs(np.linalg.det(H))<1e-12:
        raise ValueError('估计的全局变换不稳定，请更换重叠区域更大的图片。')
    notify('homography','建立全局坐标映射',42)
    mx0,my0=compose_map(H,x,y);valid0=inbounds(mx0,my0,src)
    if valid0.mean()<.05:raise ValueError('目标视场与主摄的有效重叠不足5%，无法配准。')
    coarse=warp(src,mx0,my0)
    cx,cy=w//2,h//2
    inv_j=np.array([[mx0[cy,cx+1]-mx0[cy,cx],mx0[cy+1,cx]-mx0[cy,cx]],
                    [my0[cy,cx+1]-my0[cy,cx],my0[cy+1,cx]-my0[cy,cx]]])
    scale_px=float(1/np.sqrt(max(abs(np.linalg.det(inv_j)),1e-12)))
    Cinit=fit_color(coarse,ref,valid0)
    pre=np.rint(apply_color(coarse,Cinit)*255).astype('uint8')
    if with_flow:F,B=flow_pair(ref,pre,scale_px)
    else:F=np.zeros((h,w,2),np.float32);B=F.copy()
    notify('geometry','检查局部形变与有效区域',68)
    ux=x+F[:,:,0];uy=y+F[:,:,1]
    back=cv2.remap(B,ux,uy,cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
    fb=np.linalg.norm(F+back,axis=2);mag=np.linalg.norm(F,axis=2)
    fy0,fx0=np.gradient(F[:,:,0]);fy1,fx1=np.gradient(F[:,:,1]);jac=(1+fx0)*(1+fy1)-fy0*fx1
    mx,my=compose_map(H,ux,uy);valid=inbounds(mx,my,src)&(ux>=2)&(uy>=2)&(ux<w-3)&(uy<h-3)
    if not valid.any():raise ValueError('局部变换没有有效重叠区域。')
    geom=warp(src,mx,my)
    frob=(1+fx0)**2+fy0**2+fx1**2+(1+fy1)**2
    disc=np.sqrt(np.maximum(frob*frob-4*jac*jac,0))
    svmax=np.sqrt(np.maximum((frob+disc)/2,0));svmin=np.sqrt(np.maximum((frob-disc)/2,0))
    resolution_scale=max(h,w)/1920.
    fb_limit=max(.8,1.5*resolution_scale);max_flow=64*resolution_scale
    geometric=valid&(fb<fb_limit)&(mag<max_flow)&(jac>.45)&(jac<2.2)&(svmin>.65)&(svmax<1.55)
    geometric=cv2.erode(geometric.astype('uint8'),np.ones((5,5),np.uint8)).astype(bool)
    nonsat=(ref.max(2)<250)&(ref.min(2)>3)&(geom.max(2)<250)&(geom.min(2)>3)
    fitmask=geometric&nonsat
    warnings=[]
    if fitmask.sum()<500:
        raise ValueError('可靠且未过曝的匹配区域不足，无法进行可信的亮色校正。请使用更清晰或视差更小的图片。')
    notify('color','比较全局非线性颜色模型',73)
    def color_progress(label, fraction):
        text='拟合选中的全局颜色映射' if label.startswith('refit_') else f'比较全局颜色模型 · {1+round(fraction*5)}/5'
        notify('color',text,73+int(8*min(1,max(0,fraction))))
    model,selection=choose_model(geom,ref,fitmask,resolution_scale=resolution_scale,
                                 progress=color_progress)
    global_rgb=apply_model(geom,model)
    # Offline candidate comparison started from the saved 8-bit global result.
    # Match that rounding here so the local mode has the same input contract.
    glob=np.rint(np.clip(global_rgb,0,1)*255).astype('uint8')
    global_rgb=glob.astype(np.float32)/255.
    local_sigma=max(.5,GAUSSIAN_SIGMA_1920*resolution_scale)
    local_diag={'method':'disabled','global_input_quantized':True,
                'clipped_output_fraction':0.0}
    if with_local:
        notify('color','估计可信区域的低频颜色补偿',82)
        full,bias,local_diag=masked_gaussian_residual(global_rgb,ref,fitmask,
                                                     sigma=local_sigma,limit=.18,support_min=.10)
    else:full=global_rgb
    colored=np.rint(full*255).astype('uint8')
    g=gray(ref).astype('float32')/255.;std=np.sqrt(np.maximum(blur(g*g,4)-blur(g,4)**2,0))
    structure=np.abs(blur(gray(colored).astype('float32'),4)-blur(gray(ref).astype('float32'),4))/255.
    trusted=geometric&(std>.012)&(structure<.10)&nonsat
    common=cv2.erode((valid0&valid).astype('uint8'),np.ones((17,17),np.uint8)).astype(bool)
    if common.sum()<500:raise ValueError('共同有效区域不足，不能可靠统计结果。')
    notify('evaluate','统计同一区域的处理前后误差',88)
    stages={'homography':coarse,'dense_geometry':geom,'global_color':glob,'local_color':colored}
    stage_metrics={name:image_metrics(a,ref,common) for name,a in stages.items()}
    geometric_common=common&geometric
    geometric_metrics={name:image_metrics(a,ref,geometric_common) for name,a in stages.items()}
    if geometric.mean()<.5:warnings.append('几何筛选通过面积不足50%；请重点检查遮挡、边界和小字。')
    if not with_flow:warnings.append('当前关闭局部配准；几何掩码没有经过双向光流一致性验证。')
    if with_local:warnings.append('局部调色使用长焦低频信息；严格LQ/GT训练可使用纯几何或全局调色版本。')
    if not geometric_common.any():warnings.append('几何筛选区域与公共区域没有交集；该区域的误差指标不可用。')
    if local_diag['clipped_output_fraction']>.01:warnings.append('局部校正后超过1%的像素触及RGB色域边界；请检查高光和饱和色。')
    stats.update({'source_original_wh':source_original,'source_processing_wh':[src.shape[1],src.shape[0]],
        'reference_original_wh':reference_original,'output_wh':[w,h],
        'effective_center_magnification':scale_px,'H_main_to_tele':H.tolist(),
        'color_RGB_affine_4x3':model.get('C') if model['family']=='affine' else None,
        'global_color_model':model,'global_color_selection':selection,'local_color_diagnostics':local_diag,
        'common_support_fraction':float(common.mean()),'geometric_heuristic_fraction':float(geometric.mean()),
        'trusted_textured_fraction':float(trusted.mean()),'flow_median_px':float(np.median(mag[valid])),
        'flow_p95_px':float(np.quantile(mag[valid],.95)),'fb_median_px':float(np.median(fb[valid])),
        'metrics_common_support':stage_metrics,'metrics_geometric_common':geometric_metrics,
        'geometric_common_fraction':float(geometric_common.mean()),
        'metrics_interpretation':'same-pair fitted diagnostics; fixed masks, not held-out generalization or calibrated colorimetry',
        'warnings':warnings,
        'parameters':{'maxSide':max_side,'localColor':with_local,'flowRefine':with_flow,'max_flow_px':max_flow,
                      'fb_threshold_px':fb_limit,'jacobian_range':[.45,2.2],'residual_singular_value_range':[.65,1.55],
                      'flow_finest_scale':2,'flow_refinement_alpha':60.,'local_color_sigma_px':local_sigma,
                      'local_color_method':'masked_gaussian_residual' if with_local else 'disabled',
                      'local_color_limit':.18,'local_color_support_min':.10,
                      'color_resolution_scale':resolution_scale,'evaluation_lowpass_sigma_px':8.},
        'algorithm_version':ALGORITHM_VERSION,
        'engine':'Pyodide / OpenCV '+cv2.__version__,'geometry_accuracy_claim':False})
    notify('encode','生成可下载的结果图片',94)
    output=Path('/output');output.mkdir(exist_ok=True)
    images={'reference':ref,'homography':coarse,'geometry':geom,'global':glob,'aligned':colored,
            'mask':geometric.astype('uint8')*255,'training':trusted.astype('uint8')*255}
    paths={}
    for name,a in images.items():
        path=output/(name+'.png');save(path,a);paths[name]=str(path)
    stats['seconds']=time.perf_counter()-start
    result=json.dumps({'metrics':stats,'paths':paths},ensure_ascii=False,allow_nan=False)
    notify('done','配准完成',100)
    return result
