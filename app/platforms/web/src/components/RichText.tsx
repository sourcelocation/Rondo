import { BlockMath, InlineMath } from "@tiptap/extension-mathematics";
import Highlight from "@tiptap/extension-highlight";
import Subscript from "@tiptap/extension-subscript";
import Superscript from "@tiptap/extension-superscript";
import { TableKit } from "@tiptap/extension-table";
import { Placeholder } from "@tiptap/extensions";
import { EditorContent, InputRule, Mark, Node, useEditor, useEditorState, type Editor } from "@tiptap/react";
import { BubbleMenu } from "@tiptap/react/menus";
import StarterKit from "@tiptap/starter-kit";
import {
  Bold,
  Brackets,
  Code,
  Highlighter,
  Italic,
  List,
  ListOrdered,
  Sigma,
  Subscript as Sub,
  Superscript as Sup,
  Table,
  Underline,
  Eye,
} from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Input } from "@/components/ui/input";
import { ask } from "@/components/kit";
import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Toggle } from "@/components/ui/toggle";
import { Editor as Documents, s } from "@/rondo";

const documents = Documents.getInstance();

/** The next cloze number in the document. */
function nextCloze(editor: Editor) {
  let max = 0;
  editor.state.doc.descendants((n) =>
    n.marks.forEach((m) => m.type.name === "cloze" && (max = Math.max(max, m.attrs.n as number))),
  );
  return max + 1;
}

const toggleCloze = (editor: Editor) =>
  editor.isActive("cloze")
    ? editor.chain().focus().unsetMark("cloze").run()
    : editor
        .chain()
        .focus()
        .setMark("cloze", { n: nextCloze(editor) })
        .run();

const Cloze = Mark.create({
  name: "cloze",
  addAttributes: () => ({
    n: {
      default: 1,
      parseHTML: (e) => Number(e.getAttribute("data-cloze")),
      renderHTML: (a) => ({ "data-cloze": a.n, title: `${s.cloze} ${a.n}` }),
    },
    hint: {
      default: null,
      parseHTML: (e) => e.getAttribute("data-hint"),
      renderHTML: (a) => (a.hint ? { "data-hint": a.hint } : {}),
    },
  }),
  parseHTML: () => [{ tag: "span[data-cloze]" }],
  renderHTML: ({ HTMLAttributes }) => ["span", HTMLAttributes, 0],
  addKeyboardShortcuts() {
    return { "Mod-Shift-c": () => toggleCloze(this.editor) };
  },
});

const Ruby = Node.create({
  name: "ruby",
  group: "inline",
  inline: true,
  atom: true,
  addAttributes: () => ({ base: { default: "" }, reading: { default: "" } }),
  parseHTML: () => [
    {
      tag: "ruby",
      getAttrs: (e) => ({ base: e.firstChild?.textContent ?? "", reading: e.querySelector("rt")?.textContent ?? "" }),
    },
  ],
  renderHTML: ({ node }) => ["ruby", {}, node.attrs.base, ["rt", {}, node.attrs.reading]],
});

/** `$x$` as in the markup: no space inside either dollar. */
const Inline = InlineMath.extend({
  addInputRules() {
    return [
      new InputRule({
        find: /(^|[^$\\])\$([^\s$](?:[^$\n]*[^\s$\\])?)\$$/,
        handler: ({ state, range, match }) =>
          void state.tr.replaceWith(range.from + match[1]!.length, range.to, this.type.create({ latex: match[2] })),
      }),
    ];
  },
});

/** A paragraph of `$$x$$` becomes display math. */
const Display = BlockMath.extend({
  addInputRules() {
    return [
      new InputRule({
        find: /^\$\$([^$]+)\$\$$/,
        handler: ({ state, range, match }) =>
          void state.tr.replaceWith(range.from - 1, range.to, this.type.create({ latex: match[1] })),
      }),
    ];
  },
});

function Tool({ on, label, icon, run }: { on?: boolean; label: string; icon: ReactNode; run: () => void }) {
  return (
    <Toggle
      size="sm"
      pressed={!!on}
      aria-label={label}
      title={label}
      onPressedChange={run}
      onMouseDown={(e) => e.preventDefault()}
    >
      {icon}
    </Toggle>
  );
}

const MARKS = ["bold", "italic", "underline", "highlight", "subscript", "superscript", "code", "cloze"] as const;

/** A cloze's hint, and showing its words again; offered while the caret is inside it. */
function ClozeHint({ editor }: { editor: Editor }) {
  const current = (editor.getAttributes("cloze").hint as string | null) ?? "";
  const [hint, setHint] = useState(current);
  useEffect(() => setHint(current), [current]);
  const apply = () =>
    editor
      .chain()
      .extendMarkRange("cloze")
      .updateAttributes("cloze", { hint: hint || null })
      .run();
  return (
    <div className="flex w-72 flex-col gap-2 rounded-lg border bg-popover p-3 shadow-md">
      <Input
        aria-label={s.clozeHintLabel}
        placeholder={s.clozeHintLabel}
        value={hint}
        onChange={(e) => setHint(e.target.value)}
        onBlur={apply}
        onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), apply(), editor.commands.focus())}
      />
      <Button
        size="sm"
        variant="ghost"
        onMouseDown={(e) => e.preventDefault()}
        onClick={() => editor.chain().focus().extendMarkRange("cloze").unsetMark("cloze").run()}
      >
        <Eye /> {s.showAgain}
      </Button>
    </div>
  );
}

/**
 * A text field written as it shows on the card: formatting appears over selected text, a cloze's
 * hint while the caret is in it. [size] and [center] follow the card; [lang]: the deck's language.
 */
export function RichText({
  value,
  onChange,
  cloze,
  placeholder,
  lang,
  size = "normal",
  center = true,
  autoFocus = false,
  disabled = false,
}: {
  value: string;
  onChange: (markup: string) => void;
  cloze?: boolean;
  placeholder?: string;
  lang?: string | null;
  size?: string;
  center?: boolean;
  autoFocus?: boolean;
  disabled?: boolean;
}) {
  const last = useRef(value);
  const self = useRef<Editor | null>(null);
  const editMath = async (latex: string, pos: number, inline: boolean) => {
    const next = await ask(s.math, latex);
    const e = self.current;
    if (next == null || !e) return;
    if (inline) e.chain().setNodeSelection(pos).updateInlineMath({ latex: next }).focus().run();
    else e.chain().setNodeSelection(pos).updateBlockMath({ latex: next }).focus().run();
  };
  const editor = useEditor({
    editable: !disabled,
    autofocus: autoFocus ? "end" : false,
    extensions: [
      StarterKit.configure({
        heading: false,
        blockquote: false,
        codeBlock: false,
        horizontalRule: false,
        strike: false,
        link: false,
      }),
      Placeholder.configure({ placeholder: placeholder ?? "" }),
      Highlight,
      Subscript,
      Superscript,
      TableKit,
      Cloze,
      Ruby,
      Inline.configure({
        katexOptions: { throwOnError: false },
        onClick: (n, pos) => editMath(n.attrs.latex, pos, true),
      }),
      Display.configure({
        katexOptions: { throwOnError: false },
        onClick: (n, pos) => editMath(n.attrs.latex, pos, false),
      }),
    ],
    content: JSON.parse(documents.toDocument(value)),
    editorProps: {
      attributes: {
        class: cn(
          "content tiptap rounded-md px-2 py-1 transition-colors hover:bg-accent/40 focus:bg-accent/40",
          `piece-${size}`,
          center && "text-center",
        ),
        ...(lang ? { lang, spellcheck: "true" } : {}),
      },
    },
    onUpdate: ({ editor }) => {
      last.current = documents.toMarkup(JSON.stringify(editor.getJSON()));
      onChange(last.current);
    },
  });
  self.current = editor;
  useEffect(() => {
    if (editor && value !== last.current) {
      last.current = value;
      editor.commands.setContent(JSON.parse(documents.toDocument(value)));
    }
  }, [editor, value]);
  const on = useEditorState({
    editor,
    selector: ({ editor: e }) =>
      Object.fromEntries([...MARKS, "bulletList", "orderedList"].map((m) => [m, e?.isActive(m) ?? false])),
  });
  if (!editor) return null;
  const c = () => editor.chain().focus();
  const reading = async () => {
    const { from, to } = editor.state.selection;
    const base = editor.state.doc.textBetween(from, to);
    const r = base && (await ask(s.ruby));
    if (r)
      c()
        .insertContentAt({ from, to }, { type: "ruby", attrs: { base, reading: r } })
        .run();
  };
  const math = async () => {
    const { from, to } = editor.state.selection;
    const latex = editor.state.doc.textBetween(from, to) || (await ask(s.math));
    if (latex) c().insertContentAt({ from, to }, { type: "inlineMath", attrs: { latex } }).run();
  };
  return (
    <div className="relative w-full">
      <BubbleMenu
        editor={editor}
        shouldShow={({ editor: e, state }) => e.isEditable && !state.selection.empty}
        className="z-50 flex flex-wrap gap-0.5 rounded-lg border bg-popover p-1 shadow-md"
      >
        <Tool on={on?.bold} label={s.bold} icon={<Bold />} run={() => c().toggleBold().run()} />
        <Tool on={on?.italic} label={s.italic} icon={<Italic />} run={() => c().toggleItalic().run()} />
        <Tool on={on?.underline} label={s.underline} icon={<Underline />} run={() => c().toggleUnderline().run()} />
        <Tool on={on?.highlight} label={s.highlight} icon={<Highlighter />} run={() => c().toggleHighlight().run()} />
        {cloze && (
          <Tool on={on?.cloze} label={`${s.cloze} (⌘⇧C)`} icon={<Brackets />} run={() => toggleCloze(editor)} />
        )}
        <Tool on={on?.code} label={s.codeMark} icon={<Code />} run={() => c().toggleCode().run()} />
        <Tool on={on?.subscript} label={s.subscript} icon={<Sub />} run={() => c().toggleSubscript().run()} />
        <Tool on={on?.superscript} label={s.superscript} icon={<Sup />} run={() => c().toggleSuperscript().run()} />
        <Tool label={s.math} icon={<Sigma />} run={math} />
        <Tool label={s.ruby} icon={<span className="text-xs font-semibold">あ</span>} run={reading} />
        <Tool on={on?.bulletList} label={s.list} icon={<List />} run={() => c().toggleBulletList().run()} />
        <Tool
          on={on?.orderedList}
          label={s.numberedList}
          icon={<ListOrdered />}
          run={() => c().toggleOrderedList().run()}
        />
        <Tool
          label={s.table}
          icon={<Table />}
          run={() => c().insertTable({ rows: 2, cols: 2, withHeaderRow: false }).run()}
        />
      </BubbleMenu>
      {cloze && (
        <BubbleMenu
          editor={editor}
          pluginKey="clozeHint"
          shouldShow={({ editor: e, state }) => e.isEditable && state.selection.empty && e.isActive("cloze")}
          className="z-50"
        >
          <ClozeHint editor={editor} />
        </BubbleMenu>
      )}
      <EditorContent editor={editor} />
    </div>
  );
}
