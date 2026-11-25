// Installed in a fresh JSContext for each cell. Native callbacks are captured
// here and removed from the global object before any model code runs.
(function(native, metadata) {
  const stringify = JSON.stringify;
  const parse = JSON.parse;
  const exitSignal = Object.freeze({});
  const string = value => typeof value === "string" ? value : (stringify(value) ?? String(value));
  const emit = value => native("emit", stringify(value));
  const text = value => emit({type: "input_text", text: string(value)});
  function image(value) {
    const url = typeof value === "string" ? value : value?.image_url ?? (value?.data ? `data:${value.mimeType};base64,${value.data}` : null);
    if (typeof url !== "string") throw new TypeError("image expects a URL or image content block");
    emit({type: "input_image", image_url: url});
  }
  function audio(value) {
    const url = typeof value === "string" ? value : value?.audio_url ?? (value?.data ? `data:${value.mimeType};base64,${value.data}` : null);
    if (typeof url !== "string") throw new TypeError("audio expects a URL or audio content block");
    emit({type: "input_audio", audio_url: url});
  }
  function content(value) {
    if (typeof value !== "string" && value?.[Symbol.iterator]) { for (const item of value) content(item); return; }
    if (typeof value === "string") return text(value);
    switch (value?.type) {
      case "text": return text(value.text);
      case "input_text": return text(value.text);
      case "image": return image(value);
      case "input_image": return image(value);
      case "audio": return audio(value);
      case "input_audio": return audio(value);
      case "resource_link": return text(value);
      case "resource": return text(value.resource?.text ?? value);
      default: throw new TypeError("Unsupported content block");
    }
  }
  const tools = Object.create(null);
  for (let index = 0; index < metadata.length; index++) {
    Object.defineProperty(tools, metadata[index].name, {value: input => native("tool", index, stringify(input ?? null)), enumerable: true});
  }
  Object.assign(globalThis, {
    tools: Object.freeze(tools),
    ALL_TOOLS: Object.freeze(metadata.map(item => Object.freeze(item))),
    text, image, audio, content,
    generatedImage: value => { image(value); if (value?.output_hint) text(value.output_hint); },
    store: (key, value) => native("store", String(key), stringify(value ?? null)),
    load: key => { const value = native("load", String(key)); return value === null ? undefined : parse(value); },
    notify: value => native("notify", string(value)),
    yield_control: async () => native("yield"),
    setTimeout: (callback, delay = 0) => native("timer", callback, Number(delay)),
    clearTimeout: id => native("clearTimer", Number(id)),
    exit: () => { throw exitSignal; }
  });
  // Promise failures are always observed, including exceptions before an await.
  return source => (async () => {
    try { await (new (Object.getPrototypeOf(async function(){}).constructor)(source))(); native("done", null); }
    catch (error) { native("done", error === exitSignal ? null : String(error?.stack ?? error)); }
  })();
})
