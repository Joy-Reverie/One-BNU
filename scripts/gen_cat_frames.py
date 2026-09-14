#!/usr/bin/env python3
"""把 RunCat 365 的奔跑小猫描成矢量帧（app/src/main/res/drawable/widget_cat_*.xml）。

素材：https://github.com/runcat-dev/RunCat365 里 RunCat365/resources/runners/cat/cat_0.png … cat_4.png
（32×32 黑色剪影，Copyright 2025 Takuto Nakamura，Apache License 2.0，见 third_party/RunCat365/）。
32 像素的位图直接放进小组件会发虚，这里把 alpha 通道放大、轻微模糊后取 0.5 等高线，
再做 Douglas–Peucker 简化，输出 evenOdd 填充的路径（腿与尾巴围出的空洞自然成为洞）。

用法：python3 scripts/gen_cat_frames.py <RunCat365 仓库目录> [--svg 预览输出目录]
依赖：Pillow、numpy、contourpy、opencv-python（描线用）。
"""
import os
import sys

import contourpy
import cv2
import numpy as np
from PIL import Image, ImageFilter

FRAMES = 5
SCALE = 8            # 放大倍数，等高线在放大后的网格上取
BLUR = 3.2           # 放大后网格上的高斯半径（≈0.4 个源像素），压掉像素台阶但不磨圆耳朵
EPSILON = 0.05       # Douglas–Peucker 阈值（源像素）
COLOR = "@color/widget_text_primary"

# 五帧的并集包围盒 x 0～31、y 6～26；取 y 5～27 作为 viewport，小猫脚底落在底边附近
VIEW_X, VIEW_Y, VIEW_W, VIEW_H = 0.0, 5.0, 32.0, 22.0


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def trace(png):
    alpha = Image.open(png).convert("RGBA").split()[3]
    w, h = alpha.size
    big = alpha.resize((w * SCALE, h * SCALE), Image.BICUBIC).filter(ImageFilter.GaussianBlur(BLUR))
    z = np.asarray(big, dtype=np.float32) / 255.0
    lines = contourpy.contour_generator(z=z).lines(0.5)
    paths = []
    for line in lines:
        # contourpy 的坐标是放大网格的下标；源图像素 u 覆盖 [u, u+1)，下标 i 对应 (i + 0.5) / SCALE
        pts = (np.asarray(line, dtype=np.float64) + 0.5) / SCALE
        approx = cv2.approxPolyDP(pts.astype(np.float32).reshape(-1, 1, 2), EPSILON, True).reshape(-1, 2)
        if len(approx) < 3:
            continue
        paths.append([(x - VIEW_X, y - VIEW_Y) for x, y in approx])
    return paths


def path_data(paths):
    return " ".join("M" + " L".join(f"{f(x)},{f(y)}" for x, y in p) + " Z" for p in paths)


def vector_xml(paths):
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- 由 scripts/gen_cat_frames.py 从 RunCat 365 的小猫帧描出，请勿手改；来源与许可见 third_party/RunCat365/。 -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{f(VIEW_W / 2)}dp"',
        f'    android:height="{f(VIEW_H / 2)}dp"',
        f'    android:viewportWidth="{f(VIEW_W)}"',
        f'    android:viewportHeight="{f(VIEW_H)}">',
        '    <path',
        f'        android:fillColor="{COLOR}"',
        '        android:fillType="evenOdd"',
        f'        android:pathData="{path_data(paths)}" />',
        '</vector>',
        '',
    ])


def svg(paths, color="#1c2333"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {f(VIEW_W)} {f(VIEW_H)}" '
            f'width="{int(VIEW_W * 10)}" height="{int(VIEW_H * 10)}">\n'
            f'<path fill="{color}" fill-rule="evenodd" d="{path_data(paths)}"/>\n</svg>\n')


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = os.path.join(sys.argv[1], "RunCat365", "resources", "runners", "cat")
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    res = os.path.join(root, "app", "src", "main", "res", "drawable")
    svg_dir = sys.argv[sys.argv.index("--svg") + 1] if "--svg" in sys.argv else None
    if svg_dir:
        os.makedirs(svg_dir, exist_ok=True)
    for i in range(FRAMES):
        paths = trace(os.path.join(src, f"cat_{i}.png"))
        with open(os.path.join(res, f"widget_cat_{i}.xml"), "w") as fp:
            fp.write(vector_xml(paths))
        if svg_dir:
            with open(os.path.join(svg_dir, f"cat_{i}.svg"), "w") as fp:
                fp.write(svg(paths))
        print(f"cat_{i}: {sum(len(p) for p in paths)} points in {len(paths)} contours")


if __name__ == "__main__":
    main()
