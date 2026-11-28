---
name: images
description: Inspect images and perform bounded crop, resize and right-angle rotation operations.
---

Use `file_image` for PNG/JPEG/GIF files in the managed workspace. Read the image before describing or changing it. Use `etl_render_pages` for PDF pages, and `office_render` for Office output. An image returned by a tool is available for direct visual inspection.

Use `image_transform` for user-requested cropping, resizing or 90-degree rotation; it preserves the original and returns a new PNG. Preserve aspect ratio unless distortion is requested. A crop is `[x,y,width,height]` in source pixels.

These operations are not generative image editing. Use an actual model image-generation/editing tool only when one is present in the current tool inventory and supports the request. Do not invent one, offer a raw local image path as a generated result, or claim that a skill file by itself installs an image model.

When calling an image-returning tool through Codex `exec`, forward the actual content blocks so the model can see them:

```javascript
const result = await tools.mcp__etl__etl_render_pages({context: CURRENT_CONTEXT, path: GENERATED_PATH, pages: "1-3"});
for (const part of result.content || []) {
  if (part.type === "image") image(part);
  else if (part.type === "text") text(part.text);
}
```

Use the same forwarding pattern for `office_render` and `file_image`. Printing a serialized result containing base64 is not visual inspection. Read the current tool inventory and replace the context/path placeholders with the actual returned values.
