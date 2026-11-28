---
name: spreadsheets
description: Create, read and edit Excel or CSV workbooks with verified formulas and readable output.
---

Read the `schema` reference. Use `office_inspect` for XLSX or `csv_read` for CSV/TSV. Do not infer missing values as zero. Keep raw inputs separate from computed results when their purpose differs; avoid unnecessary tabs. Preserve an existing workbook's sheet names and architecture during a narrow edit. Use explicit units, understandable headers and useful number formats.

Use `office_create(kind="xlsx")`; supply formulas as `{ "formula": "SUM(B2:B8)" }`, never as a bare text string. The writer evaluates supported formulas and rejects formula errors on creation. Use `office_edit` with `set_cell` to make a copy with targeted changes. Inspect formulas and cached results after editing; check totals and representative edge cases. External workbook links, DDE and network formulas are not supported.

Use `office_render` to inspect actual Excel print output. Check text clipping, wrapped headers, print width and number formatting. CSV is useful for simple data exchange; use `csv_write`, which protects text against formula execution. It is not a substitute for an editable workbook with formulas when the user requests XLSX.

For data analysis, read `data-analysis`. Cite data sources and avoid false precision. Do not claim native features, charts or macros that are absent from the actual resulting workbook.

When calling an image-returning tool through Codex `exec`, forward the actual content blocks so the model can see them:

```javascript
const result = await tools.mcp__etl__etl_render_pages({context: CURRENT_CONTEXT, path: GENERATED_PATH, pages: "1-3"});
for (const part of result.content || []) {
  if (part.type === "image") image(part);
  else if (part.type === "text") text(part.text);
}
```

Use the same forwarding pattern for `office_render` and `file_image`. Printing a serialized result containing base64 is not visual inspection. Read the current tool inventory and replace the context/path placeholders with the actual returned values.
