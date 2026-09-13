# 雾紫图标素材生成记录

模式：Codex 内置 imagegen。以下为实际调用提示词；生成服务不参与项目构建。

## 获选雾紫配色稿

Use case: precise-object-edit.
Asset type: color study for the accepted A2 DataCube desktop app icon.
Image 1 is the edit target. Change ONLY colors. Preserve the icon's EXACT existing geometry, all three staggered pages, upper-right curled corner, four recessed front table cells, their positions and shapes, page thicknesses, perspective, framing, rounded-square tile, exterior background, lighting direction and shadows. This is a careful recolor, not a redesign, and must remain directly comparable to the input.
Remove every trace of the original dark green / malachite / jade palette. Keep the satin finish and tonal depth, but avoid dull muddy dark colors. Do not add details, letters, bars, logos, textures or text. Output exactly one complete icon with the same composition as Image 1. Maintain clear contrast between the main page faces and the four inset cells. New palette: softly saturated misty violet. Main front page in medium iris violet approximately #8676BC, supporting pages in harmonious slightly lighter violet, page edges and folded underside in pale lilac approximately #D6CAED. Four inset cells in pearl-white approximately #F4F0FA. Rounded-square tile stays near-white porcelain with a neutral-cool cast. Keep the light neutral exterior backdrop unchanged. Clear, fresh and calm, not dark or gloomy. No green, navy, magenta, blue-purple rainbow gradient, neon glow or metallic color shifts.

## 小尺寸配套稿

Use case: precise-object-edit. Image 1 is the approved final DataCube icon. Make a carefully matched SMALL-SIZE companion icon for 16, 24 and 32 pixel Windows UI use, provided as a large square transparent PNG master. Retain the SAME misty violet three staggered data pages, SAME orientation, SAME four pale table cells and recognizable folded upper-right corner. Remove the white backing tile and exterior backdrop entirely: only the violet data-page emblem on genuinely transparent background. Enlarge the emblem to about 92 percent of the square canvas, optically centered. Optimize the drawing for tiny sizes: flatter satin fills, bolder broad silhouettes, slightly wider transparent gaps between the three page edges, clean near-white four cells with stronger contrast, simplified single folded-corner highlight, no tiny bevels or fine grain. The form must still clearly match the reference icon, not a generic spreadsheet or new logo. Keep the violet palette, no recoloring, no letters or extra elements. True transparent alpha outside the emblem including gaps, no checkerboard, no drop shadow or halo. One finished small-size companion icon only.

## 小尺寸真实透明修正

Use case: background-extraction. Image 1 is the edit target. Extract the purple three-page icon as a clean cutout. Completely REMOVE the fake gray checkerboard background, replacing every checkerboard region with genuine transparent alpha=0 pixels, including the gaps between page edges. This is NOT a request to draw a checkerboard. Preserve the three purple pages, their white four cells and folded corner, their existing colors, dimensions, positions and shading EXACTLY. Keep the light lavender sides as opaque parts of the icon, not background. No white backdrop, no new shadow, no painted transparency simulation, no extra elements. Output the same square image with actual RGBA transparency and clean anti-aliased edges. All pixels far outside the emblem must have zero alpha.

未采用带毛边的标准透明提取稿，也未采用带棋盘格的中间稿。标准版采用获选设计稿，由构建期应用圆角遮罩；小尺寸采用透明修正后的配套稿。只保留两个入库母版。
