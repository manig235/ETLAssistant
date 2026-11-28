import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import 'katex/dist/katex.min.css';

/** Normalize the two common LaTeX delimiters, leaving fenced and inline code intact. */
export function normalizeMath(source: string) {
  return source.split(/(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`)/g).map((part, i) => i % 2 ? part : part
    .replace(/\\\[([\s\S]*?)\\\]/g, (_, math: string) => `\n$$\n${math.trim()}\n$$\n`)
    .replace(/\\\(([\s\S]*?)\\\)/g, (_, math: string) => `$${math}$`)).join('');
}

export function Markdown({text}: {text: string}) {
  return <div className="markdown"><ReactMarkdown skipHtml remarkPlugins={[remarkGfm, remarkMath]}
    rehypePlugins={[[rehypeKatex, {trust: false, throwOnError: false, strict: 'ignore', maxExpand: 1000}]]}
    components={{
      a: ({href, children}) => <a href={href} onClick={e => {e.preventDefault(); if (href) void window.youngmini.control('openLink', href);}}>{children}</a>,
      img: ({alt}) => <span className="image-label">{alt || '이미지'}</span>,
    }}>{normalizeMath(text)}</ReactMarkdown></div>;
}
