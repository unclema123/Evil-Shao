import io, os, re, subprocess
src=r"E:\EvilShao\art2d\scene.html"
outdir=r"E:\EvilShao\art2d\variants"
os.makedirs(outdir, exist_ok=True)
s=io.open(src,encoding="utf-8").read()

def block(name, start_marker, end_marker):
    i=s.index(start_marker)+len(start_marker)
    j=s.index(end_marker)
    return s[i:j]

eyes=block("eyes","    <!-- eyes -->\n","    <!-- blush -->")
brows=block("brows","    <!-- brows: thin, arched -->\n","    <!-- eyes -->")
mouth=block("mouth","    <!-- mouth: soft smile with a hint of lower lip -->\n","    <!-- hair clip -->")

EYES = {
 "open": eyes,
 "blink": '''    <g>
      <path d="M212 348 C222 356 244 356 254 348" fill="none" stroke="#3A2418" stroke-width="6" stroke-linecap="round"/>
      <path d="M286 348 C296 356 318 356 328 348" fill="none" stroke="#3A2418" stroke-width="6" stroke-linecap="round"/>
      <path d="M212 348 l-8 4" stroke="#3A2418" stroke-width="5" stroke-linecap="round"/>
      <path d="M328 348 l8 4" stroke="#3A2418" stroke-width="5" stroke-linecap="round"/>
    </g>
''',
 "happy": '''    <g>
      <path d="M210 352 C220 336 248 336 258 352" fill="none" stroke="#3A2418" stroke-width="8" stroke-linecap="round"/>
      <path d="M282 352 C292 336 320 336 330 352" fill="none" stroke="#3A2418" stroke-width="8" stroke-linecap="round"/>
      <path d="M210 352 l-8 4" stroke="#3A2418" stroke-width="6" stroke-linecap="round"/>
      <path d="M330 352 l8 4" stroke="#3A2418" stroke-width="6" stroke-linecap="round"/>
    </g>
''',
 "shy": '''    <g>
      <ellipse cx="232" cy="344" rx="24" ry="29" fill="#FFFFFF"/>
      <ellipse cx="308" cy="344" rx="24" ry="29" fill="#FFFFFF"/>
      <ellipse cx="232" cy="350" rx="20" ry="23" fill="url(#iris)"/>
      <ellipse cx="308" cy="350" rx="20" ry="23" fill="url(#iris)"/>
      <ellipse cx="232" cy="352" rx="11" ry="14" fill="#2E1A12"/>
      <ellipse cx="308" cy="352" rx="11" ry="14" fill="#2E1A12"/>
      <circle cx="224" cy="340" r="7" fill="#FFFFFF"/>
      <circle cx="300" cy="340" r="7" fill="#FFFFFF"/>
      <path d="M208 332 C216 314 248 312 256 330" fill="none" stroke="#3A2418" stroke-width="9" stroke-linecap="round"/>
      <path d="M332 332 C324 314 292 312 284 330" fill="none" stroke="#3A2418" stroke-width="9" stroke-linecap="round"/>
      <path d="M208 332 l-10 -4" stroke="#3A2418" stroke-width="7" stroke-linecap="round"/>
      <path d="M332 332 l10 -4" stroke="#3A2418" stroke-width="7" stroke-linecap="round"/>
    </g>
''',
 "bored": '''    <g>
      <ellipse cx="232" cy="344" rx="24" ry="29" fill="#FFFFFF"/>
      <ellipse cx="308" cy="344" rx="24" ry="29" fill="#FFFFFF"/>
      <ellipse cx="232" cy="350" rx="19" ry="22" fill="url(#iris)"/>
      <ellipse cx="308" cy="350" rx="19" ry="22" fill="url(#iris)"/>
      <ellipse cx="232" cy="352" rx="10" ry="13" fill="#2E1A12"/>
      <ellipse cx="308" cy="352" rx="10" ry="13" fill="#2E1A12"/>
      <circle cx="225" cy="340" r="6" fill="#FFFFFF"/>
      <circle cx="301" cy="340" r="6" fill="#FFFFFF"/>
      <path d="M206 350 C216 324 250 320 258 336 L258 350 Z" fill="#FFE3D3"/>
      <path d="M334 350 C324 324 290 320 282 336 L282 350 Z" fill="#FFE3D3"/>
      <path d="M206 350 C216 324 250 320 258 336" fill="none" stroke="#3A2418" stroke-width="8" stroke-linecap="round"/>
      <path d="M334 350 C324 324 290 320 282 336" fill="none" stroke="#3A2418" stroke-width="8" stroke-linecap="round"/>
    </g>
''',
 "surprised": '''    <g>
      <ellipse cx="232" cy="344" rx="27" ry="32" fill="#FFFFFF"/>
      <ellipse cx="308" cy="344" rx="27" ry="32" fill="#FFFFFF"/>
      <ellipse cx="232" cy="344" rx="22" ry="27" fill="url(#iris)"/>
      <ellipse cx="308" cy="344" rx="22" ry="27" fill="url(#iris)"/>
      <ellipse cx="232" cy="346" rx="12" ry="16" fill="#2E1A12"/>
      <ellipse cx="308" cy="346" rx="12" ry="16" fill="#2E1A12"/>
      <circle cx="223" cy="332" r="7" fill="#FFFFFF"/>
      <circle cx="299" cy="332" r="7" fill="#FFFFFF"/>
      <path d="M204 320 C212 298 250 294 260 316" fill="none" stroke="#3A2418" stroke-width="8" stroke-linecap="round"/>
      <path d="M336 320 C328 298 290 294 280 316" fill="none" stroke="#3A2418" stroke-width="8" stroke-linecap="round"/>
      <path d="M204 320 l-9 -7" stroke="#3A2418" stroke-width="7" stroke-linecap="round"/>
      <path d="M336 320 l9 -7" stroke="#3A2418" stroke-width="7" stroke-linecap="round"/>
    </g>
''',
}

BROWS = {
 "open": brows,
 "angry": '''    <path d="M212 306 C224 292 242 290 250 296" fill="none" stroke="#6B4433" stroke-width="7" stroke-linecap="round"/>
    <path d="M328 306 C316 292 298 290 290 296" fill="none" stroke="#6B4433" stroke-width="7" stroke-linecap="round"/>
''',
 "sad": '''    <path d="M212 300 C222 292 242 294 250 302" fill="none" stroke="#6B4433" stroke-width="5" stroke-linecap="round"/>
    <path d="M328 300 C318 292 298 294 290 302" fill="none" stroke="#6B4433" stroke-width="5" stroke-linecap="round"/>
''',
}

MOUTH = {
 "open": mouth,
 "smile_open": '''    <path d="M252 408 C258 426 282 426 288 408 C280 416 260 416 252 408 Z" fill="#C2544D"/>
    <path d="M257 412 C263 418 277 418 283 412 C277 415 263 415 257 412 Z" fill="#FFFFFF" opacity="0.9"/>
    <path d="M262 424 C267 427 273 427 278 424" fill="none" stroke="#E39A93" stroke-width="3" opacity="0.7"/>
''',
 "small_smile": '''    <path d="M258 412 C263 419 277 419 282 412" fill="none" stroke="#C25A54" stroke-width="5" stroke-linecap="round"/>
''',
 "flat": '''    <path d="M256 414 C264 418 276 418 284 414" fill="none" stroke="#C25A54" stroke-width="5" stroke-linecap="round"/>
''',
 "o": '''    <ellipse cx="270" cy="416" rx="9" ry="12" fill="#C2544D"/>
''',
}

VARIANTS = {
 "neutral":  ("open","open","open"),
 "blink":    ("blink","open","open"),
 "happy":    ("happy","open","smile_open"),
 "shy":      ("shy","sad","small_smile"),
 "bored":    ("bored","open","flat"),
 "angry":    ("open","angry","flat"),
 "surprised":("surprised","open","o"),
}

mask=re.compile(r"(    <!-- eyes -->\n)(.*?)(    <!-- blush -->)", re.S)
mb=re.compile(r"(    <!-- brows: thin, arched -->\n)(.*?)(    <!-- eyes -->)", re.S)
mm=re.compile(r"(    <!-- mouth: soft smile with a hint of lower lip -->\n)(.*?)(    <!-- hair clip -->)", re.S)

for name,(e,b,m) in VARIANTS.items():
    t=s
    t=mask.sub(lambda mo: mo.group(1)+EYES[e]+"    <!-- blush -->", t, count=1)
    t=mb.sub(lambda mo: mo.group(1)+BROWS[b]+"    <!-- eyes -->", t, count=1)
    t=mm.sub(lambda mo: mo.group(1)+MOUTH[m]+"    <!-- hair clip -->", t, count=1)
    io.open(os.path.join(outdir,name+".html"),"w",encoding="utf-8",newline="\n").write(t)
print("variants written", list(VARIANTS))
