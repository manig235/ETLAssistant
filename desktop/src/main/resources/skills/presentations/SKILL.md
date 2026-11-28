---
name: presentations
description: Create, read, edit and visually verify editable PowerPoint presentations.
---

Read the `schema` reference. Inspect an existing PPTX with `office_inspect` before editing. Preserve slide dimensions and surrounding content during targeted edits. For new decks, use `office_create(kind="pptx")`; prefer concise titles, readable text, and meaningful diagrams or pictures over dense bullet lists. The tool uses a 1280 × 720, 16:9 canvas. Keep text and shapes within the canvas. Source images must be managed files returned by the app's tools.

Use native text/shape specifications where editability matters. Use `office_edit(set_text)` for an existing text shape identified by inspection. Save as a separate file.

Call `office_render` and inspect actual slide images. It exports through installed PowerPoint when the user has no other PowerPoint instance open. Review overlaps, clipping, whitespace, slide numbering, Korean glyphs and visual hierarchy. Render again after fixes. The tool does not close or attach to the user's open decks; if PowerPoint is in use, explain that concrete obstacle instead of reporting a completed render.

Do not substitute a PDF for requested PPTX. Provide the final PowerPoint file card, and a PDF only when requested. Do not claim a paid image or a Codex-app-only image tool is available merely because this skill mentions visuals.

When calling an image-returning tool through Codex `exec`, forward the actual content blocks so the model can see them:

```javascript
const result = await tools.mcp__etl__etl_render_pages({context: CURRENT_CONTEXT, path: GENERATED_PATH, pages: "1-3"});
for (const part of result.content || []) {
  if (part.type === "image") image(part);
  else if (part.type === "text") text(part.text);
}
```

Use the same forwarding pattern for `office_render` and `file_image`. Printing a serialized result containing base64 is not visual inspection. Read the current tool inventory and replace the context/path placeholders with the actual returned values.
