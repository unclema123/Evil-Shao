# -*- coding: utf-8 -*-
"""按 scene.html 生成七个表情页（几何跟画稿同步：眼 216/324,374、嘴 270,452）。"""
import io, os, re

src = r"E:\EvilShao\art2d\scene.html"
outdir = r"E:\EvilShao\art2d\variants"
os.makedirs(outdir, exist_ok=True)
s = io.open(src, encoding="utf-8").read()

def block(start_marker, end_marker):
    i = s.index(start_marker) + len(start_marker)
    j = s.index(end_marker, i)
    return s[i:j]

eyes = block("    <!-- eyes：更大、上眼睑压住虹膜、三点高光 -->\n", "    <!-- blush -->")
brows = block("    <!-- brows: thin, arched -->\n", "    <!-- eyes：")
mouth = block("    <!-- mouth: soft smile with a hint of lower lip -->\n", "    <!-- hair clip -->")

EYES = {
    "open": eyes,
    "blink": '''    <g>
      <path d="M182 368 C196 388 236 388 250 368" fill="none" stroke="#38200F" stroke-width="9" stroke-linecap="round"/>
      <path d="M290 368 C304 388 344 388 358 368" fill="none" stroke="#38200F" stroke-width="9" stroke-linecap="round"/>
      <path d="M182 368 C173 376 171 382 174 390" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M358 368 C367 376 369 382 366 390" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M192 380 C208 390 234 390 244 380" fill="none" stroke="#C88F76" stroke-width="3" opacity="0.5"/>
      <path d="M348 380 C332 390 306 390 296 380" fill="none" stroke="#C88F76" stroke-width="3" opacity="0.5"/>
    </g>

''',
    "happy": '''    <g>
      <path d="M182 382 C196 352 236 352 250 382" fill="none" stroke="#38200F" stroke-width="10" stroke-linecap="round"/>
      <path d="M290 382 C304 352 344 352 358 382" fill="none" stroke="#38200F" stroke-width="10" stroke-linecap="round"/>
      <path d="M182 382 C173 390 171 396 174 404" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M358 382 C367 390 369 396 366 404" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M196 394 C210 402 236 402 248 394" fill="none" stroke="#D79C82" stroke-width="3" opacity="0.45"/>
      <path d="M344 394 C330 402 304 402 292 394" fill="none" stroke="#D79C82" stroke-width="3" opacity="0.45"/>
    </g>

''',
    "shy": '''    <g>
      <ellipse cx="216" cy="384" rx="30" ry="32" fill="#FFFFFF"/>
      <ellipse cx="324" cy="384" rx="30" ry="32" fill="#FFFFFF"/>
      <ellipse cx="216" cy="392" rx="26" ry="27" fill="url(#iris)"/>
      <ellipse cx="324" cy="392" rx="26" ry="27" fill="url(#iris)"/>
      <ellipse cx="216" cy="396" rx="13" ry="17" fill="#28150C"/>
      <ellipse cx="324" cy="396" rx="13" ry="17" fill="#28150C"/>
      <circle cx="206" cy="376" r="9" fill="#FFFFFF"/>
      <circle cx="314" cy="376" r="9" fill="#FFFFFF"/>
      <path d="M182 376 C192 344 240 340 250 370" fill="none" stroke="#38200F" stroke-width="10" stroke-linecap="round"/>
      <path d="M358 370 C348 340 300 344 290 376" fill="none" stroke="#38200F" stroke-width="10" stroke-linecap="round"/>
      <path d="M182 376 C173 384 171 392 174 400" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M358 376 C367 384 369 392 366 400" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M194 412 C208 422 232 422 244 412" fill="none" stroke="#9A6A50" stroke-width="3" opacity="0.45"/>
      <path d="M346 412 C332 422 308 422 296 412" fill="none" stroke="#9A6A50" stroke-width="3" opacity="0.45"/>
    </g>

''',
    "bored": '''    <g>
      <ellipse cx="216" cy="378" rx="32" ry="36" fill="#FFFFFF"/>
      <ellipse cx="324" cy="378" rx="32" ry="36" fill="#FFFFFF"/>
      <ellipse cx="216" cy="382" rx="28" ry="32" fill="url(#iris)"/>
      <ellipse cx="324" cy="382" rx="28" ry="32" fill="url(#iris)"/>
      <ellipse cx="216" cy="386" rx="13" ry="18" fill="#28150C"/>
      <ellipse cx="324" cy="386" rx="13" ry="18" fill="#28150C"/>
      <circle cx="206" cy="366" r="8" fill="#FFFFFF"/>
      <circle cx="314" cy="366" r="8" fill="#FFFFFF"/>
      <path d="M181 356 C190 330 240 326 250 356 L250 376 L181 376 Z" fill="#FCE0CE"/>
      <path d="M359 356 C350 330 300 326 290 356 L290 376 L359 376 Z" fill="#FCE0CE"/>
      <path d="M181 356 C190 330 240 326 250 356" fill="none" stroke="#38200F" stroke-width="9" stroke-linecap="round"/>
      <path d="M359 356 C350 330 300 326 290 356" fill="none" stroke="#38200F" stroke-width="9" stroke-linecap="round"/>
    </g>

''',
    "surprised": '''    <g>
      <ellipse cx="216" cy="372" rx="37" ry="43" fill="#FFFFFF"/>
      <ellipse cx="324" cy="372" rx="37" ry="43" fill="#FFFFFF"/>
      <ellipse cx="216" cy="374" rx="31" ry="36" fill="url(#iris)"/>
      <ellipse cx="324" cy="374" rx="31" ry="36" fill="url(#iris)"/>
      <ellipse cx="216" cy="374" rx="12" ry="17" fill="#28150C"/>
      <ellipse cx="324" cy="374" rx="12" ry="17" fill="#28150C"/>
      <circle cx="202" cy="352" r="11" fill="#FFFFFF"/>
      <circle cx="310" cy="352" r="11" fill="#FFFFFF"/>
      <circle cx="228" cy="396" r="5" fill="#FFFFFF" opacity="0.9"/>
      <circle cx="336" cy="396" r="5" fill="#FFFFFF" opacity="0.9"/>
      <path d="M178 356 C188 316 244 312 254 348" fill="none" stroke="#38200F" stroke-width="9" stroke-linecap="round"/>
      <path d="M362 348 C352 312 296 316 286 356" fill="none" stroke="#38200F" stroke-width="9" stroke-linecap="round"/>
      <path d="M178 356 C170 364 168 372 171 380" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
      <path d="M362 348 C370 356 372 364 369 372" fill="none" stroke="#38200F" stroke-width="7" stroke-linecap="round"/>
    </g>

''',
}

BROWS = {
    "open": brows,
    "angry": '''    <path d="M192 306 C208 296 238 302 254 322" fill="none" stroke="#8A5E42" stroke-width="7" stroke-linecap="round"/>
    <path d="M348 306 C332 296 302 302 286 322" fill="none" stroke="#8A5E42" stroke-width="7" stroke-linecap="round"/>
''',
    "sad": '''    <path d="M192 322 C208 304 238 300 254 306" fill="none" stroke="#8A5E42" stroke-width="5.5" stroke-linecap="round"/>
    <path d="M348 322 C332 304 302 300 286 306" fill="none" stroke="#8A5E42" stroke-width="5.5" stroke-linecap="round"/>
''',
}

MOUTH = {
    "open": mouth,
    "laugh": '''    <path d="M244 448 C250 478 290 478 296 448 C286 456 254 456 244 448 Z" fill="#C2544D"/>
    <path d="M250 451 C260 458 280 458 290 451 C280 455 260 455 250 451 Z" fill="#FFFFFF" opacity="0.95"/>
    <path d="M258 468 C264 472 276 472 282 468" fill="none" stroke="#E39A93" stroke-width="3" opacity="0.7"/>
''',
    "small_smile": '''    <path d="M256 452 C263 460 277 460 284 452" fill="none" stroke="#C25A54" stroke-width="5" stroke-linecap="round"/>
''',
    "flat": '''    <path d="M252 456 C261 460 279 460 288 456" fill="none" stroke="#C25A54" stroke-width="5" stroke-linecap="round"/>
''',
    "o": '''    <ellipse cx="270" cy="458" rx="10" ry="13" fill="#C2544D"/>
    <ellipse cx="270" cy="462" rx="6" ry="7" fill="#7E2A28" opacity="0.55"/>
''',
}

VARIANTS = {
    "neutral": ("open", "open", "open"),
    "blink": ("blink", "open", "open"),
    "happy": ("happy", "open", "laugh"),
    "shy": ("shy", "sad", "small_smile"),
    "bored": ("bored", "open", "flat"),
    "angry": ("open", "angry", "flat"),
    "surprised": ("surprised", "open", "o"),
}

mask = re.compile(r"(    <!-- eyes：.*?-->\n)(.*?)(    <!-- blush -->)", re.S)
mb = re.compile(r"(    <!-- brows: thin, arched -->\n)(.*?)(    <!-- eyes：)", re.S)
mm = re.compile(r"(    <!-- mouth: soft smile with a hint of lower lip -->\n)(.*?)(    <!-- hair clip -->)", re.S)

for name, (e, b, m) in VARIANTS.items():
    t = s
    t = mask.sub(lambda mo: mo.group(1) + EYES[e] + "    <!-- blush -->", t, count=1)
    t = mb.sub(lambda mo: mo.group(1) + BROWS[b] + "    <!-- eyes：", t, count=1)
    t = mm.sub(lambda mo: mo.group(1) + MOUTH[m] + "    <!-- hair clip -->", t, count=1)
    io.open(os.path.join(outdir, name + ".html"), "w", encoding="utf-8", newline="\n").write(t)
print("variants written", list(VARIANTS))