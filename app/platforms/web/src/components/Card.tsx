import katex from "katex";
import { Pause, Play, Volume2 } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { app, media, Run, s, useMedia, type CardSide, type Piece, type TextBlock } from "@/rondo";
import { cn } from "cn";

const R = Run.Companion;
const MARKS: [number, (c: ReactNode) => ReactNode][] = [
  [R.BOLD, (c) => <strong>{c}</strong>],
  [R.ITALIC, (c) => <em>{c}</em>],
  [R.UNDERLINE, (c) => <u>{c}</u>],
  [R.HIGHLIGHT, (c) => <mark>{c}</mark>],
  [R.CODE, (c) => <code>{c}</code>],
  [R.SUB, (c) => <sub>{c}</sub>],
  [R.SUP, (c) => <sup>{c}</sup>],
  [R.REVEALED, (c) => <span className="cloze-shown">{c}</span>],
];

const tex = (src: string, display: boolean) => ({
  __html: katex.renderToString(src, { displayMode: display, throwOnError: false }),
});

function RunView({ r }: { r: Run }) {
  if (r.kind === R.BREAK) return <br />;
  if (r.kind === R.BLANK) return <span className="cloze">{r.text}</span>;
  let content: ReactNode = r.text;
  if (r.kind === R.MATH) content = <span dangerouslySetInnerHTML={tex(r.text, false)} />;
  if (r.kind === R.RUBY)
    content = (
      <ruby>
        {r.text}
        <rt>{r.reading}</rt>
      </ruby>
    );
  return <>{MARKS.reduce<ReactNode>((c, [bit, wrap]) => (r.marks & bit ? wrap(c) : c), content)}</>;
}

export const Runs = ({ runs }: { runs: Run[] }) => (
  <>
    {runs.map((r, i) => (
      <RunView key={i} r={r} />
    ))}
  </>
);

export function Blocks({ blocks }: { blocks: TextBlock[] }) {
  return blocks.map((b, i) => {
    if (b.kind === "math") return <div key={i} dangerouslySetInnerHTML={tex(b.tex, true)} />;
    if (b.kind === "ul" || b.kind === "ol") {
      const List = b.kind;
      return (
        <List key={i} className="inline-block text-left">
          {b.items.map((item, j) => (
            <li key={j}>
              <Runs runs={item} />
            </li>
          ))}
        </List>
      );
    }
    if (b.kind === "table")
      return (
        <table key={i}>
          <tbody>
            {b.rows.map((row, j) => (
              <tr key={j}>
                {row.map((cell, k) =>
                  j === 0 && b.header ? (
                    <th key={k}>
                      <Runs runs={cell} />
                    </th>
                  ) : (
                    <td key={k}>
                      <Runs runs={cell} />
                    </td>
                  ),
                )}
              </tr>
            ))}
          </tbody>
        </table>
      );
    return (
      <p key={i}>
        <Runs runs={b.runs} />
      </p>
    );
  });
}

function Audio({ hash, autoPlay }: { hash: string; autoPlay?: boolean }) {
  const url = useMedia(hash);
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  return (
    <div className="flex justify-center">
      <audio
        ref={audio}
        src={url}
        autoPlay={autoPlay}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
      />
      <Button
        variant="secondary"
        size="icon-lg"
        className="rounded-full"
        aria-label={s.play}
        disabled={!url}
        onClick={() => (audio.current?.paused ? audio.current.play() : audio.current?.pause())}
      >
        {playing ? <Pause /> : <Play />}
      </Button>
    </div>
  );
}

function Image({ hash, children }: { hash: string; children?: ReactNode }) {
  const url = useMedia(hash);
  if (!url)
    return (
      <div className="mx-auto grid h-40 w-64 place-items-center rounded-lg bg-muted text-sm text-muted-foreground">
        {s.missingMedia}
      </div>
    );
  return (
    <div className="relative mx-auto w-fit">
      <img src={url} alt="" className="max-h-[50vh] rounded-lg" />
      {children}
    </div>
  );
}

/** Reads [texts] aloud one after another, in [lang]'s voice; whatever was being read stops. */
export const speak = (texts: string[], lang: string) => {
  speechSynthesis.cancel();
  for (const text of texts) speechSynthesis.speak(Object.assign(new SpeechSynthesisUtterance(text), { lang }));
};

/** What a side reads aloud: its fields in the deck's language. */
export const speechOf = (side: CardSide | null | undefined) =>
  side?.html == null ? (side?.pieces.map((p) => p.speech).filter((t): t is string => !!t) ?? []) : [];

function PieceView({
  p,
  typed,
  onType,
  onDone,
  correct,
  first,
  language,
}: {
  p: Piece;
  typed?: string | null;
  onType?: (v: string) => void;
  onDone?: () => void;
  correct?: boolean | null;
  first: boolean;
  language?: string | null;
}) {
  const align = p.center ? "text-center" : "text-left";
  switch (p.kind) {
    case "divider":
      return <hr className="border-border" />;
    case "label":
      return (
        <div className={cn("text-xs font-medium tracking-wide text-muted-foreground uppercase", align)}>{p.text}</div>
      );
    case "typein":
      if (onType)
        return (
          <Input
            autoFocus
            lang={language ?? undefined}
            value={typed ?? ""}
            onChange={(e) => onType(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), onDone?.())}
            placeholder={s.typeAnswer}
            className="mx-auto max-w-sm text-center"
          />
        );
      return (
        <div className={cn("text-center", align)}>
          {typed != null && <div className={correct ? "text-good" : "text-again line-through"}>{typed}</div>}
          {!correct && <div className="font-medium">{p.text}</div>}
          {typed != null && correct != null && (
            <div className="text-xs text-muted-foreground">{correct ? s.correct : s.notQuite}</div>
          )}
        </div>
      );
    case "image":
      return <Image hash={p.media!} />;
    case "audio":
      return <Audio hash={p.media!} autoPlay={first} />;
    case "occlusion":
      return (
        <Image hash={p.media!}>
          {p.masks.map((m, i) => (
            <div
              key={i}
              className={cn(
                "absolute rounded-sm",
                m.hidden ? (m.active ? "bg-primary" : "bg-paused") : "border-2 border-primary",
              )}
              style={{ left: `${m.x * 100}%`, top: `${m.y * 100}%`, width: `${m.w * 100}%`, height: `${m.h * 100}%` }}
            />
          ))}
        </Image>
      );
    default:
      return (
        <div className={cn(`piece-${p.size}`, align)} lang={p.foreign && language ? language : undefined}>
          <Blocks blocks={p.blocks} />
          {p.speech && language && (
            <Button variant="ghost" size="icon-sm" aria-label={s.speak} onClick={() => speak([p.speech!], language)}>
              <Volume2 />
            </Button>
          )}
        </div>
      );
  }
}

/**
 * One side of a card: Rondo's pieces, or an Anki card's own HTML. [language]: the deck's, for the
 * fields in it; [autoPlay] plays the first sound (studying, not previews).
 */
export function Side({
  side,
  typed,
  onType,
  onDone,
  correct,
  language,
  autoPlay = false,
}: {
  side: CardSide;
  typed?: string | null;
  onType?: (v: string) => void;
  onDone?: () => void;
  correct?: boolean | null;
  language?: string | null;
  autoPlay?: boolean;
}) {
  if (side.html != null) return <AnkiFrame html={side.html} css={side.css ?? ""} />;
  const firstAudio = autoPlay ? side.pieces.findIndex((p) => p.kind === "audio") : -1;
  return (
    <div className="content flex flex-col gap-5">
      {side.pieces.map((p, i) => (
        <PieceView
          key={i}
          p={p}
          typed={typed}
          onType={onType}
          onDone={onDone}
          correct={correct}
          first={i === firstAudio}
          language={language}
        />
      ))}
    </div>
  );
}

/** The card itself: the same raised surface while studying, writing and previewing. */
export const CardSurface = ({
  small = false,
  className,
  ...props
}: React.ComponentProps<"article"> & { small?: boolean }) => (
  <article
    className={cn(
      "relative flex w-full flex-col justify-center rounded-2xl bg-card shadow-sm",
      small ? "min-h-32 gap-3 p-4" : "min-h-72 p-8 sm:p-12",
      className,
    )}
    {...props}
  />
);

const MEDIA = /rondo-media:([0-9a-f]{64})/g;

/** Anki's HTML as it was, without its scripts; media links point at this device's files. */
function AnkiFrame({ html, css }: { html: string; css: string }) {
  const [doc, setDoc] = useState<string>();
  useEffect(() => {
    let live = true;
    const hashes = [...new Set([...html.matchAll(MEDIA)].map((m) => m[1]!))];
    Promise.all(hashes.map((h) => media(app, h))).then((urls) => {
      if (!live) return;
      const url = (h: string) => urls[hashes.indexOf(h)] ?? "";
      const body = html
        .replace(/\[sound:rondo-media:([0-9a-f]{64})]/g, (_, h: string) => `<audio controls src="${url(h)}"></audio>`)
        .replace(MEDIA, (_, h: string) => url(h));
      const night = document.documentElement.dataset.theme === "dark" ? " nightMode night_mode" : "";
      setDoc(
        `<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:8px}img{max-width:100%}${css}</style><body class="card${night}">${body}</body>`,
      );
    });
    return () => {
      live = false;
    };
  }, [html, css]);
  // The frame grows with its content, as its pictures load too.
  const frame = useRef<HTMLIFrameElement>(null);
  const fit = () => {
    const body = frame.current?.contentDocument?.body;
    if (!body || !frame.current) return;
    const resize = () => frame.current && (frame.current.style.height = `${body.scrollHeight}px`);
    resize();
    new ResizeObserver(resize).observe(body);
  };
  return (
    <iframe
      ref={frame}
      title="card"
      sandbox="allow-same-origin"
      srcDoc={doc}
      className="w-full border-0"
      onLoad={fit}
    />
  );
}
