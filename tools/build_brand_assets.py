"""Render original vector-native Zhiyu artwork at Android launcher densities."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'app/src/main/res'
OUT=ROOT/'docs/brand'
OUT.mkdir(parents=True,exist_ok=True)
paths=[('stroke_left','M32,37 Q43,34 54,41 L54,73 Q43,66 32,69 Z'),
       ('stroke_right','M54,41 Q65,34 76,37 L76,69 Q65,66 54,73'),
       ('stroke_stem','M40,47 L46,49'),('stroke_bar1','M40,56 L46,58'),('stroke_bar2','M63,49 L69,47')]
def vector(color):
    lines=['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    for name,path in paths:
        lines.append(f'<path android:name="{name}" android:pathData="{path}" android:fillColor="@android:color/transparent" android:strokeColor="{color}" android:strokeWidth="3.8" android:strokeLineCap="round" android:strokeLineJoin="round"/>')
    lines.append('<path android:pathData="M77,24 a4,4 0,1 0,0,8 a4,4 0,1 0,0,-8" android:fillColor="#D9BA73"/>')
    return '\n'.join(lines+['</vector>'])
(RES/'drawable/ic_launcher_foreground.xml').write_text(vector('#F2F6EC'),encoding='utf-8')
(RES/'drawable/ic_splash_yen.xml').write_text(vector('@color/accent_text'),encoding='utf-8')
(RES/'drawable/brand_mark.xml').write_text(vector('@color/accent_text'),encoding='utf-8')
svg='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108"><rect width="108" height="108" rx="25" fill="#174B43"/>'
for _,path in paths: svg+=f'<path d="{path}" fill="none" stroke="#F2F6EC" stroke-width="3.8" stroke-linecap="round" stroke-linejoin="round"/>'
svg+='<circle cx="77" cy="28" r="4" fill="#D9BA73"/></svg>'
(OUT/'zhiyu-mark.svg').write_text(svg,encoding='utf-8')

def quad(a,b,c):
    return [((1-t)**2*a[0]+2*(1-t)*t*b[0]+t*t*c[0],(1-t)**2*a[1]+2*(1-t)*t*b[1]+t*t*c[1]) for t in [i/32 for i in range(33)]]
book=[quad((32,37),(43,34),(54,41))+[(54,73)]+quad((54,73),(43,66),(32,69))+[(32,37)],
      quad((54,41),(65,34),(76,37))+[(76,69)]+quad((76,69),(65,66),(54,73)),
      [(40,47),(46,49)],[(40,56),(46,58)],[(63,49),(69,47)]]
def render(size, circle=False):
    factor=size*4/108
    image=Image.new('RGBA',(size*4,size*4),(0,0,0,0)); draw=ImageDraw.Draw(image)
    draw.rounded_rectangle((0,0,size*4-1,size*4-1),radius=(54 if circle else 25)*factor,fill='#174B43')
    for pts in book:
        scaled=[(x*factor,y*factor) for x,y in pts]
        draw.line(scaled,fill='#F2F6EC',width=round(3.8*factor),joint='curve')
        for x,y in scaled: draw.ellipse((x-1.9*factor,y-1.9*factor,x+1.9*factor,y+1.9*factor),fill='#F2F6EC')
    draw.ellipse((73*factor,24*factor,81*factor,32*factor),fill='#D9BA73')
    return image.resize((size,size),getattr(Image, 'Resampling', Image).LANCZOS)
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    for name in ['ic_launcher.png','ic_launcher_round.png']: render(size,circle='round' in name).save(RES/f'mipmap-{density}'/name)
render(512).save(OUT/'zhiyu-icon.png')
for path in [RES/'layout/nav_header.xml',RES/'layout/splash_layout.xml',RES/'drawable/splash_screen.xml']:
    s=path.read_text(encoding='utf-8').replace('@drawable/app_logo_drawer','@drawable/brand_mark').replace('@drawable/splash_logo','@drawable/brand_mark')
    path.write_text(s,encoding='utf-8')
print('Rendered Zhiyu vector marks and launcher densities')
