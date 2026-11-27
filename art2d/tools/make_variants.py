# -*- coding: utf-8 -*-
"""按新画风生成七个表情页（warm2）。"""
import io, os, re

src = r"E:\EvilShao\art2d\scene.html"
outdir = r"E:\EvilShao\art2d\variants"
os.makedirs(outdir, exist_ok=True)
s = io.open(src, encoding="utf-8").read()

def block(start_marker, end_marker):
    i = s.index(start_marker) + len(start_marker)
    j = s.index(end_marker, i)
    return s[i:j]

eyes = block("    <!-- eyes -->\n", "    <!-- blush -->")
brows = block("    <!-- brows: thin, arched -->\n", "    <!-- eyes -->")
mouth = block("    <!-- mouth: soft smile with a hint of lower lip -->\n", "    <!-- hair clip -->")

EYES = {
    "open": eyes,
    "blink": '''    <g>
      <path d="M186 356 C198 372 232 372 244 356" fill="none" stroke="#3B2317" stroke-width="9" stroke-linecap="round"/>
      <path d="M296 356 C308 372 342 372 354 356" fill="none" stroke="#3B2317" stroke-width="9" stroke-linecap="round"/>
      <path d="M186 356 C178 362 176 368 178 374" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
      <path d="M354 356 C362 362 364 368 362 374" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
      <path d="M196 366 C212 374 230 374 240 366" fill="none" stroke="#C88F76" stroke-width="3" opacity="0.5"/>
      <path d="M344 366 C328 374 310 374 300 366" fill="none" stroke="#C88F76" stroke-width="3" opacity="0.5"/>
    </g>

''',
    "happy": '''    <g>
      <path d="M186 362 C198 340 232 340 244 362" fill="none" stroke="#3B2317" stroke-width="10" stroke-linecap="round"/>
      <path d="M296 362 C308 340 342 340 354 362" fill="none" stroke="#3B2317" stroke-width="10" stroke-linecap="round"/>
      <path d="M186 362 C178 368 176 374 178 380" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
      <path d="M354 362 C362 368 364 374 362 380" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
    </g>

''',
    "shy": '''    <g>
      <ellipse cx="214" cy="360" rx="30" ry="34" fill="#FFFFFF"/>
      <ellipse cx="326" cy="360" rx="30" ry="34" fill="#FFFFFF"/>
      <ellipse cx="214" cy="366" rx="25" ry="27" fill="url(#iris)"/>
      <ellipse cx="326" cy="366" rx="25" ry="27" fill="url(#iris)"/>
      <ellipse cx="214" cy="368" rx="12" ry="16" fill="#2B180F"/>
      <ellipse cx="326" cy="368" rx="12" ry="16" fill="#2B180F"/>
      <circle cx="204" cy="352" r="9" fill="#FFFFFF"/>
      <circle cx="316" cy="352" r="9" fill="#FFFFFF"/>
      <path d="M181 350 C190 322 240 318 250 348" fill="none" stroke="#3B2317" stroke-width="10" stroke-linecap="round"/>
      <path d="M359 350 C350 322 300 318 290 348" fill="none" stroke="#3B2317" stroke-width="10" stroke-linecap="round"/>
      <path d="M181 350 C172 358 170 366 173 374" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
      <path d="M359 350 C368 358 370 366 367 374" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
      <path d="M192 388 C204 396 228 396 240 388" fill="none" stroke="#9A6A50" stroke-width="3" opacity="0.45"/>
      <path d="M348 388 C336 396 312 396 300 388" fill="none" stroke="#9A6A50" stroke-width="3" opacity="0.45"/>
    </g>

''',
    "bored": '''    <g>
      <ellipse cx="214" cy="360" rx="30" ry="34" fill="#FFFFFF"/>
      <ellipse cx="326" cy="360" rx="30" ry="34" fill="#FFFFFF"/>
      <ellipse cx="214" cy="364" rx="27" ry="30" fill="url(#iris)"/>
      <ellipse cx="326" cy="364" rx="27" ry="30" fill="url(#iris)"/>
      <ellipse cx="214" cy="366" rx="12" ry="17" fill="#2B180F"/>
      <ellipse cx="326" cy="366" rx="12" ry="17" fill="#2B180F"/>
      <circle cx="205" cy="348" r="8" fill="#FFFFFF"/>
      <circle cx="317" cy="348" r="8" fill="#FFFFFF"/>
      <path d="M181 344 C190 318 240 314 250 344 L250 360 L181 360 Z" fill="#FCE0CE"/>
      <path d="M359 344 C350 318 300 314 290 344 L290 360 L359 360 Z" fill="#FCE0CE"/>
      <path d="M181 344 C190 318 240 314 250 344" fill="none" stroke="#3B2317" stroke-width="9" stroke-linecap="round"/>
      <path d="M359 344 C350 318 300 314 290 344" fill="none" stroke="#3B2317" stroke-width="9" stroke-linecap="round"/>
    </g>

''',
    "surprised": '''    <g>
      <ellipse cx="214" cy="358" rx="34" ry="39" fill="#FFFFFF"/>
      <ellipse cx="326" cy="358" rx="34" ry="39" fill="#FFFFFF"/>
      <ellipse cx="214" cy="358" rx="29" ry="34" fill="url(#iris)"/>
      <ellipse cx="326" cy="358" rx="29" ry="34" fill="url(#iris)"/>
      <ellipse cx="214" cy="360" rx="13" ry="19" fill="#2B180F"/>
      <ellipse cx="326" cy="360" rx="13" ry="19" fill="#2B180F"/>
      <circle cx="201" cy="338" r="11" fill="#FFFFFF"/>
      <circle cx="313" cy="338" r="11" fill="#FFFFFF"/>
      <path d="M177 336 C188 302 240 298 252 332" fill="none" stroke="#3B2317" stroke-width="9" stroke-linecap="round"/>
      <path d="M363 336 C352 302 300 298 288 332" fill="none" stroke="#3B2317" stroke-width="9" stroke-linecap="round"/>
      <path d="M177 336 C169 344 167 352 170 360" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
      <path d="M363 336 C371 344 373 352 370 360" fill="none" stroke="#3B2317" stroke-width="7" stroke-linecap="round"/>
    </g>

''',
}

BROWS = {
    "open": brows,
    "angry": '''    <path d="M194 316 C208 300 236 298 250 308" fill="none" stroke="#8A5E42" stroke-width="7" stroke-linecap="round"/>
    <path d="M346 316 C332 300 304 298 290 308" fill="none" stroke="#8A5E42" stroke-width="7" stroke-linecap="round"/>
''',
    "sad": '''    <path d="M194 300 C206 292 232 296 248 310" fill="none" stroke="#8A5E42" stroke-width="5.5" stroke-linecap="round"/>
    <path d="M346 300 C334 292 308 296 292 310" fill="none" stroke="#8A5E42" stroke-width="5.5" stroke-linecap="round"/>
''',
}

MOUTH = {
    "open": mouth,
    "laugh": '''    <path d="M246 430 C252 458 288 458 294 430 C284 438 256 438 246 430 Z" fill="#C2544D"/>
    <path d="M251 434 C260 441 280 441 289 434 C280 438 260 438 251 434 Z" fill="#FFFFFF" opacity="0.95"/>
    <path d="M258 448 C264 452 276 452 282 448" fill="none" stroke="#E39A93" stroke-width="3" opacity="0.7"/>
''',
    "small_smile": '''    <path d="M256 434 C263 442 277 442 284 434" fill="none" stroke="#C25A54" stroke-width="5" stroke-linecap="round"/>
''',
    "flat": '''    <path d="M254 438 C263 442 277 442 286 438" fill="none" stroke="#C25A54" stroke-width="5" stroke-linecap="round"/>
''',
    "o": '''    <ellipse cx="270" cy="440" rx="10" ry="13" fill="#C2544D"/>
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

mask = re.compile(r"(    <!-- eyes -->\n)(.*?)(    <!-- blush -->)", re.S)
mb = re.compile(r"(    <!-- brows: thin, arched -->\n)(.*?)(    <!-- eyes -->)", re.S)
mm = re.compile(r"(    <!-- mouth: soft smile with a hint of lower lip -->\n)(.*?)(    <!-- hair clip -->)", re.S)

for name, (e, b, m) in VARIANTS.items():
    t = s
    t = mask.sub(lambda mo: mo.group(1) + EYES[e] + "    <!-- blush -->", t, count=1)
    t = mb.sub(lambda mo: mo.group(1) + BROWS[b] + "    <!-- eyes -->", t, count=1)
    t = mm.sub(lambda mo: mo.group(1) + MOUTH[m] + "    <!-- hair clip -->", t, count=1)
    io.open(os.path.join(outdir, name + ".html"), "w", encoding="utf-8", newline="\n").write(t)
print("variants written", list(VARIANTS))