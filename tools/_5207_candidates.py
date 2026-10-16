#!/usr/bin/env python3
"""Compare 5207 fix candidates: keep original character, tame the wash via a
highlight shoulder (+ optional lift-keep blend). Validate on a DARK set (DNG)
and a BRIGHT synthetic set (DNG scaled up) so we see both ends."""
import json, glob, sys
import numpy as np
from PIL import Image
sys.path.insert(0, "tools"); sys.path.insert(0, "tools/stylefit")
import profile_renderer as R
from stylefit import lut3d as L3

def s2l(c):
    c = np.asarray(c, np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def l2s(c):
    c = np.clip(np.asarray(c, np.float32), 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * (c ** (1/2.4)) - 0.055)
def luma(rgb):
    return 0.2126*rgb[...,0]+0.7152*rgb[...,1]+0.0722*rgb[...,2]
def lstar(rgb):
    y=luma(s2l(np.clip(rgb,0,1))); fy=np.where(y>0.008856,np.cbrt(y),7.787*y+16/116); return 116*fy-16

# ---- build candidates from the AUTHENTIC original (backup) ----------------
prof = json.load(open("dist/profiles/_luma_neutral_backup/柯达5207.json", encoding="utf-8"))
orig = R._decode_lut(prof["color_lut"])
n = orig.shape[0]
ax = np.linspace(0,1,n,dtype=np.float32)
Bg,Gg,Rg = np.meshgrid(ax,ax,ax,indexing="ij")
inp = np.stack([Rg,Gg,Bg],-1).astype(np.float32)
lin_in = s2l(inp); lin_out = s2l(np.clip(orig,0,1))
Lin = luma(lin_in); Lout = luma(lin_out)

def build(k, shoulder):
    # Keep fraction k of the film LUT's transformation in LINEAR light
    # (k=1 -> original/washy, k=0 -> identity). This preserves the film's
    # colour + tonal character at strength k, instead of re-amplifying the
    # lift (the old kk = tgt/Lin multiply made brightened output even brighter).
    blended = (1.0 - k) * lin_in + k * lin_out
    if shoulder > 0:
        s = float(shoulder)
        Lb = luma(blended)
        m = Lb > s
        if np.any(m):
            # hue-preserving highlight SHOULDER: compress luma above s back down
            comp = s + (1.0 - s) * np.clip((Lb[m] - s) / (1.0 - s), 0.0, 1.0) ** 2.4
            f = comp / Lb[m]
            blended[m] = blended[m] * f[..., None]
    return np.clip(l2s(np.clip(blended, 0, 1)), 0, 1).astype(np.float32)

cands = {
    "orig(no fix)":    build(1.0, 0.0),
    "shoulder_only":   build(1.0, 0.82),
    "k0.7+sh":         build(0.7, 0.82),
    "k0.6+sh":         build(0.6, 0.82),
}

# ---- stats helper ---------------------------------------------------------
def stats(lut, imgs):
    nat=[]; ren=[]
    for im in imgs:
        nat.append(im); ren.append(np.clip(L3.sample(lut, im),0,1))
    nat=np.concatenate([x.reshape(-1,3) for x in nat])
    ren=np.concatenate([x.reshape(-1,3) for x in ren])
    ln=luma(s2l(nat)); lr=luma(s2l(ren))
    clip=(lr>0.98).mean()*100
    return dict(meanL=lr.mean(), p50=np.percentile(lr,50), p90=np.percentile(lr,90),
               dark=(lr<0.1).mean()*100, clip=clip)

def stats_native(imgs):
    nat=np.concatenate([x.reshape(-1,3) for x in imgs])
    ln=luma(s2l(nat))
    return dict(meanL=ln.mean(), p50=np.percentile(ln,50), p90=np.percentile(ln,90),
               dark=(ln<0.1).mean()*100, clip=(ln>0.98).mean()*100)

plain = sorted(glob.glob("D:/下载/DNG原图检测图/未命名导出/*"))[:20]
def load(p, ms):
    im=Image.open(p).convert("RGB"); m=max(im.size)
    if m>ms: s=ms/m; im=im.resize((max(1,int(im.size[0]*s)),max(1,int(im.size[1]*s))),Image.Resampling.LANCZOS)
    return np.asarray(im,np.float32)/255.0
dark = [load(p,800) for p in plain]
bright = [np.clip(im*2.3,0,1) for im in dark]   # simulate a bright phone photo

print("=== DARK set (DNG) ===")
print(f"  {'native':14s} " + " ".join(f"{k}={v:.3f}" for k,v in stats_native(dark).items()))
for name,lut in cands.items():
    s=stats(lut,dark)
    print(f"  {name:14s} meanL={s['meanL']:.3f} p50={s['p50']:.3f} p90={s['p90']:.3f} "
          f"dark%={s['dark']:5.2f} clip%={s['clip']:.2f}")
print("=== BRIGHT synthetic (DNG×2.3) ===")
print(f"  {'native':14s} " + " ".join(f"{k}={v:.3f}" for k,v in stats_native(bright).items()))
for name,lut in cands.items():
    s=stats(lut,bright)
    print(f"  {name:14s} meanL={s['meanL']:.3f} p50={s['p50']:.3f} p90={s['p90']:.3f} "
          f"dark%={s['dark']:5.2f} clip%={s['clip']:.2f}")

# ---- panels: dark native vs each candidate on 3 images -------------------
sel=dark[:3]
for name,lut in cands.items():
    cols=[]
    for im in sel:
        ims = np.asarray(Image.fromarray((im*255).astype('uint8')).resize((360,360),Image.Resampling.LANCZOS),np.float32)/255
        ap=np.asarray(Image.fromarray((np.clip(L3.sample(lut,im),0,1)*255).astype('uint8')).resize((360,360),Image.Resampling.LANCZOS),np.float32)/255
        cols.append(np.hstack([ims,ap]))
    Image.fromarray((np.vstack(cols)*255).astype('uint8')).save(f"_diag/5207_cmp/cand_{name}.jpg")
    print(f"  panel -> cand_{name}.jpg")
print("done")
