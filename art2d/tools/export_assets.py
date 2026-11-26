import io, os, subprocess
from PIL import Image
base=r"E:\EvilShao\art2d"; variants=os.path.join(base,"variants")
chrome=r"C:\Program Files\Google\Chrome\Application\chrome.exe"
tmp=os.path.join(base,"render"); os.makedirs(tmp,exist_ok=True)
assets=r"E:\EvilShao\android\app\src\main\assets"
def shot(src,out,transparent=False):
    a=[chrome,"--headless=new","--disable-gpu","--hide-scrollbars","--force-device-scale-factor=2",
       "--window-size=540,960","--screenshot="+out]
    if transparent: a.append("--default-background-color=00000000")
    a.append("file:///"+src.replace("\\","/"))
    subprocess.run(a,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=120)
def strip(html):
    i=html.index("  <!-- background -->"); j=html.index("  <!-- character -->")
    html=html[:i]+html[j:]
    html=html.replace("background:#FFE9D8","background:transparent")
    return html
for n in ["neutral","blink","happy","shy","bored","angry","surprised"]:
    html=strip(io.open(os.path.join(variants,n+".html"),encoding="utf-8").read())
    gp=os.path.join(tmp,"g2_"+n+".html"); io.open(gp,"w",encoding="utf-8",newline="\n").write(html)
    png=os.path.join(base,"g2_"+n+".png"); shot(gp,png,transparent=True)
    img=Image.open(png).convert("RGBA")
    bb=img.getbbox(); crop=img.crop(bb)
    crop.save(os.path.join(assets,"girl_%s.webp"%n),quality=90,method=6)
    print(n,bb,crop.size,os.path.getsize(os.path.join(assets,"girl_%s.webp"%n))//1024,"KB")
