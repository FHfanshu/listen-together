# 生成 App 图标（像素风）：python3 tools/gen_icon.py composeApp/src/androidMain/res
# 同时在当前目录输出 icon.svg 预览（rsvg-convert icon.svg -o icon.png 可转成图片）。
# 改图案只需要改下面的 GRID，每个字符一个像素。
import sys

GRID = [
    "................",
    "....HHHHHHHH....",
    "...H........H...",
    "..H.W......W.H..",
    "..H.WW....WW.H..",
    "..H.WPW..WPW.H..",
    "..HWWWWWWWWWWH..",
    ".hHWWWWWWWWWWHh.",
    ".hHWWEWWWWEWWHh.",
    ".hHWEWEWWEWEWHh.",
    ".hHWKWWMMWWKWHh.",
    ".hHWWWWTTWWWWHh.",
    "...WWWWWWWWWW...",
    "....WWWWWWWW....",
    "................",
    "................",
]
COLORS = {
    "W": "#FFF7EF",  # 猫
    "P": "#FF9BB0",  # 耳朵里
    "E": "#2B2140",  # ^ ^ 眼睛
    "K": "#FFB3C1",  # 腮红
    "M": "#7A1F33",  # 张开的嘴
    "T": "#FF7A92",  # 舌头
    "H": "#FF5F6D",  # 耳机
    "h": "#FF9A4C",  # 耳机罩外沿
}
BG = "#231C33"
CELL = 4.25           # 16 格 × 4.25 = 68，落在 108 画布中间直径 72 的安全区里
OX = (108 - 16 * CELL) / 2
OY = OX + CELL * 0.5  # 图案偏上，往下挪半格居中


def rects():
    """把每行连续同色的像素合成一个矩形，路径少一些。"""
    by_color = {}
    for y, row in enumerate(GRID):
        x = 0
        while x < len(row):
            c = row[x]
            if c not in COLORS:
                x += 1
                continue
            end = x
            while end + 1 < len(row) and row[end + 1] == c:
                end += 1
            px, py = OX + x * CELL, OY + y * CELL
            by_color.setdefault(c, []).append(f"M{px:g},{py:g}h{(end - x + 1) * CELL:g}v{CELL:g}h{-(end - x + 1) * CELL:g}z")
            x = end + 1
    return by_color


def svg():
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432" shape-rendering="crispEdges">',
           '<defs><clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath></defs>',
           '<g clip-path="url(#c)">', f'<rect width="108" height="108" fill="{BG}"/>']
    for c, paths in rects().items():
        out.append(f'<path d="{"".join(paths)}" fill="{COLORS[c]}"/>')
    out.append("</g></svg>")
    return "\n".join(out)


def vector(fill_paths):
    head = ['<?xml version="1.0" encoding="utf-8"?>',
            '<!-- 由 tools/gen_icon.py 生成，改图标请改那里 -->',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    body = [f'    <path android:fillColor="#FF{color[1:].upper()}" android:pathData="{d}" />' for color, d in fill_paths]
    return "\n".join(head + body + ["</vector>"]) + "\n"


open("icon.svg", "w").write(svg())
if len(sys.argv) > 1:
    res = sys.argv[1]
    fg = [(COLORS[c], "".join(p)) for c, p in rects().items()]
    open(f"{res}/drawable/ic_launcher_foreground.xml", "w").write(vector(fg))
    open(f"{res}/drawable/ic_launcher_background.xml", "w").write(vector([(BG, "M0,0h108v108h-108z")]))
