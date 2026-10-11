package handlers

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"testing"

	"github.com/google/uuid"

	"github.com/sourcelocation/rondo/server/internal/jobs"
)

// An account's export is a Rondo file: a manifest, then each table as an array of its rows.
func TestExportIsARondoFile(t *testing.T) {
	s := server(t)
	ctx := context.Background()
	me := person(t, s, "Ann", false)
	someone := person(t, s, "Bea", false)
	d := deck(t, s, me, "Spanish")
	deck(t, s, someone, "Not mine")
	template := uuid.Must(uuid.NewV7())
	note := uuid.Must(uuid.NewV7())
	if _, err := s.Pool.Exec(ctx, `INSERT INTO templates (id, owner_id, name, kind, def, v, seq)
		VALUES ($1, $2, 'Words', 1, '{"fields":[{"id":1,"name":"Word","kind":"text"}],"cards":[]}', 1, 1)`, template, me); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pool.Exec(ctx, `INSERT INTO notes (id, owner_id, deck_id, template_id, fields, tags, search_text, v, seq)
		VALUES ($1, $2, $3, $4, '{"1":"hola"}', 'greeting', 'hola', 1, 1)`, note, me, d, template); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pool.Exec(ctx, `INSERT INTO events (id, user_id, subject_id, card, kind, at, stability, seq)
		VALUES ($1, $2, $3, 1, 7, 1000, 2.5, 1)`, uuid.Must(uuid.NewV7()), me, note); err != nil {
		t.Fatal(err)
	}
	picture := []byte("a picture")
	sum := sha256.Sum256(picture)
	hash := hex.EncodeToString(sum[:])
	if err := s.Blobs.Put(ctx, "media/"+hash, "image/png", bytes.NewReader(picture)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pool.Exec(ctx, `INSERT INTO media (hash, size, mime, uploaded_by, verified) VALUES ($1, 9, 'image/png', $2, true)`,
		sum[:], me); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Pool.Exec(ctx, `INSERT INTO note_media (note_id, hash) VALUES ($1, $2)`, note, sum[:]); err != nil {
		t.Fatal(err)
	}

	files := export(t, s, me)
	var manifest jobs.Manifest
	read(t, files, "rondo.json", &manifest)
	if manifest.Format != 1 || manifest.Kind != "account" || manifest.ExportedAt == 0 {
		t.Fatalf("manifest %+v", manifest)
	}
	if manifest.Media[hash] != "image/png" || !bytes.Equal(files["media/"+hash], picture) {
		t.Fatalf("media %v", manifest.Media)
	}
	var decks []map[string]any
	read(t, files, "decks.json", &decks)
	if len(decks) != 1 || decks[0]["name"] != "Spanish" {
		t.Fatalf("decks %+v", decks)
	}
	var notes []map[string]any
	read(t, files, "notes.json", &notes)
	fields, _ := notes[0]["fields"].(map[string]any)
	if len(notes) != 1 || fields["1"] != "hola" || notes[0]["tags"] != "greeting" {
		t.Fatalf("notes %+v", notes)
	}
	if bytes.Contains(files["notes.json"], []byte("search_text")) {
		t.Fatal("a server-only column was exported")
	}
	var events []map[string]any
	read(t, files, "events.json", &events)
	if len(events) != 1 || events[0]["stability"] != 2.5 {
		t.Fatalf("events %+v", events)
	}
	if string(files["smart_decks.json"]) != "[]" {
		t.Fatalf("an empty table: %s", files["smart_decks.json"])
	}
}

// A column only the server keeps never leaves it: an export names the columns a Rondo file has.
func TestExportLeavesServerColumnsOut(t *testing.T) {
	s := server(t)
	me := person(t, s, "Ann", false)
	deck(t, s, me, "Spanish")
	if _, err := s.Pool.Exec(context.Background(), "ALTER TABLE decks ADD COLUMN secret text DEFAULT 'x'"); err != nil {
		t.Fatal(err)
	}
	if files := export(t, s, me); bytes.Contains(files["decks.json"], []byte("secret")) {
		t.Fatal("exported a column outside the Rondo file")
	}
}

func export(t *testing.T, s *Server, user uuid.UUID) map[string][]byte {
	t.Helper()
	var out bytes.Buffer
	w := &jobs.ExportWorker{Pool: s.Pool, Blobs: s.Blobs}
	if err := w.Write(context.Background(), user, &out); err != nil {
		t.Fatal(err)
	}
	z, err := zip.NewReader(bytes.NewReader(out.Bytes()), int64(out.Len()))
	if err != nil {
		t.Fatal(err)
	}
	files := map[string][]byte{}
	for _, f := range z.File {
		r, err := f.Open()
		if err != nil {
			t.Fatal(err)
		}
		files[f.Name], _ = io.ReadAll(r)
		r.Close()
	}
	return files
}

func read(t *testing.T, files map[string][]byte, name string, into any) {
	t.Helper()
	data, ok := files[name]
	if !ok {
		t.Fatalf("no %s", name)
	}
	if err := json.Unmarshal(data, into); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
}
