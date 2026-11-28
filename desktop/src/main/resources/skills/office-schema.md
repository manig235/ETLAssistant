# Native file tool schema

`office_create` takes `kind`, `name` and `spec`. Supported editable formats are DOCX, XLSX and PPTX. Inputs are JSON data, never scripts. Outputs are managed artifacts; use the returned `path` for inspection, rendering or another operation.

## DOCX

```json
{"title":"과목 학습 요약","subtitle":"범위와 목적","sections":[{"heading":"핵심 개념","paragraphs":["설명",{"text":"강조할 문장","bold":true}],"table":{"headers":["개념","정의"],"rows":[["도함수","변화율"]]},"image_path":"generated/known-image.png"}]}
```

Top-level `paragraphs` and `tables` are also supported. Section fields are optional. Image input supports PNG/JPEG. The DOCX remains editable. `office_inspect` returns paragraph indices and tables. `office_edit` takes `path`, `name`, `operations`; supported operations are `{"op":"set_paragraph","index":0,"text":"새 제목"}` and `{"op":"append_paragraph","text":"추가 문단"}`.

## XLSX

```json
{"sheets":[{"name":"학습 계획","rows":[["단원","문제 수"],["미분",2],["적분",4],["합계",{"formula":"SUM(B2:B3)"}]],"header_rows":1,"freeze_rows":1,"widths":[28,16],"formats":[{"range":"B2:B4","format":"0"}],"merged":[]}]}
```

Cells are strings, numbers, booleans, null, or `{ "formula":"SUM(B2:B3)", "format":"0.00" }`. Plain strings beginning with `=` remain text. Formula caches are calculated before saving. `office_edit` uses `{"op":"set_cell","sheet":"학습 계획","cell":"B2","value":10}`; a formula is supplied inside `value` as an object. Preserve unrelated cells and tabs. A sheet may contain `images:[{"path":"generated/chart.png","from_col":3,"from_row":1,"to_col":11,"to_row":22}]` to embed a PNG/JPEG. Indices are zero-based; this is an image, not a native editable Excel chart.

## PPTX

```json
{"slides":[{"title":"핵심 개념","body":["첫 설명","두 번째 설명"],"notes":"발표자 설명"},{"title":"구조도","shapes":[{"type":"rectangle","x":70,"y":180,"width":500,"height":260,"fill":"EDF3FB"},{"type":"text","x":100,"y":220,"width":440,"height":180,"size":28,"text":"개념의 연결","bold":true},{"type":"image","x":680,"y":180,"width":480,"height":300,"path":"generated/known-image.png"}]}]}
```

The canvas is 1280 × 720. Shape types: text, image, rectangle, ellipse. Colors are six-digit hex without `#`. Text sizes are 12–72 for custom shapes. Slide title/body defaults are already sized for presentations. `office_inspect` returns slide and text shape indices; `office_edit` uses `{"op":"set_text","slide":0,"shape":0,"text":"고친 제목"}`.

## Native verification

`office_render(path, pages="1-3")` returns actual page images and a managed `preview_pdf` path. Continue with further batches. `etl_read_file(preview_pdf)` can check extracted output text; visual checks still require images. Rendering uses installed Microsoft Office and preserves the source. A runtime without Office must report the unavailable renderer accurately. Successful creation/structural parsing alone is not a successful visual review.
