import init, { type Database, type Sqlite3Static } from "@sqlite.org/sqlite-wasm";

// SQLDelight's web worker protocol over SQLite in WebAssembly: the app's database in OPFS, or an
// Anki collection held in memory, sent first as { action: "load", bytes }.
type Request = { id: number; action: string; sql?: string; params?: unknown[]; bytes?: Uint8Array };

const transaction: Record<string, string> = {
  begin_transaction: "BEGIN",
  end_transaction: "COMMIT",
  rollback_transaction: "ROLLBACK",
};
let database: Promise<Database> | undefined;

/** The app's database. A tab that just handed Rondo over can hold its file for a moment more. */
async function stored(sqlite3: Sqlite3Static): Promise<Database> {
  const options = { name: "rondo", forceReinitIfPreviouslyFailed: true };
  for (let attempt = 1; ; attempt++) {
    try {
      return new (await sqlite3.installOpfsSAHPoolVfs(options)).OpfsSAHPoolDb("/rondo.db");
    } catch (e) {
      if (attempt === 40) throw e;
      await new Promise((wait) => setTimeout(wait, 250));
    }
  }
}

async function open(bytes?: Uint8Array): Promise<Database> {
  const sqlite3 = await init();
  if (!bytes) return stored(sqlite3);
  const db = new sqlite3.oo1.DB();
  const { capi } = sqlite3;
  const data = sqlite3.wasm.allocFromTypedArray(bytes);
  db.checkRc(
    capi.sqlite3_deserialize(
      db.pointer!,
      "main",
      data,
      bytes.length,
      bytes.length,
      capi.SQLITE_DESERIALIZE_FREEONCLOSE | capi.SQLITE_DESERIALIZE_RESIZEABLE,
    ),
  );
  return db;
}

onmessage = async ({ data: r }: MessageEvent<Request>) => {
  if (r.action === "load") return void (database = open(r.bytes));
  try {
    const db = await (database ??= open());
    const sql = transaction[r.action] ?? r.sql ?? "";
    const values = db.exec({ sql, bind: r.params as never, rowMode: "array", returnValue: "resultRows" });
    // A write answers with the rows it changed, as SQLDelight reads them.
    postMessage({
      id: r.id,
      results: { values: values.length || /^\s*(select|with|pragma)/i.test(sql) ? values : [[db.changes()]] },
    });
  } catch (e) {
    postMessage({ id: r.id, error: String(e) });
  }
};
