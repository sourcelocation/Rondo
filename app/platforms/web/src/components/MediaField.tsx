import { AudioLines, ImagePlus, Mic, Square, X } from "lucide-react";
import { useRef, useState, type PointerEvent, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { s, useMedia, type Box } from "@/rondo";
import { cn } from "cn";

type Bytes = (bytes: Int8Array, mime: string) => void;

const KEEP = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

/** Images go in as WebP at most 2048 px wide, unless already small and in a format kept as is. */
async function encode(file: File): Promise<[Int8Array, string]> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 2048 / Math.max(bitmap.width, bitmap.height));
  if (KEEP.has(file.type) && (scale === 1 || file.type === "image/gif") && file.size < 1_000_000)
    return [new Int8Array(await file.arrayBuffer()), file.type];
  const canvas = new OffscreenCanvas(Math.round(bitmap.width * scale), Math.round(bitmap.height * scale));
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  const blob = await canvas.convertToBlob({ type: "image/webp", quality: 0.85 });
  return [new Int8Array(await blob.arrayBuffer()), "image/webp"];
}

const take = async (file: File, onBytes: Bytes) =>
  file.type.startsWith("image/")
    ? onBytes(...(await encode(file)))
    : onBytes(new Int8Array(await file.arrayBuffer()), file.type);

/** A hidden file input and a way to open it. */
function useFile(accept: string, onFile: (f: File) => void) {
  const input = useRef<HTMLInputElement>(null);
  const element = (
    <input
      ref={input}
      type="file"
      accept={accept}
      hidden
      onChange={(e) => {
        const file = e.target.files?.[0];
        if (file) onFile(file);
        e.target.value = "";
      }}
    />
  );
  return [element, () => input.current?.click()] as const;
}

/** An empty place on the card that takes a file: dropped, pasted or chosen. */
function DropZone({
  accept,
  onFile,
  onClick,
  children,
}: {
  accept: string;
  onFile: (f: File) => void;
  onClick?: () => void;
  children: ReactNode;
}) {
  const [over, setOver] = useState(false);
  const pick = (files: FileList | undefined) => {
    const file = files ? [...files].find((f) => f.type.startsWith(accept)) : undefined;
    if (file) onFile(file);
  };
  return (
    <div
      role={onClick ? "button" : "group"}
      tabIndex={0}
      className={cn(
        "flex min-h-28 w-full flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed p-4 text-muted-foreground transition-colors outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50",
        over ? "border-ring bg-accent" : "border-border",
        onClick && "cursor-pointer hover:bg-accent/50",
      )}
      onClick={onClick}
      onKeyDown={(e) => onClick && (e.key === "Enter" || e.key === " ") && (e.preventDefault(), onClick())}
      onDragOver={(e) => (e.preventDefault(), setOver(true))}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => (e.preventDefault(), setOver(false), pick(e.dataTransfer.files))}
      onPaste={(e) => pick(e.clipboardData.files)}
    >
      {children}
    </div>
  );
}

const Actions = ({ onReplace, onRemove }: { onReplace: () => void; onRemove: () => void }) => (
  <div className="flex justify-center gap-1">
    <Button type="button" variant="ghost" size="sm" onClick={onReplace}>
      {s.replace}
    </Button>
    <Button type="button" variant="ghost" size="sm" onClick={onRemove}>
      {s.remove}
    </Button>
  </div>
);

export function ImageField({
  label,
  value,
  onBytes,
  onClear,
}: {
  label: string;
  value: string;
  onBytes: Bytes;
  onClear: () => void;
}) {
  const url = useMedia(value || null);
  const [input, choose] = useFile("image/*", (f) => take(f, onBytes));
  return (
    <div className="flex flex-col items-center gap-2">
      {input}
      {value ? (
        <>
          {url && <img src={url} alt="" className="max-h-60 rounded-lg" />}
          <Actions onReplace={choose} onRemove={onClear} />
        </>
      ) : (
        <DropZone accept="image/" onFile={(f) => take(f, onBytes)} onClick={choose}>
          <ImagePlus className="size-6" />
          <span className="text-sm font-medium">{label}</span>
          <span className="text-xs">{s.dropFile}</span>
        </DropZone>
      )}
    </div>
  );
}

export function AudioField({
  label,
  value,
  onBytes,
  onClear,
}: {
  label: string;
  value: string;
  onBytes: Bytes;
  onClear: () => void;
}) {
  const url = useMedia(value || null);
  const [recorder, setRecorder] = useState<MediaRecorder | null>(null);
  const [problem, setProblem] = useState(false);
  const [input, choose] = useFile("audio/*", (f) => take(f, onBytes));
  const record = async () => {
    setProblem(false);
    try {
      const r = new MediaRecorder(await navigator.mediaDevices.getUserMedia({ audio: true }));
      const parts: Blob[] = [];
      r.ondataavailable = (e) => parts.push(e.data);
      r.onstop = async () => {
        r.stream.getTracks().forEach((t) => t.stop());
        const blob = new Blob(parts, { type: r.mimeType.split(";")[0] });
        onBytes(new Int8Array(await blob.arrayBuffer()), blob.type);
      };
      r.start();
      setRecorder(r);
    } catch {
      setProblem(true);
    }
  };
  return (
    <div className="flex flex-col items-center gap-2">
      {input}
      {value ? (
        <>
          {url && <audio controls src={url} />}
          <Actions onReplace={choose} onRemove={onClear} />
        </>
      ) : (
        <DropZone accept="audio/" onFile={(f) => take(f, onBytes)}>
          <AudioLines className="size-6" />
          <span className="text-sm font-medium">{label}</span>
          <div className="flex gap-2">
            {recorder ? (
              <Button
                type="button"
                size="sm"
                variant="destructive"
                onClick={() => (recorder.stop(), setRecorder(null))}
              >
                <Square /> {s.stop}
              </Button>
            ) : (
              <Button type="button" size="sm" variant="secondary" onClick={record}>
                <Mic /> {s.record}
              </Button>
            )}
            <Button type="button" size="sm" variant="ghost" onClick={choose}>
              {s.chooseAudio}
            </Button>
          </div>
          {problem && (
            <span role="alert" className="text-xs text-destructive">
              {s.noMicrophone}
            </span>
          )}
        </DropZone>
      )}
    </div>
  );
}

/** An image with masks drawn by dragging; each mask is a card of its own. */
export function OcclusionField({
  label,
  image,
  masks,
  onBytes,
  onMasks,
  onClear,
}: {
  label: string;
  image: string | null;
  masks: Box[];
  onBytes: Bytes;
  onMasks: (flat: number[]) => void;
  onClear: () => void;
}) {
  const url = useMedia(image);
  const area = useRef<HTMLDivElement>(null);
  const [input, choose] = useFile("image/*", (f) => take(f, onBytes));
  const [draft, setDraft] = useState<{ x: number; y: number; w: number; h: number; ox: number; oy: number } | null>(
    null,
  );
  const flat = (list: { group: number; x: number; y: number; w: number; h: number }[]) =>
    list.flatMap((m) => [m.group, m.x, m.y, m.w, m.h]);
  const at = (e: PointerEvent) => {
    const r = area.current!.getBoundingClientRect();
    return [
      Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)),
      Math.min(1, Math.max(0, (e.clientY - r.top) / r.height)),
    ] as const;
  };
  if (!image)
    return (
      <>
        {input}
        <DropZone accept="image/" onFile={(f) => take(f, onBytes)} onClick={choose}>
          <ImagePlus className="size-6" />
          <span className="text-sm font-medium">{label}</span>
          <span className="text-xs">{s.dropFile}</span>
        </DropZone>
      </>
    );
  return (
    <div className="flex flex-col items-center gap-2">
      {input}
      <p className="text-xs text-muted-foreground">{s.drawMasks}</p>
      {url && (
        <div
          ref={area}
          className="relative cursor-crosshair touch-none select-none"
          onPointerDown={(e) => {
            if (e.target !== e.currentTarget.firstChild) return;
            const [x, y] = at(e);
            e.currentTarget.setPointerCapture(e.pointerId);
            setDraft({ x, y, w: 0, h: 0, ox: x, oy: y });
          }}
          onPointerMove={(e) => {
            if (!draft) return;
            const [x, y] = at(e);
            setDraft({
              ...draft,
              x: Math.min(x, draft.ox),
              y: Math.min(y, draft.oy),
              w: Math.abs(x - draft.ox),
              h: Math.abs(y - draft.oy),
            });
          }}
          onPointerUp={() => {
            if (draft && draft.w > 0.01 && draft.h > 0.01)
              onMasks(flat([...masks, { group: Math.max(0, ...masks.map((m) => m.group)) + 1, ...draft }]));
            setDraft(null);
          }}
        >
          <img src={url} alt="" draggable={false} className="max-h-[60vh] rounded-lg" />
          {[...masks, ...(draft ? [{ ...draft, group: 0 }] : [])].map((m, i) => (
            <div
              key={i}
              className="group absolute rounded-sm border-2 border-primary bg-primary/40"
              style={{ left: `${m.x * 100}%`, top: `${m.y * 100}%`, width: `${m.w * 100}%`, height: `${m.h * 100}%` }}
            >
              {m.group > 0 && (
                <button
                  type="button"
                  aria-label={s.remove}
                  onPointerDown={(e) => e.stopPropagation()}
                  onClick={() => onMasks(flat(masks.filter((x) => x !== m)))}
                  className="absolute -top-2.5 -right-2.5 grid size-5 place-items-center rounded-full bg-background shadow [@media(pointer:fine)]:hidden [@media(pointer:fine)]:group-hover:grid"
                >
                  <X className="size-3" />
                </button>
              )}
            </div>
          ))}
        </div>
      )}
      <div className="flex justify-center gap-1">
        {masks.length > 0 && (
          <Button type="button" variant="ghost" size="sm" onClick={() => onMasks([])}>
            {s.clearMasks}
          </Button>
        )}
        <Button type="button" variant="ghost" size="sm" onClick={choose}>
          {s.replace}
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={onClear}>
          {s.remove}
        </Button>
      </div>
    </div>
  );
}
