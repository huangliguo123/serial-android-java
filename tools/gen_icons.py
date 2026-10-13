# -*- coding: utf-8 -*-
"""从 Logo.png 生成 Android 图标资源（一次性工具脚本）。
生成:
  - mipmap-{dpi}/ic_launcher.png      各密度全幅图标（API<26 fallback）
  - drawable/ic_launcher_foreground.png  adaptive 前景（透明底白字，居中安全区）
"""
import os
from PIL import Image

LOGO = r"C:\Users\lylens\Desktop\Logo.png"
RES = r"C:\Users\lylens\Desktop\python_tool\serial-android\app\src\main\res"

img = Image.open(LOGO).convert("RGBA")

# 1. 各密度全幅图标
dens = {
    "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
}
for folder, size in dens.items():
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
    print("ok", folder)

# 2. adaptive 前景：黑转透明，提取白字，缩放居中到中央 62% 宽度
fg = img.resize((1024, 1024), Image.LANCZOS).convert("RGBA")
px = fg.load()
for y in range(fg.height):
    for x in range(fg.width):
        r, g, b, a = px[x, y]
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        if lum < 70:  # 近黑背景 -> 全透明
            px[x, y] = (0, 0, 0, 0)
alpha = fg.getchannel("A")
bbox = alpha.getbbox()  # 白字区域
if bbox:
    crop = fg.crop(bbox)
    cw, ch = crop.size
    tw = int(1024 * 0.62)
    nh = max(1, int(ch * tw / cw))
    crop = crop.resize((tw, nh), Image.LANCZOS)
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    canvas.paste(crop, ((1024 - tw) // 2, (1024 - nh) // 2), crop)
else:
    canvas = fg
os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
canvas.save(os.path.join(RES, "drawable", "ic_launcher_foreground.png"))
print("ok foreground", canvas.size)
print("DONE")
